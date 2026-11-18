package io.github.trysac.pekko_streams_ingest

import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.stream.{Attributes, Materializer}
import org.apache.pekko.util.ByteString

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Entry point for bounded, resumable line-oriented ingestion on Apache Pekko Streams. */
object Ingest {

  private final case class FramedLine(
      lineNumber: Long,
      rawBytes: ByteString,
      payloadBytes: Long,
      rawByteLength: Long,
      endOffset: Long,
  )

  /** A framed line after parsing: `record` is `None` when the line was rejected. */
  private final case class ParsedLine[A](
      lineNumber: Long,
      record: Option[A],
      payloadBytes: Long,
      rawByteLength: Long,
      endOffset: Long,
  )

  /** Stream-wide counters, exact up to the element that carries them. */
  private final case class Totals(
      lines: Long,
      rejected: Long,
      rawBytes: Long,
      latestEndOffset: Long,
  )

  /** A completed batch ready for the sink, or (with `batch = None`) the final counters. */
  private final case class BatchElement[A](batch: Option[Batch[A]], totals: Totals)

  /** Bytes not yet framed, the absolute offset after the last framed line and its number. */
  private final case class FramerState(buffer: ByteString, offset: Long, line: Long)

  /** The open batch plus the stream counters. */
  private final case class BatchState[A](
      records: Vector[A],
      bytes: Long,
      firstLine: Long,
      lastLine: Long,
      batchEndOffset: Long,
      nextIndex: Long,
      totals: Totals,
  )

  /** Ingests a UTF-8 line file at `path` from the offset stored in `checkpoint`.
    *
    * Line numbers in the run start at 1 relative to the resumed byte offset. After each batch is
    * written through `sink` in file order, its `endOffset` is saved to `checkpoint`.
    *
    * @param path
    *   path to the file to ingest
    * @param parser
    *   line parser converting each UTF-8 line into `Either[String, A]`
    * @param sink
    *   asynchronous batch sink
    * @param config
    *   validated ingestion bounds
    * @param checkpoint
    *   offset store consulted at start and updated after each committed batch
    * @param deadLetter
    *   callback invoked for each rejected line within `config.maxErrors`
    * @param onEvent
    *   callback invoked with [[BatchCommitted]] and [[RunFinished]] events
    * @return
    *   a `Future` completing with the [[IngestReport]] or failing with [[IngestException]]
    */
  def file[A](
      path: Path,
      parser: LineParser[A],
      sink: BatchSink[A],
      config: IngestConfig = IngestConfig(),
      checkpoint: CheckpointStore = new InMemoryCheckpointStore(),
      deadLetter: Rejected => Unit = _ => (),
      onEvent: IngestEvent => Unit = _ => (),
  )(using mat: Materializer): Future[IngestReport] = {
    given ExecutionContext = mat.executionContext
    Future {
      if (!Files.exists(path)) {
        throw IngestException.ioError(
          s"File does not exist: ${path.getFileName}",
          new java.nio.file.NoSuchFileException(path.toString),
        )
      }
      val size = Files.size(path)
      val startOffset = checkpoint.load().getOrElse(0L)
      if (startOffset < 0L || startOffset > size) {
        throw IngestException.checkpointMismatch(startOffset, size)
      }
      (size, startOffset)
    }.flatMap {
      case (size, startOffset) if startOffset == size =>
        val emptyReport = IngestReport(
          lines = 0L,
          records = 0L,
          rejected = 0L,
          batches = 0L,
          bytes = 0L,
          endOffset = startOffset,
        )
        onEvent(RunFinished(emptyReport))
        Future.successful(emptyReport)
      case (_, startOffset) =>
        val byteSource: Source[ByteString, ?] =
          FileIO
            .fromPath(path, config.chunkSize, startPosition = startOffset)
            .mapError {
              case e: IngestException => e
              case NonFatal(e)        => IngestException.ioError("Error reading input file", e)
            }
        source(
          bytes = byteSource,
          startOffset = startOffset,
          parser = parser,
          sink = sink,
          config = config,
          checkpoint = checkpoint,
          deadLetter = deadLetter,
          onEvent = onEvent,
        )
    }
  }

