package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.dto.BuildSearchQuery;
import io.adaptiq.titan.store.rows.BuildSearchHitRow;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;

/**
 * Full-text search over {@code titan.logs} joined to {@code titan.builds} / {@code titan.jobs}
 * (closes #1083).
 *
 * <p>Backed by the generated {@code tsv} column added in V34. The DAO uses {@code
 * websearch_to_tsquery} so user input is parsed as a phrase-aware query — operators ({@code -foo},
 * {@code "exact phrase"}, {@code OR}) work the way they do in any modern search bar, and the input
 * is never concatenated into SQL.
 *
 * <p>Snippets are produced by {@code ts_headline()} on the matching log chunk. {@code StartSel} /
 * {@code StopSel} place {@code <mark>} tags around hits — the headline function HTML-escapes the
 * surrounding text, so the result is safe to render via {@code dangerouslySetInnerHTML} after the
 * UI passes it through a strict sanitiser (allow-list: {@code <mark>} only).
 *
 * <p>One build can match multiple log chunks; we pick the most recent chunk's snippet via {@code
 * ROW_NUMBER() OVER (PARTITION BY b.id ORDER BY l.produced_at DESC)} so each build appears at most
 * once.
 */
@RegisterFieldMapper(BuildSearchHitRow.class)
public interface BuildSearchDao extends SqlObject {

  /** Whitespace-collapsed snippet config — narrow enough for a single result row. */
  String HEADLINE_OPTS =
      "StartSel=<mark>,StopSel=</mark>,MaxFragments=2,FragmentDelimiter=…,MaxWords=20,MinWords=5";

  /**
   * Run the search. Returns at most {@code q.limit()} rows ordered newest-first by build queued-at.
   */
  @NonNull
  default List<BuildSearchHitRow> search(@NonNull BuildSearchQuery q) {
    Handle h = getHandle();
    StringBuilder sql = new StringBuilder();
    sql.append("WITH matches AS (\n");
    sql.append("  SELECT b.id, b.job_id, j.full_name AS job_full_name,\n");
    sql.append("         b.build_number, b.status, b.queued_at, b.triggered_by,\n");
    sql.append("         ts_headline('english', l.data,\n");
    sql.append("           websearch_to_tsquery('english', :q),\n");
    sql.append("           '").append(HEADLINE_OPTS).append("') AS snippet,\n");
    sql.append(
        "         ROW_NUMBER() OVER (PARTITION BY b.id ORDER BY l.produced_at DESC) AS rn\n");
    sql.append("  FROM titan.logs l\n");
    sql.append("  JOIN (SELECT task_token, build_id FROM titan.task_queue\n");
    sql.append("        UNION ALL\n");
    sql.append("        SELECT task_token, build_id FROM titan.task_archive) tt\n");
    sql.append("    ON tt.task_token = l.task_id\n");
    sql.append("  JOIN titan.builds b ON b.id = tt.build_id\n");
    sql.append("  JOIN titan.jobs   j ON j.id = b.job_id\n");
    sql.append("  WHERE l.tsv @@ websearch_to_tsquery('english', :q)\n");
    appendFilters(sql, q);
    sql.append(")\n");
    sql.append("SELECT id, job_id, job_full_name, build_number, status, queued_at,\n");
    sql.append("       triggered_by, snippet\n");
    sql.append("FROM matches WHERE rn = 1\n");
    sql.append("ORDER BY queued_at DESC, id DESC\n");
    sql.append("LIMIT :limit OFFSET :offset");

    Query query = h.createQuery(sql.toString());
    bindFilters(query, q);
    query.bind("q", q.q());
    query.bind("limit", q.limit());
    query.bind("offset", q.offset());
    return query.mapTo(BuildSearchHitRow.class).list();
  }

  /**
   * Count distinct matching builds (independent of pagination). Used to populate {@code total} in
   * the wire envelope so the UI can render "showing 1-10 of 47".
   */
  default long countMatches(@NonNull BuildSearchQuery q) {
    Handle h = getHandle();
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT COUNT(DISTINCT b.id)\n");
    sql.append("FROM titan.logs l\n");
    sql.append("JOIN (SELECT task_token, build_id FROM titan.task_queue\n");
    sql.append("      UNION ALL\n");
    sql.append("      SELECT task_token, build_id FROM titan.task_archive) tt\n");
    sql.append("  ON tt.task_token = l.task_id\n");
    sql.append("JOIN titan.builds b ON b.id = tt.build_id\n");
    sql.append("JOIN titan.jobs   j ON j.id = b.job_id\n");
    sql.append("WHERE l.tsv @@ websearch_to_tsquery('english', :q)\n");
    appendFilters(sql, q);

    Query query = h.createQuery(sql.toString());
    bindFilters(query, q);
    query.bind("q", q.q());
    return query.mapTo(Long.class).one();
  }

  /**
   * Append the optional {@code status / jobGlob / since} filters as {@code AND}-clauses. Pulled out
   * so {@link #search} and {@link #countMatches} share the exact same predicate set — drift here
   * would let the count and the page disagree.
   */
  private static void appendFilters(StringBuilder sql, BuildSearchQuery q) {
    List<String> extra = new ArrayList<>();
    if (!q.status().isEmpty()) {
      extra.add("b.status IN (<statuses>)");
    }
    if (q.jobGlob() != null) {
      extra.add("j.full_name LIKE :jobLike");
    }
    if (q.since() != null) {
      extra.add("b.queued_at >= :since");
    }
    for (String w : extra) {
      sql.append("    AND ").append(w).append('\n');
    }
  }

  private static void bindFilters(Query query, BuildSearchQuery q) {
    if (!q.status().isEmpty()) {
      query.bindList("statuses", q.status());
    }
    if (q.jobGlob() != null) {
      query.bind("jobLike", q.jobGlob().replace('*', '%'));
    }
    if (q.since() != null) {
      query.bind("since", q.since());
    }
  }
}
