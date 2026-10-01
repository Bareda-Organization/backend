#!/usr/bin/env bash
# EC2 에서 실행되는 배포 스크립트. GitHub Actions 가 SSM Send Command 로 호출한다.
#
# 사용법:  deploy.sh <이미지태그>
#   예) deploy.sh 4f1c0a2ee6d3b7908c25d1a4f83bb6e50d9a7c31
#   태그는 워크플로가 `${{ github.sha }}` 로 push 하는 **40자 full SHA** 다.
#   short SHA(7자)로 부르면 ECR 에 그런 태그가 없어 pull 단계에서 실패한다.
#
# 이 스크립트가 실행될 때 docker-compose.prod.yml 과 infra/ 는 워크플로가 S3 로 이미
# 동기화해 둔 상태다(저장소를 EC2 에 클론하지 않는다 — 비공개 저장소 자격증명을 두지 않기 위해).
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/school-bus}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$APP_DIR/.env"
PARAM_PREFIX="/school-bus/demo"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
# docker-compose.prod.yml 의 awslogs-group 과 같아야 한다. 보관 7일은 TECH_DECISIONS §13 · DEPLOYMENT.md §2.3 의 값.
LOG_GROUP="/school-bus/demo"
LOG_RETENTION_DAYS=7
IMAGE_TAG="${1:?사용법: deploy.sh <이미지태그>}"
COMPOSE="docker compose -f $APP_DIR/docker-compose.prod.yml --env-file $ENV_FILE"

get_param() {
    # 조회 실패(비영 종료코드) 또는 빈 값/"None" 이면 어떤 파라미터인지 stderr 에
    # 찍고 실패로 리턴한다. 호출부가 반드시 단독 대입문(`X="$(get_param ...)"`)으로
    # 받아야 하는 이유는 아래 참고 — printf 같은 다른 명령의 인자 자리에서 바로
    # 명령 치환하면 그 명령 자체는 성공하므로 set -e 가 실패를 잡지 못한다.
    #
    # 두 번째 인자 `optional` 이면 파라미터가 **없을 때만**(ParameterNotFound) 빈 값으로 돌려준다.
    # 권한 오류·네트워크 오류는 선택 항목이어도 실패다 — 못 읽은 것을 "없다" 로 취급하면
    # 운영이 조용히 기본값으로 뜬다(Directions 경로 · 첫 관리자 등).
    local name="$1" mode="${2:-required}" value err
    err="$(mktemp)"
    if ! value="$(aws ssm get-parameter --region "$AWS_REGION" --name "$PARAM_PREFIX/$name" \
            --with-decryption --query 'Parameter.Value' --output text 2>"$err")"; then
        if [[ "$mode" == optional ]] && grep -q 'ParameterNotFound' "$err"; then
            rm -f "$err"
            return 0
        fi
        cat "$err" >&2
        rm -f "$err"
        echo "오류: SSM 파라미터 조회 실패 — $PARAM_PREFIX/$name" >&2
        return 1
    fi
    rm -f "$err"
    if [[ -z "$value" || "$value" == "None" ]]; then
        [[ "$mode" == optional ]] && return 0
        echo "오류: SSM 파라미터 값이 비어 있음 — $PARAM_PREFIX/$name" >&2
        return 1
    fi
    # .env 는 값을 작은따옴표로 감싸 쓴다(아래 write_env 참고). 값 자체에 작은따옴표가
    # 들어 있으면 그 방식이 깨져 조용히 잘린 값이 컨테이너에 들어간다 — 여기서 막는다.
    if [[ "$value" == *"'"* ]]; then
        echo "오류: SSM 파라미터 값에 작은따옴표가 포함됨 — $PARAM_PREFIX/$name" >&2
        echo "      .env 인용 방식이 깨진다. 작은따옴표 없는 값으로 다시 등록할 것." >&2
        return 1
    fi
    printf '%s' "$value"
}

write_env() {
    # ⚠️ 값을 반드시 작은따옴표로 감싼다. compose 의 dotenv 파서는 **인용되지 않은 값 안의
    #    `$VAR` 를 확장한다.** bcrypt 해시는 `$2y$10$...` 라 `$` 를 3개 품고 있어, 그대로 쓰면
    #    세 번째 `$` 뒤가 변수 참조로 해석돼 값이 `$2y$10` 으로 잘린다.
    #    이 사고는 조용하다 — Spring 은 정상 기동하고 헬스체크도 UP 인데 로그인만 전부 실패한다.
    #    작은따옴표 안에서는 확장하지 않으므로 전 항목에 똑같이 적용한다.
    printf "%s='%s'\n" "$1" "$2"
}

