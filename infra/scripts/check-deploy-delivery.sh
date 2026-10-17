#!/usr/bin/env bash
# 배포 전달 확인(F-1) — deploy.sh 가 `$APP_DIR/<compose 파일>` 로 읽는 파일마다 배포 워크플로가 (1) S3 로 올리고 (2) EC2 로 내려받는지 본다.
#
#   infra/scripts/check-deploy-delivery.sh           # Docker 가 필요 없다(파일만 읽는다)
#
# 종료 코드: 0 = deploy.sh 가 읽는 모든 compose 파일이 두 단계 모두에 있다 · 1 = 하나라도 빠졌다 · 2 = 확인 자체가 깨졌다(읽을 파일이 없거나 deploy.sh 에서 파일 이름을 못 찾음).
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY="$REPO/infra/scripts/deploy.sh"
WORKFLOW="$REPO/.github/workflows/deploy-backend.yml"
[[ -f "$DEPLOY" && -f "$WORKFLOW" ]] || { echo "BROKEN — deploy.sh 또는 deploy-backend.yml 이 없다" >&2; exit 2; }

files="$(grep -oE '\$APP_DIR/docker-compose\.[A-Za-z.-]+\.yml' "$DEPLOY" | sed 's|^\$APP_DIR/||' | sort -u)"
[[ -n "$files" ]] || { echo "BROKEN — deploy.sh 에서 \$APP_DIR/docker-compose.*.yml 를 찾지 못했다" >&2; exit 2; }

verdict=0
while read -r f; do
    [[ -f "$REPO/$f" ]] || { echo "FAIL  $f — 저장소에 없다"; verdict=1; continue; }
    if grep -qF "aws s3 cp $f \"s3://\$BUCKET/$f\"" "$WORKFLOW"; then echo "PASS  $f — S3 로 올린다"; else echo "FAIL  $f — 워크플로가 S3 로 올리지 않는다"; verdict=1; fi
    if grep -qF "aws s3 cp s3://\$BUCKET/$f /opt/school-bus/$f" "$WORKFLOW"; then echo "PASS  $f — EC2 로 내려받는다"; else echo "FAIL  $f — 워크플로가 EC2 로 내려받지 않는다"; verdict=1; fi
done <<<"$files"
exit $verdict
