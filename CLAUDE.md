# CLAUDE.md — backend 저장소

바래다 플랫폼의 **백엔드**(Spring Boot) 저장소다. 같은 조직(`Bareda-Organization`)의 `web` · `mobile` 과 함께
**작업 공간 폴더(`baraeda/`) 안에 clone 해서 쓴다** — 그 폴더의 `CLAUDE.md` 가 전체 규칙이고, **모든 문서는 `../docs/`** 에 있다.

- Spring 애플리케이션은 이 저장소의 `backend/` 하위 폴더다(저장소 루트에는 `infra/` · `docker-compose*.yml` · `scripts/`)
- 사양·설계를 먼저 본다 — `../docs/README.md` → `../docs/planning/FEATURE_SPEC.md` · `../docs/planning/API_SPEC.md` · `../docs/backend/ARCHITECTURE.md` · `../docs/backend/ERD.md`
- 코드 규칙은 `../docs/backend/CODE_CONVENTIONS.md`(§19 한 줄 설명 주석 · §20 SRP·크기 기준은 채점 대상)

## Build & run (backend)

모든 명령은 `backend/` 디렉토리에서 실행한다. Gradle wrapper 사용.

```bash
cd backend
./gradlew bootRun          # 앱 실행 (devtools 자동 재시작 포함)

# ⚠ 테스트류는 -PtestDbUrl 이 필수다(2026-09-14~). 안 주면 설정 단계에서 즉시 실패한다 —
# 좌석 여러 개가 같은 공유 DB(schoolbus)로 조용히 떨어져 서로의 행을 밟는 사고를 막는 장치다.
# Redis 는 별도 인자 없이 모든 시험에 자동으로 격리된 컨테이너가 붙는다(ContextCustomizerFactory).
# bootRun·compileJava 는 이 인자와 무관하다 — 영향 없이 그대로 돈다(병합 직후 컴파일 확인 용도로 그대로 쓴다).
./gradlew build -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름>            # 전체 빌드 + 테스트
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름>            # 전체 테스트
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름> --tests 'src.backend.BackendApplicationTests'   # 단일 테스트 클래스
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름> --tests '*.메서드명'                             # 단일 테스트 메서드

# 실 네이버 API 시험(@Tag("live") 3클래스)은 기본 test 에서 빠져 있다 — 따로 돈다. backend/.env 의 자격증명 필요.
# Directions 15 일일 한도가 바닥났으면(Ruling 361) 그 응답은 실패가 아니라 건너뜀이고, 다른 오류는 그대로 실패한다.
./gradlew liveTest -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름>
# 한도 회피(Directions 5 전환)는 경로·상한을 짝으로: NAVER_DIRECTIONS_PATH=/map-direction/v1/driving NAVER_DIRECTIONS_MAX_POINTS=7 ./gradlew liveTest …
# 병합 직전처럼 "자격증명이 없으면 건너뛰지 말고 실패" 해야 하면 test 에 -PrequireLive 를 주면 라이브 시험까지 포함해 돈다.
```

**한 명령 검증.** 저장소 루트의 `scripts/verify.sh` 가 백엔드 시험(전용 DB 자동 생성·삭제)을 CI(`.github/workflows/ci.yml`)와 같은 명령으로 돈다. 실 API 시험은 뺀다. 관계자 웹·앱은 각 저장소의 `scripts/verify.sh`. CI 상세·로그 정책은 `../docs/backend/infra/DEPLOYMENT.md §5.1`.

## Docker — 개발 / 배포가 파일로 갈려 있다 (2026-09-18 분리)

| 파일 | 무엇 | 명령 |
|---|---|---|
| `docker-compose.yml` | **개발 인프라만** — postgres(15432) · redis(16379) | `docker compose up -d` |
| `+ docker-compose.app.yml` | **앱 오버레이** — backend · **관계자 웹** · proxy(:3000) · 관측 | `docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build` |
| `docker-compose.prod.yml` | **배포** — ECR 이미지 · 영속 볼륨 · TLS | `../docs/backend/infra/DEPLOYMENT.md` |
| `docker-compose.staging.yml` | **스테이징(팀원 체험용 · 집 PC)** — 단독 파일 · `local,staging` 프로파일 · 메모리 DB · Cloudflare Tunnel | `../docs/backend/infra/STAGING.md` |

