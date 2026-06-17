package io.adaptiq.titan.trigger.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerContext;
import io.adaptiq.titan.trigger.TriggerOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Ported from {@code io.adaptiq.scheduler.engine.BoundedTriggerEvaluatorTest} (Wave 1). */
class BoundedTriggerEvaluatorTest {

  private static final TriggerContext CTX = new TriggerContext(Instant.now(), null, "seed");

  @Test
  void passesThroughAFastTrigger() {
    BoundedTriggerEvaluator evaluator = new BoundedTriggerEvaluator(() -> Duration.ofSeconds(5));
    TriggerOutcome outcome = evaluator.evaluate(trigger(ctx -> TriggerOutcome.fire()), CTX);
    assertEquals(TriggerOutcome.Kind.FIRE, outcome.kind());
  }

  @Test
  void cutsOffAHangingTriggerNearTheBudget() {
    BoundedTriggerEvaluator evaluator = new BoundedTriggerEvaluator(() -> Duration.ofMillis(100));
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          TriggerOutcome outcome =
              evaluator.evaluate(
                  trigger(
                      ctx -> {
                        sleepUninterruptibly(Duration.ofMinutes(1));
                        return TriggerOutcome.fire();
                      }),
                  CTX);
          assertEquals(
              TriggerOutcome.Kind.SKIP,
              outcome.kind(),
              "a trigger that overran its budget is treated as a skip");
        });
  }

  @Test
  void propagatesATriggerExceptionForTheDispatcherToRecord() {
    BoundedTriggerEvaluator evaluator = new BoundedTriggerEvaluator(() -> Duration.ofSeconds(5));
    assertThrows(
        RuntimeException.class,
        () ->
            evaluator.evaluate(
                trigger(
                    ctx -> {
                      throw new IllegalStateException("boom");
                    }),
                CTX));
  }

  private static void sleepUninterruptibly(Duration d) {
    long deadline = System.nanoTime() + d.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        // Deliberately ignore.
      }
    }
  }

  private static Trigger trigger(Function<TriggerContext, TriggerOutcome> body) {
    return new TestTrigger(body);
  }

  static final class TestTrigger extends Trigger {
    private final Function<TriggerContext, TriggerOutcome> body;

    TestTrigger(Function<TriggerContext, TriggerOutcome> body) {
      super("test-trigger");
      this.body = body;
    }

    @Override
    public String getType() {
      return "test";
    }

    @Override
    public TriggerOutcome evaluate(TriggerContext ctx) {
      return body.apply(ctx);
    }

    @Override
    public void writeState(ObjectNode node) {
      // no persisted state
    }
  }
}
