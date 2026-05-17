output "kube_context" {
  description = "Kubeconfig context Terraform used."
  value       = var.kube_context
}

output "kubeconfig_path" {
  description = "Path to the kubeconfig Terraform used."
  value       = pathexpand(var.kubeconfig_path)
}

output "namespace_name" {
  description = "Namespace the backend was deployed into."
  value       = kubernetes_namespace.workhub.metadata[0].name
}

output "backend_nodeport" {
  description = "NodePort exposed by the backend Service. Reachable on the host at http://localhost:8080 via k8s/kind-config.yaml extraPortMappings."
  value       = 30080
}

output "backend_host_url" {
  description = "URL to hit the backend from the host machine when using k8s/kind-config.yaml."
  value       = "http://localhost:8080"
}
