package io.adaptiq.titan.api;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonLocation;
import io.adaptiq.titan.api.dto.PipelineModelSummary;
import io.adaptiq.titan.api.dto.PipelineStageSummaryDto;
import io.adaptiq.titan.api.dto.PipelineTriggerSummaryDto;
import io.adaptiq.titan.api.dto.PipelineValidateRequest;
import io.adaptiq.titan.api.dto.PipelineValidationError;
import io.adaptiq.titan.api.dto.PipelineValidationResult;
import io.adaptiq.titan.api.dto.ProblemJson;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.TriggerModel;
import io.adaptiq.titan.flow.parser.PipelineParseException;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;

/**
 * Jakarta REST resource: {@code POST /api/v1/pipeline/validate} — preview/validate a pipeline YAML
 * without persisting anything (closes #745).
 *
 * <p>Closes the author-feedback loop: today users <em>git push, wait for build, see parse
 * error</em> — minutes per iteration. This endpoint runs the same {@link
 * TitanYamlParser#parse(String)} the bake step uses, returns a typed {@link
 * PipelineValidationResult}, and never writes the database.
 *
 * <p><strong>Auth.</strong> Anyone who can view a pipeline can validate one: the role set is {@link
 * Roles#READ_JOB} ∪ {@link Roles#TRIGGER_BUILD} ∪ {@link Roles#EDIT_PIPELINE} ∪ {@link
 * Roles#ADMIN}.
 *
 * <p><strong>Bounded.</strong> The request body's {@code yaml} field is capped at {@link
 * #MAX_YAML_BYTES} (1 MiB) — larger bodies surface as HTTP 413, never reach the parser. The
 * response summary is intentionally lossy (stage name + step count, trigger type + expression) so
 * it stays O(stages + triggers), not O(model).
 */
@Path("/api/v1/pipeline/validate")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.EDIT_PIPELINE, Roles.ADMIN})
public class PipelineValidateApi {

  /** Hard cap on the request {@code yaml} payload — 1 MiB (closes #745 DoS guard). */
  static final int MAX_YAML_BYTES = 1024 * 1024;

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RequiresRole(role = Authz.TitanRole.VIEWER, kind = ScopeKind.ORG, scopeId = "global")
  public Response validate(PipelineValidateRequest req) {
    if (req == null || req.yaml() == null) {
      // Body is required at all — a bare-bones contract; without yaml there's nothing to validate.
      throw new ApiBadRequestException("field 'yaml' is required");
    }

    // Size cap — RFC 7807 problem+json at 413 so the UI can surface a precise message.
    // Note: we measure UTF-8 byte length, not String#length, so multi-byte chars are bounded
    // by the same wire-budget the controller's HTTP front-end already enforces.
    byte[] bytes = req.yaml().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length > MAX_YAML_BYTES) {
      ProblemJson body =
          new ProblemJson(
              "about:blank",
              "Payload Too Large",
              413,
              "yaml exceeds " + MAX_YAML_BYTES + " bytes (got " + bytes.length + ")",
              null);
      return Response.status(413).type("application/problem+json").entity(body).build();
    }

    // Parse via the same code path the bake step uses. We do NOT call parseAndValidate (DAG
    // validation) here — the brief is a "preview/validate" of the YAML shape; a downstream
    // dependsOn cycle is reported by the bake step. (If the brief later asks for full DAG
    // verdict the swap is one-line; the DTO already accommodates structural errors.)
    PipelineModel model;
    try {
      model = TitanYamlParser.parse(req.yaml());
    } catch (PipelineParseException e) {
      return Response.ok(PipelineValidationResult.fail(toErrors(e))).build();
    } catch (RuntimeException e) {
      // Defensive: any leak from the parser other than the typed exception still surfaces as a
      // structured 200 verdict — the UI must never see a 500 for a parse-shape problem.
      return Response.ok(
              PipelineValidationResult.fail(
                  List.of(
                      PipelineValidationError.of(
                          e.getMessage() == null ? "parse failed" : e.getMessage()))))
          .build();
    }

    return Response.ok(PipelineValidationResult.ok(summarise(model))).build();
  }

  /**
   * Walk the {@link PipelineParseException} cause chain looking for a Jackson location. The parser
   * wraps the raw {@link JacksonException} (see {@code TitanYamlParser#parseInternal}); we mine the
   * {@link JsonLocation} so the UI can render an inline marker next to the offending line.
   *
   * <p>Falls back to a single locationless error when no Jackson cause is present — typical of the
   * parser's own structural messages ({@code "pipeline definition is empty"}, {@code
   * "'pipeline.stages' must be a non-empty array"}, …) which already cite the offending key.
   */
  private static List<PipelineValidationError> toErrors(PipelineParseException e) {
    List<PipelineValidationError> errors = new ArrayList<>();
    String message = e.getMessage() == null ? "invalid pipeline" : e.getMessage();

    for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
      if (c instanceof JacksonException je) {
        JsonLocation loc = je.getLocation();
        if (loc != null && loc.getLineNr() > 0) {
          errors.add(new PipelineValidationError(loc.getLineNr(), loc.getColumnNr(), message));
          return errors;
        }
      }
    }
    errors.add(PipelineValidationError.of(message));
    return errors;
  }

  /**
   * Project a successfully parsed {@link PipelineModel} into the bounded wire summary — stage names
   * + per-stage step count, trigger types + cron expressions. Never echoes step arguments, agent
   * labels, env, or any field that could carry user-supplied bulk.
   */
  private static PipelineModelSummary summarise(PipelineModel model) {
    List<PipelineStageSummaryDto> stages = new ArrayList<>(model.getStages().size());
    for (StageModel s : model.getStages()) {
      stages.add(new PipelineStageSummaryDto(s.getName(), s.getSteps().size()));
    }
    List<PipelineTriggerSummaryDto> triggers = new ArrayList<>(model.getTriggers().size());
    for (TriggerModel t : model.getTriggers()) {
      if (t.getCron() != null) {
        triggers.add(PipelineTriggerSummaryDto.cron(t.getCron()));
      } else if (t.getGithub() != null) {
        triggers.add(PipelineTriggerSummaryDto.github());
      }
    }
    return new PipelineModelSummary(stages, triggers);
  }
}
