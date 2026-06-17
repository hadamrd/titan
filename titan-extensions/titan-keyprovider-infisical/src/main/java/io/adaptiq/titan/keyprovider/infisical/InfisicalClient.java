package io.adaptiq.titan.keyprovider.infisical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The shared <a href="https://infisical.com">Infisical</a> REST client — one Infisical integration,
 * two callers (design/40 §3).
 *
 * <p>This class is the generalisation of the HTTP code originally written for {@link
 * InfisicalCredentialKeyProvider}: it fetches <em>any named secret</em> from an Infisical project,
 * not just the credential-sealing key. Two providers wrap it:
 *
 * <ul>
 *   <li>{@link InfisicalCredentialKeyProvider} — fetches the one secret holding the AES-256
 *       credential-sealing key (design/39 §3.1), and decodes it.
 *   <li>{@link InfisicalSecretProvider} — fetches an arbitrary synthesis-time secret a {@code
 *       library(..., credential: 'name')} call names (design/40 §3).
 * </ul>
 *
 * <p>Holding the Infisical HTTP details in one place means the two integrations cannot drift: the
 * same {@code /api/v3/secrets/raw/{name}} endpoint, the same {@code Bearer} auth, the same
 * project/environment/path coordinates, the same timeouts.
 *
 * <p><strong>Configuration</strong> — environment variables, the same set the key provider has
 * always read (the worker and the controller are JVMs, not a place for a config file):
 *
 * <table>
 *   <caption>Infisical client configuration</caption>
 *   <tr><td>{@code INFISICAL_TOKEN}</td><td>the API token; or…</td></tr>
 *   <tr><td>{@code INFISICAL_TOKEN_FILE}</td><td>a file holding the token (a mounted secret)</td></tr>
 *   <tr><td>{@code INFISICAL_PROJECT_ID}</td><td>the Infisical project (workspace) id</td></tr>
 *   <tr><td>{@code INFISICAL_ENV}</td><td>environment slug — default {@code prod}</td></tr>
 *   <tr><td>{@code INFISICAL_SECRET_PATH}</td><td>secret path — default {@code /}</td></tr>
 *   <tr><td>{@code INFISICAL_API_URL}</td><td>API base — default {@code https://app.infisical.com}</td></tr>
 * </table>
 *
 * <p>The token is least-privilege — a scoped, read-only Infisical token, the standard CI-runner
 * posture (design/40 §3). It is never logged: {@link #describe()} reports only project / env /
 * path.
 */
final class InfisicalClient {

  private static final Logger LOGGER = Logger.getLogger(InfisicalClient.class.getName());

  static final String ENV_TOKEN = "INFISICAL_TOKEN";
  static final String ENV_TOKEN_FILE = "INFISICAL_TOKEN_FILE";
  static final String ENV_PROJECT = "INFISICAL_PROJECT_ID";
  static final String ENV_ENVIRONMENT = "INFISICAL_ENV";
  static final String ENV_SECRET_PATH = "INFISICAL_SECRET_PATH";
  static final String ENV_API_URL = "INFISICAL_API_URL";

  private static final String DEFAULT_API_URL = "https://app.infisical.com";
  private static final String DEFAULT_ENVIRONMENT = "prod";
  private static final String DEFAULT_SECRET_PATH = "/";

  private static final ObjectMapper JSON = new ObjectMapper();

  @NonNull private final Settings settings;

  @NonNull private final HttpClient http;

  private InfisicalClient(@NonNull Settings settings) {
    this.settings = settings;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * Build a client from the process environment, or {@code null} when Infisical is not configured
   * (no token, or no project id) — the signal a provider uses to stay inert so {@code active()}
   * falls through to its built-in default.
   */
  @Nullable
  static InfisicalClient fromEnvironment() {
    Settings settings = Settings.fromEnvironment();
    return settings == null ? null : new InfisicalClient(settings);
  }

  /** Build a client from explicit settings — the test / embedding entry point. */
  @NonNull
  static InfisicalClient of(@NonNull Settings settings) {
    return new InfisicalClient(settings);
  }

  /**
   * Fetch one secret by name from the configured Infisical project / environment / path.
   *
   * @param secretName the secret's name in Infisical
   * @return the secret's value, or {@code null} on any failure (a non-200, a missing value, an I/O
   *     error) — every failure is logged (without the token or the value) and the caller decides
   *     whether {@code null} means fall-through (the key provider) or fail-closed (the secret
   *     provider).
   */
  @Nullable
  String fetchSecret(@NonNull String secretName) {
    try {
      String url =
          settings.apiUrl()
              + "/api/v3/secrets/raw/"
              + URLEncoder.encode(secretName, StandardCharsets.UTF_8)
              + "?workspaceId="
              + URLEncoder.encode(settings.projectId(), StandardCharsets.UTF_8)
              + "&environment="
              + URLEncoder.encode(settings.environment(), StandardCharsets.UTF_8)
              + "&secretPath="
              + URLEncoder.encode(settings.secretPath(), StandardCharsets.UTF_8);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .header("Authorization", "Bearer " + settings.token())
              .header("Accept", "application/json")
              .timeout(Duration.ofSeconds(15))
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOGGER.log(
            Level.WARNING,
            "[titan] Infisical secret fetch failed: HTTP {0} for secret ''{1}''",
            new Object[] {response.statusCode(), secretName});
        return null;
      }
      JsonNode value = JSON.readTree(response.body()).path("secret").path("secretValue");
      if (!value.isTextual() || value.asText().isBlank()) {
        LOGGER.log(
            Level.WARNING,
            "[titan] Infisical response for ''{0}'' had no 'secret.secretValue'",
            secretName);
        return null;
      }
      return value.asText();
    } catch (IOException | RuntimeException e) {
      LOGGER.log(Level.WARNING, "[titan] Infisical secret fetch failed: " + e.getMessage(), e);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.warning("[titan] Infisical secret fetch interrupted");
      return null;
    }
  }

  /** A short, non-sensitive description — project / env / path. Never the token. */
  @NonNull
  String describe() {
    return "infisical:"
        + settings.projectId()
        + "/"
        + settings.environment()
        + settings.secretPath();
  }

  /**
   * Resolved Infisical connection settings. {@link #fromEnvironment()} returns {@code null} when
   * Infisical is not configured.
   */
  record Settings(
      @NonNull String token,
      @NonNull String projectId,
      @NonNull String environment,
      @NonNull String secretPath,
      @NonNull String apiUrl) {

    @Nullable
    static Settings fromEnvironment() {
      String token = resolveToken();
      String projectId = env(ENV_PROJECT);
      if (token == null || projectId == null) {
        return null; // not configured
      }
      return new Settings(
          token,
          projectId,
          envOr(ENV_ENVIRONMENT, DEFAULT_ENVIRONMENT),
          envOr(ENV_SECRET_PATH, DEFAULT_SECRET_PATH),
          envOr(ENV_API_URL, DEFAULT_API_URL));
    }

    @Nullable
    private static String resolveToken() {
      String inline = env(ENV_TOKEN);
      if (inline != null) {
        return inline;
      }
      String file = env(ENV_TOKEN_FILE);
      if (file != null) {
        try {
          String content = Files.readString(Path.of(file)).strip();
          return content.isEmpty() ? null : content;
        } catch (IOException e) {
          LOGGER.log(
              Level.WARNING, "[titan] could not read INFISICAL_TOKEN_FILE: " + e.getMessage());
        }
      }
      return null;
    }

    @Nullable
    private static String env(@NonNull String name) {
      String value = System.getenv(name);
      return (value == null || value.isBlank()) ? null : value.strip();
    }

    @NonNull
    private static String envOr(@NonNull String name, @NonNull String fallback) {
      String value = env(name);
      return value == null ? fallback : value;
    }
  }
}
