# TaskForge resume entry

- Built a Java CSV analytics platform with PostgreSQL, Redis Streams, and independently scalable workers.
- Fenced worker commits with renewable leases and isolated attempt outputs to prevent stale result publication.
- Raised local throughput from 1.42 to 5.23 jobs/s on one worker processing 500K-row CSVs by reusing a compiled regex.

For the first bullet, walk through the [multi-process batch](evidence/phase2-e2e.json). For the second, explain the pause after object upload and before SQL completion in the [fault tests](../src/test/java/dev/taskforge/PlatformIntegrationTest.java). For the third, describe how calculation time dominated input reads, then explain the warmup, three trials and shared-host limitations in the [measurements](BENCHMARKS.md).
