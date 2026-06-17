package io.adaptiq.titan.store.rows;

/**
 * Read-only projection joining {@code titan.jobs} (its {@code github_installation_id} + {@code
 * github_repo_id} columns) onto {@code titan.github_repositories} so a caller can resolve a job to
 * its {@code (installId, owner, name)} tuple in one query. Used by {@link
 * io.adaptiq.titan.scm.github.GithubStatusReporter} (closes #835) to look up where to POST commit
 * statuses for an App-triggered build.
 *
 * <p>A null row means either:
 *
 * <ul>
 *   <li>the job has no GitHub-App linkage at all ({@code github_installation_id IS NULL}), or
 *   <li>the linkage exists but the matching {@code github_repositories} row was deleted (e.g. the
 *       repo was removed from the install). The status post is silently skipped in that case.
 * </ul>
 */
public class JobGithubLinkRow {
  public long installId;
  public long repoId;
  public String owner;
  public String name;
}
