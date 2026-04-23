{{- define "odimall.rum.otlpEndpoint" -}}
{{- $a := .Values.rum.endpoint | default "" | trim -}}
{{- if ne $a "" -}}{{ $a -}}{{- else -}}{{- .Values.rumEndpoint | default "" | trim -}}{{- end -}}
{{- end }}
