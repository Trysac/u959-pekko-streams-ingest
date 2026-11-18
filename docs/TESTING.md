# Testing

## Test Suites

- **IngestSuite**: Covers acceptance, backpressure, checkpointing, and error handling.
  - Criterion 1: 3-line file ingestion and report generation.
  - Criterion 2: Backpressure verification on large streams with stalled sinks.
  - Criterion 3: Batch sizing invariants (maxRecords, maxBytes), single-record batch behavior, and ordered commits with `parallelism` > 1.
  - Criterion 4: Sink failure during batch 3 of 10 and subsequent resume.
  - Criterion 5: Dead-letter routing of rejected lines and `too_many_errors` threshold enforcement.
  - Criterion 6: Error code validation ('line_too_long', 'checkpoint_mismatch', 'invalid_config') and unterminated final line handling.
  - Criterion 7: Event emission (`BatchCommitted`, `RunFinished`) ensuring no sensitive data is leaked.
  - Edge Cases: Empty files, blank lines, CRLF, multi-byte UTF-8 character splitting, malformed UTF-8, and resume at EOF.
- **TextPropertiesSuite**: Property-based tests for `Text.normalize` (idempotency, case sensitivity) and `IngestConfig.validated` (parameter bounds validation).
- **TextSuite**: Basic unit tests for `Text.normalize` and `BlankInputException`.
- **CI Conformance**: Verifies 10 GiB stream handling with bounded memory.