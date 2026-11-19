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
    public record Job(UUID id, String ownerId, UUID datasetId, String inputKey, String inputSha256, String status, int attemptCount,
                      long generation, String outputKey, String errorCode, Instant createdAt,
                      Instant startedAt, Instant finishedAt, Instant deadlineAt, Instant availableAt) {}
    private static final String SELECT = "SELECT j.*,d.object_key input_key,d.sha256 input_sha FROM jobs j JOIN datasets d ON d.id=j.dataset_id ";
    private static final RowMapper<Job> MAPPER = (r,n) -> new Job(r.getObject("id",UUID.class),r.getString("owner_id"),
            r.getObject("dataset_id",UUID.class),r.getString("input_key"),r.getString("input_sha"),r.getString("status"),r.getInt("attempt_count"),
            r.getLong("generation"),r.getString("output_key"),r.getString("error_code"),r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("started_at")==null?null:r.getTimestamp("started_at").toInstant(),
            r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toInstant(),r.getTimestamp("deadline_at").toInstant(),r.getTimestamp("next_attempt_at").toInstant());
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final Settings settings;
    public JobStore(JdbcTemplate db, TransactionTemplate tx, Settings settings) { this.db=db; this.tx=tx; this.settings=settings; }
    public void reserveDataset(UUID id,String owner,String key,String sha,long bytes) {
        tx.executeWithoutResult(s -> {
            db.update("INSERT INTO owner_scheduling(owner_id) VALUES (?) ON CONFLICT DO NOTHING",owner);
            db.queryForList("SELECT owner_id FROM owner_scheduling WHERE owner_id=? FOR UPDATE",owner);
            long used=db.queryForObject("SELECT coalesce(sum(size_bytes),0) FROM datasets WHERE owner_id=? AND expired_at IS NULL",Long.class,owner);
            if(bytes>settings.ownerStorageBytes()-used)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            db.update("INSERT INTO datasets(id,owner_id,object_key,sha256,size_bytes) VALUES (?,?,?,?,?)",id,owner,key,sha,bytes);
        });
    }
    public void datasetReady(UUID id) { db.update("UPDATE datasets SET ready=true WHERE id=? AND expired_at IS NULL",id); }
    public void abandonDataset(UUID id) { db.update("UPDATE datasets SET expired_at=clock_timestamp() WHERE id=? AND NOT ready",id); }
    public boolean reportExpired(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT output_expired_at IS NOT NULL FROM jobs WHERE id=?",Boolean.class,id));
    }
    public Job create(String owner, UUID dataset) { return create(owner,dataset,null); }
    public Job create(String owner, UUID dataset,String key) {
        if(key!=null&&!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return tx.execute(s -> {
            var hashes=db.queryForList("SELECT sha256 FROM datasets WHERE id=? AND owner_id=? FOR SHARE",String.class,dataset,owner);
            if(hashes.isEmpty())throw missing();
            String fingerprint=dataset+":"+hashes.getFirst();
            // Store a stable SHA-256 fingerprint of the public payload, not runtime retry settings.
            String digest;
            try { digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            if(key!=null) {
                db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",owner+":"+key);
                var existing=db.queryForList("SELECT id FROM jobs WHERE owner_id=? AND idempotency_key=?",UUID.class,owner,key);
                if(!existing.isEmpty()) {
                    String previous=db.queryForObject("SELECT payload_fingerprint FROM jobs WHERE id=?",String.class,existing.getFirst());
                    if(!digest.equals(previous))throw new ResponseStatusException(HttpStatus.CONFLICT);
                    return getInternal(existing.getFirst());
                }
            }
            if(db.queryForObject("SELECT count(*) FROM datasets WHERE id=? AND ready AND expired_at IS NULL",Integer.class,dataset)==0)throw missing();
            admission();
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO jobs(id,owner_id,dataset_id,max_attempts,deadline_at,idempotency_key,payload_fingerprint) VALUES (?,?,?,?,clock_timestamp() + ? * interval '1 second',?,?)",
                    id,owner,dataset,settings.maxAttempts(),settings.deadlineSeconds(),key,digest);
            enqueue(id,0);
            return getInternal(id);
        });
    }
    private void enqueue(UUID id,long delayMillis) {
        db.update("INSERT INTO outbox_events(id,job_id,available_at) VALUES (?,?,clock_timestamp()+? * interval '1 millisecond') ON CONFLICT(job_id) WHERE published_at IS NULL DO NOTHING",
                UUID.randomUUID(),id,delayMillis);
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
        var ids=db.queryForList("SELECT id FROM jobs WHERE status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=clock_timestamp() AND deadline_at>clock_timestamp() AND NOT cancel_requested ORDER BY created_at LIMIT 100",UUID.class);
        for(UUID id:ids) { var claim=claim(id,worker);if(claim!=null)return claim; }
        return null;
    }
    public Job claim(UUID id,String worker) { return tx.execute(s -> claimLocked(id,worker)); }
    private Job claimLocked(UUID id,String worker) {
        var owners=db.queryForList("SELECT owner_id FROM jobs WHERE id=?",String.class,id);
        if(owners.isEmpty())return null;
        String owner=owners.getFirst();
        db.update("INSERT INTO owner_scheduling(owner_id) VALUES (?) ON CONFLICT DO NOTHING",owner);
        db.queryForList("SELECT owner_id FROM owner_scheduling WHERE owner_id=? FOR UPDATE",owner);
        int running=db.queryForObject("SELECT count(*) FROM jobs WHERE owner_id=? AND status='RUNNING' AND lease_expires_at>clock_timestamp()",Integer.class,owner);
        if(running>=settings.perOwnerConcurrency())return null;
        int changed=db.update("UPDATE jobs SET status='RUNNING',attempt_count=attempt_count+1,generation=generation+1,lease_owner=?,lease_expires_at=clock_timestamp()+? * interval '1 second',started_at=clock_timestamp() WHERE id=? AND status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=clock_timestamp() AND deadline_at>clock_timestamp() AND NOT cancel_requested AND attempt_count<max_attempts",
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
    public boolean candidateOutput(Job job,String worker,String key) {
        return db.update("UPDATE job_attempts a SET output_key=? WHERE job_id=? AND a.generation=? AND EXISTS (SELECT 1 FROM jobs j WHERE j.id=a.job_id AND j.generation=a.generation AND j.lease_owner=? AND j.status='RUNNING' AND j.lease_expires_at>clock_timestamp())",
                key,job.id(),job.generation(),worker)==1;
    }
    public boolean heartbeat(Job job,String worker) {
        return db.update("UPDATE jobs SET lease_expires_at=clock_timestamp()+? * interval '1 second' WHERE id=? AND generation=? AND lease_owner=? AND status='RUNNING' AND lease_expires_at>clock_timestamp() AND deadline_at>clock_timestamp() AND NOT cancel_requested",
                settings.leaseSeconds(),job.id(),job.generation(),worker)==1;
    }
    public void fail(Job job,String worker,String code) { fail(job,worker,code,code.equals("INVALID_CSV")); }
    public void fail(Job job,String worker,String code,boolean permanent) {
        tx.executeWithoutResult(s -> {
            var owned=db.queryForList("SELECT id FROM jobs WHERE id=? AND generation=? AND lease_owner=? AND status='RUNNING' AND lease_expires_at>clock_timestamp() FOR UPDATE",
                    UUID.class,job.id(),job.generation(),worker);
            if(owned.isEmpty())return;
            finishFailure(job,code,permanent);
        });
    }
    private void finishFailure(Job job,String code,boolean permanent) {
        boolean exhausted=Boolean.TRUE.equals(db.queryForObject("SELECT attempt_count>=max_attempts OR deadline_at<=clock_timestamp() FROM jobs WHERE id=?",Boolean.class,job.id()));
        boolean terminal=permanent||exhausted;
        long delay=new RetryPolicy().delay(job.attemptCount(),java.util.concurrent.ThreadLocalRandom.current()).toMillis();
        db.update("UPDATE jobs SET status=?,error_code=?,finished_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,lease_owner=null,lease_expires_at=null,next_attempt_at=clock_timestamp()+? * interval '1 millisecond',notified_at=null WHERE id=?",
                terminal?"FAILED":"RETRY_WAIT",code,terminal,delay,job.id());
        db.update("UPDATE job_attempts SET finished_at=clock_timestamp(),outcome=? WHERE job_id=? AND generation=?",code,job.id(),job.generation());
        if(!terminal)enqueue(job.id(),delay);
    }
    /** Short bounded recovery transactions; the broker is not the source of job existence. */
    public int recoverExpired() {
        return tx.execute(s -> {
            var ids=db.queryForList("SELECT id FROM jobs WHERE status='RUNNING' AND lease_expires_at<=clock_timestamp() ORDER BY lease_expires_at FOR UPDATE SKIP LOCKED LIMIT 100",UUID.class);
            for(UUID id:ids)finishFailure(getInternal(id),"LEASE_EXPIRED",false);
            return ids.size();
        });
    }
    public int reconcileNotifications() {
        return tx.execute(s -> {
            var ids=db.queryForList("SELECT id FROM jobs WHERE status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=clock_timestamp() AND deadline_at>clock_timestamp() AND (notified_at IS NULL OR notified_at<clock_timestamp()-interval '30 seconds') ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100",UUID.class);
            for(UUID id:ids)enqueue(id,0);
            return ids.size();
        });
    }
    public boolean recoverable(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT count(*)>0 FROM jobs WHERE id=? AND status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=clock_timestamp() AND deadline_at>clock_timestamp() AND NOT cancel_requested",Boolean.class,id));
    }
    public record OutboxLease(UUID id,UUID jobId,UUID token) {}
    public OutboxLease leaseOutbox() {
        return tx.execute(s -> {
            var ids=db.queryForList("SELECT id FROM outbox_events WHERE published_at IS NULL AND available_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1",UUID.class);
            if(ids.isEmpty())return null;
            UUID id=ids.getFirst(), token=UUID.randomUUID();
            db.update("UPDATE outbox_events SET lease_token=?,lease_until=clock_timestamp()+interval '5 seconds',publish_attempts=publish_attempts+1 WHERE id=?",token,id);
            UUID job=db.queryForObject("SELECT job_id FROM outbox_events WHERE id=?",UUID.class,id);
            return new OutboxLease(id,job,token);
        });
    }
    public boolean published(OutboxLease lease) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            db.queryForList("SELECT id FROM jobs WHERE id=? FOR UPDATE",lease.jobId());
            int n=db.update("UPDATE outbox_events SET published_at=clock_timestamp(),lease_token=null,lease_until=null WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() AND published_at IS NULL",lease.id(),lease.token());
            if(n==1)db.update("UPDATE jobs SET notified_at=clock_timestamp() WHERE id=?",lease.jobId());
            return n==1;
        }));
    }
    public Job replay(UUID id) {
        return tx.execute(s -> {
            admission();
            var ids=db.queryForList("SELECT id FROM jobs WHERE id=? AND status='FAILED' AND error_code<>'INVALID_CSV' FOR UPDATE",UUID.class,id);
            if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT);
            db.update("UPDATE jobs SET status='QUEUED',attempt_count=0,error_code=null,cancel_requested=false,started_at=null,finished_at=null,next_attempt_at=clock_timestamp(),notified_at=null,deadline_at=clock_timestamp()+? * interval '1 second' WHERE id=?",settings.deadlineSeconds(),id);
            enqueue(id,0);return getInternal(id);
        });
    }
    private void admission() {
        db.queryForList("SELECT pg_advisory_xact_lock(72819401)");
        int active=db.queryForObject("SELECT count(*) FROM jobs WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT')",Integer.class);
        if(active>=settings.maxActiveJobs())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }
    public Job cancel(String owner,UUID id) {
        return tx.execute(s -> {
            var ids=db.queryForList("SELECT id FROM jobs WHERE id=? AND owner_id=? FOR UPDATE",UUID.class,id,owner);
            if(ids.isEmpty())throw missing();
            Job current=getInternal(id);
            if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(current.status()))return current;
            terminal(id,"CANCELLED","CANCELLED");return getInternal(id);
        });
    }
    private void terminal(UUID id,String state,String code) {
        db.update("UPDATE jobs SET status=?,cancel_requested=?,error_code=?,finished_at=clock_timestamp(),lease_owner=null,lease_expires_at=null WHERE id=?",state,state.equals("CANCELLED"),code,id);
        db.update("UPDATE job_attempts SET finished_at=clock_timestamp(),outcome=? WHERE job_id=? AND finished_at IS NULL",code,id);
        db.update("UPDATE outbox_events SET published_at=clock_timestamp(),lease_token=null,lease_until=null WHERE job_id=? AND published_at IS NULL",id);
    }
    public int expireDeadlines() {
        return tx.execute(s -> {
            var ids=db.queryForList("SELECT id FROM jobs WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT') AND deadline_at<=clock_timestamp() ORDER BY deadline_at FOR UPDATE SKIP LOCKED LIMIT 100",UUID.class);
            for(UUID id:ids)terminal(id,"FAILED","DEADLINE_EXCEEDED");
            return ids.size();
        });
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
