package io.adaptiq.titan.discovery;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * SPI for a place the discovery worker scans for Titan pipeline definitions — a local directory, a
 * GitHub repository, and (later) a GitHub org or a GitLab group.
 *
 * <p>The server discovers implementations via {@link java.util.ServiceLoader} (the SPI lives in
 * {@code META-INF/services/io.adaptiq.titan.discovery.DiscoverySource}) and instantiates them with
 * configuration drawn from env / Quarkus {@code @ConfigMapping}.
 *
 * <p>The worker upserts a {@code titan.discovery_sources} row per source (keyed by {@link
 * #getName()}), then on each poll calls {@link #scan()} to learn which pipeline files exist (and at
 * which revision) and {@link #read(String)} to fetch the YAML bytes for a file the worker has
 * decided to process.
 *
 * <p>{@link #verifyWebhook} is an additive hook for a future push-driven discovery webhook; v1
 * polls only, so the default ({@code Optional.empty()}) is correct for every current source.
 */
public abstract class DiscoverySource {

  /** Unique source name — keys the {@code titan.discovery_sources} row. */
  @NonNull
  public abstract String getName();

  /** Discriminator for the source kind, stored in {@code discovery_sources.source_type}. */
  @NonNull
  public abstract String getType();

  /**
   * Scan the source for pipeline files that exist now. Each returned {@link DiscoveredFile} carries
   * a content revision ({@code commitSha}) so an unchanged file is deduped by the worker. A scan
   * failure must be tolerated — return an empty list, never throw.
   */
  @NonNull
  public abstract List<DiscoveredFile> scan();

  /** Fetch the raw YAML bytes for a {@code path} previously returned by {@link #scan()}. */
  @NonNull
  public abstract byte[] read(@NonNull String path);

  /**
   * Verify an inbound discovery-webhook delivery. Additive hook for a future push-driven discovery
   * webhook (design blueprint) — v1 polls only, so the default is {@code Optional.empty()}.
   *
   * @return a non-empty {@code Optional} carrying a verified repo identifier when this source
   *     accepts the delivery; {@code Optional.empty()} otherwise.
   */
  @NonNull
  public Optional<String> verifyWebhook(@NonNull byte[] body, @Nullable String sig) {
    return Optional.empty();
  }

  /**
   * One pipeline file observed by {@link #scan()} at one content revision. {@code path} is the
   * source-internal locator handed back to {@link #read(String)}; {@code repo}/{@code
   * branch}/{@code commitSha} are provenance and may be {@code null} where the source has no such
   * notion.
   */
  public static final class DiscoveredFile {

    /** Source-internal locator for the file — passed back to {@link #read(String)}. Non-null. */
    @NonNull public final String path;

    /** Repository (or directory) the file lives in. Null → not applicable. */
    @Nullable public final String repo;

    /** Branch the file was seen on. Null → not applicable (e.g. a local directory). */
    @Nullable public final String branch;

    /** Content revision — blob SHA (GitHub) or content hash (local dir). Null → unversioned. */
    @Nullable public final String commitSha;

    public DiscoveredFile(
        @NonNull String path,
        @Nullable String repo,
        @Nullable String branch,
        @Nullable String commitSha) {
      this.path = path;
      this.repo = repo;
      this.branch = branch;
      this.commitSha = commitSha;
    }

    @NonNull
    public String getPath() {
      return path;
    }

    @Nullable
    public String getRepo() {
      return repo;
    }

    @Nullable
    public String getBranch() {
      return branch;
    }

    @Nullable
    public String getCommitSha() {
      return commitSha;
    }
  }
}
