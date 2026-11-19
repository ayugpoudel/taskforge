# Executed benchmark summary

Median of three trials. Each trial verifies 18 reports from a 500,000-row, 6,833,355-byte CSV. One vCPU, one slot, 512 MiB per worker.

| Version | Workers | Median jobs/s | Trial range | Median trial completion p95 | Median API p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 1 | 1.418 | 1.373–1.509 | 12.102 s | 17.3 ms |
| Baseline | 2 | 2.731 | 2.624–2.940 | 6.273 s | 28.6 ms |
| Baseline | 4 | 4.471 | 4.373–4.670 | 3.886 s | 22.5 ms |
| Precompiled regex | 1 | 5.234 | 4.857–5.318 | 3.254 s | 24.5 ms |
| Precompiled regex | 2 | 6.337 | 6.308–8.274 | 2.756 s | 28.3 ms |
| Precompiled regex | 4 | 11.582 | 10.087–11.965 | 1.484 s | 17.7 ms |

Observed one-worker throughput ratio after/before: 3.692×. Optimized one-to-four-worker ratio: 2.213×.

These are finite local batches, not sustained production capacity. Points show individual trials; ranges are not confidence intervals. Percentiles are linear interpolation from 18 job samples per trial and have limited tail precision. Uploads, setup and explicit warmup are excluded; durable admission-to-last-completion timestamps define throughput. See BENCHMARKS.md for interpretation and limitations.
