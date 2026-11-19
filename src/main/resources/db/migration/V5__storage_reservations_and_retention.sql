ALTER TABLE datasets ADD COLUMN ready boolean NOT NULL DEFAULT true;
ALTER TABLE datasets ALTER COLUMN ready SET DEFAULT false;
ALTER TABLE datasets ADD COLUMN expired_at timestamptz;
ALTER TABLE datasets ADD COLUMN last_cleanup_at timestamptz;
ALTER TABLE jobs ADD COLUMN output_expired_at timestamptz;
ALTER TABLE job_attempts ADD COLUMN last_cleanup_at timestamptz;
CREATE INDEX datasets_cleanup ON datasets(created_at) WHERE expired_at IS NULL;
