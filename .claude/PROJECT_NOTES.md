# PROJECT_NOTES — School-Bus

전역 에이전트 7개(`convention-auditor` · `debugger` · `diff-reviewer` · `docs-drift-auditor` · `security-reviewer` · `test-runner` · `test-writer`)가 이 저장소에서 동작할 때 참조하는 **사실 노트**다. 절차·판단기준은 전역 에이전트 정의(`~/.claude/agents/*.md`)에 있고, 여기에는 **이 프로젝트에서만 참인 값**만 적는다. 에이전트를 프로젝트에 복제하지 않는다.

**프로젝트 전용 에이전트 2종이 `.claude/agents/` 에 있다**(`7aa44be` 로 신설) — `task-gate-reviewer` · `goal-verifier`. **둘 다 `model: sonnet` 을 정의에 달고 있다.** 나머지는 전역 정의(`~/.claude/agents/`)를 쓴다.

⚠ **모델 자리를 채울 때 정의 파일을 `grep -m1 '^model:'` 로 확인한다**(전역 규칙 `parallel-agents-git.md §4.1`). **`head`·`sed` 로 훑으면 전역 rtk hook 이 출력을 압축해 `model:` 줄을 삼킨다** — 2026-08-29 에 실제로 "정의에 `model` 이 없다" 로 잘못 읽었다(아래 "알려진 함정" 의 rtk 압축 항목과 같은 기제다).

작성일 2026-07-28 / 최종 갱신 2026-08-25 · **2026-10-01 일부 절 재확인**(작업 범위 · Kafka 제거 · `maxHeapSize` · 포트 — `CLAUDE.md` 와 코드로 대조) · **2026-10-01 R46-DOCSYNC 재확인**(`security-reviewer` · `debugger` 절 · `ApiResponse` 설명 · 테스트 수 기준선 — 코드를 `grep` 으로 직접 대조, 아래 `알려진 함정` 이후 절은 미재확인) / 검증 방식: 소스 직접 확인(앱 미기동)

---

## 현재 작업 범위 — **백엔드 + 프론트엔드** (2026-09-10 프론트 복귀 · `CLAUDE.md` 가 기준)

이 절을 가장 먼저 읽는다. 아래 4개가 세션 시작 시점의 전제다.

| 항목 | 내용 |
|---|---|
| **작업 범위** | **`backend/` + `frontend/`.** 2026-08-25 에는 백엔드 전용이었으나 2026-09-10 사용자 결정으로 프론트가 범위 안으로 돌아왔다(Ruling 255 영구 범위 밖을 뒤집음) |
| **사양·설계의 정본** | **`docs/` 사양·설계.** 진입점은 [`docs/README.md`](../docs/README.md) — 여기서 시작한다 |
| **구현 추적** | 백엔드 [`docs/IMPLEMENTATION_PLAN.md`](../docs/IMPLEMENTATION_PLAN.md) **§8 진행 추적 표가 단일 창구.** 프론트는 [`docs/frontend/IMPLEMENTATION_PLAN.md`](../docs/frontend/IMPLEMENTATION_PLAN.md). 진행 상태를 다른 문서에 적지 않는다. 끝난 Phase·라운드 기록은 `docs/archive/rounds/`(원문 그대로 · 파일별 절 범위는 그 폴더 `README.md`)로 옮겼고, Ruling 번호는 백엔드 계획서 §11 색인에서 기록 위치를 찾는다 |
| **코드 컨벤션** | 백엔드 `docs/backend/CODE_CONVENTIONS.md` · 프론트 `docs/frontend/CONVENTIONS_REACT.md`(관계자 웹, Next.js) · `docs/frontend/CONVENTIONS_FLUTTER.md`(학부모·학생 앱, 매니저 앱) |

- 제품 3개 — 관계자 웹(Next.js) · 학부모·학생 앱(Flutter) · 매니저 앱(Flutter). 앱 하나가 로그인 결과의 역할로 갈라진다(학부모↔학생, 기사↔동승자)
- `docs/IMPLEMENTATION_PLAN.md` 의 Phase F1~F4 `➖` 표기는 **옛 Flutter 계획**에 대한 것이라 그대로 둔다 — 프론트 창구는 `docs/frontend/IMPLEMENTATION_PLAN.md`
- 프론트 문서는 `docs/frontend/` 에 있다 — `IMPLEMENTATION_PLAN.md` · `CONVENTIONS_REACT.md` · `CONVENTIONS_FLUTTER.md` · `SETUP.md`(2026-09-30 재작성). 옛 `frontend/docs/` 3종은 2026-09-20 문서 통합으로 부재
- 옛 프론트 전용 에이전트 2개(`ui-implementer` · `design-system-auditor`)는 삭제됨(범위 밖이던 시기의 정리) — 프론트 작업은 전역 에이전트와 Skill `baraeda-screen-check` 로 한다

⚠ **옛 도메인 코드를 서술하던 `무효 예정` 표기는 2026-10-01 R46-DOCSYNC 가 `security-reviewer` · `debugger` 절을 코드와 대조해 다시 쓰면서 전부 해소했다**(표기로 남은 것 0건 — `grep -n '무효 예정' .claude/PROJECT_NOTES.md` 에 걸리는 2곳은 이 문장과 `debugger` 절 머리말의 설명 인용). `알려진 함정` 절의 날짜 붙은 항목은 그 시점의 기록이라 대상 코드가 이후 바뀌었을 수 있다.

---

## 공통

| 항목 | 값 |
|---|---|
| 작업 디렉터리 | **`backend/`** — 모든 Gradle 명령은 여기서 실행. 루트는 `docker-compose.yml`·`CLAUDE.md`·문서만 |
| 빌드 도구 | Gradle wrapper (`./gradlew`), `settings.gradle` |
| 언어 | **Java 25** (toolchain 고정, `build.gradle:13`) |
| 프레임워크 | **Spring Boot 4.1.0**, dependency-management 1.1.7 |
| 웹 스타터 | **`spring-boot-starter-webmvc`** — 구 `spring-boot-starter-web` 아님. 테스트는 `spring-boot-starter-webmvc-test` |
| group / base package | `group = 'src'` / **`src.backend`** (비관례적) — 새 클래스는 반드시 `src.backend` 하위. 벗어나면 컴포넌트 스캔에서 빠진다 |
| DB / 스키마 | PostgreSQL 16 + **Flyway**(`ddl-auto: validate`) |
| 주요 인프라 의존 | PostgreSQL · Redis 7 (Kafka 는 `b48af995` 로 제거 — 실제로 흐르는 메시지가 0이었다) |
| 코드 그래프 | 루트에 **`graft/` 존재** → 코드 탐색은 graft 우선(`graft ask`·`grep`·`callers`·`skeleton`), Explore agent 금지 |

Boot 4 특유의 아티팩트 분리(주석이 `build.gradle`에 상세히 있음): `spring-boot-starter-flyway` + `flyway-database-postgresql` 둘 다 필요, HTTP 클라이언트는 webflux가 아니라 **`spring-boot-starter-webclient`**.

---

## test-runner

```bash
cd backend
./gradlew test -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용 DB 이름>              # 전체
./gradlew test -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용 DB 이름> --tests '*.BusCommandServiceTest'   # 단일 클래스
./gradlew test -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용 DB 이름> --tests '*.메서드명'                 # 단일 메서드
./gradlew build -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용 DB 이름>             # 빌드 + 테스트
```

- **`-PtestDbUrl` 은 2026-09-14부터 필수 인자다**(커밋 `9dc43753`). 빠뜨리면 공유 `schoolbus` DB로 조용히 떨어지는 대신 **Gradle 설정 단계에서 `GradleException` 으로 즉시 실패한다** — 병렬 좌석이 같은 DB 행을 밟아 코드 결함과 구별 안 되는 실패를 내는 사고(`parallel-agents-git.md §0`)를 막는 장치다. 병렬로 여러 좌석을 띄울 때는 좌석마다 다른 DB 이름을 미리 정해 준다.
- **Redis 격리는 자동이다**(같은 커밋). `RedisTestContainerContextCustomizerFactory`(`META-INF/spring.factories` 로 등록)가 Spring TestContext 기반 시험 전부에 격리된 Redis 컨테이너를 붙인다 — `RedisTestContainerBase` 를 상속하는지 여부와 무관하다. (예전엔 141+ 클래스 중 11개만 상속으로 격리돼 있었다.)
- **CI 워크플로 있음** — `.github/workflows/deploy-backend.yml` 의 "테스트" 단계가 `-PtestDbUrl=jdbc:postgresql://localhost:15432/schoolbus` 를 명시해 돈다.
- **실측 기준선(2026-10-01 R46 마무리 전체 실행)**: 백엔드 **354 클래스 · 2,019건**, JUnit 5. 판정은 콘솔 문자열이 아니라 `build/test-results/test/TEST-*.xml` 의 `tests`·`failures`·`errors`·`skipped` 속성을 직접 집계해서 한다(`phase-goal-loop.md §5.2`). ⚠ 이 수치는 조율 세션이 전달한 값이고 R46-DOCSYNC 창은 부하 측정 중이라 재실행하지 못했다 — 정적 교차 확인만 했다: `backend/src/test/java` 소스 파일 404 · `@Test`·`@ParameterizedTest`·`@TestFactory`·`@RepeatedTest` 를 가진 파일 357 · 그 애너테이션 줄 1,789(파라미터화·`@Nested` 가 펼쳐지는 만큼 실행 건수가 더 큼). 클래스 수 357 과 354 의 차이 3 은 기본 `test` 에서 빠지는 `@Tag("live")` 3클래스(`NaverGeocodingClientLiveTest` · `NaverDirectionsClientLiveTest` · `RunConfirmationServiceLiveTest`) 수와 같지만, 클래스 단위로 대응하는지는 확인 못 함. 이전 기준선: 2026-09-14 222 클래스 · 1,302건(같은 명령 연속 4회 실패 0·건너뜀 0, 회차별 4~5분대).
- ⚠ **Postgres `max_connections=100` 은 여러 좌석이 공유한다.** 동시에 뜬 bootRun 서버 + 테스트 JVM 이 많으면 순간적으로 커넥션이 소진돼 `FATAL: sorry, too many clients already`(SQLSTATE 53300)가 `Failed to load ApplicationContext` 형태로 나타난다 — 아래 "알려진 함정" 절 참고.
- Docker 인프라는 **묻지 말고 직접 기동한다**(전역 `CLAUDE.md` 작업 규칙 — `-v` 는 붙이지 않는다. 병렬 작업 창이 도는 동안 `docker compose down` 은 모든 창의 DB 를 날리므로 금지). 기동 명령:
  ```bash
  docker compose up -d postgres redis   # 루트에서
  ```