  /** Ingests an arbitrary stream of [[org.apache.pekko.util.ByteString]] chunks starting at
    * `startOffset`.
    *
    * @param bytes
    *   source of raw byte chunks
    * @param startOffset
    *   starting byte offset (>= 0)
    * @param parser
    *   line parser converting each UTF-8 line into `Either[String, A]`
    * @param sink
    *   asynchronous batch sink
    * @param config
    *   validated ingestion bounds
    * @param checkpoint
    *   offset store updated after each committed batch
    * @param deadLetter
    *   callback invoked for each rejected line within `config.maxErrors`
    * @param onEvent
    *   callback invoked with [[BatchCommitted]] and [[RunFinished]] events
    * @return
    *   a `Future` completing with the [[IngestReport]] or failing with [[IngestException]]
    */
  def source[A](
      bytes: Source[ByteString, ?],
      startOffset: Long = 0L,
      parser: LineParser[A],
      sink: BatchSink[A],
      config: IngestConfig = IngestConfig(),
      checkpoint: CheckpointStore = new InMemoryCheckpointStore(),
      deadLetter: Rejected => Unit = _ => (),
      onEvent: IngestEvent => Unit = _ => (),
  )(using mat: Materializer): Future[IngestReport] = {
    given ExecutionContext = mat.executionContext
    if (startOffset < 0L) {
      Future.failed(IngestException.checkpointMismatch(startOffset, -1L))
    } else {
      val pipeline =
        bytes
          .mapError {
            case e: IngestException => e
            case NonFatal(e)        => IngestException.ioError("Upstream byte source failed", e)
          }
          .statefulMap(() => FramerState(ByteString.empty, startOffset, 0L))(
            frameChunk(config.maxLineBytes),
            state => finalLine(config.maxLineBytes, state),
          )
          .mapConcat(identity)
          .statefulMap(() => 0L)(parseLine(parser, config.maxErrors, deadLetter), _ => None)
          .statefulMap(() => emptyBatchState[A](startOffset))(
            addToBatch(config.batchMaxRecords, config.batchMaxBytes),
            state => Some(finishBatches(state)),
          )
          .mapConcat(identity)
          .withAttributes(Attributes.inputBuffer(initial = 1, max = 1))

      pipeline
        .mapAsync(config.parallelism)(element => writeBatch(sink, element))
        .withAttributes(Attributes.inputBuffer(initial = 1, max = 1))
        .runFold(RunSummary(0L, 0L, Totals(0L, 0L, 0L, startOffset))) { (summary, element) =>
          element.batch match {
            case Some(batch) =>
              checkpoint.save(batch.endOffset)
              onEvent(
                BatchCommitted(
                  batchIndex = batch.index,
                  firstLine = batch.firstLine,
                  lastLine = batch.lastLine,
                  endOffset = batch.endOffset,
                  records = batch.records.size,
                ),
              )
              RunSummary(
                records = summary.records + batch.records.size.toLong,
                batches = summary.batches + 1L,
                totals = element.totals,
              )
            case None =>
              summary.copy(totals = element.totals)
          }
        }
        .map { summary =>
          val report = IngestReport(
            lines = summary.totals.lines,
            records = summary.records,
            rejected = summary.totals.rejected,
            batches = summary.batches,
            bytes = summary.totals.rawBytes,
            endOffset = summary.totals.latestEndOffset,
          )
          onEvent(RunFinished(report))
          report
        }
    }
  }

  /** Records and batches committed so far, plus the latest stream counters. */
  private final case class RunSummary(records: Long, batches: Long, totals: Totals)

  /** Writes the batch of `element` (if any) through `sink`, mapping failures to `sink_failed`. */
  private def writeBatch[A](sink: BatchSink[A], element: BatchElement[A])(using
      ExecutionContext,
  ): Future[BatchElement[A]] =
    element.batch match {
      case Some(batch) =>
        val writeFuture =
          try {
            sink.write(batch)
          } catch {
            case NonFatal(syncErr) => Future.failed(syncErr)
          }
        writeFuture
          .map(_ => element)
          .recoverWith {
            case e: IngestException => Future.failed(e)
            case NonFatal(cause)    => Future.failed(IngestException.sinkFailed(batch.index, cause))
          }
      case None =>
        Future.successful(element)
    }

  // Line framing: splits byte chunks on LF, strips an optional trailing CR, enforces
  // `maxLineBytes` on the payload and tracks exact byte offsets. A failure raised here fails the
  // stream before any line of the same chunk is emitted.

  private val LineFeed: Byte = 10
  private val CarriageReturn: Byte = 13

  private def payloadLengthOf(slice: ByteString): Int =
    if (slice.nonEmpty && slice.last == CarriageReturn) slice.length - 1 else slice.length

