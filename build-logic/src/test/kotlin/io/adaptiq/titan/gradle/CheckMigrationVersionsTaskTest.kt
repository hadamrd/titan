package io.adaptiq.titan.gradle

import java.io.File
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises the Gradle adapter [CheckMigrationVersionsTask] end-to-end (real task instance, real
 * [org.gradle.api.file.ConfigurableFileCollection] input), mirroring the issue's acceptance
 * criteria: two `V99` files fail with a clear message; a unique set passes.
 */
class CheckMigrationVersionsTaskTest {

  private fun newTask() =
    ProjectBuilder.builder()
      .build()
      .tasks
      .create("checkMigrationVersions", CheckMigrationVersionsTask::class.java)

  private fun touch(dir: File, name: String): File =
    File(dir, name).apply { writeText("-- $name\n") }

  @Test
  fun `fails with a clear message when two migrations share version 99`(@TempDir dir: File) {
    val task = newTask()
    task.migrationFiles.from(touch(dir, "V99__a.sql"), touch(dir, "V99__b.sql"))

    val ex = assertThrows<GradleException> { task.check() }

    // The operator must be able to read the offending version straight off the line.
    assertTrue(
      ex.message!!.contains("duplicate migration version 99"),
      "message should name the colliding version, was: ${ex.message}",
    )
    assertTrue(ex.message!!.contains("V99__a.sql"))
    assertTrue(ex.message!!.contains("V99__b.sql"))
  }

  @Test
  fun `passes when every migration version is unique`(@TempDir dir: File) {
    val task = newTask()
    task.migrationFiles.from(
      touch(dir, "V1__init.sql"),
      touch(dir, "V2__a.sql"),
      touch(dir, "V99__only.sql"),
    )

    assertDoesNotThrow { task.check() }
  }

  @Test
  fun `passes for an empty migration set`() {
    val task = newTask()
    // No files added at all — the guard must not fire.
    assertDoesNotThrow { task.check() }
  }
}
