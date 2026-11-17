// Runs every quality gate of this repository, in order, stopping at the first failure.
//
// Usage: `java scripts/Check.java` (the same command locally, in the factory and in CI); append
// gate names to run only those, in the standard order.
//
// Gates: scalafmt, the Scala 3 compiler with warnings as errors plus scalafix, a stale
// build.sbt.lock and Scaladoc on the public API, semgrep (Semgrep CE with the factory's own rules
// in .semgrep/), tests (munit and ScalaCheck under scoverage) with statement coverage >= 80 % of
// the library, documentation manifest, secrets (gitleaks), known vulnerabilities of the library's
// locked dependencies (OSV-Scanner over a CycloneDX list of the compile, runtime and test
// artifacts of build.sbt.lock) and OSV-Scanner over every artifact of build.sbt.lock (high
// severity). sbt is the launcher on PATH (it starts the version project/build.properties pins).
// No shell: it behaves the same on Windows and Linux. Single-file source program: Java 21,
// standard library only.

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The gate runner. */
public final class Check {

  static final double MIN_COVERAGE = 80.0;
  static final String DOCS_MANIFEST = "docs-manifest.json";
  static final String ALLOW_MISSING_GITLEAKS = "FABRICA_ALLOW_MISSING_GITLEAKS";
  static final String ALLOW_OFFLINE_AUDIT = "FABRICA_ALLOW_OFFLINE_AUDIT";
  // FABRICA_CI=1 also runs the tests tagged conformance (external tools or downloads); only the CI
  // conformance job sets it, so local gates and coverage skip them (build.sbt).
  static final String CI_TIER = "FABRICA_CI";
  // The factory's own Semgrep rules; Semgrep-maintained rules are never used (license).
  static final String SEMGREP_RULES = ".semgrep/factory-rules.yml";
  static final String LOCKFILE = "build.sbt.lock";
  // The configurations of the library and its tests; the rest of the lockfile is build tooling
  // (scalafix), which the osv gate still scans for high severity.
  static final List<String> LIBRARY_CONFIGURATIONS =
      List.of("compile", "optional", "provided", "runtime", "test");
  static final String SOURCES = "src/main/scala";
  static final double HIGH_CVSS = 7.0;
  // osv-scanner's exit codes: 1 when vulnerabilities were found, 128 when no package was found.
  static final int OSV_FOUND = 1;
  static final int OSV_NO_PACKAGES = 128;
  static final List<String> GATES =
      List.of("format", "lint", "semgrep", "tests", "docs", "secrets", "audit", "osv");

  private Check() {}

  /** A failed gate. */
  static final class GateFailure extends Exception {
    private static final long serialVersionUID = 1L;

    GateFailure(String message) {
      super(message);
    }
  }

  public static void main(String[] args) {
    if (!Files.isRegularFile(Path.of("build.sbt"))
        || !Files.isRegularFile(Path.of("scripts/Check.java"))) {
      System.err.println("check: run from the repository root (build.sbt not found)");
      System.exit(2);
    }
    for (String name : args) {
      if (!GATES.contains(name)) {
        System.err.println("check: unknown gate \"" + name + "\"");
        System.exit(2);
      }
    }
    List<String> only = List.of(args);
    for (String name : GATES) {
      if (!only.isEmpty() && !only.contains(name)) {
        continue;
      }
      System.out.println("==> " + name);
      System.out.flush();
      try {
        runGate(name);
      } catch (GateFailure | IOException | InterruptedException e) {
        System.err.println(name + ": " + e.getMessage());
        System.err.println("==> " + name + ": FAILED");
        System.exit(1);
      }
      System.out.println("==> " + name + ": ok");
    }
    System.out.println("==> all gates passed");
  }

  static void runGate(String name) throws GateFailure, IOException, InterruptedException {
    switch (name) {
      case "format" -> formatGate();
      case "lint" -> lintGate();
      case "semgrep" -> semgrepGate();
      case "tests" -> testsGate();
      case "docs" -> docsGate();
      case "secrets" -> secretsGate();
      case "audit" -> auditGate();
      case "osv" -> osvGate();
      default -> throw new GateFailure("unknown gate " + name);
    }
  }

