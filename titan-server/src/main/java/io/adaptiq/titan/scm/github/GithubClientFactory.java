package io.adaptiq.titan.scm.github;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.authorization.JWTTokenProvider;

/**
 * Factory that builds kohsuke {@link GitHub} clients for the GitHub App scenarios Titan needs
 * (#874, the org.kohsuke:github-api migration that replaced the hand-rolled HTTP plumbing).
 *
 * <p>Three flavours:
 *
 * <ul>
 *   <li>{@link #anonymous()} — used only for the manifest-callback exchange ({@code POST
 *       /app-manifests/{code}/conversions}), which is unauthenticated by design (the temp code is
 *       the auth).
 *   <li>{@link #asApp(long, String)} — App-as-itself, authenticated via an RS256 JWT. kohsuke's
 *       {@link JWTTokenProvider} signs JWTs on demand (and re-signs as they approach the 10-min
 *       expiry) without exposing the PEM to the caller. We construct one per call so the PEM string
 *       falls out of scope immediately afterwards — CONSTITUTION §6 cache-ban contract.
 *   <li>{@link #asInstallation(String)} — Installation-scoped, authenticated via the installation
 *       access token (from {@code POST /app/installations/{id}/access_tokens}).
 * </ul>
 *
 * <p>Extracted as a seam so {@code GithubAppServiceTest} can inject a wiremock-backed factory and
 * swap the endpoint to {@code http://localhost:&lt;port&gt;}.
 */
public class GithubClientFactory {

  /** Production GitHub REST endpoint. Override via constructor in tests. */
  public static final String DEFAULT_ENDPOINT = "https://api.github.com";

  private final String endpoint;

  public GithubClientFactory() {
    this(DEFAULT_ENDPOINT);
  }

  public GithubClientFactory(@NonNull String endpoint) {
    this.endpoint = trimTrailingSlash(endpoint);
  }

  /** Anonymous client — used for the manifest-callback exchange. */
  @NonNull
  public GitHub anonymous() throws IOException {
    return new GitHubBuilder().withEndpoint(endpoint).build();
  }

  /**
   * Exchange a manifest temp code for the App credentials. Replaces kohsuke's {@code
   * createAppFromManifest} so we own the deserialization — the response is parsed with {@link
   * GithubJson#MAPPER} into {@link GithubManifestCallback}, tolerating any field GitHub adds in the
   * future (issue #873).
   *
   * @throws GithubApiException with the HTTP status if GitHub responds non-2xx, or {@code -1} on
   *     I/O.
   */
  @NonNull
  public GithubManifestCallback exchangeManifestCode(@NonNull String code) {
    URI uri = URI.create(endpoint + "/app-manifests/" + code + "/conversions");
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> resp;
    try {
      resp =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(10))
              .build()
              .send(req, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new GithubApiException("manifest-callback I/O error: " + e.getMessage(), -1, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GithubApiException("manifest-callback interrupted", -1, e);
    }
    int status = resp.statusCode();
    if (status < 200 || status >= 300) {
      throw new GithubApiException(
          "manifest-callback exchange failed: HTTP " + status + ": " + resp.body(), status);
    }
    try {
      GithubManifestCallback parsed =
          GithubJson.MAPPER.readValue(resp.body(), GithubManifestCallback.class);
      if (parsed == null) {
        throw new GithubApiException("manifest-callback response empty", -1);
      }
      return parsed;
    } catch (IOException e) {
      throw new GithubApiException(
          "manifest-callback response not parseable: " + e.getMessage(), -1, e);
    }
  }

  /**
   * App-as-itself client, authenticated with an RS256 JWT signed by the PEM. kohsuke's {@link
   * JWTTokenProvider} signs and caches the JWT internally for ~9 minutes, re-signing on expiry. The
   * PEM is parsed once at construction and held only as a {@link java.security.PrivateKey} — the
   * original String is not retained.
   */
  @NonNull
  public GitHub asApp(long appId, @NonNull String pemPrivateKey) throws IOException {
    try {
      PrivateKey key = parseRsaPrivateKey(pemPrivateKey);
      JWTTokenProvider provider = new JWTTokenProvider(Long.toString(appId), key);
      return new GitHubBuilder().withEndpoint(endpoint).withAuthorizationProvider(provider).build();
    } catch (java.security.GeneralSecurityException e) {
      throw new IOException("failed to parse GitHub App PEM private key", e);
    }
  }

  /**
   * Parse a PEM-encoded RSA private key into a {@link PrivateKey}.
   *
   * <p>GitHub's App Manifest exchange returns the PEM in PKCS#1 ({@code -----BEGIN RSA PRIVATE
   * KEY-----}); kohsuke's {@link JWTTokenProvider} (via jjwt) only parses PKCS#8 ({@code -----BEGIN
   * PRIVATE KEY-----}). We support BOTH by trying PKCS#8 first and, on failure, wrapping the PKCS#1
   * bytes with the fixed PKCS#8 ASN.1 algorithm-identifier header for {@code rsaEncryption} (OID
   * 1.2.840.113549.1.1.1). Pure JDK, no BouncyCastle dep.
   */
  @NonNull
  static PrivateKey parseRsaPrivateKey(@NonNull String pem)
      throws java.security.GeneralSecurityException {
    String stripped =
        pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
            .replaceAll("-----END [A-Z ]+-----", "")
            .replaceAll("\\s+", "");
    byte[] der = Base64.getDecoder().decode(stripped);
    KeyFactory rsa = KeyFactory.getInstance("RSA");
    try {
      // PKCS#8: PrivateKeyInfo SEQUENCE { version, algId, OCTET STRING }
      return rsa.generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (InvalidKeySpecException pkcs8Failed) {
      // Fall back to PKCS#1: wrap with PKCS#8 header for rsaEncryption.
      return rsa.generatePrivate(new PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)));
    }
  }

