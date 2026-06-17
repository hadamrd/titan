package io.adaptiq.titan.store.rows;

import java.time.Instant;
import java.util.UUID;

/** Mutable POJO mapping to a row in {@code rf_logs}. */
public class LogRow {
  public long id;
  public UUID taskId;
  public int chunkIndex;
  public String stream;
  public String data;
  public Instant producedAt;
  public boolean isFinal;
}
