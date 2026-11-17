# AGENTS.md

Instructions for coding agents (and humans) working on the Scala library
`io.github.trysac:pekko-streams-ingest` (package `io.github.trysac.pekko_streams_ingest`).

## Commands

`sbt` is the sbt launcher (it starts the sbt version `project/build.properties` pins). The gate
runner is a Java program, so the same commands work on Windows and Linux.

| Task | Command |
|---|---|
| Fetch dependencies | `sbt update` |
| Run every gate (must pass before a PR) | `java scripts/Check.java` |
| Tests only | `sbt test` |
| Format | `sbt scalafmtAll scalafmtSbt` |
| Lint | `sbt Test/compile "scalafixAll --check" dependencyLockCheck` (compiler warnings are errors) |
| Coverage | `sbt coverage test coverageReport`, then `target/scala-3.9.0/scoverage-report/` |

The gate sequence is: scalafmt (`scalafmtCheckAll scalafmtSbtCheck`), the Scala 3 compiler with
warnings as errors plus scalafix, an up-to-date `build.sbt.lock` and Scaladoc on the public API,
Semgrep (the factory's rules in `.semgrep/`), munit and ScalaCheck tests under scoverage with
statement coverage >= 80 % of the library, documentation manifest (`docs-manifest.json`),
gitleaks, OSV-Scanner over the library's locked dependencies, and OSV-Scanner over every artifact
of `build.sbt.lock`. It stops at the first failure.

## Folder map

| Path | Content |
|---|---|
| `src/main/scala/io/github/trysac/pekko_streams_ingest/` | The library; its public declarations are the public API. |
| `src/test/scala/io/github/trysac/pekko_streams_ingest/` | munit suites (`*Suite.scala`) and ScalaCheck properties. |
| `src/test/resources/fixtures/` | Optional fixtures and golden files used by tests. |
| `scripts/` | The single definition of the quality gates (`Check.java`) and the factory's dependency tool. |
| `docs/` | Architecture, decisions (ADRs), testing, security, project record, stack. |
| `project/` | The sbt version and the build's plugins, pinned. |
| `fabrica.json` | Install and check commands used by the factory and CI. |
| `docs-manifest.json` | Documentation files the docs gate requires. |
| `.github/` | CI wrapper and Dependabot configuration. |

## Conventions

- Scala 3 (LTS) on the JVM 21, formatted by scalafmt, clean under the compiler with warnings as
  errors (`-Werror`, `-Wunused:all`) and scalafix; braces, not indentation syntax (`-no-indent`).
- Standard library only unless an ADR in `docs/decisions/` justifies a dependency.
- Every public declaration has a Scaladoc comment (`/** ... */`); invalid input throws a
  documented `IllegalArgumentException` subclass, never returns null or a sentinel value.
- Write the failing test first; tests use every literal of the acceptance items verbatim, and
  ScalaCheck properties (`property(...)` in a `munit.ScalaCheckSuite`) cover invariants.
- CI-only conformance tests (external tools or downloads) carry the munit tag
  `new munit.Tag("conformance")`; only CI runs them.
- Every behavior change updates `README.md` usage, `CHANGELOG.md` (Unreleased) and, if it is a
  design choice, a new ADR.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`).

## Limits

- Never push to `main`; work on a branch and open a pull request.
- Do NOT edit `.github/`, `project/`, `fabrica.json`, `docs-manifest.json`, `scripts/`,
  `build.sbt`, `build.sbt.lock`, `.scalafmt.conf`, `.scalafix.conf`, `.editorconfig` or any tool
  configuration. They are the quality contract; changes to them are made by a human.
- Never lower the coverage threshold, add `@nowarn` or a `scalafix:off` comment to silence a
  lint, or disable a test to make a gate pass.
- Never commit secrets, tokens or `.env` files; gitleaks blocks them.
