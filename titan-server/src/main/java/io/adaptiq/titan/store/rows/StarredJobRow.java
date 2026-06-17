package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.user_starred_jobs} (#703).
 *
 * <p>Star ownership is the OIDC subject claim — set at the API layer from {@code SecurityIdentity},
 * never trusted from a request body. The {@link #starredAt} timestamp is column-defaulted on insert
 * and read-only thereafter.
 */
public class StarredJobRow {
  public String userSubject;
  public long jobId;
  public Instant starredAt;
}
