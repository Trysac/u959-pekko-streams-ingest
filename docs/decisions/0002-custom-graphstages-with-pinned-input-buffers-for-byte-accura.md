# Custom GraphStages with Pinned Input Buffers for Byte-Accurate Framing and Batching

Status: accepted (generated, pending human review)

## Context

Standard Pekko Streams framing and grouping operators do not expose the exact post-delimiter byte offset alongside stripped payload bytes, nor do they combine record-count and byte-weight caps while accounting for rejected lines in the final byte offset.

## Decision

Implement dedicated GraphStages (LineFramingStage, ParseAndBudgetStage, WeightedBatchStage) with inputBuffer(initial = 1, max = 1) wired into mapAsync(config.parallelism) and a sequential runFold that updates the CheckpointStore.

## Consequences

Every batch carries exact 1-based line numbers and file byte offsets; memory in flight is strictly bounded by configuration (well under 4 MiB in the stalled-sink test); and out-of-order concurrent sink completions still checkpoint in strict file order.
