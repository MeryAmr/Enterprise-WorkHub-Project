CLUSTER_NAME ?= workhub
KUBE_CONTEXT ?= kind-workhub
KIND_CONFIG ?= k8s/kind-config.yaml
BACKEND_IMAGE ?= workhub-backend:dev
TF_DIR ?= terraform
TFVARS ?= terraform.tfvars.example
BACKEND_URL ?= http://localhost:8080

.PHONY: help up down restart cluster build-image load-image terraform-init terraform-apply verify status logs

help:
	@echo "WorkHub local Kubernetes commands"
	@echo ""
	@echo "  make up       Create kind cluster, build/load backend image, apply Terraform"
	@echo "  make down     Destroy Terraform resources and delete the kind cluster"
	@echo "  make restart  Run make down, then make up"
	@echo "  make status   Show WorkHub pods/services"
	@echo "  make logs     Follow backend logs"
	@echo ""
	@echo "Optional overrides:"
	@echo "  BACKEND_IMAGE=$(BACKEND_IMAGE)"
	@echo "  TFVARS=$(TFVARS)"

up: cluster build-image load-image terraform-apply verify

down:
	@if kind get clusters 2>/dev/null | grep -qx "$(CLUSTER_NAME)"; then \
		if [ -d "$(TF_DIR)/.terraform" ]; then \
			terraform -chdir=$(TF_DIR) destroy -var-file=$(TFVARS) -var='backend_image=$(BACKEND_IMAGE)' -auto-approve; \
		else \
			echo "Terraform is not initialized; skipping terraform destroy."; \
		fi; \
		kind delete cluster --name $(CLUSTER_NAME); \
	else \
		echo "No kind cluster named $(CLUSTER_NAME) is running."; \
	fi

restart: down up

cluster:
	@if kind get clusters 2>/dev/null | grep -qx "$(CLUSTER_NAME)"; then \
		echo "kind cluster $(CLUSTER_NAME) already exists."; \
	else \
		kind create cluster --name $(CLUSTER_NAME) --config $(KIND_CONFIG); \
	fi
	@kubectl --context $(KUBE_CONTEXT) -n kube-system rollout status deployment/coredns --timeout=120s

build-image:
	docker build -t $(BACKEND_IMAGE) ./backend

load-image:
	kind load docker-image $(BACKEND_IMAGE) --name $(CLUSTER_NAME)

terraform-init:
	terraform -chdir=$(TF_DIR) init
	terraform -chdir=$(TF_DIR) validate

terraform-apply: terraform-init
	terraform -chdir=$(TF_DIR) apply -var-file=$(TFVARS) -var='backend_image=$(BACKEND_IMAGE)' -auto-approve

verify:
	@curl -fsS "$(BACKEND_URL)/actuator/health/readiness"
	@echo ""

status:
	kubectl --context $(KUBE_CONTEXT) -n workhub get pods,svc

logs:
	kubectl --context $(KUBE_CONTEXT) -n workhub logs -f deployment/backend
