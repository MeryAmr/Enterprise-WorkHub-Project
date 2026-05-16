# Deployment — Phase 3 (Week 14, Track 2)

Three deploy paths, in increasing fidelity to production: local Compose, raw
Kubernetes manifests on a kind cluster, and the same kind cluster fully
provisioned via Terraform (the graded IaC path).

| Path | Purpose | Cluster origin | Backend workload origin |
|------|---------|----------------|--------------------------|
| Compose | Local dev | — | `docker-compose.yml` |
| k8s/ + kind | Manifest correctness, probes | `kind create cluster` (manual) | `kubectl apply -k k8s/` |
| Terraform | Track 2 IaC deliverable | `kind_cluster` Terraform resource | `kubernetes_deployment`, `kubernetes_service`, `kubernetes_config_map`, `kubernetes_secret` |

Across all three paths the backend reads its datasource and JWT config from env
([backend/src/main/resources/application.yaml](backend/src/main/resources/application.yaml)),
defaulting to local-dev values when not set.

## Prerequisites

- Docker ≥ 24
- `kubectl` ≥ 1.30
- `kind` ≥ 0.27 (install: `curl -fsSL -o ~/.local/bin/kind https://kind.sigs.k8s.io/dl/v0.27.0/kind-linux-amd64 && chmod +x ~/.local/bin/kind`)
- `terraform` ≥ 1.5 (for path 3)
- Optional: `kompose` (only needed to re-seed manifests from compose)

---

## Path 1 — Docker Compose (local dev + grading section A)

```bash
docker compose up -d --build
curl http://localhost:8080/actuator/health/readiness   # {"status":"UP"}
curl http://localhost:8081/                            # kafka-ui
docker compose down -v
```

What the stack runs: backend (built from `./backend/Dockerfile`) on host 8080,
Postgres 16 (host 5433), Kafka 3.7 KRaft (host 9092), Kafka UI (host 8081).

Postgres has a `pg_isready` healthcheck and the backend `depends_on` it with
`condition: service_healthy` so the JVM doesn't race the DB.

---

## Path 2 — Kubernetes (manifests + kind, grading section B)

The `k8s/` directory ships hand-tuned manifests; the original `kompose convert`
output was used as a seed only.

Layout:

```
k8s/
├── kind-config.yaml             # cluster shape, nftables kube-proxy, NodePort 30080 -> host 8080
├── kustomization.yaml           # backend app resources only; excludes kind-config.yaml
├── namespace.yaml               # workhub
├── backend-configmap.yaml       # SPRING_DATASOURCE_URL/USERNAME, KAFKA_*, profile
├── backend-secret.yaml          # SPRING_DATASOURCE_PASSWORD, JWT_SECRET
├── backend-deployment.yaml      # 2 replicas, Actuator probes, resource limits
├── backend-service.yaml         # NodePort 30080 -> targetPort http
└── infra/
    ├── kustomization.yaml       # postgres + kafka
    ├── postgres.yaml            # StatefulSet + headless Service + PVC
    └── kafka.yaml               # StatefulSet + headless Service (KRaft)
```

### Deploy

```bash
# 1. Build + load the backend image into the local kind cluster
docker build -t workhub-backend:dev ./backend
kind create cluster --name workhub --config k8s/kind-config.yaml
kubectl --context kind-workhub -n kube-system rollout status deployment/coredns --timeout=120s
kind load docker-image workhub-backend:dev --name workhub

# 2. Create shared app config/secret first. Postgres reads the same DB password
#    Secret that the backend uses.
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/backend-configmap.yaml -f k8s/backend-secret.yaml

# 3. Apply stateful infra, then the backend workload. Use kustomize so
#    kind-config.yaml is not sent to the Kubernetes API.
kubectl apply -k k8s/infra/                 # postgres + kafka
kubectl -n workhub rollout status statefulset/postgres --timeout=180s
kubectl -n workhub rollout status statefulset/kafka --timeout=180s
kubectl apply -k k8s/                       # backend deployment/service/cm/secret

# 4. Wait for the backend to become ready
kubectl -n workhub rollout status deployment/backend --timeout=180s

# 5. Hit the service via the kind-mapped host port
curl http://localhost:8080/actuator/health   # via NodePort 30080 -> host 8080

# 6. Teardown
kind delete cluster --name workhub
```

### Probes

Both readiness and liveness probes hit Spring Boot Actuator endpoints already
exposed by [application.yaml](backend/src/main/resources/application.yaml):

- `readinessProbe`: `GET /actuator/health/readiness`
- `livenessProbe`: `GET /actuator/health/liveness`

The Service is **NodePort 30080**, and `k8s/kind-config.yaml` maps that to host
port 8080 via `extraPortMappings`, so `curl http://localhost:8080` reaches the
backend without `kubectl port-forward`.

