package io.adaptiq.titan.credentials;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Boot-time fail-closed guard for {@link DevAutoKeyProvider} (issue #930, V1 bar #5 audit row 1).
 *
 * <p><strong>Why this exists.</strong> {@link DevAutoKeyProvider} is gated by {@code
 * TITAN_CREDENTIALS_DEV_AUTO_KEK} (env) / {@code titan.credentials.dev-auto-kek} (sysprop) and is
 * inert by default. That gate is the load-bearing safety property: a prod rig without a real KMS
 * provider falls through to {@code DbEnvelopeBackend.activeKek()}'s fail-closed guard. The
 * remaining footgun is an SRE who accidentally exports the flag in a prod manifest — the local-rig
 * env-var leaks into a k8s ConfigMap, the chart's {@code values.yaml} default flips for testing and
 * never gets reverted, etc. With the flag on, the provider would silently mint a host-local KEK,
 * persist it to local disk, and start sealing real production secrets under a key that has no
 * KMS-backed escrow.
 *
 * <p>This observer refuses to boot in a prod launch (no dev profile active) when the flag is on,
 * with an error message that names the offending env var so an SRE knows what to unset.
 *
 * <p><strong>Policy by profile.</strong>
 *
 * <ul>
 *   <li>{@code prod} (no dev profile active): refuses to start when the flag is on — throws {@link
 *       IllegalStateException} from the {@link StartupEvent} observer, aborting Quarkus bootstrap.
 *   <li>{@code dev} / {@code test}: permits the flag (this is the local-rig path) — no change to
 *       {@link DevAutoKeyProvider}'s existing WARN-banner behaviour.
 * </ul>
 *
 * <p>Canonical fail-closed boot-guard shape: a {@link StartupEvent} observer that throws and aborts
 * Quarkus boot with a clear pointer at the offending config key.
 */
@ApplicationScoped
public final class DevAutoKekBootCheck {

  private static final Logger LOGGER = Logger.getLogger(DevAutoKekBootCheck.class.getName());

  private final boolean flagEnabled;
  private final boolean devProfile;

  DevAutoKekBootCheck() {
    this(readFlagEnabled(), DevAutoKeyProvider.isDevProfile());
  }

  /**
   * Test-only constructor — accepts an explicit dev-profile verdict + flag so unit tests can pin
   * both. {@code devProfile} mirrors {@link DevAutoKeyProvider#isDevProfile()} (true for dev/test,
   * false for a prod launch).
   */
  DevAutoKekBootCheck(boolean flagEnabled, boolean devProfile) {
    this.flagEnabled = flagEnabled;
    this.devProfile = devProfile;
  }

  void onStart(@Observes StartupEvent event) {
    check();
  }

  /** Package-private for unit testing. */
  void check() {
    if (!flagEnabled) {
      return;
    }
    if (!devProfile) {
      // No dev profile active — treat as a prod launch. Abort boot rather than silently mint a
      // host-local KEK that has no KMS escrow, sealing prod credentials under a key that vanishes
      // with the pod's local disk. Profile is resolved the same way DevAutoKeyProvider gates the
      // provider itself (TITAN_PROFILE / titan.profile / quarkus.profile / launch mode), so the
      // boot guard and the provider agree on what "dev" means.
      throw new IllegalStateException(
          "DevAutoKeyProvider is enabled ("
              + DevAutoKeyProvider.FLAG_ENV
              + "=true) but no dev profile is active (TITAN_PROFILE is not 'dev') — refusing to"
              + " start. This looks like a prod launch. The dev-auto KEK is a local-rig convenience"
              + " that mints a host-local AES key with no KMS escrow; in production it would"
              + " silently brick credential rows on pod restart. For the local rig set"
              + " TITAN_PROFILE=dev; for production unset "
              + DevAutoKeyProvider.FLAG_ENV
              + " and configure a KMS-backed provider (e.g. titan-keyprovider-infisical) instead.");
    }
    LOGGER.info(
        "[titan][dev-auto-kek] flag is ON in a dev profile — DEV ONLY, do not promote this build"
            + " to production");
  }

  private static boolean readFlagEnabled() {
    String env = System.getenv(DevAutoKeyProvider.FLAG_ENV);
    if (env != null && !env.isBlank()) {
      return "true".equals(env.trim().toLowerCase(Locale.ROOT));
    }
    String sys = System.getProperty(DevAutoKeyProvider.FLAG_SYSPROP);
    return sys != null && "true".equals(sys.trim().toLowerCase(Locale.ROOT));
  }
}
