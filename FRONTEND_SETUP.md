# 새 프론트엔드 개발 환경 셋업 가이드

기존 `frontend/`(Next.js 데모)를 다른 프레임워크로 새로 작업하려는 개발자를 위한 문서다.
백엔드(Spring)와 인프라(Postgres/Redis/Kafka)는 이미 완성돼 있으므로, **이 문서만 따라오면
백엔드를 Docker로 띄우고 새 프론트엔드에서 바로 API를 붙일 수 있는 상태**를 만들 수 있다.

## 1. 사전 준비물

- **Docker Desktop** (Docker Compose v2 포함) — 반드시 실행 중이어야 한다.
- **Git**
- (선택) 백엔드를 Docker 없이 로컬에서 직접 띄우고 싶다면 **Java 25** — 전체를 Docker로만 띄울 거라면 불필요.
- 새 프론트엔드 스택에 맞는 런타임(Node.js, 또는 선택한 프레임워크가 요구하는 것)은 각자 준비.

## 2. 클론 및 환경변수 파일 준비

```bash
git clone https://github.com/mskim98/School-Bus.git
cd School-Bus
cp backend/.env.example backend/.env
```

`backend/.env`는 `.gitignore`에 걸려 있어 저장소에 없다. `.env.example`을 복사해서 만든다.
로컬(`local` 프로파일)에서는 `application.yml`의 기본값(DB/Redis/Kafka 접속 정보)이 그대로 쓰이므로
`.env`를 안 채워도 기동은 된다 — 다만 `JWT_SECRET`은 채워두는 습관을 들이는 게 좋다.

## 3. 백엔드 + 인프라 Docker로 한 번에 띄우기

루트 `docker-compose.yml`의 `frontend` 서비스에는 `profiles: ["frontend"]`가 걸려 있어서,
**기본 `docker compose up`은 frontend를 자동으로 제외**하고 postgres / redis / kafka / backend만
띄운다 — 서비스 이름을 일일이 나열할 필요가 없다.

```bash
docker compose up -d --build
```

- `postgres` : 5432 (스키마+데모 데이터는 Flyway가 기동 시 자동 구성)
- `redis` : 6379
- `kafka` : 29092 (호스트에서 접속용), 내부 통신은 9092
- `backend` : 8080 — Spring Boot API 서버

첫 기동은 백엔드 이미지 빌드(Gradle) 때문에 몇 분 걸릴 수 있다. 로그로 진행 확인:

```bash
docker compose logs -f backend
```

`Started BackendApplication`이 찍히면 준비 완료.

기존 Next.js 프론트까지 함께 보고 싶을 때만 profile을 명시해서 띄운다:

```bash
docker compose --profile frontend up -d --build
```

### 대안: 백엔드는 로컬에서 직접 실행 (Java 25 필요, 반복 개발에 더 빠름)

인프라만 컨테이너로 띄우고 백엔드는 IDE/로컬에서 `bootRun`으로 돌리면 코드 변경 후 재기동이
훨씬 빠르다(devtools 자동 재시작 포함).

```bash
docker compose up -d postgres redis kafka
cd backend
./gradlew bootRun
```

## 4. 정상 기동 확인

- API 문서 JSON: `curl -s http://localhost:8080/v3/api-docs` → OpenAPI 스펙(JSON)이 내려오면 기동 성공
  (이 프로젝트는 `spring-boot-starter-actuator`를 쓰지 않으므로 `/actuator/health`는 없다)
- API 문서(Swagger UI): `http://localhost:8080/swagger-ui/index.html`
  - 좌측 상단에 "00. MVP 사용 API" 그룹으로 MVP 범위 엔드포인트가 모여 있다.
  - `/api/auth/login` 호출 결과의 `accessToken`을 우측 상단 **Authorize** 버튼에 넣으면
    이후 요청에 자동으로 인증 헤더가 붙는다.

## 5. 테스트 계정 (Flyway 로컬 시드, 비밀번호 전부 `password`)

| 역할          | 이메일              |
| ------------- | ------------------- |
| 학생          | student@school.com  |
| 학부모        | parent@school.com   |
| 운전기사      | driver@school.com   |
| 학원 관리자   | admin@school.com    |
| 플랫폼 관리자 | platform@school.com |

5계층 모두 같은 학원(한빛학원) 소속으로 미리 연결돼 있어 역할별 화면을 바로 테스트할 수 있다.

## 6. 데이터를 시드 상태로 초기화하고 싶을 때

로컬 Postgres 컨테이너는 **의도적으로 영속 볼륨이 없다**. 즉:

- `docker compose stop` / `start` (컨테이너 유지) → 데이터 그대로 남음
- `docker compose down` 후 다시 `up` (컨테이너 재생성) → Flyway가 스키마+시드를 처음부터 다시 구성 → **깨끗한 상태로 리셋**

