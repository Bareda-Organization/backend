#!/usr/bin/env bash
# EC2 에서 실행되는 배포 스크립트. GitHub Actions 가 SSM Send Command 로 호출한다.
#
# 사용법:  deploy.sh <이미지태그>     예) deploy.sh 9f2c1ab
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
    aws ssm get-parameter --region "$AWS_REGION" --name "$PARAM_PREFIX/$1" \
        --with-decryption --query 'Parameter.Value' --output text
}

echo "== 1. SSM 에서 시크릿을 읽어 .env 생성 =="
# umask 077 로 만들어 다른 사용자가 읽지 못하게 한다.
# .env 를 파일로 남기는 이유 — compose 가 ${VAR} 를 해석할 때 파일이 필요하다(메모리로는 안 됨)
umask 077
: > "$ENV_FILE"
{
    printf 'AWS_REGION=%s\n'                 "$AWS_REGION"
    printf 'IMAGE_TAG=%s\n'                  "$IMAGE_TAG"
    printf 'ECR_REGISTRY=%s\n'               "$(get_param ECR_REGISTRY)"
    printf 'DB_PASSWORD=%s\n'                "$(get_param DB_PASSWORD)"
    printf 'JWT_SECRET=%s\n'                 "$(get_param JWT_SECRET)"
    printf 'SEED_PASSWORD_HASH=%s\n'         "$(get_param SEED_PASSWORD_HASH)"
    printf 'CORS_ALLOWED_ORIGINS=%s\n'       "$(get_param CORS_ALLOWED_ORIGINS)"
    printf 'WS_ALLOWED_ORIGIN_PATTERNS=%s\n' "$(get_param WS_ALLOWED_ORIGIN_PATTERNS)"
    printf 'NAVER_DIRECTIONS_KEY_ID=%s\n'    "$(get_param NAVER_DIRECTIONS_KEY_ID)"
    printf 'NAVER_DIRECTIONS_KEY=%s\n'       "$(get_param NAVER_DIRECTIONS_KEY)"
    printf 'ROUTING_PROVIDER=%s\n'           "$(get_param ROUTING_PROVIDER)"
} >> "$ENV_FILE"

echo "== 2. ECR 로그인 후 새 이미지 수신 =="
ECR_REGISTRY="$(get_param ECR_REGISTRY)"
aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$ECR_REGISTRY"

$COMPOSE pull backend

echo "== 3. 기동 =="
# 단일 인스턴스라 재생성 중 수십 초 다운타임이 생긴다(설계 문서 §4.3). 데모에서는 수용한다.
$COMPOSE up -d

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
