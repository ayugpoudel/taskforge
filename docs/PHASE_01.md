# Phase 1 plan: smallest useful asynchronous vertical slice

1. Implement CSV calculation as a pure streaming component with strict field/column limits. Test numbers using independently hand-calculated expectations, malformed widths, missing data, UTF-8 and quoting.
2. Add migrations for datasets/jobs/attempts. API stores immutable input then transactionally accepts a job; a separate `local-worker` role polls PostgreSQL. No heavy computation in HTTP requests.
3. MinIO stores input and JSON output; ownership-scoped endpoints upload, submit, poll and retrieve. Development bearer-token mapping establishes a stable owner boundary, with no production credential defaults.
4. Run unit and real-service integration tests before adding Redis. At this milestone, automatic crash recovery/queue distribution is not claimed.

CSV semantics: header is required, trimmed, nonblank and unique; blank cells count as missing. Wrong-width logical records are counted and skipped; only the first 20 logical record numbers are retained. Broken syntax/UTF-8 or resource-limit violation fails the job. Types are inferred across nonmissing valid records: NUMBER, BOOLEAN, STRING, EMPTY. Numeric aggregates include parseable finite decimal cells even in mixed STRING columns and report the numeric sample count. Decimal arithmetic uses DECIMAL128; extreme/overlong numeric tokens remain strings. No raw CSV contents in parsing diagnostics.

CSV semantics: rowCount excludes the header and includes wrong-width records; validRows excludes wrong-width records. Missing values are blank/whitespace cells in valid records. Numeric stats summarize numeric cells, even when other cells make the inferred column STRING. An all-missing column has inferred STRING and null numeric aggregates. Numeric values use bounded decimal input and DECIMAL128 arithmetic; reports do not promise exact unlimited-precision sums. Invalid row numbers are logical CSV record indices, not physical lines for quoted multiline fields. Structural parse/UTF-8/field-limit errors fail the job rather than attempting ambiguous resynchronization.

An initial wrong-width row is never partially aggregated. Memory grows with at most 256 columns and one bounded record, not file length. HTTP multipart files spool to disk. Phase 1 output is JSON only (the requirement allows JSON or CSV).

Local development commands (once integration acceptance passes):

```sh
./scripts/setup.sh
docker compose up -d postgres redis minio
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew bootJar
./scripts/run.sh api
# second terminal, independent process
./scripts/run.sh local-worker
```

Read an owner's random bearer token from the private `.env`; do not publish it. Upload `POST /api/datasets` as multipart `file`, then `POST /api/jobs` with `{"datasetId":"..."}`, poll `GET /api/jobs/{id}`, download `GET /api/jobs/{id}/result`. Full API examples will be made executable in the E2E script.