**개발 방식은 둘 중 하나를 고른다.**
1. **인프라만 컨테이너 + 백엔드는 IDE** — `docker compose up -d` 후 `./gradlew bootRun`. Swagger 는 `http://localhost:8080/...`
2. **전부 컨테이너** — 위 오버레이 명령. **모든 HTTP 는 proxy(:3000) 한 곳을 지난다** — 관계자 웹 `http://localhost:3000` · API `.../api/v1/...` · Swagger `.../swagger-ui/index.html` · Grafana `:3001`(admin/admin) · Prometheus `:9090`

- ⚠⚠ **프록시가 `3000` 인 것은 우연이 아니다.** 네이버 지도 키의 **서비스 URL** 과 백엔드 **CORS 허용 목록**이 둘 다 `http://localhost:3000` 으로 등록돼 있다. 다른 포트로 열면 지도 SDK 의 `/v3/auth` 가 **401** 로 거절되고 화면에는 *"지도를 불러오지 못했습니다"* 만 뜬다(2026-09-18 `:80` 으로 열었다가 실제로 겪었고, 3000 으로 바꾸니 같은 요청이 200 이 됐다). 포트를 바꾸려면 **NCP 콘솔의 서비스 URL 부터** 바꿔야 한다

- ⚠ **오버레이를 썼으면 `down` 에도 `-f` 두 개를 그대로 준다.** 빼면 compose 가 인프라 파일만 읽어 backend·proxy·관측 컨테이너가 **살아남는다.** 번거로우면 `export COMPOSE_FILE=docker-compose.yml:docker-compose.app.yml`
- ⚠ **`/actuator` 는 프록시가 라우팅하지 않는다 — 404 가 정상이다.** 헬스는 `docker compose ... exec backend curl -s localhost:8080/actuator/health`
- ⚠ **컨테이너 모드에서 Redis 포트를 덮어야 한다**(`SPRING_DATA_REDIS_PORT: 6379`). `application.yml` 기본값 16379 는 **호스트에 낸 포트**라, 호스트만 덮고 두면 **기동은 되는데 헬스가 DOWN 으로 남는다**(2026-09-18 실제 발생)

앱 기동 후 API 테스트는 Swagger UI 를 쓴다 — 로그인 응답의 `access_token`(응답 봉투 `data` 안)을 우측 상단 Authorize에 넣으면 이후 요청에 자동으로 붙는다. 로그인 계정은 QA Mock 시드(`db/qa-seed/` · 계정 표는 workspace 저장소 `docs/qa/QA_SCENARIOS.md`) 참조 — **로컬**은 비밀번호가 모두 `password`(배포 환경은 다름, 아래 Flyway 항목 참고).

**로컬 postgres는 의도적으로 영속 볼륨이 없다**(2026-07-22~, Swagger로 반복 테스트해도 항상 시드 상태로 되돌리기 위함) — `docker compose down`(컨테이너 제거) 후 `docker compose up -d postgres redis`로 다시 띄우면 Flyway가 스키마(V1)+데모 시드(V2)를 매번 자동으로 새로 구성한다(수동 `DROP SCHEMA`/`volume rm` 불필요, `DataInitializer`는 2026-07-20 삭제됨). `stop`/`start`(컨테이너를 제거하지 않음)는 데이터가 유지된다 — 리셋하려면 반드시 `down`을 거칠 것.

**배포**는 `../docs/backend/infra/DEPLOYMENT.md`를 따른다. 설계 근거(관리형 서비스 채택 검토·차단 결함·비용)는 `../docs/archive/specs/2026-08-10-mvp-배포-design.md`. 운영은 EC2 1대 + `docker-compose.prod.yml`이며 **백엔드 인스턴스는 반드시 1개**다(`@Scheduled` 중복·InMemory 버스위치·WS 세션 로컬 보관).

## Stack / 주요 특이사항