  static boolean flag(String name) {
    return "1".equals(System.getenv(name));
  }

  static boolean ciTier() {
    return flag(CI_TIER);
  }

  /** The program's path when it is on PATH (with the Windows executable suffixes). */
  static Optional<String> which(String program) {
    String path = System.getenv("PATH");
    if (path == null) {
      return Optional.empty();
    }
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    List<String> suffixes = windows ? List.of(".exe", ".cmd", ".bat", "") : List.of("");
    for (String dir : path.split(File.pathSeparator)) {
      for (String suffix : suffixes) {
        Path candidate = Paths.get(dir.isEmpty() ? "." : dir, program + suffix);
        if (Files.isRegularFile(candidate)) {
          return Optional.of(candidate.toString());
        }
      }
    }
    return Optional.empty();
  }

  /** Runs a tool from the repository root, streaming its output; returns its exit code. */
  static int exec(List<String> argv) throws IOException, InterruptedException {
    return new ProcessBuilder(argv).inheritIO().start().waitFor();
  }

  static void run(List<String> argv) throws GateFailure, IOException, InterruptedException {
    int code = exec(argv);
    if (code != 0) {
      throw new GateFailure("`" + String.join(" ", argv) + "` failed (exit " + code + ")");
    }
  }

  /** sbt in batch mode, without colors (the launcher on PATH: sbt, or sbt.bat on Windows). */
  static List<String> sbt(String... args) throws GateFailure {
    String exe =
        which("sbt")
            .orElseThrow(
                () ->
                    new GateFailure(
                        "sbt is not installed (https://www.scala-sbt.org/download/); the"
                            + " factory runs the gates in the fabrica-scala-gates image"));
    List<String> argv = new ArrayList<>(List.of(exe, "--batch", "--no-colors"));
    argv.addAll(List.of(args));
    return argv;
  }

  static void formatGate() throws GateFailure, IOException, InterruptedException {
    try {
      run(sbt("scalafmtCheckAll", "scalafmtSbtCheck"));
    } catch (GateFailure e) {
      throw new GateFailure(e.getMessage() + ": run `sbt scalafmtAll scalafmtSbt`");
    }
    System.out.println("scalafmt: every file is formatted");
  }

  /**
   * The Scala 3 compiler (warnings are errors) on the library and its tests (conformance tests
   * included), scalafix with the rules of .scalafix.conf, build.sbt.lock up to date with the
   * build, and Scaladoc on every public declaration of the library.
   */
  static void lintGate() throws GateFailure, IOException, InterruptedException {
    run(sbt("Test/compile", "scalafixAll --check", "dependencyLockCheck"));
    List<String> undocumented = undocumented(Path.of(SOURCES));
    for (String line : undocumented) {
      System.err.println("scaladoc: " + line);
    }
    if (!undocumented.isEmpty()) {
      throw new GateFailure(
          undocumented.size() + " public declaration(s) without a Scaladoc comment (/** ... */)");
    }
    System.out.println("scalac + scalafix + build.sbt.lock + Scaladoc: no findings");
  }

  // A declaration that starts a line, with its modifiers; private and protected ones never match
  // (they are not in the modifier list), nor do local definitions or constructor parameters (see
  // undocumentedIn).
  static final Pattern DECLARATION =
      Pattern.compile(
          "^\\s*(?:@\\S+\\s+)*(?:(?:final|sealed|abstract|case|implicit|lazy|override|inline"
              + "|open|transparent|opaque|infix)\\s+)*(class|object|trait|enum|def|val|type"
              + "|given)\\b");
  static final Pattern TEMPLATE = Pattern.compile("^(?:class|object|trait|enum)$");

  /** Every public declaration of the library's sources without a Scaladoc comment above it. */
  static List<String> undocumented(Path dir) throws IOException {
    List<String> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    List<Path> files;
    try (Stream<Path> walk = Files.walk(dir)) {
      files = walk.filter(p -> p.toString().endsWith(".scala")).sorted().toList();
    }
    for (Path file : files) {
      String rel = file.toString().replace('\\', '/');
      for (int line : undocumentedIn(Files.readString(file, StandardCharsets.UTF_8))) {
        out.add(rel + ":" + line + ": no Scaladoc comment");
      }
    }
    return out;
  }