  /** The line that follows `state`, made of `rawSlice` and consuming `consumedBytes` bytes. */
  private def nextLine(
      maxLineBytes: Int,
      state: FramerState,
      rawSlice: ByteString,
      consumedBytes: Long,
  ): FramedLine = {
    val payloadLen = payloadLengthOf(rawSlice)
    val lineNumber = state.line + 1L
    if (payloadLen > maxLineBytes) {
      throw IngestException.lineTooLong(lineNumber, maxLineBytes)
    }
    FramedLine(
      lineNumber = lineNumber,
      rawBytes = if (payloadLen < rawSlice.length) rawSlice.take(payloadLen) else rawSlice,
      payloadBytes = payloadLen.toLong,
      rawByteLength = consumedBytes,
      endOffset = state.offset + consumedBytes,
    )
  }

  @tailrec
  private def extractLines(
      maxLineBytes: Int,
      state: FramerState,
      acc: Vector[FramedLine],
  ): (FramerState, Vector[FramedLine]) = {
    val nlIdx = state.buffer.indexOf(LineFeed)
    if (nlIdx >= 0) {
      val line = nextLine(maxLineBytes, state, state.buffer.take(nlIdx), nlIdx.toLong + 1L)
      val next = FramerState(state.buffer.drop(nlIdx + 1), line.endOffset, line.lineNumber)
      extractLines(maxLineBytes, next, acc :+ line)
    } else {
      // No line feed yet: fail as soon as the buffered bytes exceed `maxLineBytes`, even if the
      // next bytes were CR LF.
      if (state.buffer.nonEmpty && payloadLengthOf(state.buffer) > maxLineBytes) {
        throw IngestException.lineTooLong(state.line + 1L, maxLineBytes)
      }
      (state, acc)
    }
  }

  private def frameChunk(maxLineBytes: Int)(
      state: FramerState,
      chunk: ByteString,
  ): (FramerState, Vector[FramedLine]) =
    if (chunk.isEmpty) (state, Vector.empty)
    else extractLines(maxLineBytes, state.copy(buffer = state.buffer ++ chunk), Vector.empty)

  /** The unterminated last line, if any bytes remain at the end of the input. */
  private def finalLine(maxLineBytes: Int, state: FramerState): Option[Vector[FramedLine]] =
    if (state.buffer.isEmpty) None
    else Some(Vector(nextLine(maxLineBytes, state, state.buffer, state.buffer.length.toLong)))

  // Parsing: decodes each framed line as strict UTF-8, runs `parser.parse`, routes rejections to
  // `deadLetter` and enforces `maxErrors`. The state is the number of rejected lines so far.

  private def decodeUtf8Strict(bytes: ByteString): Either[String, String] = {
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try {
      Right(decoder.decode(bytes.asByteBuffer).toString)
    } catch {
      case e: CharacterCodingException =>
        Left(s"Malformed UTF-8 sequence: ${Option(e.getMessage).getOrElse("invalid bytes")}")
    }
  }

