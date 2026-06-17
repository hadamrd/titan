package io.adaptiq.titan.scm.github;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Test-only helper for generating RSA keypairs and PKCS#8 PEM strings. Shared across {@link
 * GithubAppJwtSignerTest}, {@link GithubAppServiceTest}, and the API test in the {@code api}
 * package.
 */
public final class TestRsaKeys {

  private TestRsaKeys() {}

  public static KeyPair newRsaKeyPair() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    return g.generateKeyPair();
  }

  public static String toPkcs8Pem(KeyPair kp) {
    byte[] der = kp.getPrivate().getEncoded(); // JDK emits PKCS#8 for RSA private keys
    String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
    return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
  }
}
