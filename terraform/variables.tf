variable "cluster_name" {
  description = "Name of the local kind cluster."
  type        = string
  default     = "workhub"
}

variable "kubeconfig_path" {
  description = "Where the kind provider writes the kubeconfig."
  type        = string
  default     = "~/.kube/config"
}

variable "namespace" {
  description = "Kubernetes namespace for the workhub app workload."
  type        = string
  default     = "workhub"
}

variable "backend_image" {
  description = "Container image for the backend. Must be loaded into kind via `kind load docker-image ... --name <cluster_name>` before apply."
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

variable "db_password" {
  description = "Postgres password injected into the backend Secret. Must match the value Postgres was started with (k8s/infra/postgres.yaml reads the same Secret)."
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