- 로컬 postgres는 **의도적으로 영속 볼륨이 없다**(`docker-compose.yml:15-17`). `down` 후 `up` 하면 Flyway가 V1(스키마)+V2(데모 시드)를 매번 새로 구성한다. `stop`/`start`는 데이터가 남으므로 리셋하려면 반드시 `down`을 거친다.

### 프론트 표준 실행 명령

```bash
# 웹 (Next.js, vitest)
NEXT_PUBLIC_API_BASE_URL=http://localhost:<포트> npm test

# Flutter (학부모·매니저 앱 공통)
flutter test --dart-define=API_BASE_URL=http://localhost:<포트>/api/v1
```

- **프론트 실측 기준선(2026-10-01 R46 마무리 전체 실행 → 다듬기 뒤)** — 웹 `npx vitest run --exclude '**/*[Rr]ealBackend*.test.ts'` **138 파일 · 823건**(마무리 전체 실행 시점 806). Flutter `flutter test --exclude-tags real_backend`(실서버 시험 제외) — `baraeda_core` **85** · `baraeda_ui` **265**(마무리 시점 240) · `manager-app` **411**(403) · `parent-app` **313**(312). 웹 823 · `baraeda_ui` 265 · `manager-app` 411 · `parent-app` 313 은 R46-POLISH 보고서가 적은 실행 결과이고, 806 · 240 · 403 · 312 · 85 는 R46-LAST 보고서와 조율 세션이 전달한 마무리 시점 값이다. 두 시점 사이 증가분이 R46-POLISH 보고서의 "새 시험" 수(`baraeda_ui` 23 · `manager-app` 7 · `parent-app` 1)와 정확히 맞지는 않는다(240→265 는 25) — 원인 확인 못 함. 이 창은 어느 수치도 재실행하지 않았다. 실서버 시험은 위에서 뺐고 별도로 백엔드를 띄워 돈다.
- ⚠ **Flutter는 `/api/v1` 접미사를 반드시 붙인다.** 빠뜨리면 실서버 시험이 전부 경로 단계에서 실패한다 — 이 접미사 누락으로 앱 2종이 11건씩 실패한 전례가 있다.
- ⚠ **`--dart-define=API_BASE_URL` 을 빠뜨리면 실패하지 않고 기본값 `localhost:8080` 으로 조용히 붙는다.** 이 저장소에서 같은 형태로 3번 났다(`parallel-agents-git.md §13.2`) — 인자 누락이 에러가 아니라 남의 서버(조율자 시드 서버 등)를 실제로 호출하는 조용한 오염으로 나타난다. 주소를 직접 문자열로 박지 말고 `requireRealBackendApiBaseUrl()`류의 헬퍼를 쓴다.

---

## test-writer

> 전역 정책대로 **반드시 워크트리 격리(`isolation: "worktree"`)로 호출**한다.

- **위치·네이밍**: `backend/src/test/java/src/backend/<모듈>/<레이어>/<클래스명>Test.java` — main 패키지를 그대로 미러링. 예) `bus/command/BusCommandServiceTest.java`, `global/security/JwtTokenProviderTest.java`
- **메서드명**: `대상_조건_기대결과` (camelCase 세그먼트를 `_`로 연결). 예) `createBus_routeInOtherTenant_throwsInvalidInput`, `login_validation_fails_without_password`
- **단언**: **AssertJ** — `assertThat(...)`, `assertThatThrownBy(...)`. Hamcrest·JUnit `Assertions` 혼용 안 함.
- **목 라이브러리**: Mockito. 단 `@ExtendWith(MockitoExtension.class)`를 쓰지 않는다 — 서비스가 생성자 주입이라 **필드에서 `mock(X.class)`로 만들고 `new Service(...)`로 직접 조립**하는 방식이 표준이다(Spring 컨텍스트 없이 가장 빠름).
  ```java
  private final BusRepository busRepository = mock(BusRepository.class);
  private final BusCommandService service = new BusCommandService(busRepository, ...);
  ```
- **스텁**: BDD 스타일 `given(...).willReturn(...)` / `willAnswer(...)`. `when(...).thenReturn(...)` 아님.
- **엔티티 id 주입**: 빌더에 id가 없으므로 `ReflectionTestUtils.setField(entity, "id", 1L)`.
- **예외 검증**: `ErrorCode`까지 확인하는 게 관례다.
  ```java
  assertThatThrownBy(() -> ...).isInstanceOf(BusinessException.class)
      .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
  ```
- **컨트롤러 슬라이스** (Boot 4 주의점 3가지):
  - import가 **`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`** (구 `org.springframework.boot.test.autoconfigure.web.servlet` 아님)
  - **`@MockitoBean`** 사용 — `@MockBean`은 Boot 4에서 제거됨
  - 보안 빈을 명시 import 해야 필터체인이 산다: `@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})`
  - 응답 검증은 `ApiResponse` 봉투 기준: `jsonPath("$.success")`, `jsonPath("$.data.xxx")`
- **통합 시험은 실제 로컬 Postgres 를 쓰는 `@SpringBootTest` 가 표준이다**(`IMPLEMENTATION_PLAN §7` 규칙 15) — `@WebMvcTest` 는 예외(`JwtAuthenticationFilterTest` · `AccountStatusGateInterceptorTest` 2곳뿐)다. 순수 단위 시험(서비스를 `mock()`으로 직접 조립)이 가능하면 그쪽을 먼저 쓰되, 보안 필터·학원 격리·Flyway 스키마까지 함께 검증해야 하면 슬라이스로 축소하지 말고 `@SpringBootTest` 를 쓴다.
- **고정 시계는 새로 만들지 않고 `testsupport.clock` 의 공유 설정을 `@Import` 한다**(BR-107) — 값이 같은 `Clock` 을 클래스마다 중첩 `@TestConfiguration` 으로 선언하면 `@SpringBootTest` 컨텍스트 캐시 키가 갈려 같은 값인데도 컨텍스트가 중복 기동된다. 필요한 값이 없으면 `testsupport.clock.FixedClockNNNNConfig` 를 새로 추가한다.

---

## convention-auditor

> 규약 원문은 **`docs/backend/CODE_CONVENTIONS.md`**(Claude 참조용 Markdown). 사람용 렌더는 `CODE_CONVENTIONS.html`이며 **명시 요청이 있을 때만** 수정한다.

- **레이어 구조** (모듈당 표준):
  `command/` `query/` `entity/` `event/` `projection/` `repository/` `dto/` `controller/` `infrastructure/`
- **spec/impl 분리 기준은 단 하나 — "구현이 변경될 가능성이 있는가"**. 외부 연동·전략 패턴·복수 구현체·Mock 필요·MSA 분리 후보만 `spec/`+`impl/`로 나눈다. 단순 CRUD(`StudentService`·`BusService`·`TenantService` 등)는 **인터페이스를 만들지 않는 게 맞다** — "인터페이스가 없다"를 위반으로 잡지 말 것.
- **엔티티 패키지는 예외 없이 `entity/`.** 옛 코드의 `notification`·`routing` 이 `domain/` 을 쓰던 것은 2026-08-24 재작성으로 소멸.
- **CQRS**: Command(생성·수정·삭제)는 **Query를 호출하지 않는다**. Projection은 읽기 모델만 만들고 비즈니스 로직을 두지 않는다.
- **계층 책임**: Controller는 검증·인증사용자 확인·서비스 호출만 / Service는 HTTP·Redis·JPA를 직접 알지 않고 Port(spec) 경유 / Repository는 JPA 접근만 / 외부 기술은 `infrastructure/`.
- **DTO**: Entity를 직접 반환하지 않는다. Request DTO → Service → Response DTO.
- **Event 이름은 과거형** (`LocationUpdatedEvent`, `StudentBoardedEvent`). Command·Query 어휘를 이벤트명에 쓰지 않는다. 서비스 간 직접 체이닝 호출 금지 — 커밋 뒤 도메인 이벤트(`@TransactionalEventListener(AFTER_COMMIT)`)로 잇는다(Kafka 는 제거됨).
- **응답 규약**: 성공은 `ApiResponse<T> { success, data, message }`(`global/response/ApiResponse.java`) 3필드 — **실패는 이 타입을 재사용하지 않는다.** 실패는 `ErrorResponse { error { code, message, details } }`(`API_SPEC §1.10`)이고 `code` 는 `ErrorCode.name()` 그대로라 클라이언트가 문구가 아니라 코드로 분기한다(같은 403 의 `AUTH_PENDING` · `AUTH_REJECTED` · `FORBIDDEN` 구분이 이유 — `ApiResponse` 자바독). 예외는 `BusinessException` + `ErrorCode` enum(**80종** — `ErrorCodeCatalogTest` 는 그중 일부의 HTTP 상태만 `API_SPEC §8` 에서 손으로 옮긴 리터럴과 대조하고 80종 전수를 대조하지는 않음), 전역 처리는 `GlobalExceptionHandler`. `@Valid` 실패(`MethodArgumentNotValidException`)는 `findFirst()` 로 **첫 필드 오류 1개만** `"snake_case_필드명: 메시지"` 형식으로 반환한다(필드명을 `snakeCase()` 로 바꿔 JSON 키와 맞춤).
- **마이그레이션 — 2026-08-24 방향 전환으로 규칙이 뒤집혔다.** 첫 배포 이전인 현재는 **`V1__init_schema.sql` 을 직접 수정하고 로컬 DB 를 재구성**한다(`docker compose down` → `up -d postgres redis`). 버전을 쌓지 않는다. 옛 규칙("`V{n}` 추가, V1 수정 금지")은 **첫 배포 이후에 되살아난다** — 근거와 전환 시점은 `docs/IMPLEMENTATION_PLAN.md` §2.1·§2.2. 데모 시드는 `db/migration-local/`(**`local`·`demo` 두 프로파일에서만 로드**, prod 미적용). 시드 비밀번호 해시는 Flyway placeholder `seedPasswordHash`로 주입 — local은 `application.yml` 기본값(평문 `password`), demo는 SSM 값(기본값 없음).
- **`package-info.java`를 두지 않는다** (패키지 레벨 애너테이션이 필요할 때만 예외).

---

