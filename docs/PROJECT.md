# Project

## Objective

Provide a deterministic, memory-bounded line-ingestion pipeline on Apache Pekko Streams.

## Scope

- Byte-accurate `\n` and `\r\n` framing with `maxLineBytes` enforcement.
- Strict UTF-8 decoding and typed `LineParser[A]` parsing.
- Dead-letter routing (`Rejected`) and configurable error budget (`maxErrors`).
- Dual-bounded batching (`batchMaxRecords` and `batchMaxBytes`).
- Ordered parallel sink commits (`mapAsync(parallelism)`) and resumable `CheckpointStore` implementations.

## Out of Scope

- Compressed archive decoding.
- Multi-line CSV quoting rules.
- Directory watching or multi-file coordination.
- Distributed cluster sharding.

## Factory run u959 (measured from API usage)

| Date (UTC) | Phase | Model | Batch | Input | Output | Cache write | Cache read | Cost (USD) |
|---|---|---|---|---|---|---|---|---|
| 2026-11-17 | generation | google/gemini-4-argon | yes | 6315 | 21931 | 0 | 0 | 0.4639 |
| 2026-11-17 | fix-1 | google/gemini-3.8-flash | no | 112317 | 2676 | 0 | 233739 | 0.1118 |
| 2026-11-17 | fix-2 | google/gemini-3.8-flash | no | 135897 | 1997 | 0 | 828799 | 0.1716 |
| 2026-11-17 | fix-3 | google/gemini-3.8-flash | no | 129317 | 1014 | 0 | 1041266 | 0.1789 |
| 2026-11-17 | fix-4 | google/gemini-3.8-flash | no | 154362 | 971 | 0 | 812547 | 0.1804 |
| 2026-11-18 | audit | google/gemini-3.8-flash | no | 93213 | 1325 | 0 | 516040 | 0.1136 |
| 2026-11-18 | docs | google/gemini-3.1-flash-lite | no | 23414 | 1617 | 0 | 0 | 0.0083 |

Total: $1.2284. Prices: vertex line, as measured by its adapter. Providers serve the current version of each model id, so the date tells which version answered; versions, launch stages and price sources per line are in the factory's model register (docs/MODELS.md).
