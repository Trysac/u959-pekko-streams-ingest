package io.github.trysac.pekko_streams_ingest

/** Tests of the template text helpers. */
class TextSuite extends munit.FunSuite {

  for ((input, want) <- Seq("  Hello World " -> "hello world", "ABC" -> "abc", "\tx\n" -> "x"))
    test(s"normalize trims and lower-cases ${input.trim}") {
      assertEquals(Text.normalize(input), want)
    }

  for (input <- Seq("", "   ", "\t\n"))
    test(s"normalize rejects blank input of length ${input.length}") {
      val error = intercept[BlankInputException](Text.normalize(input))
      assertEquals(error.getMessage, "input is blank")
    }
}
