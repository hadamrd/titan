package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.github_installations} — one per org / user the App
 * is installed on (#832, design/63).
 */
public class GithubInstallationRow {
  public long id;
  public long installId;
  public String accountLogin;
  public String accountType;
  public String targetType;
  @Nullable public Instant suspendedAt;
  public Instant createdAt;
  public Instant updatedAt;
}
