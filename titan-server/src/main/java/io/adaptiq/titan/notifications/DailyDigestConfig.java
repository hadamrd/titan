package io.adaptiq.titan.notifications;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Workspace-level configuration for the daily failing-builds email digest (issue #1103).
 *
 * <p>Mirrors the YAML shape from the spec's acceptance criteria:
 *
 * <pre>{@code
 * notifications:
 *   email:
 *     daily_digest:
 *       recipients: ["sre-oncall@example.com"]
 *       hour_utc: 8
 * }</pre>
 *
 * <p>Surfaced to {@link DailyDigestJob} via Quarkus {@code @ConfigProperty} bindings — see that
 * class for the property keys. This record is deliberately a small immutable carrier so the digest
 * job has a single value to pass around, and tests can build one in two lines without a CDI
 * container.
 *
 * <p>The SMTP credential is fetched via {@code CredentialsService} keyed by {@code (scope, key)};
 * the plaintext returned is interpreted as {@code "username:password"} (the same shape Slack and
 * GitHub Personal Access Token credentials use). {@code null} credential coordinates mean the SMTP
 * server accepts unauthenticated submissions (in-memory test SMTP, dev rigs).
 */
public record DailyDigestConfig(
    boolean enabled,
    @NonNull List<String> recipients,
    int hourUtc,
    @NonNull String smtpHost,
    int smtpPort,
    @NonNull String fromAddress,
    @Nullable String credentialScope,
    @Nullable String credentialKey) {

  public DailyDigestConfig {
    if (recipients == null) {
      throw new IllegalArgumentException("recipients must not be null");
    }
    recipients = List.copyOf(recipients);
    if (hourUtc < 0 || hourUtc > 23) {
      throw new IllegalArgumentException("hour_utc must be in [0, 23] — got " + hourUtc);
    }
    if (smtpHost == null || smtpHost.isBlank()) {
      throw new IllegalArgumentException("smtp host must not be blank");
    }
    if (smtpPort <= 0 || smtpPort > 65535) {
      throw new IllegalArgumentException("smtp port out of range — got " + smtpPort);
    }
    if (fromAddress == null || fromAddress.isBlank()) {
      throw new IllegalArgumentException("from address must not be blank");
    }
  }

  /** Convenience: nothing to do if disabled OR there are no recipients. */
  public boolean isActionable() {
    return enabled && !recipients.isEmpty();
  }
}
