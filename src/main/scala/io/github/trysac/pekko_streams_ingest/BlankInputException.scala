package io.github.trysac.pekko_streams_ingest

/** The input is empty or holds only whitespace; the message is `input is blank`. */
final class BlankInputException extends IllegalArgumentException("input is blank")
