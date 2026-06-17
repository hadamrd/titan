package io.adaptiq.titan.flow.artifact;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * The {@link ArtifactStoreProvider} for the S3 / R2 backend — {@code kind = "s3"} (design/46 D1,
 * D2). Discovered via {@code META-INF/services}.
 *
 * <p>One {@code s3} backend serves both real AWS S3 and Cloudflare R2: R2 <em>is</em> the S3 API
 * plus a custom {@code endpoint}. Pointing {@link #CONFIG_ENDPOINT} at {@code
 * https://<account>.r2.cloudflarestorage.com} selects R2; leaving it unset selects AWS S3.
 *
 * <p>Config keys (the {@link #create(Map)} contract — design/46 §Config):
 *
 * <table>
 *   <caption>config-map keys</caption>
 *   <tr><th>key</th><th>required</th><th>meaning</th></tr>
 *   <tr><td>{@code bucket}</td><td>yes</td><td>the bucket name</td></tr>
 *   <tr><td>{@code endpoint}</td><td>no</td><td>S3 endpoint; set for R2, unset for AWS S3</td></tr>
 *   <tr><td>{@code region}</td><td>no</td><td>AWS region; R2 uses {@code auto}; default
 *       {@code us-east-1}</td></tr>
 *   <tr><td>{@code accesskey}</td><td>yes</td><td>S3 access key id</td></tr>
 *   <tr><td>{@code secretkey}</td><td>yes</td><td>S3 secret access key</td></tr>
 *   <tr><td>{@code pathstyle}</td><td>no</td><td>{@code true} to force path-style addressing;
 *       default false</td></tr>
 * </table>
 */
public final class S3ArtifactStoreProvider implements ArtifactStoreProvider {

  /** Config key — the bucket name (required). */
  public static final String CONFIG_BUCKET = "bucket";

  /** Config key — the S3 endpoint; set for R2, unset for AWS S3 (optional). */
  public static final String CONFIG_ENDPOINT = "endpoint";

  /** Config key — the AWS region; R2 uses {@code auto}; defaults to {@code us-east-1}. */
  public static final String CONFIG_REGION = "region";

  /** Config key — the S3 access key id (required). */
  public static final String CONFIG_ACCESS_KEY = "accesskey";

  /** Config key — the S3 secret access key (required). */
  public static final String CONFIG_SECRET_KEY = "secretkey";

  /** Config key — {@code true} to force path-style addressing (optional, default false). */
  public static final String CONFIG_PATH_STYLE = "pathstyle";

  /** The region used when {@link #CONFIG_REGION} is unset. */
  private static final String DEFAULT_REGION = "us-east-1";

  @Override
  public String kind() {
    return "s3";
  }

  @Override
  public ArtifactStore create(Map<String, String> config) {
    Map<String, String> cfg = config == null ? Map.of() : config;

    String bucket = require(cfg, CONFIG_BUCKET);
    String accessKey = require(cfg, CONFIG_ACCESS_KEY);
    String secretKey = require(cfg, CONFIG_SECRET_KEY);
    String endpoint = trimToNull(cfg.get(CONFIG_ENDPOINT));
    String region = trimToNull(cfg.get(CONFIG_REGION));
    boolean pathStyle =
        Boolean.parseBoolean(
            cfg.getOrDefault(CONFIG_PATH_STYLE, "false").trim().toLowerCase(Locale.ROOT));

    S3Client client =
        S3Client.builder()
            .httpClient(UrlConnectionHttpClient.builder().build())
            // AWS SDK v2 2.30+ defaults to trailing CRC32 checksums on every request;
            // S3-compatible stores that predate that (older MinIO, some R2 paths) reject it
            // and demand the legacy Content-MD5. WHEN_REQUIRED restores per-operation
            // integrity headers (e.g. the Content-MD5 DeleteObjects needs) and is harmless
            // against real AWS S3, which accepts it.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .region(Region.of(region == null ? DEFAULT_REGION : region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
            .applyMutation(
                b -> {
                  if (endpoint != null) {
                    b.endpointOverride(URI.create(endpoint));
                  }
                })
            .build();

    return new S3ArtifactStore(client, bucket);
  }

  private static String require(Map<String, String> cfg, String key) {
    String v = trimToNull(cfg.get(key));
    if (v == null) {
      throw new IllegalArgumentException("s3 artifact store requires config key '" + key + "'");
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
