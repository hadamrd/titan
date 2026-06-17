package io.adaptiq.titan.audit;

/**
 * Closed enum of audit action codes — the discriminator on the wire (string) and the
 * compile-checked surface on the server side (closes #478).
 *
 * <p>The UI mirrors this set as a discriminated union over the {@code action} string, so the
 * Details cell knows which fields to render per action. Adding a new action here MUST be paired
 * with a UI handler in {@code titan-ui/src/routes/audit/...}.
 *
 * <p>Targets are documented next to each constant — they correspond to {@link AuditTargetType}.
 */
public enum AuditAction {
  /** A new job row was inserted via POST /api/v1/jobs. target=JOB/{id}. */
  JOB_CREATE,
  /** An existing job's pipelineScript (or other field) was patched. target=JOB/{id}. */
  JOB_UPDATE,
  /** A job row was hard-deleted via DELETE /api/v1/jobs/{id}. target=JOB/{id}. */
  JOB_DELETE,
  /** A build was triggered (manual via POST /api/v1/jobs/{id}/builds). target=BUILD/{id}. */
  BUILD_TRIGGER,
  /**
   * A manual trigger was rejected by the per-(user, job) token-bucket rate limiter (closes #739).
   * target=JOB/{id} — the build was never created, so there is no build id to point at.
   */
  TRIGGER_RATE_LIMITED,
  /** A build was aborted via POST /api/v1/builds/{id}/cancel. target=BUILD/{id}. */
  BUILD_ABORT,
  /** A personal access token was minted. target=PAT/{id}. NEVER carry plaintext. */
  PAT_CREATE,
  /** A personal access token was revoked (soft-delete). target=PAT/{id}. */
  PAT_REVOKE,
  /**
   * A build's {@code display_name} was set by the {@code setBuildName:} pipeline step (#762).
   * target=BUILD/{id}. Details carry {@code {"buildId":…,"displayName":"…"}}. Emitted once per
   * successful step apply — a pipeline calling the step twice produces two audit rows even though
   * only the last write is observable in {@code titan.builds.display_name}.
   */
  BUILD_RENAMED,
  /**
   * A request authenticated with a scoped PAT was denied because the resolved job did not match the
   * token's {@code job_pattern} (closes #1082). target=PAT/{id}. Details carry {@code
   * {"jobId":…,"jobFullName":"…","pattern":"…","path":"…","method":"…"}} — never the bearer or any
   * credential. Emitted by {@code PatJobScopeFilter} immediately before the 403 response.
   */
  PAT_SCOPE_DENIED,
  /**
   * RBAC check on {@code POST /api/v1/builds/{id}/replay} or {@code /replay-from-failed} (closes
   * #1121). target=JOB/{jobId} — the build's owning job is the unit of authority. Details carry
   * {@code {"outcome":"ALLOWED|DENIED","resource":"job:…","remoteIp":"…"}}. Emitted on BOTH allow
   * and deny — the denial row is the audit signal an operator looks for.
   */
  BUILD_RERUN,
  /**
   * RBAC check on {@code PATCH /api/v1/jobs/{id}} when the pipeline script is being rewritten
   * (closes #1121). target=JOB/{id}. Details carry {@code {"outcome":"ALLOWED|DENIED",
   * "resource":"job:…","remoteIp":"…"}}. Emitted on BOTH allow and deny. Distinct from {@link
   * #JOB_UPDATE} (which records WHAT changed); {@code PIPELINE_EDIT} records WHO was authorized to
   * attempt the change.
   */
  PIPELINE_EDIT,
  /**
   * The reconcile loop replayed a webhook delivery that was dropped on the wire (closes #1118).
   * target=JOB/{jobId} when the recovered event matched a job, else target=JOB with null id.
   * Details carry {@code {"provider":"github","repo":"…","eventId":"…","gapSeconds":…}} —
   * gapSeconds is the wall-clock distance between the event and reconcile pickup. Emitted ONLY on
   * the reconcile path; webhook-hot-path dispatches are not audited at this granularity (that path
   * already produces a BUILD_TRIGGER row downstream).
   */
  SCM_WEBHOOK_RECOVERED,
  /**
   * An admin created a new SSO group → Titan role mapping row via {@code POST
   * /api/v1/orgs/{orgId}/sso/mappings} (closes #1136). target=SSO_MAPPING/{id}. Details carry
   * {@code {"orgId":"…","groupPath":"/…","role":"…"}}.
   */
  SSO_MAPPING_CREATE,
  /**
   * An admin changed the role on an existing SSO group → role mapping row via {@code PUT
   * /api/v1/orgs/{orgId}/sso/mappings/{id}} (closes #1136). target=SSO_MAPPING/{id}. Details carry
   * {@code {"orgId":"…","groupPath":"/…","before":"…","after":"…"}} — before/after captured so the
   * audit reader can reconstruct the role transition without joining against a snapshot table.
   */
  SSO_MAPPING_UPDATE,
  /**
   * An admin deleted an SSO group → role mapping row via {@code DELETE
   * /api/v1/orgs/{orgId}/sso/mappings/{id}} (closes #1136). target=SSO_MAPPING/{id}. Details carry
   * {@code {"orgId":"…","groupPath":"/…","role":"…"}} — the role at time of deletion is the value
   * the next login would have lost.
   */
  SSO_MAPPING_DELETE,
  /**
   * Scoped RBAC check emitted by {@link io.adaptiq.titan.auth.ScopedAuthz#requires} on every
   * {@code @RequiresRole}-gated call (closes #1131, epic #1114). target=JOB (the V20 target_type
   * enum has no ORG/REPO yet; the actual scope is in the details payload). Details carry {@code
   * {"outcome":"ALLOWED|DENIED","required":"…","effective":"…","scope":"ORG:…|REPO:…"}}. Emitted on
   * BOTH allow and deny — the deny row is the audit signal an operator looks for; the allow row is
   * the "who did what" answer to the customer story.
   */
  RBAC_CHECK,
  /**
   * An admin granted a scoped Titan role to a user via {@code PUT
   * /api/v1/admin/users/{userId}/roles} (epic #1114 item 6, closes #1236). target=USER/{userId}.
   * Details carry {@code {"scope":"ORG:…|REPO:…","role":"…","before":["…"],"after":["…"]}} — the
   * user's full role set on that scope before and after the grant, so the audit reader can
   * reconstruct the transition without joining a snapshot table.
   */
  ROLE_GRANT,
  /**
   * An admin revoked a user's Titan role(s) on a scope via {@code DELETE
   * /api/v1/admin/users/{userId}/roles/{scopeKind}/{scopeId}} (epic #1114 item 6, closes #1236).
   * target=USER/{userId}. Details carry {@code {"scope":"ORG:…|REPO:…","before":["…"],"after":[]}}
   * — the roles removed are the {@code before} set (revoke clears every role on the scope).
   */
  ROLE_REVOKE,
  /**
   * A build's per-(build, kind) state-transition counter crossed the <em>soft</em> cap (issue
   * #1074, V1-shippable-bar §3). target=BUILD/{id}. Details carry {@code {"buildId":…,
   * "kind":"ORCHESTRATE|BAKE|SYNTHESIZE|ADVANCE","count":…,"softCap":…}}. Emitted EXACTLY ONCE per
   * (build, kind) — on the crossing edge — by the controller's {@code QueueProcessor}; actor is
   * {@code "titan-controller"}. A healthy @golden pipeline never produces this row; its presence is
   * the early-warning signal that a build is re-entering one transition kind pathologically.
   */
  TRANSITION_CAP_WARN,
  /**
   * A build's per-(build, kind) state-transition counter crossed the <em>hard</em> cap and the
   * build was fail-closed with reason {@code "transition spam guard hit"} (issue #1074,
   * V1-shippable-bar §3). target=BUILD/{id}. Details carry {@code {"buildId":…,
   * "kind":"ORCHESTRATE|BAKE|SYNTHESIZE|ADVANCE","count":…,"hardCap":…}}. Emitted EXACTLY ONCE per
   * build — on the first kind to trip the hard cap — by the controller; actor is {@code
   * "titan-controller"}. This is the terminal audit signal an operator joins against the FAILED
   * build to confirm the spam-guard (not a user error) ended the run.
   */
  TRANSITION_CAP_HALT
}
