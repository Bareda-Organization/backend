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

APP_DIR=/opt/school-bus
ENV_FILE="$APP_DIR/.env"
PARAM_PREFIX="/school-bus/demo"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
IMAGE_TAG="${1:?사용법: deploy.sh <이미지태그>}"
COMPOSE="docker compose -f $APP_DIR/docker-compose.prod.yml --env-file $ENV_FILE"

get_param() {
    # 조회 실패(비영 종료코드) 또는 빈 값/"None" 이면 어떤 파라미터인지 stderr 에
    # 찍고 실패로 리턴한다. 호출부가 반드시 단독 대입문(`X="$(get_param ...)"`)으로
    # 받아야 하는 이유는 아래 참고 — printf 같은 다른 명령의 인자 자리에서 바로
    # 명령 치환하면 그 명령 자체는 성공하므로 set -e 가 실패를 잡지 못한다.
    local name="$1" value
    if ! value="$(aws ssm get-parameter --region "$AWS_REGION" --name "$PARAM_PREFIX/$name" \
            --with-decryption --query 'Parameter.Value' --output text)"; then
        echo "오류: SSM 파라미터 조회 실패 — $PARAM_PREFIX/$name" >&2
        return 1
    fi
    if [[ -z "$value" || "$value" == "None" ]]; then
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

echo "== 1. SSM 에서 시크릿을 읽어 .env 생성 =="
# umask 077 로 만들어 다른 사용자가 읽지 못하게 한다.
# .env 를 파일로 남기는 이유 — compose 가 ${VAR} 를 해석할 때 파일이 필요하다(메모리로는 안 됨)
umask 077
: > "$ENV_FILE"
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
} >> "$ENV_FILE"

echo "== 1-1. .env 가 실제로 compose 에 온전히 전달되는지 확인 =="
# 위 인용이 깨지면 해시가 잘려도 앱은 정상 기동한다(로그인만 전부 실패). 배포가 조용히
# 성공을 찍고 지나가지 않도록, compose 가 컨테이너에 넘길 최종 값을 직접 읽어 검사한다.
# ⚠️ compose config 는 값 안의 `$` 를 `$$` 로 이스케이프해 출력하므로 되돌린 뒤 비교한다.
RENDERED_HASH="$($COMPOSE config | awk -F': ' '/^ *SEED_PASSWORD_HASH:/ {print $2; exit}' \
    | sed 's/\$\$/$/g')"
if [[ "$RENDERED_HASH" != '$2'* || ${#RENDERED_HASH} -lt 50 ]]; then
    echo "오류: compose 가 받는 SEED_PASSWORD_HASH 가 온전하지 않다(길이=${#RENDERED_HASH})." >&2
    echo "      .env 인용이 깨져 bcrypt 해시가 잘렸을 가능성이 크다 — 배포를 중단한다." >&2
    exit 1
fi

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

echo "== 4. 스모크 테스트 (최대 3분 대기) =="
for _ in $(seq 1 36); do
    if $COMPOSE exec -T backend \
         curl -fsS http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        echo "배포 성공 (tag=$IMAGE_TAG)"
        docker image prune -f >/dev/null
        exit 0
    fi
    sleep 5
done

echo "배포 실패 — 3분 안에 헬스체크가 UP 이 되지 않았다" >&2
$COMPOSE logs --tail 120 backend >&2
exit 1
