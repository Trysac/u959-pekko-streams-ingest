package io.github.trysac.pekko_streams_ingest

/** Thrown when a text helper receives only whitespace. */
final class BlankInputException extends IllegalArgumentException("input is blank")
