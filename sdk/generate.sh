#!/usr/bin/env bash
# =============================================================================
# Generate the GoRSSO client SDKs from the wrapper's OpenAPI contract.
#
#   sdk/generate.sh                    # java + typescript from sso-wrapper/openapi.json
#   sdk/generate.sh java               # one target (java | typescript | python)
#   SPEC=/path/to/openapi.json sdk/generate.sh
#   VERIFY=1 sdk/generate.sh           # also compile what was generated
#
# Output goes to sdk/<target>/ and is NOT committed (see .gitignore); CI regenerates it
# from the committed contract. openapi-generator runs in Docker, pinned below, so every
# machine and CI produce byte-identical output for the same spec.
# =============================================================================
set -euo pipefail

GENERATOR_IMAGE="${GENERATOR_IMAGE:-openapitools/openapi-generator-cli:v7.14.0}"

SDK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SDK_DIR}/.." && pwd)"
SPEC="${SPEC:-${REPO_ROOT}/sso-wrapper/openapi.json}"
VERIFY="${VERIFY:-0}"

targets=("$@")
if [[ ${#targets[@]} -eq 0 || "${targets[0]}" == "all" ]]; then
  targets=(java typescript)
fi

generator_for() {
  case "$1" in
    java) echo "java" ;;
    typescript) echo "typescript-fetch" ;;
    python) echo "python" ;;
    *) echo "unknown target: $1 (java | typescript | python)" >&2; return 1 ;;
  esac
}

if [[ ! -f "${SPEC}" ]]; then
  echo "error: OpenAPI spec not found: ${SPEC}" >&2
  echo "       (the wrapper writes it: mvn -f sso-wrapper/pom.xml test -Dtest=OpenApiContractTest -Dopenapi.update=true)" >&2
  exit 1
fi
command -v docker >/dev/null || { echo "error: docker is required" >&2; exit 1; }

SPEC_ABS="$(cd "$(dirname "${SPEC}")" && pwd)/$(basename "${SPEC}")"
SPEC_DIR="$(dirname "${SPEC_ABS}")"
SPEC_FILE="$(basename "${SPEC_ABS}")"

run_generator() {
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "${SDK_DIR}:/sdk" \
    -v "${SPEC_DIR}:/spec:ro" \
    "${GENERATOR_IMAGE}" "$@"
}

echo "==> spec:      ${SPEC_ABS}"
echo "==> generator: ${GENERATOR_IMAGE}"
run_generator validate -i "/spec/${SPEC_FILE}" || {
  echo "error: the spec does not validate; fix the contract first" >&2
  exit 1
}

for target in "${targets[@]}"; do
  gen="$(generator_for "${target}")"
  out="${SDK_DIR}/${target}"
  echo "==> ${target} (${gen}) -> sdk/${target}"
  rm -rf "${out}"
  mkdir -p "${out}"
  run_generator generate \
    -g "${gen}" \
    -i "/spec/${SPEC_FILE}" \
    -c "/sdk/config/${target}.yaml" \
    -o "/sdk/${target}" \
    --skip-validate-spec >/dev/null
  echo "    $(find "${out}" -type f | wc -l) files"
done

if [[ "${VERIFY}" == "1" ]]; then
  for target in "${targets[@]}"; do
    case "${target}" in
      java)
        echo "==> verify java: mvn package"
        mvn -q -B -f "${SDK_DIR}/java/pom.xml" -DskipTests -Dmaven.javadoc.skip=true package
        ;;
      typescript)
        echo "==> verify typescript: tsc"
        if [[ -x "${REPO_ROOT}/.tools/node/bin/node" ]]; then export PATH="${REPO_ROOT}/.tools/node/bin:${PATH}"; fi
        (cd "${SDK_DIR}/typescript" && npm install --no-audit --no-fund --silent && npx --no-install tsc -p . --noEmit)
        ;;
      *) echo "==> verify ${target}: skipped" ;;
    esac
  done
fi

echo "==> done"
