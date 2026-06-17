package io.adaptiq.titan.scm;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * SCM-agnostic structured finding emitted by an analysis step (sast, lint, coverage, …) and posted
 * back to the originating pull request as an inline review comment. This is the single shared DTO
 * across every SCM adapter (GitHub, GitLab, Bitbucket) — no provider-specific finding type is
 * allowed to leak past an adapter boundary (issue #1117).
 *
 * <p>The {@link Severity} discriminator is a shared enum, never a stringly-typed token, so producer
 * and consumer cannot drift (Titan manifesto: "no stringly-typed cross-module discriminators").
 *
 * @param path repository-relative path of the file the finding is on (e.g. {@code src/foo.py}).
 *     This is the NEW-side path as it appears in the PR diff; a finding on a file not present in
 *     the diff falls back to a summary comment at the adapter boundary.
 * @param line 1-based line number in the NEW version of the file (Bitbucket {@code inline.to},
 *     GitHub review {@code line}). Values &lt;= 0 are treated as file-level (no anchor line).
 * @param severity severity bucket — drives the emoji / label the adapter renders.
 * @param message human-readable finding text (already rendered; markdown allowed).
 */
public record Finding(
    @NonNull String path, int line, @NonNull Severity severity, @NonNull String message) {

  /** Severity bucket. Ordered least → most severe; shared across all SCM adapters. */
  public enum Severity {
    INFO,
    MINOR,
    MAJOR,
    CRITICAL
  }

  public Finding {
    if (path.isBlank()) {
      throw new IllegalArgumentException("Finding.path must not be blank");
    }
    if (message.isBlank()) {
      throw new IllegalArgumentException("Finding.message must not be blank");
    }
  }

  /** {@code true} when this finding can be anchored to a concrete line in the diff. */
  public boolean hasLineAnchor() {
    return line > 0;
  }
}
