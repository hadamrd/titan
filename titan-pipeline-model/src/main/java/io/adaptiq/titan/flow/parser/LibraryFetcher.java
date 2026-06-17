package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.crypto.SecretProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a Titan shared library — a real git repository — at synthesis time (design/38 §4–5,
 * Stage&nbsp;2b).
 *
 * <p>design/38 §4: "a Titan shared library is a real code repository — {@code src/}, helpers,
 * classes, tests — versioned by git ref like any repo." A library function is a pure function
 * {@code params → DAG fragment}. The synthesis builder method {@code library('<git-url>@<ref>')}
 * fetches that repo so its {@code vars/*.groovy} functions become callable from the synthesis
 * program — an SCM checkout at a ref, cached on disk.
 *
 * <h2>Where this runs — and where it must not</h2>
 *
 * <p>A {@code library(...)} call shells out to the {@code git} CLI; that is real I/O against a
 * remote repo. It is invoked at step execution time by the {@code libraryCall} handler (design/53)
 * — never on the controller.
 *
 * <h2>Repository trust</h2>
 *
 * <p>This is a plain {@code git} client — it does not carry git-ownership workarounds. A worker
 * that fetches a {@code file://} library from a multi-user filesystem (a shared volume written by
 * another uid) must have its git configured to trust those repositories — git's dubious-ownership
 * guard otherwise refuses the fetch. That is a CI-worker environment concern, declared once at the
 * image level ({@code git config --system safe.directory}), exactly as {@code actions/checkout} and
 * the standard CI base images do — not something a library fetcher patches per call. A library
 * fetched from an ordinary {@code https://} / {@code ssh://} remote never raises the question.
 *
 * <h2>The cache</h2>
 *
 * <p>The cache is <strong>content-addressed by the resolved commit SHA</strong>, not by the literal
 * ref string. {@link #resolve} first resolves the coordinate's ref to a commit: a full commit SHA
 * is immutable and used as-is; any other ref (a tag or a branch) is re-resolved against the remote
 * with {@code git ls-remote} on <em>every</em> call. The cache directory is then keyed by a SHA-256
 * of {@code (url, resolvedSha)}. A {@code .titan-fetched} marker proves a prior fetch completed.
 *
 * <p>This is what makes a <em>mutable</em> ref correct: an unchanged branch resolves to the same
 * SHA and is a cache hit (only the sub-second {@code ls-remote} runs — no clone); a branch that has
 * moved resolves to a new SHA, misses the cache, and is re-fetched. Keying by the literal ref
 * string instead would freeze a branch at its first-fetched commit forever. A tag and a branch that
 * point at the same commit share one cache entry. Two builds at the same resolved SHA share one
 * checkout; a re-delivered {@code SYNTHESIZE} task re-uses it too.
 *
 * <h2>Coordinate form</h2>
 *
 * <p>Stage&nbsp;2b accepts only the explicit form {@code <git-url>@<ref>} — {@code ref} is a tag,
 * branch or commit SHA. A bare-name coordinate ({@code library('ci-shared')}) resolved through a
 * name→URL registry is a documented Stage&nbsp;2b non-goal — a follow-up.
 *
 * <h2>Authenticated fetch — a private library (design/40)</h2>
 *
 * <p>A team's real shared library is usually a <strong>private</strong> repo. The synthesis form
 * {@code library('<git-url>@<ref>', credential: '<secret-name>')} names a secret; {@link
 * #resolve(String, String)} resolves it via {@link SecretProvider#active()} — the worker-side SPI
 * that reads an external secret manager (design/40 §3) — and authenticates the git fetch with it.
 *
 * <p>The token is handled so it cannot leak (design/40 §5):
 *
 * <ul>
 *   <li><strong>Never in argv</strong> — not a URL-embedded {@code https://oauth2:TOKEN@host/…}
 *       (visible in {@code ps}), not {@code git -c http.extraHeader=…}. The clone URL carries only
 *       a fixed username ({@code https://x-access-token@…} — not a secret).
 *   <li><strong>Via {@code GIT_ASKPASS}</strong> — a tiny throwaway askpass script git invokes for
 *       the password; the token reaches it through an environment variable on the git process
 *       (inherited by the askpass child), never argv, never disk-as-a-secret. The askpass script
 *       itself contains no token — it just echoes its {@code $TITAN_GIT_TOKEN} env var.
 *   <li><strong>{@code GIT_TERMINAL_PROMPT=0}</strong> — a missing or invalid token fails fast
 *       instead of hanging on an interactive prompt.
 *   <li>The askpass script is <strong>deleted</strong> after the fetch; the cached checkout's
 *       {@code .git/config} holds no credential, so a cache hit (which performs no fetch) needs
 *       none. A {@code null}/blank secret <strong>fails synthesis closed</strong> — no fall-through
 *       to an unauthenticated fetch.
 * </ul>
 */
public final class LibraryFetcher {

  private static final Logger LOGGER = Logger.getLogger(LibraryFetcher.class.getName());

  /**
   * Wall-clock budget for one {@code git} invocation. A fetch should be quick or fail loud with a
   * precise "fetch timed out" message rather than getting force-killed somewhere upstream.
   */
  private static final long GIT_TIMEOUT_SECONDS = 45;

  /** Marker file written into a cache dir once a fetch has fully completed. */
  private static final String FETCH_MARKER = ".titan-fetched";

  /**
   * The fixed username placed in the clone URL for an authenticated HTTPS fetch (design/40 §5). It
   * is <em>not</em> a secret — GitHub/GitLab token auth accepts any non-empty username and takes
   * the token as the password (which {@code GIT_ASKPASS} supplies). {@code x-access-token} is the
   * convention GitHub documents for a token-as-password fetch.
   */
  private static final String AUTH_USERNAME = "x-access-token";

  /**
   * The environment variable through which the token reaches the throwaway askpass script — set on
   * the git process, inherited by the askpass child, never written to argv or to disk as a secret
   * (design/40 §5).
   */
  private static final String TOKEN_ENV = "TITAN_GIT_TOKEN";

  /** Root under which all fetched libraries are cached. */
  @NonNull private final Path cacheRoot;

  /**
   * The secret provider used to resolve a {@code credential:}-named synthesis secret, or {@code
   * null} to use {@link SecretProvider#active()} (the {@link ServiceLoader}-discovered provider —
   * the production path). A non-{@code null} value is the test/embedding seam: it makes an
   * authenticated fetch exercisable without installing a {@code META-INF/services} entry that would
   * change {@code SecretProvider.active()} globally.
   */
  @Nullable private final SecretProvider secretProvider;

  /**
   * @param cacheRoot the directory under which fetched library checkouts are cached; created on
   *     demand. On a worker this is typically {@code <TITAN_LIBRARIES_ROOT>/synthesis-cache}.
   */
  public LibraryFetcher(@NonNull Path cacheRoot) {
    this(cacheRoot, null);
  }

  /**
   * Test/embedding constructor — an explicit {@link SecretProvider} for {@code credential:}
   * resolution instead of the {@link ServiceLoader}-discovered {@link SecretProvider#active()}.
   *
   * @param cacheRoot the library cache root
   * @param secretProvider the provider for {@code credential:} secrets, or {@code null} to use
   *     {@link SecretProvider#active()}
   */
  LibraryFetcher(@NonNull Path cacheRoot, @Nullable SecretProvider secretProvider) {
    this.cacheRoot = cacheRoot;
    this.secretProvider = secretProvider;
  }

  /**
   * Resolve a library coordinate to a local checkout directory, fetching it if it is not already
   * cached.
   *
   * @param coordinate {@code <git-url>@<ref>} — e.g. {@code file:///srv/libs/ci-shared.git@v1.0}
   * @return the local directory holding the checked-out library repo (with {@code vars/} and {@code
   *     src/} subdirectories, if the library provides them)
   * @throws PipelineParseException if the coordinate is malformed, or the git fetch fails
   */
  @NonNull
  public Path resolve(@NonNull String coordinate) {
    return resolve(coordinate, null);
  }

  /**
   * Resolve a library coordinate to a local checkout, authenticating the git fetch with a secret
   * named by {@code credentialName} when one is supplied (design/40 §4–5).
   *
   * <p>When {@code credentialName} is {@code null} or blank this is an unauthenticated fetch,
   * identical to {@link #resolve(String)} — a public library. Otherwise the named secret is
   * resolved via {@link SecretProvider#active()}; a {@code null} or blank value from the provider
   * <strong>fails synthesis closed</strong> (design/40 §4) — there is no fall-through to an
   * unauthenticated fetch. The token authenticates the fetch through a {@code GIT_ASKPASS} helper
   * and never appears in argv, a log, or the cached {@code .git/config} (design/40 §5).
   *
   * @param coordinate {@code <git-url>@<ref>}
   * @param credentialName the name of a secret in the external secret manager, or {@code null} for
   *     an unauthenticated (public-repo) fetch
   * @return the local directory holding the checked-out library repo
   * @throws PipelineParseException if the coordinate is malformed, the named secret cannot be
   *     resolved, or the git fetch fails
   */
  @NonNull
  public Path resolve(@NonNull String coordinate, @Nullable String credentialName) {
    Coordinate c = Coordinate.parse(coordinate);
    // Resolve the ref to a commit SHA *before* keying the cache, so a mutable ref (a branch) is
    // re-checked on every call: an unchanged branch resolves to the same SHA (cache hit), a
    // moved branch resolves to a new SHA (re-fetch). A tag or full SHA is already immutable.
    // ls-remote also needs authentication for a private remote — resolveRef carries the auth.
    GitAuth auth = resolveAuth(coordinate, credentialName);
    try {
      String resolvedRef = resolveRef(c, auth);
      Path dest = cacheRoot.resolve(cacheKey(c.url, resolvedRef));
      Path marker = dest.resolve(FETCH_MARKER);
      if (Files.isRegularFile(marker)) {
        LOGGER.log(
            Level.FINE,
            "[titan-synthesis] library cache hit: {0} -> {1}",
            new Object[] {coordinate, resolvedRef});
        return dest;
      }
      fetchAtomically(c, resolvedRef, dest, marker, auth);
      return dest;
    } finally {
      auth.cleanup();
    }
  }

  /**
   * Fetch the library into a private staging directory, write the completion marker into it, and
   * publish it to {@code dest} with a single atomic directory move.
   *
   * <p>Two synthesis runs can resolve the same coordinate at the same time — concurrent executor
   * slots within one worker, or two workers on one host sharing {@link #defaultCacheRoot()}. The
   * old fetch-into-{@code dest} path was a check-then-act race: both callers saw the marker absent
   * and both {@code git}-cloned into the <em>same</em> directory, interleaving into a corrupt
   * checkout. Staging + atomic move removes the race for both cases: each caller fetches into its
   * own staging dir, and {@code dest} only ever appears — atomically — as a complete,
   * marker-bearing checkout. The loser of the race discards its staging copy and uses the winner's
   * identical checkout (same resolved SHA, same content).
   */
  private void fetchAtomically(
      @NonNull Coordinate c,
      @NonNull String resolvedRef,
      @NonNull Path dest,
      @NonNull Path marker,
      @NonNull GitAuth auth) {
    try {
      Files.createDirectories(cacheRoot);
    } catch (IOException e) {
      throw new PipelineParseException(
          "synthesis: could not create library cache dir " + cacheRoot, e);
    }
    Path staging;
    try {
      staging = Files.createTempDirectory(cacheRoot, ".staging-");
    } catch (IOException e) {
      throw new PipelineParseException(
          "synthesis: could not create a library staging dir under " + cacheRoot, e);
    }
    try {
      fetch(c, resolvedRef, staging, auth);
      Files.writeString(
          staging.resolve(FETCH_MARKER),
          c.url + "@" + c.ref + " -> " + resolvedRef + "\n",
          StandardCharsets.UTF_8);
      try {
        Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
      } catch (FileSystemException raceLost) {
        // dest appeared while we were fetching — another concurrent resolve of the same
        // coordinate published it first. If it is a complete checkout (marker present),
        // that is the expected race outcome: drop our copy and use the winner's.
        if (!Files.isRegularFile(marker)) {
          throw raceLost;
        }
        LOGGER.log(
            Level.FINE,
            "[titan-synthesis] library cache race lost — using already-published {0}",
            dest);
        deleteRecursively(staging);
      }
    } catch (IOException e) {
      deleteRecursively(staging);
      throw new PipelineParseException(
          "synthesis: could not finalise library cache for '" + c.url + "@" + c.ref + "'", e);
    } catch (RuntimeException e) {
      deleteRecursively(staging);
      throw e;
    }
  }

  /**
   * Resolve the git authentication for a fetch. With no {@code credentialName} this is {@link
   * GitAuth#NONE} — an unauthenticated fetch. Otherwise the named secret is fetched from {@link
   * SecretProvider#active()} and an askpass helper is materialised; a {@code null}/blank secret
   * fails synthesis closed (design/40 §4).
   */
  @NonNull
  private GitAuth resolveAuth(@NonNull String coordinate, @Nullable String credentialName) {
    if (credentialName == null || credentialName.isBlank()) {
      return GitAuth.NONE;
    }
    SecretProvider provider = (secretProvider != null) ? secretProvider : SecretProvider.active();
    String token = provider.secret(credentialName);
    if (token == null || token.isBlank()) {
      // Fail closed — never silently fall through to an unauthenticated fetch (design/40 §4).
      throw new PipelineParseException(
          "synthesis: library '"
              + coordinate
              + "' needs credential '"
              + credentialName
              + "', but the secret provider ("
              + provider.describe()
              + ") returned no value for it — synthesis fails closed."
              + " Configure the worker's secret manager with a secret named '"
              + credentialName
              + "'.");
    }
    LOGGER.log(
        Level.FINE,
        "[titan-synthesis] library credential ''{0}'' resolved via {1}",
        new Object[] {credentialName, provider.describe()});
    return GitAuth.withToken(token);
  }

  /**
   * Resolve a coordinate's ref to a commit SHA for cache keying. A full commit SHA is immutable and
   * used as-is — no network call; any other ref (a tag or branch) is re-resolved against the remote
   * with {@code git ls-remote} on every call, so a moved branch is always detected.
   */
  @NonNull
  private String resolveRef(@NonNull Coordinate c, @NonNull GitAuth auth) {
    if (isFullCommitSha(c.ref)) {
      return c.ref;
    }
    String sha = lsRemote(c, auth);
    LOGGER.log(
        Level.FINE, "[titan-synthesis] resolved {0}@{1} -> {2}", new Object[] {c.url, c.ref, sha});
    return sha;
  }

  /** True if {@code ref} is a full git commit hash — 40 hex (SHA-1) or 64 hex (SHA-256). */
  private static boolean isFullCommitSha(@NonNull String ref) {
    return ref.matches("[0-9a-f]{40}") || ref.matches("[0-9a-f]{64}");
  }

  /**
   * Resolve a mutable ref to its current commit SHA via {@code git ls-remote <url> <ref>}.
   *
   * <p>{@code ls-remote} prints one {@code <sha>\t<refname>} line per matching ref. When more than
   * one line matches, the selection rule is total and deterministic:
   *
   * <ol>
   *   <li>keep only lines whose SHA is 40- or 64-hex;
   *   <li>prefer a peeled line ({@code refname} ending {@code ^{}} — the commit an annotated tag
   *       points at), then {@code refs/heads/*}, then {@code refs/tags/*}, then any remaining;
   *   <li>break ties by lexicographic {@code refname}.
   * </ol>
   *
   * Any stable SHA is a valid cache key; the preference order merely collapses a tag and a branch
   * at the same commit into one cache entry.
   *
   * @throws PipelineParseException if the ref matches nothing on the remote
   */
  @NonNull
  private String lsRemote(@NonNull Coordinate c, @NonNull GitAuth auth) {
    // ls-remote needs no local repo — run it in the cache root (created on demand).
    try {
      Files.createDirectories(cacheRoot);
    } catch (IOException e) {
      throw new PipelineParseException(
          "synthesis: could not create library cache dir " + cacheRoot, e);
    }
    String output = runCapturing(cacheRoot, auth, "git", "ls-remote", auth.fetchUrl(c.url), c.ref);
    String best = null;
    int bestRank = Integer.MAX_VALUE;
    String bestRefName = null;
    for (String line : output.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.trim().split("\\s+", 2);
      String sha = parts[0];
      if (!isFullCommitSha(sha)) {
        continue;
      }
      String refName = parts.length > 1 ? parts[1] : "";
      int rank =
          refName.endsWith("^{}")
              ? 0
              : refName.startsWith("refs/heads/") ? 1 : refName.startsWith("refs/tags/") ? 2 : 3;
      if (rank < bestRank
          || (rank == bestRank && bestRefName != null && refName.compareTo(bestRefName) < 0)) {
        best = sha;
        bestRank = rank;
        bestRefName = refName;
      }
    }
    if (best == null) {
      throw new PipelineParseException(
          "synthesis: shared-library ref '" + c.ref + "' not found in " + c.url);
    }
    return best;
  }

  /** Shallow-clone {@code c} into {@code dest} at the requested ref. */
  private void fetch(
      @NonNull Coordinate c,
      @NonNull String resolvedRef,
      @NonNull Path dest,
      @NonNull GitAuth auth) {
    // A partial dir from a prior failed fetch must not be mistaken for a good checkout.
    deleteRecursively(dest);
    try {
      Files.createDirectories(dest);
    } catch (IOException e) {
      throw new PipelineParseException("synthesis: could not create library cache dir " + dest, e);
    }
    // git init + fetch <ref> + checkout — works for a tag, a branch, or a full commit SHA, and
    // stays shallow (depth 1). The fetch uses the ref *name* (not the SHA resolveRef produced):
    // fetching by raw SHA needs the remote's uploadpack.allowAnySHA1InWant, which is not
    // universal. Any drift between the ls-remote resolution and this fetch (a branch that moved
    // in between) is self-healing — the next resolve re-resolves and re-fetches.
    //
    // The remote URL stored in .git/config carries at most a *username* (auth.fetchUrl) —
    // never the token (design/40 §5): the token is supplied transiently via GIT_ASKPASS only
    // for the duration of the fetch process, so the cached checkout holds no credential.
    run(dest, auth, "git", "init", "--quiet");
    run(dest, auth, "git", "remote", "add", "origin", auth.fetchUrl(c.url));
    run(dest, auth, "git", "fetch", "--depth", "1", "--quiet", "origin", c.ref);
    run(dest, auth, "git", "checkout", "--quiet", "FETCH_HEAD");
    LOGGER.log(
        Level.INFO,
        "[titan-synthesis] fetched library {0}@{1} ({2}){3}",
        new Object[] {c.url, c.ref, resolvedRef, auth.isAuthenticated() ? " [authenticated]" : ""});
  }

  /** An empty git config used as {@code GIT_CONFIG_GLOBAL} so a fetch ignores the host's. */
  private static volatile Path hermeticGlobalConfig;

  /**
   * Lazily create (once per JVM) an empty file used as {@code GIT_CONFIG_GLOBAL} — pointing git at
   * it makes every fetch hermetic against the host user's {@code ~/.gitconfig}.
   */
  @NonNull
  private static Path hermeticGlobalConfig() {
    Path cfg = hermeticGlobalConfig;
    if (cfg != null) {
      return cfg;
    }
    synchronized (LibraryFetcher.class) {
      if (hermeticGlobalConfig == null) {
        try {
          Path empty = Files.createTempFile("titan-git-hermetic", ".config");
          empty.toFile().deleteOnExit();
          hermeticGlobalConfig = empty;
        } catch (IOException e) {
          throw new PipelineParseException("synthesis: could not create a hermetic git config", e);
        }
      }
      return hermeticGlobalConfig;
    }
  }

  /**
   * Run one git command in {@code workDir}, discarding its output; fail with
   * PipelineParseException.
   */
  private static void run(
      @NonNull Path workDir, @NonNull GitAuth auth, @NonNull String... command) {
    runCapturing(workDir, auth, command);
  }

  /**
   * Run one git command in {@code workDir} and return its captured stdout (stderr merged in). When
   * {@code auth} is authenticated, {@code GIT_ASKPASS} / {@code GIT_TERMINAL_PROMPT} and the token
   * environment variable are applied to the process (design/40 §5).
   *
   * @throws PipelineParseException on a launch failure, a timeout, or a non-zero exit
   */
  @NonNull
  private static String runCapturing(
      @NonNull Path workDir, @NonNull GitAuth auth, @NonNull String... command) {
    ProcessBuilder pb =
        new ProcessBuilder(auth.gitCommand(command))
            .directory(workDir.toFile())
            .redirectErrorStream(true);
    auth.applyTo(pb);
    // Hermetic fetch — git must NOT read the host user's ~/.gitconfig. That file can carry an
    // inherited credential helper (Git Credential Manager, an osxkeychain helper) and `insteadOf`
    // URL rewrites; either makes a library fetch non-deterministic and host-dependent, and a
    // helper/rewrite can even pop an interactive credential or ssh prompt. GIT_CONFIG_GLOBAL
    // points git at an empty config instead — the fetch uses only the credential Titan
    // explicitly supplies and only the URL as given. System config is left untouched (the
    // worker image's `safe.directory` lives there).
    pb.environment().put("GIT_CONFIG_GLOBAL", hermeticGlobalConfig().toString());
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      throw new PipelineParseException(
          "synthesis: could not run '" + command[0] + "' — is the git CLI installed on the worker?",
          e);
    }
    String output;
    try {
      output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new PipelineParseException(
            "synthesis: '"
                + String.join(" ", command)
                + "' timed out after "
                + GIT_TIMEOUT_SECONDS
                + "s");
      }
    } catch (IOException e) {
      throw new PipelineParseException(
          "synthesis: failed reading output of '" + String.join(" ", command) + "'", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new PipelineParseException(
          "synthesis: interrupted running '" + String.join(" ", command) + "'", e);
    }
    int exit = process.exitValue();
    if (exit != 0) {
      // The token is never in argv (it travels via GIT_ASKPASS) and the URL carries only a
      // username — but git can echo a remote URL into stderr, so redact defensively before
      // the captured output reaches an exception message or a log (design/40 §5).
      throw new PipelineParseException(
          "synthesis: shared-library git command failed — '"
              + String.join(" ", command)
              + "' exited "
              + exit
              + ":\n"
              + auth.redact(output.strip()));
    }
    return output;
  }

  /** A cache directory name from a SHA-256 of {@code url@resolvedRef} — filesystem-safe. */
  @NonNull
  private static String cacheKey(@NonNull String url, @NonNull String resolvedRef) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest((url + "@" + resolvedRef).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // every JVM ships it
    }
  }

  /** Best-effort recursive delete — a stale or partial cache dir before a fresh fetch. */
  private static void deleteRecursively(@NonNull Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      List<Path> ordered = new ArrayList<>(paths.toList());
      ordered.sort((a, b) -> b.getNameCount() - a.getNameCount()); // deepest first
      for (Path p : ordered) {
        Files.deleteIfExists(p);
      }
    } catch (IOException e) {
      throw new PipelineParseException(
          "synthesis: could not clear stale library cache dir " + dir, e);
    }
  }

  /**
   * Git authentication for one {@link #resolve(String, String)} call (design/40 §5).
   *
   * <p>{@link #NONE} is an unauthenticated fetch — a public library. An authenticated instance
   * holds the token only in worker memory and materialises a throwaway {@code GIT_ASKPASS} script
   * on construction; {@link #applyTo} wires the askpass and the token environment variable onto a
   * git {@link ProcessBuilder}, and {@link #cleanup} deletes the askpass script once the resolve
   * finishes. The token is never written to argv, never embedded in the clone URL, never persisted
   * in the cached checkout's {@code .git/config}.
   */
  private static final class GitAuth {

    /** The unauthenticated fetch — a public library. */
    static final GitAuth NONE = new GitAuth(null);

    /** The token — held only here, in worker memory. {@code null} for {@link #NONE}. */
    @Nullable private final String token;

    /** The throwaway askpass script, or {@code null} when unauthenticated. */
    @Nullable private final Path askpass;

    private GitAuth(@Nullable String token) {
      this.token = token;
      this.askpass = (token == null) ? null : writeAskpassScript();
    }

    @NonNull
    static GitAuth withToken(@NonNull String token) {
      return new GitAuth(token);
    }

    boolean isAuthenticated() {
      return token != null;
    }

    /**
     * The URL git is given. Unauthenticated: the URL verbatim. Authenticated over HTTP(S): the
     * fixed {@link #AUTH_USERNAME} is spliced in as the userinfo — a username is <em>not</em> a
     * secret; the token is supplied separately by the askpass helper. A non-HTTP(S) URL (an {@code
     * ssh://} or {@code file://} remote) is returned unchanged — userinfo splicing only applies to
     * the HTTP(S) password flow {@code GIT_ASKPASS} drives.
     */
    @NonNull
    String fetchUrl(@NonNull String url) {
      if (token == null) {
        return url;
      }
      int scheme = url.indexOf("://");
      if (scheme < 0) {
        return url;
      }
      String protocol = url.substring(0, scheme);
      if (!protocol.equalsIgnoreCase("http") && !protocol.equalsIgnoreCase("https")) {
        return url;
      }
      String rest = url.substring(scheme + 3);
      // If the URL already carries userinfo, leave it — do not double-splice.
      int slash = rest.indexOf('/');
      String authority = (slash < 0) ? rest : rest.substring(0, slash);
      if (authority.indexOf('@') >= 0) {
        return url;
      }
      return protocol + "://" + AUTH_USERNAME + "@" + rest;
    }

    /**
     * The full git argv for {@code command} — <em>always</em> splices {@code -c credential.helper=}
     * (an empty value, which resets the helper list) right after {@code git}. The fetch is thereby
     * <strong>hermetic</strong>: the only credential git can use is the one Titan supplies (a token
     * via {@code GIT_ASKPASS}), or none at all. A credential cached by a host-level helper (Windows
     * Credential Manager, an osxkeychain helper) is never consulted — so an authenticated fetch
     * cannot silently ride a stale cached token, and an <em>un</em>authenticated fetch of a private
     * repo genuinely fails instead of being served by whatever the worker host happened to have
     * cached.
     */
    @NonNull
    String[] gitCommand(@NonNull String[] command) {
      if (command.length == 0) {
        return command;
      }
      String[] out = new String[command.length + 2];
      out[0] = command[0]; // "git"
      out[1] = "-c";
      out[2] = "credential.helper="; // empty — reset/disable any inherited helper
      System.arraycopy(command, 1, out, 3, command.length - 1);
      return out;
    }

    /**
     * Apply the auth to a git {@link ProcessBuilder}. {@code GIT_ASKPASS} is set <em>always</em>: a
     * 401 must never reach an interactive prompt. {@code GIT_TERMINAL_PROMPT=0} gates only the
     * <em>terminal</em> prompt — with no askpass set, git-for-Windows still pops a <em>GUI</em>
     * credential dialog. So an unauthenticated fetch gets a <strong>deny</strong> askpass (supplies
     * nothing, exits non-zero → git fails the auth at once); an authenticated fetch gets the
     * throwaway token askpass and the token environment variable it echoes — the only place the
     * token leaves memory, an env var inherited by git and its askpass child, never argv.
     */
    void applyTo(@NonNull ProcessBuilder pb) {
      Map<String, String> env = pb.environment();
      env.put("GIT_TERMINAL_PROMPT", "0");
      if (token == null) {
        env.put("GIT_ASKPASS", denyAskpass().toAbsolutePath().toString());
        return;
      }
      env.put("GIT_ASKPASS", askpass.toAbsolutePath().toString());
      env.put(TOKEN_ENV, token);
    }

    /**
     * Redact the token from text bound for an exception message or a log. The token never
     * <em>should</em> appear there — it is never in argv — but git can echo a remote into stderr,
     * so this is defence in depth (design/40 §5).
     */
    @NonNull
    String redact(@NonNull String text) {
      return (token == null || token.isEmpty()) ? text : text.replace(token, "***");
    }

    /** Delete the throwaway askpass script once the resolve finishes. Best-effort. */
    void cleanup() {
      if (askpass == null) {
        return;
      }
      try {
        Files.deleteIfExists(askpass);
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "[titan-synthesis] could not delete askpass script {0}", askpass);
      }
    }

    /**
     * Write the throwaway {@code GIT_ASKPASS} script. It contains <strong>no token</strong> — git
     * invokes it with the credential prompt as {@code $1} and it echoes the token from the {@link
     * #TOKEN_ENV} environment variable git passed it. A {@code .sh} POSIX script on a POSIX
     * filesystem (made executable); a {@code .bat} on Windows. The token only ever lives in the
     * environment, never on disk.
     */
    @NonNull
    private static Path writeAskpassScript() {
      boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
      try {
        Path dir = Files.createTempDirectory("titan-askpass");
        if (windows) {
          Path script = dir.resolve("askpass.bat");
          Files.writeString(
              script, "@echo off\r\necho %" + TOKEN_ENV + "%\r\n", StandardCharsets.UTF_8);
          return script;
        }
        Path script = dir.resolve("askpass.sh");
        Files.writeString(
            script, "#!/bin/sh\nprintf '%s\\n' \"${" + TOKEN_ENV + "}\"\n", StandardCharsets.UTF_8);
        try {
          Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
          script.toFile().setExecutable(true, true);
        }
        return script;
      } catch (IOException e) {
        throw new PipelineParseException(
            "synthesis: could not prepare the git askpass helper for an"
                + " authenticated library fetch",
            e);
      }
    }

    /** The deny askpass — exits non-zero, supplies nothing. Created once per JVM. */
    private static volatile Path denyAskpass;

    /**
     * The deny {@code GIT_ASKPASS} helper: a script that exits non-zero so git fails an
     * unauthenticated 401 immediately, rather than falling back to git-for-Windows' GUI credential
     * dialog. It carries no secret and is identical for every fetch, so a single JVM-lifetime copy
     * is reused.
     */
    @NonNull
    private static Path denyAskpass() {
      Path script = denyAskpass;
      if (script != null) {
        return script;
      }
      synchronized (GitAuth.class) {
        if (denyAskpass == null) {
          boolean windows =
              System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
          try {
            Path dir = Files.createTempDirectory("titan-askpass-deny");
            Path s;
            if (windows) {
              s = dir.resolve("askpass-deny.bat");
              Files.writeString(s, "@echo off\r\nexit /b 1\r\n", StandardCharsets.UTF_8);
            } else {
              s = dir.resolve("askpass-deny.sh");
              Files.writeString(s, "#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8);
              try {
                Files.setPosixFilePermissions(s, PosixFilePermissions.fromString("rwx------"));
              } catch (UnsupportedOperationException ignored) {
                s.toFile().setExecutable(true, true);
              }
            }
            s.toFile().deleteOnExit();
            denyAskpass = s;
          } catch (IOException e) {
            throw new PipelineParseException(
                "synthesis: could not prepare the git deny-askpass helper", e);
          }
        }
        return denyAskpass;
      }
    }
  }

  /** A parsed {@code <git-url>@<ref>} coordinate. */
  private static final class Coordinate {
    @NonNull final String url;

    @NonNull final String ref;

    private Coordinate(@NonNull String url, @NonNull String ref) {
      this.url = url;
      this.ref = ref;
    }

    /**
     * Split a coordinate at its <em>last</em> {@code @}. A git URL itself can contain {@code @} (an
     * {@code scp}-style {@code user@host:path} or an embedded credential), so the ref — the
     * trailing tag/branch/SHA, which never contains {@code @} — is taken after the final one.
     */
    @NonNull
    static Coordinate parse(@NonNull String coordinate) {
      String trimmed = coordinate.strip();
      int at = trimmed.lastIndexOf('@');
      if (at <= 0 || at == trimmed.length() - 1) {
        throw new PipelineParseException(
            "synthesis: library coordinate '"
                + coordinate
                + "' must be of the form '<git-url>@<ref>' — e.g."
                + " 'file:///srv/libs/ci-shared.git@v1.0' (a bare library name is not"
                + " supported in this release)");
      }
      String url = trimmed.substring(0, at).strip();
      String ref = trimmed.substring(at + 1).strip();
      if (url.isEmpty() || ref.isEmpty()) {
        throw new PipelineParseException(
            "synthesis: library coordinate '"
                + coordinate
                + "' has an empty url or ref — expected '<git-url>@<ref>'");
      }
      return new Coordinate(url, ref);
    }
  }

  /** Default cache root under the JVM temp dir — used when no worker-supplied root is set. */
  @NonNull
  public static Path defaultCacheRoot() {
    return Paths.get(System.getProperty("java.io.tmpdir"), "titan-synthesis-libs");
  }
}
