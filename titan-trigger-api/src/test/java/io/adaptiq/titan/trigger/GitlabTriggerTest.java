package io.adaptiq.titan.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the GitLab webhook trigger (issue #1078) — descriptor registration, JSON round-trip,
 * default-events behaviour, and the "never fires on poll" invariant.
 */
class GitlabTriggerTest {

  /** A polling tick must never fire a GitLab trigger — it fires only via inbound HTTP delivery. */
  @Test
  void pollingTickAlwaysSkips() {
    GitlabTrigger t =
        new GitlabTrigger(null, List.of("trunk"), List.of("push"), "gitlab-webhook-token");
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    TriggerOutcome outcome = t.evaluate(new TriggerContext(now, null, "seed"));
    assertEquals(TriggerOutcome.Kind.SKIP, outcome.kind());
  }

  /** Defaults to a single 'push' event when {@code events:} is omitted. */
  @Test
  void eventsDefaultToPush() {
    GitlabTrigger t = new GitlabTrigger(null, List.of(), null, "gitlab-webhook-token");
    assertEquals(List.of("push"), t.getEvents());
  }

  /** writeState / readState round-trip preserves every field. */
  @Test
  void codecRoundTripPreservesFields() {
    ObjectMapper m = new ObjectMapper();
    GitlabTrigger original =
        new GitlabTrigger(
            "fixed-id",
            List.of("trunk", "feat/**"),
            List.of("push", "merge_request", "tag_push"),
            "gitlab-webhook-token");

    ObjectNode node = m.createObjectNode();
    original.writeState(node);

    assertEquals("gitlab-webhook-token", node.path("credentialsId").asText());
    ArrayNode events = (ArrayNode) node.path("events");
    assertEquals(3, events.size());
    assertEquals("merge_request", events.get(1).asText());

    GitlabTrigger.DescriptorImpl descriptor = new GitlabTrigger.DescriptorImpl();
    Trigger reconstructed = descriptor.readState("fixed-id", node);
    assertTrue(reconstructed instanceof GitlabTrigger);
    GitlabTrigger gl = (GitlabTrigger) reconstructed;
    assertEquals(original.getBranches(), gl.getBranches());
    assertEquals(original.getEvents(), gl.getEvents());
    assertEquals(original.getCredentialsId(), gl.getCredentialsId());
    assertEquals("fixed-id", gl.getId());
  }

  /** credentialsId is trimmed at construction — defensive against editor-introduced whitespace. */
  @Test
  void credentialsIdIsTrimmed() {
    GitlabTrigger t = new GitlabTrigger(null, null, null, "   gitlab-webhook-token \n");
    assertEquals("gitlab-webhook-token", t.getCredentialsId());
  }

  /** The descriptor is discoverable via ServiceLoader (META-INF/services registration). */
  @Test
  void descriptorIsServiceLoaderRegistered() {
    assertTrue(
        TriggerDescriptor.all().stream().anyMatch(d -> GitlabTrigger.TYPE.equals(d.triggerType())),
        "GitlabTrigger.DescriptorImpl must be registered in META-INF/services");
  }
}
