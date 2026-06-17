package io.adaptiq.titan.worker.step.builtin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds a throwaway git repository usable as a Titan shared library, and returns its {@code
 * file://<path>@<sha>} coordinate — an immutable ref, as design/53 D2 requires.
 *
 * <p>Test-only. Shells the {@code git} CLI exactly as {@link
 * io.adaptiq.titan.flow.parser.LibraryFetcher} does, so a passing fixture proves the same toolchain
 * the fetcher needs is present.
 */
final class GitLibraryFixture {

  private GitLibraryFixture() {}

  /**
   * Create a library repo under {@code dir} with the given {@code vars/} files and an optional
   * {@code titan-contract.yml}, commit it, and return its {@code file://…@<sha>} coordinate.
   *
   * @param dir an empty directory to initialise the repo in
   * @param contractYml the {@code titan-contract.yml} content, or {@code null} to omit the file
   * @param varsFiles map of {@code vars/} file name (e.g. {@code "deployToK8s.groovy"}) to source
   */
  static String build(Path dir, String contractYml, Map<String, String> varsFiles)
      throws Exception {
    Files.createDirectories(dir.resolve("vars"));
    for (Map.Entry<String, String> v : varsFiles.entrySet()) {
      Files.writeString(
          dir.resolve("vars").resolve(v.getKey()), v.getValue(), StandardCharsets.UTF_8);
    }
    if (contractYml != null) {
      Files.writeString(dir.resolve("titan-contract.yml"), contractYml, StandardCharsets.UTF_8);
    }
    git(dir, "init", "-q");
    git(dir, "-c", "user.email=t@titan", "-c", "user.name=titan", "add", ".");
    git(dir, "-c", "user.email=t@titan", "-c", "user.name=titan", "commit", "-q", "-m", "lib");
    String sha = capture(dir, "git", "rev-parse", "HEAD").trim();
    return "file://" + dir.toAbsolutePath() + "@" + sha;
  }

  private static void git(Path dir, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    int exit = p.waitFor();
    if (exit != 0) {
      throw new IllegalStateException(
          "git "
              + List.of(args)
              + " failed ("
              + exit
              + "): "
              + new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static String capture(Path dir, String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor();
    return out;
  }
}
