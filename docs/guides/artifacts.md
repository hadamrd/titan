# Artifacts

How to archive build outputs and point Titan at an artifact backend.

Titan archives files produced by a build to a pluggable object store. A pipeline
declares *what* to archive with the `archiveArtifacts` step; an operator
configures *where* it goes with `TITAN_ARTIFACT_*` environment variables.

## Archiving artifacts in a pipeline

The `archiveArtifacts` step captures files matching one or more Ant globs
(`**`, `*`, `?`). Two forms are accepted.

Scalar shorthand:

```yaml
steps:
  - archiveArtifacts: 'target/*.jar'
```

Explicit arguments:

```yaml
steps:
  - archiveArtifacts:
      artifacts: 'target/*.jar, build/reports/**'   # required — comma/space-separated globs
      excludes: '**/*.tmp'                           # optional
      allowEmptyArchive: false                       # optional (no match → step FAILS unless true)
      fingerprint: false                             # optional — record a content fingerprint
      caseSensitive: true                            # optional
      followSymlinks: true                           # optional
```

Notes:

- Re-archiving the same name overwrites it (idempotent), so a retried step is
  safe.
- There is no `onlyIfSuccessful` argument — conditional execution is the DAG's
  `when:` instead.
- If no artifact store is configured, `archiveArtifacts` **fails at run time**
  with the sink error in the step log.

## Configuring a backend

The backend is selected by `TITAN_ARTIFACT_STORE` and configured by the other
`TITAN_ARTIFACT_*` variables. **Set the same values on both the controller and
the worker** — they must resolve to the same store. Configuration is
environment-only; there is no separate UI or file-based config for this.

The default backend is `fs` (local filesystem, rooted at `TITAN_ARTIFACT_ROOT`).
Leaving `TITAN_ARTIFACT_STORE` unset means no store, and `archiveArtifacts`
fails closed.

### S3 / Cloudflare R2

One `s3` backend serves both AWS S3 and Cloudflare R2 (R2 is the S3 API plus a
custom endpoint).

| Variable | Required | Notes |
|---|---|---|
| `TITAN_ARTIFACT_STORE=s3` | yes | selects the backend |
| `TITAN_ARTIFACT_BUCKET` | yes | bucket name |
| `TITAN_ARTIFACT_ACCESSKEY` | yes | access key id |
| `TITAN_ARTIFACT_SECRETKEY` | yes | secret access key |
| `TITAN_ARTIFACT_ENDPOINT` | no | set for R2 (`https://<account>.r2.cloudflarestorage.com`); unset = AWS S3 |
| `TITAN_ARTIFACT_REGION` | no | default `us-east-1`; R2 uses `auto` |
| `TITAN_ARTIFACT_PATHSTYLE` | no | `true` to force path-style addressing |

A single object upload caps at 5 GiB (no multipart).

### Nexus

Targets a Nexus 3 **raw hosted** repository over HTTP with Basic auth.

| Variable | Required | Notes |
|---|---|---|
| `TITAN_ARTIFACT_STORE=nexus` | yes | selects the backend |
| `TITAN_ARTIFACT_URL` | yes | Nexus base URL |
| `TITAN_ARTIFACT_REPOSITORY` | yes | the raw hosted repository name |
| `TITAN_ARTIFACT_USERNAME` | yes | |
| `TITAN_ARTIFACT_PASSWORD` | yes | |

## Adding your own backend

A backend is a self-contained module implementing two interfaces in
`titan-pipeline-model` (package `io.adaptiq.titan.flow.artifact`):
`ArtifactStoreProvider` (a `kind()` string plus
`ArtifactStore create(Map<String,String> config)`) and the `ArtifactStore`
itself (`put`, `open`, `delete`, build/stash pruning). The provider is
discovered via a `ServiceLoader` entry. The shipped S3 and Nexus modules under
`titan-extensions/` are the canonical templates. See
[Extending Titan](extending-titan.md) for the packaging mechanics and
[the SPI reference](../reference/spi.md) for the full interface contract.

## See also

- [Extending Titan](extending-titan.md) — ship a backend as an extension.
- [Credentials & secrets](credentials.md) — keeping backend credentials sealed.
