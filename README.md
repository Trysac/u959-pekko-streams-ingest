# pekko-streams-ingest

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![CI](https://github.com/Trysac/u959-pekko-streams-ingest/actions/workflows/ci.yml/badge.svg)](https://github.com/Trysac/u959-pekko-streams-ingest/actions/workflows/ci.yml)

Line-oriented file ingestion on Pekko Streams with backpressure from a slow sink, byte-bounded batches, a dead-letter channel and resumable offset checkpoints

## Problem

Describe in two or three sentences the concrete problem this utility solves, who hits it, and what
they do today without it. Replace this paragraph when the first real feature lands.

## Install

Maven coordinates `io.github.trysac:pekko-streams-ingest_3` (Scala 3). Until it is published to Maven
Central, build it from a release tag through JitPack (`https://jitpack.io`), then declare in
`build.sbt`:

```scala
libraryDependencies += "io.github.trysac" %% "pekko-streams-ingest" % "0.1.0"
```

Requires Scala 3.9 or newer and Java 21 or newer. No dependencies outside the Scala standard
library.

## Usage

```scala
import io.github.trysac.pekko_streams_ingest.Text

// normalize trims surrounding whitespace and lower-cases the text.
val name = Text.normalize("  Hello World ") // "hello world"

// Blank input is rejected explicitly instead of returning an empty string.
Text.normalize("   ") // throws BlankInputException ("input is blank")
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) and [decisions](docs/decisions/)
- [Testing](docs/TESTING.md) and [security](docs/SECURITY.md)
- [Project record](docs/PROJECT.md) and [stack](docs/STACK.md)
- [Changelog](CHANGELOG.md) and [contributing guide](CONTRIBUTING.md)

## License

[MIT](LICENSE) (c) 2026 Miguel Gomez
