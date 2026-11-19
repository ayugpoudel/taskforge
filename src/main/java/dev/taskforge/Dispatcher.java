package dev.taskforge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="taskforge.role",havingValue="dispatcher")
class Dispatcher {
    final JobStore jobs;
    final RedisQueue queue;
    Dispatcher(JobStore jobs,RedisQueue queue) { this.jobs=jobs;this.queue=queue; }
    boolean publishOne(Runnable afterPublish) {
        var event=jobs.leaseOutbox();
        if(event==null)return false;
        queue.publish(event.jobId());
        afterPublish.run(); // deterministic post-publish/pre-mark crash boundary in tests
        jobs.published(event);
        return true;
    }
    @Scheduled(fixedDelay=500)
    void tick() {
        try {
            jobs.recoverExpired();
            jobs.reconcileNotifications();
            for(int i=0;i<100&&publishOne(()->{});i++) { /* bounded batch */ }
        } catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("event=dispatcher_unavailable error={}",e.getClass().getSimpleName()); }
    }
}
