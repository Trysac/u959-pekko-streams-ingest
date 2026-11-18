package io.github.trysac.pekko_streams_ingest

/** Base exception for all errors raised by the ingestion pipeline.
  *
  * @param code
  *   machine-readable error code: `'invalid_config'`, `'line_too_long'`, `'too_many_errors'`,
  *   `'sink_failed'`, `'checkpoint_mismatch'` or `'io_error'`
  * @param message
  *   human-readable summary that never includes more than 200 characters of input
  * @param lineNumber
  *   optional 1-based line number associated with the failure
  * @param cause
  *   optional underlying cause
  */
final class IngestException(
    val code: String,
    message: String,
    val lineNumber: Option[Long] = None,
    cause: Option[Throwable] = None,
) extends IllegalArgumentException(message, cause.orNull) {

  /** Creates an exception without line number or cause. */
  def this(code: String, message: String) = this(code, message, None, None)
}

/** Factory methods and error code constants for [[IngestException]]. */
object IngestException {

  /** Error code when an [[IngestConfig]] parameter is less than 1. */
  val InvalidConfig: String = "invalid_config"

  /** Error code when an input line exceeds `maxLineBytes`. */
  val LineTooLong: String = "line_too_long"

  /** Error code when rejected lines exceed `maxErrors`. */
  val TooManyErrors: String = "too_many_errors"

  /** Error code when the target [[BatchSink]] fails its returned `Future`. */
  val SinkFailed: String = "sink_failed"

  /** Error code when the stored checkpoint offset is negative or exceeds the file size. */
  val CheckpointMismatch: String = "checkpoint_mismatch"

  /** Error code when reading the source or writing the checkpoint fails with an I/O error. */
  val IoError: String = "io_error"

  /** Truncates any untrusted text snippet to at most 200 characters. */
  def truncatePrefix(text: String, maxChars: Int = 200): String = {
    if (text.length <= maxChars) text
    else text.substring(0, maxChars)
  }

  /** Constructs an `'invalid_config'` exception. */
  def invalidConfig(detail: String): IngestException =
    new IngestException(InvalidConfig, s"Invalid ingestion configuration: $detail")

  /** Constructs a `'line_too_long'` exception with its 1-based line number. */
  def lineTooLong(lineNumber: Long, maxLineBytes: Int): IngestException =
    new IngestException(
      LineTooLong,
      s"Line $lineNumber exceeds maximum length of $maxLineBytes bytes",
      Some(lineNumber),
      None,
    )

  /** Constructs a `'too_many_errors'` exception. */
  def tooManyErrors(rejectedCount: Long, maxErrors: Long, lineNumber: Long): IngestException =
    new IngestException(
      TooManyErrors,
      s"Rejected lines ($rejectedCount) exceeded maxErrors ($maxErrors) at line $lineNumber",
      Some(lineNumber),
      None,
    )

  /** Constructs a `'sink_failed'` exception wrapping the underlying sink failure. */
  def sinkFailed(batchIndex: Long, cause: Throwable): IngestException =
    new IngestException(
      SinkFailed,
      s"Sink failed while writing batch $batchIndex",
      None,
      Some(cause),
    )

  /** Constructs a `'checkpoint_mismatch'` exception. */
  def checkpointMismatch(offset: Long, fileSize: Long): IngestException =
    new IngestException(
      CheckpointMismatch,
      s"Checkpoint offset $offset is invalid for file of size $fileSize bytes",
      None,
      None,
    )

  /** Constructs an `'io_error'` exception wrapping the underlying I/O failure. */
  def ioError(detail: String, cause: Throwable): IngestException =
    new IngestException(IoError, s"I/O error during ingestion: $detail", None, Some(cause))
}
