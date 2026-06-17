# Triggers and discovery

How builds get started: SCM webhooks, the `.titan/pipelines/` discovery
convention, and the one-build-per-push policy.

## Two ways a build starts

A build is either requested directly or fired by a trigger:

- **Directly** — a manual run from the UI or API, or a seed script.
- **By a trigger** — a schedule fires, or an SCM event arrives.

Triggers are evaluation-driven. Each trigger decides an outcome — `FIRE`,
`SKIP`, or `DEFER` — and the firing engine enqueues a build on `FIRE`. Two
things ask the engine to evaluate: a periodic tick, and an inbound event.

## Schedules

A `CronTrigger` fires on a cron expression. The schedule engine ticks
periodically and asks each schedule whether it is due. The cron evaluation is
catch-up correct: a schedule missed during a server outage fires once, late,
rather than not at all — with a clamp so a long outage cannot produce a backlog
of runs. It is multi-controller-safe: firing and recording the last-fired time
happen under a lock against the database, so two servers cannot double-fire the
same schedule.

## SCM events

When an event should start a build *now* — a push, a pull request — polling is
too slow. Titan accepts webhooks from the SCM and evaluates immediately. The
webhook endpoint verifies the delivery, builds an event, and runs an evaluation
pass with that event in scope. An SCM trigger keys off the event; a cron
trigger ignores it.

Webhook endpoints exist for GitHub, GitLab, Bitbucket, and Pulsar. Each
verifies the delivery (HMAC signature for the SCM providers), resolves which
pipelines the event affects, and enqueues their builds.

A `TriggerSource` extension point covers non-HTTP ingress — a long-lived
listener (a message-bus consumer, a watch) that calls into the engine on each
event. HTTP webhooks do not need it; they are request-driven.

## Discovery: the pipeline file is the pipeline

Titan finds pipelines by convention, the way GitHub Actions finds workflows.

**Pipelines live at exactly one path: `.titan/pipelines/*.yml` (and `.yaml`).**
No root-level file, no alternate locations, no "configure the path" setting. If
you follow the convention it works; if you don't, you move the file, not
configure the platform.

A successfully-parsed pipeline file on the repository's default branch **is** a
Titan job — there is no separate "enable" step. When a scan sees a file that
parses cleanly, the corresponding job row is created; when the file disappears
from the default branch, the job is removed. The mental model is deliberately
flat: the file in the repo is the source of truth for whether a pipeline
exists.

Two consequences:

- A file that fails to parse does **not** become a job. The parse error is
  surfaced as a broken pipeline, but auto-enabling a half-broken pipeline that
  then fires on every push would be a worse failure mode.
- Job rows are reconciled from the **default branch** only. Feature-branch
  edits to a pipeline file still feed per-event builds, but they do not create
  duplicate jobs — otherwise a repo with N branches editing the same file would
  yield N jobs.

## One build per push

The webhook-to-build path is 1:1 by design. Every verified, branch-matching
delivery enqueues a fresh build. There is no dedupe, no debounce, no rate
limit, and no per-job concurrency cap.

This is the out-of-the-box behaviour of GitHub Actions and GitLab CI, and it is
the locked v1 contract:

- Two pushes 100 ms apart → two builds.
- A force-push to the same commit → two builds; the receiver does not compare
  SHAs.
- Twelve pushes during a rebase → twelve builds.

The reasoning is no hidden behaviour: when an operator pushes three times and
sees three builds, they are correct, with no debounce window to discover by
archaeology. A specific commit is the unit of audit and reproducibility;
silently merging two deliveries would conflate two distinct events into one
record.

Backpressure comes from one knob only: **the size of the worker pool.** The
queue drains at whatever rate workers are available. One worker means strict
serialisation; more workers means more parallelism. Operators throttle a noisy
repo by sizing workers, not by adding receiver-side coalescing.
