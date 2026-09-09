#!/usr/bin/env bash
# EC2 최초 1회 세팅 (Amazon Linux 2023).
# SSM Session Manager 로 접속해 실행한다 — SSH 키·22번 포트가 필요 없다.
#
# 사용법:
#   sudo BACKUP_BUCKET=내버킷명 bash bootstrap-ec2.sh
set -euo pipefail

BACKUP_BUCKET="${BACKUP_BUCKET:?BACKUP_BUCKET 미설정 — DB 백업이 갈 S3 버킷명}"
COMPOSE_VERSION="v2.29.7"
APP_DIR=/opt/school-bus

echo "== 1. docker·cron 설치 =="
dnf update -y
# ⚠️ cronie 를 함께 깐다. Amazon Linux 2023 은 cron 을 **기본 포함하지 않는다**(AWS 는
#    systemd timer 대체를 권고한다). 없는 채로 두면 아래 5단계가 /etc/cron.d 를 못 찾아
#    부트스트랩이 마지막에 하드 실패하거나, 디렉터리만 있고 crond 가 안 돌아 DB 백업이
#    조용히 영원히 실행되지 않는다 — 알람이 없어 복구가 필요한 날에야 알게 된다.
dnf install -y docker cronie
systemctl enable --now docker
systemctl enable --now crond

echo "== 2. compose v2 플러그인 설치 =="
# Amazon Linux 2023 에는 docker-compose-plugin 패키지가 없어 바이너리를 직접 놓는다.
mkdir -p /usr/local/lib/docker/cli-plugins
# ⚠️ 자산 이름은 소문자다(docker-compose-linux-x86_64). `uname -s` 는 "Linux" 를 주는데,
#    GitHub 다운로드 경로가 대소문자를 무시해 대문자로도 지금은 받아진다 — 그러나 그건
#    문서화되지 않은 동작이라 기대면 안 된다. OS 는 어차피 Amazon Linux 2023 고정이므로 박아둔다.
curl -fsSL \
    "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-$(uname -m)" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version

echo "== 3. 앱 디렉터리 =="
mkdir -p "$APP_DIR/infra/scripts" "$APP_DIR/infra/proxy" "$APP_DIR/infra/certbot"

echo "== 4. 스왑 2GB =="
# t3.medium(4GB)이라도 배포 순간엔 옛 컨테이너와 새 컨테이너가 잠깐 함께 살아 있다.
# 스왑이 없으면 그 순간 OOM Killer 가 Postgres 나 Spring 을 죽인다.
if [ ! -f /swapfile ]; then
    dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
free -h

echo "== 5. DB 백업 크론 (매일 03:10) =="
cat > /etc/cron.d/schoolbus-backup <<EOF
BACKUP_BUCKET=${BACKUP_BUCKET}
10 3 * * * root ${APP_DIR}/infra/scripts/backup-db.sh >> /var/log/schoolbus-backup.log 2>&1
EOF
chmod 644 /etc/cron.d/schoolbus-backup

# 크론 파일을 놓는 것만으로는 부족하다 — crond 가 실제로 돌고 있어야 한다.
# 여기서 확인해 두지 않으면 백업 미실행을 복구가 필요한 날까지 아무도 모른다.
if ! systemctl is-active --quiet crond; then
    echo "오류: crond 가 실행 중이 아니다 — DB 백업이 돌지 않는다." >&2
    systemctl status crond --no-pager >&2 || true
    exit 1
fi
echo "crond 활성 확인 — 백업 크론이 매일 03:10 에 실행된다."

echo
echo "부트스트랩 완료. 다음 단계:"
echo "  1) SSM Parameter Store 에 시크릿을 넣는다 (docs/DEPLOYMENT.md §2)"
echo "  2) api.<도메인> A 레코드를 이 EC2 의 EIP 로 연결한다 (§2.9)"
echo "     — 인증서 HTTP-01 챌린지가 도메인을 조회하므로 발급보다 먼저 끝내야 한다"
echo "  3) infra/certbot/init-cert.sh 로 인증서를 발급한다 (§2.10)"
echo "  4) GitHub Actions 를 돌려 첫 배포를 한다"