write_env_if_set() {
    # 선택 항목 — 값이 있을 때만 쓴다. 빈 값을 쓰면 compose 가 빈 문자열을 컨테이너에 넘겨
    # application.yml 의 기본값을 덮어 버린다(빈 문자열도 "값이 있다" 로 취급된다).
    [[ -n "$2" ]] && write_env "$1" "$2"
    return 0
}

require_pair() {
    # 둘 다 있거나 둘 다 없어야 하는 짝 — 하나만 있으면 배포를 멈춘다. $1·$2 는 이름, $3·$4 는 값, $5 는 이유.
    if [[ -n "$3" && -z "$4" ]] || [[ -z "$3" && -n "$4" ]]; then
        echo "오류: $1 와 $2 는 함께 등록하거나 함께 비워야 한다 — $5" >&2
        return 1
    fi
}

require_bcrypt() {
    # bcrypt 해시(`$2a|b|y$NN$` + 53자)가 아니면 멈춘다. 값은 출력하지 않는다(평문 비밀번호를 넣은 실수일 수 있다).
    if [[ ! "$2" =~ ^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$ ]]; then
        echo "오류: $1 가 bcrypt 해시 형식이 아니다 — 평문 비밀번호를 넣지 않았는지 확인할 것(§4 절차로 해시를 만든다)." >&2
        return 1
    fi
}

