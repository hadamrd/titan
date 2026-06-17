package io.adaptiq.titan.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.expr.ExpressionException;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.FlowNodeDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Classifies and applies the {@code setBuildName:} pipeline step (#762) — a controller-native,
 * worker-free step that stamps {@link io.adaptiq.titan.store.rows.BuildRow#displayName} on the
 * current build. Sibling of {@link ApprovalResolver} (parsing) + the orchestrator's wait/approval
 * park paths (immediate-side-effect-then-SUCCESS).
 *
 * <p>Scalar shorthand ({@code setBuildName: "deploy-${{ params.BRANCH }}"}) and object form ({@code
 * setBuildName: {name: "..."}}) are both accepted — the parser folds a bare scalar into the
 * conventional {@code value} key (design/42 §4.6), so {@link #apply} reads either {@code name} or
 * {@code value}.
 *
 * <p>Template resolution is the engine's standard {@link TemplateResolver#resolveArguments} pass
 * (same as approval/wait); a malformed {@code ${{ … }}} reference fails the node CONFIG with the
 * resolver's error message so the user sees which reference broke.
 *
 * <p>Idempotent on replay: re-running the step with the same resolved value rewrites the same
 * column value (last-write wins). When the build status is already terminal ({@code ABORTED} /
 * {@code CANCELLED} / {@code FAILED}) the side effect is skipped but the node still completes
 * {@code SUCCESS} so the surrounding DAG drains cleanly — a setBuildName step can never block a
 * pipeline that is already shutting down.
 *
 * <p>Audit: each successful apply emits a {@link AuditAction#BUILD_RENAMED} row. Two consecutive
 * applies produce two audit rows (last-write wins on the column; the audit log preserves history).
 */
public final class SetBuildNameResolver {

  /** Hard ceiling on the persisted name length — matches the column width in V27. */
  public static final int MAX_LENGTH = 200;

  /** The single canonical descriptor id for the step. */
  public static final String DESCRIPTOR_ID = "setBuildName";

  private static final Logger LOGGER = Logger.getLogger(SetBuildNameResolver.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> TERMINAL_BUILD_STATUSES =
      Set.of("SUCCESS", "FAILED", "ABORTED", "CANCELLED");

  private SetBuildNameResolver() {}

  /**
   * Audit emit seam — invoked once per successful apply with {@code (buildId, resolvedName)}. Kept
   * a {@link java.util.function.BiConsumer} rather than a hard {@code AuditService} dependency so
   * raw-JDBI ITs can verify audit emit without spinning a CDI scope.
   *
   * <p>Production wiring lives in {@code TitanStores}-aware glue: pass {@code (id, name) ->
   * audit.recordAs("pipeline", BUILD_RENAMED, BUILD, String.valueOf(id), details)} from the
   * orchestrator's CDI seam.
   */
  @FunctionalInterface
  public interface AuditEmitter {
    void emit(long buildId, @NonNull String resolvedName);
  }

  /** Whether {@code descriptorId} is the controller-native setBuildName step. */
  public static boolean isSetBuildName(@Nullable String descriptorId) {
    return DESCRIPTOR_ID.equals(descriptorId);
  }

  /**
   * Outcome of one {@link #apply} call — drives the orchestrator's CAS on the flow node.
   *
   * @param ok whether the step succeeded (true ⇒ node SUCCESS, false ⇒ node FAILED CONFIG)
   * @param resultJson the JSON blob to stamp on {@code flow_nodes.result_json}
   * @param failureReason populated when {@code ok} is false; null otherwise
   * @param resolvedName the value written to {@code builds.display_name}; null when {@code ok} is
   *     false OR when the build was terminal and the write was skipped
   */
  public record Outcome(
      boolean ok,
      @NonNull String resultJson,
      @Nullable String failureReason,
      @Nullable String resolvedName) {}

  /**
   * Apply the step: resolve {@code ${{ … }}} references against {@code ctx}, validate the name,
   * persist it. Pure side-effect API — the orchestrator owns the {@code flow_nodes} CAS.
   *
   * @param stores DAO bundle (read build row, write display name)
   * @param audit emitter for the BUILD_RENAMED audit event; may be {@code null} in test contexts
   *     where no CDI is wired
   * @param buildId the build whose row this step targets
   * @param step the pipeline step model — only {@link StepModel#getArguments()} is read
   * @param ctx the {@code ${{ params/steps/pipeline }}} resolution context
   */
  @NonNull
  public static Outcome apply(
      @NonNull TitanStores stores,
      @Nullable AuditEmitter audit,
      long buildId,
      @NonNull StepModel step,
      @NonNull Map<String, Object> ctx) {
    Map<String, Object> resolvedArgs;
    try {
      resolvedArgs = TemplateResolver.resolveArguments(step.getArguments(), ctx);
    } catch (ExpressionException badRef) {
      // Malformed ${{ params.MISSING }} or unknown step outputs: fail the node CONFIG with a
      // pointed message so the user can fix the reference in their YAML.
      return fail("setBuildName: failed to resolve template references: " + describe(badRef));
    } catch (RuntimeException unexpected) {
      return fail("setBuildName: template resolution failed: " + describe(unexpected));
    }

    Object raw =
        resolvedArgs.containsKey("name") ? resolvedArgs.get("name") : resolvedArgs.get("value");
    if (raw == null) {
      return fail(
          "setBuildName: requires a name — `setBuildName: \"my-name\"` or "
              + "`setBuildName: {name: \"…\"}`");
    }
    String name = String.valueOf(raw).trim();
    if (name.isEmpty()) {
      return fail("setBuildName: name is blank after template resolution");
    }
    if (name.length() > MAX_LENGTH) {
      // Hard fail — never silently truncate (the brief is explicit).
      return fail(
          "setBuildName: name length "
              + name.length()
              + " exceeds the maximum of "
              + MAX_LENGTH
              + " characters");
    }

    BuildRow build = stores.builds().findById(buildId).orElse(null);
    boolean buildTerminal = build != null && TERMINAL_BUILD_STATUSES.contains(build.status);

    if (!buildTerminal) {
      stores.builds().setDisplayName(buildId, name);
      writeAudit(audit, buildId, name);
      LOGGER.log(
          Level.INFO, "[titan] build {0}: setBuildName -> {1}", new Object[] {buildId, name});
    } else {
      LOGGER.log(
          Level.INFO,
          "[titan] build {0}: setBuildName skipped (build status={1}); node still SUCCESS",
          new Object[] {buildId, build == null ? "UNKNOWN" : build.status});
    }

    ObjectNode result = JSON.createObjectNode();
    result.put("displayName", name);
    result.put("applied", !buildTerminal);
    return new Outcome(true, result.toString(), null, buildTerminal ? null : name);
  }

  private static Outcome fail(@NonNull String reason) {
    ObjectNode result = JSON.createObjectNode();
    result.put("error", reason);
    return new Outcome(false, result.toString(), reason, null);
  }

  private static void writeAudit(@Nullable AuditEmitter audit, long buildId, @NonNull String name) {
    if (audit == null) {
      return;
    }
    try {
      audit.emit(buildId, name);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] audit BUILD_RENAMED emit failed for build {0}: {1}",
          new Object[] {buildId, e.getMessage()});
    }
  }

  @NonNull
  private static String describe(@NonNull Throwable t) {
    String msg = t.getMessage();
    return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
  }

  /**
   * Test seam: drive the resolver against a stores bundle without an audit emitter. Used by ITs
   * that boot raw JDBI on a Testcontainer (no CDI scope, so no {@link AuditService} bean).
   */
  @NonNull
  public static Outcome apply(
      @NonNull TitanStores stores,
      long buildId,
      @NonNull StepModel step,
      @NonNull Map<String, Object> ctx) {
    return apply(stores, (AuditEmitter) null, buildId, step, ctx);
  }

  /**
   * Orchestrator entry point: do the apply, write the result_json + CAS the flow node SUCCESS /
   * FAILED. Encapsulates the orchestrator-side bookkeeping mirror of {@link
   * io.adaptiq.titan.flow.TitanOrchestrator#parkApprovalStep} so the orchestrator's caller is a
   * single line.
   */
  public static void applyAndComplete(
      @NonNull TitanStores stores,
      @Nullable AuditEmitter audit,
      long buildId,
      @NonNull FlowNodeDao flowNodes,
      @NonNull StepModel step,
      @NonNull Map<String, Object> ctx) {
    Outcome out = apply(stores, audit, buildId, step, ctx);
    Instant now = Instant.now();
    if (out.ok()) {
      flowNodes.compareAndSetStatus(
          buildId, step.getId(), "PENDING", "SUCCESS", null, now, null, out.resultJson());
    } else {
      flowNodes.updateFailure(buildId, step.getId(), "CONFIG", out.failureReason());
      flowNodes.compareAndSetStatus(
          buildId, step.getId(), "PENDING", "FAILED", null, now, null, out.resultJson());
    }
  }
}
