# Segmented (multi-connection) downloads

Package `com.almus.ytdownloader.engine`. Nothing here depends on Android; the engine still reaches the network only through `HttpTransport`.

## When it is used

The engine always starts like before: one plain GET. It switches to segments only when **all** of this is true (`SegmentationPolicy`):

| Requirement | Why |
|---|---|
| Plain `200` with a known `Content-Length` of at least 4 MiB | smaller files lose more to the extra requests and the merge than they gain |
| `Accept-Ranges: bytes` announced | the server says it supports ranges |
| no `Content-Encoding` | byte positions must mean file positions |
| a strong `ETag` or a `Last-Modified` | goes into `If-Range`, so every segment is guaranteed to come from the same version of the file |
| more than one connection allowed right now | network, settings, other running downloads |

Otherwise the download runs exactly as before (single connection, Range resume). The announcement is not trusted blindly: **every** ranged answer is checked by `RangeValidator` before its first byte is written (206; `Content-Range` equal to the requested range; same total; `Content-Length` equal to the range length; same validator; no content coding; not multipart). A server that ignores Range or answers wrongly makes the download fall back to one connection; a changed file restarts it.

## Pieces

- `SegmentPlanner` splits the file (1 to 16 MiB segments, about four per connection, at most 1024 segments).
- `SegmentedDownloader` runs the workers. The first request of every segment also carries `If-Range`. Each segment is its own file `dl-ID.segN`.
- `SegmentManifest` (`dl-ID.segs`) records the plan, the validator and, per segment, the number of flushed bytes and their CRC-32. It is replaced atomically and protected by its own checksum; an unreadable manifest means "start fresh", never a guess. No Room change is needed.
- Checkpoints: a segment is flushed and recorded every 4 MiB and when it ends, pauses or is cancelled. After a restart only checkpointed bytes are trusted; the CRC of the prefix is re-checked.
- Recovery (per segment): a connection that breaks resumes inside the same segment; a missing, short or checksum-failing segment is deleted and downloaded again on its own (at most 3 times per segment); HTTP 408/429/5xx are retried with the existing `RetryPolicy` (Retry-After honoured); 429/503 also halve the connection count.
- `SegmentMerger`: every segment is verified (size, then CRC-32 while copied) before it becomes part of the result. Segment 0 becomes the head of the merge file by rename, the others are appended in order; the merge file is flushed in 32 MiB batches, then the journal is saved, then the merged segment files are deleted. A crash leaves either the segment or its merged copy. Finally `dl-ID.merge` is atomically renamed to `dl-ID.part`; from there the normal verify-and-publish path runs. Extra disk space needed: about one segment plus 32 MiB.
- `TempFiles`: one place that knows every temporary file (`.part`, `.segN`, `.segs`, `.segs.tmp`, `.merge`). Removed on completion, cancel, non-resumable failure, restart-fresh, fallback, and by a sweep at start-up for anything that belongs to no unfinished download.

## Connection count

`ConnectionPolicy.ceiling` (network-aware): Ethernet 8, unmetered Wi-Fi 6, mobile data or metered 3, unknown 4; at most 2 on links under 3 Mbit/s and 3 under 10 Mbit/s; divided among downloads that run together; overridden by `EngineSettings.maxConnectionsPerDownload` (0 = automatic, capped at 16); 1 when `segmentedDownloads` is off or the network is offline. It is re-read about every 50 ms, so a change from Wi-Fi to mobile data lowers the number of connections within one segment.

`AdaptiveConnections` starts at 2 and adds one connection per 1.5 s window only while the measured total speed rises by at least 15 %. Without a gain it takes the connection back and waits before trying again; after a connection error it drops one, after 429/503 it halves and waits 30 s.

Speed work: progress of all connections is aggregated and reported once per interval (one engine-lock acquisition instead of one per connection), 128 KiB buffers, finished segments' connections are reused by OkHttp, `OkHttpTransport` raises OkHttp's default of 5 requests per host to 16 and uses HTTP/1.1 for segments so that they really are separate TCP connections (HTTP/2 would multiplex them over one).

## Guarantees and limits

- The published file always has exactly the announced size, every byte came from a validated 206 of one version of the file, and every segment passed a CRC-32 comparison with what was written.
- Without a hash from the server nothing can detect a server that itself sends wrong bytes of the right length. If a hash is ever available, verify it in `SegmentedDownloader.run` after the merge.
- On very fast links (tens of MB/s from a single connection) segmenting costs more than it gains because of the flushes and the merge copy; see `BENCHMARKS.md`.
- Settings `segmentedDownloads` and `maxConnectionsPerDownload` exist in `EngineSettings` with defaults; no screen controls them yet.
