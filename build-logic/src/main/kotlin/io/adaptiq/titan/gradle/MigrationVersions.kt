/*
 * MigrationVersions — pure parsing + duplicate-detection logic for Flyway
 * versioned migrations.
 *
 * Why this exists: on 2026-06-03 two PRs each added a `V34__*.sql` migration to
 * `titan-db-core` (see #1157). Flyway refuses to start when two migrations share
 * a version ("Found more than one migration with version 34"), so titan-server
 * CrashLooped on the rig and blocked the deploy — caught only by reading pod
 * logs at 3am. This logic powers a Gradle `check`-wired task so a duplicate
 * version can never reach trunk again.
 *
 * Kept as a side-effect-free object (no Gradle types) so it is trivially
 * unit-testable; the Gradle task is a thin adapter over it.
 */
package io.adaptiq.titan.gradle

/** Side-effect-free Flyway migration filename parsing + duplicate detection. */
object MigrationVersions {

  /**
   * Matches a Flyway *versioned* migration filename: `V<version>__<description>.sql`.
   *
   * The version is one or more numeric components separated by `.` or `_` (Flyway treats the two
   * separators as equivalent), e.g. `V34`, `V23_1`, `V1.2.3`. Repeatable (`R__…`) and undo (`U…`)
   * migrations have no version and are intentionally NOT matched — they cannot collide on a
   * version.
   */
  private val VERSIONED_MIGRATION = Regex("""^V(?<version>\d+(?:[._]\d+)*)__.*\.sql$""")

  /**
   * Extracts the normalized version of a single migration filename, or `null` when the name is not
   * a versioned migration (repeatable, undo, junk).
   *
   * Normalization collapses Flyway's interchangeable `_`/`.` separators to `.` so that `V23_1` and
   * `V23.1` are recognized as the same version `23.1`.
   */
  fun parseVersion(fileName: String): String? {
    val match = VERSIONED_MIGRATION.matchEntire(fileName) ?: return null
    return match.groups["version"]!!.value.replace('_', '.')
  }

  /**
   * Groups the given migration filenames by version and returns only the versions claimed by more
   * than one file — i.e. the Flyway-fatal duplicates.
   *
   * @param fileNames bare filenames (not paths). Order-independent; non-migration names are
   *   ignored.
   * @return version → the (sorted) filenames sharing it, for every version with ≥2 files. Empty map
   *   means the migration set is safe. Iteration order is ascending by version for deterministic,
   *   readable error messages.
   */
  fun findDuplicates(fileNames: Collection<String>): Map<String, List<String>> {
    val byVersion = LinkedHashMap<String, MutableList<String>>()
    for (name in fileNames) {
      val version = parseVersion(name) ?: continue
      byVersion.getOrPut(version) { mutableListOf() }.add(name)
    }
    return byVersion
      .filterValues { it.size > 1 }
      .mapValues { (_, names) -> names.sorted() }
      .toSortedMap(VERSION_ORDER)
  }

  /**
   * Orders dotted numeric versions numerically rather than lexicographically, so `9` sorts before
   * `10` and `23.1` after `23`.
   */
  private val VERSION_ORDER: Comparator<String> = Comparator { a, b ->
    val pa = a.split('.').map { it.toLong() }
    val pb = b.split('.').map { it.toLong() }
    var i = 0
    while (i < pa.size && i < pb.size) {
      val c = pa[i].compareTo(pb[i])
      if (c != 0) return@Comparator c
      i++
    }
    pa.size.compareTo(pb.size)
  }
}
