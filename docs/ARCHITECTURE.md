# Architecture

## Overview

`io.github.trysac.pekko_streams_ingest` implements a single-pass, demand-driven line ingestion pipeline built on Apache Pekko Streams 2.0.0 (`org.apache.pekko:pekko-stream_3`).

## Pipeline Stages

1. **Byte Source (`FileIO.fromPath` or `Source[ByteString, ?]`)**:
   Reads `chunkSize`-byte chunks starting at the offset loaded from `CheckpointStore`. If the stored offset is negative or greater than the file size, the run fails immediately with `IngestException` (`checkpoint_mismatch`). If the offset equals the file size, the run completes immediately with an empty `IngestReport` at `endOffset = startOffset`.

2. **Line Framing (`LineFramingStage`)**:
   Frames incoming chunks on `\n`, strips an optional trailing `\r` (supporting both LF and CRLF files), and emits the final line even if it has no trailing newline. Each framed line tracks its exact byte length (including delimiter bytes) and its exact `endOffset`. Lines whose payload exceeds `maxLineBytes` fail the stage immediately with `IngestException` (`line_too_long`) and the 1-based line number.

3. **UTF-8 Decoding, Parsing & Error Budget (`ParseAndBudgetStage`)**:
   Decodes each framed line with a strict `UTF-8` `CharsetDecoder` (`CodingErrorAction.REPORT`). Malformed byte sequences or `Left(reason)` results from `LineParser.parse` are routed to the `deadLetter` callback as `Rejected(lineNumber, reason, prefix)` where both `reason` and `prefix` are capped at 200 characters. When rejected lines exceed `maxErrors`, the stage fails with `IngestException` (`too_many_errors`).

4. **Byte- and Count-Weighted Batching (`WeightedBatchStage`)**:
   Accumulates parsed records into `Batch[A]` values that never exceed `batchMaxRecords` or `batchMaxBytes` (except when a single record exceeds `batchMaxBytes`, in which case it forms a single-record batch of its own). Line numbers are 1-based relative to the starting checkpoint offset.

5. **Ordered Concurrent Sink Writes & Checkpointing**:
   `mapAsync(config.parallelism)(sink.write)` executes up to `parallelism` batch writes concurrently while emitting completed batches downstream strictly in file order. Downstream `runFold` commits `checkpoint.save(batch.endOffset)` and emits `BatchCommitted` before advancing, guaranteeing that a crash or sink failure leaves the checkpoint at the last contiguous committed batch.
