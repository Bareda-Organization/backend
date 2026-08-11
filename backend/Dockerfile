# --- 빌드 단계: Gradle 로 실행 가능한 jar 생성 ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# 의존성 캐시 최적화: 빌드 스크립트 먼저 복사
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
# 소스 복사 후 부트 jar 빌드
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# --- 실행 단계: JRE 만 담아 가볍게 ---
FROM eclipse-temurin:25-jre
WORKDIR /app
# docker-compose.prod.yml 의 healthcheck 가 컨테이너 안에서 curl 로 /actuator/health 를 친다.
# JRE 이미지에 curl 이 포함된다는 보장이 없어 명시적으로 설치한다(없으면 healthcheck 가
# 영원히 실패해 proxy 가 기동하지 않는다).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