- **Spring Boot 4.1.1**, **Java 25**(toolchain 고정), Gradle. 웹 스타터는 신형 아티팩트명 `spring-boot-starter-webmvc`(테스트는 `spring-boot-starter-webmvc-test`)를 사용한다 — 구버전 `spring-boot-starter-web`이 아님.
- **스키마는 Flyway가 관리**(`spring-boot-starter-flyway`+`flyway-database-postgresql`, 2026-07-20부터)한다 — `ddl-auto: validate`로 Hibernate는 검증만.
- **개발 단계에서는 마이그레이션을 새 버전으로 쌓지 않아도 된다**(2026-08-23 정책 변경). 스키마를 바꿔야 하면 **기존 파일(`V1__init_schema.sql` 포함)을 직접 고치고 로컬 DB를 통째로 재구성**하는 편을 우선한다 — `docker compose down` 후 `docker compose up -d postgres redis`. 로컬은 영속 볼륨이 없어 데이터를 잃을 것이 없고, 버전 파일이 늘어나 스키마의 최종 형태를 여러 파일에 흩어 놓는 것보다 낫다. 기존 파일을 고치면 체크섬이 바뀌어 **이미 적용된 DB는 `FlywayValidateException`으로 기동에 실패**하므로, 재구성 없이 앱만 다시 띄우면 실패한다는 점만 기억한다(코드 결함이 아니라 재구성 누락 신호다). 단 `local` 프로파일 `bootRun` 은 기동마다 `clean()` 후 다시 적재하므로(`LocalFlywayCleanStrategy`) 이 실패가 나지 않는다 — 실패하는 곳은 `clean()` 을 끄는 `-PtestDbUrl` 시험 DB · 컨테이너 모드 backend(`--app.flyway-clean.suppressed=true`)다.
- **이 예외는 "아직 아무 영속 환경에도 적용되지 않은 마이그레이션"에만 해당한다.** demo·prod에 한 번이라도 적용된 뒤에는 원칙이 뒤집혀 **기존 파일 수정 금지 · `V{n}` 추가만 허용**이다. 운영 DB는 볼륨이 있어 재구성으로 되돌릴 수 없고, 체크섬 불일치는 곧 기동 불가다. 첫 배포 시점에 이 항목을 갱신할 것. 시드는 두 벌이고 섞지 않는다(2026-10-03 분리). **QA Mock** `db/qa-seed/`(경기 부천 · 학원 3곳 · 학생 152명 · 지난 7일 운행 이력 · 초기화 시각 기준 오늘 회차)는 **`local`·`staging`·`demo`** 에서 깔리고 재기동·`POST /dev/reset` 마다 새로 깔린다 — 계정·시나리오는 workspace 저장소 `docs/qa/QA_SCENARIOS.md`. **시험 시드** `db/fixture/`(옛 `migration-local` 을 그대로 옮김)는 시험 JVM(`build.gradle`)과 `fixture` 프로파일(`local,fixture` — 웹·앱 실서버 계약 시험 · 부하 측정)에서만 깔린다. QA 데이터를 고쳐도 시험은 영향이 없고, 계약 시험을 QA 데이터로 띄운 서버에 돌리면 깨진다. 시드 계정의 비밀번호 해시는 Flyway placeholder `seedPasswordHash`로 주입한다 — local은 `application.yml`의 기본값(평문 `password`), demo는 SSM에서 받은 값이라 **배포 환경의 비밀번호는 `password`가 아니다.**
- 기본 패키지가 `src.backend`이고 Gradle `group = 'src'`이다(비관례적). 새 클래스는 이 `src.backend` 하위에 두어 `@SpringBootApplication` 컴포넌트 스캔 범위를 유지한다.
- **코드 컨벤션 상세는 `../docs/backend/CODE_CONVENTIONS.md`(Claude 참조용, Markdown)를 먼저 읽는다.** spec/impl 판단기준·패키지 구조·CQRS·Event 규칙 등 전체 원칙이 정리돼 있다. 사람이 브라우저로 보는 동일 내용의 렌더링 버전은 `../docs/render/CODE_CONVENTIONS.html`(시각화 포함) — 둘은 원칙은 같고 매체만 다르며, Claude는 세션마다 `CODE_CONVENTIONS.md`를 참조한다.
- **핵심 요약**: service·repository는 "구현이 바뀔 가능성이 있는가"를 기준으로만 `spec`(인터페이스) / `impl`(구현체) 하위 패키지로 분리한다(단순 CRUD는 분리하지 않음) — 예 `routing/map/spec/MapRouteClient.java` + `routing/map/impl/NaverDirectionsClient.java`(`bus` 같은 단순 CRUD 모듈은 `command/` · `query/` 만 두고 `spec`/`impl` 이 없다). 컨트롤러 등은 `spec`만 의존한다. `package-info.java`는 두지 않는다(패키지 레벨 애너테이션이 필요할 때만 예외).
