package io.adaptiq.titan.db;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Test-only helper: discovers Flyway-style {@code V<N>__*.sql} migration scripts by classpath scan,
 * eliminating the stale-hardcoded-list drift bug (#366, #377, #369).
 *
 * <p>Scans {@link ClassLoader#getResources(String)} for both {@code file:} (exploded test-classpath
 * directories) and {@code jar:} (titan-db-core dependency jar) URLs. Returns basenames sorted by
 * the numeric version embedded in {@code V<N>__}.
 *
 * <p>Scope: this helper does NOT include {@code migration-postgresql/} (PG-only overrides — see PR
 * #362; the H2 test path is incompatible with them). Pass the precise package path you want.
 */
public final class MigrationScripts {

  private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.*\\.sql$");

  private MigrationScripts() {}

  /**
   * List {@code V<N>__*.sql} basenames in version-ascending order from the given resource package
   * path (e.g. {@code "io/adaptiq/titan/db/migration"}). Returns an unmodifiable list. Throws
   * {@link IllegalStateException} if nothing is found, so a missing classpath entry fails loudly
   * (the cure for "silently went stale" is the test refusing to run).
   */
  public static List<String> listInOrder(String packagePath) {
    String normalized = packagePath.replace('.', '/');
    // TreeSet dedupes if the package is split across multiple classpath entries (rare but possible
    // with shadow jars). Comparator sorts by the numeric V<N> prefix, not lexically — V2 sorts
    // before V10, which lexical ordering would invert.
    TreeSet<String> sorted = new TreeSet<>(Comparator.comparingInt(MigrationScripts::version));
    ClassLoader cl = MigrationScripts.class.getClassLoader();
    try {
      Enumeration<URL> urls = cl.getResources(normalized);
      while (urls.hasMoreElements()) {
        URL url = urls.nextElement();
        switch (url.getProtocol()) {
          case "file" -> collectFromDirectory(url, sorted);
          case "jar" -> collectFromJar(url, normalized, sorted);
          default ->
              throw new IllegalStateException(
                  "MigrationScripts: unsupported classpath URL scheme: " + url);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          "MigrationScripts: failed to scan classpath for " + normalized, e);
    }
    if (sorted.isEmpty()) {
      throw new IllegalStateException(
          "MigrationScripts: no V<N>__*.sql resources found under "
              + normalized
              + " — is titan-db-core on the test classpath?");
    }
    List<String> result = new ArrayList<>(sorted);
    // Loud breadcrumb: prints the discovered list so test logs make a future drift obvious.
    System.err.println(
        "[MigrationScripts] " + normalized + " → " + result.size() + " scripts: " + result);
    return Collections.unmodifiableList(result);
  }

  private static void collectFromDirectory(URL url, TreeSet<String> sink) throws IOException {
    Path dir;
    try {
      dir = Paths.get(url.toURI());
    } catch (Exception e) {
      throw new IOException("MigrationScripts: bad file URL: " + url, e);
    }
    if (!Files.isDirectory(dir)) return;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "V*__*.sql")) {
      for (Path p : stream) {
        String name = p.getFileName().toString();
        if (MIGRATION.matcher(name).matches()) {
          sink.add(name);
        }
      }
    }
  }

  private static void collectFromJar(URL url, String packagePath, TreeSet<String> sink)
      throws IOException {
    JarURLConnection conn = (JarURLConnection) url.openConnection();
    String prefix = packagePath.endsWith("/") ? packagePath : packagePath + "/";
    // Use JarFile.entries() rather than holding the connection's stream — we need to walk all
    // entries, not just the one the URL points at.
    try (JarFile jar = conn.getJarFile()) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry e = entries.nextElement();
        if (e.isDirectory()) continue;
        String n = e.getName();
        if (!n.startsWith(prefix)) continue;
        String basename = n.substring(prefix.length());
        // Only the immediate directory — no nested files (V*.sql shouldn't be nested anyway)
        if (basename.indexOf('/') >= 0) continue;
        if (MIGRATION.matcher(basename).matches()) {
          sink.add(basename);
        }
      }
    }
  }

  private static int version(String basename) {
    var m = MIGRATION.matcher(basename);
    if (!m.matches()) {
      throw new IllegalArgumentException("not a migration script name: " + basename);
    }
    return Integer.parseInt(m.group(1));
  }
}
