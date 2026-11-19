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
    @Scheduled(fixedDelay=500)
    void tick() {
        try {
            for(var id:jobs.notifications()) {
                queue.publish(id);
                jobs.notified(id);
            }
        } catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("event=dispatcher_unavailable error={}",e.getClass().getSimpleName()); }
    }
}
