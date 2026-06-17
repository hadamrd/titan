package io.adaptiq.titan.discovery;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.credentials.CredentialsService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

/**
 * A {@link DiscoverySource} that watches a single GitHub repository for one pipeline file, fetched
 * via the standard GitHub client library ({@code org.kohsuke.github}).
 *
 * <p>Auth is optional: with no credential reference the source connects anonymously and works
 * against public repos; with a credential reference resolving a secret-text credential in {@link
 * CredentialsService}, the token is supplied via {@code GitHubBuilder.withOAuthToken} so private
 * repos and a higher rate limit are reachable.
 *
 * <p>The {@code org.kohsuke.github} {@link GHContent} type decodes the blob (base64) transparently,
 * so no manual JSON picking or base64 decoding is needed here.
 *
 * <p>Every call routes through the overridable {@link #gitHub()} seam so a unit test can subclass
 * this source and inject a mock/fake {@link GitHub} with no network; the default seam builds a real
 * client via {@link GitHubBuilder}.
 *
 * <p>Uses constructor injection and {@link CredentialsService}-based token resolution.
 */
public class GitHubRepoSource extends DiscoverySource {

  private static final Logger LOGGER = Logger.getLogger(GitHubRepoSource.class.getName());

  private final String name;
  private final String repo;
  private String branch = "main";
  private String path = "titan-pipeline.yml";

  @Nullable private String credentialsScope;
  @Nullable private String credentialsKey;
  @Nullable private final CredentialsService credentials;

  public GitHubRepoSource(@NonNull String name, @NonNull String repo) {
    this(name, repo, null);
  }

  public GitHubRepoSource(
      @NonNull String name, @NonNull String repo, @Nullable CredentialsService credentials) {
    this.name = name;
    this.repo = repo;
    this.credentials = credentials;
  }

  @NonNull
  @Override
  public String getName() {
    return name;
  }

  @NonNull
  @Override
  public String getType() {
    return "gitHubRepo";
  }

  @NonNull
  public String getRepo() {
    return repo;
  }

  @NonNull
  public String getBranch() {
    return branch;
  }

  public void setBranch(@Nullable String branch) {
    this.branch = (branch == null || branch.isBlank()) ? "main" : branch;
  }

  @NonNull
  public String getPath() {
    return path;
  }

  public void setPath(@Nullable String path) {
    this.path = (path == null || path.isBlank()) ? "titan-pipeline.yml" : path;
  }

  @Nullable
  public String getCredentialsScope() {
    return credentialsScope;
  }

  @Nullable
  public String getCredentialsKey() {
    return credentialsKey;
  }

  /** Set the {@code (scope, key)} tuple that addresses a secret-text credential. */
  public void setCredentials(@Nullable String scope, @Nullable String key) {
    this.credentialsScope = (scope == null || scope.isBlank()) ? null : scope;
    this.credentialsKey = (key == null || key.isBlank()) ? null : key;
  }

  @NonNull
  @Override
  public List<DiscoveredFile> scan() {
    try {
      GHContent content = gitHub().getRepository(repo).getFileContent(path, branch);
      return List.of(new DiscoveredFile(repo + "/" + path, repo, branch, content.getSha()));
    } catch (GHFileNotFoundException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] discovery source ''{0}'': {1}/{2}@{3} not found on GitHub",
          new Object[] {name, repo, path, branch});
      return List.of();
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "[titan] discovery source '" + name + "': GitHub scan failed", e);
      return List.of();
    }
  }

  @NonNull
  @Override
  public byte[] read(@NonNull String path) {
    // scan() emits the locator as "<repo>/<in-repo path>"; honour that
    // argument rather than re-deriving from the configured field, so the
    // SPI contract holds if a future source serves more than one file.
    String prefix = repo + "/";
    String inRepoPath = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    try {
      GHContent content = gitHub().getRepository(repo).getFileContent(inRepoPath, branch);
      // GHContent.read() yields the decoded blob bytes — base64 is handled by the client.
      try (InputStream in = content.read()) {
        return in.readAllBytes();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(
          "GitHub contents fetch failed for " + repo + "/" + inRepoPath, e);
    }
  }

  /**
   * The GitHub-client seam — produces a connected {@link GitHub} instance. The default
   * implementation builds a real client via {@link GitHubBuilder}: an anonymous connection when no
   * credential is set, or an OAuth-token connection when a secret-text credential resolves.
   * Overridable (subclass) so a unit test can inject a mock/fake {@code GitHub} with no network.
   *
   * <p>Kept as a single factory so a future {@code GitHubOrgSource} can share the exact same
   * client-building logic.
   */
  @NonNull
  protected GitHub gitHub() throws IOException {
    String token = resolveToken();
    GitHubBuilder builder = new GitHubBuilder();
    if (token != null) {
      builder = builder.withOAuthToken(token);
    }
    return builder.build();
  }

  /** Resolve the secret-text token for the configured credential, or null when unset/unresolved. */
  @Nullable
  private String resolveToken() {
    if (credentialsScope == null || credentialsKey == null || credentials == null) {
      return null;
    }
    Optional<String> plain = credentials.resolvePlaintext(credentialsScope, credentialsKey);
    if (plain.isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] discovery source ''{0}'': credential ''{1}/{2}'' did not resolve",
          new Object[] {name, credentialsScope, credentialsKey});
      return null;
    }
    return plain.get();
  }
}
