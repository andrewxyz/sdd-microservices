#!/usr/bin/env bash
# Seeds the running platform with demo data for the student web app walkthrough.
# Safe to run more than once: exits without changes if any course already exists.
#
# Usage: scripts/seed-demo.sh [base-url]
# Default base-url: http://localhost:9080
set -euo pipefail

BASE="${1:-http://localhost:9080}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${LEARNHUB_ENV_FILE:-$REPO_ROOT/.env}"

if [ -f "$ENV_FILE" ]; then
  # shellcheck source=/dev/null
  set -a
  . "$ENV_FILE"
  set +a
fi

ADMIN_EMAIL="${ADMIN_EMAIL:-admin@learnhub.example}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-change-me-admin-password-1}"

die() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }
step() { printf '==> %s\n' "$1"; }

# Exit early if courses already exist (idempotent guard).
step "Checking for existing courses"
courses_resp="$(curl -fsS "$BASE/api/v1/courses")"
total="$(printf '%s' "$courses_resp" | grep -o '"totalItems":[0-9]*' | cut -d: -f2 || true)"
if [ "${total:-0}" -gt 0 ]; then
  printf 'Courses already exist (%s found). Nothing to do.\n' "$total"
  exit 0
fi

# Sign in as administrator.
step "Signing in as administrator"
admin_resp="$(curl -fsS -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")" \
  || die "Admin login failed. Is the platform up? (scripts/up.sh)"
admin_access="$(printf '%s' "$admin_resp" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)"

auth_header() { printf 'Authorization: Bearer %s' "$1"; }

# Create two categories.
step "Creating categories"
cat_prog_resp="$(curl -fsS -X POST "$BASE/api/v1/categories" \
  -H "$(auth_header "$admin_access")" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Programming"}')"
cat_prog="$(printf '%s' "$cat_prog_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"

cat_design_resp="$(curl -fsS -X POST "$BASE/api/v1/categories" \
  -H "$(auth_header "$admin_access")" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Design"}')"
cat_design="$(printf '%s' "$cat_design_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"

# Register the instructor account.
step "Registering instructor"
INSTRUCTOR_EMAIL="instructor@learnhub.example"
INSTRUCTOR_PASSWORD="Instructor-password-1"

reg_resp="$(curl -fsS -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$INSTRUCTOR_EMAIL\",\"password\":\"$INSTRUCTOR_PASSWORD\"}")"
instructor_id="$(printf '%s' "$reg_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"

# Promote to INSTRUCTOR. Role changes take effect on the account's *next* login (research R15).
curl -fsS -X PATCH "$BASE/api/v1/auth/accounts/$instructor_id/role" \
  -H "$(auth_header "$admin_access")" \
  -H 'Content-Type: application/json' \
  -d '{"role":"INSTRUCTOR"}' >/dev/null

# Sign in as the instructor (role is now effective).
step "Signing in as instructor"
inst_resp="$(curl -fsS -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$INSTRUCTOR_EMAIL\",\"password\":\"$INSTRUCTOR_PASSWORD\"}")"
inst_access="$(printf '%s' "$inst_resp" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)"

# Helper: fetch the current ETag for a course (used before any mutation).
fetch_etag() {
  local course_id="$1"
  local headers_file
  headers_file="$(mktemp)"
  curl -fsS \
    -H "$(auth_header "$inst_access")" \
    -D "$headers_file" \
    -o /dev/null \
    "$BASE/api/v1/courses/$course_id/full"
  grep -i '^etag:' "$headers_file" | tr -d '\r' | awk '{print $2}'
  rm -f "$headers_file"
}

# Helper: create a course, add 3 lessons, then publish it.
create_course() {
  local course_title="$1"
  local description="$2"
  local cat_id="$3"
  local price_cents="$4"
  local level="$5"

  # Create draft.
  local course_resp course_id
  course_resp="$(curl -fsS -X POST "$BASE/api/v1/courses" \
    -H "$(auth_header "$inst_access")" \
    -H 'Content-Type: application/json' \
    -d "{\"title\":\"$course_title\",\"description\":\"$description\",\"categoryId\":\"$cat_id\",\"priceCents\":$price_cents,\"level\":\"$level\"}")"
  course_id="$(printf '%s' "$course_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"

  # Add 3 lessons (each requires the current ETag).
  local etag i
  for i in 1 2 3; do
    etag="$(fetch_etag "$course_id")"
    curl -fsS -X POST "$BASE/api/v1/courses/$course_id/lessons" \
      -H "$(auth_header "$inst_access")" \
      -H "If-Match: $etag" \
      -H 'Content-Type: application/json' \
      -d "{\"title\":\"Lesson $i\",\"durationMinutes\":10,\"content\":\"This is the content for lesson $i of $course_title.\"}" \
      >/dev/null
  done

  # Publish.
  etag="$(fetch_etag "$course_id")"
  curl -fsS -X PATCH "$BASE/api/v1/courses/$course_id/publish" \
    -H "$(auth_header "$inst_access")" \
    -H "If-Match: $etag" \
    >/dev/null

  printf '  Published: %s\n' "$course_title"
}

step "Creating courses"
create_course "Introduction to TypeScript" \
  "A beginner-friendly guide to TypeScript fundamentals." \
  "$cat_prog" 0 "BEGINNER"

create_course "Advanced React Patterns" \
  "Master composable, reusable React component patterns." \
  "$cat_prog" 4999 "INTERMEDIATE"

create_course "UI Design Fundamentals" \
  "Learn the core principles of visual and interaction design." \
  "$cat_design" 2999 "BEGINNER"

create_course "Full-Stack Architecture" \
  "Design and build scalable full-stack systems end to end." \
  "$cat_prog" 9999 "ADVANCED"

printf '\nDemo data seeded.\n'
printf '  Categories : Programming, Design\n'
printf '  Instructor : %s\n' "$INSTRUCTOR_EMAIL"
printf '  Courses    : 4 published (1 free, 3 paid)\n'
