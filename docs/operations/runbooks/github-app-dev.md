# GitHub App dev tunnel (smee.io)

## Symptoms

- You're iterating on the Titan GitHub App integration on `task dev:titan`
  and need GitHub.com to deliver webhook events to a backend listening on
  `http://localhost:8080`.
- You created a GitHub App via the Manifest flow and pasted a `localhost`
  webhook URL; GitHub silently dropped every delivery (Settings → Apps →
  your App → Advanced → Recent Deliveries shows red).
- A teammate's `task dev:titan` works but they can't see push events in
  the server log when they push to a fixture repo.

GitHub requires a **publicly reachable HTTPS URL** for webhook delivery.
`localhost` is unreachable from GitHub's network. Production rigs solve
this with their own TLS hostname; dev rigs solve it with a relay.

## Diagnosis

The dev-loop topology is:

```
github.com  ──webhook──>  https://smee.io/<channel>  ──relay──>  http://localhost:8080/api/v1/github-app/events
                                                       (smee-client running on your laptop)
```

The smee channel URL is a free, persistent endpoint provisioned by
`https://smee.io/new`. It has no auth — the payload secret in the App's
webhook config is what authenticates the delivery (signature verified
server-side).

To confirm whether the relay is at fault vs. the backend:

```bash
# Watch the smee-client process started by `task dev:tunnel` — every
# delivery from GitHub prints a line. If GitHub redelivers and you see
# nothing here, the App's webhook URL is wrong or your smee channel was
# evicted (free channels eventually expire if unused).
#
# Manual injection — confirms the relay end-to-end even when GitHub is
# not in the loop:
curl -X POST https://smee.io/<channel> \
  -H 'Content-Type: application/json' \
  -H 'X-GitHub-Event: ping' \
  -d '{"zen":"smoke"}'

# Then check your local titan-server log:
docker logs titan-local-titan-server-1 2>&1 | tail -20
```

If the smee-client prints the delivery but `titan-server` logs nothing, the
relay is fine and the backend is at fault — check that `task dev:titan` is up
and the `/api/v1/github-app/events` route is reachable.

## Fix

### One-time install

```bash
pnpm i -g smee-client
```

(Node 18+ required. `pnpm` is already a `task setup` dependency.)

### Provision your channel

```bash
task dev:tunnel
```

The task:
1. Reads `dev/.smee-channel` (gitignored).
2. If absent, `curl --max-time 5 https://smee.io/new` (a GET that returns
   a `307` with `Location: https://smee.io/<channel>`) is used to mint a
   fresh channel URL, parsed out of the response headers, and written to
   that file.
3. Runs `smee --url $(cat dev/.smee-channel) --target http://localhost:8080/api/v1/github-app/events`
   in the foreground.

Leave it running in its own terminal alongside `task dev:titan`.

### Paste the channel URL into your App

When you click "Create Titan GitHub App" in the UI, Titan substitutes
`{{WEBHOOK_TARGET}}` in `manifest-template.json` with the value the admin
pastes from `dev/.smee-channel`. In production rigs that field is the rig's
TLS hostname (e.g. `https://titan.example.com/api/v1/github-app/events`) and no
tunnel is needed.

### Production: replace the tunnel

For a self-hosted production rig:

- Webhook target = `https://<rig-host>/api/v1/github-app/events` (the
  rig's nginx TLS termination).
- No smee.io. No `dev:tunnel`. The App Manifest is created with the
  rig's hostname baked in.
- The same backend handler validates the App's webhook signature — there
  is no code path that trusts the source IP or the absence of a tunnel.

## Prevention

- `dev/.smee-channel` is gitignored. Never commit a channel URL — it is
  effectively a public webhook ingress for your laptop.
- If you rotate channels (laptop change, channel expired), delete
  `dev/.smee-channel` and re-run `task dev:tunnel`; then re-paste into
  the App's webhook config (Settings → Apps → your App → General →
  Webhook URL).
- The webhook **secret** stays the same across channel rotation — it
  lives in the `titan_github_app` table seeded at App-create time, not in
  the smee URL.

## Credentials in dev

The App's PEM + webhook secret are stored once at App-create time in the
`titan_github_app` table (sealed under the credential KEK). In a shared dev
environment they can be injected from Infisical under `GITHUB_APP_PEM` +
`GITHUB_APP_WEBHOOK_SECRET`; the webhook secret is what authenticates each
delivery (signature verified server-side), so it must match the value in the
App's webhook config.

## Cross-references

- [`docs/guides/scm-integration.md`](../../guides/scm-integration.md) —
  GitHub App setup, webhooks, and status reporting.
- `titan-server/src/main/resources/io/adaptiq/titan/scm/github/manifest-template.json` —
  the template substituted at App-create time.
- `Taskfile.yml` — the `dev:tunnel` task definition.
