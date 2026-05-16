# Terraform — WorkHub IaC (Track 2: Kubernetes)

Provisions a local **kind** cluster and the **backend application workload** on it
(namespace, ConfigMap, Secret, Deployment with readiness/liveness probes, NodePort
Service).

The kind cluster uses `kube_proxy_mode = "nftables"` plus a matching
`KubeProxyConfiguration` kubeadm patch because this Arch/Docker host failed
kube-proxy's default iptables rule setup, which caused CoreDNS to stay Running
but not Ready. The host still needs matching kernel and module versions for
netfilter to work.

## Scope (deliberate narrowing)

Terraform owns:

- The kind cluster itself (`tehcyx/kind` provider)
- `kubernetes_namespace` — `workhub`
- `kubernetes_config_map` — backend non-secret env (datasource URL/username, Kafka,
  profile)
- `kubernetes_secret` — `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`
- `kubernetes_deployment` — backend with Spring Boot Actuator probes
- `kubernetes_service` — NodePort 30080 → host 8080 (via kind extra port mappings)

Terraform does **not** provision the database or Kafka. Postgres (StatefulSet +
PVC) and Kafka (StatefulSet) live as plain YAML in `../k8s/infra/` and are applied
separately with `kubectl apply`. This is a deliberate Track 2 narrowing — stateful
infra evolves on its own cadence and is easier to debug outside Terraform state.

## Prerequisites

- `terraform` ≥ 1.5
- `docker` running (kind spins the cluster up as containers on the host daemon)
- `kubectl`
- The backend image built locally (`workhub-backend:dev`). Build it before or
  after `terraform apply`, then load it into the newly-created kind cluster.

## End-to-end deploy

```bash
# 0. Build the backend image
docker build -t workhub-backend:dev ../backend

# 1. Configure
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars — set db_password + jwt_secret at minimum

# 2. Init + validate
terraform init
terraform validate
terraform fmt -check

# 3. Plan
terraform plan

# 4. Apply — creates the cluster + namespace + backend workload objects.
#    The backend Deployment intentionally does not wait for rollout because
#    the local image and stateful dependencies are loaded/applied next.
terraform apply

# 5. Load the backend image into the freshly-created cluster
kind load docker-image workhub-backend:dev --name "$(terraform output -raw cluster_name)"

# 6. Wait for cluster DNS, then apply stateful infra (postgres + kafka).
#    These resources are NOT managed by Terraform.
kubectl --context kind-workhub -n kube-system rollout status deployment/coredns --timeout=120s
kubectl --context kind-workhub apply -k ../k8s/infra/
kubectl --context kind-workhub -n workhub rollout status statefulset/postgres --timeout=180s
kubectl --context kind-workhub -n workhub rollout status statefulset/kafka --timeout=180s

# 7. Restart the backend so it picks up Postgres now that it exists
kubectl --context kind-workhub -n workhub rollout restart deployment/backend
kubectl --context kind-workhub -n workhub rollout status deployment/backend --timeout=180s

# 8. Verify
curl "$(terraform output -raw backend_host_url)/actuator/health"
```

## Teardown

```bash
kubectl --context kind-workhub delete -k ../k8s/infra/ --ignore-not-found
terraform destroy
```

## Troubleshooting

- **`Error: failed to create cluster: ... port is already allocated`** — another
  process is on host port 8080. Stop it or edit `k8s/kind-config.yaml` +
  `main.tf`'s `extra_port_mappings` block.
- **Backend CrashLoopBackOff with `Connection refused`** — you applied Terraform
  but skipped step 6 (`kubectl apply -k ../k8s/infra/`). Postgres has to exist
  before the backend will become Ready.
- **`terraform apply` used to hang at `Still creating... kubernetes_deployment.backend`** —
  the Deployment was waiting for rollout before the local image and Postgres/Kafka
  existed. The Terraform resource now sets `wait_for_rollout = false`; use the
  rollout commands above after loading the image and applying infra.
- **CoreDNS stays `0/1 Running` and kube-proxy logs iptables rule errors** —
  the kind config sets kube-proxy to `nftables` mode for this host. If an old
  cluster already exists, destroy and recreate it so the networking mode is applied.
  If kube-proxy still fails, compare `uname -r` with `ls /usr/lib/modules`. On the
  verified machine, the running kernel was `7.0.5-arch1-1` while only
  `7.0.7-arch2-1` modules were installed, so kube-proxy could not program Service
  networking until rebooting into the installed kernel or installing matching modules.
- **`Error: failed to load image: image ... not present`** — you skipped step 5
  (`kind load docker-image`).
- **`Provider registry.terraform.io/tehcyx/kind ... not found`** — re-run
  `terraform init`; first-time download.