  /**
   * Wrap a PKCS#1 RSA private-key DER body with the PKCS#8 PrivateKeyInfo SEQUENCE. The
   * algorithm-identifier is a fixed 15-byte block for {@code rsaEncryption} (OID
   * 1.2.840.113549.1.1.1 + NULL parameters); the body becomes the inner OCTET STRING.
   */
  @NonNull
  private static byte[] pkcs1ToPkcs8(@NonNull byte[] pkcs1) {
    final byte[] rsaAlgId = {
      0x30,
      0x0d, // SEQUENCE, length 13
      0x06,
      0x09, // OID, length 9
      0x2a,
      (byte) 0x86,
      0x48,
      (byte) 0x86,
      (byte) 0xf7,
      0x0d,
      0x01,
      0x01,
      0x01,
      0x05,
      0x00 // NULL parameters
    };
    final byte[] version = {0x02, 0x01, 0x00}; // INTEGER 0
    byte[] octetString = derTagLengthValue((byte) 0x04, pkcs1);
    byte[] inner = concat(version, rsaAlgId, octetString);
    return derTagLengthValue((byte) 0x30, inner); // outer SEQUENCE
  }

  @NonNull
  private static byte[] derTagLengthValue(byte tag, @NonNull byte[] content) {
    int len = content.length;
    byte[] lenBytes;
    if (len < 0x80) {
      lenBytes = new byte[] {(byte) len};
    } else if (len <= 0xff) {
      lenBytes = new byte[] {(byte) 0x81, (byte) len};
    } else if (len <= 0xffff) {
      lenBytes = new byte[] {(byte) 0x82, (byte) (len >> 8), (byte) (len & 0xff)};
    } else {
      lenBytes =
          new byte[] {(byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) (len & 0xff)};
    }
    byte[] out = new byte[1 + lenBytes.length + content.length];
    out[0] = tag;
    System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
    System.arraycopy(content, 0, out, 1 + lenBytes.length, content.length);
    return out;
  }

  @NonNull
  private static byte[] concat(@NonNull byte[]... parts) {
    int total = 0;
    for (byte[] p : parts) total += p.length;
    byte[] out = new byte[total];
    int off = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, off, p.length);
      off += p.length;
    }
    return out;
  }

  /** Installation-scoped client, authenticated with the {@code ghs_*} installation token. */
  @NonNull
  public GitHub asInstallation(@NonNull String installationToken) throws IOException {
    return new GitHubBuilder()
        .withEndpoint(endpoint)
        .withAppInstallationToken(installationToken)
        .build();
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
