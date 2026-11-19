package dev.taskforge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableScheduling
class Scheduling {}

/** SQL-polling phase-1 mode retained for comparison; use worker for Redis distribution. */
@Component
@ConditionalOnProperty(name="taskforge.role",havingValue="local-worker")
class LocalWorker {
    final JobStore jobs;
    final JobRunner runner;
    final Settings settings;
    LocalWorker(JobStore jobs,JobRunner runner,Settings settings) { this.jobs=jobs;this.runner=runner;this.settings=settings; }
    @Scheduled(fixedDelay=500)
    void tick() {
        var job=jobs.claimNext(settings.instanceId());
        if(job!=null)runner.run(job,settings.instanceId());
    }
}
