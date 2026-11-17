package io.github.trysac.pekko_streams_ingest

import org.scalacheck.Prop.forAll

/** Property tests (ScalaCheck) of the invariants of [[Text.normalize]]. */
class TextPropertiesSuite extends munit.ScalaCheckSuite {

  property("normalizing twice changes nothing") {
    forAll { (input: String) =>
      input.isBlank || {
        val once = Text.normalize(input)
        Text.normalize(once) == once
      }
    }
  }

  property("the output has no surrounding whitespace") {
    forAll { (input: String) =>
      input.isBlank || {
        val out = Text.normalize(input)
        out.strip() == out
      }
    }
  }
}
