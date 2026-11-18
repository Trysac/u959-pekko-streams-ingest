// Factory-owned file: agents must not edit it. Every sbt plugin of the build, pinned to an exact version.

// Format: scalafmt (the version .scalafmt.conf pins).
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
// Lint: scalafix with the rules of .scalafix.conf.
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.10")
// Coverage: scoverage (the Scala 3 compiler's own instrumentation).
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
// Lockfile: build.sbt.lock records every resolved artifact; the lint gate fails when it is stale.
addSbtPlugin("software.purpledragon" % "sbt-dependency-lock" % "1.5.1")

// The build definition's own output; the gates image moves it out of the mounted repository.
sys.env.get("FABRICA_BUILD_DIR").filter(_.nonEmpty).toList.map { dir =>
  target := file(dir) / "project-target"
}
