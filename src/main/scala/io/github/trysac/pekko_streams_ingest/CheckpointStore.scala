package io.github.trysac.pekko_streams_ingest

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** Stores and retrieves the committed byte offset of an ingestion pipeline. */
trait CheckpointStore {

  /** Loads the last committed byte offset, or `None` if no checkpoint has been saved yet. */
  def load(): Option[Long]

  /** Saves `offset` as the latest committed byte offset. */
  def save(offset: Long): Unit
}

/** Thread-safe in-memory [[CheckpointStore]] for tests and single-process ephemeral runs.
  *
  * @param initial
  *   optional initial byte offset
  */
final class InMemoryCheckpointStore(initial: Option[Long] = None) extends CheckpointStore {
  private val ref = new AtomicReference[Option[Long]](initial)
  private val historyBuf = scala.collection.mutable.ArrayBuffer.empty[Long]

  /** Returns the last saved offset, or `initial` when nothing was saved yet. */
  override def load(): Option[Long] = ref.get()

  /** Stores `offset` and appends it to [[savedOffsets]]. */
  override def save(offset: Long): Unit = synchronized {
    ref.set(Some(offset))
    if (historyBuf.size < 10_000) {
      historyBuf += offset
    }
  }

  /** Returns the sequence of offsets saved to this store in call order (capped at 10,000 entries).
    */
  def savedOffsets: Vector[Long] = synchronized {
    historyBuf.toVector
  }
}

/** Durable file-backed [[CheckpointStore]] that writes the decimal byte offset to a temporary file
  * and atomically moves it over `path`.
  *
  * @param path
  *   path to the checkpoint file
  */
final class FileCheckpointStore(val path: Path) extends CheckpointStore {

  /** Reads the offset stored in `path`, or `None` when the file is missing or empty. */
  override def load(): Option[Long] = {
    try {
      if (!Files.exists(path)) None
      else {
        val raw = Files.readString(path, StandardCharsets.UTF_8).strip()
        if (raw.isEmpty) None
        else {
          val parsed = raw.toLongOption.getOrElse {
            throw IngestException.checkpointMismatch(-1L, -1L)
          }
          Some(parsed)
        }
      }
    } catch {
      case e: IngestException => throw e
      case NonFatal(e)        =>
        throw IngestException.ioError("Failed to read checkpoint file", e)
    }
  }

  /** Writes `offset` to a temporary file and atomically moves it over `path`. */
  override def save(offset: Long): Unit = {
    try {
      val parent = Option(path.toAbsolutePath.getParent).getOrElse(Path.of("."))
      Files.createDirectories(parent)
      val tmp = Files.createTempFile(parent, path.getFileName.toString + ".", ".tmp")
      try {
        Files.writeString(tmp, offset.toString, StandardCharsets.UTF_8)
        try {
          val _ = Files.move(
            tmp,
            path,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
          )
        } catch {
          case _: AtomicMoveNotSupportedException =>
            val _ = Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
      } finally {
        val _ = Files.deleteIfExists(tmp)
      }
    } catch {
      case NonFatal(e) =>
        throw IngestException.ioError("Failed to save checkpoint file", e)
    }
  }
}
