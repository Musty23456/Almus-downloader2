# Download engine benchmarks

Original engine (0.14.0) against the segmented engine, same scenarios, same machine, same code path (`engine/bench/DownloadBenchmark.kt` compiled once against each engine). Median of 3 runs after one warm-up; every run's output is compared by SHA-256 with the source file.

| Scenario | Setup | Original | Segmented | Speed-up | Requests (orig / new) | Byte-exact |
|---|---|---|---|---|---|---|
| per-connection-limit-2MBps | Server caps each connection at 2 MB/s (typical CDN), 20 ms latency, 24 MiB | 13.05 s (1.93 MB/s) | 5.13 s (4.9 MB/s) | **2.54x** | 1 / 17 | yes |
| per-connection-limit-8MBps | Server caps each connection at 8 MB/s, 20 ms latency, 48 MiB | 7.32 s (6.88 MB/s) | 3.65 s (13.78 MB/s) | **2.00x** | 1 / 17 | yes |
| shared-link-cap-10MBps | The whole link is 10 MB/s whatever the connection count, 32 MiB (no gain possible) | 3.42 s (9.82 MB/s) | 3.53 s (9.5 MB/s) | **0.97x** | 1 / 17 | yes |
| unthrottled-loopback | No limits at all, 64 MiB (pure overhead of segmenting and merging) | 0.24 s (275.03 MB/s) | 0.59 s (113.08 MB/s) | **0.41x** | 1 / 17 | yes |
| flaky-connections | 4 MB/s per connection, every request is cut after 1.5 MiB, 32 MiB | 10.09 s (3.33 MB/s) | 9.54 s (3.52 MB/s) | **1.06x** | 23 / 33 | yes |
| server-without-range-support | Server never supports Range, 4 MB/s, 16 MiB (must not regress) | 4.62 s (3.63 MB/s) | 4.61 s (3.64 MB/s) | **1.00x** | 1 / 1 | yes |
| small-file | 1 MiB file, 20 ms latency (must not regress) | 0.31 s (3.39 MB/s) | 0.30 s (3.45 MB/s) | **1.02x** | 1 / 1 | yes |

## Reading the numbers

- **Per-connection limits (what CDNs and many hosts do):** 2.0x to 2.5x faster. The adaptive tuner starts at 2 connections and adds one per 1.5 s window only while speed rises, so a 5 s download does not reach the ceiling of 4; longer downloads gain more (the test servers' per-connection speed scales linearly, so the ideal at 4 connections is 4x).
- **Shared link cap:** no gain is possible and none is lost (0.97x, within noise): the tuner stops adding connections.
- **Flaky connections, no Range support, small file:** no regression (1.06x, 1.00x, 1.02x). Servers without Range support and files under 4 MiB take the unchanged single-connection path.
- **Unthrottled loopback (a 64 MiB file served at memory speed): 0.41x, i.e. slower.** Segmenting pays for crash safety with disk flushes (every segment and every checkpoint) and the merge copy, which this sandbox's disk does at roughly 200 MB/s. When a single connection already delivers more than about 100 MB/s, use `segmentedDownloads = false` or a lower ceiling; realistic mobile and Wi-Fi links are far below that, but this is the real cost of the design. A possible follow-up is merging finished leading segments while the rest still downloads.

## Method and honesty notes

- Local server throttled per connection or per link, 20 ms added latency, over loopback with the JDK HTTP client. The Android build tooling and OkHttp were not available in the build sandbox, so the benchmark (and all tests) run the real engine on a JVM with a JDK-based `HttpTransport` stand-in (`tools/jvm-harness/shim`). Absolute numbers on a phone will differ; the ratios under throttling are what carry over.
- Raw results: `docs/benchmarks/original-engine.jsonl`, `docs/benchmarks/segmented-engine.jsonl`.
- Reproduce: `app/src/test/java/com/almus/ytdownloader/engine/bench/run-benchmark.sh <original-project> <new-project> 3 <outdir>` (see `tools/jvm-harness`).
