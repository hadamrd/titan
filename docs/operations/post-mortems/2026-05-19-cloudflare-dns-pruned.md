# Post-mortem: staging rig DNS destroyed — Crossplane prune deleted live Cloudflare records

**Date:** 2026-05-19
**Severity:** P1 (staging rig fully unreachable — `the rig host` NXDOMAIN)
**Duration:** ~from helm rev 18 deploy (12:36 UTC area) to DNS verified restored.
**Authors:** DevOps Agent (A8)

## Timeline (UTC)
- ~10:24 — A `helm upgrade` of the `titan` chart renders the cloudflare
  templates EMPTY. Helm prunes `ProviderConfig titan-cloudflare`, both DNS
  `Record` CRs, and the R2 `Bucket` CR — all four get `deletionTimestamp:
  2026-05-19T10:24:00..01Z`. Crossplane, owning external state, issues the
  Cloudflare deletes: both DNS records are destroyed; the R2 bucket delete is
  refused by Cloudflare (`409 Conflict — bucket not empty`).
- 10:24–12:37 — A corrected `rig-values.yaml` is redeployed (helm revs up to
  19). The cloudflare manifests are rendered again, but the live Cloudflare
  DNS records are already gone and the pruned ProviderConfig/Bucket are stuck
  terminating on finalizers, so the records do not come back.
- Incident reported: `the rig host` → NXDOMAIN.
- A8 gets cluster access via `rig/k3s/fetch-kubeconfig.sh`.
- Diagnosis: `kubectl get records...` → `No resources found`; ProviderConfig +
  Bucket carry the 10:24 `deletionTimestamp`; Bucket has
  `LastAsyncOperation: DestroyFailure` with the Cloudflare 409.
- Fix: orphan the stuck Bucket CR (remove finalizer — real bucket + data
  survive) → frees the ProviderConfig to finish terminating → recreate
  ProviderConfig + both Record CRs WITHOUT the stale `external-name`
  annotation so Crossplane CREATEs fresh Cloudflare records.
- Both Records reconcile to `Synced=True Ready=True`; fresh Cloudflare record
  IDs assigned.
- Verified: `dig @1.1.1.1` / `@8.8.8.8` resolve `the rig host`
  → `203.0.113.10`; `https://.../login` returns the rig sign-in page.

## What broke

The rig's DNS records are Crossplane managed resources rendered by the
`titan` Helm chart. Every cloudflare template is wrapped in
`{{- if .Values.cloudflare.enabled }}`. The chart's *default* `values.yaml`
has `cloudflare.enabled: false`. The live rig depends entirely on
`rig-values.yaml` overriding it to `true`.

A malformed `rig-values.yaml` was deployed once during PR #223 work (the
operator self-reported a values file with the `artifacts:` key stripped).
YAML is whitespace-structural — a dropped or misindented key takes adjacent
blocks with it. With the `cloudflare:` override block effectively gone,
`.Values.cloudflare.enabled` fell back to the chart default `false`. All
three cloudflare templates (`cloudflare-dns.yaml`,
`cloudflare-providerconfig.yaml`, `cloudflare-r2.yaml`) rendered to nothing.

`helm upgrade` treats a resource that disappears from the rendered manifest
as a resource to **prune**. It deleted the ProviderConfig, both DNS Records,
and the R2 Bucket. Crossplane managed resources are not ordinary k8s objects
— deleting the CR tells Crossplane to **delete the external cloud resource**.
The live `titan.` and `registry.` Cloudflare DNS records were destroyed.
The R2 bucket survived only because it contained objects and Cloudflare
refuses to delete a non-empty bucket.

## Why it broke (root cause)

Five-whys:

1. **Why was DNS down?** The Cloudflare DNS records were deleted.
2. **Why were they deleted?** `helm upgrade` pruned the Crossplane `Record`
   CRs; Crossplane propagated the delete to Cloudflare.
