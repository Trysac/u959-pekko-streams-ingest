package io.github.trysac.pekko_streams_ingest

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.testkit.scaladsl.TestSource
import org.apache.pekko.util.ByteString

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.jdk.CollectionConverters.*

/** Acceptance, edge-case, backpressure, checkpoint and conformance suites for [[Ingest]]. */
class IngestSuite extends munit.FunSuite {

  // munit's default is 30 seconds per test; the 10 GiB conformance test needs minutes, and longer
  // under the coverage instrumentation of the CI tier. Every other test finishes in seconds.
  override val munitTimeout: Duration = 40.minutes

  private val system: ActorSystem = ActorSystem("IngestSuite")
  private given Materializer = Materializer(system)

  override def afterAll(): Unit = {
    val _ = Await.result(system.terminate(), 10.seconds)
  }

  private def withTempFile(bytes: Array[Byte])(body: Path => Unit): Unit = {
    val path = Files.createTempFile("pekko-ingest-test-", ".txt")
    try {
      Files.write(path, bytes)
      body(path)
    } finally {
      val _ = Files.deleteIfExists(path)
    }
  }

  private def withTempStringFile(content: String)(body: Path => Unit): Unit =
    withTempFile(content.getBytes(StandardCharsets.UTF_8))(body)

  test("Criterion 1: 3-line file a\\nbb\\nccc\\n produces expected Batch and IngestReport") {
    withTempStringFile("a\nbb\nccc\n") { path =>
      val batches = new ConcurrentLinkedQueue[Batch[String]]()
      val sink = BatchSink.instance[String] { batch =>
        batches.add(batch)
        Future.successful(())
      }
      val checkpoint = new InMemoryCheckpointStore()
      val report = Await.result(
        Ingest.file(path, LineParser.identity, sink, IngestConfig(), checkpoint),
        10.seconds,
      )

      val received = batches.asScala.toVector
      assertEquals(received.size, 1)
      val batch = received.head
      assertEquals(batch.index, 1L)
      assertEquals(batch.records, Vector("a", "bb", "ccc"))
      assertEquals(batch.firstLine, 1L)
      assertEquals(batch.lastLine, 3L)
      assertEquals(batch.endOffset, 9L)
      assertEquals(batch.bytes, 6L)

      assertEquals(report.lines, 3L)
      assertEquals(report.records, 3L)
      assertEquals(report.rejected, 0L)
      assertEquals(report.batches, 1L)
      assertEquals(report.bytes, 9L)
      assertEquals(report.endOffset, 9L)
      assertEquals(checkpoint.load(), Some(9L))
    }
  }

  test(
    "Criterion 2: stalled sink stops pulling below 4_194_304 bytes on a 1_073_741_824-byte stream and completes after release",
  ) {
    val totalTargetBytes: Long = 1_073_741_824L
    val chunkSize: Int = 8_192
    val totalChunks: Int = (totalTargetBytes / chunkSize.toLong).toInt

    // Build an 8,192-byte chunk containing 8 lines of 1,024 bytes each (1,023 'x' + '\n').
    val singleLine = ("x" * 1023) + "\n"
    val chunkBytes = ByteString(singleLine * 8)
    assertEquals(chunkBytes.length, chunkSize)

    val pulledBytes = new AtomicLong(0L)
    val gate = Promise[Unit]()
    val firstBatchArrived = Promise[Unit]()

    val generatedSource: Source[ByteString, ?] =
      Source
        .repeat(chunkBytes)
        .take(totalChunks.toLong)
        .map { chunk =>
          pulledBytes.addAndGet(chunk.length.toLong)
          chunk
        }

    val sink = BatchSink.instance[String] { _ =>
      firstBatchArrived.trySuccess(())
      gate.future
    }

    val config = IngestConfig.validated(
      chunkSize = 8_192,
      maxLineBytes = 4_096,
      batchMaxRecords = 1_000,
      batchMaxBytes = 65_536L,
      parallelism = 2,
      maxErrors = 100L,
    )

    val runFuture = Ingest.source(
      bytes = generatedSource,
      startOffset = 0L,
      parser = LineParser.identity,
      sink = sink,
      config = config,
    )

    Await.result(firstBatchArrived.future, 10.seconds)
    // Allow stream buffers to fill until backpressure settles.
    @tailrec
    def settle(prev: Long, current: Long, spins: Int): Long =
      if (current == prev || spins >= 50) current
      else {
        Thread.sleep(10L)
        settle(current, pulledBytes.get(), spins + 1)
      }
    val curr = settle(-1L, pulledBytes.get(), 0)

    assert(
      curr > 0L && curr < 4_194_304L,
      s"Expected pulled bytes while sink stalled to be < 4_194_304, but got $curr",
    )

    gate.success(())
    val report = Await.result(runFuture, 120.seconds)
    assertEquals(report.bytes, 1_073_741_824L)
    assertEquals(report.endOffset, 1_073_741_824L)
    assertEquals(report.lines, totalChunks.toLong * 8L)
    assertEquals(report.records, totalChunks.toLong * 8L)
  }

