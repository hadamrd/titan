package io.adaptiq.titan.auth;

/**
 * Titan RBAC role constants.
 *
 * <p>Roles map 1:1 to Keycloak realm roles. Every {@code @RolesAllowed} annotation in the API
 * resources references one of these constants to keep the role strings in a single source of truth.
 *
 * <ul>
 *   <li>{@link #READ_JOB} — list/get jobs, builds, nodes, and logs.
 *   <li>{@link #TRIGGER_BUILD} — POST trigger-build and cancel-build.
 *   <li>{@link #EDIT_PIPELINE} — placeholder for future pipeline-edit endpoints.
 *   <li>{@link #APPROVE_GATE} — placeholder for future gate-approval endpoints.
 *   <li>{@link #READ_AUDIT} — view the audit log without needing full {@link #ADMIN}.
 *   <li>{@link #ABORT_BUILD} — cancel a running build without needing {@link #TRIGGER_BUILD}.
 *   <li>{@link #OPERATE_WORKER} — drain/undrain workers without needing full {@link #ADMIN}.
 *   <li>{@link #ADMIN} — all of the above (super-role).
 * </ul>
 */
public final class Roles {

  public static final String READ_JOB = "READ_JOB";
  public static final String TRIGGER_BUILD = "TRIGGER_BUILD";
  public static final String EDIT_PIPELINE = "EDIT_PIPELINE";
  public static final String APPROVE_GATE = "APPROVE_GATE";
  public static final String READ_AUDIT = "READ_AUDIT";
  public static final String ABORT_BUILD = "ABORT_BUILD";
  public static final String OPERATE_WORKER = "OPERATE_WORKER";

  /**
   * Replay an existing build without needing {@link #TRIGGER_BUILD}. Power-user role for CI triage
   * / flake retries — a holder can re-run a finished build (optionally from a chosen node) but
   * cannot start a fresh build of an arbitrary job.
   */
  public static final String REPLAY_BUILD = "REPLAY_BUILD";

  /**
   * Manage Titan's encrypted credentials store: create / rotate / delete entries and trigger KEK
   * re-wrap. A dedicated role for secrets operators so credential lifecycle doesn't require {@link
   * #EDIT_PIPELINE} (which grants pipeline-DSL authoring) or full {@link #ADMIN}. Read of
   * credential metadata still goes via {@link #EDIT_PIPELINE} or {@link #ADMIN}.
   */
  public static final String MANAGE_CREDENTIALS = "MANAGE_CREDENTIALS";

  /**
   * Decide a parked {@code approval:} step (#715) — POST {@code /api/v1/approvals/{id}/approve} or
   * {@code /reject}. Distinct from {@link #APPROVE_GATE} (which targets the older stage-level
   * {@code gate:} node, design/29 §7.1) so an operator with rights to one human-signoff surface
   * doesn't implicitly get the other. The endpoint additionally enforces that the caller's subject
   * is in the approval's approvers list (or holds {@link #ADMIN}).
   */
  public static final String APPROVE_BUILD = "APPROVE_BUILD";

  public static final String ADMIN = "ADMIN";

  private Roles() {}
}
