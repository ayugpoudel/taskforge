package dev.taskforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class JobRunner {
    static final class OwnershipLost extends RuntimeException {}
    final JobStore jobs;
    final ObjectStorage storage;
    final ObjectMapper json;
    final Map<String,JobProcessor> processors;
    final Settings settings;
    public JobRunner(JobStore jobs,ObjectStorage storage,ObjectMapper json,List<JobProcessor> processors,Settings settings) {
        this.jobs=jobs;this.storage=storage;this.json=json;this.settings=settings;
        var registry=new HashMap<String,JobProcessor>();
        for(var p:processors)if(registry.put(p.type(),p)!=null)throw new IllegalArgumentException("Duplicate processor");
        this.processors=Map.copyOf(registry);
    }
    void run(JobStore.Job job,String worker) {
        if(job==null)return;
        var lost=new AtomicBoolean(false);
        var renewer=Executors.newSingleThreadScheduledExecutor();
        var renewal=renewer.scheduleAtFixedRate(() -> {
            if(lost.get())return;
            try { if(!jobs.heartbeat(job,worker))lost.set(true); }
            catch(RuntimeException unavailable) { lost.set(true); }
        },Math.max(1,settings.leaseSeconds()/3),Math.max(1,settings.leaseSeconds()/3),TimeUnit.SECONDS);
        Runnable checkpoint=()-> { if(lost.get()||Thread.currentThread().isInterrupted())throw new OwnershipLost(); };
        String key="reports/"+job.id()+"/"+job.generation()+"-"+UUID.randomUUID()+".json";
        java.security.MessageDigest digest;
        try { digest=java.security.MessageDigest.getInstance("SHA-256"); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try(var in=new java.security.DigestInputStream(new LimitedInputStream(storage.read(job.inputKey()),52_428_800),digest)) {
            var report=processors.get("CSV_ANALYTICS").process(in,checkpoint);
            checkpoint.run();
            if(!java.util.HexFormat.of().formatHex(digest.digest()).equals(job.inputSha256()))
                throw new CsvAnalytics.InvalidCsv("Input integrity verification failed.");
            if(!jobs.candidateOutput(job,worker,key))throw new OwnershipLost();
            storage.putReport(key,json.writeValueAsBytes(report));
            checkpoint.run();
            if(!jobs.complete(job,worker,key)) {
                try { storage.delete(key); } catch(Exception ignored) { /* persistent attempt points to cleanup candidate */ }
            }
        } catch(OwnershipLost ignored) {
            // Never commit/fail after losing a lease. Dispatcher recovers durable state.
        } catch(CsvAnalytics.InvalidCsv e) { jobs.fail(job,worker,"INVALID_CSV",true); }
        catch(Exception e) { jobs.fail(job,worker,"PROCESSING_ERROR",false); }
        finally { renewal.cancel(true);renewer.shutdownNow(); }
    }
}
