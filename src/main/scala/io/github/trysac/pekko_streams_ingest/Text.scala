package io.github.trysac.pekko_streams_ingest

import java.util.Locale

/** Text helpers of this library. */
object Text {

  /** Trims surrounding whitespace and lower-cases `input`.
    *
    * @throws BlankInputException
    *   when nothing is left after trimming, instead of an empty string
    */
  def normalize(input: String): String = {
    val trimmed = input.strip()
    if (trimmed.isEmpty) throw BlankInputException()
    trimmed.toLowerCase(Locale.ROOT)
  }
}
