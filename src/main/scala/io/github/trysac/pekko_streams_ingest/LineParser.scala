package io.github.trysac.pekko_streams_ingest

/** Parses a decoded UTF-8 line and its 1-based line number into a domain record `A` or a rejection
  * reason.
  */
trait LineParser[A] {

  /** Parses `line` at `lineNumber`.
    *
    * @return
    *   `Right(record)` when valid, or `Left(reason)` when the line should be sent to the
    *   dead-letter channel
    */
  def parse(line: String, lineNumber: Long): Either[String, A]
}

/** Companion helpers for [[LineParser]]. */
object LineParser {

  /** Creates a [[LineParser]] from a function. */
  def instance[A](f: (String, Long) => Either[String, A]): LineParser[A] =
    new LineParser[A] {
      override def parse(line: String, lineNumber: Long): Either[String, A] = f(line, lineNumber)
    }

  /** A parser that accepts every line unchanged as a `String`. */
  val identity: LineParser[String] = instance((line, _) => Right(line))
}
