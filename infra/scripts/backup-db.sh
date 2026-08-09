#!/usr/bin/env bash
# Postgres 덤프를 S3 로 올린다. /etc/cron.d/schoolbus-backup 이 매일 03:10 에 호출한다.
#
# 보관 기간은 스크립트가 아니라 S3 수명주기 규칙(7일)으로 관리한다 —
# 삭제 로직을 스크립트에 두면 버그 하나로 백업 전체가 지워질 수 있다.
set -euo pipefail

APP_DIR=/opt/school-bus
BUCKET="${BACKUP_BUCKET:?BACKUP_BUCKET 미설정}"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
STAMP="$(date +%F-%H%M)"
DUMP="/tmp/schoolbus-$STAMP.sql.gz"

trap 'rm -f "$DUMP"' EXIT

docker compose -f "$APP_DIR/docker-compose.prod.yml" --env-file "$APP_DIR/.env" \
    exec -T postgres pg_dump -U schoolbus schoolbus | gzip > "$DUMP"

# 빈 덤프를 올리면 "백업이 있다"는 착각만 남는다. 파이프 중간 실패는 종료코드로 안 잡히므로
# 크기로 한 번 더 확인한다(정상 덤프는 최소 수십 KB).
SIZE=$(stat -c%s "$DUMP")
if [ "$SIZE" -lt 10240 ]; then
    echo "덤프가 10KB 미만($SIZE B) — 백업 실패로 간주한다" >&2
    exit 1
fi

aws s3 cp --region "$AWS_REGION" "$DUMP" "s3://$BUCKET/db/$STAMP.sql.gz"
echo "백업 완료: s3://$BUCKET/db/$STAMP.sql.gz ($SIZE B)"
