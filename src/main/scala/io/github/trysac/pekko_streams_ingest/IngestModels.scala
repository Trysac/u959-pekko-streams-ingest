package io.github.trysac.pekko_streams_ingest

/** Summary metrics returned when an ingestion run completes.
  *
  * @param lines
  *   total lines read in this run (1-based relative to the start offset)
  * @param records
  *   total valid records delivered to the sink in this run
  * @param rejected
  *   total lines rejected due to malformed UTF-8 or parser errors in this run
  * @param batches
  *   total batches committed to the sink in this run
  * @param bytes
  *   total input bytes consumed in this run (including line delimiters)
  * @param endOffset
  *   final byte offset at the end of this run (`startOffset + bytes`)
  */
final case class IngestReport(
    lines: Long,
    records: Long,
    rejected: Long,
    batches: Long,
    bytes: Long,
    endOffset: Long,
)

/** Dead-letter record emitted when a line fails UTF-8 decoding or [[LineParser.parse]].
  *
  * @param lineNumber
  *   1-based line number relative to the run's starting offset
  * @param reason
  *   rejection reason from the decoder or parser (truncated to at most 200 characters)
  * @param prefix
  *   first at most 200 characters of the rejected line
  */
final case class Rejected(
    lineNumber: Long,
    reason: String,
    prefix: String,
)

/** Structured observability events emitted during an ingestion run.
  *
  * Events carry only batch and run metadata and never include raw record content.
  */
sealed trait IngestEvent

/** Emitted after a batch has been written to the sink and its end offset saved to the
  * [[CheckpointStore]].
  *
  * @param batchIndex
  *   1-based batch index
  * @param firstLine
  *   1-based line number of the first record in the batch
  * @param lastLine
  *   1-based line number of the last record in the batch
  * @param endOffset
  *   committed byte offset after this batch
  * @param records
  *   number of records in the batch
  */
final case class BatchCommitted(
    batchIndex: Long,
    firstLine: Long,
    lastLine: Long,
    endOffset: Long,
    records: Int,
) extends IngestEvent

/** Emitted once when the ingestion run finishes cleanly.
  *
  * @param report
  *   the final [[IngestReport]] of the run
  */
final case class RunFinished(report: IngestReport) extends IngestEvent
