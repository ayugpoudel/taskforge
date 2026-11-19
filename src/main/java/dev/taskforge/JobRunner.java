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
    final TaskMetrics metrics;
    final Settings settings;
    public JobRunner(JobStore jobs,ObjectStorage storage,ObjectMapper json,List<JobProcessor> processors,Settings settings,TaskMetrics metrics) {
        this.jobs=jobs;this.storage=storage;this.json=json;this.metrics=metrics;this.settings=settings;
        var registry=new HashMap<String,JobProcessor>();
        for(var p:processors)if(registry.put(p.type(),p)!=null)throw new IllegalArgumentException("Duplicate processor");
        this.processors=Map.copyOf(registry);
    }
    void run(JobStore.Job job,String worker) {
        if(job==null)return;
        long nanoStart=System.nanoTime();metrics.started(job);
        org.slf4j.MDC.put("job_id",job.id().toString());org.slf4j.MDC.put("worker_id",worker);
        org.slf4j.LoggerFactory.getLogger(getClass()).info("event=job_started generation={}",job.generation());
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
        try(var timed=new MeasuredInputStream(openInput(job));
            var in=new java.security.DigestInputStream(new LimitedInputStream(timed,52_428_800),digest)) {
            long calculationStart=System.nanoTime();
            Object report;
            try { report=processors.get("CSV_ANALYTICS").process(in,checkpoint); }
            finally {
                metrics.stage("input_read",timed.readNanos);
                metrics.stage("calculation",System.nanoTime()-calculationStart-timed.readNanos);
            }
            checkpoint.run();
            if(!java.util.HexFormat.of().formatHex(digest.digest()).equals(job.inputSha256()))
                throw new CsvAnalytics.InvalidCsv("Input integrity verification failed.");
            if(!jobs.candidateOutput(job,worker,key))throw new OwnershipLost();
            long serializeStart=System.nanoTime();byte[] bytes=json.writeValueAsBytes(report);
            metrics.stage("serialization",System.nanoTime()-serializeStart);
            long outputStart=System.nanoTime();
            try { storage.putReport(key,bytes); } finally { metrics.stage("output_write",System.nanoTime()-outputStart); }
            checkpoint.run();
            long finalizeStart=System.nanoTime();boolean committed;
            try { committed=jobs.complete(job,worker,key); } finally { metrics.stage("finalization",System.nanoTime()-finalizeStart); }
            if(committed) {
                metrics.succeeded(job);org.slf4j.LoggerFactory.getLogger(getClass()).info("event=job_succeeded generation={}",job.generation());
            } else {
                try { storage.delete(key); } catch(Exception ignored) { /* persistent attempt points to cleanup candidate */ }
            }
        } catch(OwnershipLost ignored) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=attempt_ownership_lost generation={}",job.generation());
            // Never commit/fail after losing a lease. Dispatcher recovers durable state.
        } catch(CsvAnalytics.InvalidCsv e) { jobs.fail(job,worker,"INVALID_CSV",true);org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=attempt_error code=INVALID_CSV"); }
        catch(Exception e) { jobs.fail(job,worker,"PROCESSING_ERROR",false);org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=attempt_error code=PROCESSING_ERROR"); }
        finally { renewal.cancel(true);renewer.shutdownNow();metrics.finished(nanoStart);org.slf4j.MDC.remove("job_id");org.slf4j.MDC.remove("worker_id"); }
    }
    private java.io.InputStream openInput(JobStore.Job job) throws Exception {
        long start=System.nanoTime();try { return storage.read(job.inputKey()); }
        finally { metrics.stage("input_open",System.nanoTime()-start); }
    }
}
