{{/*
Common labels for every resource in the chart.
*/}}
{{- define "titan.labels" -}}
app.kubernetes.io/name: titan
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/*
Selector labels — STABLE across upgrades (must not include version).
*/}}
{{- define "titan.selectorLabels" -}}
app.kubernetes.io/name: titan
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
The name of the k8s Secret holding chart secrets. Centralised so every
secretKeyRef refers to the same name.
*/}}
{{- define "titan.secretName" -}}
{{- .Values.secrets.name | default "titan-secrets" -}}
{{- end -}}

{{/*
Resolve which mechanism owns `titan-secrets`. The ExternalSecret (ESO-owned,
synced from Infisical) and the plaintext Secret (chart literals) are MUTUALLY
EXCLUSIVE — a k8s Secret can have only one owner. When
`secrets.externalSecret.enabled=true` the ExternalSecret WINS and the plaintext
Secret is auto-suppressed, so a single flag flip is all an operator needs and
two manifests can never both claim `titan-secrets` (#1160). This helper is the
single source of truth for "should the plaintext Secret render?" — both
secrets.yaml and the helm-template tests consult the same predicate.
*/}}
{{- define "titan.plaintextSecretEnabled" -}}
{{- and .Values.secrets.create (not .Values.secrets.externalSecret.enabled) -}}
{{- end -}}

{{/*
True when EITHER the server or the worker resolves credentials through the
Infisical SecretsBackend. The single source of truth for "render INFISICAL_TOKEN
into titan-secrets + the INFISICAL_* env onto the pods". Both server and worker
read the same key, so the secret is rendered if either side needs it.
*/}}
{{- define "titan.infisicalEnabled" -}}
{{- $server := eq .Values.titanServer.secretsBackend "infisical" -}}
{{- $worker := eq (default "db-envelope" .Values.titanWorker.secretsBackend) "infisical" -}}
{{- if or $server $worker -}}true{{- else -}}false{{- end -}}
{{- end -}}

{{/*
Render the INFISICAL_* env block onto a container. Shared by the server and the
worker so the two never drift. Call with a dict:
  (dict "secretName" <titan-secrets name> "infisical" <component .infisical block>)
INFISICAL_TOKEN is pulled from titan-secrets (never inlined); the rest are the
plain Infisical coordinates from values. Mirrors InfisicalClient's env contract.
*/}}
{{- define "titan.infisicalEnv" -}}
- name: INFISICAL_TOKEN
  valueFrom:
    secretKeyRef:
      name: {{ .secretName }}
      key: INFISICAL_TOKEN
- name: INFISICAL_PROJECT_ID
  value: {{ .infisical.projectId | quote }}
- name: INFISICAL_ENV
  value: {{ .infisical.env | quote }}
- name: INFISICAL_SECRET_PATH
  value: {{ .infisical.secretPath | quote }}
- name: INFISICAL_API_URL
  value: {{ .infisical.apiUrl | quote }}
{{- end -}}

{{/*
Validate the secretsBackend discriminator(s) — fail loud on an unknown value
rather than silently resolve every credential to empty at build time. Mirrors
titan.artifacts.validate.
*/}}
{{- define "titan.secretsBackend.validate" -}}
{{- $known := dict "db-envelope" true "infisical" true "vault" true -}}
{{- $s := .Values.titanServer.secretsBackend -}}
{{- if not (hasKey $known $s) -}}{{- fail (printf "values: titanServer.secretsBackend must be one of db-envelope|infisical|vault, got %q" $s) -}}{{- end -}}
{{- $w := default "db-envelope" .Values.titanWorker.secretsBackend -}}
{{- if not (hasKey $known $w) -}}{{- fail (printf "values: titanWorker.secretsBackend must be one of db-envelope|infisical|vault, got %q" $w) -}}{{- end -}}
{{- end -}}

{{/*
Validate the artifacts backend block — fail loud on an unknown discriminator
rather than silently default to fs.
*/}}
{{- define "titan.artifacts.validate" -}}
{{- $b := .Values.titanServer.artifacts.backend -}}
{{- if not (or (eq $b "fs") (eq $b "s3")) -}}
{{- fail (printf "values: titanServer.artifacts.backend must be `fs` or `s3`, got %q" $b) -}}
{{- end -}}
{{- if eq $b "s3" -}}
{{- if not .Values.titanServer.artifacts.s3.bucket -}}{{- fail "values: titanServer.artifacts.backend=s3 but .s3.bucket is empty." -}}{{- end -}}
{{- if not .Values.titanServer.artifacts.s3.endpoint -}}{{- fail "values: titanServer.artifacts.backend=s3 but .s3.endpoint is empty." -}}{{- end -}}
{{- end -}}
{{- end -}}
