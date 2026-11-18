# pekko-streams-ingest

Line-oriented file ingestion on Apache Pekko Streams with backpressure from a slow sink, byte-bounded batches, a dead-letter channel and resumable offset checkpoints.

## Problem

Loading multi-gigabyte line files into a slower store with a read-everything loop either exhausts the heap or floods the store, and a crash halfway forces the whole file to be reloaded. Pekko Streams provides demand-driven backpressure, but framing, byte-bounded batching, error budgets and safe checkpoints still have to be assembled correctly each time. This library packages that pipeline so memory stays bounded by configuration, not by file size, and a restart resumes after the last committed batch.

## Installation

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.github.trysac" %% "pekko-streams-ingest" % "0.1.0"
```

## Usage

```scala
import io.github.trysac.pekko_streams_ingest.*
import java.nio.file.Path
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import scala.concurrent.Future

given system: ActorSystem = ActorSystem("ingest")
given Materializer = Materializer(system)
val sink = BatchSink.instance[String](_ => Future.successful(()))
val reportF = Ingest.file(Path.of("data.log"), LineParser.identity, sink)
```

## Public API

- `IngestConfig`: validated bounds (`chunkSize`, `maxLineBytes`, `batchMaxRecords`, `batchMaxBytes`, `parallelism`, `maxErrors`). Throws `IngestException` ('invalid_config') if parameters are < 1.
- `LineParser[A]`: parses a decoded UTF-8 line and its 1-based line number into `Either[String, A]`.
- `Batch[A]`: carries `index`, `records`, `firstLine`, `lastLine`, `endOffset` and `bytes`.
- `BatchSink[A]`: writes a `Batch[A]` asynchronously (`Future[Unit]`).
- `CheckpointStore`: `InMemoryCheckpointStore` and atomic `FileCheckpointStore(path)`. Provides `load()` and `save(offset)`.
- `Ingest.file` and `Ingest.source`: run the bounded pipeline and return `Future[IngestReport]`. May fail with `IngestException` ('checkpoint_mismatch', 'io_error', 'line_too_long', 'too_many_errors', 'sink_failed').

## Why not existing alternatives?

Existing Alpakka file connectors lack built-in byte-weighted batching, ordered resilient checkpointing, and configurable dead-letter error budgets, requiring manual and error-prone implementation of these concerns. This library provides a unified, tested, and memory-bounded pipeline for these specific requirements.

See [docs/SECURITY.md](docs/SECURITY.md) for threat model and limits.