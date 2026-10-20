terraform {
  required_version = ">= 1.5.0"

  required_providers {
    keycloak = {
      # The official provider (formerly mrparkers/keycloak). v5 targets the
      # Quarkus distribution, which is what we run (keycloak/Dockerfile, 26.2).
      source  = "keycloak/keycloak"
      version = "~> 5.0"
    }
  }

  # State backend — the stack's own PostgreSQL.
  #
  # Deliberately NOT a cloud backend: the deployment target is an on-prem,
  # HTTP-only box (10.10.73.8) with no cloud account, and `local` state cannot
  # be shared between an engineer's laptop and the CI runner. The Postgres that
  # already backs Keycloak gives us locking and a shared store for free.
  #
  # Configured partially — pass the connection string at init time so it never
  # lands in version control:
  #
  #   terraform init -backend-config=envs/dev.backend.hcl \
  #                  -backend-config="conn_str=$PG_CONN_STR"
  backend "pg" {}
}
