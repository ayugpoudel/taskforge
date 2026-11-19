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
    final StreamOperations<String,String,String> streams;
    volatile boolean initialized;
    public RedisQueue(StringRedisTemplate redis) { this.redis=redis;this.streams=redis.opsForStream(); }
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
    public String publish(UUID id) { return streams.add(STREAM,Map.of("jobId",id.toString())).getValue(); }
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
    public void acknowledge(String id) {
        streams.acknowledge(STREAM,GROUP,RecordId.of(id));
        // Exactly one application consumer group; terminal messages have no remaining readers.
        streams.delete(STREAM,RecordId.of(id));
    }
}
