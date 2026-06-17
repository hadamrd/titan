package io.adaptiq.titan.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [MigrationVersions]. Adversarial-first: the whole point of this class is to catch
 * the #1157 duplicate-version outage, so the duplicate-finding paths get the heaviest coverage.
 */
class MigrationVersionsTest {

  // ── parseVersion ─────────────────────────────────────────────────────────

  @ParameterizedTest
  @CsvSource(
    "V1__init.sql, 1",
    "V34__build_search_fts.sql, 34",
    "V99__a.sql, 99",
    "V23_1__task_queue_cancel_intent_partial_index.sql, 23.1",
    "V1.2.3__multi_component.sql, 1.2.3",
    "V100__big.sql, 100",
  )
  fun `parseVersion extracts and normalizes the version`(file: String, expected: String) {
    assertEquals(expected, MigrationVersions.parseVersion(file))
  }

  @Test
  fun `parseVersion treats underscore and dot separators as equivalent`() {
    assertEquals(
      MigrationVersions.parseVersion("V23.1__x.sql"),
      MigrationVersions.parseVersion("V23_1__x.sql"),
    )
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "R__titan_pg_partial_indexes.sql", // repeatable — no version
        "U34__undo.sql", // undo migration — not a versioned 'V' file
        "V34_build_search_fts.sql", // missing the '__' separator
        "Vfoo__bad.sql", // non-numeric version
        "README.md", // not a migration at all
        "V34__build_search_fts.txt", // wrong extension
        "", // empty string
      ]
  )
  fun `parseVersion returns null for non-versioned-migration names`(name: String) {
    assertNull(MigrationVersions.parseVersion(name))
  }

  // ── findDuplicates: the sad path (the reason this code exists) ────────────

  @Test
  fun `findDuplicates flags two files sharing a version`() {
    val dupes = MigrationVersions.findDuplicates(listOf("V99__a.sql", "V99__b.sql"))
    assertEquals(setOf("99"), dupes.keys)
    assertEquals(listOf("V99__a.sql", "V99__b.sql"), dupes["99"])
  }

  @Test
  fun `findDuplicates reproduces the #1157 duplicate-V34 outage`() {
    val realMigrations =
      listOf("V33__x.sql", "V34__build_search_fts.sql", "V34__scm_event_cursor.sql", "V35__y.sql")
    val dupes = MigrationVersions.findDuplicates(realMigrations)
    assertEquals(setOf("34"), dupes.keys)
    assertEquals(2, dupes.getValue("34").size)
  }

  @Test
  fun `findDuplicates flags a collision that spans dotted and underscore forms`() {
    // Flyway treats V23_1 and V23.1 as the same version — they MUST be caught.
    val dupes = MigrationVersions.findDuplicates(listOf("V23_1__a.sql", "V23.1__b.sql"))
    assertEquals(setOf("23.1"), dupes.keys)
  }

  @Test
  fun `findDuplicates reports multiple duplicate versions in ascending numeric order`() {
    val dupes =
      MigrationVersions.findDuplicates(
        listOf("V9__a.sql", "V9__b.sql", "V10__c.sql", "V10__d.sql", "V2__e.sql", "V2__f.sql")
      )
    // Numeric, not lexicographic: 2, 9, 10 — NOT 10, 2, 9.
    assertEquals(listOf("2", "9", "10"), dupes.keys.toList())
  }

  @Test
  fun `findDuplicates sorts the colliding filenames within a version`() {
    val dupes = MigrationVersions.findDuplicates(listOf("V5__zeta.sql", "V5__alpha.sql"))
    assertEquals(listOf("V5__alpha.sql", "V5__zeta.sql"), dupes.getValue("5"))
  }

  // ── findDuplicates: the happy path ───────────────────────────────────────

  @Test
  fun `findDuplicates returns empty for a clean, unique migration set`() {
    val clean = listOf("V1__init.sql", "V2__a.sql", "V23_1__pg.sql", "R__repeatable.sql")
    assertTrue(MigrationVersions.findDuplicates(clean).isEmpty())
  }

  @Test
  fun `findDuplicates ignores non-migration and repeatable files when counting`() {
    // Two R__ files, a README, and a single V7 — nothing collides.
    val mixed =
      listOf("R__a.sql", "R__b.sql", "README.md", "V7__only.sql", "V34__build_search_fts.txt")
    assertTrue(MigrationVersions.findDuplicates(mixed).isEmpty())
  }

  @Test
  fun `findDuplicates handles the empty list without crashing`() {
    assertTrue(MigrationVersions.findDuplicates(emptyList()).isEmpty())
  }

  @Test
  fun `findDuplicates is order-independent`() {
    val a = MigrationVersions.findDuplicates(listOf("V99__a.sql", "V1__x.sql", "V99__b.sql"))
    val b = MigrationVersions.findDuplicates(listOf("V99__b.sql", "V99__a.sql", "V1__x.sql"))
    assertEquals(a, b)
  }
}
