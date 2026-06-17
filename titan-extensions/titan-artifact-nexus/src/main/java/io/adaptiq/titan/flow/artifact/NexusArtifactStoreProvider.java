package io.adaptiq.titan.flow.artifact;

import java.util.Map;

/**
 * The {@link ArtifactStoreProvider} for the Nexus backend — {@code kind = "nexus"} (design/49 D1,
 * D2). Discovered via {@code META-INF/services}.
 *
 * <p>Targets a Nexus 3 <em>raw</em> hosted repository: arbitrary files addressed by path, served
 * over plain HTTP. Talks to it with the JDK {@link java.net.http.HttpClient} and HTTP Basic auth —
 * no HTTP dependency on the classpath (D3).
 *
 * <p>Config keys (the {@link #create(Map)} contract — design/49 §Config):
 *
 * <table>
 *   <caption>config-map keys</caption>
 *   <tr><th>key</th><th>required</th><th>meaning</th></tr>
 *   <tr><td>{@code url}</td><td>yes</td><td>Nexus base URL, e.g. {@code https://nexus.example.com}</td></tr>
 *   <tr><td>{@code repository}</td><td>yes</td><td>the raw hosted repository name</td></tr>
 *   <tr><td>{@code username}</td><td>yes</td><td>Nexus user</td></tr>
 *   <tr><td>{@code password}</td><td>yes</td><td>Nexus password</td></tr>
 * </table>
 */
public final class NexusArtifactStoreProvider implements ArtifactStoreProvider {

  /** Config key — the Nexus base URL (required). */
  public static final String CONFIG_URL = "url";

  /** Config key — the raw hosted repository name (required). */
  public static final String CONFIG_REPOSITORY = "repository";

  /** Config key — the Nexus username (required). */
  public static final String CONFIG_USERNAME = "username";

  /** Config key — the Nexus password (required). */
  public static final String CONFIG_PASSWORD = "password";

  @Override
  public String kind() {
    return "nexus";
  }

  @Override
  public ArtifactStore create(Map<String, String> config) {
    Map<String, String> cfg = config == null ? Map.of() : config;

    String url = require(cfg, CONFIG_URL);
    String repository = require(cfg, CONFIG_REPOSITORY);
    String username = require(cfg, CONFIG_USERNAME);
    String password = require(cfg, CONFIG_PASSWORD);

    return new NexusArtifactStore(url, repository, username, password);
  }

  private static String require(Map<String, String> cfg, String key) {
    String v = trimToNull(cfg.get(key));
    if (v == null) {
      throw new IllegalArgumentException("nexus artifact store requires config key '" + key + "'");
    }
    return v;
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
