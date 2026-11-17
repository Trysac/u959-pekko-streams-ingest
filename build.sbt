// Factory-owned file: agents must not edit it. Dependencies are added by the factory
// (java scripts/AddDependency.java), every plugin and library is pinned to an exact version, and
// build.sbt.lock records every resolved artifact (the lint gate fails when it is stale).

ThisBuild / organization := "io.github.trysac"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.9.0"
// scalafix reads the SemanticDB the compiler writes.
ThisBuild / semanticdbEnabled := true

// CI-only conformance tier: tests tagged conformance run only when FABRICA_CI=1 (the CI
// conformance job); the lint gate always compiles them.
val ciTier = sys.env.get("FABRICA_CI").contains("1")
// Build output; the gates image moves it out of the mounted repository (FABRICA_BUILD_DIR).
val buildDir = sys.env.get("FABRICA_BUILD_DIR").filter(_.nonEmpty)

lazy val root = (project in file("."))
  .settings(
    name := "pekko-streams-ingest",
    description := "Line-oriented file ingestion on Pekko Streams with backpressure from a slow sink, byte-bounded batches, a dead-letter channel and resumable offset checkpoints",
    licenses := Seq("MIT" -> url("https://opensource.org/license/mit")),
    target := buildDir.fold(baseDirectory.value / "target")(dir => file(dir) / "target"),
    // Lint: every compiler warning is an error, unused code included. -no-indent keeps braces
    // (the Scaladoc check of scripts/Check.java reads class and object bodies by their braces).
    scalacOptions ++= Seq(
      "-no-indent",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Werror",
    ),
    javacOptions ++= Seq("--release", "21"),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.3.6" % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.3.1" % Test,
    ),
    Test / testOptions ++= {
      if (ciTier) Nil
      else Seq(Tests.Argument(TestFrameworks.MUnit, "--exclude-tags=conformance"))
    },
    // Coverage: scoverage writes scoverage-report/scoverage.xml; scripts/Check.java enforces the
    // threshold.
    coverageFailOnMinimum := false,
    coverageOutputHTML := false,
    coverageOutputCobertura := false,
  )
