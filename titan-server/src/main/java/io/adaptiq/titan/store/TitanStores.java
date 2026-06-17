package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.db.Database;
import io.adaptiq.titan.db.TitanDataException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * Façade exposing every Titan execution-engine store (the {@code Rf*} DAOs). Always-on singleton
 * bound to {@link Database#dataSource()} for the default deployment; constructible against a custom
 * {@link DataSource} for tests.
 *
 * <p>The DAOs are JDBI 3 {@code SqlObject} interfaces. A single {@link Jdbi} instance is built per
 * {@code TitanStores} from the backing {@link DataSource} (with the {@link SqlObjectPlugin}
 * installed); each accessor returns a JDBI {@code onDemand} proxy — a thread-safe handle that
 * borrows and releases a connection per call.
 *
 * <p>Errors: JDBI's own {@code JdbiException}s propagate as runtime exceptions; the explicit
 * transaction helper {@link #withTransaction} wraps failures in {@link TitanDataException}.
 */
public final class TitanStores {

  private final JobDao jobDao;
  private final JobTriggerDao jobTriggerDao;
  private final BuildDao buildDao;
  private final BuildSearchDao buildSearchDao;
  private final TaskQueueDao taskQueueDao;
  private final FlowNodeDao flowNodeDao;
  private final AgentDao agentDao;
  private final AgentEventsDao agentEventsDao;
  private final LogDao logDao;
  private final ArtifactDao artifactDao;
  private final DiscoverySourceDao discoverySourceDao;
  private final DiscoveryEventDao discoveryEventDao;
  private final TimerDao timerDao;
  private final CredentialDao credentialDao;
  private final DiscoveryStateDao discoveryStateDao;
  private final TestResultDao testResultDao;
  private final StatsDao statsDao;
  private final TopFailingJobDao topFailingJobDao;
  private final JobStatsDao jobStatsDao;
  private final JobTimingsDao jobTimingsDao;
  private final DurationTrendDao durationTrendDao;
  private final ActivityDao activityDao;
  private final PersonalAccessTokenDao personalAccessTokenDao;
  private final AuditLogDao auditLogDao;
  private final AuditRetentionPolicyDao auditRetentionPolicyDao;
  private final StarredJobsDao starredJobsDao;
  private final ApprovalsDao approvalsDao;
  private final GithubAppDao githubAppDao;
  private final GithubInstallationDao githubInstallationDao;
  private final GithubRepositoryDao githubRepositoryDao;
  private final GithubPipelineDiscoveredDao githubPipelineDiscoveredDao;
  private final UserRolesDao userRolesDao;
  private final GroupRoleMappingDao groupRoleMappingDao;
  private final RbacUserRoleDao rbacUserRoleDao;
  private final RbacAuditDao rbacAuditDao;
  private final ScmWebhookEventDao scmWebhookEventDao;
  private final ScmEventSeenDao scmEventSeenDao;
  private final PulsarSourceDao pulsarSourceDao;

  private final DataSource ds;
  private final Jdbi jdbi;

  /**
   * Legacy singleton — backs {@link #get()}. New code (everything in {@code titan-server}) takes
   * {@code TitanStores} via constructor injection (see {@code QueueProcessor.tick(stores,
   * controllerId, reapTimeoutSeconds)} as the template).
   */
  private static volatile TitanStores instance;

  private TitanStores(@NonNull DataSource ds) {
    this.ds = ds;
    this.jdbi = Jdbi.create(ds).installPlugin(new SqlObjectPlugin());
    this.jobDao = translating(jdbi.onDemand(JobDao.class), JobDao.class);
    this.jobTriggerDao = translating(jdbi.onDemand(JobTriggerDao.class), JobTriggerDao.class);
    this.buildDao = translating(jdbi.onDemand(BuildDao.class), BuildDao.class);
    this.buildSearchDao = translating(jdbi.onDemand(BuildSearchDao.class), BuildSearchDao.class);
    this.taskQueueDao = translating(jdbi.onDemand(TaskQueueDao.class), TaskQueueDao.class);
    this.flowNodeDao = translating(jdbi.onDemand(FlowNodeDao.class), FlowNodeDao.class);
    this.agentDao = translating(jdbi.onDemand(AgentDao.class), AgentDao.class);
    this.agentEventsDao = translating(jdbi.onDemand(AgentEventsDao.class), AgentEventsDao.class);
    this.logDao = translating(jdbi.onDemand(LogDao.class), LogDao.class);
    this.artifactDao = translating(jdbi.onDemand(ArtifactDao.class), ArtifactDao.class);
    this.discoverySourceDao =
        translating(jdbi.onDemand(DiscoverySourceDao.class), DiscoverySourceDao.class);
    this.discoveryEventDao =
        translating(jdbi.onDemand(DiscoveryEventDao.class), DiscoveryEventDao.class);
    this.timerDao = translating(jdbi.onDemand(TimerDao.class), TimerDao.class);
    this.credentialDao = translating(jdbi.onDemand(CredentialDao.class), CredentialDao.class);
    this.discoveryStateDao =
        translating(jdbi.onDemand(DiscoveryStateDao.class), DiscoveryStateDao.class);
    this.testResultDao = translating(jdbi.onDemand(TestResultDao.class), TestResultDao.class);
    this.statsDao = translating(jdbi.onDemand(StatsDao.class), StatsDao.class);
    this.topFailingJobDao =
        translating(jdbi.onDemand(TopFailingJobDao.class), TopFailingJobDao.class);
    this.jobStatsDao = translating(jdbi.onDemand(JobStatsDao.class), JobStatsDao.class);
    this.jobTimingsDao = translating(jdbi.onDemand(JobTimingsDao.class), JobTimingsDao.class);
    this.durationTrendDao =
        translating(jdbi.onDemand(DurationTrendDao.class), DurationTrendDao.class);
    this.activityDao = translating(jdbi.onDemand(ActivityDao.class), ActivityDao.class);
    this.personalAccessTokenDao =
        translating(jdbi.onDemand(PersonalAccessTokenDao.class), PersonalAccessTokenDao.class);
    this.auditLogDao = translating(jdbi.onDemand(AuditLogDao.class), AuditLogDao.class);
    this.auditRetentionPolicyDao =
        translating(jdbi.onDemand(AuditRetentionPolicyDao.class), AuditRetentionPolicyDao.class);
    this.starredJobsDao = translating(jdbi.onDemand(StarredJobsDao.class), StarredJobsDao.class);
    this.approvalsDao = translating(jdbi.onDemand(ApprovalsDao.class), ApprovalsDao.class);
    this.githubAppDao = translating(jdbi.onDemand(GithubAppDao.class), GithubAppDao.class);
    this.githubInstallationDao =
        translating(jdbi.onDemand(GithubInstallationDao.class), GithubInstallationDao.class);
    this.githubRepositoryDao =
        translating(jdbi.onDemand(GithubRepositoryDao.class), GithubRepositoryDao.class);
    this.githubPipelineDiscoveredDao =
        translating(
            jdbi.onDemand(GithubPipelineDiscoveredDao.class), GithubPipelineDiscoveredDao.class);
    this.userRolesDao = translating(jdbi.onDemand(UserRolesDao.class), UserRolesDao.class);
    this.groupRoleMappingDao =
        translating(jdbi.onDemand(GroupRoleMappingDao.class), GroupRoleMappingDao.class);
    this.rbacUserRoleDao = translating(jdbi.onDemand(RbacUserRoleDao.class), RbacUserRoleDao.class);
    this.rbacAuditDao = translating(jdbi.onDemand(RbacAuditDao.class), RbacAuditDao.class);
    this.scmWebhookEventDao =
        translating(jdbi.onDemand(ScmWebhookEventDao.class), ScmWebhookEventDao.class);
    this.scmEventSeenDao = translating(jdbi.onDemand(ScmEventSeenDao.class), ScmEventSeenDao.class);
    this.pulsarSourceDao = translating(jdbi.onDemand(PulsarSourceDao.class), PulsarSourceDao.class);
  }

  /**
   * Wrap a JDBI {@code onDemand} DAO so that any {@link org.jdbi.v3.core.JdbiException} thrown by a
   * SQL operation surfaces as a {@link TitanDataException}. Preserves the project convention that
   * SQL failures are unchecked {@code TitanDataException}s — callers (and the existing DAO tests)
   * never see a checked {@code SQLException} or a JDBI-specific exception type.
   */
  @NonNull
  private static <D> D translating(@NonNull D dao, @NonNull Class<D> daoType) {
    return daoType.cast(
        Proxy.newProxyInstance(
            daoType.getClassLoader(),
            new Class<?>[] {daoType},
            (proxy, method, args) -> {
              try {
                return method.invoke(dao, args);
              } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof org.jdbi.v3.core.JdbiException) {
                  throw new TitanDataException(
                      "titan." + daoType.getSimpleName() + "." + method.getName() + " failed",
                      cause);
                }
                throw cause;
              }
            }));
  }

  /** Build a TitanStores against an explicit {@link DataSource} (tests). */
  @NonNull
  public static TitanStores forDataSource(@NonNull DataSource ds) {
    return new TitanStores(ds);
  }

  /**
   * Resolve the default singleton, lazily wired to {@link Database#dataSource()}. <strong>Legacy
   * API.</strong> New code takes {@code TitanStores} via constructor injection.
   */
  @Deprecated
  @NonNull
  public static TitanStores get() {
    TitanStores local = instance;
    if (local == null) {
      synchronized (TitanStores.class) {
        if (instance == null) {
          instance = new TitanStores(Database.dataSource());
        }
        local = instance;
      }
    }
    return local;
  }

  /**
   * Reset the singleton (tests, and after {@link Database#reconfigure}). Legacy companion of {@link
   * #get()}.
   */
  @Deprecated
  public static void reset() {
    synchronized (TitanStores.class) {
      instance = null;
    }
  }

  /**
   * Build a single Titan SqlObject DAO bound to an explicit {@link DataSource}. Used by tests that
   * exercise one DAO in isolation; the production path goes through {@link #get()}.
   */
  @NonNull
  public static <D> D daoFor(@NonNull DataSource ds, @NonNull Class<D> daoType) {
    D dao = Jdbi.create(ds).installPlugin(new SqlObjectPlugin()).onDemand(daoType);
    return translating(dao, daoType);
  }

  /**
   * Run an action against a SqlObject DAO attached to a caller-supplied {@link Connection}. The
   * connection is <em>not</em> closed (its lifecycle belongs to the caller, e.g. {@link
   * #withTransaction}); only the JDBI {@code Handle} wrapping it is released. Backs the {@code
   * Connection}-taking DAO overloads (such as {@code BuildDao.insert(Connection, ...)}).
   */
  public static <D, T> T onConnection(
      @NonNull Connection conn, @NonNull Class<D> daoType, @NonNull Function<D, T> action) {
    Connection nonClosing = nonClosing(conn);
    Jdbi handleJdbi = Jdbi.create(() -> nonClosing).installPlugin(new SqlObjectPlugin());
    return handleJdbi.withExtension(daoType, action::apply);
  }

  /**
   * Execute a block of work inside a single database transaction.
   *
   * <p>The connection is borrowed from the pool, auto-commit is turned off, and the provided
   * function is executed. On success the transaction is committed; on any exception the transaction
   * is rolled back and the exception is wrapped in a {@link TitanDataException}.
   *
   * <p>Example:
   *
   * <pre>{@code
   * TitanStores.get().withTransaction(conn -> {
   *     long buildId = TitanStores.get().builds().insert(conn, buildRow);
   *     return buildId;
   * });
   * }</pre>
   *
   * @param <T> result type
   * @param action the transactional work, receiving a single connection
   * @return the result of the action
   * @throws TitanDataException on any SQL or application exception (transaction rolled back)
   */
  public <T> T withTransaction(@NonNull Function<Connection, T> action) {
    Connection conn = null;
    try {
      conn = ds.getConnection();
      conn.setAutoCommit(false);
      try {
        T result = action.apply(conn);
        conn.commit();
        return result;
      } catch (RuntimeException e) {
        conn.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new TitanDataException("Transaction failed: connection error", e);
    } catch (RuntimeException e) {
      throw e instanceof TitanDataException
          ? (TitanDataException) e
          : new TitanDataException("Transaction failed", e);
    } finally {
      if (conn != null) {
        try {
          conn.setAutoCommit(true);
          conn.close();
        } catch (SQLException ignored) {
          // best-effort cleanup
        }
      }
    }
  }

  /**
   * Wrap a connection so {@link Connection#close()} is a no-op. JDBI's {@code Handle} closes the
   * connection it was opened with; for borrowed/transactional connections that would steal the
   * connection from under the caller, so we hand JDBI a non-closing view instead.
   */
  @NonNull
  private static Connection nonClosing(@NonNull Connection delegate) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            new NonClosingConnectionHandler(delegate));
  }

  private record NonClosingConnectionHandler(Connection delegate) implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
        return null; // swallow — caller owns the real connection
      }
      try {
        return method.invoke(delegate, args);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }

  @NonNull
  public JobDao jobs() {
    return jobDao;
  }

  @NonNull
  public JobTriggerDao jobTriggers() {
    return jobTriggerDao;
  }

  @NonNull
  public BuildDao builds() {
    return buildDao;
  }

  /**
   * DAO for full-text search over {@code titan.logs} ({@code /api/v1/builds/search}, closes #1083).
   * Backed by the V34 generated {@code tsv} column + GIN index.
   */
  @NonNull
  public BuildSearchDao buildSearch() {
    return buildSearchDao;
  }

  @NonNull
  public TaskQueueDao taskQueue() {
    return taskQueueDao;
  }

  @NonNull
  public FlowNodeDao flowNodes() {
    return flowNodeDao;
  }

  @NonNull
  public AgentDao agents() {
    return agentDao;
  }

  /**
   * DAO for {@code titan.agent_events} — worker join/leave lifecycle log feeding the Home activity
   * timeline (closes #714).
   */
  @NonNull
  public AgentEventsDao agentEvents() {
    return agentEventsDao;
  }

  @NonNull
  public LogDao logs() {
    return logDao;
  }

  @NonNull
  public ArtifactDao artifacts() {
    return artifactDao;
  }

  @NonNull
  public DiscoverySourceDao discoverySources() {
    return discoverySourceDao;
  }

  @NonNull
  public DiscoveryEventDao discoveryEvents() {
    return discoveryEventDao;
  }

  /** DAO for {@code titan.timers} — the durable-timer substrate. */
  @NonNull
  public TimerDao timers() {
    return timerDao;
  }

  /** DAO for {@code titan.credentials} — the encrypted secrets store (closes #274). */
  @NonNull
  public CredentialDao credentials() {
    return credentialDao;
  }

  /**
   * DAO for {@code titan.discovery_state} — per-job SCM polling state recorded by the {@link
   * io.adaptiq.titan.discovery.DiscoveryService} (closes #275).
   */
  @NonNull
  public DiscoveryStateDao discoveryState() {
    return discoveryStateDao;
  }

  /**
   * DAO for {@code titan.test_result} — per-test-case results from the {@code junit} step (closes
   * #298, unblocks the #296 UI test-results panel and the upcoming {@code GET
   * /api/v1/builds/{id}/tests} endpoint).
   */
  @NonNull
  public TestResultDao testResults() {
    return testResultDao;
  }

  /**
   * DAO for Overview-page KPIs ({@code builds_today / success_rate / median_duration_ms}) — closes
   * #346. A single SQL pass over {@code titan.builds}.
   */
  @NonNull
  public StatsDao stats() {
    return statsDao;
  }

  /**
   * DAO for the Home "Top failing jobs" widget — {@code GET /api/v1/jobs/top-failing} (closes
   * #769). Aggregate over {@code titan.builds} grouped by job over a sliding window.
   */
  @NonNull
  public TopFailingJobDao topFailingJobs() {
    return topFailingJobDao;
  }

  /**
   * DAO for the per-job Stats page — {@code GET /api/v1/jobs/{id}/stats} (closes #775). Totals,
   * failure rate, p50/p95 duration, and daily buckets over a sliding window.
   */
  @NonNull
  public JobStatsDao jobStats() {
    return jobStatsDao;
  }

  /**
   * Per-job stage-timing percentiles + per-build samples (closes #1095). Powers the "Stage Timing —
   * last N builds" panel on the pipeline detail page.
   */
  @NonNull
  public JobTimingsDao jobTimings() {
    return jobTimingsDao;
  }

  /**
   * Per-job recent build-duration trend (closes #1096). Powers the inline "Duration trend (30d)"
   * sparkline on the /pipelines index.
   */
  @NonNull
  public DurationTrendDao durationTrend() {
    return durationTrendDao;
  }

  /**
   * DAO for the Overview-page activity feed ({@code GET /api/v1/activity}) — closes #304. Derives
   * activity from terminal {@code titan.builds} rows; no separate events table in v1.
   */
  @NonNull
  public ActivityDao activity() {
    return activityDao;
  }

  /**
   * DAO for {@code titan.personal_access_tokens} — per-user API tokens for headless / CLI / CI use
   * (closes #434).
   */
  @NonNull
  public PersonalAccessTokenDao personalAccessTokens() {
    return personalAccessTokenDao;
  }

  /**
   * DAO for {@code titan.audit_log} — queryable audit trail for high-risk actions (closes #478).
   */
  @NonNull
  public AuditLogDao auditLog() {
    return auditLogDao;
  }

  /**
   * DAO for {@code titan.audit_retention_policy} — per-event-kind audit-log retention horizons
   * applied by the nightly {@link io.adaptiq.titan.audit.RetentionJob} (closes #1104). Mutated via
   * the admin override surface {@code AuditPolicyHandler}.
   */
  @NonNull
  public AuditRetentionPolicyDao auditRetentionPolicy() {
    return auditRetentionPolicyDao;
  }

  /**
   * DAO for {@code titan.user_starred_jobs} — per-user pinned (favorited) jobs for the sidebar
   * 'Starred' section (closes #703).
   */
  @NonNull
  public StarredJobsDao starredJobs() {
    return starredJobsDao;
  }

  /**
   * DAO for {@code titan.approvals} — the durable side of the {@code approval:} parked-step gate
   * (#715). One row per in-flight human approval; the orchestrator inserts PENDING on park and the
   * decide endpoint / sweep flips terminal.
   */
  @NonNull
  public ApprovalsDao approvals() {
    return approvalsDao;
  }

  /**
   * DAO for {@code titan.github_app} — the singleton GitHub App row (#832). Sealed bytes never
   * leave the package boundary; only {@link io.adaptiq.titan.scm.github.GithubAppService} unwraps
   * them.
   */
  @NonNull
  public GithubAppDao githubApp() {
    return githubAppDao;
  }

  /** DAO for {@code titan.github_installations} — one row per org/user install (#832). */
  @NonNull
  public GithubInstallationDao githubInstallations() {
    return githubInstallationDao;
  }

  /** DAO for {@code titan.github_repositories} — repos visible to each installation (#832). */
  @NonNull
  public GithubRepositoryDao githubRepositories() {
    return githubRepositoryDao;
  }

  /**
   * DAO for {@code titan.github_pipelines_discovered} — one row per {@code .titan/pipelines/*.yml}
   * file the scanner walked (#833). The UI's "discovered pipelines" view consumes the rows; the
   * scanner replaces rows for a repo on every scan.
   */
  @NonNull
  public GithubPipelineDiscoveredDao githubPipelinesDiscovered() {
    return githubPipelineDiscoveredDao;
  }

  /**
   * DAO for {@code titan.user_roles} — flat per-user role assignments backing the v1 RBAC seam
   * (closes #1121). Read by {@link io.adaptiq.titan.auth.Authz} on every {@code requires(...)}
   * call.
   */
  @NonNull
  public UserRolesDao userRoles() {
    return userRolesDao;
  }

  /**
   * DAO for {@code titan.group_role_mapping} — per-org SSO group → Titan role mapping (closes
   * #1136). Read by {@link io.adaptiq.titan.auth.GroupRoleResolver} on OIDC login; mutated via the
   * org-admin REST surface {@code SsoMappingApi}.
   */
  @NonNull
  public GroupRoleMappingDao groupRoleMapping() {
    return groupRoleMappingDao;
  }

  /**
   * DAO for {@code titan.rbac_user_role} — scoped per-user role assignments backing the {@link
   * io.adaptiq.titan.auth.ScopedAuthz} per-action permission check (closes #1131, epic #1114).
   */
  @NonNull
  public RbacUserRoleDao rbacUserRoles() {
    return rbacUserRoleDao;
  }

  /**
   * DAO for {@code titan.rbac_audit} — typed audit trail for every {@link
   * io.adaptiq.titan.auth.RequiresRole @RequiresRole}-gated call (closes #1131, epic #1114). One
   * row per check (allow OR deny). Insert path is best-effort — see {@link
   * io.adaptiq.titan.auth.ScopedAuthz#recordRbacAudit}.
   */
  @NonNull
  public RbacAuditDao rbacAudit() {
    return rbacAuditDao;
  }

  /**
   * DAO for {@code titan.scm_webhook_event} — durable SCM webhook ingestion log with {@code
   * (provider, delivery_id)} dedupe + timer-driven retry (closes #1129). Webhook endpoints insert
   * PENDING rows on entry and transition to PROCESSED / FAILED via this DAO; the {@link
   * io.adaptiq.titan.scm.webhook.WebhookRetryService} sweeper polls {@link
   * ScmWebhookEventDao#findDuePending} for missed/dropped deliveries.
   */
  @NonNull
  public ScmWebhookEventDao scmWebhookEvents() {
    return scmWebhookEventDao;
  }

  /**
   * DAO for {@code titan.pulsar_sources} — registered Pulsar SCM node connections (#1283). Written
   * by the {@code PulsarSourcesApi} admin endpoint; the UI Integrations card reads the list +
   * drives per-source re-probes through it.
   */
  @NonNull
  public PulsarSourceDao pulsarSources() {
    return pulsarSourceDao;
  }

  /**
   * DAO for {@code titan.scm_event_seen} — the dispatch-side idempotency boundary (#1118) shared by
   * the webhook hot-path and the reconcile/poll replay path. Backs {@code JdbiEventDedupeStore}.
   */
  @NonNull
  public ScmEventSeenDao scmEventSeen() {
    return scmEventSeenDao;
  }
}