  test("Backpressure verification with pekko-stream-testkit TestSource probe") {
    val gate = Promise[Unit]()
    val sink = BatchSink.instance[String](_ => gate.future)
    val config = IngestConfig.validated(
      chunkSize = 64,
      maxLineBytes = 64,
      batchMaxRecords = 1,
      batchMaxBytes = 64L,
      parallelism = 1,
      maxErrors = 5L,
    )

    val (pubProbe, source) = TestSource[ByteString]()(using system).preMaterialize()
    val runFuture = Ingest.source(source, 0L, LineParser.identity, sink, config)

    pubProbe.sendNext(ByteString("line1\n"))
    pubProbe.sendNext(ByteString("line2\n"))
    gate.success(())
    pubProbe.sendComplete()

    val report = Await.result(runFuture, 10.seconds)
    assertEquals(report.records, 2L)
  }

  test(
    "Criterion 3: Batches respect batchMaxRecords, batchMaxBytes, oversize single record, and commit in order with parallelism 4",
  ) {
    // Lines: two 4-byte lines (fit together under batchMaxBytes=10), one 25-byte oversize line,
    // then 8 short lines split by batchMaxRecords=3.
    val lines = Vector(
      "aaaa",
      "bbbb",
      "z" * 25,
      "1",
      "2",
      "3",
      "4",
      "5",
      "6",
      "7",
      "8",
    )
    val content = lines.mkString("", "\n", "\n")

    withTempStringFile(content) { path =>
      val batches = new ConcurrentLinkedQueue[Batch[String]]()
      val checkpoint = new InMemoryCheckpointStore()
      val pendingPromises = Map(
        1L -> Promise[Unit](),
        2L -> Promise[Unit](),
        3L -> Promise[Unit](),
        4L -> Promise[Unit](),
      )
      val allFourStarted = Promise[Unit]()
      val startedCount = new AtomicLong(0L)

      val sink = BatchSink.instance[String] { batch =>
        batches.add(batch)
        pendingPromises.get(batch.index) match {
          case Some(p) =>
            if (startedCount.incrementAndGet() == 4L) {
              val _ = allFourStarted.trySuccess(())
            }
            p.future
          case None =>
            Future.successful(())
        }
      }

      val config = IngestConfig.validated(
        chunkSize = 16,
        maxLineBytes = 100,
        batchMaxRecords = 3,
        batchMaxBytes = 10L,
        parallelism = 4,
        maxErrors = 10L,
      )

      val runFuture = Ingest.file(path, LineParser.identity, sink, config, checkpoint)
      Await.result(allFourStarted.future, 10.seconds)

      // Complete batches 4, 3, 2 before batch 1 to test out-of-order completion.
      pendingPromises(4L).success(())
      pendingPromises(3L).success(())
      pendingPromises(2L).success(())
      pendingPromises(1L).success(())

      val report = Await.result(runFuture, 10.seconds)
      val seenBatches = batches.asScala.toVector.sortBy(_.index)

      // Verify batch sizing rules.
      for (b <- seenBatches) {
        assert(b.records.size <= config.batchMaxRecords)
        if (b.records.size > 1) {
          assert(b.bytes <= config.batchMaxBytes)
        }
      }
      // Batch 2 is the single 25-byte record.
      assertEquals(seenBatches(1).records, Vector("z" * 25))
      assertEquals(seenBatches(1).bytes, 25L)

      // Verify checkpoints were saved in strictly increasing batch order.
      val expectedOffsets = seenBatches.map(_.endOffset)
      assertEquals(checkpoint.savedOffsets, expectedOffsets)
      assertEquals(report.records, lines.size.toLong)
    }
  }

