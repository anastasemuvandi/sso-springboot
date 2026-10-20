# =============================================================================
# GoRSSO / SSO.RISA developer commands.   `make help` lists them.
#
# WRAPPER OPS SUBCOMMANDS. Several targets run the wrapper jar as a CLI
# (`java -jar sso-wrapper/target/sso-wrapper-*.jar <subcommand>`, see
# sso-wrapper/src/main/java/rw/gov/risa/sso/wrapper/cli/OpsCommand.java). The wrapper must provide:
#   delegated-validate   templates.json is safe (guardrail, namespaces); no Keycloak        (exit != 0 = unsafe)
#   delegated-apply      create the inst:<slug>:* roles the templates require
#   delegated-check      audit Keycloak: guardrail violations / drift                        (CI gate)
#   fgap-plan            render configs/generated/fgap-v2-plan.json (CRLF, byte-identical)
#   fgap-plan-check      fail when the committed plan is stale                               (CI gate)
#   registry-validate    configs/institutions.yaml rules (slugs, prefixes, ippis codes)      (CI gate)
#   mfa-install          build + bind the browser-totp flow through the Admin API (offline installs)
#   mfa-enrol            bulk CONFIGURE_TOTP for users without OTP (idempotent, resumable)
#   verify               Keycloak-direct FGAP acceptance as a real institution admin (9/9)
#   ippis-reconcile      one IPPIS pull reconcile (delta; --full for a full run)
# Ops commands read the same env as the server (KEYCLOAK_*, IPPIS_*, ...). Against the dev stack,
# source deploy/dev-cli.env first or pass the variables explicitly (see README.md).
# =============================================================================

SHELL := /bin/bash
.DEFAULT_GOAL := help

PROJECT ?= gorsso
ENV_FILE ?= deploy/.env
COMPOSE_FILES := -f deploy/docker-compose.base.yml -f deploy/docker-compose.apps.yml
COMPOSE := docker compose -p $(PROJECT) $(if $(wildcard $(ENV_FILE)),--env-file $(ENV_FILE)) $(COMPOSE_FILES)

MVN := mvn -B
NODE_PATH_PREFIX := export PATH=$(CURDIR)/.tools/node/bin:$$PATH
WRAPPER_JAR = $(firstword $(filter-out %.original,$(wildcard sso-wrapper/target/sso-wrapper-*.jar)))
WRAPPER_CLI = java -jar $(WRAPPER_JAR)

TF_ENV ?= dev
TF_DIR := infra/terraform
# terraform from PATH, else the pinned docker image.
TERRAFORM ?= $(if $(shell command -v terraform 2>/dev/null),terraform,docker run --rm -i --user $$(id -u):$$(id -g) -e PG_CONN_STR -e TF_VAR_keycloak_url -e TF_VAR_keycloak_admin_username -e TF_VAR_keycloak_admin_password -v $(CURDIR):/repo -w /repo/$(TF_DIR) hashicorp/terraform:1.9.8)

.PHONY: help build test it start stop clean logs ps seed console-dev openapi openapi-check \
        fgap-plan fgap-plan-check delegated-validate delegated-apply delegated-check registry-validate \
        tf-fmt tf-validate tf-init tf-plan tf-apply tf-output mfa-start mfa-install mfa-enrol sdk verify \
        wrapper-jar parent images smoke

help:
	@echo "Build and test"
	@echo "  build              Maven package of every module (skip tests) + console-web build"
	@echo "  test               unit tests: every Maven module, console-web, keycloak-theme, sdk/react"
	@echo "  it                 + Testcontainers suites (mvn -Pit verify; needs Docker)"
	@echo "  images             build every image of the compose stack"
	@echo "Stack (project '$(PROJECT)'; deploy/.env is used when present)"
	@echo "  start              up -d --build: Keycloak, Postgres, Redis, wrapper, console, audit, notification, IPPIS mock, MailHog, Adminer + seed"
	@echo "  seed               re-run the idempotent dev seeder (minagri, minisante, control plane, demo users, FGAP)"
	@echo "  stop | clean       down | down -v (wipes every volume)"
	@echo "  logs [S=wrapper]   follow logs;  ps  service status"
	@echo "  console-dev        Vite dev server for console-web (proxy to a running BFF)"
	@echo "  mfa-start          start the stack with the MFA overlay (runs mfa-install)"
	@echo "Contracts and config gates"
	@echo "  openapi            regenerate the committed openapi.json of wrapper, audit, notification"
	@echo "  openapi-check      fail when any committed openapi.json is stale"
	@echo "  fgap-plan          render configs/generated/fgap-v2-plan.json;  fgap-plan-check  fail when stale"
	@echo "  delegated-validate | delegated-apply | delegated-check"
	@echo "  registry-validate  check configs/institutions.yaml"
	@echo "Terraform (TF_ENV=$(TF_ENV))"
	@echo "  tf-fmt | tf-validate (offline) | tf-init | tf-plan | tf-apply | tf-output"
	@echo "Operations"
	@echo "  mfa-install        install the browser-totp flow (offline alternative to Terraform)"
	@echo "  mfa-enrol          bulk CONFIGURE_TOTP (MFA_DRY_RUN=1 for a dry run)"
	@echo "  verify             Keycloak-direct FGAP acceptance (9/9)"
	@echo "  sdk                regenerate the Java/TypeScript SDKs from sso-wrapper/openapi.json"

# ---------------------------------------------------------------------------- build / test
parent:
	$(MVN) -q -N install
	$(MVN) -q -f mfa-policy/pom.xml install -DskipTests

