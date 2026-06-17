package io.adaptiq.titan.api.dto;

/**
 * Body of {@code POST /api/v1/pipeline/validate} (closes #745).
 *
 * <p>The only field is the raw pipeline YAML. The server validates by feeding the string through
 * the same {@link io.adaptiq.titan.flow.parser.TitanYamlParser} used by the bake step, so the
 * verdict the UI shows is identical to the verdict the engine will reach at build time. There is
 * intentionally no {@code jobId} / {@code fullName} on this DTO — validate is a pure parser call
 * with no DB write and no per-job context.
 *
 * @param yaml the raw pipeline YAML; {@code null} or blank triggers a structured error in the
 *     response (not a 4xx), so the UI can render the message inline.
 */
public record PipelineValidateRequest(String yaml) {}