## security-reviewer

> **2026-10-01 R46-DOCSYNC 가 코드와 대조해 다시 썼다.** 옛 판의 `Tenant` · `Membership` · `TenantGuard` · Role 5종(`ACADEMY_ADMIN` · `PLATFORM_ADMIN`) · `/api/auth/**` · `/topic/tenant/{id}/**` 는 코드에서 사라졌다(`grep -rn 'TenantGuard\|class Membership' backend/src/main` 0건). 계정 1개가 학원 1곳에 속하는 단일 소속 모델이고(`ARCHITECTURE §5`·`§6`), 아래는 전부 `backend/src/main/java/src/backend/global/security/` 아래 소스를 읽은 결과다.

- **인증 방식**: JWT Bearer. `JwtAuthenticationFilter` 를 `UsernamePasswordAuthenticationFilter` **앞에** 삽입, 세션 `STATELESS`, CSRF 비활성 (`SecurityConfig.java`). 이 필터는 `@Component` 라 자동 등록되지만 `FilterRegistrationBean` 으로 꺼서 **시큐리티 체인 안에서만** 돈다 — 체인 밖 이중 등록이 인증을 지워 유효한 토큰이 401 이 되는 결함을 막는 장치(`SecurityConfig` 자바독).
- **공개 경로(permitAll)** — 이 목록이 늘어나면 반드시 근거를 따진다. 실제 경로는 `ApiPathPrefixConfig.API_PREFIX`(`/api/v1`)가 붙는다:
  `GET /academies/search` · `POST /auth/signup` · `POST /auth/login` · `POST /auth/refresh` · `POST /auth/recover`(5개 모두 `PublicEndpoints` 한 곳) + `/actuator/health` · `/actuator/prometheus` · `/ws/**` · `/swagger-ui/**` · `/swagger-ui.html` · `/v3/api-docs/**`. 그 외 `anyRequest().authenticated()`. 미인증은 403 이 아니라 **401** — 본문은 `GlobalExceptionHandler` 가 `ErrorResponse` 로 쓰고, 토큰 부재는 `UNAUTHORIZED` · 만료는 `TOKEN_EXPIRED`(클라이언트가 "재발급" 과 "로그인부터" 를 가르는 근거). `ControllerAuthorizationConventionTest.EXPECTED_PUBLIC_ENDPOINTS` 가 `PublicEndpoints` 를 참조하지 않는 하드코딩 목록으로 독립 대조한다.
  - `/actuator/prometheus` 가 열린 것은 취약점이 아니다 — 스크레이프가 JWT 를 못 들고 오기 때문이고 경계는 네트워크다(nginx 가 외부의 `/actuator` 를 404 로 막고 Prometheus 는 compose 내부망에서만 닿음 — `SecurityConfig` 주석).
- **`/ws/**` 가 permitAll 인 것은 취약점이 아니다** — WebSocket 핸드셰이크엔 토큰을 못 싣는 클라이언트가 많아 인증을 **STOMP `CONNECT`(별칭 `STOMP` 포함)에서 `StompAuthChannelInterceptor` 가 세션당 1회** 검증한다. `SUBSCRIBE` 는 목적지 4종만 허용하고 각각 인가한다(Ruling 209) — `/topic/students/{studentId}/run` · `/topic/manager/runs/{runId}` · `/topic/academy/{academyId}/live` · `/topic/admin/live`. 옛 `/topic/tenant/{id}/**` 는 소멸. 클라이언트가 `/topic`·`/queue`·`/user` 로 보내는 SEND 도 같은 인터셉터가 막는다(브로커가 구독자에게 그대로 배달해 서버 방송을 위조할 수 있기 때문 — 소스 주석).
- **역할 6종** — `Role` enum: `PARENT` · `STUDENT` · `DRIVER` · `ESCORT`(동승자) · `STAFF`(학원 관계자) · `SYSTEM_ADMIN`(메인 관리자). `account.academy_id` 가 null 일 수 있는 것은 `SYSTEM_ADMIN` 뿐이다(`ck_account_academy_scope` — `AuthUser` 컴팩트 생성자도 같은 조건을 강제).
- **역할 인가**: `@EnableMethodSecurity` + 컨트롤러 메서드의 권한 메타 애너테이션(`global/security/authz/` 의 `@CanXxx` **38개**, 예 `@CanRequestChange` = `@PreAuthorize("hasAuthority('" + Permissions.CHANGE_REQUEST_WRITE + "')")`). 역할 → 권한 부여표는 **`RolePermissions` 한 파일**이고 우변에 `ROLE_` 을 쓰지 않는다(역할 간 상속 금지 — `RolePermissionsTest` 가 고정). 로그인 불요 핸들러는 `@PublicEndpoint`, 로그인만 필요하면 `@AuthenticatedOnly`. **모든 핸들러가 셋 중 하나를 달았는지** 를 `ControllerAuthorizationConventionTest` 가 전수 대조한다 — 새 엔드포인트를 만들 때 그 시험의 핸들러 수 하한이 같이 움직인다(`COMMON.md` 전수 목록 4곳).
- **현재 사용자 획득**: `AuthUser(accountId, academyId, role, status, mustChangePassword)` record 를 `@AuthenticationPrincipal` 로 주입받는다(`Principal` 도 구현해 STOMP 세션에도 같은 타입). 옛 `List<Membership>` 은 없다.
- **계정 상태 게이트**: `global/security/gate` 의 `AccountStatusGateInterceptor` 가 `pending` · `rejected` 계정과 `mustChangePassword`(임시 비밀번호 강제 변경 표식, Ruling 540) 계정의 API 접근을 허용 목록(`@AllowedWhenPending` · `@AllowedWhenRejected` · `@AllowedWhenPasswordChange`)으로 제한한다. **새 엔드포인트는 `AccountStatusGateEndpoints`(거부 목록)에도 등재**한다(`COMMON.md`).
- **학원 격리 — 신규 API 리뷰 시 최우선 체크**: 범위는 **토큰에서만** 온다(요청 본문·쿼리의 학원 id 는 대조에만 쓴다 — `API_SPEC §1.5`). 판정은 `global/security/access/AcademyScope` 한 곳(`resolveListScope` = 목록 조건값, `assertAccessible` = 단건) — 둘을 나눠 두면 "단건은 막는데 목록은 새는" 상태가 된다(`ARCHITECTURE §6.1`). 다른 학원 접근은 `ACADEMY_SCOPE_VIOLATION`. 빈 `Optional` 은 메인 관리자가 학원을 지정하지 않은 경우 하나뿐이라, 호출부가 이를 "조건 없음" 으로 흘리면 곧 격리 구멍이다. 저장소 쿼리에 학원 조건이 있는지는 `AcademyScopeRepositoryConventionTest`(아래 `알려진 함정` 에 한계)가 본다.
- **시크릿 보관**:
  - 실제 값은 **`backend/.env`(gitignore, 커밋 금지)**. `backend/.env.example` 은 **키 값을 빈 채로 유지**한다 — 여기에 실키가 들어가면 유출이다. 현재 키 이름: `NAVER_MAPS_KEY_ID` · `NAVER_MAPS_KEY` · `NAVER_SEARCH_CLIENT_ID` · `NAVER_SEARCH_CLIENT_SECRET`(옛 이름 `NAVER_DIRECTIONS_KEY_ID` · `NAVER_DIRECTIONS_KEY` 는 `application.yml` 이 폴백으로 읽음). `JWT_SECRET` 은 빈 값이 아니라 `change-me-to-a-long-random-secret` 자리표시자다.
  - `JWT_SECRET` 은 **공통 섹션에 기본값이 없다**(`application.yml` `jwt.secret: ${JWT_SECRET}`). 개발용 기본값은 `local` 프로파일 블록에만 있어, `prod`·`demo`·`staging` 으로 뜨면서 `JWT_SECRET` 이 없으면 **애플리케이션이 기동에 실패한다**(플레이스홀더 미해결). `JwtTokenProvider` 가 `@Value` 생성자 주입이라 실패 시점이 첫 토큰 발급이 아니라 기동 시점이다. `DeploymentConfigGuardTest` 가 이 상태를 고정한다 — 공통 섹션에 기본값을 되살리면 테스트가 실패한다.
  - `docker-compose.yml` 의 `schoolbus/schoolbus` DB 자격증명은 로컬 전용이며 prod 프로파일은 `${DB_URL}` 등 환경변수만 쓴다.
- **CORS**: `app.cors.allowed-origins`(콤마 구분)로 `/api/**` 에만 적용. local 은 개발 출처 6개(`localhost:3000 · 5173 · 4200 · 8081` + `127.0.0.1:3000 · 5173`) 기본 허용, **prod · demo 는 기본값이 비어 있어 미설정 시 전부 차단, staging 은 기본값 자체가 없어 미주입 시 기동 실패**(의도된 설계). `allowCredentials(true)` 인 이유는 웹이 refresh 토큰을 쿠키로 주고받기 때문(`API_SPEC §1.2.1`) — 허용 출처가 `*` 가 아니라 명시 목록이라 성립한다. 응답 헤더는 요청 추적 식별자(`RequestIdFilter.HEADER`)만 노출. **WebSocket 출처는 별개 설정** `app.ws.allowed-origin-patterns`(핸드셰이크가 CORS 필터를 안 타기 때문) — local 기본 `*`, prod·demo·staging 은 기본값이 없어 미주입 시 기동 실패가 맞다.
- **비밀번호**: BCrypt(`BCryptPasswordEncoder`). 로그인 식별자는 이메일이 아니라 `login_id`. 미등록 아이디도 존재 계정의 첫 실패와 **본문 형태와 값이 같고**(`details.remaining_attempts`), 미등록일 때도 더미 해시 대조(`UNKNOWN_ACCOUNT_HASH`)를 한 번 수행한다 — 그래도 계정 열거는 완전히 닫히지 않는다(잠금 전이 때 403 `AUTH_ACCOUNT_BLOCKED` vs 미등록 401 — `LoginCommandService` 자바독이 한계를 적어 둠). `API_SPEC §2.9` 계정 복구의 열거는 2026-10-01 조율 결정(`DECISIONS.md` 11:00)으로 "같은 응답" 방향이 정해졌으나 **코드 반영 여부는 이 창이 확인하지 못함**.

---

## debugger

