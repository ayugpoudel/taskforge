CREATE TABLE owner_scheduling (
    owner_id varchar(120) PRIMARY KEY
);
CREATE INDEX jobs_owner_running ON jobs(owner_id,lease_expires_at) WHERE status='RUNNING';
