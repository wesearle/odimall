{{- define "odimall.rum.otlpEndpoint" -}}
{{- $a := .Values.rum.endpoint | default "" | trim -}}
{{- if ne $a "" -}}{{ $a -}}{{- else -}}{{- .Values.rumEndpoint | default "" | trim -}}{{- end -}}
{{- end }}

{{- define "odimall.clickstack.collectorUrl" -}}
{{- $a := .Values.clickstack.url | default "" | trim -}}
{{- if ne $a "" -}}{{ $a -}}{{- else -}}{{- .Values.clickstackUrl | default "" | trim -}}{{- end -}}
{{- end }}

{{/*
Kubernetes Secret name for frontend CLICKSTACK_API_KEY.
Default: <release>-clickstack-credentials
*/}}
{{- define "odimall.clickstack.secretName" -}}
{{- if .Values.clickstack.secretName -}}
{{- .Values.clickstack.secretName -}}
{{- else -}}
{{- printf "%s-clickstack-credentials" .Release.Name -}}
{{- end -}}
{{- end }}

{{- define "odimall.clickstack.secretKey" -}}
{{- .Values.clickstack.secretKey | default "CLICKSTACK_API_KEY" -}}
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

{{/*
Kubernetes Secret name for product-service Langfuse keys.
Default: <release>-langfuse-credentials
*/}}
{{- define "odimall.langfuse.secretName" -}}
{{- if .Values.productService.langfuse.secretName -}}
{{- .Values.productService.langfuse.secretName -}}
{{- else -}}
{{- printf "%s-langfuse-credentials" .Release.Name -}}
{{- end -}}
{{- end }}

{{- define "odimall.langfuse.publicKeySecretKey" -}}
{{- .Values.productService.langfuse.publicKeySecretKey | default "LANGFUSE_PUBLIC_KEY" -}}
{{- end }}

{{- define "odimall.langfuse.secretKeySecretKey" -}}
{{- .Values.productService.langfuse.secretKeySecretKey | default "LANGFUSE_SECRET_KEY" -}}
{{- end }}
