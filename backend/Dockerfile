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
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
