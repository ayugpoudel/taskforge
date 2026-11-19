package dev.taskforge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Repository
public class JobStore {
    public record Job(UUID id, String ownerId, UUID datasetId, String inputKey, String status, int attemptCount,
                      long generation, String outputKey, String errorCode, Instant createdAt,
                      Instant startedAt, Instant finishedAt, Instant deadlineAt) {}
    private static final String SELECT = "SELECT j.*,d.object_key input_key FROM jobs j JOIN datasets d ON d.id=j.dataset_id ";
    private static final RowMapper<Job> MAPPER = (r,n) -> new Job(r.getObject("id",UUID.class),r.getString("owner_id"),
            r.getObject("dataset_id",UUID.class),r.getString("input_key"),r.getString("status"),r.getInt("attempt_count"),
            r.getLong("generation"),r.getString("output_key"),r.getString("error_code"),r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("started_at")==null?null:r.getTimestamp("started_at").toInstant(),
            r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toInstant(),r.getTimestamp("deadline_at").toInstant());
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final Settings settings;
    public JobStore(JdbcTemplate db, TransactionTemplate tx, Settings settings) { this.db=db; this.tx=tx; this.settings=settings; }
    public void dataset(UUID id,String owner,String key,String sha,long bytes) {
        db.update("INSERT INTO datasets(id,owner_id,object_key,sha256,size_bytes) VALUES (?,?,?,?,?)",id,owner,key,sha,bytes);
    }
    public Job create(String owner, UUID dataset) {
        return tx.execute(s -> {
            if (db.queryForObject("SELECT count(*) FROM datasets WHERE id=? AND owner_id=?",Integer.class,dataset,owner)==0) throw missing();
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO jobs(id,owner_id,dataset_id,max_attempts,deadline_at) VALUES (?,?,?,?,clock_timestamp() + ? * interval '1 second')",
                    id,owner,dataset,settings.maxAttempts(),settings.deadlineSeconds());
            return getInternal(id);
        });
    }
    public Job get(String owner,UUID id) {
        var rows=db.query(SELECT+" WHERE j.id=? AND j.owner_id=?",MAPPER,id,owner);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }
    public Job getInternal(UUID id) {
        var rows=db.query(SELECT+" WHERE j.id=?",MAPPER,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<Map<String,Object>> attempts(String owner, UUID id) {
        get(owner,id);
        return db.queryForList("SELECT generation,started_at,finished_at,outcome FROM job_attempts WHERE job_id=? ORDER BY generation",id);
    }
    public Job claimNext(String worker) {
        return tx.execute(s -> {
            var ids=db.queryForList("SELECT id FROM jobs WHERE status='QUEUED' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",UUID.class);
            return ids.isEmpty()?null:claimLocked(ids.getFirst(),worker);
        });
    }
    public Job claim(UUID id,String worker) { return tx.execute(s -> claimLocked(id,worker)); }
    public List<UUID> notifications() {
        return db.queryForList("SELECT id FROM jobs WHERE status='QUEUED' AND (notified_at IS NULL OR notified_at<clock_timestamp()-interval '30 seconds') ORDER BY created_at LIMIT 100",UUID.class);
    }
    public void notified(UUID id) { db.update("UPDATE jobs SET notified_at=clock_timestamp() WHERE id=?",id); }
    private Job claimLocked(UUID id,String worker) {
        int changed=db.update("UPDATE jobs SET status='RUNNING',attempt_count=attempt_count+1,generation=generation+1,lease_owner=?,lease_expires_at=clock_timestamp()+? * interval '1 second',started_at=clock_timestamp() WHERE id=? AND status='QUEUED'",
                worker,settings.leaseSeconds(),id);
        if(changed==0)return null;
        Job j=getInternal(id);
        db.update("INSERT INTO job_attempts(job_id,generation,worker_id) VALUES (?,?,?)",id,j.generation(),worker);
        return j;
    }
    public boolean complete(Job job,String worker,String key) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            int n=db.update("UPDATE jobs SET status='SUCCEEDED',output_key=?,finished_at=clock_timestamp(),lease_owner=null,lease_expires_at=null WHERE id=? AND generation=? AND lease_owner=? AND status='RUNNING' AND lease_expires_at>clock_timestamp() AND deadline_at>clock_timestamp() AND NOT cancel_requested",
                    key,job.id(),job.generation(),worker);
            if(n==1)db.update("UPDATE job_attempts SET finished_at=clock_timestamp(),outcome='SUCCEEDED',output_key=? WHERE job_id=? AND generation=?",key,job.id(),job.generation());
            return n==1;
        }));
    }
    public void fail(Job job,String worker,String code) {
        tx.executeWithoutResult(s -> {
            int n=db.update("UPDATE jobs SET status='FAILED',error_code=?,finished_at=clock_timestamp(),lease_owner=null,lease_expires_at=null WHERE id=? AND generation=? AND lease_owner=? AND status='RUNNING' AND lease_expires_at>clock_timestamp()",code,job.id(),job.generation(),worker);
            if(n==1)db.update("UPDATE job_attempts SET finished_at=clock_timestamp(),outcome=? WHERE job_id=? AND generation=?",code,job.id(),job.generation());
        });
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
