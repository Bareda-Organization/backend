#!/usr/bin/env bash
# Postgres 덤프와 학생 사진을 S3 로 올린다. /etc/cron.d/schoolbus-backup 이 매일 03:10 에 호출한다.
#
# 보관 기간은 스크립트가 아니라 S3 수명주기 규칙(db/·photos/ 각 7일)으로 관리한다 —
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

# 학생 사진 — named volume(photo-data)을 backend 컨테이너 안에서 묶어 임시 파일 없이 S3 로 흘린다.
# ⚠ `run` 이 아니라 `exec` 다: run 으로 띄운 컨테이너는 stdout 이 로깅 드라이버(CloudWatch)로도 가서 사진 바이너리가
#   로그로 올라간다. exec 의 출력은 클라이언트로만 온다. 그래서 backend 가 떠 있어야 하고, 꺼져 있으면 이 단계가 실패한다.
# 매번 전체를 묶는다(증분 아님) — 사진이 수 GB 를 넘으면 `aws s3 sync` 방식으로 바꾼다.
# 복원: docs/infra/DEPLOYMENT.md §7.2
PHOTO_KEY="photos/$STAMP.tar.gz"
docker compose -f "$APP_DIR/docker-compose.prod.yml" --env-file "$APP_DIR/.env" \
    exec -T backend tar czf - -C /app/var photos \
    | aws s3 cp --region "$AWS_REGION" - "s3://$BUCKET/$PHOTO_KEY"
echo "사진 백업 완료: s3://$BUCKET/$PHOTO_KEY"