assert_rendered_hash() {
    # compose 가 컨테이너에 넘길 최종 값이 bcrypt 해시 그대로인지 본다($1 = 변수 이름).
    # ⚠️ compose config 는 값 안의 `$` 를 `$$` 로 이스케이프해 출력하므로 되돌린 뒤 비교한다.
    local rendered
    rendered="$($COMPOSE config | awk -F': ' -v key="$1" '$1 ~ "^ *" key "$" {print $2; exit}' \
        | sed 's/\$\$/$/g')"
    if [[ "$rendered" != '$2'* || ${#rendered} -lt 50 ]]; then
        echo "오류: compose 가 받는 $1 가 온전하지 않다(길이=${#rendered})." >&2
        echo "      .env 인용이 깨져 bcrypt 해시가 잘렸을 가능성이 크다 — 배포를 중단한다." >&2
        return 1
    fi
}

echo "== 1. SSM 에서 시크릿을 읽어 .env 생성 =="
# umask 077 로 만들어 다른 사용자가 읽지 못하게 한다.
# .env 를 파일로 남기는 이유 — compose 가 ${VAR} 를 해석할 때 파일이 필요하다(메모리로는 안 됨)
umask 077
# ⚠ 전부 읽고 검증한 **뒤에** .env 를 쓴다. 중간에 멈춘 배포가 기존 .env 를 비워 두면, 이 파일로 compose 를
#   읽는 백업 크론(backup-db.sh)이 필수 변수 누락으로 실패한다 — 다음 배포까지 백업이 조용히 멈춘다.
# 반드시 단독 대입문으로 먼저 받는다 — get_param 이 실패하면 대입문 자체가
# 비영 종료코드가 되어 set -e 가 그 자리에서 스크립트를 죽인다.
ECR_REGISTRY="$(get_param ECR_REGISTRY)"
DB_PASSWORD="$(get_param DB_PASSWORD)"
JWT_SECRET="$(get_param JWT_SECRET)"
SEED_PASSWORD_HASH="$(get_param SEED_PASSWORD_HASH)"
CORS_ALLOWED_ORIGINS="$(get_param CORS_ALLOWED_ORIGINS)"
WS_ALLOWED_ORIGIN_PATTERNS="$(get_param WS_ALLOWED_ORIGIN_PATTERNS)"
NAVER_DIRECTIONS_KEY_ID="$(get_param NAVER_DIRECTIONS_KEY_ID)"
NAVER_DIRECTIONS_KEY="$(get_param NAVER_DIRECTIONS_KEY)"
ROUTING_PROVIDER="$(get_param ROUTING_PROVIDER)"
GRAFANA_ADMIN_PASSWORD="$(get_param GRAFANA_ADMIN_PASSWORD)"

# 프로파일은 기본값 없이 SSM 에서 명시한다 — 값이 빠진 배포가 가짜 시드 + 가짜 버스(demo)로 뜨는 것을 막는다.
SPRING_PROFILES_ACTIVE="$(get_param SPRING_PROFILES_ACTIVE)"
case "$SPRING_PROFILES_ACTIVE" in
    prod|demo) ;;
    *)
        echo "오류: SPRING_PROFILES_ACTIVE 는 prod 또는 demo 여야 한다(SSM 값: $SPRING_PROFILES_ACTIVE)." >&2
        exit 1
        ;;
esac

# prod 전용 — FCM 자격 증명(Ruling 331). 비면 앱이 기동에 실패하므로(로그 전용 발송 차단) 배포를 여기서 멈춘다.
FCM_PROJECT_ID="" FCM_CLIENT_EMAIL="" FCM_PRIVATE_KEY=""
if [[ "$SPRING_PROFILES_ACTIVE" == prod ]]; then
    FCM_PROJECT_ID="$(get_param FCM_PROJECT_ID)"
    FCM_CLIENT_EMAIL="$(get_param FCM_CLIENT_EMAIL)"
    FCM_PRIVATE_KEY="$(get_param FCM_PRIVATE_KEY)"
fi

# 선택 항목 — 없으면 컨테이너가 application.yml 기본값으로 뜬다. 필수 계약(위 항목)에 섞지 않는다.
NAVER_SEARCH_CLIENT_ID="$(get_param NAVER_SEARCH_CLIENT_ID optional)"
NAVER_SEARCH_CLIENT_SECRET="$(get_param NAVER_SEARCH_CLIENT_SECRET optional)"
NAVER_DIRECTIONS_PATH="$(get_param NAVER_DIRECTIONS_PATH optional)"
NAVER_DIRECTIONS_MAX_POINTS="$(get_param NAVER_DIRECTIONS_MAX_POINTS optional)"
BOOTSTRAP_ADMIN_LOGIN_ID="$(get_param BOOTSTRAP_ADMIN_LOGIN_ID optional)"
BOOTSTRAP_ADMIN_PASSWORD_HASH="$(get_param BOOTSTRAP_ADMIN_PASSWORD_HASH optional)"

# 경보 수신(Ruling 480 ④ · 483) — 텔레그램 봇 + 이메일 예비. 전부 선택이고 없으면 수신자 없이 뜬다. .env 가 아니라
# Alertmanager 설정 파일에만 쓴다(compose 변수가 아니다). 짝·형식 검사는 render-alertmanager.sh 가 하고, 틀리면 여기서 멈춘다.
ALERT_TELEGRAM_BOT_TOKEN="$(get_param ALERT_TELEGRAM_BOT_TOKEN optional)"
ALERT_TELEGRAM_CHAT_ID="$(get_param ALERT_TELEGRAM_CHAT_ID optional)"
ALERT_EMAIL_TO="$(get_param ALERT_EMAIL_TO optional)"
ALERT_SMTP_HOST="$(get_param ALERT_SMTP_HOST optional)"
ALERT_SMTP_USER="$(get_param ALERT_SMTP_USER optional)"
ALERT_SMTP_PASSWORD="$(get_param ALERT_SMTP_PASSWORD optional)"

# Directions 두 값은 짝이다(Ruling 361) — 하나만 바꾸면 Directions 5 에 경유지 15개를 보내 문서 밖 동작에 기댄다.
require_pair NAVER_DIRECTIONS_PATH NAVER_DIRECTIONS_MAX_POINTS \
    "$NAVER_DIRECTIONS_PATH" "$NAVER_DIRECTIONS_MAX_POINTS" "경로와 최대 지점 수는 짝으로만 바꾼다(Ruling 361)"
# 첫 메인 관리자 — 아이디와 해시가 짝이고, 해시가 아닌 값이면 그 계정은 로그인이 되지 않는다.
require_pair BOOTSTRAP_ADMIN_LOGIN_ID BOOTSTRAP_ADMIN_PASSWORD_HASH \
    "$BOOTSTRAP_ADMIN_LOGIN_ID" "$BOOTSTRAP_ADMIN_PASSWORD_HASH" "첫 메인 관리자는 아이디와 비밀번호 해시가 모두 필요하다"
if [[ -n "$BOOTSTRAP_ADMIN_PASSWORD_HASH" ]]; then
    require_bcrypt BOOTSTRAP_ADMIN_PASSWORD_HASH "$BOOTSTRAP_ADMIN_PASSWORD_HASH"
fi

# Alertmanager 설정 파일 — .env 보다 먼저 만든다(값이 틀리면 .env 도 컨테이너도 건드리기 전에 멈춘다). 파일은 항상 만든다 —
# compose 가 이 폴더를 바인드 마운트하는데 없으면 Docker 가 빈 폴더를 만들어 Alertmanager 가 기동하지 못한다.
ALERT_TELEGRAM_BOT_TOKEN="$ALERT_TELEGRAM_BOT_TOKEN" ALERT_TELEGRAM_CHAT_ID="$ALERT_TELEGRAM_CHAT_ID" \
ALERT_EMAIL_TO="$ALERT_EMAIL_TO" ALERT_SMTP_HOST="$ALERT_SMTP_HOST" \
ALERT_SMTP_USER="$ALERT_SMTP_USER" ALERT_SMTP_PASSWORD="$ALERT_SMTP_PASSWORD" \
    "$SCRIPT_DIR/render-alertmanager.sh" "$APP_DIR/alertmanager/alertmanager.yml"
# Alertmanager 컨테이너는 nobody(65534)로 돈다 — root 가 600 으로 만든 파일은 못 읽어 기동에 실패한다. root 로 도는 EC2 에서만 넘긴다.
if [[ "$(id -u)" -eq 0 ]]; then
    chown -R 65534:65534 "$APP_DIR/alertmanager"
fi

: > "$ENV_FILE"
{
    write_env AWS_REGION                 "$AWS_REGION"
    write_env IMAGE_TAG                  "$IMAGE_TAG"
    write_env ECR_REGISTRY               "$ECR_REGISTRY"
    write_env DB_PASSWORD                "$DB_PASSWORD"
    write_env JWT_SECRET                 "$JWT_SECRET"
    write_env SEED_PASSWORD_HASH         "$SEED_PASSWORD_HASH"
    write_env CORS_ALLOWED_ORIGINS       "$CORS_ALLOWED_ORIGINS"
    write_env WS_ALLOWED_ORIGIN_PATTERNS "$WS_ALLOWED_ORIGIN_PATTERNS"
    write_env NAVER_DIRECTIONS_KEY_ID    "$NAVER_DIRECTIONS_KEY_ID"
    write_env NAVER_DIRECTIONS_KEY       "$NAVER_DIRECTIONS_KEY"
    write_env ROUTING_PROVIDER           "$ROUTING_PROVIDER"
    write_env GRAFANA_ADMIN_PASSWORD     "$GRAFANA_ADMIN_PASSWORD"
    write_env SPRING_PROFILES_ACTIVE     "$SPRING_PROFILES_ACTIVE"
    write_env_if_set FCM_PROJECT_ID                  "$FCM_PROJECT_ID"
    write_env_if_set FCM_CLIENT_EMAIL                "$FCM_CLIENT_EMAIL"
    write_env_if_set FCM_PRIVATE_KEY                 "$FCM_PRIVATE_KEY"
    write_env_if_set NAVER_SEARCH_CLIENT_ID          "$NAVER_SEARCH_CLIENT_ID"
    write_env_if_set NAVER_SEARCH_CLIENT_SECRET      "$NAVER_SEARCH_CLIENT_SECRET"
    write_env_if_set NAVER_DIRECTIONS_PATH           "$NAVER_DIRECTIONS_PATH"
    write_env_if_set NAVER_DIRECTIONS_MAX_POINTS     "$NAVER_DIRECTIONS_MAX_POINTS"
    write_env_if_set BOOTSTRAP_ADMIN_LOGIN_ID        "$BOOTSTRAP_ADMIN_LOGIN_ID"
    write_env_if_set BOOTSTRAP_ADMIN_PASSWORD_HASH   "$BOOTSTRAP_ADMIN_PASSWORD_HASH"
} >> "$ENV_FILE"

echo "== 1-1. .env 가 실제로 compose 에 온전히 전달되는지 확인 =="
# 위 인용이 깨지면 해시가 잘려도 앱은 정상 기동한다(로그인만 전부 실패). 배포가 조용히
# 성공을 찍고 지나가지 않도록, compose 가 컨테이너에 넘길 최종 값을 직접 읽어 검사한다.
assert_rendered_hash SEED_PASSWORD_HASH
if [[ -n "$BOOTSTRAP_ADMIN_PASSWORD_HASH" ]]; then
    assert_rendered_hash BOOTSTRAP_ADMIN_PASSWORD_HASH
fi

echo "== 1-2. 배포 게이트 — 운행 중(moving) 회차 확인 =="
# 이미지를 받기 전에 막는다 — pull 뒤에 걸면 새 이미지만 낭비되고 판단은 똑같이 늦다.
# set -e 라 게이트가 exit 1 이면 여기서 스크립트가 즉시 죽는다(§14.3, moving 회차 강제 종료 금지).
"$SCRIPT_DIR/deploy-gate.sh" "$APP_DIR/docker-compose.prod.yml" "$ENV_FILE"

echo "== 2. ECR 로그인 후 새 이미지 수신 =="
ECR_REGISTRY="$(get_param ECR_REGISTRY)"
aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$ECR_REGISTRY"

$COMPOSE pull backend

echo "== 3. 기동 =="
# 단일 인스턴스라 재생성 중 수십 초 다운타임이 생긴다(설계 문서 §4.3). 데모에서는 수용한다.
#
# ⚠️ proxy 가 `depends_on: backend / condition: service_healthy` 라, backend 가 healthy 가
#    되지 않으면 이 명령 자체가 "dependency failed to start" 로 비영 종료한다. 그러면 set -e 가
#    여기서 스크립트를 죽여 아래 스모크 루프와 로그 덤프에 **도달하지 못한다** — 운영자는
#    unhealthy 한 줄만 보고 원인(Flyway 실패인지 DB 접속인지)을 알 수 없다. 여기서도 덤프한다.
if ! $COMPOSE up -d; then
    echo "기동 실패 — backend 가 healthy 가 되지 않아 의존 서비스(proxy)가 뜨지 못했을 가능성이 크다." >&2
    $COMPOSE logs --tail 120 backend >&2 || true
    exit 1
fi

# Alertmanager 설정은 바인드 마운트라 compose 가 바뀐 줄 모른다 — 컨테이너를 다시 만들지 않고 새 설정만 읽힌다(침묵 설정 유지).
# 읽지 못하면(잘못된 설정이면 기존 설정이 유지된다) 배포는 성공이고 경고만 남긴다.
$COMPOSE kill -s HUP alertmanager \
    || echo "경고: Alertmanager 가 새 설정을 읽지 못했다 — docker compose logs alertmanager 를 확인할 것(DEPLOYMENT.md §11.3)" >&2

echo "== 4. 스모크 테스트 (최대 3분 대기) =="
for _ in $(seq 1 36); do
    if $COMPOSE exec -T backend \
         curl -fsS http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        echo "배포 성공 (tag=$IMAGE_TAG)"
        # 안 쓰는 이미지를 72시간 뒤 전부 지운다 — 매 배포가 새 SHA 태그라 옛 이미지는 이름이 있어 이름 없는 것만
        # 지우는 옵션으로는 남는다(디스크 30GB 를 배포마다 ~400MB 씩 채운다). 컨테이너가 쓰는(정지 포함) 이미지는
        # 시간과 무관하게 안 지워진다. 72시간은 이미지 **생성** 시각 기준이라 더 오래전에 빌드된 직전 이미지는
        # 바로 지워지고, 그 태그로의 롤백(§6)은 ECR 에서 다시 받는다.
        docker image prune -af --filter until=72h >/dev/null
        # 로그 그룹은 Docker 가 만들어 보관 기간이 무기한이다 — 배포마다 멱등하게 건다. 실패해도 배포는 성공이다.
        aws logs put-retention-policy --region "$AWS_REGION" --log-group-name "$LOG_GROUP" \
            --retention-in-days "$LOG_RETENTION_DAYS" \
            || echo "경고: CloudWatch 로그 보관 기간 설정 실패 — IAM logs:PutRetentionPolicy 를 확인할 것(DEPLOYMENT.md §2.3)" >&2
        exit 0
    fi
    sleep 5
done

echo "배포 실패 — 3분 안에 헬스체크가 UP 이 되지 않았다" >&2
$COMPOSE logs --tail 120 backend >&2
exit 1
