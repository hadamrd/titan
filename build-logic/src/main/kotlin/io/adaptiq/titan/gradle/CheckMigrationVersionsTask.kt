/*
 * CheckMigrationVersionsTask — Gradle adapter over [MigrationVersions].
 *
 * Wired into `check` (see titan.java-library.gradle.kts). Scans a module's
 * Flyway migration files and FAILS the build if two share a version, so the
 * #1157-class outage (duplicate V34 → titan-server CrashLoop on the rig) is
 * caught at build time instead of at 3am in pod logs.
 */
package io.adaptiq.titan.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/** Fails the build when two Flyway migrations claim the same version. */
abstract class CheckMigrationVersionsTask : DefaultTask() {

  /**
   * The versioned migration files to check. Only the filename matters ([PathSensitivity.NAME_ONLY])
   * — duplicate detection is on the `V<n>__` prefix, not directory or contents — which keeps the
   * task up-to-date across unrelated edits.
   */
  @get:InputFiles
  @get:SkipWhenEmpty
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val migrationFiles: ConfigurableFileCollection

  init {
    group = "verification"
    description =
      "Fails the build if two Flyway migrations share the same version (guards against the #1157 duplicate-V34 outage)."
  }

  @TaskAction
  fun check() {
    val names = migrationFiles.files.map { it.name }
    val duplicates = MigrationVersions.findDuplicates(names)

    if (duplicates.isNotEmpty()) {
      val detail =
        duplicates.entries.joinToString("\n") { (version, files) ->
          "  - duplicate migration version $version: ${files.joinToString(", ")}"
        }
      throw GradleException(
        "Found ${duplicates.size} duplicate Flyway migration version(s). " +
          "Flyway will refuse to start ('Found more than one migration with version …') " +
          "and titan-server will CrashLoop on deploy. Renumber one file in each pair:\n" +
          detail
      )
    }

    logger.lifecycle(
      "checkMigrationVersions: ${names.size} migration file(s), no duplicate versions."
    )
  }
}
