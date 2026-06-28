package io.adaptiq.titan.scm.pulsar;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.scm.pulsar.PulsarEventSource.PulsarTrigger;
import io.adaptiq.titan.scm.reconcile.EventDedupeStore;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.scm.reconcile.ScmReconcileException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.PulsarSourceRow;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Boot-wired Quarkus {@code @Scheduled} driver that turns the registered-Pulsar-source endpoint
 * into a live, Kafka-free build trigger — the documented "#T2 (the Pulsar build-trigger slice)" gap
 * that {@link PulsarScannerScheduler} explicitly deferred.
 *
 * <p>Mirrors {@link io.adaptiq.titan.scm.github.GithubScannerScheduler} exactly for the boot
 * pattern: an {@code @ApplicationScoped} bean with a config-gated, config-cadenced
 * {@code @Scheduled} {@link #tick()} and a non-reentrant guard. Each tick:
 *
 * <ol>
 *   <li>reads the source-of-truth set of nodes from {@code titan.pulsar_sources} ({@link
 *       TitanStores#pulsarSources()});
 *   <li>builds a {@link PulsarClient} per node ({@link PulsarClientFactory#forNode});
 *   <li>runs one non-reentrant {@link PulsarScannerScheduler} pass over a {@link PulsarRepoScanner}
 *       bound to that node and the SHARED, DB-backed {@link EventDedupeStore} (so a change builds
 *       once across ticks, not every tick);
 *   <li>hands each fresh {@link PulsarChangeDiscovery} to a sink that dispatches a real build via
 *       the SAME tail as {@link io.adaptiq.titan.api.PulsarWebhookApi#receive}: resolve the linked
 *       enabled job by {@code repo}, normalize via {@code PulsarEventSource(nodeUrl)::triggerFor},
 *       then {@link BuildEnqueuer#enqueue}.
 * </ol>
 *
 * <p><strong>Per-source isolation.</strong> One node being down (a {@link ScmReconcileException})
 * is caught inside {@link PulsarScannerScheduler#tick()} and never aborts the other sources. A
 * per-source {@link RuntimeException} from job-resolution / dispatch is also isolated here.
 *
 * <p><strong>Gating + cadence.</strong> Disabled by default via {@code pulsar.scan.enabled}
 * (GitHub's crawler defaults on, but Pulsar poll-without-a-node is pure overhead until an operator
 * registers a source, so the safe default is off). Cadence is {@code
 * quarkus.scheduler.titan.pulsar-scan.every}, default {@code 30s}.
 */
@ApplicationScoped
public class PulsarScanScheduler {

  private static final Logger LOGGER = Logger.getLogger(PulsarScanScheduler.class.getName());

  private final TitanStores stores;
  private final EventDedupeStore dedupe;
  private final boolean enabled;
  private final Function<String, PulsarClient> clientForNode;
  private final Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>>
      triggerResolverForNode;
  private final BuildDispatch dispatch;
  private final ReconcileAudit audit;

  private final AtomicBoolean running = new AtomicBoolean(false);

  /** Production constructor — real node clients, real {@link PulsarEventSource}, real enqueue. */
  @Inject
  public PulsarScanScheduler(
      TitanStores stores,
      EventDedupeStore dedupe,
      AuditService auditService,
      @ConfigProperty(name = "pulsar.scan.enabled", defaultValue = "false") boolean enabled) {
    this(
        stores,
        dedupe,
        enabled,
        new PulsarClientFactory()::forNode,
        nodeUrl -> new PulsarEventSource(nodeUrl)::triggerFor,
        BuildEnqueuer::enqueue,
        reconcileAuditSink(auditService));
  }

  /**
   * Test constructor — explicit client factory, trigger resolver, dispatch and audit seam (no
   * network).
   */
  PulsarScanScheduler(
      @NonNull TitanStores stores,
      @NonNull EventDedupeStore dedupe,
      boolean enabled,
      @NonNull Function<String, PulsarClient> clientForNode,
      @NonNull
          Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>>
              triggerResolverForNode,
      @NonNull BuildDispatch dispatch,
      @NonNull ReconcileAudit audit) {
    this.stores = Objects.requireNonNull(stores, "stores");
    this.dedupe = Objects.requireNonNull(dedupe, "dedupe");
    this.enabled = enabled;
    this.clientForNode = Objects.requireNonNull(clientForNode, "clientForNode");
    this.triggerResolverForNode =
        Objects.requireNonNull(triggerResolverForNode, "triggerResolverForNode");
    this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  /**
   * Adapt the request-scoped {@link AuditService} to the {@link ReconcileAudit} seam. Every build
   * the poll scanner enqueues is by construction a <em>reconcile-recovered</em> build: it reached
   * the scanner only because no other path (the webhook) claimed the shared dedupe key first — i.e.
   * the webhook was dropped or never arrived. So it earns one {@code SCM_WEBHOOK_RECOVERED} audit
   * row (target=JOB), the operator's signal that the webhook channel is flaky for that repo.
   */
  static ReconcileAudit reconcileAuditSink(@NonNull AuditService auditService) {
    Objects.requireNonNull(auditService, "auditService");
    return (change, jobId, buildId) ->
        auditService.recordAs(
            "pulsar-scan",
            AuditAction.SCM_WEBHOOK_RECOVERED,
            AuditTargetType.JOB,
            String.valueOf(jobId),
            "{\"provider\":\"pulsar\",\"repo\":\""
                + change.repo()
                + "\",\"changeId\":\""
                + change.changeId()
                + "\",\"revision\":\""
                + change.revision()
                + "\",\"eventId\":\""
                + change.dispatchEventId()
                + "\",\"buildId\":"
                + buildId
                + "}");
  }

  /**
   * The Quarkus-driven scheduled entry point. Quarkus requires a {@code @Scheduled} business method
   * to return {@code void}; the testable engine is {@link #tick()}.
   */
  @Scheduled(
      every = "{quarkus.scheduler.titan.pulsar-scan.every:30s}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduledScan() {
    tick();
  }

  /**
   * One scheduled pass over every registered Pulsar source. Non-reentrant: a tick that fires while
   * the previous pass is still running is skipped. Disabled fast-returns. Never throws — every
   * per-source exceptional path is isolated so one bad node cannot starve its siblings.
   *
   * @return the total number of fresh discoveries dispatched across all sources this pass
   */
  public int tick() {
    if (!enabled) {
      return 0;
    }
    if (!running.compareAndSet(false, true)) {
      return 0; // defence-in-depth — SKIP above already serialises
    }
    try {
      int total = 0;
      for (PulsarSourceRow src : stores.pulsarSources().listAll()) {
        total += scanSource(src);
      }
      return total;
    } finally {
      running.set(false);
    }
  }

  /**
   * Scan one source. A node outage surfaces as a {@link ScmReconcileException} inside the {@link
   * PulsarScannerScheduler} tick (caught + logged there); any other per-source {@link
   * RuntimeException} is caught here so the loop continues to the next source.
   */
  private int scanSource(@NonNull PulsarSourceRow src) {
    try {
      PulsarClient client = clientForNode.apply(src.nodeUrl);
      PulsarRepoScanner scanner = new PulsarRepoScanner(client, dedupe);
      Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> resolver =
          triggerResolverForNode.apply(src.nodeUrl);
      PulsarScannerScheduler nodeScheduler =
          new PulsarScannerScheduler(scanner, discovery -> dispatchDiscovery(discovery, resolver));
      return nodeScheduler.tick();
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[pulsar-scan] source {0} failed — isolating, other sources continue: {1}",
          new Object[] {src.nodeUrl, e.getMessage()});
      return 0;
    }
  }

  /**
   * The dispatch tail — mirrors {@link io.adaptiq.titan.api.PulsarWebhookApi#receive}: resolve the
   * enabled job linked to the change's repo (no enabled job ⇒ authenticated no-op), normalize via
   * the node's {@link PulsarEventSource} (no pipeline file ⇒ honest no-op), then enqueue a build
   * with the IDENTICAL argument shape the webhook uses.
   */
  private void dispatchDiscovery(
      @NonNull PulsarChangeDiscovery change,
      @NonNull Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> resolver) {
    Optional<JobRow> job = stores.jobs().findByFullName(change.repo());
    if (job.isEmpty() || !job.get().enabled) {
      LOGGER.log(
          Level.FINE,
          "[pulsar-scan] change {0}@{1}: no enabled job linked to repo — no-op",
          new Object[] {change.changeId(), change.repo()});
      return;
    }
    // The fallible tail: resolver clones the change tip + discovers its pipeline (throws on a
    // transient transport failure — git missing, shallow-fetch rejected, node 5xx), then we
    // enqueue.
    // The dedupe claim was already taken in the scanner BEFORE this point, so a FAILURE here must
    // release the claim or the change is permanently dropped (live bug: every fix needed a
    // brand-new
    // change). An EMPTY trigger is an HONEST no-op (no pipeline file) — no build is owed, so the
    // claim is KEPT to avoid re-cloning the same change every tick.
    try {
      Optional<PulsarTrigger> trigger = resolver.apply(change);
      if (trigger.isEmpty()) {
        return; // honest no-op: a change with no pipeline file dispatches nothing — keep the claim
      }
      long buildId =
          dispatch.enqueue(
              stores,
              job.get().id,
              "pulsar:change:" + change.changeId(),
              "pulsar",
              "{\"commitSha\":\""
                  + change.revision()
                  + "\",\"changeId\":\""
                  + change.changeId()
                  + "\"}",
              null);
      LOGGER.log(
          Level.INFO,
          "[pulsar-scan] enqueued build {0} for job {1} (change {2}@{3} rev {4})",
          new Object[] {
            buildId, job.get().fullName, change.changeId(), change.repo(), change.revision()
          });
      // The poll scanner reached this change only because the webhook never claimed its tip — a
      // reconcile recovery. Emit the SCM_WEBHOOK_RECOVERED audit row (issue #4).
      audit.recordRecovered(change, job.get().id, buildId);
    } catch (RuntimeException e) {
      // The dedupe claim was taken in the scanner BEFORE this fallible tail (resolver clones the
      // change tip + discovers its pipeline — throws on a transient transport failure: git missing,
      // shallow-fetch rejected, node 5xx; enqueue can also fail). A FAILURE here must RELEASE the
      // claim or the change is permanently dropped (live bug: every fix needed a brand-new change).
      // An EMPTY trigger above is an honest no-op (no pipeline file) — no build owed — so the claim
      // is KEPT (no release, no re-clone every tick). We log + swallow so a sibling discovery on
      // the
      // same scan still dispatches (per-discovery isolation); the next tick re-emits + retries this
      // change because the claim is gone.
      dedupe.release(ScmProvider.PULSAR, change.dispatchEventId());
      LOGGER.log(
          Level.WARNING,
          "[pulsar-scan] dispatch failed for change {0}@{1} rev {2} — released dedupe claim,"
              + " next tick retries: {3}",
          new Object[] {change.changeId(), change.repo(), change.revision(), e.getMessage()});
    }
  }

  /**
   * The enqueue seam — {@link BuildEnqueuer#enqueue} in production, a probe in tests. Keeps the
   * static enqueue call testable without a live DB while preserving its exact argument shape.
   */
  @FunctionalInterface
  interface BuildDispatch {
    long enqueue(
        @NonNull TitanStores stores,
        long jobId,
        @NonNull String triggeredBy,
        @NonNull String triggerType,
        String triggerMetaJson,
        String parametersJson);
  }

  /**
   * The audit seam — {@code AuditService.recordAs(SCM_WEBHOOK_RECOVERED, …)} in production, a probe
   * in tests. Invoked exactly once per build the poll scanner recovers (the webhook for this tip
   * was dropped), so tests can assert recovery is audited without standing up a SecurityIdentity.
   */
  @FunctionalInterface
  interface ReconcileAudit {
    void recordRecovered(@NonNull PulsarChangeDiscovery change, long jobId, long buildId);
  }
}
