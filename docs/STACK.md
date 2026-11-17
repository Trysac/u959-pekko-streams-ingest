# Stack

## Used

| Tool | Why |
|---|---|
| Scala 3 LTS (JVM 21) | The long-term-support Scala 3 line, pinned in `build.sbt`; Java 21 LTS. |
| sbt | The standard Scala build; `project/build.properties` pins its version, `project/plugins.sbt` its plugins. |
| sbt-dependency-lock | sbt has no lockfile: `build.sbt.lock` records every resolved artifact with its checksum, and the lint gate fails when it is stale. |
| scalafmt | Layout of the Scala and sbt files (`.scalafmt.conf`), applied and checked. |
| Scala compiler + scalafix | Compiler warnings as errors (unused code included); scalafix's built-in rules (`.scalafix.conf`), any finding fails. |
| munit + ScalaCheck | Example-based suites and property-based tests (`munit.ScalaCheckSuite`). |
| scoverage | Statement coverage of the library (the Scala 3 compiler's instrumentation); the gate reads its XML report. |
| Semgrep CE + OSV-Scanner | The factory's own rules; OSV advisories over the locked library dependencies and the whole lockfile. |
| gitleaks | Secret scanning locally and in CI. |
| GitHub Actions + Dependabot | Thin CI wrapper over the local gates; weekly dependency updates. |

## Discarded

| Tool | Why not |
|---|---|
| sbt 2 | Not every plugin of the build is published for it yet (sbt-dependency-lock). |
| Mill, Scala CLI | sbt is the build Dependabot, scoverage and scalafix support best. |
| ScalaTest | munit is smaller, has tags for the conformance tier and integrates ScalaCheck. |
| WartRemover | The compiler's strict warnings and scalafix cover the lint without another compiler plugin. |
| Shell scripts for gates | Not portable to Windows; the gates are a Java program in `scripts/Check.java`. |