  private def lossyPrefix(bytes: ByteString): String = {
    val decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes.take(800).toArray)).toString
    IngestException.truncatePrefix(decoded, 200)
  }

  /** The rejection reason and line prefix, or the parsed record. */
  private def parseOutcome[A](
      parser: LineParser[A],
      framed: FramedLine,
  ): Either[(String, String), A] =
    decodeUtf8Strict(framed.rawBytes) match {
      case Left(decodeErr) => Left((decodeErr, lossyPrefix(framed.rawBytes)))
      case Right(lineStr)  =>
        val parsed =
          try {
            parser.parse(lineStr, framed.lineNumber)
          } catch {
            case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
          }
        parsed.left.map(reason => (reason, lineStr))
    }

  private def parseLine[A](
      parser: LineParser[A],
      maxErrors: Long,
      deadLetter: Rejected => Unit,
  )(rejected: Long, framed: FramedLine): (Long, ParsedLine[A]) =
    parseOutcome(parser, framed) match {
      case Right(record) =>
        (rejected, parsedLine(framed, Some(record)))
      case Left((reason, prefix)) =>
        val count = rejected + 1L
        deadLetter(
          Rejected(
            framed.lineNumber,
            IngestException.truncatePrefix(reason, 200),
            IngestException.truncatePrefix(prefix, 200),
          ),
        )
        if (count > maxErrors) {
          throw IngestException.tooManyErrors(count, maxErrors, framed.lineNumber)
        }
        (count, parsedLine(framed, None))
    }

  private def parsedLine[A](framed: FramedLine, record: Option[A]): ParsedLine[A] =
    ParsedLine(
      lineNumber = framed.lineNumber,
      record = record,
      payloadBytes = framed.payloadBytes,
      rawByteLength = framed.rawByteLength,
      endOffset = framed.endOffset,
    )

  // Batching: groups valid records into batches bounded by `batchMaxRecords` and `batchMaxBytes`
  // while tracking exact stream counters. A single record whose `payloadBytes > batchMaxBytes`
  // forms a batch of its own.

  private def emptyBatchState[A](startOffset: Long): BatchState[A] =
    BatchState(
      records = Vector.empty[A],
      bytes = 0L,
      firstLine = 0L,
      lastLine = 0L,
      batchEndOffset = startOffset,
      nextIndex = 1L,
      totals = Totals(0L, 0L, 0L, startOffset),
    )

  private def isFull[A](batchMaxRecords: Int, batchMaxBytes: Long, state: BatchState[A]): Boolean =
    state.records.size >= batchMaxRecords || state.bytes >= batchMaxBytes

  /** Closes the open batch: the state with an empty batch and the ready element. */
  private def flush[A](state: BatchState[A]): (BatchState[A], BatchElement[A]) = {
    val batch = Batch(
      index = state.nextIndex,
      records = state.records,
      firstLine = state.firstLine,
      lastLine = state.lastLine,
      endOffset = state.batchEndOffset,
      bytes = state.bytes,
    )
    val emptied = state.copy(
      records = Vector.empty[A],
      bytes = 0L,
      firstLine = 0L,
      lastLine = 0L,
      nextIndex = state.nextIndex + 1L,
    )
    (emptied, BatchElement(Some(batch), state.totals))
  }

  private def append[A](state: BatchState[A], line: ParsedLine[A], record: A): BatchState[A] =
    state.copy(
      records = state.records :+ record,
      bytes = state.bytes + line.payloadBytes,
      firstLine = if (state.records.isEmpty) line.lineNumber else state.firstLine,
      lastLine = line.lineNumber,
      batchEndOffset = line.endOffset,
    )

  /** Adds `record` to the open batch and flushes it when it reaches a bound. */
  private def appendAndFlushIfFull[A](
      batchMaxRecords: Int,
      batchMaxBytes: Long,
      state: BatchState[A],
      line: ParsedLine[A],
      record: A,
  ): (BatchState[A], Vector[BatchElement[A]]) = {
    val appended = append(state, line, record)
    if (isFull(batchMaxRecords, batchMaxBytes, appended)) {
      val (emptied, ready) = flush(appended)
      (emptied, Vector(ready))
    } else {
      (appended, Vector.empty)
    }
  }

  private def addToBatch[A](batchMaxRecords: Int, batchMaxBytes: Long)(
      state: BatchState[A],
      line: ParsedLine[A],
  ): (BatchState[A], Vector[BatchElement[A]]) = {
    val totals = Totals(
      lines = state.totals.lines + 1L,
      rejected = state.totals.rejected + (if (line.record.isEmpty) 1L else 0L),
      rawBytes = state.totals.rawBytes + line.rawByteLength,
      latestEndOffset = line.endOffset,
    )
    val counted = state.copy(totals = totals)
    line.record match {
      case None =>
        val endOffset = if (state.records.nonEmpty) line.endOffset else state.batchEndOffset
        (counted.copy(batchEndOffset = endOffset), Vector.empty)
      case Some(record) =>
        val fits =
          state.records.size + 1 <= batchMaxRecords &&
            state.bytes + line.payloadBytes <= batchMaxBytes
        if (state.records.isEmpty || fits) {
          appendAndFlushIfFull(batchMaxRecords, batchMaxBytes, counted, line, record)
        } else {
          val (emptied, ready) = flush(counted)
          val (next, more) =
            appendAndFlushIfFull(batchMaxRecords, batchMaxBytes, emptied, line, record)
          (next, ready +: more)
        }
    }
  }

  /** At the end of the input: the open batch, if any, followed by the final counters. */
  private def finishBatches[A](state: BatchState[A]): Vector[BatchElement[A]] = {
    val last = if (state.records.nonEmpty) Vector(flush(state)._2) else Vector.empty
    last :+ BatchElement(None, state.totals)
  }
}
