{{/*
Standard labels stamped on every resource.
*/}}
{{- define "titan-registry.labels" -}}
app.kubernetes.io/name: titan-registry
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/component: registry
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/*
Selector labels — STABLE across upgrades (no version).
*/}}
{{- define "titan-registry.selectorLabels" -}}
app.kubernetes.io/name: titan-registry
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Name of the in-cluster Secret holding the htpasswd file. The Deployment, the
ExternalSecret, and any out-of-band tooling all reference this single name.
*/}}
{{- define "titan-registry.authSecretName" -}}
{{- .Values.auth.secretName | default "titan-registry-auth" -}}
{{- end -}}
