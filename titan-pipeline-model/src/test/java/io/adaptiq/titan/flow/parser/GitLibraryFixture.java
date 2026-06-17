package io.adaptiq.titan.flow.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A test fixture that builds a real local git repository — a Titan shared library — on disk, so the
 * library-resolution tests (design/38 §4–5, Stage 2b) exercise the genuine {@code git} CLI path
 * that {@link LibraryFetcher} uses on a worker. No mocking: a {@code vars/} file, a real commit, a
 * real tag or branch, a {@code file://} URL.
 */
final class GitLibraryFixture {

  private GitLibraryFixture() {}

  /**
   * Build a git repository under {@code repoDir} containing the supplied files (path relative to
   * the repo root → file content), commit them, and tag the commit {@code tag}.
   *
   * @return a {@code file://} git URL for the repository
   */
  static String buildLibrary(Path repoDir, String tag, Map<String, String> files) throws Exception {
    Files.createDirectories(repoDir);
    writeFiles(repoDir, files);
    git(repoDir, "init", "--quiet");
    configure(repoDir);
    git(repoDir, "add", ".");
    git(repoDir, "commit", "--quiet", "-m", "library fixture");
    git(repoDir, "tag", tag);
    return repoDir.toUri().toString();
  }

  /** Add a second commit and a second tag to an existing fixture repo. */
  static void addTaggedCommit(Path repoDir, String tag, Map<String, String> files)
      throws Exception {
    writeFiles(repoDir, files);
    git(repoDir, "add", ".");
    git(repoDir, "commit", "--quiet", "-m", "library fixture: " + tag);
    git(repoDir, "tag", tag);
  }

  /**
   * Build a git repository under {@code repoDir} on branch {@code branch}, committing the supplied
   * files. Unlike {@link #buildLibrary} this creates <em>no tag</em> — the library is addressable
   * only by its (mutable) branch name, which is what exercises the moving-ref path.
   *
   * @return a {@code file://} git URL for the repository
   */
  static String buildLibraryOnBranch(Path repoDir, String branch, Map<String, String> files)
      throws Exception {
    Files.createDirectories(repoDir);
    writeFiles(repoDir, files);
    // -c init.defaultBranch pins the branch name deterministically across git versions.
    git(repoDir, "-c", "init.defaultBranch=" + branch, "init", "--quiet");
    configure(repoDir);
    git(repoDir, "add", ".");
    git(repoDir, "commit", "--quiet", "-m", "library fixture on " + branch);
    return repoDir.toUri().toString();
  }

  /** Add a further commit to {@code branch} of an existing fixture repo — no tag. */
  static void addCommitOnBranch(Path repoDir, String branch, Map<String, String> files)
      throws Exception {
    git(repoDir, "checkout", "--quiet", branch);
    writeFiles(repoDir, files);
    git(repoDir, "add", ".");
    git(repoDir, "commit", "--quiet", "-m", "library fixture: update " + branch);
  }

  /** The full commit SHA of {@code repoDir}'s current HEAD. */
  static String headSha(Path repoDir) throws Exception {
    return git(repoDir, "rev-parse", "HEAD").strip();
  }

  /** The full commit SHA of a bare repository's {@code HEAD} branch tip. */
  static String bareHeadSha(Path bareRepoDir) throws Exception {
    return git(bareRepoDir, "rev-parse", "HEAD").strip();
  }

  /**
   * Build a <strong>bare</strong> git repository at {@code bareRepoDir}, on branch {@code main},
   * carrying the supplied files — a repo a {@code git http-backend} server can serve directly (the
   * authenticated-fetch tests, design/40). The work is done in a throwaway non-bare clone which is
   * then pushed into the bare repo; {@code git http-backend} smart-HTTP serves a bare repo without
   * further server-info bookkeeping.
   */
  static void buildBareServableLibrary(Path bareRepoDir, Map<String, String> files)
      throws Exception {
    Files.createDirectories(bareRepoDir);
    git(bareRepoDir, "-c", "init.defaultBranch=main", "init", "--bare", "--quiet");

    Path work = bareRepoDir.resolveSibling(bareRepoDir.getFileName() + ".work");
    Files.createDirectories(work);
    writeFiles(work, files);
    git(work, "-c", "init.defaultBranch=main", "init", "--quiet");
    configure(work);
    git(work, "add", ".");
    git(work, "commit", "--quiet", "-m", "private library fixture");
    git(work, "branch", "-M", "main");
    git(work, "remote", "add", "origin", bareRepoDir.toAbsolutePath().toString());
    git(work, "push", "--quiet", "origin", "main");
    // Point the bare repo's HEAD at main so http-backend serves it as the default branch.
    git(bareRepoDir, "symbolic-ref", "HEAD", "refs/heads/main");
  }

  /**
   * Create an <em>annotated</em> tag on the current HEAD. An annotated tag is a tag object distinct
   * from the commit it points at — {@code git ls-remote} reports it as two lines (the tag object
   * and the peeled {@code ^{}} commit), which exercises {@code LibraryFetcher}'s peeled-line
   * selection rule.
   */
  static void annotateTag(Path repoDir, String tag) throws Exception {
    git(repoDir, "tag", "-a", tag, "-m", "annotated " + tag);
  }

  /** Write each {@code relative-path → content} entry into {@code repoDir}. */
  private static void writeFiles(Path repoDir, Map<String, String> files) throws IOException {
    for (Map.Entry<String, String> file : files.entrySet()) {
      Path target = repoDir.resolve(file.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
    }
  }

  /** Apply the identity / signing config every fixture repo needs to commit non-interactively. */
  private static void configure(Path repoDir) throws Exception {
    git(repoDir, "config", "user.email", "titan-test@example.invalid");
    git(repoDir, "config", "user.name", "Titan Test");
    git(repoDir, "config", "commit.gpgsign", "false");
  }

  /**
   * Run one git command in {@code workDir}, returning its stdout; fail loudly on a non-zero exit.
   */
  private static String git(Path workDir, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process p =
        new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
    }
    if (p.exitValue() != 0) {
      throw new IOException(
          "git " + String.join(" ", args) + " failed (" + p.exitValue() + "):\n" + output);
    }
    return output;
  }
}