새 프론트엔드를 반복 테스트하다가 데이터가 꼬였다면 `docker compose down` 후 `up`으로 되돌리면 된다.

## 7. 새 프론트엔드에서 API 연결 시 꼭 확인할 것

1. **API Base URL**: `http://localhost:8080`
2. **인증 방식**: 역할 기반 JWT. `/api/auth/login`으로 로그인 후 발급받은 `accessToken`을
   `Authorization: Bearer <token>` 헤더로 붙여야 한다. (`/api/auth/**`, `/actuator/health`,
   `/swagger-ui/**`, `/v3/api-docs/**`, `/ws/**`만 인증 없이 열려 있고 나머지는 전부 토큰 필요)
3. **CORS는 흔한 개발 포트가 이미 허용돼 있다**: `/api/**`는
   `SecurityConfig`(`backend/src/main/java/src/backend/global/security/SecurityConfig.java`)의
   `CorsConfigurationSource` 빈이 `application.yml`의 `app.cors.allowed-origins` 목록에 있는
   출처만 허용한다. 로컬(`local` 프로파일) 기본값에 아래 포트가 이미 포함돼 있어 대부분의
   프레임워크 개발 서버는 별도 설정 없이 바로 붙는다.
   - `http://localhost:3000` (Next.js / CRA), `http://localhost:5173` (Vite),
     `http://localhost:4200` (Angular), `http://localhost:8081`, 그리고 `127.0.0.1` 버전들
   - 다른 포트를 쓴다면 `application.yml`의 `app.cors.allowed-origins`에 추가하거나,
     환경변수 `CORS_ALLOWED_ORIGINS`(콤마 구분)로 오버라이드한다 — 예:
     `CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5555 ./gradlew bootRun`
   - `/ws/location`(WebSocket)은 이 설정과 무관하게 이미 모든 origin을 허용한다.
   - **운영(`prod`) 프로파일은 기본값이 없다** — `CORS_ALLOWED_ORIGINS`를 명시하지 않으면 허용
     출처가 0개(전부 차단)이므로, 배포 시 실제 프론트 도메인을 반드시 환경변수로 넣어야 한다.
4. **실시간 위치 스트림**: `/ws/location`이 STOMP over WebSocket 엔드포인트다. Mock GPS가
   기본 활성화(`app.location.mock.enabled: true`)돼 있어 실 기기 연동 없이도 위치가 흘러간다.
5. **API 명세 참고 문서**:
   - `backend/docs/MVP_API_SPEC.md` — MVP 범위 API 명세
   - `backend/docs/API_ENDPOINTS.html` — 전체 엔드포인트 문서
   - Swagger UI가 가장 최신이므로 실제 요청/응답 스키마는 Swagger 기준으로 확인할 것

## 8. 기존 `frontend/` 처리

새 프레임워크로 교체할 계획이라면:

- 기존 `frontend/` 디렉터리는 참고용(화면 흐름·시나리오 파악)으로만 두고 새 프론트엔드는
  별도 디렉터리(예: `web/`)에 만드는 것을 권장한다 — 한 번에 지우기보다 비교하면서 이전하는 편이 안전하다.
- 루트 `docker-compose.yml`의 `frontend` 서비스는 새 프론트엔드가 준비되면 빌드 경로(`build: ./frontend`)와
  포트를 새 디렉터리에 맞게 수정하거나, 새 프론트엔드는 Docker Compose에 넣지 않고 자체 개발 서버로
  띄워도 무방하다(백엔드 API는 컨테이너 밖에서도 `localhost:8080`으로 접근 가능).

## 9. 트러블슈팅

- **포트 충돌** (`5432`/`6379`/`8080` 등 이미 사용 중): 로컬에 다른 Postgres/Redis가 떠 있는지 확인하거나
  `docker-compose.yml`의 `ports` 매핑을 조정.
- **`docker compose up` 후 backend가 계속 재시작됨**: `docker compose logs backend`로 원인 확인 —
  대개 postgres healthcheck 통과 전에 접속을 시도하는 경우이므로 `depends_on: condition: service_healthy`가
  걸려 있는지, 혹은 이미지 빌드가 실패했는지 로그로 확인.
- **로그인은 되는데 이후 요청이 401**: Swagger의 Authorize에 토큰을 `Bearer ` 접두사 없이 넣었는지 확인
  (springdoc이 자동으로 붙여주므로 토큰 값만 넣으면 된다).
- **CORS 에러가 여전히 발생**: ① 프론트 개발 서버 주소가 `app.cors.allowed-origins` 목록과 프로토콜·호스트·포트까지
  정확히 일치하는지(`http://localhost:3000`과 `http://127.0.0.1:3000`은 다른 출처로 취급됨) ② `application.yml` 수정
  후 백엔드를 재시작했는지(설정은 기동 시 한 번만 읽는다) ③ 요청 경로가 `/api/`로 시작하는지(다른 경로는 이 CORS
  설정 대상이 아니다) 확인.