`k8s/kind-config.yaml` also sets `networking.kubeProxyMode: nftables` and repeats
the same setting via a `KubeProxyConfiguration` kubeadm patch. On this Arch/Docker
host, kind's default iptables mode failed to program Service rules, which left
CoreDNS running but not Ready. nftables mode is the better fit for an nf_tables
host, but it still requires the running kernel to have matching netfilter modules
installed.

If CoreDNS remains `0/1 Running`, check the host kernel/modules pair:

```bash
uname -r
ls /usr/lib/modules
kubectl --context kind-workhub -n kube-system logs daemonset/kube-proxy --tail=80
```

The verified failure on this machine was a running kernel of `7.0.5-arch1-1`
with only `/usr/lib/modules/7.0.7-arch2-1` installed. Rebooting into the installed
kernel (or installing modules matching the running kernel) is required before
kube-proxy/CoreDNS can become healthy.

---

## Path 3 — Terraform (Track 2 IaC, grading section C)

This is the graded IaC path. Terraform provisions the **kind cluster** plus the
**backend application workload** (namespace, ConfigMap, Secret, Deployment with
probes, NodePort Service).

**Out of scope for Terraform, by design**: Postgres and Kafka stay in
`k8s/infra/` and are applied with `kubectl apply` after Terraform is done. See
[terraform/README.md](terraform/README.md) for the rationale.

### Init + apply

```bash
cd terraform

# 1. Configure
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars — set db_password + jwt_secret

# 2. Init (downloads tehcyx/kind + hashicorp/kubernetes providers)
terraform init
terraform fmt -check
terraform validate

# 3. Plan + apply. Terraform creates the Deployment object but does not wait
#    for rollout because the local image and stateful dependencies come next.
terraform plan
terraform apply

# 4. Load image + apply stateful infra (out-of-band, by design)
docker build -t workhub-backend:dev ../backend
kind load docker-image workhub-backend:dev --name "$(terraform output -raw cluster_name)"
kubectl --context kind-workhub -n kube-system rollout status deployment/coredns --timeout=120s
kubectl --context kind-workhub apply -k ../k8s/infra/
kubectl --context kind-workhub -n workhub rollout status statefulset/postgres --timeout=180s
kubectl --context kind-workhub -n workhub rollout status statefulset/kafka --timeout=180s

# 5. Restart backend so it sees Postgres now that the StatefulSet is up
kubectl --context kind-workhub -n workhub rollout restart deployment/backend
kubectl --context kind-workhub -n workhub rollout status deployment/backend --timeout=180s

# 6. Verify
curl "$(terraform output -raw backend_host_url)/actuator/health"

# 7. Teardown
kubectl --context kind-workhub delete -f ../k8s/infra/ --ignore-not-found
terraform destroy
```

### Why Terraform is narrowed

- **No drift**: Stateful resources (Postgres PVCs) mutate outside Terraform's
  view; keeping them in YAML avoids `terraform refresh` spam.
- **Tight blast radius**: `terraform destroy` removes the cluster and the backend
  in one shot. PVC data on the host disk is reclaimed with the kind container.
- **Image lifecycle is out-of-band anyway**: Even if Terraform managed Postgres,
  it could not push images to kind; `docker build` + `kind load` is always a
  manual step in a local cluster.

---

## CI/CD — GitHub Actions

Workflow: `.github/workflows/ci.yml`. Three jobs:

| Job | What it does | Trigger |
|-----|--------------|---------|
| `build-test` | Spin up a Postgres 16 service container, run `./mvnw -B verify`. Kafka tests use Testcontainers ([backend/pom.xml](backend/pom.xml)). | Every push + PR |
| `docker-build` | Build the backend image with Buildx, tag `workhub-backend:$SHA`. Push is intentionally off (rubric: optional). | After `build-test` passes |
| `terraform-validate` | `terraform fmt -check` + `terraform init -backend=false` + `terraform validate`. Catches drift in the IaC. | Every push + PR |

Why a Postgres service container, not Testcontainers postgres: zero new Maven
deps, zero new test code, and the env externalisation done in section 0 means
the existing `@SpringBootTest` integration tests just pick up
`SPRING_DATASOURCE_URL` and connect.

---

## Image strategy

Local: `docker build -t workhub-backend:dev ./backend`.
kind: `kind load docker-image workhub-backend:dev --name workhub`.
CI: `workhub-backend:${GITHUB_SHA}` built but not pushed. To enable push to
GHCR, add a `docker/login-action@v3` step + `push: true` in
[`.github/workflows/ci.yml`](.github/workflows/ci.yml).

---

## Tag

```bash
git tag v3-phase3-week14
git push origin v3-phase3-week14
```
