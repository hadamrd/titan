package io.adaptiq.titan.scm.github;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.EnvelopeCipher;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import io.adaptiq.titan.store.GithubAppDao;
import io.adaptiq.titan.store.GithubInstallationDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.GithubAppRow;
import io.adaptiq.titan.store.rows.GithubInstallationRow;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAppInstallationToken;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHRepositorySelection;
import org.kohsuke.github.GHTargetType;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;

/**
 * Persistence + auth glue for the GitHub App golden-path (#832, design/63). Reworked in #874 to
 * delegate every GitHub round-trip to {@code org.kohsuke:github-api} — see {@link
 * GithubClientFactory}. The DB persistence + envelope-encryption contract is unchanged.
 *
 * <h2>What this service does</h2>
 *
 * <ul>
 *   <li>Stores the App's id, name, slug, html_url, PEM, webhook_secret on first manifest callback.
 *       The PEM and webhook secret are envelope-encrypted via {@link EnvelopeCipher} so the at-rest
 *       contract matches {@code titan.credentials} (PR #333, CONSTITUTION §6).
 *   <li>Replays of the manifest-callback update the singleton row in place — no duplicate row.
 *   <li>Issues installation tokens via the kohsuke {@link GHAppInstallation#createToken()} call,
 *       caches them in-process (key = install id) with a 60-min TTL, and refreshes on the 50-minute
 *       mark so callers never see a fresh-token round-trip in the hot path.
 * </ul>
 *
 * <h2>What this service does NOT do</h2>
 *
 * <ul>
 *   <li>It does not cache PEM plaintext or webhook-secret plaintext — every App-client construction
 *       re-unseals the PEM and discards it. CONSTITUTION §6 cache-ban contract.
 *   <li>It does not own webhook signature verification — that lives in {@code GithubAppWebhookApi}
 *       (kohsuke does not ship an HMAC verifier; we keep ours, with {@link
 *       GithubAppWebhookSecretComparator} for the constant-time compare).
 * </ul>
 */
public class GithubAppService {

  private static final Logger LOGGER = Logger.getLogger(GithubAppService.class.getName());

  /** Hard TTL we trust GitHub's installation tokens for (matches their 60-min stamp). */
  static final Duration HARD_TTL = Duration.ofMinutes(60);

  /** Refresh threshold — at 50min of age we re-mint, leaving 10min of safe runway. */
  static final Duration REFRESH_AT = Duration.ofMinutes(50);

  private final TitanStores stores;
  private final CredentialKeyProvider keyProvider;
  private final GithubClientFactory clientFactory;
  private final Clock clock;

  /**
   * In-process installation-token cache. Keyed by install id; value is the most recently issued
   * token + its issuance timestamp. Cache lookup is lock-free; {@code compute} guarantees one
   * concurrent refresh per install id under contention.
   */
  private final ConcurrentHashMap<Long, CachedInstallationToken> tokenCache =
      new ConcurrentHashMap<>();

  public GithubAppService(
      @NonNull TitanStores stores,
      @NonNull CredentialKeyProvider keyProvider,
      @NonNull GithubClientFactory clientFactory) {
    this(stores, keyProvider, clientFactory, Clock.systemUTC());
  }

  public GithubAppService(
      @NonNull TitanStores stores,
      @NonNull CredentialKeyProvider keyProvider,
      @NonNull GithubClientFactory clientFactory,
      @NonNull Clock clock) {
    this.stores = stores;
    this.keyProvider = keyProvider;
    this.clientFactory = clientFactory;
    this.clock = clock;
  }

  // ── manifest callback ─────────────────────────────────────────────────────

