## Summary

<!-- One or two sentences. What does this PR do, and why? -->

## Roadmap chapter

<!-- Which chapter in docs/ROADMAP.md does this advance? Or "out-of-band". -->

## Changes

<!-- Bulleted list of the substantive changes. -->

-
-

## Test plan

<!-- How did you verify this? Paste the tail of `task ci:verify` output. -->

- [ ] `task ci:verify` → BUILD SUCCESSFUL (gradle check + integrationTest + ui verify). See `docs/operations/runbooks/ci-on-pr.md`.
- [ ] New tests added (if behaviour changed) — unit in `<module>/src/test/`, IT in `<module>/src/integrationTest/`.
- [ ] Manual smoke via `task dev:titan` (if rig- or UI-affecting).

## Branding & policy

- [ ] No former-employer internal names or infrastructure references introduced
- [ ] No new CDN dependencies (frontend assets stay bundled)
- [ ] No `.github/workflows/*.yml` added (CONSTITUTION NG13 — CI is the Titan self-pipeline, not GitHub Actions)
- [ ] Secrets stay out of git; `.env.example` only

## Notes for reviewers

<!-- Anything that would help review go faster? Areas of uncertainty? -->