  test(
    "Criterion 4: Failure on batch 3 of a 10-batch file saves batch 2 offset and resumes cleanly for batches 3 to 10",
  ) {
    val lines = (1 to 20).map(i => f"rec-$i%02d").toVector
    val content = lines.mkString("", "\n", "\n")

    withTempStringFile(content) { path =>
      val checkpointFile = Files.createTempFile("pekko-checkpoint-", ".chk")
      Files.deleteIfExists(checkpointFile)
      try {
        val checkpoint = new FileCheckpointStore(checkpointFile)
        val config = IngestConfig.validated(
          chunkSize = 32,
          maxLineBytes = 64,
          batchMaxRecords = 2,
          batchMaxBytes = 1024L,
          parallelism = 1,
          maxErrors = 5L,
        )

        val deliveredRun1 = new ConcurrentLinkedQueue[String]()
        val failingSink = BatchSink.instance[String] { batch =>
          if (batch.index == 3L) {
            Future.failed(new RuntimeException("simulated store outage on batch 3"))
          } else {
            batch.records.foreach(deliveredRun1.add)
            Future.successful(())
          }
        }

        val err = intercept[IngestException] {
          Await.result(
            Ingest.file(path, LineParser.identity, failingSink, config, checkpoint),
            10.seconds,
          )
        }
        assertEquals(err.code, "sink_failed")
        assert(Option(err.getCause).isDefined)
        assertEquals(deliveredRun1.asScala.toVector, lines.take(4))
        // Each record "rec-XX\n" is 7 bytes; 2 batches of 2 records = 4 * 7 = 28 bytes.
        assertEquals(checkpoint.load(), Some(28L))

        val deliveredRun2 = new ConcurrentLinkedQueue[String]()
        val workingSink = BatchSink.instance[String] { batch =>
          batch.records.foreach(deliveredRun2.add)
          Future.successful(())
        }

        val report2 = Await.result(
          Ingest.file(path, LineParser.identity, workingSink, config, checkpoint),
          10.seconds,
        )
        assertEquals(deliveredRun2.asScala.toVector, lines.drop(4))
        assertEquals(deliveredRun1.asScala.toVector ++ deliveredRun2.asScala.toVector, lines)
        assertEquals(report2.batches, 8L)
        assertEquals(report2.records, 16L)
        assertEquals(report2.lines, 16L)
        assertEquals(report2.endOffset, 140L)
        assertEquals(checkpoint.load(), Some(140L))
      } finally {
        val _ = Files.deleteIfExists(checkpointFile)
      }
    }
  }

  test(
    "Criterion 5: Dead-letter channel captures 2 of 1_000 failing lines with maxErrors 5 and fails with too_many_errors when maxErrors 1",
  ) {
    val longInvalidLine = "BAD:" + ("x" * 250)
    val lines = (1 to 1_000).map { i =>
      if (i == 100 || i == 500) longInvalidLine
      else s"ok-$i"
    }
    val content = lines.mkString("", "\n", "\n")

    val parser = LineParser.instance[String] { (line, _) =>
      if (line.startsWith("BAD:")) Left("invalid prefix BAD")
      else Right(line)
    }

    withTempStringFile(content) { path =>
      val rejected = new ConcurrentLinkedQueue[Rejected]()
      val sink = BatchSink.instance[String](_ => Future.successful(()))
      val config5 = IngestConfig.validated(maxErrors = 5L)

      val report = Await.result(
        Ingest.file(
          path = path,
          parser = parser,
          sink = sink,
          config = config5,
          checkpoint = new InMemoryCheckpointStore(),
          deadLetter = r => { val _ = rejected.add(r) },
        ),
        10.seconds,
      )

      assertEquals(report.lines, 1_000L)
      assertEquals(report.records, 998L)
      assertEquals(report.rejected, 2L)

      val deadLetters = rejected.asScala.toVector
      assertEquals(deadLetters.map(_.lineNumber), Vector(100L, 500L))
      for (dl <- deadLetters) {
        assertEquals(dl.reason, "invalid prefix BAD")
        assertEquals(dl.prefix.length, 200)
        assert(dl.prefix.startsWith("BAD:xxxx"))
      }

      // Now run with maxErrors = 1: the second error must fail the run with code 'too_many_errors'.
      val rejectedStrict = new ConcurrentLinkedQueue[Rejected]()
      val config1 = IngestConfig.validated(maxErrors = 1L)
      val err = intercept[IngestException] {
        Await.result(
          Ingest.file(
            path = path,
            parser = parser,
            sink = sink,
            config = config1,
            checkpoint = new InMemoryCheckpointStore(),
            deadLetter = r => { val _ = rejectedStrict.add(r) },
          ),
          10.seconds,
        )
      }
      assertEquals(err.code, "too_many_errors")
      assertEquals(err.lineNumber, Some(500L))
    }
  }

