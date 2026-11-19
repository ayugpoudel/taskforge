package dev.taskforge;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RedisQueue {
    public static final String STREAM="taskforge:jobs", GROUP="taskforge-workers";
    public record Delivery(String messageId,UUID jobId) {}
    final StringRedisTemplate redis;
    final io.micrometer.core.instrument.MeterRegistry metrics;
    final StreamOperations<String,String,String> streams;
    volatile boolean initialized;
    String pendingCursor;
    public RedisQueue(StringRedisTemplate redis,io.micrometer.core.instrument.MeterRegistry metrics) { this.redis=redis;this.metrics=metrics;this.streams=redis.opsForStream(); }
    public synchronized void initialize() {
        if(initialized)return;
        // MKSTREAM is handled by Spring Data's group creation command.
        try { streams.createGroup(STREAM,ReadOffset.from("0-0"),GROUP); }
        catch(Exception e) {
            if(!hasMessage(e,"BUSYGROUP"))throw e;
        }
        initialized=true;
    }
    private static boolean hasMessage(Throwable e,String text) {
        for(Throwable t=e;t!=null;t=t.getCause())if(t.getMessage()!=null&&t.getMessage().contains(text))return true;
        return false;
    }
    public String publish(UUID id) {
        String message=streams.add(STREAM,Map.of("jobId",id.toString())).getValue();
        metrics.counter("taskforge.queue.publications").increment();return message;
    }
    void recovered(int count) { if(count>0)metrics.counter("taskforge.jobs.recovered").increment(count); }
    public Delivery receive(String consumer) {
        try {
            initialize();
            var records=streams.read(Consumer.from(GROUP,consumer),StreamReadOptions.empty().count(1),
                    StreamOffset.create(STREAM,ReadOffset.lastConsumed()));
            if(records==null || records.isEmpty())return null;
            var r=records.getFirst();
            try { return new Delivery(r.getId().getValue(),UUID.fromString(r.getValue().get("jobId"))); }
            catch(RuntimeException e) { acknowledge(r.getId().getValue());return null; }
        } catch(RuntimeException e) { initialized=false;throw e; }
    }
    /** Scan bounded pending pages. Database state, not queue idle age, permits recovery. */
    public synchronized Delivery recoverPending(JobStore jobs,String consumer) {
        initialize();
        org.springframework.data.domain.Range<String> range=pendingCursor==null
                ?org.springframework.data.domain.Range.unbounded()
                :org.springframework.data.domain.Range.of(org.springframework.data.domain.Range.Bound.exclusive(pendingCursor),
                    org.springframework.data.domain.Range.Bound.unbounded());
        var pending=streams.pending(STREAM,GROUP,range,100);
        if(pending==null||pending.isEmpty()) { pendingCursor=null;return null; }
        for(var entry:pending) {
            pendingCursor=entry.getId().getValue();
            var records=streams.range(STREAM,org.springframework.data.domain.Range.closed(pendingCursor,pendingCursor));
            if(records==null||records.isEmpty()) { streams.acknowledge(STREAM,GROUP,entry.getId());continue; }
            UUID id;
            try { id=UUID.fromString(records.getFirst().getValue().get("jobId")); }
            catch(RuntimeException invalid) { acknowledge(pendingCursor);continue; }
            var state=jobs.getInternal(id);
            if(state==null||Set.of("SUCCEEDED","FAILED","CANCELLED").contains(state.status())) {
                acknowledge(pendingCursor);continue;
            }
            if(!jobs.recoverable(id))continue;
            var claimed=streams.claim(STREAM,GROUP,consumer,java.time.Duration.ZERO,entry.getId());
            if(claimed!=null&&!claimed.isEmpty()) { metrics.counter("taskforge.queue.redeliveries").increment();return new Delivery(pendingCursor,id); }
        }
        return null;
    }
    public void acknowledge(String id) {
        streams.acknowledge(STREAM,GROUP,RecordId.of(id));
        // Exactly one application consumer group; terminal messages have no remaining readers.
        streams.delete(STREAM,RecordId.of(id));
    }
}
