package io.adaptiq.titan.scm.github;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GithubAppWebhookSecretComparatorTest {

  @Test
  void constantTimeEquals_matchingStringsReturnTrue() {
    assertTrue(GithubAppWebhookSecretComparator.constantTimeEquals("hunter2", "hunter2"));
  }

  @Test
  void constantTimeEquals_differingStringsReturnFalse() {
    assertFalse(GithubAppWebhookSecretComparator.constantTimeEquals("hunter2", "hunter3"));
  }

  @Test
  void constantTimeEquals_differentLengthsReturnFalse() {
    // MessageDigest.isEqual handles unequal-length arrays without short-circuit timing leak.
    assertFalse(GithubAppWebhookSecretComparator.constantTimeEquals("hunter2", "hunter22"));
  }

  @Test
  void constantTimeEquals_emptyStrings() {
    assertTrue(GithubAppWebhookSecretComparator.constantTimeEquals("", ""));
  }
}
