package dev.taskforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableScheduling
class Scheduling {}

/** Phase 1 SQL-polling role; replaced by stream transport in phase 2. */
@Component
@ConditionalOnProperty(name="taskforge.role",havingValue="local-worker")
class LocalWorker {
    final JobStore jobs;
    final ObjectStorage storage;
    final ObjectMapper json;
    final Settings settings;
    LocalWorker(JobStore jobs,ObjectStorage storage,ObjectMapper json,Settings settings) {
        this.jobs=jobs;this.storage=storage;this.json=json;this.settings=settings;
    }
    @Scheduled(fixedDelay=500)
    void tick() throws Exception {
        var job=jobs.claimNext(settings.instanceId());
        if(job==null)return;
        String key="reports/"+job.id()+"/"+job.generation()+"-"+java.util.UUID.randomUUID()+".json";
        try(var in=storage.read(job.inputKey())) {
            var report=new CsvAnalytics().analyze(in, () -> {});
            storage.putReport(key,json.writeValueAsBytes(report));
            if(!jobs.complete(job,settings.instanceId(),key))storage.delete(key);
        } catch(CsvAnalytics.InvalidCsv e) { jobs.fail(job,settings.instanceId(),"INVALID_CSV"); }
        catch(Exception e) { jobs.fail(job,settings.instanceId(),"PROCESSING_ERROR"); }
    }
}
