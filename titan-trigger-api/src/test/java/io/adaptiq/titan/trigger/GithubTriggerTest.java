package io.adaptiq.titan.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the GitHub webhook trigger (issue #397) — descriptor registration, JSON round-trip,
 * and the "never fires on poll" invariant.
 */
class GithubTriggerTest {

  /** A polling tick must never fire a GitHub trigger — it fires only via inbound HTTP delivery. */
  @Test
  void pollingTickAlwaysSkips() {
    GithubTrigger t =
        new GithubTrigger(null, List.of("trunk"), List.of("push"), "github-webhook-secret");
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    TriggerOutcome outcome = t.evaluate(new TriggerContext(now, null, "seed"));
    assertEquals(TriggerOutcome.Kind.SKIP, outcome.kind());
  }

  /** Defaults to a single 'push' event when {@code events:} is omitted. */
  @Test
  void eventsDefaultToPush() {
    GithubTrigger t = new GithubTrigger(null, List.of(), null, "github-webhook-secret");
    assertEquals(List.of("push"), t.getEvents());
  }

  /** writeState / readState round-trip preserves every field, including branch glob order. */
  @Test
  void codecRoundTripPreservesFields() {
    ObjectMapper m = new ObjectMapper();
    GithubTrigger original =
        new GithubTrigger(
            "fixed-id",
            List.of("trunk", "feat/**"),
            List.of("push", "pull_request"),
            "github-webhook-secret");

    com.fasterxml.jackson.databind.node.ObjectNode node = m.createObjectNode();
    original.writeState(node);

    assertEquals("github-webhook-secret", node.path("credentialsId").asText());
    ArrayNode branches = (ArrayNode) node.path("branches");
    assertEquals(2, branches.size());
    assertEquals("trunk", branches.get(0).asText());
    assertEquals("feat/**", branches.get(1).asText());

    GithubTrigger.DescriptorImpl descriptor = new GithubTrigger.DescriptorImpl();
    Trigger reconstructed = descriptor.readState("fixed-id", node);
    assertTrue(reconstructed instanceof GithubTrigger);
    GithubTrigger gh = (GithubTrigger) reconstructed;
    assertEquals(original.getBranches(), gh.getBranches());
    assertEquals(original.getEvents(), gh.getEvents());
    assertEquals(original.getCredentialsId(), gh.getCredentialsId());
    assertEquals("fixed-id", gh.getId());
  }

  /** The descriptor is discoverable via ServiceLoader (META-INF/services registration). */
  @Test
  void descriptorIsServiceLoaderRegistered() {
    assertTrue(
        TriggerDescriptor.all().stream().anyMatch(d -> GithubTrigger.TYPE.equals(d.triggerType())),
        "GithubTrigger.DescriptorImpl must be registered in META-INF/services");
  }
}