  /**
   * Exchange a manifest temp code for the App's credentials and persist the singleton row. Safe to
   * call twice with the same code — but GitHub will reject the second call with HTTP 422 since the
   * code is single-use. Safe to call twice with two distinct codes (e.g. an admin who re-ran the
   * manifest flow): the {@code id = 1} row is updated in place.
   *
   * @return the persisted {@link GithubAppRow}.
   * @throws GithubApiException if the code exchange fails (HTTP 422 = invalid / already used).
   */
  @NonNull
  public GithubAppRow handleManifestCallback(@NonNull String code) throws GithubApiException {
    // #873: use our own DTO + tolerant Jackson mapper so new fields GitHub adds (client_id,
    // client_secret, …) don't fail the exchange. Replaces the kohsuke createAppFromManifest call
    // which used the library's hand-rolled DTO with FAIL_ON_UNKNOWN_PROPERTIES on.
    GithubManifestCallback manifest = clientFactory.exchangeManifestCode(code);

    long appId = manifest.id;
    String pem = manifest.pem;
    String webhookSecret = manifest.webhookSecret;
    if (pem == null || webhookSecret == null) {
      throw new GithubApiException("manifest-callback response missing pem or webhook_secret", -1);
    }

    byte[] kek = activeKek();
    int kekVersion = keyProvider.credentialKeyVersion();

    // Envelope-encrypt PEM and webhook secret. The singleton row has id = 1, so we use it
    // directly as the credentialId for the EnvelopeCipher AAD binding.
    EnvelopeCipher.Sealed pemSealed =
        EnvelopeCipher.seal(pem, kek, kekVersion, "github_app:pem", 1L);
    EnvelopeCipher.Sealed wsSealed =
        EnvelopeCipher.seal(webhookSecret, kek, kekVersion, "github_app:webhook_secret", 1L);

    GithubAppRow row = new GithubAppRow();
    row.id = 1;
    row.appId = appId;
    row.name = manifest.name;
    row.slug = manifest.slug;
    row.htmlUrl = manifest.htmlUrl;
    row.pemSealedValue = pemSealed.sealedValue();
    row.pemWrappedDek = pemSealed.wrappedDek();
    row.pemKekVersion = pemSealed.kekVersion();
    row.webhookSecretSealedValue = wsSealed.sealedValue();
    row.webhookSecretWrappedDek = wsSealed.wrappedDek();
    row.webhookSecretKekVersion = wsSealed.kekVersion();

    GithubAppDao dao = stores.githubApp();
    if (dao.count() == 0) {
      dao.insert(row);
    } else {
      dao.update(row);
      // Invalidate any cached install tokens — the App identity has changed, so previously
      // minted JWTs are no longer valid for the new key.
      tokenCache.clear();
    }
    LOGGER.log(
        Level.INFO,
        "[titan] github_app registered/updated: appId={0} slug={1}",
        new Object[] {appId, row.slug});
    return loadRowOrThrow();
  }

  // ── read access ───────────────────────────────────────────────────────────

  /** Return the registered App row, or empty if no manifest callback has been handled yet. */
  @NonNull
  public Optional<GithubAppRow> findApp() {
    return stores.githubApp().findSingleton();
  }

  // ── installation lifecycle ────────────────────────────────────────────────

  /**
   * Sync the {@code titan.github_installations} table with what the App actually sees on GitHub.
   * Idempotent — runs on demand from the {@code POST .../sync} endpoint and (eventually) on {@code
   * installation} webhook events.
   */
  @NonNull
  public List<GithubInstallationRow> syncInstallations() {
    GithubAppRow appRow = loadRowOrThrow();
    GithubInstallationDao dao = stores.githubInstallations();
    try {
      GitHub appClient = appClient(appRow);
      GHApp app = appClient.getApp();
      for (GHAppInstallation install : app.listInstallations()) {
        long installId = install.getId();
        GHUser account = install.getAccount();
        String accountLogin = account == null ? "" : nullToEmpty(account.getLogin());
        String accountType = account == null ? "" : nullToEmpty(account.getType());
        String targetType = install.getTargetType() == null ? "" : install.getTargetType().name();
        Instant suspendedAt =
            install.getSuspendedAt() == null ? null : install.getSuspendedAt().toInstant();
        if (dao.findByInstallId(installId).isPresent()) {
          dao.update(installId, accountLogin, accountType, targetType, suspendedAt);
        } else {
          dao.insert(installId, accountLogin, accountType, targetType, suspendedAt);
        }
      }
    } catch (HttpException e) {
      throw new GithubApiException(
          "list-installations failed: HTTP " + e.getResponseCode() + ": " + e.getMessage(),
          e.getResponseCode(),
          e);
    } catch (IOException e) {
      throw new GithubApiException("list-installations I/O error: " + e.getMessage(), -1, e);
    }
    return dao.listAll();
  }

