package io.adaptiq.titan.flow.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WhenFacts#fromTriggerMeta} (GH #1093) — extracting the branch and
 * changed-file set from a build's trigger metadata, including the adversarial degrade-to-empty
 * paths.
 */
class WhenFactsTest {

  @Test
  void extractsBranchFromTriggerMeta() {
    WhenFacts f = WhenFacts.fromTriggerMeta("{\"branch\":\"trunk\",\"commitSha\":\"a3f9c12\"}");
    assertEquals("trunk", f.branch());
    assertTrue(f.changedFiles().isEmpty());
  }

  @Test
  void extractsChangedFilesCamelCase() {
    WhenFacts f =
        WhenFacts.fromTriggerMeta(
            "{\"branch\":\"main\",\"changedFiles\":[\"docs/a.md\",\"src/B.java\"]}");
    assertEquals(List.of("docs/a.md", "src/B.java"), f.changedFiles());
  }

  @Test
  void extractsChangedFilesSnakeCase() {
    WhenFacts f = WhenFacts.fromTriggerMeta("{\"changed_files\":[\"x.txt\"]}");
    assertEquals(List.of("x.txt"), f.changedFiles());
    assertNull(f.branch());
  }

  @Test
  void nullMetadataDegradesToEmpty() {
    WhenFacts f = WhenFacts.fromTriggerMeta(null);
    assertNull(f.branch());
    assertTrue(f.changedFiles().isEmpty());
  }

  @Test
  void blankMetadataDegradesToEmpty() {
    WhenFacts f = WhenFacts.fromTriggerMeta("   ");
    assertNull(f.branch());
    assertTrue(f.changedFiles().isEmpty());
  }

  @Test
  void malformedJsonDegradesToEmptyNotCrash() {
    // Adversarial: a corrupt trigger_meta_json must never crash the orchestrator's dispatch loop.
    WhenFacts f = WhenFacts.fromTriggerMeta("{not valid json");
    assertNull(f.branch());
    assertTrue(f.changedFiles().isEmpty());
  }

  @Test
  void nonObjectJsonDegradesToEmpty() {
    WhenFacts f = WhenFacts.fromTriggerMeta("[1,2,3]");
    assertNull(f.branch());
    assertTrue(f.changedFiles().isEmpty());
  }

  @Test
  void blankBranchValueReadsAsNull() {
    WhenFacts f = WhenFacts.fromTriggerMeta("{\"branch\":\"  \"}");
    assertNull(f.branch());
  }

  @Test
  void nonStringChangedFileEntriesAreSkipped() {
    WhenFacts f =
        WhenFacts.fromTriggerMeta(
            "{\"changedFiles\":[\"keep.txt\", 7, null, \"  \", \"also.txt\"]}");
    assertEquals(List.of("keep.txt", "also.txt"), f.changedFiles());
  }
}
