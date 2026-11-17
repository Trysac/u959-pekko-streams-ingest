# Architecture

## Purpose

`io.github.trysac:pekko-streams-ingest` is a small, dependency-free Scala 3 library: Line-oriented file ingestion on Pekko Streams with backpressure from a slow sink, byte-bounded batches, a dead-letter channel and resumable offset checkpoints

## Shape

```text
caller code --> package io.github.trysac.pekko_streams_ingest (src/main/scala)
                    |
                    +-- objects and functions that are pure; no I/O, no global mutable state
                    +-- private helpers (optional)
```

- **Public API**: every public declaration of the package. `private` and `private[pekko_streams_ingest]`
  declarations may change without a major version.
- **Errors**: invalid input throws an `IllegalArgumentException` subclass of the library (such as
  `BlankInputException`) documented with `@throws`. The library never prints or exits.
- **Side effects**: none. If a feature needs I/O, accept an `InputStream`, a `Reader` or a small
  trait instead of opening resources, and record the choice in an ADR.

## Quality contract

`scripts/Check.java` is the single definition of the gates; `fabrica.json` tells the factory and CI
how to run it. See [TESTING.md](TESTING.md).

## Decisions

Architecture decisions are recorded in [decisions/](decisions/) as numbered ADRs.
