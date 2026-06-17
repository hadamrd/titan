# Produced is not in effect

*A pattern noticed across one session of work on the Titan pipeline editor — 2026-05-19.
Save-validation and schema autocomplete were added; five separate problems came up
along the way. They presented as five unrelated bugs. They were one bug, wearing
five coats.*

## The five

| What was *produced* | What was actually *in effect* | The stale symptom |
|---|---|---|
| The parser rejected the YAML for the right reason (`unknown key 'envVar'`) | The editor displayed an older error (`'stage' is present but null`) | A "validator bug" report — the validator was correct |
| `doCheckPipelineScript` computed and *displayed* an error | Nothing *blocked* the save — `submit()` stored the value unconditionally | Invalid pipelines saved fine, failed later at bake time |
| `task build` produced a fresh artifact with the new code | The running server still served the build it booted with | Code changes "didn't take effect" after deploy |
| `vite build` produced a new editor bundle | The browser served the old bundle from cache (fixed URL, weak headers) | A deployed fix that wasn't visible |
| A subagent reported "changed `${rootURL}` → `${resURL}`" | The file still said `${rootURL}` | A confident "done" that wasn't |

## The pattern

Every one of these is a gap between **a thing being produced** and **that thing being
in effect** — and, sitting inside that gap, a *representation* that looks authoritative
while being stale:

- A validation message represents "is this valid?" — it goes stale the instant the document changes.
- A `doCheck` result represents an answer — it is *advisory*; it never had the power to enforce.
- A built artifact represents "the latest code" — until something loads it, it is just a file on disk.
- A subagent's final message represents work done — it is not the work.

The gap is where the bugs live, precisely because the representation inside it is the
thing you instinctively trust. The user trusted the on-screen error and reported a
validator bug. The operator trusted "deploy succeeded" and tested stale code. The
subagent's "done" was trusted until Playwright showed the `<script>` tag unchanged.

## The discipline

The fix, every time, was the same move: **stop reading the representation; measure the
thing at its point of effect.**

- Don't read the displayed error — re-run the parser on the exact input. (It was correct.)
- Don't trust that `doCheck` shows red — check whether `submit()` can actually throw. (It couldn't — making it throw *became* the fix.)
- Don't trust "deploy succeeded" — exec into the container and check which plugin is exploded.
- Don't trust the built bundle — fetch the URL the browser actually requests.
- Don't trust a subagent's "done" — diff the file.

"Verify before acting" is already our practice. This session sharpens *where*: verify
not at the point a thing is **produced**, but at the point it takes **effect**. Those
are different places, and the distance between them is exactly the bug.

## Two design corollaries

- **If a check is meant to enforce, it must sit on the enforcing path.** A
  validator that only computes and *displays* an error is structurally advisory;
  the only real save-blocker is one that throws on the write path itself. A
  "real" check placed anywhere else is decoration — it informs, it never prevents.
- **A produced artifact needs an invalidation story, or it will be served stale.** The
  editor bundle had a fixed URL; the fix was `${resURL}`, whose per-restart hash *forces*
  the gap shut. Cache-busting is not a nicety — it is the mechanism that makes "produced"
  and "in effect" the same thing.

## The one-line version

A bug report, a green deploy, and a subagent's "done" are all *claims about state*.
Reproduction, a container `exec`, and a `diff` are *state*. When they disagree, the
claim is the bug.