  /** Read-only listing of installations as currently persisted. */
  @NonNull
  public List<GithubInstallationRow> listInstallations() {
    return stores.githubInstallations().listAll();
  }

  /**
   * Refresh the {@code titan.github_repositories} table for one installation. Pulls the live list
   * via the installation token and rewrites the rows for that install (delete-then-insert).
   *
   * @throws GithubApiException if the installation no longer exists on GitHub or the token mint
   *     fails.
   */
  public int syncRepositoriesForInstall(long installId) {
    String token = getInstallationToken(installId);
    int count = 0;
    try {
      GitHub installClient = clientFactory.asInstallation(token);
      List<GHRepository> repos = new ArrayList<>();
      // kohsuke surfaces installation repositories via GHAppInstallation.listRepositories(), but
      // when authenticated as the installation we use the simpler `installation/repositories` —
      // wrapped by GitHub#getInstallation() and listRepositories on it.
      for (GHRepository repo :
          installClient.getInstallation().listRepositories().withPageSize(100)) {
        repos.add(repo);
      }
      stores.githubRepositories().deleteByInstall(installId);
      for (GHRepository repo : repos) {
        String owner =
            repo.getOwnerName() != null
                ? repo.getOwnerName()
                : (repo.getOwner() == null ? "" : repo.getOwner().getLogin());
        stores
            .githubRepositories()
            .insert(
                installId,
                repo.getId(),
                owner,
                repo.getName(),
                repo.getDefaultBranch(),
                repo.isPrivate());
        count++;
      }
    } catch (HttpException e) {
      throw new GithubApiException(
          "list-installation-repositories failed: HTTP "
              + e.getResponseCode()
              + ": "
              + e.getMessage(),
          e.getResponseCode(),
          e);
    } catch (IOException e) {
      throw new GithubApiException(
          "list-installation-repositories I/O error: " + e.getMessage(), -1, e);
    }
    return count;
  }

  // ── installation token (cached) ───────────────────────────────────────────

  /**
   * Return a valid installation access token for {@code installId}, minting one via the GitHub API
   * only if no cached token exists or the cached token is past the refresh threshold.
   *
   * <p>Thread-safe: {@code compute} on the cache holds a per-key lock, so two concurrent callers
   * for the same install id will mint exactly one token between them.
   */
  @NonNull
  public String getInstallationToken(long installId) {
    Instant now = Instant.now(clock);
    CachedInstallationToken cached = tokenCache.get(installId);
    if (cached != null && !cached.isStale(now)) {
      return cached.token();
    }
    // Slow path: refresh under per-key lock.
    return tokenCache
        .compute(
            installId,
            (id, existing) -> {
              if (existing != null && !existing.isStale(Instant.now(clock))) {
                return existing; // another thread refreshed while we were waiting
              }
              String minted = mintInstallationToken(id);
              return new CachedInstallationToken(minted, Instant.now(clock));
            })
        .token();
  }

  @NonNull
  private String mintInstallationToken(long installId) {
    GithubAppRow appRow = loadRowOrThrow();
    try {
      GitHub appClient = appClient(appRow);
      GHAppInstallation install = appClient.getApp().getInstallationById(installId);
      GHAppInstallationToken token = install.createToken().create();
      return token.getToken();
    } catch (HttpException e) {
      throw new GithubApiException(
          "install-token exchange failed: HTTP " + e.getResponseCode() + ": " + e.getMessage(),
          e.getResponseCode(),
          e);
    } catch (GHFileNotFoundException e) {
      // 404 from getInstallationById — install id has been revoked or never existed.
      throw new GithubApiException(
          "install-token exchange failed: installation " + installId + " not found", 404, e);
    } catch (IOException e) {
      throw new GithubApiException("install-token I/O error: " + e.getMessage(), -1, e);
    }
  }