build: parent
	$(MVN) package -DskipTests
	$(MVN) -q -f tools/seeder/pom.xml package -DskipTests
	$(NODE_PATH_PREFIX) && cd console-web && npm ci --no-audit --no-fund && npm run build

wrapper-jar: parent
	@if [ -z "$(WRAPPER_JAR)" ]; then $(MVN) -q -f sso-wrapper/pom.xml package -DskipTests; fi

test: parent
	$(MVN) verify
	$(MVN) -q -f tools/seeder/pom.xml test
	$(NODE_PATH_PREFIX) && cd console-web && npm ci --no-audit --no-fund && npm test
	$(NODE_PATH_PREFIX) && cd keycloak-theme && npm ci --no-audit --no-fund && npm test
	$(NODE_PATH_PREFIX) && cd sdk/react && npm ci --no-audit --no-fund && npm test

it: parent
	$(MVN) -Pit verify

images:
	DOCKER_BUILDKIT=1 $(COMPOSE) build

# ---------------------------------------------------------------------------- stack
start:
	$(COMPOSE) up -d --build

stop:
	$(COMPOSE) down

clean:
	$(COMPOSE) down -v

logs:
	$(COMPOSE) logs -f $(S)

ps:
	$(COMPOSE) ps

seed:
	$(COMPOSE) run --rm --build seed

mfa-start:
	$(COMPOSE) -f deploy/docker-compose.mfa.yml up -d --build

console-dev:
	$(NODE_PATH_PREFIX) && cd console-web && npm ci --no-audit --no-fund && npm run dev

# ---------------------------------------------------------------------------- contracts
openapi: parent
	$(MVN) -q -f sso-wrapper/pom.xml test -Dtest=OpenApiContractTest -Dopenapi.update=true -Dsurefire.failIfNoSpecifiedTests=false
	$(MVN) -q -f audit-service/pom.xml test -Dtest=OpenApiSpecTest -Dopenapi.write=true -Dsurefire.failIfNoSpecifiedTests=false
	$(MVN) -q -f notification-service/pom.xml test -Dtest=OpenApiStalenessTest -Dopenapi.update=true -Dsurefire.failIfNoSpecifiedTests=false

openapi-check: parent
	$(MVN) -q -f sso-wrapper/pom.xml test -Dtest=OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false
	$(MVN) -q -f audit-service/pom.xml test -Dtest=OpenApiSpecTest -Dsurefire.failIfNoSpecifiedTests=false
	$(MVN) -q -f notification-service/pom.xml test -Dtest=OpenApiStalenessTest -Dsurefire.failIfNoSpecifiedTests=false

fgap-plan: wrapper-jar
	$(WRAPPER_CLI) fgap-plan

fgap-plan-check: wrapper-jar
	$(WRAPPER_CLI) fgap-plan-check

delegated-validate: wrapper-jar
	$(WRAPPER_CLI) delegated-validate

delegated-apply: wrapper-jar
	$(WRAPPER_CLI) delegated-apply

delegated-check: wrapper-jar
	$(WRAPPER_CLI) delegated-check

registry-validate: wrapper-jar
	$(WRAPPER_CLI) registry-validate

# ---------------------------------------------------------------------------- terraform
# Credentials are NEVER in envs/*.tfvars. Export TF_VAR_keycloak_url, TF_VAR_keycloak_admin_username,
# TF_VAR_keycloak_admin_password and PG_CONN_STR (the pg state backend) first.
tf-fmt:
	$(TERRAFORM) fmt -recursive

# Offline: no Keycloak, no backend, no credentials. Mirrors the CI job.
tf-validate:
	$(TERRAFORM) fmt -check -recursive -diff
	$(TERRAFORM) init -backend=false -input=false >/dev/null
	$(TERRAFORM) validate

tf-init:
	@if [ -z "$$PG_CONN_STR" ]; then echo "PG_CONN_STR is required, e.g. postgres://gorsso:...@localhost:5435/gorsso?sslmode=disable"; exit 1; fi
	$(TERRAFORM) init -reconfigure -backend-config=envs/$(TF_ENV).backend.hcl -backend-config="conn_str=$$PG_CONN_STR"

tf-plan: tf-init
	$(TERRAFORM) plan -var-file=envs/$(TF_ENV).tfvars

tf-apply: tf-init
	$(TERRAFORM) apply -var-file=envs/$(TF_ENV).tfvars

tf-output:
	$(TERRAFORM) output -json institutions

# ---------------------------------------------------------------------------- operations
mfa-install: wrapper-jar
	$(WRAPPER_CLI) mfa-install

MFA_CHECKPOINT ?= .mfa-enrol-checkpoint.json
mfa-enrol: wrapper-jar
	$(WRAPPER_CLI) mfa-enrol --checkpoint $(MFA_CHECKPOINT) $(if $(MFA_DRY_RUN),--dry-run)

verify: wrapper-jar
	$(WRAPPER_CLI) verify

sdk:
	./sdk/generate.sh

# ---------------------------------------------------------------------------- smoke (running stack)
# End-to-end checks against a running, seeded stack: API (login gates, audit, delegated admin,
# directory, ops commands, brute-force alert) then a real browser login through the console.
# Same HOST_PORT_* variables as compose (defaults = the dev ports).
smoke:
	deploy/smoke/api-smoke.sh
	$(NODE_PATH_PREFIX) && cd console-web && npx playwright install chromium >/dev/null
	$(NODE_PATH_PREFIX) && CONSOLE_URL=http://localhost:$${HOST_PORT_CONSOLE:-3001} node deploy/smoke/console-login.mjs
