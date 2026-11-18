package io.github.trysac.pekko_streams_ingest

import scala.concurrent.Future

/** A byte- and count-bounded batch of parsed records ready to be written to a [[BatchSink]].
  *
  * @param index
  *   1-based sequential index of this batch within the current run
  * @param records
  *   the parsed records in file order
  * @param firstLine
  *   1-based line number of the first record in this batch (relative to the run start offset)
  * @param lastLine
  *   1-based line number of the last record in this batch (relative to the run start offset)
  * @param endOffset
  *   exact byte offset in the underlying file or stream immediately after the last line in this
  *   batch (including its line terminator if present)
  * @param bytes
  *   sum of the framed line payload bytes of the records in this batch
  */
final case class Batch[A](
    index: Long,
    records: Vector[A],
    firstLine: Long,
    lastLine: Long,
    endOffset: Long,
    bytes: Long,
)

/** Asynchronous target store for batches produced by the ingestion pipeline. */
trait BatchSink[A] {

  /** Writes `batch` to the destination store.
    *
    * @return
    *   a `Future` that completes when the batch has been durably written
    */
  def write(batch: Batch[A]): Future[Unit]
}

/** Companion helpers for [[BatchSink]]. */
object BatchSink {

  /** Creates a [[BatchSink]] from a function. */
  def instance[A](f: Batch[A] => Future[Unit]): BatchSink[A] =
    new BatchSink[A] {
      override def write(batch: Batch[A]): Future[Unit] = f(batch)
    }
}
