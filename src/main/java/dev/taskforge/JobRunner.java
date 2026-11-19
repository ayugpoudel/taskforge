package dev.taskforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class JobRunner {
    final JobStore jobs;
    final ObjectStorage storage;
    final ObjectMapper json;
    final Map<String,JobProcessor> processors;
    public JobRunner(JobStore jobs,ObjectStorage storage,ObjectMapper json,List<JobProcessor> processors) {
        this.jobs=jobs;this.storage=storage;this.json=json;
        var registry=new HashMap<String,JobProcessor>();
        for(var p:processors)if(registry.put(p.type(),p)!=null)throw new IllegalArgumentException("Duplicate processor type");
        this.processors=Map.copyOf(registry);
    }
    public void run(JobStore.Job job,String worker) {
        String key="reports/"+job.id()+"/"+job.generation()+"-"+UUID.randomUUID()+".json";
        try(var in=storage.read(job.inputKey())) {
            var report=processors.get("CSV_ANALYTICS").process(in, () -> {});
            storage.putReport(key,json.writeValueAsBytes(report));
            if(!jobs.complete(job,worker,key))storage.delete(key);
        } catch(CsvAnalytics.InvalidCsv e) { jobs.fail(job,worker,"INVALID_CSV"); }
        catch(Exception e) { jobs.fail(job,worker,"PROCESSING_ERROR"); }
    }
}
