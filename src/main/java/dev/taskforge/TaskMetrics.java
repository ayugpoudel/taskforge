package dev.taskforge;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
class TaskMetrics {
    final MeterRegistry registry;
    final JdbcTemplate db;
    final StringRedisTemplate redis;
    final Settings settings;
    final AtomicInteger active=new AtomicInteger();
    volatile Map<String,Double> snapshot=Map.of();
    final io.micrometer.core.instrument.Timer queueWait,execution,endToEnd;
    TaskMetrics(MeterRegistry registry,JdbcTemplate db,StringRedisTemplate redis,Settings settings) {
        this.registry=registry;this.db=db;this.redis=redis;this.settings=settings;
        queueWait=histogram("taskforge.job.queue.wait");execution=histogram("taskforge.job.execution");endToEnd=histogram("taskforge.job.end.to.end");
        Gauge.builder("taskforge.worker.active",active,AtomicInteger::get).register(registry);
        Gauge.builder("taskforge.worker.utilization",active,n->n.get()/(double)settings.workerConcurrency()).register(registry);
        if(settings.role().equals("api")) {
            for(String state:List.of("QUEUED","RUNNING","RETRY_WAIT","SUCCEEDED","FAILED","CANCELLED"))
                Gauge.builder("taskforge.jobs.current",this,m->m.value(state.toLowerCase(Locale.ROOT))).tag("status",state).register(registry);
            for(String name:List.of("submitted","succeeded","retried","failed"))
                FunctionCounter.builder("taskforge.history."+name,this,m->m.value(name+"_total")).register(registry);
            gauge("taskforge.queue.oldest.age","oldest_age");gauge("taskforge.outbox.pending","outbox_pending");
            gauge("taskforge.outbox.oldest.age","outbox_age");gauge("taskforge.redis.entries","stream_entries");gauge("taskforge.redis.pending","stream_pending");
        }
    }
    private io.micrometer.core.instrument.Timer histogram(String name) {
        return io.micrometer.core.instrument.Timer.builder(name).publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofMinutes(10)).register(registry);
    }
    private void gauge(String name,String key) { Gauge.builder(name,this,m->m.value(key)).register(registry); }
    double value(String key) { return snapshot.getOrDefault(key,Double.NaN); }
    @Scheduled(fixedDelay=2000)
    void refresh() {
        if(!settings.role().equals("api"))return;
        var values=new HashMap<String,Double>();
        try {
            var jobs=db.queryForMap("SELECT count(*) submitted_total,count(*) FILTER(WHERE status='QUEUED') queued,count(*) FILTER(WHERE status='RUNNING') running,count(*) FILTER(WHERE status='RETRY_WAIT') retry_wait,count(*) FILTER(WHERE status='SUCCEEDED') succeeded,count(*) FILTER(WHERE status='FAILED') failed,count(*) FILTER(WHERE status='CANCELLED') cancelled,coalesce(greatest(0,extract(epoch FROM clock_timestamp()-min(next_attempt_at) FILTER(WHERE status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=clock_timestamp()))),0) oldest_age FROM jobs");
            jobs.forEach((k,v)->values.put(k,((Number)v).doubleValue()));
            var attempts=db.queryForMap("SELECT count(*) FILTER(WHERE outcome='SUCCEEDED') succeeded_total,count(*) FILTER(WHERE generation>1) retried_total,count(*) FILTER(WHERE outcome IS NOT NULL AND outcome NOT IN ('SUCCEEDED','CANCELLED')) failed_total FROM job_attempts");
            attempts.forEach((k,v)->values.put(k,((Number)v).doubleValue()));
            var outbox=db.queryForMap("SELECT count(*) outbox_pending,coalesce(greatest(0,extract(epoch FROM clock_timestamp()-min(available_at) FILTER(WHERE available_at<=clock_timestamp()))),0) outbox_age FROM outbox_events WHERE published_at IS NULL");
            outbox.forEach((k,v)->values.put(k,((Number)v).doubleValue()));
        } catch(RuntimeException unavailable) { snapshot=Map.of();return; }
        try {
            Long size=redis.opsForStream().size(RedisQueue.STREAM);
            values.put("stream_entries",size==null?0d:size.doubleValue());
            var pending=redis.opsForStream().pending(RedisQueue.STREAM,RedisQueue.GROUP);
            values.put("stream_pending",pending==null?0d:(double)pending.getTotalPendingMessages());
        } catch(RuntimeException unavailable) { /* NaN means unavailable, never a fabricated zero */ }
        snapshot=Map.copyOf(values);
    }
    void stage(String stage,long nanos) {
        io.micrometer.core.instrument.Timer.builder("taskforge.job.stage").tag("stage",stage)
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofMinutes(10))
                .register(registry).record(Math.max(0,nanos),java.util.concurrent.TimeUnit.NANOSECONDS);
    }
    void started(JobStore.Job job) {
        active.incrementAndGet();
        record(queueWait,Duration.between(job.availableAt(),job.startedAt()));
    }
    void succeeded(JobStore.Job job) { record(endToEnd,Duration.between(job.createdAt(),Instant.now())); }
    void finished(long nanoStart) { active.decrementAndGet();execution.record(System.nanoTime()-nanoStart,java.util.concurrent.TimeUnit.NANOSECONDS); }
    private static void record(io.micrometer.core.instrument.Timer timer,Duration duration) { if(!duration.isNegative())timer.record(duration); }
}
