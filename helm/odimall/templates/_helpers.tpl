{{- define "odimall.rum.otlpEndpoint" -}}
{{- $a := .Values.rum.endpoint | default "" | trim -}}
{{- if ne $a "" -}}{{ $a -}}{{- else -}}{{- .Values.rumEndpoint | default "" | trim -}}{{- end -}}
{{- end }}

{{/*
Kubernetes Secret name for product-service OPENAI_API_KEY.
Default: <release>-openai-credentials (Helm creates it with a placeholder key when productService.openai.createSecret is true).
*/}}
{{- define "odimall.openai.secretName" -}}
{{- if .Values.productService.openai.secretName -}}
{{- .Values.productService.openai.secretName -}}
{{- else if .Values.productService.openaiApiKeySecretName -}}
{{- .Values.productService.openaiApiKeySecretName -}}
{{- else -}}
{{- printf "%s-openai-credentials" .Release.Name -}}
{{- end -}}
{{- end }}

{{- define "odimall.openai.secretKey" -}}
{{- .Values.productService.openai.secretKey | default .Values.productService.openaiApiKeySecretKey | default "OPENAI_API_KEY" -}}
{{- end }}

{{/*
Kubernetes Secret name for product-service GEMINI_API_KEY (Google AI Studio / Gemini API).
Default: <release>-gemini-credentials
*/}}
{{- define "odimall.gemini.secretName" -}}
{{- if .Values.productService.gemini.secretName -}}
{{- .Values.productService.gemini.secretName -}}
{{- else -}}
{{- printf "%s-gemini-credentials" .Release.Name -}}
{{- end -}}
{{- end }}

{{- define "odimall.gemini.secretKey" -}}
{{- .Values.productService.gemini.secretKey | default "GEMINI_API_KEY" -}}
{{- end }}