  /**
   * The 1-based lines of the public declarations of one source without a Scaladoc comment. Only
   * the members of a class, object, trait or enum body (and the top level) count: braces track the
   * bodies, which the compiler's -no-indent option guarantees. A template header may wrap (its
   * constructor parameters, or `extends X {` on a later line): the first brace after it, at the
   * same parenthesis depth, opens its body; a new declaration first means it has no body.
   * Declarations inside parentheses (constructor parameters) are not members. String literals
   * (triple-quoted ones too, across lines) and comments are ignored when counting brackets.
   */
  static List<Integer> undocumentedIn(String source) {
    List<Integer> out = new ArrayList<>();
    List<Boolean> bodies = new ArrayList<>();
    String[] lines = source.replace("\r\n", "\n").split("\n", -1);
    Scan state = new Scan();
    int parens = 0;
    boolean pendingTemplate = false;
    String previous = "";
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      boolean startsInCode = !state.comment && !state.triple;
      String code = state.next(line);
      Matcher m = DECLARATION.matcher(line);
      boolean declaration = startsInCode && parens == 0 && !code.isBlank() && m.find();
      boolean member = bodies.isEmpty() || bodies.get(bodies.size() - 1);
      if (declaration) {
        pendingTemplate = TEMPLATE.matcher(m.group(1)).matches();
        if (member && !previous.endsWith("*/")) {
          out.add(i + 1);
        }
      }
      for (char c : code.toCharArray()) {
        if (c == '(' || c == '[') {
          parens++;
        } else if ((c == ')' || c == ']') && parens > 0) {
          parens--;
        } else if (c == '{' && parens == 0) {
          bodies.add(pendingTemplate);
          pendingTemplate = false;
        } else if (c == '{') {
          bodies.add(false);
        } else if (c == '}' && !bodies.isEmpty()) {
          bodies.remove(bodies.size() - 1);
        }
      }
      String stripped = line.strip();
      if (!stripped.isEmpty() && !stripped.startsWith("@")) {
        previous = stripped;
      }
    }
    return out;
  }

  /** A line scanner that keeps block comments and triple-quoted strings open across lines. */
  static final class Scan {
    boolean comment;
    boolean triple;

    /** The code of the next line: without string and character literals and comments. */
    String next(String line) {
      StringBuilder sb = new StringBuilder();
      int i = 0;
      while (i < line.length()) {
        if (comment) {
          int end = line.indexOf("*/", i);
          if (end < 0) {
            return sb.toString();
          }
          comment = false;
          i = end + 2;
        } else if (triple) {
          int end = line.indexOf("\"\"\"", i);
          if (end < 0) {
            return sb.toString();
          }
          triple = false;
          i = end + 3;
          while (i < line.length() && line.charAt(i) == '"') {
            i++; // a triple-quoted string may end with extra quotes ("""a"""")
          }
        } else if (line.startsWith("//", i)) {
          return sb.toString();
        } else if (line.startsWith("/*", i)) {
          comment = true;
          i += 2;
        } else if (line.startsWith("\"\"\"", i)) {
          triple = true;
          i += 3;
        } else if (line.charAt(i) == '"' || line.charAt(i) == '\'') {
          i = literalEnd(line, i);
        } else {
          sb.append(line.charAt(i));
          i++;
        }
      }
      return sb.toString();
    }

    /**
     * The index after a one-line string or character literal starting at {@code start}: a
     * backslash escapes the next character, so "a\\" ends at its second quote. A lone quote (a
     * Scala 2 symbol) is just skipped.
     */
    static int literalEnd(String line, int start) {
      char quote = line.charAt(start);
      int i = start + 1;
      while (i < line.length()) {
        char c = line.charAt(i);
        if (c == '\\') {
          i += 2;
        } else if (c == quote) {
          return i + 1;
        } else {
          i++;
        }
      }
      return quote == '\'' ? start + 1 : line.length();
    }
  }

  static Path buildDir() {
    String dir = System.getenv("FABRICA_BUILD_DIR");
    return dir == null || dir.isEmpty() ? Path.of("target") : Path.of(dir, "target");
  }

  static void testsGate() throws GateFailure, IOException, InterruptedException {
    if (ciTier()) {
      System.out.println("tests: CI tier, the conformance tests run too");
    }
    Path report = buildDir().resolve("scala-3.9.0/scoverage-report/scoverage.xml");
    Files.deleteIfExists(report);
    run(sbt("coverage", "test", "coverageReport"));
    if (!Files.isRegularFile(report)) {
      throw new GateFailure("no coverage report at " + report);
    }
    double total = statementCoverage(Files.readString(report, StandardCharsets.UTF_8));
    System.out.printf(Locale.ROOT, "Total coverage: %.2f%%%n", total);
    if (total < MIN_COVERAGE) {
      throw new GateFailure(
          String.format(
              Locale.ROOT,
              "total statement coverage %.1f%% is below %.0f%%",
              total,
              MIN_COVERAGE));
    }
    System.out.printf(
        Locale.ROOT, "tests: statement coverage %.1f%% (minimum %.0f%%)%n", total, MIN_COVERAGE);
  }

  /**
   * The statement coverage percentage of a scoverage XML report: the counts of the root {@code
   * <scoverage>} element (its {@code statement-rate} is rounded to two decimals).
   */
  static double statementCoverage(String xml) throws GateFailure {
    Matcher root = SCOVERAGE_ROOT.matcher(xml);
    if (!root.find()) {
      throw new GateFailure("the coverage report has no <scoverage> element");
    }
    Matcher count = STATEMENT_COUNT.matcher(root.group());
    Matcher invoked = STATEMENTS_INVOKED.matcher(root.group());
    if (!count.find() || !invoked.find()) {
      throw new GateFailure("no statement counts in the coverage report");
    }
    long total = Long.parseLong(count.group(1));
    if (total == 0) {
      throw new GateFailure("the coverage report has no statements");
    }
    return 100.0 * Long.parseLong(invoked.group(1)) / total;
  }

  static final Pattern SCOVERAGE_ROOT = Pattern.compile("<scoverage\\b[^>]*>");
  static final Pattern STATEMENT_COUNT = Pattern.compile("\\bstatement-count=\"(\\d+)\"");
  static final Pattern STATEMENTS_INVOKED = Pattern.compile("\\bstatements-invoked=\"(\\d+)\"");

  static Optional<String> readText(Path root, String rel) {
    Path path = root.resolve(rel);
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  static int countLines(String text) {
    String t = text.replace("\r\n", "\n");
    if (t.isEmpty()) {
      return 0;
    }
    while (t.endsWith("\n")) {
      t = t.substring(0, t.length() - 1);
    }
    return t.split("\n", -1).length;
  }

  /** Matches one path segment against a glob segment with *, ? and [...] (ranges such as 0-9). */
  static boolean segmentMatches(String pattern, String name) {
    if (pattern.isEmpty()) {
      return name.isEmpty();
    }
    char p = pattern.charAt(0);
    if (p == '*') {
      for (int skip = 0; skip <= name.length(); skip++) {
        if (segmentMatches(pattern.substring(1), name.substring(skip))) {
          return true;
        }
      }
      return false;
    }
    if (name.isEmpty()) {
      return false;
    }
    if (p == '?') {
      return segmentMatches(pattern.substring(1), name.substring(1));
    }
    if (p == '[') {
      int close = pattern.indexOf(']');
      if (close < 0) {
        return name.charAt(0) == '[' && segmentMatches(pattern.substring(1), name.substring(1));
      }
      String cls = pattern.substring(1, close);
      char c = name.charAt(0);
      boolean hit = false;
      int i = 0;
      while (i < cls.length()) {
        if (i + 2 < cls.length() && cls.charAt(i + 1) == '-') {
          hit |= cls.charAt(i) <= c && c <= cls.charAt(i + 2);
          i += 3;
        } else {
          hit |= cls.charAt(i) == c;
          i += 1;
        }
      }
      return hit && segmentMatches(pattern.substring(close + 1), name.substring(1));
    }
    return name.charAt(0) == p && segmentMatches(pattern.substring(1), name.substring(1));
  }

  /** Whether some regular file under dir matches the remaining glob segments. */
  static boolean globHasFile(Path dir, List<String> segments) {
    if (segments.isEmpty() || !Files.isDirectory(dir)) {
      return false;
    }
    List<Path> entries;
    try (Stream<Path> listed = Files.list(dir)) {
      entries = listed.sorted(Comparator.naturalOrder()).toList();
    } catch (IOException e) {
      return false;
    }
    List<String> rest = segments.subList(1, segments.size());
    for (Path entry : entries) {
      if (!segmentMatches(segments.get(0), entry.getFileName().toString())) {
        continue;
      }
      if (rest.isEmpty() ? Files.isRegularFile(entry) : globHasFile(entry, rest)) {
        return true;
      }
    }
    return false;
  }

  /** Every violation of docs-manifest.json; an empty list means the gate passes. */
  static List<String> docsErrors(Path root) throws GateFailure {
    String raw =
        readText(root, DOCS_MANIFEST)
            .orElseThrow(() -> new GateFailure(DOCS_MANIFEST + " not found"));
    Map<String, Object> manifest = Json.object(Json.parse(raw));
    List<String> errors = new ArrayList<>();
    for (Object rel : Json.array(manifest.get("required"))) {
      Optional<String> text = readText(root, rel.toString());
      if (text.isEmpty()) {
        errors.add("missing required file: " + rel);
      } else if (text.get().isBlank()) {
        errors.add("required file is empty: " + rel);
      }
    }
    for (Object pattern : Json.array(manifest.get("required_globs"))) {
      if (!globHasFile(root, List.of(pattern.toString().split("/")))) {
        errors.add("no file matches required pattern: " + pattern);
      }
    }
    for (Map.Entry<String, Object> limit : Json.object(manifest.get("max_lines")).entrySet()) {
      Optional<String> text = readText(root, limit.getKey());
      long max = limit.getValue() instanceof Double d ? d.longValue() : 0;
      if (text.isPresent() && countLines(text.get()) > max) {
        errors.add(limit.getKey() + " has " + countLines(text.get()) + " lines (max " + max + ")");
      }
    }
    for (Map.Entry<String, Object> ref : Json.object(manifest.get("must_reference")).entrySet()) {
      Optional<String> text = readText(root, ref.getKey());
      String needle = String.valueOf(ref.getValue());
      if (text.isPresent() && !text.get().contains(needle)) {
        errors.add(ref.getKey() + " must reference " + needle);
      }
    }
    return errors;
  }

  static void docsGate() throws GateFailure {
    List<String> errors = docsErrors(Path.of("."));
    for (String err : errors) {
      System.err.println("docs: " + err);
    }
    if (!errors.isEmpty()) {
      throw new GateFailure(errors.size() + " documentation problem(s)");
    }
    System.out.println("docs: every file in docs-manifest.json is present");
  }

  static void secretsGate() throws GateFailure, IOException, InterruptedException {
    Optional<String> exe = which("gitleaks");
    if (exe.isEmpty()) {
      if (flag(ALLOW_MISSING_GITLEAKS)) {
        System.out.println("gitleaks: SKIPPED (not installed)");
        return;
      }
      throw new GateFailure(
          "FAIL - gitleaks is not installed. Install it"
              + " (https://github.com/gitleaks/gitleaks#installing), e.g. `winget install"
              + " Gitleaks.Gitleaks`, `brew install gitleaks` or a release binary, or set "
              + ALLOW_MISSING_GITLEAKS
              + "=1 to skip this gate locally.");
    }
    run(List.of(exe.get(), "dir", ".", "--redact", "--verbose", "--no-banner"));
  }

  /** A scanner that is not installed: skipped locally, a failure in CI (FABRICA_CI=1). */
  static void missingScanner(String tool) throws GateFailure {
    if (ciTier()) {
      throw new GateFailure(tool + " not installed (required when " + CI_TIER + "=1)");
    }
    System.out.println(tool + ": skipped: " + tool + " not installed");
  }

  /**
   * Semgrep CE with the factory's own rules only; ERROR rules fail and nosemgrep comments are
   * ignored. Tests and scripts/ are not scanned.
   */
  static void semgrepGate() throws GateFailure, IOException, InterruptedException {
    Optional<String> exe = which("semgrep");
    if (exe.isEmpty()) {
      missingScanner("semgrep");
      return;
    }
    run(
        List.of(
            exe.get(),
            "scan",
            "--config",
            SEMGREP_RULES,
            "--error",
            "--severity",
            "ERROR",
            "--metrics",
            "off",
            "--disable-version-check",
            "--disable-nosem",
            "--quiet",
            "--exclude=scripts",
            "--exclude=src/test",
            "--exclude=target",
            "--exclude=project",
            "."));
  }

  /**
   * The artifacts of build.sbt.lock (sbt-dependency-lock's JSON: one entry per resolved module
   * with its configurations) locked for at least one of the given configurations (every
   * configuration when null), as [group, artifact, version].
   */
  static List<String[]> lockedArtifacts(String lockfile, List<String> configurations) {
    List<String[]> out = new ArrayList<>();
    for (Object dep : Json.array(Json.object(Json.parse(lockfile)).get("dependencies"))) {
      Map<String, Object> d = Json.object(dep);
      List<String> confs =
          Json.array(d.get("configurations")).stream().map(String::valueOf).toList();
      if (configurations == null || confs.stream().anyMatch(configurations::contains)) {
        String group = String.valueOf(d.get("org"));
        String artifact = String.valueOf(d.get("name"));
        out.add(new String[] {group, artifact, String.valueOf(d.get("version"))});
      }
    }
    return out;
  }

  static List<String[]> readLock(List<String> configurations) throws GateFailure, IOException {
    Path lockfile = Path.of(LOCKFILE);
    if (!Files.isRegularFile(lockfile)) {
      throw new GateFailure(LOCKFILE + " not found: run `sbt dependencyLockWrite`");
    }
    try {
      return lockedArtifacts(Files.readString(lockfile, StandardCharsets.UTF_8), configurations);
    } catch (IllegalArgumentException e) {
      throw new GateFailure(LOCKFILE + " is not valid JSON: " + e.getMessage());
    }
  }

  /** A CycloneDX 1.5 JSON document listing the artifacts by their Maven package URL. */
  static String cycloneDx(List<String[]> artifacts) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"bomFormat\": \"CycloneDX\", \"specVersion\": \"1.5\", \"version\": 1,")
        .append(" \"components\": [");
    for (int i = 0; i < artifacts.size(); i++) {
      String[] a = artifacts.get(i);
      String purl = "pkg:maven/" + a[0] + "/" + a[1] + "@" + a[2];
      sb.append(i == 0 ? "" : ", ")
          .append("{\"type\": \"library\", \"group\": ")
          .append(Json.quote(a[0]))
          .append(", \"name\": ")
          .append(Json.quote(a[1]))
          .append(", \"version\": ")
          .append(Json.quote(a[2]))
          .append(", \"purl\": ")
          .append(Json.quote(purl))
          .append(", \"bom-ref\": ")
          .append(Json.quote(purl))
          .append('}');
    }
    return sb.append("]}\n").toString();
  }

  /** The exit code and the standard output of OSV-Scanner over the artifacts as a CycloneDX SBOM. */
  static Object[] osvScan(String exe, List<String[]> artifacts, boolean json)
      throws IOException, InterruptedException {
    Path dir = Files.createTempDirectory("fabrica-osv-");
    try {
      Path bom = dir.resolve("dependencies.cdx.json");
      Files.writeString(bom, cycloneDx(artifacts), StandardCharsets.UTF_8);
      List<String> argv = new ArrayList<>(List.of(exe, "scan", "source"));
      if (json) {
        argv.addAll(List.of("--format", "json"));
      }
      argv.add("--lockfile=" + bom.toAbsolutePath());
      ProcessBuilder pb = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.INHERIT);
      if (!json) {
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
      }
      Process proc = pb.start();
      byte[] stdout = json ? proc.getInputStream().readAllBytes() : new byte[0];
      int code = proc.waitFor();
      return new Object[] {code, new String(stdout, StandardCharsets.UTF_8)};
    } finally {
      deleteTree(dir);
    }
  }

  /**
   * Every locked artifact of the library and its tests (transitive) against OSV; any known
   * vulnerability fails, like pip-audit or npm audit over a lockfile. The list comes from
   * build.sbt.lock, written as a CycloneDX SBOM for OSV-Scanner.
   */
  static void auditGate() throws GateFailure, IOException, InterruptedException {
    Optional<String> exe = which("osv-scanner");
    if (exe.isEmpty()) {
      if (flag(ALLOW_OFFLINE_AUDIT)) {
        System.out.println("audit: SKIPPED (osv-scanner not installed)");
        return;
      }
      throw new GateFailure(
          "osv-scanner is not installed (go install"
              + " github.com/google/osv-scanner/v2/cmd/osv-scanner@v2.6.0), or set "
              + ALLOW_OFFLINE_AUDIT
              + "=1 to skip this gate locally");
    }
    List<String[]> artifacts = readLock(LIBRARY_CONFIGURATIONS);
    if (artifacts.isEmpty()) {
      System.out.println("audit: no locked library dependencies");
      return;
    }
    int code = (int) osvScan(exe.get(), artifacts, false)[0];
    if (code == OSV_FOUND) {
      throw new GateFailure("known vulnerabilities in the locked library dependencies");
    }
    if (code != 0) {
      throw new GateFailure("osv-scanner failed (exit " + code + ")");
    }
    System.out.println(
        "audit: no known vulnerabilities in " + artifacts.size() + " locked artifacts");
  }

  static void deleteTree(Path dir) throws IOException {
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  /** The advisory groups with CVSS >= 7.0 or no score (unknown counts as high). */
  static List<String> osvBlocking(Object report) {
    List<String> out = new ArrayList<>();
    for (Object result : Json.array(Json.object(report).get("results"))) {
      for (Object pkg : Json.array(Json.object(result).get("packages"))) {
        Map<String, Object> info = Json.object(Json.object(pkg).get("package"));
        String name = String.valueOf(info.getOrDefault("name", ""));
        String version = String.valueOf(info.getOrDefault("version", ""));
        for (Object group : Json.array(Json.object(pkg).get("groups"))) {
          Map<String, Object> g = Json.object(group);
          String severity = String.valueOf(g.getOrDefault("max_severity", "")).strip();
          Double score = null;
          try {
            score = Double.valueOf(severity);
          } catch (NumberFormatException e) {
            score = null;
          }
          if (score == null || score >= HIGH_CVSS) {
            List<String> ids = Json.array(g.get("ids")).stream().map(String::valueOf).toList();
            String shown = score == null ? "unknown" : severity;
            out.add(name + "@" + version + " " + String.join(",", ids) + " (" + shown + ")");
          }
        }
      }
    }
    return out;
  }

  /** Every locked artifact of build.sbt.lock, tooling included; high or unknown fails. */
  static void osvGate() throws GateFailure, IOException, InterruptedException {
    Optional<String> exe = which("osv-scanner");
    if (exe.isEmpty()) {
      missingScanner("osv-scanner");
      return;
    }
    List<String[]> artifacts = readLock(null);
    if (artifacts.isEmpty()) {
      System.out.println("osv-scanner: no packages in " + LOCKFILE);
      return;
    }
    Object[] scan = osvScan(exe.get(), artifacts, true);
    int code = (int) scan[0];
    if (code == 0) {
      System.out.println("osv-scanner: no known vulnerabilities in " + LOCKFILE);
      return;
    }
    if (code == OSV_NO_PACKAGES) {
      System.out.println("osv-scanner: no packages in " + LOCKFILE);
      return;
    }
    if (code != OSV_FOUND) {
      throw new GateFailure("osv-scanner failed (exit " + code + ")");
    }
    List<String> blocking;
    try {
      blocking = osvBlocking(Json.parse((String) scan[1]));
    } catch (IllegalArgumentException e) {
      throw new GateFailure("osv-scanner: unreadable JSON report: " + e.getMessage());
    }
    for (String line : blocking) {
      System.err.println("osv-scanner: " + line);
    }
    if (!blocking.isEmpty()) {
      throw new GateFailure(blocking.size() + " advisory group(s) of high or unknown severity");
    }
    System.out.println("osv-scanner: only advisories below high severity (reported, not failing)");
  }

  /** A minimal JSON reader: objects, arrays, strings, numbers (as Double), booleans and null. */
  static final class Json {
    private final String text;
    private int at;

    private Json(String text) {
      this.text = text;
    }

    static Object parse(String text) {
      Json json = new Json(text);
      Object value = json.value();
      json.skipSpace();
      if (json.at != text.length()) {
        throw new IllegalArgumentException("trailing characters at " + json.at);
      }
      return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
      return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
      return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    static String quote(String s) {
      StringBuilder sb = new StringBuilder("\"");
      for (char c : s.toCharArray()) {
        switch (c) {
          case '"' -> sb.append("\\\"");
          case '\\' -> sb.append("\\\\");
          case '\n' -> sb.append("\\n");
          case '\r' -> sb.append("\\r");
          case '\t' -> sb.append("\\t");
          default -> {
            if (c < 0x20) {
              sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
              sb.append(c);
            }
          }
        }
      }
      return sb.append('"').toString();
    }

    private void skipSpace() {
      while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
        at++;
      }
    }

    private IllegalArgumentException error(String what) {
      return new IllegalArgumentException(what + " at " + at);
    }

    private Object value() {
      skipSpace();
      if (at >= text.length()) {
        throw error("unexpected end");
      }
      char c = text.charAt(at);
      switch (c) {
        case '{':
          return objectValue();
        case '[':
          return arrayValue();
        case '"':
          return string();
        default:
          if (text.startsWith("true", at)) {
            at += 4;
            return Boolean.TRUE;
          }
          if (text.startsWith("false", at)) {
            at += 5;
            return Boolean.FALSE;
          }
          if (text.startsWith("null", at)) {
            at += 4;
            return null;
          }
          return number();
      }
    }

    private Map<String, Object> objectValue() {
      Map<String, Object> out = new LinkedHashMap<>();
      at++;
      skipSpace();
      if (at < text.length() && text.charAt(at) == '}') {
        at++;
        return out;
      }
      while (true) {
        skipSpace();
        if (at >= text.length() || text.charAt(at) != '"') {
          throw error("expected a key");
        }
        String key = string();
        skipSpace();
        if (at >= text.length() || text.charAt(at) != ':') {
          throw error("expected ':'");
        }
        at++;
        out.put(key, value());
        skipSpace();
        if (at < text.length() && text.charAt(at) == ',') {
          at++;
        } else if (at < text.length() && text.charAt(at) == '}') {
          at++;
          return out;
        } else {
          throw error("expected ',' or '}'");
        }
      }
    }

    private List<Object> arrayValue() {
      List<Object> out = new ArrayList<>();
      at++;
      skipSpace();
      if (at < text.length() && text.charAt(at) == ']') {
        at++;
        return out;
      }
      while (true) {
        out.add(value());
        skipSpace();
        if (at < text.length() && text.charAt(at) == ',') {
          at++;
        } else if (at < text.length() && text.charAt(at) == ']') {
          at++;
          return out;
        } else {
          throw error("expected ',' or ']'");
        }
      }
    }

    private String string() {
      StringBuilder sb = new StringBuilder();
      at++;
      while (at < text.length()) {
        char c = text.charAt(at++);
        if (c == '"') {
          return sb.toString();
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (at >= text.length()) {
          break;
        }
        char e = text.charAt(at++);
        switch (e) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'r' -> sb.append('\r');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'u' -> {
            if (at + 4 > text.length()) {
              throw error("bad unicode escape");
            }
            sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
            at += 4;
          }
          default -> sb.append(e);
        }
      }
      throw error("unterminated string");
    }

    private Double number() {
      int start = at;
      while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
        at++;
      }
      if (start == at) {
        throw error("unexpected character");
      }
      try {
        return Double.valueOf(text.substring(start, at));
      } catch (NumberFormatException e) {
        throw error("bad number");
      }
    }
  }
}
