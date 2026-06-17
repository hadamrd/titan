package io.adaptiq.titan.notifications;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Daily failing-builds email digest cron (closes #1103).
 *
 * <p>Why a digest and not live alerts: on-call wants the morning summary, Slack already covers
 * real-time alerting (#387). The digest is the adoption path for shops that don't run Slack — and
 * it is configurable per workspace so a shop can turn it off without disabling the rest of the
 * notification system.
 *
 * <p>Wiring:
 *
 * <ol>
 *   <li>The cron expression defaults to {@code 0 0 8 * * ?} (08:00 UTC daily) matching the spec's
 *       {@code hour_utc: 8}. Override via {@code titan.notifications.email.digest.cron}. Set the
 *       property to an empty string to disable the cron entirely (the {@code disabled} wildcard).
 *   <li>{@link #runOnce(Instant)} contains the actual logic and is invoked by both the cron and any
 *       code path that wants a manual fire (tests, future REST endpoint).
 *   <li>The send is suppressed when there were no failures in the window — the spec's
 *       no-noise-when-nothing-broke contract.
 *   <li>SMTP credentials are pulled from {@link CredentialsService} just before the send. The
 *       plaintext format is {@code "username:password"}; no separator means anonymous SMTP, which a
 *       dev / in-memory test server accepts.
 * </ol>
 *
 * <p>This bean is non-reentrant: a long send pass cannot overlap a cron tick. Quarkus {@code
 * Scheduled.ConcurrentExecution.SKIP} handles the cron path; an {@link AtomicBoolean} guard covers
 * any programmatic invocation.
 */
@ApplicationScoped
public class DailyDigestJob {

  private static final Logger LOG = Logger.getLogger(DailyDigestJob.class.getName());

  /** The window the digest covers — 24h is the contract. Centralised so tests can read it. */
  static final Duration WINDOW = Duration.ofHours(24);

  private final TitanStores stores;
  private final CredentialsService credentials;
  private final SmtpSender sender;
  private final DigestBuilder digestBuilder;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @ConfigProperty(name = "titan.notifications.email.digest.enabled", defaultValue = "false")
  boolean enabled;

  // Optional<String>, NOT String defaultValue="" — Quarkus/SmallRye does not apply an empty-string
  // @ConfigProperty default to a String injection, so an absent value crashes startup with
  // "Failed to load config value". Optional handles absence cleanly.
  @ConfigProperty(name = "titan.notifications.email.digest.recipients")
  Optional<String> recipientsCsv;

  @ConfigProperty(name = "titan.notifications.email.digest.from", defaultValue = "titan@localhost")
  String fromAddress;

  @ConfigProperty(name = "titan.notifications.email.digest.smtp.host", defaultValue = "localhost")
  String smtpHost;

  @ConfigProperty(name = "titan.notifications.email.digest.smtp.port", defaultValue = "25")
  int smtpPort;

  /**
   * Optional credential coordinates. Both must be supplied for AUTH PLAIN to fire; if either is
   * blank the sender omits the AUTH step (dev / in-memory test).
   */
  @ConfigProperty(name = "titan.notifications.email.digest.credential.scope")
  Optional<String> credentialScope;

  @ConfigProperty(name = "titan.notifications.email.digest.credential.key")
  Optional<String> credentialKey;

  public DailyDigestJob(
      TitanStores stores,
      CredentialsService credentials,
      SmtpSender sender,
      DigestBuilder digestBuilder) {
    this.stores = stores;
    this.credentials = credentials;
    this.sender = sender;
    this.digestBuilder = digestBuilder;
  }

  @Scheduled(
      cron = "{titan.notifications.email.digest.cron:0 0 8 * * ?}",
      identity = "titan-daily-digest",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  public void scheduledTick() {
    try {
      runOnce(Instant.now());
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "[digest] scheduled tick failed", e);
    }
  }

  /**
   * The cron body — package-private so tests can drive it directly without waiting on the
   * scheduler.
   *
   * @return {@code true} if an email was actually dispatched, {@code false} if the run was a no-op
   *     (disabled / no recipients / no failures / config invalid).
   */
  boolean runOnce(@NonNull Instant now) {
    if (!running.compareAndSet(false, true)) {
      LOG.fine("[digest] previous run still active; skipping");
      return false;
    }
    try {
      DailyDigestConfig cfg = resolveConfig();
      if (cfg == null || !cfg.isActionable()) {
        LOG.fine("[digest] not actionable (disabled or no recipients) — skipping");
        return false;
      }

      Instant windowStart = now.minus(WINDOW);
      List<BuildRow> builds = stores.builds().findFinishedSince(windowStart);
      Digest digest = digestBuilder.build(windowStart, now, builds, jobsFor(builds));

      if (digest.isEmpty()) {
        LOG.info(
            "[digest] zero failures in window — skipping send (recipients="
                + cfg.recipients().size()
                + ")");
        return false;
      }

      String[] creds = resolveSmtpCredential(cfg);
      try {
        sender.send(
            cfg.smtpHost(),
            cfg.smtpPort(),
            creds[0],
            creds[1],
            cfg.fromAddress(),
            cfg.recipients(),
            digestBuilder.renderSubject(digest),
            digestBuilder.renderHtml(digest));
        LOG.info(
            "[digest] sent — failures="
                + digest.totalFailures()
                + " jobs="
                + digest.failingJobs().size()
                + " recipients="
                + cfg.recipients().size());
        return true;
      } catch (SmtpSender.SmtpException e) {
        LOG.log(Level.SEVERE, "[digest] SMTP send failed", e);
        return false;
      }
    } finally {
      running.set(false);
    }
  }

  /** Visible for tests so the empty-window contract is testable without faking config injection. */
  Optional<DailyDigestConfig> currentConfig() {
    return Optional.ofNullable(resolveConfig());
  }

  private DailyDigestConfig resolveConfig() {
    List<String> recipients = parseRecipients(recipientsCsv.orElse(""));
    try {
      return new DailyDigestConfig(
          enabled,
          recipients,
          8, // documented; cron carries the actual schedule, this is metadata for the digest
          smtpHost,
          smtpPort,
          fromAddress,
          credentialScope.filter(s -> !s.isBlank()).orElse(null),
          credentialKey.filter(s -> !s.isBlank()).orElse(null));
    } catch (IllegalArgumentException e) {
      LOG.log(Level.WARNING, "[digest] config invalid — disabling: " + e.getMessage());
      return null;
    }
  }

  private List<JobRow> jobsFor(List<BuildRow> builds) {
    if (builds.isEmpty()) {
      return List.of();
    }
    Set<Long> jobIds = new HashSet<>();
    for (BuildRow b : builds) {
      jobIds.add(b.jobId);
    }
    List<JobRow> out = new java.util.ArrayList<>(jobIds.size());
    for (Long id : jobIds) {
      stores.jobs().findById(id).ifPresent(out::add);
    }
    return out;
  }

  private String[] resolveSmtpCredential(DailyDigestConfig cfg) {
    if (cfg.credentialScope() == null || cfg.credentialKey() == null) {
      return new String[] {null, null};
    }
    Optional<String> plaintext =
        credentials.resolvePlaintext(cfg.credentialScope(), cfg.credentialKey());
    if (plaintext.isEmpty()) {
      LOG.warning(
          "[digest] credential "
              + cfg.credentialScope()
              + "/"
              + cfg.credentialKey()
              + " not found — sending without AUTH");
      return new String[] {null, null};
    }
    String raw = plaintext.get();
    int sep = raw.indexOf(':');
    if (sep < 0) {
      return new String[] {null, null};
    }
    return new String[] {raw.substring(0, sep), raw.substring(sep + 1)};
  }

  static List<String> parseRecipients(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    String[] parts = csv.split("[,;\\s]+");
    List<String> out = new java.util.ArrayList<>(parts.length);
    for (String p : parts) {
      String trimmed = p.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }
}
