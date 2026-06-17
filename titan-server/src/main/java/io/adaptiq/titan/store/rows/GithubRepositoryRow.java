package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.github_repositories} — repos visible to one
 * installation (#832, design/63). Populated by the sync endpoint; scanner (Child B) reads from
 * here.
 */
public class GithubRepositoryRow {
  public long id;
  public long installId;
  public long repoId;
  public String owner;
  public String name;
  @Nullable public String defaultBranch;
  public boolean isPrivate;
  @Nullable public Instant lastScannedAt;
  public Instant createdAt;
}
