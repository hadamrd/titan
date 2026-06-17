package io.adaptiq.titan.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker-side pipeline synthesis (design/38 §3, Stage 1b).
 *
 * <p>design/38 un-fuses synthesis from execution and moves synthesis off the controller: "synthesis
 * does not run on the controller — it runs as the build's first task, on a worker". A {@code
 * SYNTHESIZE} task claimed from the shared {@code synthesis} queue carries the build's pipeline
 * script in its payload; this handler parses it into a {@link PipelineModel} and writes the model
 * JSON to {@code titan.builds.pipeline_model_json}. The controller's {@code BAKE} phase then
 * consumes that model — unchanged.
 *
 * <p>Synthesis is pure deserialisation: Titan YAML is the only accepted form, parsed by {@link
 * TitanYamlParser#parseAndValidate} from {@code titan-pipeline-model}. No code runs. Cross-pipeline
 * library code reuse happens at <em>step execution</em> time through the {@code libraryCall}
 * handler (design/53), not at synthesis.
 *
 * <p><strong>Retry-safety.</strong> Synthesis is a re-deliverable task (design/30). A re-delivered
 * {@code SYNTHESIZE} for a build whose {@code pipeline_model_json} is already set is a no-op — a
 * pure synthesis re-runs to the identical model, and {@link WorkerDb#writePipelineModelJson}'s
 * {@code WHERE pipeline_model_json IS NULL} guard makes even a concurrent re-delivery safe.
 */
final class SynthesisTaskHandler {

  private static final Logger LOG = LoggerFactory.getLogger(SynthesisTaskHandler.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final WorkerDb db;

  SynthesisTaskHandler(WorkerDb db) {
    this.db = db;
  }

  /** Outcome of running a synthesis task — mirrors {@link TaskExecutor.Result}. */
  record Result(boolean success, String resultJson) {}

  /**
   * Run one claimed {@code SYNTHESIZE} task: parse the payload's pipeline script into a {@link
   * PipelineModel} and persist it. A bad pipeline (invalid YAML, broken DAG) fails the task — the
   * controller's synthesis coordinator then marks the build {@code FAILED}.
   */
  Result run(WorkerDb.ClaimedTask task) {
    DbLogSink log = new DbLogSink(db, task.taskToken());
    try {
      JsonNode payload = JSON.readTree(task.payloadJson());
      long buildId = payload.path("buildId").asLong();
      if (buildId == 0) {
        return fail(log, "SYNTHESIZE payload missing buildId");
      }
      String pipelineScript = payload.path("pipelineScript").asText(null);
      if (pipelineScript == null || pipelineScript.isBlank()) {
        return fail(log, "SYNTHESIZE payload missing pipelineScript");
      }

      // Retry-safe: an earlier delivery may already have synthesised this build.
      if (db.hasPipelineModel(buildId)) {
        log.system("build " + buildId + " already synthesised — skipping (retry-safe)");
        log.finish("synthesis already done");
        return new Result(true, "{\"status\":\"ALREADY_SYNTHESIZED\"}");
      }

      log.system("synthesising pipeline for build " + buildId);
      PipelineModel model = TitanYamlParser.parseAndValidate(pipelineScript);
      String modelJson = JSON.writeValueAsString(model);

      boolean wrote = db.writePipelineModelJson(buildId, modelJson);
      String note =
          wrote
              ? "synthesised "
                  + model.getStages().size()
                  + " stage(s), "
                  + model.getGates().size()
                  + " gate(s), "
                  + model.getPreconditions().size()
                  + " precondition(s)"
              : "synthesis raced — model already written by a peer";
      log.system(note);
      log.finish("synthesis completed");
      LOG.info("build {} synthesised (wrote={})", buildId, wrote);
      return new Result(true, "{\"status\":\"OK\"}");
    } catch (Exception e) {
      // Includes PipelineParseException — a bad pipeline definition.
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      return fail(log, "synthesis failed: " + cause.getMessage());
    }
  }

  private Result fail(DbLogSink log, String message) {
    LOG.warn("synthesis task failed: {}", message);
    log.finish(message);
    return new Result(false, JSON.createObjectNode().put("error", message).toString());
  }
}
