package io.adaptiq.titan.build;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Discriminated-union result of {@link BuildService#retryStage} — #744. The {@code type:} tag is
 * the Jackson discriminator: a typed-shape on the wire, no string-sniffing on the client.
 *
 * <p>Today only the {@link Applied} variant is materialised by the service — every precondition
 * failure throws a {@link io.adaptiq.titan.api.ApiNotFoundException} or {@link
 * RetryStagePreconditionException} so the REST layer's error mapping mirrors the existing replay
 * endpoint. The sealed sum is left open-ended so a future "dry-run" / "what-if" mode can add a
 * {@code Rejected} variant without a wire-shape bump.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = RetryStageOutcome.Applied.class, name = "applied")})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface RetryStageOutcome {

  /**
   * Successful retry — the stage and {@code resetNodeIds} (its DAG descendants, inclusive of the
   * stage itself) were reset (stages → {@code QUEUED}, steps → {@code PENDING} with a bumped
   * dispatch generation, #125) and the build was flipped back to {@code RUNNING}.
   *
   * @param buildId the build that was retried
   * @param stageId the failed stage that was retried
   * @param resetNodeIds the flow-node ids reset (stage + descendants); included in the response so
   *     the UI can update its DAG view without a re-fetch
   * @param taskId the id of the enqueued {@code ORCHESTRATE/ADVANCE} task; the worker will pick it
   *     up on the next tick and dispatch the first step
   */
  record Applied(
      long buildId, @NonNull String stageId, @NonNull List<String> resetNodeIds, long taskId)
      implements RetryStageOutcome {
    @Override
    public String type() {
      return "applied";
    }
  }

  /** The Jackson discriminator tag — also exposed as a method for Java callers. */
  @NonNull
  String type();
}
