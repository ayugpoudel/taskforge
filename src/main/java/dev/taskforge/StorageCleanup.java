package dev.taskforge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;
import java.util.*;

/** Logical expiration is committed before external deletion. Every candidate key is immutable. */
@Component
class StorageCleanup {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final ObjectStorage storage;
    final Settings settings;
    StorageCleanup(JdbcTemplate db,TransactionTemplate tx,ObjectStorage storage,Settings settings) {
        this.db=db;this.tx=tx;this.storage=storage;this.settings=settings;
    }
    @Scheduled(fixedDelay=60_000)
    void tick() {
        if(!settings.role().equals("dispatcher"))return;
        try { cleanOnce(); }
        catch(Exception e) { LoggerFactory.getLogger(getClass()).warn("event=storage_cleanup_unavailable error={}",e.getClass().getSimpleName()); }
    }
    void cleanOnce() throws Exception {
        tx.executeWithoutResult(s -> {
            var ids=db.queryForList("SELECT id FROM datasets WHERE expired_at IS NULL AND ((NOT ready AND created_at<clock_timestamp()-? * interval '1 second') OR created_at<clock_timestamp()-? * interval '1 second') ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100",UUID.class,settings.cleanupGraceSeconds(),settings.retentionSeconds());
            for(UUID id:ids) {
                // Job creation holds FOR SHARE on this dataset until job/outbox commit.
                int active=db.queryForObject("SELECT count(*) FROM jobs WHERE dataset_id=? AND status IN ('QUEUED','RUNNING','RETRY_WAIT')",Integer.class,id);
                if(active==0)db.update("UPDATE datasets SET expired_at=clock_timestamp() WHERE id=?",id);
            }
            db.update("UPDATE jobs SET output_expired_at=clock_timestamp() WHERE id IN (SELECT id FROM jobs WHERE status='SUCCEEDED' AND output_expired_at IS NULL AND finished_at<clock_timestamp()-? * interval '1 second' FOR UPDATE SKIP LOCKED LIMIT 100)",settings.retentionSeconds());
        });
        var inputs=db.queryForList("SELECT id,object_key FROM datasets WHERE expired_at IS NOT NULL AND (last_cleanup_at IS NULL OR last_cleanup_at<clock_timestamp()-interval '1 hour') ORDER BY last_cleanup_at NULLS FIRST LIMIT 100");
        for(var input:inputs) {
            storage.delete((String)input.get("object_key"));
            db.update("UPDATE datasets SET last_cleanup_at=clock_timestamp() WHERE id=?",input.get("id"));
        }
        var outputs=db.queryForList("SELECT a.job_id,a.generation,a.output_key FROM job_attempts a JOIN jobs j ON j.id=a.job_id WHERE a.output_key IS NOT NULL AND (a.last_cleanup_at IS NULL OR a.last_cleanup_at<clock_timestamp()-interval '1 hour') AND ((a.finished_at<clock_timestamp()-? * interval '1 second' AND a.output_key IS DISTINCT FROM j.output_key) OR (a.output_key=j.output_key AND j.output_expired_at IS NOT NULL)) AND NOT(j.status='RUNNING' AND j.generation=a.generation) ORDER BY a.last_cleanup_at NULLS FIRST LIMIT 100",settings.cleanupGraceSeconds());
        for(var output:outputs) {
            storage.delete((String)output.get("output_key"));
            db.update("UPDATE job_attempts SET last_cleanup_at=clock_timestamp() WHERE job_id=? AND generation=?",output.get("job_id"),output.get("generation"));
        }
    }
}
