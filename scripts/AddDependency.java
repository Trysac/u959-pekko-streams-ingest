// Adds one dependency to build.sbt at an exact version, then rewrites build.sbt.lock (sbt has no
// command for the first step). Factory-owned: agents never run it; they list dependencies and the
// factory adds the allowed ones.
//
// Usage: java scripts/AddDependency.java [--test | --optional] group/artifact [spec]
//        java scripts/AddDependency.java [--test | --optional] --git owner repo tag name
//
// spec: empty (the latest release on Maven Central), an exact version (1.2.3 or ==1.2.3) or a
// lower bound with an optional upper bound (>=1.2 or >=1.2,<2): the latest release must satisfy it
// and is written as the exact version. A Scala library (Maven Central has artifact_3) is written
// with %%, a Java library with %. --test adds a Test dependency; --optional a library one (an
// extra is a library dependency, as in Kotlin). --git adds a factory repository at its tag
// through JitPack (com.github.<owner> %% <repo> % <tag>).

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds a dependency to build.sbt and relocks. */
public final class AddDependency {

  static final String CENTRAL = "https://repo1.maven.org/maven2/";
  static final String JITPACK = "https://jitpack.io";
  // The Scala binary version of the build (Scala 3): Scala libraries publish artifact_3.
  static final String SCALA_SUFFIX = "_3";
  static final String LOCKFILE = "build.sbt.lock";
  // Maven Central lookups: attempts and the first backoff (doubled each retry).
  static final int ATTEMPTS = 4;
  static final long BACKOFF_MS = 2000;
  static final String DEPENDENCIES = "    libraryDependencies ++= Seq(\n";
  static final String DEPENDENCIES_END = "    ),\n";
  static final String RESOLVER = "ThisBuild / resolvers += \"jitpack\" at \"" + JITPACK + "\"\n";
  static final Pattern NAME = Pattern.compile("^([A-Za-z0-9_.-]+)[/:]([A-Za-z0-9_.-]+)$");
  static final Pattern VERSION = Pattern.compile("^[0-9][A-Za-z0-9._-]*$");
  // Milestones, release candidates, previews and snapshots (4.0.0-M1, 2.0-rc1, 1.0-SNAPSHOT).
  static final Pattern PRE_RELEASE =
      Pattern.compile(
          "(?i)[.-](?:alpha|beta|a|b|m|rc|cr|ea|preview|milestone|snapshot)[.-]?\\d*(?:$|[.-])");

  private AddDependency() {}

  /** A refused or failed addition. */
  static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;

