ALTER TABLE jobs ADD COLUMN notified_at timestamptz;
CREATE INDEX jobs_notify ON jobs(notified_at,created_at) WHERE status='QUEUED';
