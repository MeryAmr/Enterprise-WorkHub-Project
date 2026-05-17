variable "kubeconfig_path" {
  description = "Path to the kubeconfig for the target Kubernetes cluster."
  type        = string
  default     = "~/.kube/config"
}

variable "kube_context" {
  description = "Kubeconfig context Terraform should use. For local kind, this is kind-workhub."
  type        = string
  default     = "kind-workhub"
}

variable "namespace" {
  description = "Kubernetes namespace for the workhub app workload."
  type        = string
  default     = "workhub"
}

variable "backend_image" {
  description = "Container image for the backend. For local kind, build it and load it with `kind load docker-image ...` before apply."
  type        = string
  default     = "workhub-backend:dev"
}

variable "replicas" {
  description = "Backend replica count."
  type        = number
  default     = 2
}

variable "db_url" {
  description = "JDBC URL the backend uses to reach Postgres. Cluster-internal DNS is the default."
  type        = string
  default     = "jdbc:postgresql://postgres.workhub.svc.cluster.local:5432/workhub"
}

variable "db_username" {
  description = "Postgres username injected into the backend ConfigMap."
  type        = string
  default     = "workhub"
}

variable "db_name" {
  description = "Postgres database name."
  type        = string
  default     = "workhub"
}

variable "postgres_image" {
  description = "Postgres container image for the local Kubernetes stack."
  type        = string
  default     = "postgres:16"
}

variable "postgres_storage" {
  description = "Persistent volume size requested by the Postgres StatefulSet."
  type        = string
  default     = "1Gi"
}

variable "kafka_image" {
  description = "Kafka container image for the local Kubernetes stack."
  type        = string
  default     = "apache/kafka:3.7.0"
}

variable "kafka_storage" {
  description = "Persistent volume size requested by the Kafka StatefulSet."
  type        = string
  default     = "1Gi"
}

variable "kafka_cluster_id" {
  description = "Static KRaft cluster ID used by the single-node local Kafka broker."
  type        = string
  default     = "4L6g3nShT-eMCtK--X86sw"
}

variable "db_password" {
  description = "Postgres password injected into the backend Secret and Terraform-managed Postgres StatefulSet."
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "JWT signing secret. Base64-encoded HMAC key used by Spring Security."
  type        = string
  sensitive   = true
}

variable "kafka_bootstrap" {
  description = "Kafka bootstrap servers reachable from the backend pods."
  type        = string
  default     = "kafka.workhub.svc.cluster.local:9092"
}
