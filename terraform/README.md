# Terraform — WorkHub IaC (Track 2: Kubernetes)

This Terraform stack provisions the local kind cluster and the Kubernetes
runtime stack for WorkHub:

- kind cluster with host port `8080` mapped to backend NodePort `30080`
- `workhub` namespace
- backend ConfigMap and Secret
- Postgres headless Service and StatefulSet
- Kafka headless Service and StatefulSet
- backend Deployment with Actuator readiness/liveness probes
- backend NodePort Service

The kind cluster uses kube-proxy `nftables` mode because this Arch/Docker host
failed kube-proxy's default iptables rule setup when the running kernel and
installed modules were out of sync. The host still needs working netfilter
modules; verify `uname -r` matches a directory under `/usr/lib/modules` if
CoreDNS ever stalls.

## Delivery Model

Terraform does **not** build Docker images. That is deliberate and closer to how
enterprise delivery works: CI/build tooling creates an image, then Terraform
deploys an explicit image tag and waits for Kubernetes readiness.

For a real shared environment, set `backend_image` to a registry image such as
`ghcr.io/<org>/workhub-backend:<sha>` and run one normal `terraform apply`.

For local kind, there is no registry push, so you bootstrap the cluster first,
load the image into kind, then run the full apply.

## Local Kind From Scratch

```bash
# From repo root
cd terraform
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars if needed

terraform init
terraform fmt -check
terraform validate

# 1. Create only the cluster so kind has somewhere to receive the local image.
terraform apply -target=kind_cluster.workhub -auto-approve
cd ..

# 2. Build and load the backend image into that cluster.
docker build -t workhub-backend:dev ./backend
kind load docker-image workhub-backend:dev --name workhub
kubectl --context kind-workhub -n kube-system rollout status deployment/coredns --timeout=120s

# 3. Apply the full Terraform stack. This now waits for Postgres, Kafka,
#    and backend readiness.
cd terraform
terraform apply -auto-approve

# 4. Verify.
curl "$(terraform output -raw backend_host_url)/actuator/health/readiness"
```

Expected verification:

```json
{"status":"UP"}
```

## Registry-Backed Apply

If `backend_image` points to a pullable registry image, the cleaner production-like
path is just:

```bash
cd terraform
terraform init
terraform validate
terraform apply -var='backend_image=ghcr.io/<org>/workhub-backend:<sha>'
```

## Teardown

```bash
cd terraform
terraform destroy -auto-approve
```

Destroying the kind cluster also removes the local PVC data inside that cluster.

## Notes

- Do not run `kubectl apply -k ../k8s/infra/` during the Terraform path. Postgres
  and Kafka are now Terraform-managed.
- The `k8s/` directory remains useful for the raw manifest rubric path and for
  reviewing the equivalent Kubernetes shape.
- The backend image must exist before the final Terraform apply if using local
  kind and `workhub-backend:dev`.
