# Security & Threat Model

## Defended Threats

- **Memory Exhaustion**: The pipeline is memory-bounded. Stage input buffers are pinned to 1 element (`Attributes.inputBuffer(1, 1)`). Total memory usage is bounded by `chunkSize` + `maxLineBytes` + (`parallelism` * `batchMaxBytes`), independent of file size. 
- **Unbounded Line Length**: `LineFramingStage` inspects buffered chunks; lines exceeding `config.maxLineBytes` abort immediately with `IngestException` ('line_too_long'), preventing memory overflow from excessively long lines.
- **Data Leakage**: `IngestException` messages truncate error details to 200 characters. `Rejected` record prefixes are capped at 200 characters. `IngestEvent` (`BatchCommitted`, `RunFinished`) carries only numeric metadata and no record content.
- **Corruption of Checkpoint State**: `FileCheckpointStore` uses atomic renaming (`StandardCopyOption.ATOMIC_MOVE`) to ensure that partial writes during a crash never corrupt the checkpoint file.

## Not Defended

- This library does not provide encryption for data at rest (the input file or checkpoint file).
- This library does not sanitize output content in the `BatchSink`; it assumes the user-provided sink handles necessary data sanitization for the target destination.