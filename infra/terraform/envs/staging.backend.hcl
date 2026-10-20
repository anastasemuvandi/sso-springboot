# Partial backend config for staging. See envs/dev.backend.hcl for how conn_str
# is supplied. A distinct schema keeps dev and staging state from colliding in
# the same database.

schema_name = "terraform_sso_staging"