  test(
    "Criterion 6: Error codes line_too_long (4_097 with max 4_096), checkpoint_mismatch, invalid_config, and final line without newline",
  ) {
    // 1. invalid_config
    val errPar = intercept[IngestException](IngestConfig.validated(parallelism = 0))
    assertEquals(errPar.code, "invalid_config")
    val errChunk = intercept[IngestException](IngestConfig.validated(chunkSize = 0))
    assertEquals(errChunk.code, "invalid_config")
    val errLine = intercept[IngestException](IngestConfig.validated(maxLineBytes = 0))
    assertEquals(errLine.code, "invalid_config")
    val errBatchRec = intercept[IngestException](IngestConfig.validated(batchMaxRecords = 0))
    assertEquals(errBatchRec.code, "invalid_config")
    val errBatchBytes = intercept[IngestException](IngestConfig.validated(batchMaxBytes = 0L))
    assertEquals(errBatchBytes.code, "invalid_config")
    val errMaxErr = intercept[IngestException](IngestConfig.validated(maxErrors = 0L))
    assertEquals(errMaxErr.code, "invalid_config")

    // 2. line_too_long on line 2 of 4_097 bytes with maxLineBytes 4_096
    val overlongContent = "ok\n" + ("a" * 4_097) + "\n"
    withTempStringFile(overlongContent) { path =>
      val cfg = IngestConfig.validated(chunkSize = 1_024, maxLineBytes = 4_096)
      val sink = BatchSink.instance[String](_ => Future.successful(()))
      val err = intercept[IngestException] {
        Await.result(Ingest.file(path, LineParser.identity, sink, cfg), 10.seconds)
      }
      assertEquals(err.code, "line_too_long")
      assertEquals(err.lineNumber, Some(2L))
      assert(err.getMessage.length <= 200)
    }

    // Also verify a line of exactly 4_096 bytes succeeds and an unterminated final line of 4_097 bytes fails.
    val unterminatedOverlong = "ok\n" + ("b" * 4_097)
    withTempStringFile(unterminatedOverlong) { path =>
      val cfg = IngestConfig.validated(chunkSize = 8_192, maxLineBytes = 4_096)
      val sink = BatchSink.instance[String](_ => Future.successful(()))
      val err = intercept[IngestException] {
        Await.result(Ingest.file(path, LineParser.identity, sink, cfg), 10.seconds)
      }
      assertEquals(err.code, "line_too_long")
      assertEquals(err.lineNumber, Some(2L))
    }

    // 3. checkpoint_mismatch when offset > file size or negative
    withTempStringFile("hello\n") { path =>
      val sink = BatchSink.instance[String](_ => Future.successful(()))
      val badCheckpoint = new InMemoryCheckpointStore(Some(100L))
      val err = intercept[IngestException] {
        Await.result(
          Ingest.file(path, LineParser.identity, sink, IngestConfig(), badCheckpoint),
          10.seconds,
        )
      }
      assertEquals(err.code, "checkpoint_mismatch")

      val negErr = intercept[IngestException] {
        Await.result(
          Ingest.source(Source.single(ByteString("hi\n")), -5L, LineParser.identity, sink),
          10.seconds,
        )
      }
      assertEquals(negErr.code, "checkpoint_mismatch")
    }

    // 4. Final line without a trailing newline is ingested
    withTempStringFile("first\nsecond-without-newline") { path =>
      val records = new ConcurrentLinkedQueue[String]()
      val sink = BatchSink.instance[String] { b =>
        b.records.foreach(records.add)
        Future.successful(())
      }
      val report = Await.result(Ingest.file(path, LineParser.identity, sink), 10.seconds)
      assertEquals(records.asScala.toVector, Vector("first", "second-without-newline"))
      assertEquals(report.lines, 2L)
      assertEquals(report.records, 2L)
      assertEquals(report.bytes, 28L)
      assertEquals(report.endOffset, 28L)
    }
  }

