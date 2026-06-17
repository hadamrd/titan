package io.adaptiq.titan.scm.github;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time compare helpers for webhook-signature verification (#832, design/63).
 *
 * <p>{@link MessageDigest#isEqual(byte[], byte[])} is the JDK-provided constant-time array compare;
 * this class wraps it with String overloads so callers don't have to remember the primitive. The
 * CONSTITUTION mandates constant-time compare for any secret / signature work — a {@code .equals()}
 * on signature strings short-circuits on the first differing byte and leaks the prefix via timing.
 *
 * <p>Child C (#834) uses this when verifying the {@code X-Hub-Signature-256} header on incoming
 * webhook deliveries. This file ships in Child A so the primitive is in tree the moment any
 * downstream PR needs it, and so the adversarial test that "never use a naive equals" can lock the
 * contract.
 */
public final class GithubAppWebhookSecretComparator {

  private GithubAppWebhookSecretComparator() {}

  /** Constant-time UTF-8 byte compare of two strings. */
  public static boolean constantTimeEquals(@NonNull String a, @NonNull String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
