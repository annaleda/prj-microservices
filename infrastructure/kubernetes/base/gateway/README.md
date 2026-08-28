# Gateway API (Envoy Gateway)

Questi manifest (`GatewayClass`, `Gateway`) presuppongono che il
controller **Envoy Gateway** sia gia' installato nel cluster. Non è
incluso qui perché è infrastruttura del cluster, non un componente
applicativo del progetto.

Installazione (quando si deciderà di provare il deploy):

```bash
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.1.0 \
  -n envoy-gateway-system \
  --create-namespace

kubectl wait --timeout=5m -n envoy-gateway-system \
  deployment/envoy-gateway --for=condition=Available
```

Una volta installato, applicando `gatewayclass.yaml` e `gateway.yaml`
Envoy Gateway crea automaticamente un `Service` di tipo `LoadBalancer`
(o `NodePort` su kind) per esporre il listener HTTP definito in
`gateway.yaml`.
