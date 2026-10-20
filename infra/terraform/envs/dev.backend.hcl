# Partial backend config for dev.
#
# `conn_str` is intentionally absent — it carries the Postgres password. Pass it
# at init time:
#
#   terraform init -backend-config=envs/dev.backend.hcl \
#                  -backend-config="conn_str=$PG_CONN_STR"
#
# where PG_CONN_STR points at the stack's Postgres, e.g.
#   postgres://user:password@10.10.73.8:5432/sso_risa?sslmode=disable
#
# The backend creates the schema below on first init and takes an advisory lock
# per apply, so CI and a laptop cannot apply at the same time.

schema_name = "terraform_sso_dev"
