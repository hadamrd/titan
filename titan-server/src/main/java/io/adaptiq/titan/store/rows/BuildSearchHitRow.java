package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * One hit row from the build full-text search ({@code /api/v1/builds/search}, #1083).
 *
 * <p>Carries enough to render a result card without a follow-up fetch:
 *
 * <ul>
 *   <li>build identity ({@code id}, {@code buildNumber}, {@code jobFullName}) for click-through;
 *   <li>summary fields ({@code status}, {@code queuedAt}, {@code triggeredBy}) for the row UI;
 *   <li>{@code snippet} — a {@code ts_headline}-rendered fragment with {@code <mark>}-style tags
 *       around matched tokens so the UI can dangerously-set-inner-html the snippet AFTER
 *       server-side highlighting.
 * </ul>
 *
 * <p>The snippet is generated server-side, not client-side, because the underlying log chunks live
 * only in the DB — we don't ship the whole log to the browser to grep through.
 */
public class BuildSearchHitRow {
  public long id;
  public long jobId;
  public String jobFullName;
  public int buildNumber;
  public String status;
  public Instant queuedAt;

  @Nullable public String triggeredBy;

  @Nullable public String snippet;
}
