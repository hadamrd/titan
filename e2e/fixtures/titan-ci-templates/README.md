# `titan-ci-templates` — vendored shared-library contract mirror

Byte-identical mirror of the two contract files in
[`hadamrd/titan-ci-templates`](https://github.com/hadamrd/titan-ci-templates) at tag
**`v1`** (commit `c7ab96856578acb22475ced8869b2b0c80fdb29b`, vendored 2026-07-08,
issue #48):

| File | Contract |
|---|---|
| `vars/ci.groovy` | must contain spec-47's `LIBRARY_ONLY_MARKER` (`TITAN_LIB_MARKER_buildAndTest_v1`) |
| `VERSION` | trimmed content derives spec-48's `MULTIREPO_MARKER` (`MULTIREPO_OK_<VERSION>`) |

`00-library-drift-guard.spec.ts` reconciles the spec constants against THESE files
(hermetic — no runtime GitHub fetch, see #48). Live-repo drift is still caught every
smoke run because the golden 47/48 builds clone the live `titan-ci-templates@v1` on
the worker and fail if the markers moved.

If the upstream `@v1` contract legitimately changes: update the specs, re-import both
files, and bump the pinned commit above.

```sh
gh api "repos/hadamrd/titan-ci-templates/contents/vars/ci.groovy?ref=v1" --jq .content \
  | base64 -d > e2e/fixtures/titan-ci-templates/vars/ci.groovy
gh api "repos/hadamrd/titan-ci-templates/contents/VERSION?ref=v1" --jq .content \
  | base64 -d > e2e/fixtures/titan-ci-templates/VERSION
```
