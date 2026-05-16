output "cluster_name" {
  description = "Name of the kind cluster Terraform created."
  value       = kind_cluster.workhub.name
}

output "kubeconfig_path" {
  description = "Path on disk to the kubeconfig the kind provider wrote."
  value       = kind_cluster.workhub.kubeconfig_path
}

output "namespace_name" {
  description = "Namespace the backend was deployed into."
  value       = kubernetes_namespace.workhub.metadata[0].name
}

output "backend_nodeport" {
  description = "NodePort exposed by the backend Service. Reachable on the host at http://localhost:<backend_host_port> via kind extra_port_mappings."
  value       = 30080
}

output "backend_host_url" {
  description = "URL to hit the backend from the host machine (kind maps NodePort 30080 -> host 8080)."
  value       = "http://localhost:8080"
}
