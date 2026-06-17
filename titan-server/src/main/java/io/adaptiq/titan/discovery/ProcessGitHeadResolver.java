package io.adaptiq.titan.discovery;

import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Default {@link GitHeadResolver} — shells out to {@code git ls-remote <url> <branch>} and returns
 * the SHA of the matching {@code refs/heads/<branch>} line.
 *
 * <p>Anonymous-only in v1: any authenticated remote needs git's own credential machinery
 * (env-var-baked token or an ssh agent). Per-job credentials are the follow-up (see #275
 * follow-up); the convention is to resolve a {@code credentialsId} from the new {@code
 * titan.credentials} store (#274) and pre-bake the URL with a token (https) or set {@code
 * GIT_SSH_COMMAND} (ssh).
 */
@ApplicationScoped
public class ProcessGitHeadResolver implements GitHeadResolver {

  private static final Pattern SHA = Pattern.compile("^[0-9a-fA-F]{40,64}$");
  private static final long TIMEOUT_SECONDS = 30L;

  @Override
  @NonNull
  public String resolve(@NonNull String url, @NonNull String branch) throws GitHeadException {
    if (url.isBlank()) {
      throw new GitHeadException("git url is blank");
    }
    if (branch.isBlank()) {
      throw new GitHeadException("branch is blank");
    }

    ProcessBuilder pb =
        new ProcessBuilder("git", "ls-remote", "--heads", "--exit-code", url, branch);
    pb.redirectErrorStream(false);

    Process p;
    try {
      p = pb.start();
    } catch (IOException e) {
      throw new GitHeadException("git ls-remote could not start", e);
    }

    String stdout;
    String stderr;
    try (InputStream out = p.getInputStream();
        InputStream err = p.getErrorStream()) {
      stdout = readAll(out);
      stderr = readAll(err);
      if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new GitHeadException("git ls-remote timed out after " + TIMEOUT_SECONDS + "s");
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new GitHeadException("git ls-remote failed: " + e.getMessage(), e);
    }

    if (p.exitValue() != 0) {
      throw new GitHeadException(
          "git ls-remote exit=" + p.exitValue() + " stderr=" + stderr.trim());
    }

    for (String line : stdout.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.trim().split("\\s+", 2);
      if (parts.length < 1) {
        continue;
      }
      String sha = parts[0];
      if (SHA.matcher(sha).matches()) {
        return sha;
      }
    }
    throw new GitHeadException(
        "git ls-remote returned no matching ref for " + url + " @ " + branch);
  }

  @NonNull
  private static String readAll(@NonNull InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        sb.append(line).append('\n');
      }
    }
    return sb.toString();
  }
}
