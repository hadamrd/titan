package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.discovery_sources} — a watched place (a local
 * directory, a GitHub repo) plus the mutable outcome of its last timed poll. Natural key: {@link
 * #name}.
 */
public class DiscoverySourceRow {
  public long id;

  /** Unique source name — keys the row; matches the configured {@code DiscoverySource} name. */
  public String name;

  /** Discriminator for the {@code DiscoverySource} kind, e.g. {@code localDirectory}. */
  public String sourceType;

  /** Opaque JSON snapshot of the source configuration. Null → no config captured. */
  @Nullable public String configJson;

  public boolean enabled;

  /** When the worker last polled this source. Null → never polled. */
  @Nullable public Instant lastPolledAt;

  /** Outcome of the last poll, e.g. {@code ok}/{@code error}. Null → never polled. */
  @Nullable public String lastStatus;

  /** The last poll error, if any — surfaced for diagnostics. Null → no error. */
  @Nullable public String lastError;

  public Instant createdAt;
}
