package io.adaptiq.titan.trigger.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.adaptiq.titan.api.PostgresItProfile;
import io.adaptiq.titan.api.PostgresTestResource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Wave-1 smoke IT for the ported trigger framework — real PostgreSQL via Testcontainers, real
 * {@link TitanStores}, real Quarkus context. Inserts a job whose {@code config_json} has a {@code
 * "* * * * *"} cron trigger past first-sight, drives one {@link TriggerEngine#tickAt} manually, and
 * asserts a {@code titan.builds} row was enqueued.
 *
 * <p>Pins the production end-to-end path: {@code DbTriggerSubsystem} enumerates the job, parses the
 * cron from {@code config_json}, the dispatch algorithm finds it due, {@code DbTriggerStore} opens
 * a locked transaction, and {@code DbTriggerScope.fire()} inserts a {@code builds} + {@code
 * task_queue} row.
 */
@QuarkusTest
@TestProfile(PostgresItProfile.class)
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class CronTriggerSmokeIT {

  @Inject TitanStores stores;

  @Inject TriggerEngine engine;

  @Test
  void cronTriggerEnqueuesABuild() {
    // ── Seed: a job with a cron trigger whose last_fired_at is already 2 min ago ─
    JobRow row = new JobRow();
    row.fullName = "trigger-smoke-it/" + System.nanoTime();
    row.displayName = "trigger-smoke-it";
    row.folderPath = null;
    row.pipelineScript = "stages { stage 'noop' { echo 'x' } }";
    // The DbTriggerSubsystem parses triggers out of config_json.triggers[] using TriggerCodec.
    row.configJson =
        "{\"triggers\":[{\"type\":\"cron\",\"id\":\"smoke-t\",\"spec\":\"* * * * *\"}]}";
    row.createdBy = "smoke";
    row.enabled = true;

    long jobId = stores.jobs().insert(row);

    // Stamp last_fired_at to 2 minutes ago via the trigger row so the first tick *fires*
    // (otherwise design/50's first-sight rule would only stamp the baseline and skip).
    Instant twoMinutesAgo = Instant.now().minus(Duration.ofMinutes(2));
    stores.withTransaction(
        conn -> {
          stores.jobTriggers().recordState(conn, jobId, "smoke-t", twoMinutesAgo, null);
          return null;
        });

    int buildsBefore = stores.builds().listByJob(jobId).size();

    // ── Drive one tick manually with `now` so the cron evaluation is deterministic ─
    engine.tickAt(Instant.now());

    int buildsAfter = stores.builds().listByJob(jobId).size();
    assertFalse(
        buildsAfter == buildsBefore,
        "DbTriggerSubsystem + DbTriggerStore + DbTriggerScope must enqueue a build for a"
            + " cron-triggered job that was already past first-sight (had a last_fired_at)");
  }
}
