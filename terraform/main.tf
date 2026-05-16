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

# 5. Postgres — stateful dependency owned by Terraform for the local stack.
resource "kubernetes_service" "postgres" {
  metadata {
    name      = "postgres"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels    = { app = "postgres" }
  }

  spec {
    cluster_ip = "None"
    selector   = { app = "postgres" }

    port {
      name        = "postgres"
      port        = 5432
      target_port = 5432
      protocol    = "TCP"
    }
  }
}

resource "kubernetes_stateful_set" "postgres" {
  wait_for_rollout = true

  metadata {
    name      = "postgres"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels    = { app = "postgres" }
  }

  spec {
    service_name = kubernetes_service.postgres.metadata[0].name
    replicas     = 1

    selector {
      match_labels = { app = "postgres" }
    }

    template {
      metadata {
        labels = { app = "postgres" }
      }

      spec {
        container {
          name  = "postgres"
          image = var.postgres_image

          port {
            name           = "postgres"
            container_port = 5432
          }

          env {
            name  = "POSTGRES_DB"
            value = var.db_name
          }

          env {
            name  = "POSTGRES_USER"
            value = var.db_username
          }

          env {
            name = "POSTGRES_PASSWORD"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.backend.metadata[0].name
                key  = "SPRING_DATASOURCE_PASSWORD"
              }
            }
          }

          env {
            name  = "PGDATA"
            value = "/var/lib/postgresql/data/pgdata"
          }

          volume_mount {
            name       = "pgdata"
            mount_path = "/var/lib/postgresql/data"
          }

          readiness_probe {
            exec {
              command = ["pg_isready", "-U", var.db_username, "-d", var.db_name]
            }
            initial_delay_seconds = 10
            period_seconds        = 5
            timeout_seconds       = 3
          }

          liveness_probe {
            exec {
              command = ["pg_isready", "-U", var.db_username, "-d", var.db_name]
            }
            initial_delay_seconds = 30
            period_seconds        = 15
          }

          resources {
            requests = {
              cpu    = "100m"
              memory = "256Mi"
            }
            limits = {
              cpu    = "500m"
              memory = "512Mi"
            }
          }
        }
      }
    }

    volume_claim_template {
      metadata {
        name = "pgdata"
      }

      spec {
        access_modes = ["ReadWriteOnce"]
        resources {
          requests = {
            storage = var.postgres_storage
          }
        }
      }
    }
  }
}

# 6. Kafka — single-node KRaft broker for local/course delivery.
resource "kubernetes_service" "kafka" {
  metadata {
    name      = "kafka"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels    = { app = "kafka" }
  }

  spec {
    cluster_ip = "None"
    selector   = { app = "kafka" }

    port {
      name        = "plaintext"
      port        = 9092
      target_port = 9092
      protocol    = "TCP"
    }

    port {
      name        = "controller"
      port        = 9093
      target_port = 9093
      protocol    = "TCP"
    }
  }
}

resource "kubernetes_stateful_set" "kafka" {
  wait_for_rollout = true

  metadata {
    name      = "kafka"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels    = { app = "kafka" }
  }

  spec {
    service_name = kubernetes_service.kafka.metadata[0].name
    replicas     = 1

    selector {
      match_labels = { app = "kafka" }
    }

    template {
      metadata {
        labels = { app = "kafka" }
      }

      spec {
        container {
          name  = "kafka"
          image = var.kafka_image

          port {
            name           = "plaintext"
            container_port = 9092
          }

          port {
            name           = "controller"
            container_port = 9093
          }

          env {
            name  = "KAFKA_NODE_ID"
            value = "1"
          }
          env {
            name  = "KAFKA_PROCESS_ROLES"
            value = "broker,controller"
          }
          env {
            name  = "KAFKA_LISTENERS"
            value = "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093"
          }
          env {
            name  = "KAFKA_ADVERTISED_LISTENERS"
            value = "PLAINTEXT://kafka.${var.namespace}.svc.cluster.local:9092"
          }
          env {
            name  = "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP"
            value = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
          }
          env {
            name  = "KAFKA_CONTROLLER_QUORUM_VOTERS"
            value = "1@kafka-0.kafka.${var.namespace}.svc.cluster.local:9093"
          }
          env {
            name  = "KAFKA_CONTROLLER_LISTENER_NAMES"
            value = "CONTROLLER"
          }
          env {
            name  = "KAFKA_INTER_BROKER_LISTENER_NAME"
            value = "PLAINTEXT"
          }
          env {
            name  = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR"
            value = "1"
          }
          env {
            name  = "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR"
            value = "1"
          }
          env {
            name  = "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR"
            value = "1"
          }
          env {
            name  = "KAFKA_AUTO_CREATE_TOPICS_ENABLE"
            value = "true"
          }
          env {
            name  = "KAFKA_LOG_DIRS"
            value = "/var/lib/kafka/data"
          }
          env {
            name  = "CLUSTER_ID"
            value = var.kafka_cluster_id
          }

          volume_mount {
            name       = "kafka-data"
            mount_path = "/var/lib/kafka/data"
          }

          readiness_probe {
            tcp_socket {
              port = 9092
            }
            initial_delay_seconds = 20
            period_seconds        = 10
          }

          liveness_probe {
            tcp_socket {
              port = 9092
            }
            initial_delay_seconds = 60
            period_seconds        = 20
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
        }
      }
    }

    volume_claim_template {
      metadata {
        name = "kafka-data"
      }

      spec {
        access_modes = ["ReadWriteOnce"]
        resources {
          requests = {
            storage = var.kafka_storage
          }
        }
      }
    }
  }
}

# 7. Backend Deployment — probes hit Spring Boot Actuator.
resource "kubernetes_deployment" "backend" {

  metadata {
    name      = "backend"
    namespace = kubernetes_namespace.workhub.metadata[0].name
    labels = {
      app                           = "backend"
      "app.kubernetes.io/name"      = "workhub-backend"
      "app.kubernetes.io/component" = "api"
    }
  }

  depends_on = [
    kubernetes_stateful_set.postgres,
    kubernetes_stateful_set.kafka,
  ]

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
