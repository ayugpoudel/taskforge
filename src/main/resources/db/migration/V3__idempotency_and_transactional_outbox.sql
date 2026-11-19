ALTER TABLE jobs ADD COLUMN idempotency_key varchar(128);
ALTER TABLE jobs ADD COLUMN payload_fingerprint char(64);
CREATE UNIQUE INDEX jobs_request_idempotency ON jobs(owner_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX outbox_due ON outbox_events(available_at,created_at) WHERE published_at IS NULL;
CREATE UNIQUE INDEX outbox_one_pending_per_job ON outbox_events(job_id) WHERE published_at IS NULL;
-- Preserve pre-outbox queued jobs when upgrading a populated local system.
INSERT INTO outbox_events(id,job_id) SELECT id,id FROM jobs WHERE status='QUEUED';
