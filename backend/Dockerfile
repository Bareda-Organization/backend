# --- 빌드 단계: Gradle 로 실행 가능한 jar 생성 ---
FROM eclipse-temurin:25.0.4.1_1-jdk AS build
WORKDIR /app
# 의존성 캐시 최적화: 빌드 스크립트 먼저 복사.
# ⚠ lombok.config 를 빠뜨리면 이미지 빌드가 통째로 실패한다 — build.gradle 이 그 파일을
#   compileJava 의 입력으로 등록해 두어(`inputs.file('lombok.config')`), 없으면
#   "property 'lombokConfig' specifies file '/app/lombok.config' which doesn't exist" 로
#   컴파일 태스크 구성 단계에서 멈춘다. 2026-08-26 Lombok 전환 때 이 파일이 생겼는데
#   Dockerfile 이 함께 갱신되지 않아 그 뒤로 `docker compose up --build` 가 줄곧 깨져 있었다
#   (로컬은 bootRun 으로 돌려 왔기 때문에 드러나지 않았다).
COPY gradlew settings.gradle build.gradle gradle.properties lombok.config ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
# 소스 복사 후 부트 jar 빌드
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# --- 실행 단계: JRE 만 담아 가볍게 ---
FROM eclipse-temurin:25.0.4.1_1-jre
WORKDIR /app
# docker-compose.prod.yml 의 healthcheck 가 컨테이너 안에서 curl 로 관리 포트(8081)의 /actuator/health 를 친다(Ruling 740).
# JRE 이미지에 curl 이 포함된다는 보장이 없어 명시적으로 설치한다(없으면 healthcheck 가
# 영원히 실패해 proxy 가 기동하지 않는다).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