> **2026-10-01 R46-DOCSYNC 가 코드와 대조해 다시 썼다.** 옛 판이 "무효 예정" 으로 표시했던 `@Scheduled` 4종(위치 tick · 연결끊김 · 등원 접근 · SOS) · 서버측 Mock 위치 소스 · 옛 설정 블록(`app.location.mock` · `app.location.bus-mock` · `app.sos` · `app.drivesession` · `app.connection`) · `PushTargetResolver` 는 코드와 yml 에서 0건이다(`grep -rn` 으로 확인). **포트별 증상표와 커밋 후 이벤트 기제는 유효**.

**포트별 증상표** — 실패를 보면 먼저 여기를 대조한다.

프론트는 범위 안이다(2026-09-10~) — 전부 컨테이너로 띄우면 모든 HTTP 가 proxy(:3000) 한 곳을 지난다(`CLAUDE.md` Docker 절).

| 포트 | 서비스 | 꺼져 있을 때의 증상 |
|---|---|---|
| 15432 | postgres | `bootRun`·`@SpringBootTest` 컨텍스트 로드 실패(Hikari 연결 거부 / Flyway 실패). **가장 흔한 원인** |
| 16379 | redis | 앱은 뜨지만 캐시·Pub/Sub 경로에서 연결 예외 |
| 8080 | backend | Swagger UI `http://localhost:8080/swagger-ui/index.html` |
| 3000 | proxy(관계자 웹 · API · Swagger) | `docker-compose.app.yml` 오버레이로만 기동(기본 compose 에서 제외). 네이버 지도 키 서비스 URL 과 CORS 허용 목록이 이 포트로 등록돼 있다 |

