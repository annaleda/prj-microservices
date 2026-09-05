#!/bin/sh
# Scarica l'agent OpenTelemetry per Java.
#
# L'agent strumenta l'applicazione a runtime, senza modificare il codice:
# riconosce da solo Spring MVC, JDBC, il client Kafka (producer *e*
# consumer, propagando il contesto negli header del messaggio) e Camel.
#
# Il jar non e' versionato (~25 MB, vedi .gitignore): si scarica qui.
set -e

VERSION="${1:-2.8.0}"
DEST="$(dirname "$0")/opentelemetry-javaagent.jar"
URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${VERSION}/opentelemetry-javaagent.jar"

if [ -f "$DEST" ]; then
  echo "Agent gia' presente: $DEST"
  echo "Per riscaricarlo, cancellalo prima."
  exit 0
fi

echo "Scarico l'agent OpenTelemetry $VERSION..."
curl -fL -o "$DEST" "$URL"
echo "Salvato in $DEST ($(du -h "$DEST" | cut -f1))"

cat <<'ISTRUZIONI'

Per usarlo, avvia un servizio Java cosi':

  java -javaagent:infrastructure/monitoring/opentelemetry-javaagent.jar \
       -Dotel.service.name=order-service \
       -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
       -Dotel.metrics.exporter=none \
       -jar services/order-service/target/order-service.jar

Il nome del servizio (otel.service.name) e' cio' che appare in Jaeger:
va cambiato per ogni servizio, altrimenti le trace risultano tutte dello
stesso.

`otel.metrics.exporter=none` perche' le metriche le raccoglie gia'
Prometheus da Micrometer: mandarle anche via OTLP le duplicherebbe.

ATTENZIONE ALLA PORTA: la 4318 e' OTLP su **HTTP**, la 4317 e' OTLP su
**gRPC**. Dalla versione 2.x l'agent usa di default il protocollo
`http/protobuf`, quindi va sulla 4318. Puntandolo alla 4317 senza
cambiare protocollo l'errore e' criptico -- "unexpected end of stream" --
e le trace semplicemente non arrivano.

Per usare gRPC servono entrambe le cose:
  -Dotel.exporter.otlp.protocol=grpc -Dotel.exporter.otlp.endpoint=http://localhost:4317
ISTRUZIONI
