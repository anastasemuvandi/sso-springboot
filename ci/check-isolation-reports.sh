#!/bin/sh
# Fail when a required isolation scenario class is missing from the surefire
# reports or ran zero tests (SPEC §7.1).
#
#   ci/check-isolation-reports.sh <surefire-reports-dir> <required-list-file>
#
# Counts <testcase> elements per classname across every TEST-*.xml. A @Nested
# class reported as `Outer$Inner` counts towards `Outer`. Only grep/sed/awk.
set -eu

reports_dir=${1:?usage: $0 <surefire-reports-dir> <required-list>}
required=${2:?usage: $0 <surefire-reports-dir> <required-list>}

if [ ! -f "$required" ]; then
  echo "ERROR: required-class list '$required' not found" >&2
  exit 2
fi

counts=$(mktemp)
trap 'rm -f "$counts"' EXIT

if [ -d "$reports_dir" ]; then
  # One line per <testcase ...> with its classname, outer class only.
  find "$reports_dir" -name 'TEST-*.xml' -type f -exec cat {} + 2>/dev/null \
    | tr '\n' ' ' \
    | sed 's/<testcase /\n<testcase /g' \
    | sed -n 's/^<testcase [^>]*classname="\([^"]*\)".*/\1/p' \
    | sed 's/\$.*//' \
    | sort | uniq -c | awk '{print $2, $1}' > "$counts"
else
  echo "WARNING: reports directory '$reports_dir' does not exist" >&2
fi

missing=0
echo "Isolation scenario classes:"
while IFS= read -r line || [ -n "$line" ]; do
  cls=$(printf '%s' "$line" | sed 's/#.*//' | tr -d ' \t\r')
  [ -z "$cls" ] && continue
  n=$(awk -v c="$cls" '$1 == c { print $2 }' "$counts")
  n=${n:-0}
  if [ "$n" -eq 0 ]; then
    echo "  FAIL  $cls: 0 tests (missing or empty)"
    missing=$((missing + 1))
  else
    echo "  ok    $cls: $n test(s)"
  fi
done < "$required"

if [ "$missing" -gt 0 ]; then
  echo "ERROR: $missing required isolation class(es) collected 0 tests." >&2
  echo "Rename/move a scenario class only together with ci/isolation-required.txt." >&2
  exit 1
fi
echo "All required isolation scenario classes ran."
