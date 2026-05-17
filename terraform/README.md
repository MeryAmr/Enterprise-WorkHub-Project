# Terraform — WorkHub IaC (Track 2: Kubernetes)

This Terraform stack provisions Kubernetes resources into an existing cluster.
For the Phase 3 local deployment, that cluster is a kind cluster created from
`../k8s/kind-config.yaml`.

Terraform owns:

- `workhub` namespace
- backend ConfigMap and Secret
- Postgres headless Service and StatefulSet
- Kafka headless Service and StatefulSet
- backend Deployment with Actuator readiness/liveness probes
- backend NodePort Service

Terraform intentionally does **not** create the kind cluster and does **not**
build Docker images. That matches the Phase 3 Track 2 wording: Terraform must
provision Kubernetes resources; cluster resources are optional.

## Delivery Model

The clean delivery order is:

1. A Kubernetes cluster exists.
2. CI or local tooling builds the backend image.
3. The image is made available to the cluster.
4. Terraform applies the Kubernetes resources and waits for readiness.

For local kind, step 3 is `kind load docker-image`. For a real shared
environment, step 3 is normally `docker push` to a registry, and `backend_image`
points at an immutable registry tag such as `ghcr.io/<org>/workhub-backend:<sha>`.

## Local Kind From Scratch

```bash
# From repo root
kind create cluster --name workhub --config k8s/kind-config.yaml
kubectl --context kind-workhub -n kube-system rollout status deployment/coredns --timeout=120s

docker build -t workhub-backend:dev ./backend
kind load docker-image workhub-backend:dev --name workhub

terraform -chdir=terraform init
terraform -chdir=terraform fmt -check
terraform -chdir=terraform validate
terraform -chdir=terraform apply -var-file=terraform.tfvars.example -auto-approve

curl "$(terraform -chdir=terraform output -raw backend_host_url)/actuator/health/readiness"
```

Expected verification:

```json
{"status":"UP"}
```

## Registry-Backed Apply

If `backend_image` points to a pullable registry image, the flow is the same
except there is no `kind load` step:

```bash
terraform -chdir=terraform init
terraform -chdir=terraform validate
terraform -chdir=terraform apply   -var-file=terraform.tfvars.example   -var='backend_image=ghcr.io/<org>/workhub-backend:<sha>'
```

## Teardown

Destroy Terraform-managed Kubernetes resources:

```bash
terraform -chdir=terraform destroy -var-file=terraform.tfvars.example -auto-approve
```

Delete the local kind cluster separately:

```bash
kind delete cluster --name workhub
```

## Notes

- Do not run `kubectl apply -k ../k8s/infra/` during the Terraform path. Postgres
  and Kafka are Terraform-managed in this path.
- The `k8s/` directory remains useful for the raw manifest rubric path and for
  reviewing the equivalent Kubernetes shape.
- The backend image must be available to the target cluster before Terraform
  creates/rolls the backend Deployment.
- If you previously used a Terraform state that tracked `kind_cluster.workhub`,
  remove that stale state entry once with:

```bash
terraform -chdir=terraform state rm kind_cluster.workhub
```

That command does not delete the cluster; it only stops Terraform tracking it.