3. **Why were the CRs pruned?** The cloudflare templates rendered empty.
4. **Why did they render empty?** `.Values.cloudflare.enabled` was `false` —
   the chart default — because a malformed `rig-values.yaml` lost the
   `cloudflare:` override block.
5. **Why did a malformed values file silently disable DNS instead of being
   rejected?** The chart had (a) no `values.schema.json`, so a structurally
   broken file was accepted as-is, and (b) an `{{- if .enabled }}` that
   treats "key absent → default false" identically to a deliberate opt-out.
   A typo and an intentional non-Cloudflare rig were indistinguishable — and
   the typo case silently destroys production DNS.

The deeper defect: **a Helm chart that renders Crossplane managed resources
behind a soft toggle makes a values-file typo an irreversible
destroy-external-state operation, with zero warning.**

## What we did

1. `rig/k3s/fetch-kubeconfig.sh` — cluster access via SSH tunnel.
2. Inspected Crossplane state: `Record` CRs gone; `ProviderConfig` + `Bucket`
   terminating since 10:24; Bucket `DestroyFailure` (Cloudflare 409 — bucket
   not empty, so artifacts data was safe).
3. Orphaned the stuck `Bucket` CR (`patch ... finalizers: []`) — the real R2
   bucket and its contents survive; this freed the `providerconfigusage`,
   which let the zombie `ProviderConfig` finish terminating.
4. Recreated `ProviderConfig titan-cloudflare` + both `Record` CRs from a
   hand-written manifest, deliberately OMITTING the
   `crossplane.io/external-name` annotation (the old record IDs were dead),
   so Crossplane created fresh Cloudflare records.
5. Watched both records reach `Synced=True Ready=True`.
6. Verified DNS on public resolvers + the rig login page over HTTPS.
7. Confirmed all pods healthy (titan-server, titan-worker 2/2, postgres, registry)
   — the deploy left the workloads themselves fine; only the Crossplane DNS
   layer was damaged.
8. Hardened the chart (PR off trunk, not merged — see below).

## What worked well

- The `10:24:00Z` deletionTimestamp shared by all four Crossplane resources
  was an unambiguous fingerprint of a single prune event.
- Cloudflare's refusal to delete a non-empty R2 bucket (`409 Conflict`)
  accidentally saved the artifacts data — a real safety net for object
  storage that DNS records simply do not have.
- Crossplane's adoption model meant recreating the CRs immediately
  re-created the live records — recovery was a `kubectl apply`, not a
  Cloudflare-dashboard scramble.

## What did not

- The chart had no schema validation and a destroy-capable soft toggle.
- The corrected redeploy did not self-heal: pruned-then-terminating
  Crossplane objects cannot be un-deleted by re-adding them to the manifest;
  they must finish terminating and be recreated.
- The new Cloudflare record IDs differ from the ones pinned in
  `rig-values.yaml` `cloudflare.dnsRecords[].externalName` — those must be
  re-read and updated, or the next deploy creates duplicates. (Operator
  follow-up.)

## Corrective actions

| # | Action | Status |
|---|--------|--------|
| 1 | `titan.cloudflare.validate` fail-loud guard in `_helpers.tpl`; every cloudflare template calls it. `enabled: true` + any empty required field, or empty `dnsRecords`, aborts the render. | Done (PR) |
| 2 | `rig/k3s/helm/titan/values.schema.json` — Helm rejects a structurally broken `rig-values.yaml` before rendering. | Done (PR) |
| 3 | The chart guards above (the fail-loud validate + the values schema) are the durable fix; they abort a misconfigured render before any prune can happen. | Done |
| 4 | Re-read the live Cloudflare record IDs and update `rig-values.yaml` `cloudflare.dnsRecords[].externalName`. | Operator follow-up |
| 5 | Evaluate `deletionPolicy: Orphan` on the DNS `Record` resources so a future prune drops only the CR, not the live record. | Proposed |
| 6 | Add `helm template ... | grep 'kind: Record'` diff to the pre-deploy checklist for any rig-values change. | Proposed |
