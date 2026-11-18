package io.github.trysac.pekko_streams_ingest

/** Configuration for a bounded line-ingestion pipeline.
  *
  * Every parameter must be at least 1; otherwise construction throws an [[IngestException]] with
  * code `'invalid_config'`.
  *
  * @param chunkSize
  *   byte chunk size read from files (default 65,536)
  * @param maxLineBytes
  *   maximum allowed bytes in a single line before the newline delimiter (default 1,048,576)
  * @param batchMaxRecords
  *   maximum number of parsed records in a single [[Batch]] (default 1,000)
  * @param batchMaxBytes
  *   maximum sum of UTF-8 line payload bytes in a single [[Batch]] (default 4,194,304)
  * @param parallelism
  *   number of concurrent batch writes in flight to the [[BatchSink]] (default 4)
  * @param maxErrors
  *   maximum number of rejected lines permitted before aborting with `'too_many_errors'` (default
  *   100)
  */
final case class IngestConfig(
    chunkSize: Int = 65_536,
    maxLineBytes: Int = 1_048_576,
    batchMaxRecords: Int = 1_000,
    batchMaxBytes: Long = 4_194_304L,
    parallelism: Int = 4,
    maxErrors: Long = 100L,
) {
  if (chunkSize < 1) {
    throw IngestException.invalidConfig(s"chunkSize must be >= 1, got $chunkSize")
  }
  if (maxLineBytes < 1) {
    throw IngestException.invalidConfig(s"maxLineBytes must be >= 1, got $maxLineBytes")
  }
  if (batchMaxRecords < 1) {
    throw IngestException.invalidConfig(s"batchMaxRecords must be >= 1, got $batchMaxRecords")
  }
  if (batchMaxBytes < 1L) {
    throw IngestException.invalidConfig(s"batchMaxBytes must be >= 1, got $batchMaxBytes")
  }
  if (parallelism < 1) {
    throw IngestException.invalidConfig(s"parallelism must be >= 1, got $parallelism")
  }
  if (maxErrors < 1L) {
    throw IngestException.invalidConfig(s"maxErrors must be >= 1, got $maxErrors")
  }
}

/** Companion factory for [[IngestConfig]]. */
object IngestConfig {

  /** Validates and constructs an [[IngestConfig]] where every parameter is at least 1.
    *
    * @throws IngestException
    *   with code `'invalid_config'` when any parameter is less than 1
    */
  def validated(
      chunkSize: Int = 65_536,
      maxLineBytes: Int = 1_048_576,
      batchMaxRecords: Int = 1_000,
      batchMaxBytes: Long = 4_194_304L,
      parallelism: Int = 4,
      maxErrors: Long = 100L,
  ): IngestConfig =
    IngestConfig(
      chunkSize = chunkSize,
      maxLineBytes = maxLineBytes,
      batchMaxRecords = batchMaxRecords,
      batchMaxBytes = batchMaxBytes,
      parallelism = parallelism,
      maxErrors = maxErrors,
    )
}