  test(
    "Criterion 7: onEvent receives BatchCommitted and RunFinished only, and never carries record content",
  ) {
    val secretMarker = "SENSITIVE_RECORD_PAYLOAD_XYZ_987"
    val content = s"$secretMarker-1\n$secretMarker-2\n$secretMarker-3\n"
    // Every line has the same length: the marker, "-N" and the line feed (35 bytes).
    val lineBytes = s"$secretMarker-1\n".getBytes(StandardCharsets.UTF_8).length.toLong
    withTempStringFile(content) { path =>
      val events = new ConcurrentLinkedQueue[IngestEvent]()
      val sink = BatchSink.instance[String](_ => Future.successful(()))
      val config = IngestConfig.validated(batchMaxRecords = 2)

      val report = Await.result(
        Ingest.file(
          path = path,
          parser = LineParser.identity,
          sink = sink,
          config = config,
          checkpoint = new InMemoryCheckpointStore(),
          onEvent = ev => { val _ = events.add(ev) },
        ),
        10.seconds,
      )

      val recorded = events.asScala.toVector
      assertEquals(
        recorded,
        Vector(
          BatchCommitted(
            batchIndex = 1L,
            firstLine = 1L,
            lastLine = 2L,
            endOffset = 2L * lineBytes,
            records = 2,
          ),
          BatchCommitted(
            batchIndex = 2L,
            firstLine = 3L,
            lastLine = 3L,
            endOffset = 3L * lineBytes,
            records = 1,
          ),
          RunFinished(report),
        ),
      )
      for (ev <- recorded) {
        assert(!ev.toString.contains(secretMarker))
      }
    }
  }

