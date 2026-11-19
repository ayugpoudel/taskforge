CREATE TABLE datasets (
    id uuid PRIMARY KEY,
    owner_id varchar(120) NOT NULL,
    object_key text NOT NULL UNIQUE,
    sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 52428800),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX datasets_owner ON datasets(owner_id, created_at);
CREATE TABLE jobs (
    id uuid PRIMARY KEY,
    owner_id varchar(120) NOT NULL,
    dataset_id uuid NOT NULL REFERENCES datasets(id),
    type varchar(40) NOT NULL DEFAULT 'CSV_ANALYTICS' CHECK(type = 'CSV_ANALYTICS'),
    status varchar(24) NOT NULL DEFAULT 'QUEUED'
        CHECK(status IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
    max_attempts integer NOT NULL DEFAULT 3 CHECK(max_attempts BETWEEN 1 AND 10),
    generation bigint NOT NULL DEFAULT 0 CHECK(generation >= 0),
    lease_owner varchar(200),
    lease_expires_at timestamptz,
    cancel_requested boolean NOT NULL DEFAULT false,
    output_key text UNIQUE,
    error_code varchar(80),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deadline_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at timestamptz,
    finished_at timestamptz,
    CHECK(status <> 'SUCCEEDED' OR (output_key IS NOT NULL AND finished_at IS NOT NULL)),
    CHECK(status <> 'RUNNING' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE INDEX jobs_due ON jobs(next_attempt_at, created_at) WHERE status IN ('QUEUED','RETRY_WAIT');
CREATE INDEX jobs_expired ON jobs(lease_expires_at) WHERE status = 'RUNNING';
CREATE INDEX jobs_owner ON jobs(owner_id, created_at);
CREATE TABLE job_attempts (
    job_id uuid NOT NULL REFERENCES jobs(id),
    generation bigint NOT NULL,
    worker_id varchar(200) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    outcome varchar(40),
    output_key text,
    PRIMARY KEY(job_id, generation)
);
