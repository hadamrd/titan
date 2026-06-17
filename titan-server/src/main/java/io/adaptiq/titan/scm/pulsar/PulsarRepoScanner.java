package io.adaptiq.titan.scm.pulsar;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.reconcile.EventDedupeStore;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.scm.reconcile.ScmReconcileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls a Pulsar node for open changes that need a build and emits a normalized {@link
 * PulsarChangeDiscovery} per fresh change (issue #1280, convergence axis 2 / scm-depth). Mirrors
 * {@link io.adaptiq.titan.scm.github.GithubRepoScanner} — the pull-based discovery walker — but for
 * Pulsar's change ledger instead of GitHub's {@code .titan/pipelines} tree.
 *
 * <h2>Scan loop</h2>
 *
 * <p>For each repo the node hosts ({@link PulsarClient#listRepos()}):
 *
 * <ol>
 *   <li>List the open changes ({@link PulsarClient#listOpenChanges(String)}); each entry already
 *       carries its current tip oid ({@code revision.tip}). The scanner does NOT call {@link
 *       PulsarClient#listChangeRefs(String)} for tip resolution — the live node publishes only
 *       {@code refs/heads/main} and no {@code refs/pulsar/changes/*} refs, so the tip must come
 *       from the {@code /changes} payload itself. A change with a blank/absent tip is simply not
 *       returned and so is naturally skipped.
 *   <li>Dedupe against the shared {@link EventDedupeStore}: the dispatch key is {@code
 *       <repo>:<changeId>:<revision>}, so a change re-emits only when its tip advances (a new push
 *       to the change ⇒ a new build is owed). A change already claimed is skipped.
 * </ol>
 *
 * <h2>Error contract</h2>
 *
 * <p>A node 5xx or malformed-JSON response surfaces from {@link PulsarClient} as a {@link
 * PulsarApiException}; the scanner re-wraps it as a {@link ScmReconcileException} carrying the
 * provider + repo (Manifesto §"Errors": typed boundary error, never a silent empty scan). The
 * caller (scheduler) logs the single structured line and the next tick retries.
 */
public final class PulsarRepoScanner {

  private static final Logger LOGGER = Logger.getLogger(PulsarRepoScanner.class.getName());

  private final PulsarClient client;
  private final EventDedupeStore dedupe;

  public PulsarRepoScanner(@NonNull PulsarClient client, @NonNull EventDedupeStore dedupe) {
    this.client = Objects.requireNonNull(client, "client");
    this.dedupe = Objects.requireNonNull(dedupe, "dedupe");
  }

  /**
   * Scan every repo the node hosts. A failure on the repo-listing call aborts the whole scan with a
   * {@link ScmReconcileException} (repo {@code "*"}). A failure on a single repo's scan is
   * <em>isolated</em>: it is logged and the walk continues to its siblings (mirrors {@link
   * io.adaptiq.titan.scm.github.GithubRepoScanner#scanAll()}'s try/catch-log-continue per unit), so
   * one broken repo can't discard every already-scanned repo's discoveries nor starve sibling
   * builds indefinitely.
   */
  @NonNull
  public List<PulsarChangeDiscovery> scanAll() throws ScmReconcileException {
    List<String> repos;
    try {
      repos = client.listRepos();
    } catch (PulsarApiException e) {
      throw new ScmReconcileException(
          ScmProvider.PULSAR, "*", "pulsar listRepos failed: " + e.getMessage(), e);
    }
    List<PulsarChangeDiscovery> out = new ArrayList<>();
    for (String repo : repos) {
      try {
        out.addAll(scanRepo(repo));
      } catch (ScmReconcileException e) {
        LOGGER.log(
            Level.WARNING,
            "[pulsar-scan] scan failed for repo {0}: {1} — isolating, siblings continue",
            new Object[] {repo, e.getMessage()});
      }
    }
    return out;
  }

  /**
   * Scan one repo: list its open changes, resolve each tip, dedupe, and return the fresh discovery
   * items. A transport / parse failure surfaces as a {@link ScmReconcileException}.
   */
  @NonNull
  public List<PulsarChangeDiscovery> scanRepo(@NonNull String repo) throws ScmReconcileException {
    final List<PulsarClient.PulsarOpenChange> openChanges;
    try {
      openChanges = client.listOpenChanges(repo);
    } catch (PulsarApiException e) {
      throw new ScmReconcileException(
          ScmProvider.PULSAR, repo, "pulsar listOpenChanges failed: " + e.getMessage(), e);
    }

    List<PulsarChangeDiscovery> out = new ArrayList<>();
    for (PulsarClient.PulsarOpenChange change : openChanges) {
      // The tip comes straight from the /changes payload (revision.tip) — the live node publishes
      // no refs/pulsar/changes/* refs, so listChangeRefs cannot resolve it. A change with a
      // blank/absent tip is never returned by listOpenChanges, so it's already filtered out here.
      String revision = change.tip();
      // Dispatch key includes the revision so a change re-emits when its tip advances. markSeen
      // returns false when this (repo,change,revision) was already claimed (built) — skip it.
      String eventId = PulsarChangeDiscovery.dispatchEventId(repo, change.changeId(), revision);
      boolean fresh =
          dedupe.markSeen(ScmProvider.PULSAR, eventId, EventDedupeStore.Source.RECONCILE);
      if (!fresh) {
        continue;
      }
      out.add(PulsarChangeDiscovery.of(repo, change.changeId(), revision));
    }
    return out;
  }
}
