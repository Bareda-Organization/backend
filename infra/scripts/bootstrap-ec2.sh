#!/usr/bin/env bash
# EC2 최초 1회 세팅 (Amazon Linux 2023).
# SSM Session Manager 로 접속해 실행한다 — SSH 키·22번 포트가 필요 없다.
#
# 사용법:
#   sudo BACKUP_BUCKET=내버킷명 bash bootstrap-ec2.sh
#   데이터 디스크가 /dev/nvme1n1 이 아니면 DATA_DEVICE=/dev/... 를 함께 준다(`lsblk` 로 확인).
#
# 인스턴스를 새로 만들어 옛 데이터 디스크를 붙인 경우에도 같은 명령이다 — 이미 파일시스템이 있는 디스크는
# 포맷하지 않고 그대로 마운트한다(복구 절차 DEPLOYMENT.md §7.3).
set -euo pipefail

BACKUP_BUCKET="${BACKUP_BUCKET:?BACKUP_BUCKET 미설정 — DB 백업이 갈 S3 버킷명}"
DATA_DEVICE="${DATA_DEVICE:-/dev/nvme1n1}"
COMPOSE_VERSION="v2.29.7"
APP_DIR=/opt/school-bus
DOCKER_VOLUMES=/var/lib/docker/volumes
TEXTFILE_DIR=/var/lib/node_exporter/textfile

echo "== 0. 데이터 디스크 — DB·사진·Prometheus 지표·인증서(Docker named volume 전부)를 루트 디스크 밖에 둔다 =="
# 루트 디스크는 인스턴스와 함께 사라지지만(종료 때 삭제) 이 디스크는 남아 새 인스턴스에 붙일 수 있다(DeleteOnTermination=false · §2.4).
# Docker 설치보다 먼저 해야 한다 — 설치 뒤에 마운트하면 이미 만들어진 볼륨이 가려진다.
if [ ! -b "$DATA_DEVICE" ]; then
    echo "오류: 데이터 디스크 $DATA_DEVICE 가 없다 — EC2 에 두 번째 EBS 볼륨이 붙었는지(§2.4), 장치 이름이 맞는지(lsblk) 확인한다." >&2
    exit 1
fi
if ! mountpoint -q "$DOCKER_VOLUMES" && [ -n "$(ls -A "$DOCKER_VOLUMES" 2>/dev/null)" ]; then
    echo "오류: $DOCKER_VOLUMES 에 이미 데이터가 있다 — 마운트하면 가려진다. 새 인스턴스에서만 실행한다." >&2
    exit 1
fi
# ⚠ 파일시스템이 이미 있는 디스크는 절대 포맷하지 않는다 — 옛 DB 가 그 안에 있다.
if ! blkid "$DATA_DEVICE" >/dev/null 2>&1; then
    mkfs -t xfs "$DATA_DEVICE"
fi
DATA_UUID="$(blkid -s UUID -o value "$DATA_DEVICE")"
mkdir -p "$DOCKER_VOLUMES"
# nofail — 디스크가 안 붙은 채 부팅해도 인스턴스는 뜬다. 그 상태에서 docker 가 켜지면 빈 볼륨으로 뜨므로 아래 마운트 확인을 통과해야 다음으로 간다.
grep -q "$DATA_UUID" /etc/fstab || echo "UUID=$DATA_UUID $DOCKER_VOLUMES xfs defaults,nofail 0 2" >> /etc/fstab
mountpoint -q "$DOCKER_VOLUMES" || mount "$DOCKER_VOLUMES"
mountpoint -q "$DOCKER_VOLUMES" || { echo "오류: $DOCKER_VOLUMES 마운트 실패" >&2; exit 1; }
df -h "$DOCKER_VOLUMES"

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

echo "== 5. 백업 크론 — DB 매시 · 사진 매일 03:10 =="
# 백업 성공 시각은 node-exporter textfile 수집기가 읽는 이 폴더에 backup-db.sh 가 쓴다(docker-compose.prod.yml 이 읽기 전용으로 마운트).
# /opt/school-bus 안에 두지 않는 이유 — 배포가 infra/ 를 S3 와 동기화하며 --delete 로 지워 경보가 거짓으로 울린다.
mkdir -p "$TEXTFILE_DIR"
chmod 755 "$TEXTFILE_DIR"
cat > /etc/cron.d/schoolbus-backup <<EOF
BACKUP_BUCKET=${BACKUP_BUCKET}
0 * * * * root ${APP_DIR}/infra/scripts/backup-db.sh db >> /var/log/schoolbus-backup.log 2>&1
10 3 * * * root ${APP_DIR}/infra/scripts/backup-db.sh photos >> /var/log/schoolbus-backup.log 2>&1
EOF
chmod 644 /etc/cron.d/schoolbus-backup

# 크론 파일을 놓는 것만으로는 부족하다 — crond 가 실제로 돌고 있어야 한다.
# 여기서 확인해 두지 않으면 백업 미실행을 복구가 필요한 날까지 아무도 모른다.
if ! systemctl is-active --quiet crond; then
    echo "오류: crond 가 실행 중이 아니다 — DB 백업이 돌지 않는다." >&2
    systemctl status crond --no-pager >&2 || true
    exit 1
fi
echo "crond 활성 확인 — DB 백업이 매시, 사진 백업이 매일 03:10 에 실행된다."

echo
echo "부트스트랩 완료. 다음 단계:"
echo "  1) SSM Parameter Store 에 시크릿을 넣는다 (docs/infra/DEPLOYMENT.md §2)"
echo "  2) api.<도메인> A 레코드를 이 EC2 의 EIP 로 연결한다 (§2.9)"
echo "     — 인증서 HTTP-01 챌린지가 도메인을 조회하므로 발급보다 먼저 끝내야 한다"
echo "  3) infra/certbot/init-cert.sh 로 인증서를 발급한다 (§2.10)"
echo "  4) GitHub Actions 를 돌려 첫 배포를 한다"
echo "  5) 첫 배포 직후 backup-db.sh 를 손으로 1회 돌리고 복구 연습을 한다 (§7)"
