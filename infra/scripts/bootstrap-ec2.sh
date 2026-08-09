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

echo "== 1. docker 설치 =="
dnf update -y
dnf install -y docker
systemctl enable --now docker

echo "== 2. compose v2 플러그인 설치 =="
# Amazon Linux 2023 에는 docker-compose-plugin 패키지가 없어 바이너리를 직접 놓는다.
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL \
    "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-$(uname -s)-$(uname -m)" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version

echo "== 3. 앱 디렉터리 =="
mkdir -p "$APP_DIR/infra/scripts" "$APP_DIR/infra/proxy" "$APP_DIR/infra/certbot"

echo "== 4. 스왑 2GB =="
# t3.medium(4GB)이라도 배포 순간엔 옛 컨테이너와 새 컨테이너가 잠깐 함께 살아 있다.
# 스왑이 없으면 그 순간 OOM Killer 가 Kafka 나 Spring 을 죽인다.
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

echo
echo "부트스트랩 완료. 다음 단계:"
echo "  1) SSM Parameter Store 에 시크릿을 넣는다 (docs/DEPLOYMENT.md §2)"
echo "  2) infra/certbot/init-cert.sh 로 인증서를 발급한다"
echo "  3) GitHub Actions 를 돌려 첫 배포를 한다"
