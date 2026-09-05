{{/*
Etichette comuni a tutte le risorse.

`app.kubernetes.io/*` sono le etichette raccomandate da Kubernetes: le
usano strumenti di terze parti (dashboard, service mesh) per capire cosa
appartiene a cosa. `managed-by: Helm` e' anche il modo in cui Helm
riconosce le risorse che ha creato.
*/}}
{{- define "polyglot.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .release }}
app.kubernetes.io/part-of: polyglot-commerce
app.kubernetes.io/managed-by: Helm
helm.sh/chart: {{ .chart }}
{{- end -}}

{{/*
Etichette del selettore: solo quelle che identificano i pod.

Vanno tenute separate e *immutabili*: il selector di un Deployment non si
puo' modificare dopo la creazione, quindi non deve contenere nulla che
cambi fra una versione e l'altra (come la versione del chart).
*/}}
{{- define "polyglot.selectorLabels" -}}
app: {{ .name }}
{{- end -}}

{{/*
Riferimento completo all'immagine di un servizio.
*/}}
{{- define "polyglot.image" -}}
{{- printf "%s/%s:%s" .repository .name .tag -}}
{{- end -}}