  /** Force-evict a cached install token — used by the webhook handler on {@code unsuspended}. */
  public void invalidateInstallationToken(long installId) {
    tokenCache.remove(installId);
  }

  // ── client builders ───────────────────────────────────────────────────────

  /**
   * Build an App-as-itself kohsuke client. The PEM is unsealed inside this method, handed to
   * kohsuke's {@link org.kohsuke.github.extras.authorization.JWTTokenProvider} (which parses it to
   * a {@link java.security.PrivateKey} once), and the String reference exits scope. The JWT itself
   * is signed by the provider on demand and re-signed on expiry.
   */
  @NonNull
  GitHub appClient(@NonNull GithubAppRow row) throws IOException {
    String pem = unsealPem(row);
    return clientFactory.asApp(row.appId, pem);
  }

  /**
   * Build an installation-scoped kohsuke client — exposed to {@link GithubRepoScanner} so it can
   * walk a repository's {@code .titan/pipelines/} directory through the same auth path used
   * everywhere else.
   */
  @NonNull
  public GitHub installationClient(long installId) throws IOException {
    return clientFactory.asInstallation(getInstallationToken(installId));
  }

  @NonNull
  private GithubAppRow loadRowOrThrow() {
    return stores
        .githubApp()
        .findSingleton()
        .orElseThrow(() -> new IllegalStateException("no GitHub App registered for this tenant"));
  }

  @NonNull
  private String unsealPem(@NonNull GithubAppRow row) {
    return EnvelopeCipher.unseal(
        row.pemSealedValue,
        row.pemWrappedDek,
        kekForVersion(row.pemKekVersion),
        row.pemKekVersion,
        "github_app:pem",
        1L);
  }

  /**
   * Unseal the webhook secret — called per request by {@link
   * io.adaptiq.titan.api.GithubAppWebhookApi}. Constant-time compare against the {@code
   * X-Hub-Signature-256} HMAC is the caller's responsibility — see {@link
   * GithubAppWebhookSecretComparator}.
   */
  @NonNull
  public String unsealWebhookSecret() {
    GithubAppRow row = loadRowOrThrow();
    return EnvelopeCipher.unseal(
        row.webhookSecretSealedValue,
        row.webhookSecretWrappedDek,
        kekForVersion(row.webhookSecretKekVersion),
        row.webhookSecretKekVersion,
        "github_app:webhook_secret",
        1L);
  }

  /**
   * Resolve the KEK bytes for a specific version, falling back to the active KEK when the provider
   * does not version its keys (returns null for {@code credentialKeyByVersion}).
   */
  @NonNull
  private byte[] kekForVersion(int version) {
    byte[] keyed = keyProvider.credentialKeyByVersion(version);
    return keyed != null ? keyed : activeKek();
  }

  @NonNull
  private byte[] activeKek() {
    byte[] k = keyProvider.credentialKey();
    if (k == null) {
      throw new SecretCipher.CipherException(
          "no credential key configured — GithubAppService fails closed rather than persist or "
              + "decrypt secrets in plaintext");
    }
    return k;
  }

  @NonNull
  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  // Suppress IntelliJ unused-warning on the public enums we surface in javadoc.
  @SuppressWarnings("unused")
  private static final Class<?>[] KOHSUKE_PUBLIC = {
    GHTargetType.class, GHRepositorySelection.class
  };

  /** Cached install-token record. {@code issuedAt} stamps the moment Titan minted it. */
  record CachedInstallationToken(@NonNull String token, @NonNull Instant issuedAt) {
    boolean isStale(@NonNull Instant now) {
      Duration age = Duration.between(issuedAt, now);
      return age.compareTo(REFRESH_AT) >= 0;
    }
  }
}
