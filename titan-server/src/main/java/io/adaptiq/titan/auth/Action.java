package io.adaptiq.titan.auth;

/**
 * Closed enum of privileged actions guarded by {@link Authz#requires}.
 *
 * <p>v1 ships exactly the two destructive admin surfaces called out in #1121:
 *
 * <ul>
 *   <li>{@link #BUILD_RERUN} — {@code POST /api/v1/builds/{id}/replay} and {@code
 *       /replay-from-failed}. Re-queues a build of the parent's job; can re-deploy prod.
 *   <li>{@link #PIPELINE_EDIT} — {@code PATCH /api/v1/jobs/{id}}; rewrites the job's pipeline YAML
 *       which then runs arbitrary code on workers as the next build.
 * </ul>
 *
 * <p>Adding a new {@code Action} requires a paired entry in {@link Authz}'s policy table — the
 * exhaustive switch in {@link Authz#isAllowed} ensures the compile fails on missing rows.
 */
public enum Action {
  BUILD_RERUN,
  PIPELINE_EDIT
}