- **Docker 가 꺼져 있으면 묻지 말고 직접 기동한다**(전역 `CLAUDE.md` 작업 규칙 — `-v` 는 붙이지 않는다. 병렬 작업 창이 도는 동안 `docker compose down` 은 모든 창의 DB 를 날리므로 금지). 꺼진 상태의 실패는 코드 결함이 아니라 **환경 문제로 분류**해 보고한다.
- **로그 포맷**: 별도 logback 설정이 없어 Spring Boot 기본 콘솔 포맷. `spring.jpa.properties.hibernate.format_sql: true` 라 SQL 이 정렬 출력된다.
- **비동기·스레드 모델** — "저장은 됐는데 후속이 안 온다"류 버그는 대부분 여기다:
  1. **알림은 아웃박스다.** 알림 리스너(`notification/command/*Listener`) 대부분이 **평범한 `@EventListener`**(발행한 트랜잭션 안)로 `NotificationOutbox.append`(`Propagation.MANDATORY`)를 불러 `push_state='pending'` 행을 **상태 변경과 같은 트랜잭션**에 남긴다 — 롤백되면 알림 행도 없다. `@TransactionalEventListener(AFTER_COMMIT)` 인 것은 `NotificationDispatchListener`(커밋 직후 즉시 발송, 전용 실행기 `notificationDispatchExecutor`) · `AssignmentChangedNotificationListener` 등 일부뿐이다.
  2. 커밋과 즉시 발송 사이에 앱이 죽어도 `NotificationOutboxWorker`(`@Scheduled` 30초)가 `pending` 행을 다시 집어 발송한다(`TECH_DECISIONS §7.2`). "알림이 몇십 초 늦게 온다" 는 이 경로일 수 있다.
  3. **같은 `dedup_key` 는 UNIQUE 제약 `uk_notification_log_dedup_key` 가 막고, `NotificationOutbox.append` 가 `DUPLICATE_NOTIFICATION`(409)을 던진다.** 옛 판의 "조용히 skip" 은 사실이 아니다. 다만 호출한 리스너가 이 예외를 받아 넘기는 코드는 `grep -rn DUPLICATE_NOTIFICATION backend/src/main` 으로 `NotificationOutbox` 외에 0건이라, 같은 키 재적재가 실제로 일어났을 때 예외가 발행 트랜잭션으로 전파되는지는 **재현하지 못해 확인 못 함**.
  4. **WebSocket 방송**: `WebSocketBroadcastGateway` 가 `WebSocketDestinations` 의 목적지 4종(`/topic/students/{id}/run` · `/topic/manager/runs/{id}` · `/topic/academy/{id}/live` · `/topic/admin/live`)으로 보낸다. 위치 이벤트(`position`)는 `PositionBroadcastListener`(`AFTER_COMMIT`)가 부르고, 게이트웨이는 **브로커 구독 등록부에 구독자가 없으면 직렬화·전송을 생략**한다(R46-BE D #6 — 구독이 등록되기 직전에 지나간 위치 1건은 걸러질 수 있음). 방송이 안 온다면 클라이언트 SUBSCRIBE 가 `StompAuthChannelInterceptor` 에서 거부됐는지(세션 속성 `FORBIDDEN_SUBSCRIPTION_ATTR`)부터 본다.
  5. **`@Scheduled` 10개** — `SchedulingConfig.POOL_SIZE = 10`(스레드 수 = 메서드 수, 작업을 더하면 같이 올려야 하고 `SchedulingPoolSizeTest` 가 개수를 세어 어긋나면 실패). 주기는 각 클래스의 애너테이션 기본값이고 `application.yml`·`application-load.yml` 에 덮어쓰기는 0건:

     | 작업 | 주기(기본값) | 속성 키 |
     |---|---|---|
     | `RunConfirmationScheduler` 회차 확정 | 30,000ms | `app.run.confirmation.poll-interval-ms` |
     | `NoShowEscalationScheduler` no-show 에스컬레이션 | 30,000ms | `app.exception.noshow-escalation.poll-interval-ms` |
     | `ChangeRequestAutoRejectionScheduler` 변경 요청 자동 거절 | 30,000ms | `app.request.autoreject.poll-interval-ms` |
     | `NotificationOutboxWorker` 알림 재발송 | 30,000ms | `app.notification.outbox.poll-interval-ms` |
     | `ProximityNotificationScheduler` 근접 알림 | 10,000ms | `app.location.proximity.poll-interval-ms` |
     | `RunUnconfirmedGaugeScheduler` · `RunPositionLostGaugeScheduler` 관측 게이지 2개 | 30,000ms | `app.observability.run-unconfirmed.…` · `run-position-lost.poll-interval-ms` |
     | `DailyRunGenerator` 당일·익일 회차 생성 | cron `0 5 0 * * *` (Asia/Seoul) | `app.run.generation.cron` |
     | `RetentionCleanupScheduler` 보존 기한 정리 | cron `0 15 0 * * *` (Asia/Seoul) | `app.retention.cleanup.cron` |
     | `DemoRunSimulator` (**`local` 프로파일 전용**) | 2,000ms(상수 `TICK_MS`, 시작 지연 15,000ms) | `app.demo.enabled`(기본 `true`) · `app.demo.initial-delay-ms` |

     `DailyRunGenerator` · `RetentionCleanupScheduler` · `NotificationOutboxWorker` · `ProximityNotificationScheduler` · `NoShowEscalationScheduler` · `ChangeRequestAutoRejectionScheduler` · 관측 게이지 2개는 `@SchedulerLock`(ShedLock)이 붙어 인스턴스가 2대 이상이어도 중복 실행을 막는다. `RunConfirmationScheduler` 와 `DemoRunSimulator` 에는 `@SchedulerLock` 이 없다 — 확정은 한 틱을 `join` 으로 끝내 겹침을 막고 조건부 UPDATE 로 멱등을 잡는 구조(`ARCHITECTURE §9`)로 읽히나, 인스턴스 2대에서의 동작은 **확인 못 함**(운영은 인스턴스 1개 전제 — `CLAUDE.md`).
- **서버측 Mock 위치 소스는 없다.** 위치는 기사 단말이 2초마다 `POST /runs/{runId}/position`(`DriverPositionController`)으로 올린다 → `RunPositionCommandService` 가 `run_position` 에 적재 → 커밋 뒤 `RunPositionRedisListener` 가 Redis 최신 좌표를 갱신(키·값 형식은 `RunPositionStore` 한 곳)하고 `PositionBroadcastListener` 가 방송한다. **`local` 프로파일에서만** `DemoRunSimulator`(`@Profile("local")`, `app.demo.enabled` 기본 `true`)가 기사 단말 자리를 대신해 확정 노선의 `road_path` 를 따라 정식 서비스(`RunStartCommandService` · `RunPositionCommandService`)를 호출한다 — 그래서 로컬에서 버스가 저절로 움직이는 것은 버그가 아니고, **기동 후 약 15초 뒤 시작**한다. 지도에 버스가 없으면 시뮬레이터가 꺼졌는지(`app.demo.enabled`), 프로파일이 `local` 인지부터 본다. 수신 후 2분 이상 지난 값은 오래된 위치로 본다(`StudentBusPositionQueryService.STALE_THRESHOLD`, `RunPositionLostGaugeScheduler` 도 같은 값을 씀).
- 로그인 계정은 Flyway 시드(`db/migration-local/V2__seed_data.sql`) 참조 — **로컬**의 비밀번호는 전부 `password`(배포 환경은 다름). 데모 규모 시드(`db/migration-demo/V13__demo_fleet.sql` · `V14__demo_scale.sql`, 학원 10곳 · 학생 600명)는 **`local` 프로파일의 `spring.flyway.locations` 에만** 들어 있고 시험 JVM 은 `build.gradle` 이 뺀다(`application.yml` 주석). `demo` 프로파일은 `migration-local` 까지만 읽는다. ⚠ `local` 프로파일은 `LocalFlywayCleanStrategy` 가 **재기동마다 `clean()` 후 `migrate()`** 한다(`clean-disabled: false`) — `CLAUDE.md` 의 "체크섬이 바뀌면 재구성 필요" 설명과의 관계는 아래 R46-DOCSYNC 보고 ②에 적음.

---

## 에이전트 명명 규칙 (사용자 지시 2026-08-25)

에이전트를 띄울 때 **`name` 파라미터를 반드시 채운다.**

**형식 — `p{Phase}-t{Task}-{역할}-{에이전트}-{모델}`**

| 자리 | 값 |
|---|---|
| 역할 | `impl` 구현 · `fix{N}` 수정 라운드 N · `review` 태스크 게이트 리뷰 · `rereview{N}` 라운드 N 재리뷰 · `goalverify` 완료 조건 실증 |
| 에이전트 | `gp`(general-purpose — **내장 타입이라 지침이 0줄이고 `PROJECT_NOTES.md` 를 자동으로 읽지 않는다**) · `gate`(task-gate-reviewer) · `goal`(goal-verifier) · `diff`·`sec`·`conv`·`dbg`·`tw`·`tr`·`drift`(전역 7종) |
| 모델 | `opus` · `sonnet` · `haiku` · `fable` |

예 — `p1-t5-review-gate-sonnet` · `p1-t6-impl-gp-sonnet` · `p1-t3-fix2-gp-sonnet` · `p1-goalverify-goal-sonnet`

이름은 **영문·숫자·`_`·`-`만 허용**(`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`)이라 한글 불가. 이름이 있으면 화면에서 좌석·모델이 식별되고 `SendMessage({to: 이름})` 으로 재개할 수 있다 — 내부 id 불요.

⚠ **실행 중인 에이전트는 이름을 바꿀 수 없다.** 띄우는 시점에 정한다.

---

## task-gate-reviewer

- **입력 4종은 조율자가 파일 경로로 준다** — 브리프 · 구현자 보고서 · diff 패키지(`커밋목록 + stat + -U10` 을 한 파일에) · 전역 제약. 하나라도 없으면 `BLOCKED`
- **이 저장소의 사양 정본은 `docs/` 다.** 브리프·재료 문서와 `docs/` 가 어긋나면 `docs/` 가 이긴다. ⚠ **그 재료 문서(`phase1-*.md`)는 2026-09-20 에 삭제했다** — 조사 산출물이라 낡은 값을 포함했고 근거로 쓰면 안 되는 것이었다. 보존한 것은 `docs/archive/sdd/` 의 목표 표·판정문뿐이다
- **코드 컨벤션 근거는 `docs/backend/CODE_CONVENTIONS.md` §19·§20** 이다. §19 는 "기본 한 문장, 둘째 문장은 다른 질문에 답할 때만" 이며 **문장 수를 세어 결함으로 매기지 않는다**(2026-08-25 개정)
- **TDD 사이클(RED 선관측)의 적용 경계는 `docs/IMPLEMENTATION_PLAN.md §4.6.4`** — 마이그레이션 SQL · `application.yml` 같은 선언과 **엔티티 필드 매핑**은 미적용, 판정 로직과 대조 테스트는 적용. 조율자가 태스크마다 경계를 지정하므로 그것을 우선한다
- 결과는 응답에 담는다. 별도 보고서 파일을 만들지 않는다 (조율자가 원장에 옮긴다)

---

## goal-verifier

- **작업 디렉터리는 `backend/`.** Gradle wrapper 사용
- **`./gradlew clean` 을 쓰지 마라** — 여러 에이전트가 Gradle 데몬을 공유한다. 재실행이 필요하면 `--rerun-tasks`
- **`bootRun` 은 포트 8080 고정**이라 공유 자원이다. 목표 표가 명시적으로 요구할 때만 띄우고, 끝나면 종료 후 `lsof -i :8080` 으로 해제를 확인한다
- **Docker 는 목표 표가 지시할 때만 만진다.** `docker compose down` 은 로컬 postgres 를 시드 상태로 되돌리는 정상 절차이나 다른 에이전트의 컨테이너도 함께 죽인다. **`-v` 는 어떤 경우에도 붙이지 마라**
- **테스트 결과 집계는 `backend/build/test-results/test/TEST-*.xml` 의 `tests=`·`failures=`·`errors=` 를 직접 세는 편이 정확하다** — 콘솔 요약보다 신뢰할 수 있고 클래스별로 갈린다

---

## diff-reviewer

- **기준 브랜치: `main`.** 2026-08-25 재확인 — `git symbolic-ref --short refs/remotes/origin/HEAD` 가 이제 `origin/main` 을 정상 반환한다(이전 기록의 "무조건 실패" 는 낡음). 다만 값이 `main` 이므로 결과는 같다.
- **코드 그래프 도구 있음**: 루트 `graft/`(2026-09-18 tokensave 에서 교체). 영향범위 확인은 `graft callers <심볼> --depth all`, 위치·이해는 `graft ask "<질문>"`, 전수 검색은 `graft grep "<문자열>"`, 파일 API 개요는 `graft skeleton <파일>` 을 쓰고 Explore agent를 띄우지 않는다. 색인은 파일 편집 훅이 자동 재생성하며 `graft check` 로 신선도를 본다. ⚠ **Java·TypeScript·Dart 가 한 그래프에 같이 들어 있다** — 스택 간 계약 불일치를 한 질의로 잡을 수 있다.
- **리뷰 시 함께 볼 것**:
  - 엔티티 변경이 스키마에 반영됐는가(`ddl-auto: validate`라 없으면 기동 자체가 실패한다). **반영 방식은 첫 배포 이전인 현재 `V1__init_schema.sql` 직접 수정 + 로컬 DB 재구성**이다 — 위 `convention-auditor` 절 참조.
  - 새 API 가 학원 격리를 강제하는가 (`ARCHITECTURE §6.1`) — 범위를 토큰에서만 얻고(`global/security/access/AcademyScope`) 저장소 쿼리에 학원 조건이 붙었는지(`AcademyScopeRepositoryConventionTest`). 옛 `TenantGuard` · N:M 멤버십 전제는 코드에서 사라졌다.
  - 전 엔드포인트에 Swagger 가 적용됐고 예시가 `SeedFixtures` 를 참조하는가 (`docs/IMPLEMENTATION_PLAN.md` §3.3). **`"00. MVP 사용 API"` 이중 태깅 체계는 2026-08-24 방향 전환으로 폐기.**
- **문서 반영 규칙**: 진행 상황·큰 변경은 `docs/IMPLEMENTATION_PLAN.md` §8 진행 추적 표(단일 창구)에 반영한다. **Markdown 원본을 고쳤다고 대응 HTML을 자동 동기화하지 않는다** — HTML은 사용자가 명시 요청할 때만.
- **보고서 산출물**: 리뷰·감사·분석 결과는 대화에만 남기지 말고 `backend/report/YYYY-MM-DD-주제.md`로 쓴다. **수정 지시가 없으면 보고만 하고 코드는 건드리지 않는다.**

---

## docs-drift-auditor

이 저장소는 문서를 계약처럼 쓴다. 사양·설계의 정본은 `docs/` 10종(진입점 `docs/README.md`)이고, 그중 아래 4개가 **대조 대상**이다. 지정이 없으면 이 목록을 본다.

| 문서 | 성격 | 드리프트 시 영향 |
|---|---|---|
| `docs/API_SPEC.md` | 엔드포인트 계약의 정의처 | **가장 높음** — 필드·부수효과가 틀리면 구현이 계약과 갈린다 |
| `docs/FEATURE_SPEC.md` | 공통 규칙·상태머신·권한의 정의처 | 규칙 판정이 호출 지점마다 갈린다 |
| `docs/IMPLEMENTATION_PLAN.md` | 구현 순서·진행 추적 단일 창구 | 완료/미완 표기가 실제와 어긋난다 |
| `docs/backend/CODE_CONVENTIONS.md` | 코드 컨벤션 원본 | `convention-auditor`가 틀린 근거로 지적한다 |

- **대조 범위는 `backend/` 와 `frontend/` 전부다**(2026-09-10 프론트 재개 뒤). ~~프론트는 착수 대상 밖이라 `frontend/docs/` 3종은 드리프트 지적 대상이 아니다~~ — 그 경로는 2026-09-20 부재, 프론트 규칙 원본은 `docs/frontend/*.md` 이고 낡으면 지적 대상이다.
- **기준선 수치는 부재.** 옛 기준선이던 Swagger `"00. MVP 사용 API"` 태그 17개는 **2026-08-24 방향 전환으로 무효**(태그 체계 자체가 폐기). 새 기준선은 Phase 1 이후 `IMPLEMENTATION_PLAN` §2.3 의 테이블 수와 §3.3 의 대조 테스트 3종이 대신한다.
- **`docs/backend/*.html`은 대조 대상이 아니다.** `CODE_CONVENTIONS.html` 등은 `CODE_CONVENTIONS.md`의 사람용 렌더이며 **원칙만 동기화하고 자동 동기화하지 않는다**(의도된 설계). HTML이 Markdown과 다르다는 지적은 올리지 않는다.
- **`docs/source/학원 통학버스 통합관리 시스템.docx` 는 기획 원본(불변)** 이라 코드와 어긋나는 게 정상이다. 대조 대상이 아니다. (`projectInfo.md` 는 2026-08-24 삭제)
- 주기·기본값은 `backend/src/main/resources/application.yml`을 **직접 읽어** 대조한다(미커밋 수정분이 자주 있다).
- 결과는 `backend/report/YYYY-MM-DD-주제.md`로 남긴다. **문서와 코드 어느 쪽도 고치지 않는다.**

---

## 알려진 함정

### 🔴 `FATAL: sorry, too many clients already` — Postgres 커넥션이 여러 좌석에 걸쳐 공유 소진된다

**2026-09-14 목표 11(같은 시험 명령 연속 4회) 수행 중 3회 연속 발생.** 전체 시험을 돌리면 특정 `@SpringBootTest`/`@WebMvcTest` 클래스가 `IllegalStateException: Failed to load ApplicationContext` 로 실패하고, 원인을 따라가면 `entityManagerFactory`/`flywayInitializer` 생성 중 `BeanCreationException` → **`FATAL: sorry, too many clients already`(SQLSTATE 53300)**.

- **코드 결함과 구별하는 법** — 같은 클래스가 매 회차 똑같이 실패하면 코드 결함이다. **회차마다 실패하는 클래스 이름이 달라지면** 이 커넥션 경합이다(2026-09-14 실측: 3회 시도에서 각각 다른 클래스 조합이 걸렸다).
- **원인** — `max_connections=100` 이 서버 전체 공유다(`parallel-agents-git.md §0`). `backend/build.gradle` 이 테스트 Hikari 풀을 `maximum-pool-size=6`으로 제한해 두었는데도, `@SpringBootTest` 설정 조합이 많아 캐시된 컨텍스트마다 별도 풀이 생긴다 — 한 회차 안에서 `HikariPool-73` 까지 번호가 올라간 적이 있다. 여러 좌석의 bootRun 서버 + 테스트 JVM 이 겹치면 순간적으로 100을 넘긴다.
- **이 저장소 Hikari 풀 크기·`maxHeapSize` 설정은 2026-09-14 이전부터 있던 값이다** — 커밋 `9dc43753`(목표 9·10) 은 이 설정을 건드리지 않았다. 직접 대조: `git show 2ea66814:backend/build.gradle`. (당시 1024m — 현재 `build.gradle` 은 `maxHeapSize = '2048m'`.)
- **재발 시 대응** — **`docker`·`psql` 로 손대지 말고 같은 명령을 그대로 재시도한다**(연결 스냅샷이 낮으면 몇 초~몇 분 뒤 재시도로 통과한다. 2026-09-14 실측: 4번째 시도에서 통과). 다른 좌석과 동시에 대량 테스트를 돌리는 시점을 조율하는 것이 근본 대책이나, `maxParallelForks`·`forkEvery` 조정은 다른 좌석에도 영향을 주는 공유 설정이라 **혼자 판단해 바꾸지 않는다.**

### 🔴 로컬 Redis 는 **16379** 인데 앱 기본값은 **6379** 다 — 전체 실행에 `SPRING_DATA_REDIS_PORT` 를 준다 (⚠ `./gradlew test` 한정으로 2026-09-14 해소됨, 아래 참고)

`application.yml:46` 이 `localhost:6379` 인데 이 머신의 compose 오버레이는 **16379** 로 매핑한다
(다른 프로젝트 스택이 6379·5432 를 쓰기 때문). **6379 에 아무것도 없으면 `Connection refused`** 가 나고,
Redis 를 타는 경로가 **`500`**, `/actuator/health` 가 **`503`** 으로 떨어진다.

```bash
SPRING_DATA_REDIS_PORT=16379 ./gradlew test -PtestDbUrl=jdbc:postgresql://localhost:15432/schoolbus --rerun
```

- ~~⚠ **`-PtestDbUrl` 은 DB 만 바꾼다. Redis 는 안 바꾼다** — DB 만 맞추면 절반만 맞춘 것이다~~
  **2026-09-14 해소.** `RedisTestContainerContextCustomizerFactory`(`META-INF/spring.factories` 로 등록,
  `testsupport.redis` 패키지)가 Spring TestContext 를 띄우는 시험 전부에 `spring.data.redis.host`·`port` 를
  전용 컨테이너 값으로 **`addFirst`(최우선순위) 주입**한다 — `application.yml`·`SPRING_DATA_REDIS_PORT`
  둘 다 안 거친다. **직접 재현**: `SPRING_DATA_REDIS_PORT` 를 지운 채
  `./gradlew test --tests '*StaffEmergencyControllerTest' -PtestDbUrl=...` 실행 → `tests=9 failures=0 errors=0
  skipped=0`(아래 명령은 이 해소 전에 밟은 사고의 기록으로 남긴다)
- **2026-09-03 Phase 12 최종 실행에서 실제로 밟았다.** 9건이 실패했고(`StaffEmergencyControllerTest` 5 ·
  `AdminEmergencyControllerTest` 3 · `ActuatorHealthTest` 1) **조율자가 처음에 "병합이 만든 회귀" 로 오판**했다.
  단독 재실행에서도 같이 실패해 더 그럴듯해 보였다 — **단독 재실행은 부하 의존만 갈라 주고 설정 문제는 못 가른다**
- **판별법** — 실패한 시험의 `system-out` 에서 서버측 예외를 읽는다. `Unable to connect to Redis` 가 있으면
  이 건이다. 상태 코드(`500`·`503`)만 보면 코드 결함과 구별되지 않는다
- Phase 10·11 이 통과했던 것은 **그때 6379 에 다른 프로젝트 Redis 가 떠 있었기 때문**이고 코드가 바뀐 것이 아니다
- ⚠ **이 해소는 Spring TestContext 를 띄우는 시험에만 적용된다.** 컨텍스트를 안 띄우는 순수 유닛 시험이나
  `bootRun`(실제 앱 기동)은 여전히 `application.yml`·환경변수를 그대로 따른다 — 위 `SPRING_DATA_REDIS_PORT`
  안내는 **`bootRun` 기준으로는 여전히 유효**하다

### Spring Data 파생 쿼리를 개명하면 깨진다 — **`@Query` 로 바꿀 때 `@Param` 이 필수다**

이 저장소 `build.gradle` 에 **`-parameters` 컴파일 플래그가 부재**하다. 그래서 `@Query` 의 이름 붙은
파라미터(`:academyId`)를 메서드 인자에 매핑하려면 **`@Param("academyId")` 를 반드시 적어야 한다** —
안 적으면 기동 시점에 파라미터를 못 찾아 컨텍스트 로드가 실패한다.

- **언제 걸리나** — `countByAcademyIdAndAckedFalse` 처럼 **메서드 이름 자체가 쿼리**인 파생 쿼리를
  다른 이름(`countUnackedForStaffLog`)으로 바꾸는 순간 그 문법이 깨져 `@Query` 로 옮기게 되고,
  거기서 `@Param` 을 빠뜨린다
- **2026-09-02 Phase 12 T3 에서 실제로 밟았고, 그 이전 세션에도 같은 함정 기록이 있다**(반복 2회 이상)
- 파생 쿼리 이름을 바꿀 일이 있으면 **개명과 `@Query` 전환을 한 번에** 하고 `@Param` 을 함께 붙인다

### 알림 종류가 늘면 **설정 토글 분류가 컴파일로 막힌다** — 그게 정상 동작이다

`NotificationSetting.isEnabledFor` 는 **`default` 없는 `switch` 식**이다(2026-09-02 Phase 12 T1, Ruling 223).
`NotificationType` 에 값을 더하면 **이 파일이 컴파일 오류로 멈춘다** — 고장이 아니라 분류를 강제하는 장치다.

- 예전에는 `if-else` 사슬의 마지막이 `return true` 라, 새 종류를 등록에서 빠뜨려도 **조용히 "항상 발송"으로
  샜다.** 컴파일도 테스트도 안 걸렸다
- 새 종류를 더했으면 **어느 토글 소관인지 판정해 `case` 에 넣는다.** 설정 대상이 아니면 "항상 발송" 쪽
  `case` 에 명시적으로 넣는다 — **`default` 를 되살리지 마라**

### 알림 `acked` 는 **쓰는 쪽과 세는 쪽이 같은 집합을 봐야 한다**

`NotificationType.IMPORTANT_FOR_ACK` **한 곳**에만 정의하고 양쪽이 참조한다(Ruling 227).

- 읽음 처리(`NotificationReadCommandService`)가 이 집합에만 `acked` 를 남기고,
  미확인 배지(`NotificationLogRepository#countUnackedForStaffLog`)도 같은 집합만 센다
- ⚠ **어긋나면 배지가 0 이 되지 않고 발송할 때마다 단조 증가한다.** 2026-09-02 병합 시점에 실제로 그 상태였고
  **좌석 양쪽 시험이 각자 전건 통과**라 아무 데서도 안 잡혔다 — `Phase12AckBoundaryTest` 가 유일한 탐지 수단이다
- ⚠ 정본이 "중요 통지" 를 열거값으로 못박은 문장은 **부재**하다(`USER_FLOWS:623` 의 `중요 알림(지연 ·
  미승차 · 노선 변경)` 을 규칙5 와 붙여 읽은 추론). 정본이 명시하면 **그 상수만** 고친다

### 병렬 좌석에 **`-PtestDbUrl` 을 반드시 준다** — 안 주면 전원이 같은 DB 를 밟는다 (⚠ 2026-09-14 부터 안 주면 아예 실행이 안 된다)

`backend/build.gradle:189` 에 격리 옵션이 **이미 있고 주석이 용도까지 적어 두었다** — *"병렬 작업용 DB 격리 … 에이전트를 2개 이상 동시에 돌릴 때 서로의 행을 밟아 코드 결함처럼 보이는 환경 실패가 나는 것을 막는다."* **2026-09-14 이전엔** 주지 않으면 기본값 `schoolbus` 로 조용히 떨어졌다. **2026-09-14 부터는** 그 기본값 폴백을 없애고 `-PtestDbUrl` 미지정 시 Gradle 설정 단계에서 `GradleException` 으로 즉시 실패하게 바꿨다(커밋 `9dc43753`) — "잊고 공유 DB 로 떨어지는" 사고 자체를 구조로 막는다.

```bash
./gradlew test --tests <클래스> -PtestDbUrl=jdbc:postgresql://localhost:5432/<좌석별DB>
```

**2026-08-31 Phase 10 에서 조율자가 이 옵션 없이 좌석 4개를 발주했다.** 발주 후에 알아채 정정을 보냈다. 전역 규칙 `parallel-agents-git.md §4.2` 에 등재.

- **DB 는 조율자가 미리 만든다** — `docker exec school-bus-postgres-1 psql -U schoolbus -d schoolbus -c "CREATE DATABASE <이름> OWNER schoolbus"`. ⚠ **역할은 `postgres` 가 아니라 `schoolbus` 다**(`docker-compose.yml:22`)
- **좌석마다 이름을 발주문에 박는다.** "전용 DB 를 지정하라" 만 적으면 좌석들이 같은 이름을 고른다
- ⚠ **정리는 좌석이 전부 멈춘 뒤에 한다** — 도는 중의 `DROP DATABASE ... WITH (FORCE)` 가 postgres 를 반복 크래시시킨 전례가 있다(전역 규칙 §0)
- ⚠ **포트를 확인하고 나서 명령을 적어라.** 위 예시의 `5432` 는 기본값일 뿐이다 — `groom-shopping` 스택이 5432·6379 를 점유하면 School-Bus 를 옮겨야 하고, 2026-08-31 에 실제로 postgres `15432` · redis `16379` 로 옮겼다. `docker port school-bus-postgres-1` 로 실측한 값을 발주문에 박는다

### 시드의 "오늘" 행은 **DB 를 만든 날**에 굳는다 — 하루 지나면 시험이 썩는다

`db/migration-local/V2__seed_data.sql` 의 `run.service_date` 가 SQL 안에서 날짜를 계산한다. 마이그레이션은 **DB 생성 시 한 번만** 돌므로 그 값은 그때의 날짜로 고정되는데, 조회 코드는 `LocalDate.now(clock)` 으로 **실행 시점의 오늘**을 묻는다. 하루만 지나도 두 값이 갈린다.

**증상** — 어제까지 통과하던 통합 시험이 아무도 코드를 안 건드렸는데 `404 RUN_NOT_FOUND` 로 실패한다. 2026-09-01 에 Phase 10 T4 의 두 클래스 7건이 이렇게 실패했다.

⚠ **`DROP DATABASE` 후 재생성하면 통과하지만 그것은 해결이 아니다.** 내일 같은 증상이 돌아오고, 그 사이에 든 다른 수정이 원인을 가린다 — 실제로 좌석이 시간대 수정과 DB 재생성을 한 회차에 함께 넣어 **어느 쪽이 효과를 냈는지 갈리지 않았다.**

- **판별** — `psql -d <DB> -c "SELECT DISTINCT service_date FROM run;"` 를 오늘 날짜와 대조한다. 다르면 코드 결함이 아니다
- **재현** — `UPDATE run SET service_date = service_date - INTERVAL '1 day';` 가 내일의 상태를 그대로 만든다. 되돌릴 때는 `+ INTERVAL` 로 같은 값을 더한다
- **이 함정을 피하는 기존 관례가 이미 있다** — 시드와 안 겹치는 미래 날짜를 직접 지정(`StaffRunControllerTest` 등)하거나, `@TestConfiguration static class FixedClockConfig` 로 시계를 고정(`BoardingControllerTest`·`ChangeRequestControllerTest`)한다. **시드의 "오늘" 행에 기대는 시험을 새로 만들지 마라**

### ⚠ `CURRENT_DATE` 는 **컨테이너 시간대가 아니라 접속한 JVM 의 시간대**를 따른다

같은 시드가 심는 값이 실행 호스트에 따라 달라진다. pgjdbc 가 접속 시 세션 `TimeZone` 을 **JVM 기본 시간대에 맞추기** 때문이다.

- `docker exec psql` 로 본 `CURRENT_DATE`(컨테이너 세션은 UTC)와 **앱이 실제로 심은 값이 다르다.** 2026-09-01 실측 — 같은 DB 에서 세션 `CURRENT_DATE` 는 `2026-08-31`, 시드가 심은 `service_date` 는 `2026-09-01`
- 개발 호스트가 `Asia/Seoul` 이면 증상이 안 나타나고, UTC 인 CI 에서만 한국 시간 자정~오전 9시에 하루 어긋난다 — **로컬에서 재현되지 않는 실패**가 된다
- 시드 SQL 에서 날짜를 계산할 때는 `(now() AT TIME ZONE 'Asia/Seoul')::date` 처럼 **시간대를 문자로 박는다**
- ⚠ `route.weekday` 의 `extract(dow from now())` 도 같은 위험을 안고 있다(미수정)

### 테스트에 **Redis 설정이 한 건도 없다** — Phase 10 이 처음이다 (2026-08-31 당시 기록, ⚠ 아래에서 해소됨)

`grep -rn 'redis' backend/src/test/java` **계수 0**(2026-08-31 실측). 즉 **가리킬 본보기가 부재**하다.

⚠ **`testsupport/db/MigratedPostgresTestBase` 를 본보기로 주지 마라** — 이름이 `TestBase` 라 통합 시험의 베이스로 오해하기 쉬우나, 클래스 주석이 밝히듯 **Spring 컨텍스트를 띄우지 않고 Flyway 를 직접 호출하는 마이그레이션 검사 전용**이다. 2026-08-31 에 조율자가 실제로 이렇게 잘못 가리켰다.

⚠ **Redis 는 이름공간이 부재해 좌석끼리 갈라 둘 수단이 없다.** 공유 컨테이너 `school-bus-redis-1` 에 여러 좌석이 붙으면 서로의 키를 밟는다. **2026-09-14 해소** — `RedisTestContainerContextCustomizerFactory` 가 모든 Spring TestContext 기반 시험에 격리 컨테이너를 자동으로 붙인다(위 `test-runner` 절 참고). 이 항목은 "왜 예전엔 격리가 없었는가" 의 기록으로만 남긴다.

### `AcademyScopeRepositoryConventionTest` 는 **텍스트 판정**이라 조건 *무력화* 를 못 잡는다

**실측**(2026-08-31 Phase 9 음성 대조) — `NavRunStopRepository` 의 학원 조건을
`r.academyId = :academyId` → `r.academyId <> :academyId` 로 **뒤집었더니**
`NavigationControllerTest` 5건 · `Phase9CrossSeatWiringTest` 1건이 실패했는데
**규약 시험은 통과**했다(`failures=0`).

**기제** — 판정 술어(`AcademyScopeRule#isNarrowedByAcademy`)는 `@Query` 의 WHERE 절에
`academyId` **문자열이 있는가**만 본다. 비교 연산자·조인 대상이 옳은지는 보지 않는다.
주석 제거·`GROUP BY` 경계 처리까지 갖춰 **우회는 잘 막지만**, 조건이 *존재하되 틀린* 형태는
설계상 범위 밖이다.

**그래서 어떻게 쓰나**

- 규약 시험은 **"조건을 붙였는가"** 를 고정한다 — 새 조회가 조용히 격리를 빠뜨리는 것을 막는다
- **"그 조건이 실제로 거르는가"** 는 행동 시험(컨트롤러·통합) 몫이다. 학원 조건을 새로 붙일 때
  **두 종류를 함께 돌려야** 검증이 닫힌다
- ⚠ 규약 시험만 초록인 것을 "격리 확인" 으로 읽지 마라

### 학원 조건은 **상류가 막고 있어도 개별 조회마다 다시 건다**

`NavRunStopRepository#findAllByRouteVersionIdOrderBySeqAsc` 가 상류 2겹
(`findByIdAndAcademyId` → `assertAssigned`)에 기대어 조건이 부재했고 규약 시험이 잡았다.
**사용자 확정(2026-08-31) — 예외 표시가 아니라 질의 보강**(`968bc9f`).

- `@AcademyScopeExempt` 는 **좁힐 수단 자체가 부재한** 조회(로그인·재발급)용이다.
  부모 조인이 가능하면 예외 대상이 아니다
- `academy_id` 컬럼이 부재한 자식 테이블의 표준 사슬 —
  `RouteVersion` → `Run`(PK 를 공유하는 `ConfirmedRoute` 경유). 선례는
  `RunStopRepository#findAllByRouteVersionIdAndAcademyIdOrderBySeq`
- 근거 — 횡단 규칙 7 이 개별 조회마다 조건을 요구하고, Phase 8 의 `ApprovalQueryService` 가
  상류 검증을 근거로 하류를 안 좁혔다가 격리 우회가 가능했다



- **2026-08-25** — **리뷰어·검증자에게 "응답 자체가 보고서다, 별도 파일을 만들지 마라" 라고 지시하면 전송 유실 시 산출물이 통째로 사라진다.** 구현자는 커밋이 남아 복구되지만 리뷰어는 **아무 흔적도 남지 않는다** — 한 세션에서 리뷰 판정 회수를 위해 재요청한 사례가 3회, 두 번 연속 실패해 리뷰어를 교체한 사례가 1회. **대응 — 리뷰어에게도 판정 파일을 먼저 쓰게 하고 그 다음 응답으로도 보내게 한다.** ⚠ 옛 저장 위치 `.superpowers/sdd/<plan>/` 은 2026-09-20 에 사라졌다 — 지금은 작업 창의 워크트리 안에 쓰게 한다(Skill `parallel-agents`). 조율자 컨텍스트 보호(응답에 본문 복사 금지)와 충돌하지 않는다 — 조율자는 필요할 때만 파일을 읽는다
- **2026-08-25** — **에이전트가 작업을 마치고도 최종 보고 메시지만 유실되는 경우가 잦다.** 증상 — `idle_notification` 은 오는데 결과 보고가 부재. **커밋·워크트리는 정상**인 경우가 대부분이라 작업 유실이 아니라 **전송 유실**이다. 이 세션에서 5회 발생(`p1-t8-review` · `p1-t9-impl2` · `p1-t6t8-rereview1` 등). **대응 — 죽었다고 판단하지 말고 `SendMessage` 로 "판정을 다시 보내라" 고 요청한다.** 요청 시 판정 대상·최우선 확인 항목을 **다시 실어 보내야** 한다(그쪽 컨텍스트가 남아 있어도 재확인 비용이 싸다). 재착수시키면 같은 작업을 두 번 하게 된다
- **2026-08-25** — **이 머신은 `pmset` 의 `sleep` 이 `1`(유휴 1분)이라 백그라운드 에이전트가 작업 중 죽는다.** 증상은 `API Error: Your computer went to sleep mid-response` 이고, 한 세션에서 **4회 발생**했다. 도구 호출 사이 유휴가 1분을 넘기는 긴 작업(테스트 실행·컴파일)에서 특히 잘 걸린다. **병렬 에이전트를 띄우기 전에 `nohup caffeinate -i -m -s &` 로 절전을 억제하고 `pmset -g assertions | grep PreventSystemSleep` 으로 확인**한다. 되돌리는 법은 `pkill caffeinate` — `pmset` 설정 자체는 건드리지 않는다. ⚠ **이 사망을 코드 결함이나 에이전트 결함으로 오분류하지 마라** — 워크트리 상태를 확인해 잔여물이 없으면 그대로 재착수한다. resume 보다 **신규 에이전트**가 낫다(죽은 세션의 컨텍스트 무결성을 신뢰할 근거가 부재)
작업 중 발견한 이 저장소 특유의 함정을 누적한다. 근거(파일:라인, 명령, 날짜)를 같이 남긴다. **날짜가 붙은 항목은 그 시점의 기록**이라 대상 파일이 이후 삭제됐을 수 있다 — 교훈만 취한다.

- **2026-07-28** *(대상 문서 `MVP_API_SPEC.md` 는 이후 삭제 — 교훈만 유효)* — `MVP_API_SPEC.md:503`(§8 비고)이 "버스 위치는 Mock 소스가 없어 기사가 직접 보고해야 한다"고 서술하지만 **사실과 반대**다. `location/source/MockBusLocationSource.java:20,32`가 `app.location.bus-mock.enabled` 기본 `true`(`application.yml:60-61`)로 3초마다 버스 좌표를 자동 생성한다. 같은 문서 `:171`(`"origin":"MOCK"`)·`:191`과도 모순. **문서를 근거로 위치 기능 동작을 판단하면 틀린다.** *(2026-10-01 추가: `MockBusLocationSource` 와 `app.location.bus-mock` 자체가 코드·yml 에서 삭제됨 — `grep` 0건. 지금의 위치 소스는 위 `debugger` 절.)*
- ~~**2026-07-28** — `git symbolic-ref --short refs/remotes/origin/HEAD` 가 실패한다~~ → **2026-08-25 해소.** 같은 명령이 `origin/main` 을 정상 반환한다. 그 사이에 `origin/HEAD` 가 설정된 것으로 보인다. **낡은 함정 항목을 근거로 절차를 건너뛰지 마라 — 명령으로 확인하는 편이 맞다.**
- **2026-07-28** — 문서 검증 에이전트에게 문서만 지정하면 **`backend/report/` 의 기존 보고서를 먼저 찾아 읽는다**(`docs-drift-auditor` 실측). 그러면 "기존 지적 N건 재현"이 독립 재현이 아니게 된다. 교차검증이 목적이면 프롬프트에 **기존 보고서 열람 금지**를 명시한다.
- **2026-07-28** — 문서 검증은 **문서 전체를 한 에이전트에 맡기지 말고 섹션별로 쪼개 병렬로 돌린다.** `MVP_API_SPEC.md` 실측: 전체 패스 1개 = 신규 1건 / 섹션 패스 3개 = 신규 11건. 전체 패스는 계약 일치 여부 확인용으로만 쓴다.
- **2026-07-28** — 전역 `rtk` hook 이 `grep`·`ls` 출력을 압축해 내용을 삼키는 경우가 있다(`grep -n '^#' CODE_CONVENTIONS.md` → `19 matches in 0 files` 만 출력). 파일 목차·목록을 확보할 땐 Read 툴을 쓰거나 `rtk proxy '<원본명령>'` 으로 우회한다.

### ShedLock 락 행을 **`DELETE` 로 지우면 그 락 이름은 JVM 수명 동안 다시 획득되지 않는다**

Phase 11 T4 실측. 시험의 `tearDown` 이 `DELETE FROM shedlock WHERE name = '...'` 로 격리를 하려다 **시험 4개가 전부 "호출 0회"로 실패**했고, 원인을 찾는 데 이 세션의 대부분이 들었다.

**기제** — `JdbcTemplateLockProvider` 는 **그 락 이름에 대한 첫 획득 시도만** `INSERT ... ON CONFLICT` 상향 갱신문을 쓰고, **이후 모든 시도는 `UPDATE ... WHERE lock_until <= now` 단문만 쓴다. INSERT 대체 경로가 없다.** 행이 사라지면 갱신할 대상이 없어 획득이 영구히 실패한다. "만료" 와 "행 삭제" 는 다른 상태다.

- **재발 방지** — 시험 간 격리는 **행을 지우지 말고 `lock_until` 을 과거 시각으로 `UPDATE`** 한다. 심는 헬퍼도 `ON CONFLICT (name) DO UPDATE` 로 멱등하게 만든다
- ⚠ **증상이 코드 결함과 구별되지 않는다** — "스케줄러가 안 불린다" 로 보여 스케줄러 쪽을 파게 된다. `shedlock` 테이블의 행 유무를 먼저 봐라

### `shedlock.lock_until` 은 **무시간대 `TIMESTAMP`** 라 `Timestamp.from(Instant)` 로 바인딩하면 KST 만큼 밀린다

같은 세션에서 위 함정 뒤에 드러난 2차 사고. 시험 헬퍼가 `Timestamp.from(instant)` 로 심었더니 JDBC 가 **JVM 기본 시간대(KST, UTC+9)로 벽시계 숫자를 다시 계산**해 무시간대 컬럼에 썼다. "5분 과거" 로 심은 값이 **9시간 미래**로 저장돼 `lock_until <= now` 가 항상 거짓이 됐다.

- **왜 늦게 드러났나** — **한쪽 시험만 상시 실패**했다. 만료 회수를 검사하는 쪽은 계속 실패하고, "이미 잠겨 있으면 건너뛴다" 를 검사하는 쪽은 **결함 방향이 우연히 "항상 잠김" 과 같은 결과**를 내 계속 통과했다. 통과하는 시험이 있으니 헬퍼를 의심하지 않게 된다
- **프로덕션에는 이 결함이 없다** — `ShedLockConfig.usingDbTime()` 이 Java 쪽에서 타임스탬프를 만들지 않고 SQL 의 `timezone('utc', CURRENT_TIMESTAMP)` 로만 시간 연산을 한다. **시험 헬퍼만의 함정**이다
- **재발 방지** — 무시간대 컬럼에는 `Timestamp.valueOf(LocalDateTime)` 로 벽시계 숫자를 그대로 옮긴다

### `verify(times(BATCH_SIZE))` 처럼 **단언이 구현과 같은 상수를 읽으면 그 상수는 검증되지 않는다**

Phase 11 T4 음성 대조에서 드러났다. `BATCH_SIZE` 를 50 → 51 로 바꾸는 변형이 **5개 시험 전부 통과**했다 — 단언과 구현이 같은 값을 읽어 **함께 움직이기** 때문이다.

- **잡히는 변형은 상수가 아니라 사용처를 어긋내는 쪽**이다 — `PageRequest.of(0, BATCH_SIZE)` → `BATCH_SIZE + 1`(상수는 그대로). 이쪽만 표적 시험 1건을 정확히 실패시켰다
- ⚠ **상수값 자체가 옳은지는 시험이 아니라 정본이 근거다.** 시험이 검사할 수 있는 것은 "구현이 그 상수를 실제로 쓰는가" 까지다

### 컨테이너를 다시 띄울 때 **`docker compose up` 이 포트 충돌로 실패**한다 — 오버레이에 `!override` 가 필요하다

`docker-compose.yml` 은 postgres `5432:5432` · redis `6379:6379` 인데, 이 머신은 **다른 프로젝트 스택이 그 두 포트를 점유**한다. School-Bus 는 **15432·16379** 로 옮겨 쓰고 **그 값은 compose 파일 어디에도 없다** — 컨테이너가 한번 정지하면 그 사실을 아는 수단이 부재하다.

- ⚠ **`ports` 는 오버레이에서 "교체" 가 아니라 "합치기" 다.** `15432:5432` 만 적으면 원본 `5432:5432` 가 **함께 붙어** 여전히 충돌한다. **`ports: !override` 로 적어야** 교체된다
- ⚠ **`docker compose down` 금지** — postgres 에 **영속 볼륨이 의도적으로 부재**해(compose 주석) `down` 후 `up` 은 **DB 를 전부 날린다**. 반면 `up -d` 재생성은 익명 볼륨을 이어받아 **보존된다**(2026-09-01 실측 — 전용 DB 4개·시드 학원 6행 생존)
- **증상** — 재기동 후 `docker port <컨테이너>` 가 **빈 결과**다. 컨테이너는 `Up (healthy)` 인데 호스트에서 붙을 수 없어 시험이 전부 연결 실패로 죽는다

### `SNAKE_CASE` 는 **요청 역직렬화에도** 걸린다 — 시험 본문에 카멜케이스를 쓰면 `422` 로 나타난다

Phase 11 T1 이 밟았다. 새로 쓴 시험 2개 파일이 `PATCH`·`POST` 본문에 자바 필드명 그대로(`noShowWaitMinutes` · `attemptType`)를 넣었더니 **10개 중 9개가 `422` 로 실패**했다. `application.yml:11` 의 `spring.jackson.property-naming-strategy: SNAKE_CASE` 가 **응답 직렬화만이 아니라 요청 역직렬화에도** 적용되므로, 카멜케이스 키는 필드에 바인딩되지 않고 그대로 `null` 이 되어 `@NotNull`·`@NotBlank` 가 걸린다.

- **왜 원인을 엉뚱한 곳에서 찾게 되나** — 증상이 **검증 실패(`422`)** 라서 "요청 값이 범위를 벗어났나 · 검증 애너테이션이 잘못됐나" 를 먼저 보게 된다. **키 이름이 안 맞아 값이 도착하지 않은 것**이라는 신호가 응답에 없다
- **한 단어 필드는 영향이 없다**(`result` · `decision`) — 그래서 **일부만 실패해** 원인이 더 흐려진다
- **재발 방지** — 시험 요청 본문은 **정본(`API_SPEC`)의 snake_case 키를 그대로** 쓴다. 자바 필드명을 보고 짜지 마라
- ⚠ **응답 쪽에도 같은 뿌리의 사고가 있었다**(같은 Phase, 목표 11) — `elapsedSecondsSinceRaised` 가 `elapsed_seconds_since_raised` 로 나가 정본의 `elapsed_since_raised` 와 어긋났다. **자바 이름과 JSON 키 사이에 변환이 끼어 있다는 것을 양방향 모두에서 잊는다.** 그래서 **시험은 JSON 키 문자열을 직접 검사해야** 한다 — 자바 필드명만 보는 시험은 이 어긋남을 잡지 못한다

### 시험 헬퍼·메서드 이름이 **한글**이라 영문 `grep` 으로 "커버리지 부재" 를 판정하면 틀린다

2026-09-02 조율자가 실제로 오판했다. `EmergencyNotificationListener` 를 다른 모듈로 옮기는 발주문에 **"알림 적재를 검사하는 시험이 없으니 옮기다 기능이 죽어도 아무도 모른다"** 를 근거로 실었는데, **그 시험은 실재했다.**

- **못 찾은 이유** — 검사 주체가 `StaffEmergencyControllerTest` 의 **한글 이름 헬퍼 `알림_행수(...)`** 이고, 그것이 `notification_log` 를 직접 질의한다. 조율자가 쓴 검색어(`NotificationOutbox` · `notification_log` · `NotificationLogRepository`)는 **호출부가 아니라 구현부에만** 걸리는 이름이라 헬퍼를 지나쳤다
- **이 저장소는 시험 클래스·메서드·헬퍼 이름을 한글로 쓴다**(`연락_시도를_기록하면_no_show_contact_행이_생긴다` 류). 영문 심볼만 훑으면 **있는 시험이 없는 것으로 읽힌다**
- **재발 방지** — 커버리지 부재를 주장하기 전에 ①**검사 대상 테이블·컬럼 문자열**로 `src/test` 를 훑고(`grep -rn 'notification_log' src/test`) ②의심되는 시험 클래스를 **열어서** 확인한다. ③가장 확실한 것은 **결함을 심어 무엇이 실패하는지 보는 것**이다 — 좌석이 그렇게 해서 조율자의 전제를 뒤집었다
- ⚠ **틀린 "커버리지 부재" 는 방향이 나쁘다** — 있는 시험을 없다고 하면 **중복 시험을 새로 만들게** 되고, 그 중복은 컴파일도 통과하고 초록이라 아무도 못 알아챈다(`Ruling 206` 의 "낡은 없음이 없는 것을 새로 만들게 한다" 와 같은 형태)
