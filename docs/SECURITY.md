# Security notes

## Threat model

`io.github.trysac:pekko-streams-ingest` is a pure library without I/O. The main risks are:

- **Supply chain**: a compromised dependency, sbt plugin or CI action. Mitigation: standard
  library only, every plugin pinned in `project/plugins.sbt` and every dependency pinned to an
  exact version in `build.sbt`, every resolved artifact recorded with its checksum in
  `build.sbt.lock` (the lint gate fails when it is stale), the sbt version pinned in
  `project/build.properties`, OSV-Scanner over the locked library dependencies and over the whole
  lockfile in the gates, Dependabot weekly updates, actions pinned by commit SHA, minimal workflow
  permissions.
- **Leaked secrets**: tokens committed by mistake. Mitigation: `gitleaks` runs in the local gates
  and in CI; `.env` files are git-ignored.
- **Insecure code**: command injection, unsafe deserialization, disabled TLS verification.
  Mitigation: scalafix and the Scala compiler with warnings as errors, and the factory's Semgrep
  rules (`.semgrep/factory-rules.yml`, `nosemgrep` ignored); fix the code instead of silencing it.
- **Untrusted input**: callers may pass hostile strings. Mitigation: validate input and throw
  explicit, documented exceptions; never deserialize untrusted bytes with `ObjectInputStream`;
  never start a shell or build a command line from untrusted data (`scala.sys.process` takes a
  `Seq` of arguments).

## Publishing

Releases are git tags; publishing to Maven Central needs a namespace account and a signing key,
which are never stored in the repository.

To report a vulnerability, see [SECURITY.md](../SECURITY.md).
