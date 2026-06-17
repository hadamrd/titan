package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.pulsar_sources} — one registered Pulsar SCM node
 * connection (#1283). Populated by the source-registration endpoint; the UI Integrations card reads
 * from here. Public-field POJO per the JDBI {@code @RegisterFieldMapper} convention.
 */
public class PulsarSourceRow {
  public long id;
  public String nodeUrl;
  @Nullable public String nodeName;
  @Nullable public Integer repoCount;
  @Nullable public Instant lastPolledAt;
  public Instant createdAt;
  public Instant updatedAt;
}
