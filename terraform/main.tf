terraform {
  required_version = ">= 1.5.0"

  required_providers {
    kind = {
      source  = "tehcyx/kind"
      version = "~> 0.11.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.38.0"
    }
  }
}

# 1. Local kind cluster — fully IaC-managed.
resource "kind_cluster" "workhub" {
  name            = var.cluster_name
  wait_for_ready  = true
  kubeconfig_path = pathexpand(var.kubeconfig_path)

  kind_config {
    kind        = "Cluster"
    api_version = "kind.x-k8s.io/v1alpha4"

    networking {
      # Arch + Docker setups that use nf_tables can fail kube-proxy's legacy
      # iptables rules, which leaves CoreDNS unable to reach the API Service.
      kube_proxy_mode = "nftables"
    }

    node {
      role = "control-plane"
      kubeadm_config_patches = [
        <<-EOT
        kind: KubeProxyConfiguration
        apiVersion: kubeproxy.config.k8s.io/v1alpha1
        mode: nftables
        EOT
      ]

      extra_port_mappings {
        container_port = 30080
        host_port      = 8080
        listen_address = "0.0.0.0"
        protocol       = "TCP"
      }
    }
  }
}

# Kubernetes provider talks to the cluster the resource above just created.
provider "kubernetes" {
  host                   = kind_cluster.workhub.endpoint
  cluster_ca_certificate = kind_cluster.workhub.cluster_ca_certificate
  client_certificate     = kind_cluster.workhub.client_certificate
  client_key             = kind_cluster.workhub.client_key
}

# 2. Namespace.
resource "kubernetes_namespace" "workhub" {
  metadata {
    name = var.namespace
    labels = {
      "app.kubernetes.io/name"    = "workhub"
      "app.kubernetes.io/part-of" = "workhub-platform"
    }
  }
}

# 3. Backend non-secret config (datasource URL/username, Kafka, profile).
resource "kubernetes_config_map" "backend" {
  metadata {
    name      = "backend-config"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels    = { app = "backend" }
  }

  data = {
    SPRING_PROFILES_ACTIVE     = "k8s"
    SPRING_DATASOURCE_URL      = var.db_url
    SPRING_DATASOURCE_USERNAME = var.db_username
    KAFKA_BOOTSTRAP_SERVERS    = var.kafka_bootstrap
  }
}

# 4. Backend secrets (DB password + JWT).
resource "kubernetes_secret" "backend" {
  metadata {
    name      = "backend-secret"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels    = { app = "backend" }
  }

  type = "Opaque"

  data = {
    SPRING_DATASOURCE_PASSWORD = var.db_password
    JWT_SECRET                 = var.jwt_secret
  }
}

# 5. Backend Deployment — probes hit Spring Boot Actuator.
resource "kubernetes_deployment" "backend" {
  # The local image and stateful dependencies are loaded/applied immediately
  # after Terraform creates the cluster and workload objects.
  wait_for_rollout = false

  metadata {
    name      = "backend"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels = {
      app                           = "backend"
      "app.kubernetes.io/name"      = "workhub-backend"
      "app.kubernetes.io/component" = "api"
    }
  }

  spec {
    replicas = var.replicas

    selector {
      match_labels = { app = "backend" }
    }

    strategy {
      type = "RollingUpdate"
      rolling_update {
        max_surge       = "1"
        max_unavailable = "0"
      }
    }

    template {
      metadata {
        labels = {
          app                           = "backend"
          "app.kubernetes.io/name"      = "workhub-backend"
          "app.kubernetes.io/component" = "api"
        }
      }

      spec {
        container {
          name              = "backend"
          image             = var.backend_image
          image_pull_policy = "IfNotPresent"

          port {
            name           = "http"
            container_port = 8080
          }

          env_from {
            config_map_ref {
              name = kubernetes_config_map.backend.metadata[0].name
            }
          }

          env_from {
            secret_ref {
              name = kubernetes_secret.backend.metadata[0].name
            }
          }

          readiness_probe {
            http_get {
              path = "/actuator/health/readiness"
              port = "http"
            }
            initial_delay_seconds = 20
            period_seconds        = 10
            timeout_seconds       = 3
            failure_threshold     = 6
          }

          liveness_probe {
            http_get {
              path = "/actuator/health/liveness"
              port = "http"
            }
            initial_delay_seconds = 60
            period_seconds        = 20
            timeout_seconds       = 3
            failure_threshold     = 5
          }

          resources {
            requests = {
              cpu    = "200m"
              memory = "512Mi"
            }
            limits = {
              cpu    = "1000m"
              memory = "1Gi"
            }
          }

          security_context {
            allow_privilege_escalation = false
            read_only_root_filesystem  = false
            run_as_non_root            = true
            run_as_user                = 10001
            run_as_group               = 10001
            capabilities {
              drop = ["ALL"]
            }
          }
        }
      }
    }
  }
}

# 6. Backend Service — NodePort matches kind extra_port_mappings.
resource "kubernetes_service" "backend" {
  metadata {
    name      = "backend"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels    = { app = "backend" }
  }

  spec {
    type = "NodePort"
    selector = {
      app = "backend"
    }
    port {
      name        = "http"
      port        = 8080
      target_port = "http"
      node_port   = 30080
      protocol    = "TCP"
    }
  }
}
