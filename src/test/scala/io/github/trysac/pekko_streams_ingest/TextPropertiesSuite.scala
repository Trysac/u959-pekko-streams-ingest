package io.github.trysac.pekko_streams_ingest

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Property-based tests of the text helpers and ingestion batching invariants. */
class TextPropertiesSuite extends munit.ScalaCheckSuite {

  private val nonBlank: Gen[String] =
    Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString("  ", "", " "))

  property("normalize is idempotent on non-blank strings and produces no uppercase ASCII") {
    forAll(nonBlank) { (s: String) =>
      val once = Text.normalize(s)
      assertEquals(Text.normalize(once), once)
      assert(once.forall(ch => !(ch >= 'A' && ch <= 'Z')))
    }
  }

  property("IngestConfig.validated accepts positive parameters and rejects non-positive ones") {
    forAll(Gen.choose(1, 10000), Gen.choose(1, 10000), Gen.choose(-100, 0)) {
      (pos1: Int, pos2: Int, bad: Int) =>
        val valid = IngestConfig.validated(
          chunkSize = pos1,
          maxLineBytes = pos2,
          batchMaxRecords = pos1,
          batchMaxBytes = pos2.toLong,
          parallelism = 2,
          maxErrors = 5L,
        )
        assertEquals(valid.chunkSize, pos1)
        val err = intercept[IngestException] {
          IngestConfig.validated(chunkSize = bad)
        }
        assertEquals(err.code, "invalid_config")
    }
  }
}
