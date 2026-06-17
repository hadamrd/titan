/**
 * Bitbucket Cloud PR-reporting smoke (issue #1117) — PLACEHOLDER, intentionally skipped.
 *
 * The outbound Bitbucket adapter (commit build-status, sticky PR summary
 * comment, inline review comments) is unit- + integration-tested in
 * `titan-server` (BitbucketReportingFlowIT). A true end-to-end check needs a
 * LIVE Bitbucket Cloud sandbox workspace + repo + app password + a real PR,
 * which the CI rig does not provision. This spec documents the manual smoke an
 * operator runs against a sandbox until that infrastructure exists.
 *
 * Manual smoke (run once against a Bitbucket Cloud sandbox):
 *   1. Seed a credential in scope `bitbucket-webhook` as `username:app_password`.
 *   2. Connect a sandbox repo; open a PR that touches `src/foo.py`.
 *   3. Trigger a Titan build for that PR (commitSha + workspace + repoSlug +
 *      prId + bitbucketCredentialsId in the trigger meta).
 *   4. Assert on the PR, in order:
 *        - a `ci/titan` commit build-status flips INPROGRESS → SUCCESSFUL;
 *        - exactly ONE sticky summary comment (marker
 *          `<!-- titan:pr-summary:<jobId> -->`), edited in place on rerun;
 *        - inline review comments on the `sast` findings' lines;
 *        - a finding on a renamed/absent file appears in the summary, not as a
 *          crash.
 *
 * Unskip when the rig grows a Bitbucket sandbox seed surface.
 */
import { test } from '../fixtures';

test.describe('bitbucket pr-reporting smoke (manual until a live sandbox exists)', () => {
  test.skip(
    true,
    'needs a live Bitbucket Cloud sandbox (workspace + app password + PR); covered by BitbucketReportingFlowIT until then',
  );

  test('commit status + sticky summary + inline review land on a real Bitbucket PR', async () => {
    // Intentionally empty — see file header for the manual smoke procedure.
  });
});
