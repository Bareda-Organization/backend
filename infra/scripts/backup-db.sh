#!/usr/bin/env bash
# Postgres 덤프와 학생 사진을 S3 로 올린다. /etc/cron.d/schoolbus-backup 이 호출한다(bootstrap-ec2.sh).
#
# 사용법:  backup-db.sh [db|photos]      — 인자가 없으면 둘 다(첫 배포 직후 손으로 1회 돌릴 때)
#   db      매시 정각 — DB 덤프. 목표 유실 최대 1시간(RPO 1시간 · Ruling 480)
#   photos  매일 03:10 — 학생 사진 묶음(매번 전체라 매시로 돌리면 사진이 늘수록 무겁다). 사진 유실 최대 하루
#
# 보관 기간은 스크립트가 아니라 S3 수명주기 규칙(db/·photos/ 각 7일)으로 관리한다 —
# 삭제 로직을 스크립트에 두면 버그 하나로 백업 전체가 지워질 수 있다.
#
# 성공하면 마지막 줄에서 "성공 시각" 지표 파일을 쓴다(node-exporter textfile 수집기가 읽는다). **S3 에 올린 뒤에만** 쓴다 —
# 그래야 Prometheus 경보 BackupDbStale·BackupPhotosStale(infra/observability/prometheus/alerts.yml)이 "백업이 멈춤"을 본다.
# 실패는 크론 로그(/var/log/schoolbus-backup.log)에만 남고 아무도 읽지 않으므로 지표가 유일한 감지 수단이다.
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/school-bus}"
TEXTFILE_DIR="${TEXTFILE_DIR:-/var/lib/node_exporter/textfile}"
BUCKET="${BACKUP_BUCKET:?BACKUP_BUCKET 미설정}"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
MODE="${1:-all}"
STAMP="$(date +%F-%H%M)"
DUMP="${TMPDIR:-/tmp}/schoolbus-$STAMP.sql.gz"
COMPOSE=(docker compose -f "$APP_DIR/docker-compose.prod.yml" --env-file "$APP_DIR/.env")

case "$MODE" in
    all|db|photos) ;;
    *) echo "사용법: backup-db.sh [db|photos] — 받은 값: $MODE" >&2; exit 2 ;;
esac

trap 'rm -f "$DUMP"' EXIT

record_success() {
    # textfile 수집기는 *.prom 만 읽는다 — 임시 파일에 쓴 뒤 옮겨 반쯤 쓴 파일을 읽히지 않는다.
    local kind="$1" file="$TEXTFILE_DIR/schoolbus_backup_$1.prom"
    mkdir -p "$TEXTFILE_DIR"
    printf '# HELP schoolbus_backup_last_success_timestamp_seconds 마지막 백업 성공 시각(S3 업로드 완료, epoch 초)\n# TYPE schoolbus_backup_last_success_timestamp_seconds gauge\nschoolbus_backup_last_success_timestamp_seconds{kind="%s"} %s\n' \
        "$kind" "$(date +%s)" > "$file.tmp"
    mv "$file.tmp" "$file"
}

backup_db() {
    "${COMPOSE[@]}" exec -T postgres pg_dump -U schoolbus schoolbus | gzip > "$DUMP"

    # 빈 덤프를 올리면 "백업이 있다"는 착각만 남는다 — 정상 덤프는 최소 수십 KB 다.
    local size
    size="$(wc -c < "$DUMP" | tr -d ' ')"
    if [ "$size" -lt 10240 ]; then
        echo "덤프가 10KB 미만($size B) — 백업 실패로 간주한다" >&2
        return 1
    fi

    aws s3 cp --region "$AWS_REGION" "$DUMP" "s3://$BUCKET/db/$STAMP.sql.gz"
    echo "백업 완료: s3://$BUCKET/db/$STAMP.sql.gz ($size B)"
    record_success db
}

# 학생 사진 — named volume(photo-data)을 backend 컨테이너 안에서 묶어 임시 파일 없이 S3 로 흘린다.
# ⚠ `run` 이 아니라 `exec` 다: run 으로 띄운 컨테이너는 stdout 이 로깅 드라이버(CloudWatch)로도 가서 사진 바이너리가
#   로그로 올라간다. exec 의 출력은 클라이언트로만 온다. 그래서 backend 가 떠 있어야 하고, 꺼져 있으면 이 단계가 실패한다.
# 매번 전체를 묶는다(증분 아님) — 사진이 수 GB 를 넘으면 `aws s3 sync` 방식으로 바꾼다.
# 복원: docs/infra/DEPLOYMENT.md §7.2
backup_photos() {
    local key="photos/$STAMP.tar.gz"
    "${COMPOSE[@]}" exec -T backend tar czf - -C /app/var photos | aws s3 cp --region "$AWS_REGION" - "s3://$BUCKET/$key"
    echo "사진 백업 완료: s3://$BUCKET/$key"
    record_success photos
}

[ "$MODE" = photos ] || backup_db
[ "$MODE" = db ] || backup_photos
