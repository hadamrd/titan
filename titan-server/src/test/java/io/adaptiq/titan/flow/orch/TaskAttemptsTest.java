package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit pins for {@link TaskAttempts#attemptOf} — the dispatch-generation read the #125 reconciler
 * guard and the lost-dispatch self-heal both key on. The default-to-1 contract is load-bearing:
 * every {@code EXECUTE_COMMAND} enqueued before #125 carries no {@code attempt} field, and those
 * tasks must keep folding for never-retried nodes (whose {@code flow_nodes.attempt} is 1).
 */
class TaskAttemptsTest {

  @Test
  void stampedAttempt_isReturned() {
    assertEquals(2, TaskAttempts.attemptOf("{\"nodeId\":\"test-s0\",\"attempt\":2}"));
    assertEquals(7, TaskAttempts.attemptOf("{\"attempt\":7}"));
  }

  @Test
  void missingAttempt_defaultsToOne_preFix125Payloads() {
    assertEquals(1, TaskAttempts.attemptOf("{\"nodeId\":\"test-s0\",\"exitCode\":1}"));
    assertEquals(1, TaskAttempts.attemptOf("{}"));
  }

  @Test
  void nullOrBlankPayload_defaultsToOne() {
    assertEquals(1, TaskAttempts.attemptOf(null));
    assertEquals(1, TaskAttempts.attemptOf(""));
    assertEquals(1, TaskAttempts.attemptOf("   "));
  }

  @Test
  void malformedPayload_defaultsToOne() {
    assertEquals(1, TaskAttempts.attemptOf("not json at all"));
    assertEquals(1, TaskAttempts.attemptOf("{\"attempt\":\"not-a-number\"}"));
    assertEquals(1, TaskAttempts.attemptOf("{\"attempt\":null}"));
  }

  @Test
  void nonPositiveAttempt_isClampedToOne() {
    assertEquals(1, TaskAttempts.attemptOf("{\"attempt\":0}"));
    assertEquals(1, TaskAttempts.attemptOf("{\"attempt\":-3}"));
  }
}