    Refused(String message) {
      super(message);
    }
  }

  public static void main(String[] args) {
    Path build = Path.of("build.sbt");
    Path lock = Path.of(LOCKFILE);
    byte[] buildBefore;
    byte[] lockBefore;
    try {
      buildBefore = Files.readAllBytes(build);
      lockBefore = Files.isRegularFile(lock) ? Files.readAllBytes(lock) : null;
    } catch (IOException e) {
      System.err.println("add-dependency: " + e.getMessage());
      System.exit(1);
      return;
    }
    try {
      add(build, List.of(args));
      relock();
    } catch (Refused | IOException | InterruptedException e) {
      // A failed addition leaves the repository exactly as it was: build.sbt and build.sbt.lock.
      restore(build, buildBefore, lock, lockBefore);
      System.err.println("add-dependency: " + e.getMessage());
      System.exit(1);
    }
  }

  /** Puts build.sbt and build.sbt.lock back to their bytes before the addition. */
  static void restore(Path build, byte[] buildBefore, Path lock, byte[] lockBefore) {
    try {
      Files.write(build, buildBefore);
      if (lockBefore == null) {
        Files.deleteIfExists(lock);
      } else {
        Files.write(lock, lockBefore);
      }
    } catch (IOException e) {
      System.err.println("add-dependency: could not restore build.sbt and " + LOCKFILE + ": " + e);
    }
  }

  static void add(Path build, List<String> args) throws Refused, IOException, InterruptedException {
    List<String> rest = new ArrayList<>(args);
    String scope = "";
    if (!rest.isEmpty() && (rest.get(0).equals("--test") || rest.get(0).equals("--optional"))) {
      scope = rest.remove(0);
    }
    String group;
    String artifact;
    String version;
    boolean scala;
    boolean jitpack = false;
    if (!rest.isEmpty() && rest.get(0).equals("--git")) {
      if (rest.size() != 5) {
        throw new Refused("usage: --git owner repo tag name");
      }
      group = "com.github." + rest.get(1);
      artifact = rest.get(2);
      version = rest.get(3);
      scala = true; // a factory Scala repository publishes artifact_3
      jitpack = true;
    } else {
      if (rest.isEmpty() || rest.size() > 2) {
        throw new Refused("usage: [--test | --optional] group/artifact [spec]");
      }
      Matcher m = NAME.matcher(rest.get(0));
      if (!m.matches()) {
        throw new Refused("an sbt dependency is named group/artifact, got " + rest.get(0));
      }
      group = m.group(1);
      artifact = m.group(2);
      String spec = rest.size() == 2 ? rest.get(1).strip() : "";
      String scalaMetadata = metadata(group, artifact + SCALA_SUFFIX);
      scala = scalaMetadata != null;
      String meta = scala ? scalaMetadata : metadata(group, artifact);
      if (meta == null) {
        throw new Refused(group + ":" + artifact + " not found on Maven Central");
      }
      version = resolve(group, artifact, spec, meta);
    }
    String text = Files.readString(build, StandardCharsets.UTF_8);
    text = insert(text, group, artifact, version, scope, scala);
    if (jitpack) {
      text = withJitpack(text);
    }
    Files.writeString(build, text, StandardCharsets.UTF_8);
    System.out.println("added " + group + (scala ? " %% " : " % ") + artifact + " % " + version);
  }

  /**
   * The maven-metadata.xml of an artifact on Maven Central, or null when Central answers 404 (the
   * artifact does not exist). Rate limits (429), server errors (5xx) and network failures are
   * retried with backoff; when they persist the addition fails instead of guessing, so a Scala
   * library is never taken for an absent one (and added as a Java artifact).
   */
  static String metadata(String group, String artifact) throws Refused, InterruptedException {
    String url = CENTRAL + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    String last = "";
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      if (attempt > 0) {
        Thread.sleep(BACKOFF_MS * (1L << (attempt - 1)));
      }
      int status;
      String body;
      try {
        HttpResponse<String> resp =
            client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        status = resp.statusCode();
        body = resp.body();
      } catch (IOException e) {
        last = e.toString();
        continue;
      }
      if (status == 200) {
        return body;
      }
      if (status == 404) {
        return null;
      }
      last = "HTTP " + status;
      if (status != 429 && status < 500) {
        break; // another client error will not change on a retry
      }
    }
    throw new Refused("Maven Central did not answer for " + url + " (" + last + "); try again later");
  }

  /** The exact version a spec selects; only the latest release is ever considered. */
  static String resolve(String group, String artifact, String spec, String metadata)
      throws Refused {
    String exact = spec.startsWith("==") ? spec.substring(2).strip() : spec;
    if (VERSION.matcher(exact).matches()) {
      return exact;
    }
    String lower = null;
    String upper = null;
    if (!spec.isEmpty()) {
      for (String part : spec.split(",")) {
        String p = part.strip();
        if (p.startsWith(">=") && VERSION.matcher(p.substring(2).strip()).matches()) {
          lower = p.substring(2).strip();
        } else if (p.startsWith("<") && VERSION.matcher(p.substring(1).strip()).matches()) {
          upper = p.substring(1).strip();
        } else {
          throw new Refused("unsupported version spec " + spec + " (use 1.2.3, >=1.2 or >=1.2,<2)");
        }
      }
    }
    String latest = latestStable(metadata);
    if (latest == null) {
      throw new Refused("no stable release of " + group + ":" + artifact + " on Maven Central");
    }
    if ((lower != null && compare(latest, lower) < 0)
        || (upper != null && compare(latest, upper) >= 0)) {
      throw new Refused("the latest release " + latest + " does not satisfy " + spec);
    }
    return latest;
  }

  /**
   * The highest stable version listed in maven-metadata.xml. Its {@code <release>} element can name
   * a milestone (4.0.0-M1), so pre-releases are skipped here.
   */
  static String latestStable(String metadata) {
    Matcher m = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>").matcher(metadata);
    String best = null;
    while (m.find()) {
      String v = m.group(1);
      if (VERSION.matcher(v).matches()
          && !PRE_RELEASE.matcher(v).find()
          && (best == null || compare(v, best) > 0)) {
        best = v;
      }
    }
    return best;
  }

  /** Compares dotted versions numerically segment by segment; a qualifier sorts after numbers. */
  static int compare(String a, String b) {
    String[] x = a.split("[.-]");
    String[] y = b.split("[.-]");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      String p = i < x.length ? x[i] : "0";
      String q = i < y.length ? y[i] : "0";
      boolean pn = p.chars().allMatch(Character::isDigit);
      boolean qn = q.chars().allMatch(Character::isDigit);
      int c =
          pn && qn
              ? Long.compare(Long.parseLong(p), Long.parseLong(q))
              : pn ? -1 : qn ? 1 : p.compareTo(q);
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /**
   * build.sbt with one more line in its {@code libraryDependencies ++= Seq(...)} block (every line
   * of the block ends with a comma; .scalafmt.conf keeps trailing commas).
   */
  static String insert(
      String text, String group, String artifact, String version, String scope, boolean scala)
      throws Refused {
    int begin = text.indexOf(DEPENDENCIES);
    int end = begin < 0 ? -1 : text.indexOf(DEPENDENCIES_END, begin);
    if (begin < 0 || end < 0) {
      throw new Refused("build.sbt has no libraryDependencies ++= Seq( ... ) block");
    }
    String block = text.substring(begin, end);
    Matcher declared =
        Pattern.compile(
                "\"" + Pattern.quote(group) + "\"\\s+%%?\\s+\"" + Pattern.quote(artifact) + "\"")
            .matcher(block);
    if (declared.find()) {
      throw new Refused(group + ":" + artifact + " is already declared");
    }
    String line =
        "      \""
            + group
            + "\" "
            + (scala ? "%%" : "%")
            + " \""
            + artifact
            + "\" % \""
            + version
            + "\""
            + (scope.equals("--test") ? " % Test" : "")
            + ",\n";
    return text.substring(0, end) + line + text.substring(end);
  }

  static String withJitpack(String text) {
    if (text.contains(JITPACK)) {
      return text;
    }
    int at = text.indexOf("\nlazy val root");
    return at < 0
        ? text + "\n" + RESOLVER
        : text.substring(0, at) + "\n" + RESOLVER + text.substring(at);
  }

  /** The sbt launcher on PATH (sbt, or sbt.bat on Windows). */
  static String sbtExecutable() throws Refused {
    String path = System.getenv("PATH");
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    List<String> suffixes = windows ? List.of(".bat", ".cmd", ".exe", "") : List.of("");
    for (String dir : path == null ? new String[0] : path.split(java.io.File.pathSeparator)) {
      for (String suffix : suffixes) {
        Path candidate = Path.of(dir.isEmpty() ? "." : dir, "sbt" + suffix);
        if (Files.isRegularFile(candidate)) {
          return candidate.toString();
        }
      }
    }
    throw new Refused("sbt is not installed (https://www.scala-sbt.org/download/)");
  }

  /** Rewrites build.sbt.lock from sbt's own resolution of every configuration. */
  static void relock() throws Refused, IOException, InterruptedException {
    List<String> argv = List.of(sbtExecutable(), "--batch", "--no-colors", "dependencyLockWrite");
    // sbt reports resolution errors on its standard output: both streams reach the caller.
    int code = new ProcessBuilder(argv).inheritIO().start().waitFor();
    if (code != 0) {
      throw new Refused("`sbt dependencyLockWrite` failed (exit " + code + ")");
    }
  }
}