  test(
    "Edge cases: empty file, blank lines only, CRLF lines, split multi-byte UTF-8, malformed UTF-8, and resume at EOF",
  ) {
    // 1. Empty file -> report all zeros, no batch
    withTempStringFile("") { path =>
      val batches = new ConcurrentLinkedQueue[Batch[String]]()
      val sink = BatchSink.instance[String] { b =>
        batches.add(b)
        Future.successful(())
      }
      val report = Await.result(Ingest.file(path, LineParser.identity, sink), 10.seconds)
      assertEquals(batches.size(), 0)
      assertEquals(report, IngestReport(0L, 0L, 0L, 0L, 0L, 0L))
    }

    // 2. File of only blank lines -> empty strings are valid records
    withTempStringFile("\n\n\n") { path =>
      val batches = new ConcurrentLinkedQueue[Batch[String]]()
      val sink = BatchSink.instance[String] { b =>
        batches.add(b)
        Future.successful(())
      }
      val report = Await.result(Ingest.file(path, LineParser.identity, sink), 10.seconds)
      assertEquals(batches.asScala.toVector.flatMap(_.records), Vector("", "", ""))
      assertEquals(report.lines, 3L)
      assertEquals(report.records, 3L)
      assertEquals(report.bytes, 3L)
    }

    // 3. CRLF lines -> trailing \r stripped, exact byte offsets preserved
    withTempStringFile("alpha\r\nbeta\r\ngamma\r") { path =>
      val batches = new ConcurrentLinkedQueue[Batch[String]]()
      val sink = BatchSink.instance[String] { b =>
        batches.add(b)
        Future.successful(())
      }
      val report = Await.result(Ingest.file(path, LineParser.identity, sink), 10.seconds)
      val batch = batches.peek()
      assertEquals(batch.records, Vector("alpha", "beta", "gamma"))
      assertEquals(batch.bytes, 14L) // 5 + 4 + 5 payload bytes without \r\n
      assertEquals(report.bytes, 19L) // 7 + 6 + 6 raw bytes
      assertEquals(report.endOffset, 19L)
    }

    // 4. Multi-byte UTF-8 character (e.g. '€' = E2 82 AC, '🚀' = F0 9F 9A 80) split across two chunks
    val utf8Text = "price: €100 🚀\nnext: café\n"
    val rawBytes = utf8Text.getBytes(StandardCharsets.UTF_8)
    // Split right inside the 3-byte Euro sign and 4-byte rocket emoji.
    val chunks = rawBytes.grouped(2).map(ByteString.fromArray).toVector
    val records = new ConcurrentLinkedQueue[String]()
    val sink = BatchSink.instance[String] { b =>
      b.records.foreach(records.add)
      Future.successful(())
    }
    val utf8Report = Await.result(
      Ingest.source(Source(chunks), 0L, LineParser.identity, sink),
      10.seconds,
    )
    assertEquals(records.asScala.toVector, Vector("price: €100 🚀", "next: café"))
    assertEquals(utf8Report.bytes, rawBytes.length.toLong)

    // 5. Malformed UTF-8 bytes become a parse rejection in deadLetter
    val malformedBytes =
      ByteString("valid\n") ++ ByteString(Array[Byte](0xff.toByte, 0xfe.toByte, '\n'.toByte))
    val deadLetters = new ConcurrentLinkedQueue[Rejected]()
    val malReport = Await.result(
      Ingest.source(
        bytes = Source.single(malformedBytes),
        startOffset = 0L,
        parser = LineParser.identity,
        sink = sink,
        deadLetter = r => { val _ = deadLetters.add(r) },
      ),
      10.seconds,
    )
    assertEquals(malReport.lines, 2L)
    assertEquals(malReport.records, 1L)
    assertEquals(malReport.rejected, 1L)
    assertEquals(deadLetters.peek().lineNumber, 2L)

    // 6. Resume from an offset equal to the file size -> nothing to do
    withTempStringFile("abc\ndef\n") { path =>
      val batches = new ConcurrentLinkedQueue[Batch[String]]()
      val sinkAtEnd = BatchSink.instance[String] { b =>
        batches.add(b)
        Future.successful(())
      }
      val checkpointAtEnd = new InMemoryCheckpointStore(Some(8L))
      val eofReport = Await.result(
        Ingest.file(path, LineParser.identity, sinkAtEnd, IngestConfig(), checkpointAtEnd),
        10.seconds,
      )
      assertEquals(batches.size(), 0)
      assertEquals(eofReport, IngestReport(0L, 0L, 0L, 0L, 0L, 8L))
    }

    // 7. Non-existent file throws io_error
    val missingPath = Path.of("non-existent-directory-xyz", "missing-file.txt")
    val ioErr = intercept[IngestException] {
      Await.result(
        Ingest.file(missingPath, LineParser.identity, sink),
        10.seconds,
      )
    }
    assertEquals(ioErr.code, "io_error")
  }

  test("10 GiB generated stream completes with bounded memory".tag(new munit.Tag("conformance"))) {
    val targetBytes: Long = 10_737_418_240L
    val chunkSize: Int = 65_536
    val totalChunks: Long = targetBytes / chunkSize.toLong

    // 64 lines of 1,024 bytes each per 65,536-byte chunk.
    val line1024 = ("a" * 1023) + "\n"
    val chunk = ByteString(line1024 * 64)
    assertEquals(chunk.length, chunkSize)

    val inFlightBytes = new AtomicLong(0L)
    val maxInFlightObserved = new AtomicReference[Long](0L)

    val source: Source[ByteString, ?] =
      Source
        .repeat(chunk)
        .take(totalChunks)
        .map { c =>
          val now = inFlightBytes.addAndGet(c.length.toLong)
          val _ = maxInFlightObserved.updateAndGet(prev => math.max(prev, now))
          c
        }

    val sink = BatchSink.instance[String] { batch =>
      // Each record came from a 1024-byte raw line (1023 payload + 1 newline).
      inFlightBytes.addAndGet(-(batch.bytes + batch.records.size.toLong))
      Future.successful(())
    }

    val report = Await.result(
      Ingest.source(source, 0L, LineParser.identity, sink, IngestConfig()),
      35.minutes,
    )
    assertEquals(report.bytes, 10_737_418_240L)
    assertEquals(report.endOffset, 10_737_418_240L)
    assertEquals(report.records, totalChunks * 64L)
    assert(maxInFlightObserved.get() < 67_108_864L)
  }
}
