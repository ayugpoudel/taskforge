# Interview notes

TaskForge accepts CSV analysis jobs and returns a job ID while an independent worker streams the data into a report. PostgreSQL describes durable truth; a queue tells workers to look for work. Delivery can repeat, so completion must be guarded rather than relying on queue acknowledgement alone.

Phase 0: this is the accepted design, not yet a completed feature. Add code-grounded examples and measured bottlenecks as phases pass.
