package io.adaptiq.titan.scm.bitbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BitbucketAuthProvider} — app-password Basic header, OAuth stub, redaction.
 */
class BitbucketAuthProviderTest {

  @Test
  void appPassword_buildsBasicHeaderWithBase64OfUserColonSecret() {
    BitbucketAuthProvider auth = BitbucketAuthProvider.appPassword("alice", "s3cr3t");
    String expected =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:s3cr3t".getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, auth.authorizationHeaderValue());
    assertEquals(BitbucketAuthProvider.Mode.APP_PASSWORD, auth.mode());
  }

  @Test
  void fromColonPair_splitsOnFirstColon_preservingColonsInSecret() {
    BitbucketAuthProvider auth = BitbucketAuthProvider.fromColonPair("bob:ab:cd:ef");
    String expected =
        "Basic "
            + Base64.getEncoder().encodeToString("bob:ab:cd:ef".getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, auth.authorizationHeaderValue());
  }

  @Test
  void fromColonPair_noColon_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> BitbucketAuthProvider.fromColonPair("nocolon"));
    assertTrue(ex.getMessage().contains("username:app_password"));
  }

  @Test
  void fromColonPair_blankUsername_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> BitbucketAuthProvider.fromColonPair(":onlysecret"));
  }

  @Test
  void fromColonPair_blankSecret_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> BitbucketAuthProvider.fromColonPair("onlyuser:"));
  }

  @Test
  void appPassword_blankUsername_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> BitbucketAuthProvider.appPassword("", "secret"));
  }

  @Test
  void appPassword_emptySecret_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> BitbucketAuthProvider.appPassword("alice", ""));
  }

  @Test
  void oauth2_authorizationHeader_throwsWithActionableMessage() {
    BitbucketAuthProvider auth = BitbucketAuthProvider.oauth2();
    assertEquals(BitbucketAuthProvider.Mode.OAUTH2, auth.mode());
    UnsupportedOperationException ex =
        assertThrows(UnsupportedOperationException.class, auth::authorizationHeaderValue);
    assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("oauth2"));
    assertTrue(ex.getMessage().contains("app-password"));
  }

  @Test
  void toString_neverContainsSecretOrBase64() {
    String secret = "DO-NOT-LEAK-supersecret";
    BitbucketAuthProvider auth = BitbucketAuthProvider.appPassword("alice", secret);
    String b64 =
        Base64.getEncoder().encodeToString(("alice:" + secret).getBytes(StandardCharsets.UTF_8));
    String s = auth.toString();
    assertFalse(s.contains(secret), "toString leaked the raw secret: " + s);
    assertFalse(s.contains(b64), "toString leaked the base64 (decodes to the secret): " + s);
    assertTrue(s.contains("redacted"));
  }
}
