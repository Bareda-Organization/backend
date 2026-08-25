# PROJECT_NOTES — School-Bus

전역 에이전트 7개(`convention-auditor` · `debugger` · `diff-reviewer` · `docs-drift-auditor` · `security-reviewer` · `test-runner` · `test-writer`)가 이 저장소에서 동작할 때 참조하는 **사실 노트**다. 절차·판단기준은 전역 에이전트 정의(`~/.claude/agents/*.md`)에 있고, 여기에는 **이 프로젝트에서만 참인 값**만 적는다. 에이전트를 프로젝트에 복제하지 않는다.

**프로젝트 전용 에이전트는 부재.** `.claude/agents/` 는 비어 있다.

작성일 2026-07-28 / 최종 갱신 2026-08-25 / 검증 방식: 소스 직접 확인(앱 미기동)

---

## 현재 작업 범위 — **백엔드 전용** (2026-08-25 사용자 확정)

이 절을 가장 먼저 읽는다. 아래 4개가 세션 시작 시점의 전제다.

| 항목 | 내용 |
|---|---|
| **작업 범위** | **`backend/` 만.** 프론트엔드는 착수 대상 밖 |
| **사양·설계의 정본** | **`docs/` 10종.** 진입점은 [`docs/README.md`](../docs/README.md) — 여기서 시작한다 |
| **구현 추적** | [`docs/IMPLEMENTATION_PLAN.md`](../docs/IMPLEMENTATION_PLAN.md) **§8 진행 추적 표가 단일 창구.** 진행 상태를 다른 문서에 적지 않는다 |
| **코드 컨벤션** | `backend/docs/reference.md` (Claude 참조용 Markdown) |

**프론트 작업은 범위 밖이다.** 사양(`FEATURE_SPEC` · `USER_FLOWS` 등)에 프론트 요구가 그대로 남아 있으나 **만들 것이 사라진 것이 아니라 지금 만들지 않는 것**이며, 사양에서 지우지 않는다. `IMPLEMENTATION_PLAN` Phase F1~F4 는 `➖ 범위 밖` 으로 고정돼 상태 갱신 대상이 아니다.

- 백엔드 Phase **15개**(0~14)가 갱신 대상, 프론트 Phase **4개**(F1~F4)가 범위 밖
- 프론트 문서로 남은 것은 `frontend/docs/` 3개 — `FLUTTER_CODE_CONVENTIONS.md` · `DESIGN_SYSTEM.md` · `FRONTEND_SETUP.md`. **재개 시점의 규칙 원본으로 존치하되 갱신 대상이 아니다**
- 프론트 전용 에이전트 2개(`ui-implementer` · `design-system-auditor`)는 **삭제됨**. 프론트 작업 요청을 받으면 범위 밖임을 먼저 알린다

⚠ **아래 절 중 옛 도메인 코드를 서술한 부분은 `IMPLEMENTATION_PLAN` Phase 0(걷어내기) 시점에 무효가 된다.** 해당 위치에 `무효 예정` 표기를 붙여 뒀다 — 표기가 붙은 값을 근거로 지적하지 않는다.

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
| 주요 인프라 의존 | PostgreSQL · Redis 7 · Kafka 3.9(KRaft) |
| 코드 그래프 | 루트에 **`.tokensave/` 존재** → 코드 탐색은 tokensave MCP 우선, Explore agent 금지 |

Boot 4 특유의 아티팩트 분리(주석이 `build.gradle`에 상세히 있음): `spring-boot-starter-flyway` + `flyway-database-postgresql` 둘 다 필요, `spring-boot-starter-kafka`(신형명), HTTP 클라이언트는 webflux가 아니라 **`spring-boot-starter-webclient`**.

---

## test-runner

> ⚠ **무효 예정** — 아래 테스트 수치(21파일·97메서드·1건 실패 기준선)는 **옛 도메인 코드의 테스트 기준**이다. `IMPLEMENTATION_PLAN` Phase 0 이 도메인 15개를 걷어내면 함께 소멸한다. **명령·Docker 판별법은 그대로 유효**하고 수치만 무효.

```bash
cd backend
./gradlew test                                 # 전체
./gradlew test --tests '*.BusCommandServiceTest'   # 단일 클래스
./gradlew test --tests '*.메서드명'                 # 단일 메서드
./gradlew build                                # 빌드 + 테스트
```

- 테스트 파일 **21개 / 테스트 메서드 97개**, JUnit 5(`useJUnitPlatform()`). **CI 워크플로 없음**(`.github/` 없음) — 로컬 실행이 유일한 게이트.
- **DB 없이 실행한 실측 기준선 (2026-07-28)**: `97 tests completed, 1 failed` — 실패는 `BackendApplicationTests > contextLoads()` **하나뿐**이고 원인은 `IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:195`(컨텍스트 로드 실패). 소요 8초. **이 1건 실패는 정상 상태다.** 2건 이상 실패하면 그때부터 진짜 코드 결함이다.
- **외부 서비스 필요 여부 — 갈리는 지점**:
  - `src/test/java/src/backend/BackendApplicationTests.java` 는 순수 `@SpringBootTest`라 **실제 PostgreSQL(localhost:5432)이 필요**하다. 테스트용 datasource 오버라이드나 Testcontainers가 없고 `ddl-auto: validate` + Flyway라, DB가 없으면 컨텍스트 로드 단계에서 실패한다.
  - 나머지 20개는 순수 단위 테스트(생성자에 Mockito mock 주입) 또는 `@WebMvcTest` 슬라이스라 **Docker 없이 통과**한다.
- **Docker가 꺼진 상태의 `BackendApplicationTests` 실패는 "테스트 깨짐"이 아니라 환경 문제로 분류**해 코드 결함과 구분해 보고한다.
- 기동 명령은 **안내만 하고 직접 실행하지 않는다**(사용자에게 요청):
  ```bash
  docker compose up -d postgres redis kafka   # 루트에서
  ```
- 로컬 postgres는 **의도적으로 영속 볼륨이 없다**(`docker-compose.yml:15-17`). `down` 후 `up` 하면 Flyway가 V1(스키마)+V2(데모 시드)를 매번 새로 구성한다. `stop`/`start`는 데이터가 남으므로 리셋하려면 반드시 `down`을 거친다.

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
- **새 테스트에 DB를 끌어들이지 않는다** — `@SpringBootTest`는 `BackendApplicationTests` 하나로 충분하다. 새 테스트는 단위 또는 `@WebMvcTest`로 쓴다.

---

## convention-auditor

> 규약 원문은 **`backend/docs/reference.md`**(Claude 참조용 Markdown). 사람용 렌더는 `CODE_CONVENTIONS.html`이며 **명시 요청이 있을 때만** 수정한다.

- **레이어 구조** (모듈당 표준):
  `command/` `query/` `entity/` `event/` `projection/` `repository/` `dto/` `controller/` `infrastructure/`
- **spec/impl 분리 기준은 단 하나 — "구현이 변경될 가능성이 있는가"**. 외부 연동·전략 패턴·복수 구현체·Mock 필요·MSA 분리 후보만 `spec/`+`impl/`로 나눈다. 단순 CRUD(`StudentService`·`BusService`·`TenantService` 등)는 **인터페이스를 만들지 않는 게 맞다** — "인터페이스가 없다"를 위반으로 잡지 말 것.
- **엔티티 패키지는 예외 없이 `entity/`.** 옛 코드의 `notification`·`routing` 이 `domain/` 을 쓰던 것은 2026-08-24 재작성으로 소멸.
- **CQRS**: Command(생성·수정·삭제)는 **Query를 호출하지 않는다**. Projection은 읽기 모델만 만들고 비즈니스 로직을 두지 않는다.
- **계층 책임**: Controller는 검증·인증사용자 확인·서비스 호출만 / Service는 HTTP·Redis·Kafka·JPA를 직접 알지 않고 Port(spec) 경유 / Repository는 JPA 접근만 / 외부 기술은 `infrastructure/`.
- **DTO**: Entity를 직접 반환하지 않는다. Request DTO → Service → Response DTO.
- **Event 이름은 과거형** (`LocationUpdatedEvent`, `StudentBoardedEvent`). Command·Query 어휘를 이벤트명에 쓰지 않는다. 서비스 간 직접 체이닝 호출 금지, Kafka 경유 우선.
- **응답 규약**: `ApiResponse<T> { success, data, message }` 3필드뿐 — **머신리더블 `errorCode` 필드는 없다**(의도된 설계). 예외는 `BusinessException` + `ErrorCode` enum(8종), 전역 처리는 `GlobalExceptionHandler`. `@Valid` 실패는 `findFirst()`로 **첫 필드 오류 1개만** `"필드명: 메시지"` 형식으로 반환한다.
- **마이그레이션 — 2026-08-24 방향 전환으로 규칙이 뒤집혔다.** 첫 배포 이전인 현재는 **`V1__init_schema.sql` 을 직접 수정하고 로컬 DB 를 재구성**한다(`docker compose down` → `up -d postgres redis kafka`). 버전을 쌓지 않는다. 옛 규칙("`V{n}` 추가, V1 수정 금지")은 **첫 배포 이후에 되살아난다** — 근거와 전환 시점은 `docs/IMPLEMENTATION_PLAN.md` §2.1·§2.2. 데모 시드는 `db/migration-local/`(**`local`·`demo` 두 프로파일에서만 로드**, prod 미적용). 시드 비밀번호 해시는 Flyway placeholder `seedPasswordHash`로 주입 — local은 `application.yml` 기본값(평문 `password`), demo는 SSM 값(기본값 없음).
- **`package-info.java`를 두지 않는다** (패키지 레벨 애너테이션이 필요할 때만 예외).

---

## security-reviewer

> ⚠ **일부 무효 예정** — **살아남는 것**: JWT Bearer 필터 배치 · `STATELESS` · 공개 경로 목록 · `/ws/**` permitAll 의 근거 · 시크릿 보관 · CORS · BCrypt · 로그인 실패 메시지 미구분. **Phase 0·2 에서 소멸하는 것**: Role 5종 목록 · `AuthUser` 의 `List<Membership>` · `TenantGuard` 경유 규칙 — 새 모델은 계정 1개가 학원 1곳에 속하고 역할 6종이며 격리는 저장소 계층에서 강제한다 (`ARCHITECTURE §5`·`§6` · `IMPLEMENTATION_PLAN` Phase 2).

- **인증 방식**: JWT Bearer. `JwtAuthenticationFilter`를 `UsernamePasswordAuthenticationFilter` **앞에** 삽입, 세션 `STATELESS`, CSRF 비활성 (`global/security/SecurityConfig.java`).
- **공개 경로(permitAll)** — 이 목록이 늘어나면 반드시 근거를 따진다:
  `/api/auth/**` · `/actuator/health` · `/ws/**` · `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`. 그 외 `anyRequest().authenticated()`, 미인증은 403이 아니라 **401**(`HttpStatusEntryPoint`).
- **`/ws/**`가 permitAll인 것은 취약점이 아니다** — WebSocket 핸드셰이크엔 토큰을 못 싣는 클라이언트가 많아, 인증을 **STOMP CONNECT 프레임에서 `StompAuthChannelInterceptor`가 세션당 1회** 검증한다. 추가로 `/topic/tenant/{id}/**` SUBSCRIBE 시 테넌트 인가를 한 번 더 건다.
- **역할 인가**: `@EnableMethodSecurity` + 컨트롤러 메서드의 `@PreAuthorize`. Role 5종(STUDENT/PARENT/DRIVER/ACADEMY_ADMIN/PLATFORM_ADMIN).
- **현재 사용자 획득**: `AuthUser`(id, email, `List<Membership{tenantId, role}>`)를 컨트롤러 파라미터로 주입받는다.
- **멀티테넌트 격리 — 신규 관리자 API 리뷰 시 최우선 체크**: `global/tenant/TenantGuard.resolveTenantId(authUser, tenantId)`를 반드시 경유해야 한다. PLATFORM_ADMIN이 `tenantId`를 생략하면 400, 다른 학원 자원 접근은 403. 이 가드를 우회해 `tenantId`를 그대로 신뢰하는 코드가 크로스테넌트 유출 경로다.
- **시크릿 보관**:
  - 실제 값은 **`backend/.env`(gitignore, 커밋 금지)**. `backend/.env.example`은 **키 값을 빈 채로 유지**한다 — 여기에 실키가 들어가면 유출이다(특히 `NAVER_DIRECTIONS_KEY_ID` / `NAVER_DIRECTIONS_KEY`).
  - `JWT_SECRET`은 **공통 섹션에 기본값이 없다**(2026-08-23 변경). 개발용 기본값은 `local` 프로파일 블록에만 있어,
    `prod`·`demo` 로 뜨면서 `JWT_SECRET` 이 없으면 **애플리케이션이 기동에 실패한다**(플레이스홀더 미해결).
    `JwtTokenProvider` 가 `@Value` 생성자 주입이라 실패 시점이 첫 토큰 발급이 아니라 기동 시점이다.
    `DeploymentConfigGuardTest` 가 이 상태를 고정한다 — 공통 섹션에 기본값을 되살리면 테스트가 깨진다.
  - `docker-compose.yml`의 `schoolbus/schoolbus` DB 자격증명은 로컬 전용이며 prod 프로파일은 `${DB_URL}` 등 환경변수만 쓴다.
- **CORS**: `app.cors.allowed-origins`(콤마 구분)로 `/api/**`에만 적용. local은 개발 포트 6개 기본 허용, **prod는 기본값이 비어 있어 미설정 시 전부 차단**(의도된 설계).
- **비밀번호**: BCrypt(`BCryptPasswordEncoder`). 로그인 실패 메시지는 이메일 미존재/비밀번호 불일치를 **의도적으로 구분하지 않는다**(사용자 열거 방지).

---

## debugger

> ⚠ **일부 무효 예정** — **포트별 증상표·로그 포맷·비동기 스레드 모델의 기제(커밋 후 발행 · dedup skip · STOMP push)는 유효**. **Phase 0 에서 소멸하는 것**: `@Scheduled` 4종의 구체 주기 · Mock 위치 소스 기본값 · `V2__seed_data.sql` 의 계정 구성 — 옛 도메인 설정 블록(`app.location.mock` · `app.sos` · `app.drivesession` · `app.connection`)이 폐기 대상이다 (`IMPLEMENTATION_PLAN` §1.2).

**포트별 증상표** — 실패를 보면 먼저 여기를 대조한다.

`3000 frontend` 행은 참고용 — 프론트는 현재 착수 대상 밖이다.

| 포트 | 서비스 | 꺼져 있을 때의 증상 |
|---|---|---|
| 5432 | postgres | `bootRun`·`@SpringBootTest` 컨텍스트 로드 실패(Hikari 연결 거부 / Flyway 실패). **가장 흔한 원인** |
| 6379 | redis | 앱은 뜨지만 캐시·Pub/Sub 경로에서 연결 예외 |
| **29092** | kafka | **가장 헷갈리는 증상** — API는 200을 반환하는데 **알림·WebSocket push가 오지 않는다**. 호스트에서 `bootRun` 할 땐 `PLAINTEXT_HOST`(29092), 컨테이너 내부는 `kafka:9092` |
| 8080 | backend | Swagger UI `http://localhost:8080/swagger-ui/index.html` |
| 3000 | frontend | `--profile frontend`로만 기동(기본 compose에서 제외) |

- **Docker가 꺼져 있으면 직접 `docker compose up`을 실행하지 말고 사용자에게 요청**한다. 그 실패는 코드 결함이 아니라 **환경 문제로 분류**해 보고한다.
- **로그 포맷**: 별도 logback 설정이 없어 Spring Boot 기본 콘솔 포맷. `spring.jpa.properties.hibernate.format_sql: true`라 SQL이 정렬 출력된다.
- **비동기·스레드 모델** — "저장은 됐는데 후속이 안 온다"류 버그는 대부분 여기다:
  1. `@TransactionalEventListener(AFTER_COMMIT)`(`global/event/TransactionalDomainEventRelay`) → **커밋 이후**에야 Kafka 발행. 롤백되면 아무 일도 안 일어난다.
  2. Kafka `@KafkaListener` 소비 → 알림 생성. **`dedupKey` 중복이면 조용히 skip**한다(멱등 처리) — "두 번째 요청에 알림이 안 온다"는 정상 동작일 수 있다.
  3. STOMP 브로커 스레드에서 `/user/queue/**`·`/topic/tenant/{id}/**` push. `PushTargetResolver`가 대상을 못 찾으면 개인 큐뿐 아니라 **토픽 broadcast까지 통째로 skip**된다.
  4. `@Scheduled` 4종: 위치 tick 3,000ms / 연결끊김 점검 10,000ms(유예 30초) / 등원 접근 점검 15,000ms / SOS 에스컬레이션 30,000ms. 주기값은 `application.yml`의 `app.*` 하위.
- **Mock 위치 소스가 기본 켜져 있다** — 학생(`app.location.mock.enabled`)·**버스(`app.location.bus-mock.enabled`) 둘 다 local 기본 `true`**. 좌표가 저절로 움직이는 건 버그가 아니다. prod 프로파일에서는 둘 다 `false`, 실 GPS push가 `true`.
- 로그인 계정은 Flyway 시드(`db/migration-local/V2__seed_data.sql`) 참조 — **로컬**의 비밀번호는 전부 `password`(배포 환경은 다름).

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
- **이 저장소의 사양 정본은 `docs/` 다.** 브리프·재료 문서와 `docs/` 가 어긋나면 `docs/` 가 이긴다. 재료 문서 `.superpowers/sdd/IMPLEMENTATION_PLAN/phase1-*.md` 는 **조사 산출물이라 낡은 값을 포함**한다 — 그것을 근거로 지적하지 마라
- **코드 컨벤션 근거는 `backend/docs/reference.md` §19·§20** 이다. §19 는 "기본 한 문장, 둘째 문장은 다른 질문에 답할 때만" 이며 **문장 수를 세어 결함으로 매기지 않는다**(2026-08-25 개정)
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
- **코드 그래프 도구 있음**: 루트 `.tokensave/`. 영향범위 확인은 `tokensave_impact` / `tokensave_callers` / `tokensave_affected` 를 쓰고 Explore agent를 띄우지 않는다. 툴로 부족하면 `.tokensave/tokensave.db`(테이블 `nodes`·`edges`·`files`)에 SQL로 직접 질의.
- **리뷰 시 함께 볼 것**:
  - 엔티티 변경이 스키마에 반영됐는가(`ddl-auto: validate`라 없으면 기동 자체가 실패한다). **반영 방식은 첫 배포 이전인 현재 `V1__init_schema.sql` 직접 수정 + 로컬 DB 재구성**이다 — 위 `convention-auditor` 절 참조.
  - 새 API 가 학원 격리를 저장소 계층에서 강제하는가 (`ARCHITECTURE §6.1`). **`TenantGuard` 경유 검사는 Phase 0·2 이후 무효** — 옛 N:M 멤버십 전제다.
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
| `backend/docs/reference.md` | 코드 컨벤션 원본 | `convention-auditor`가 틀린 근거로 지적한다 |

- **대조 범위는 `backend/` 뿐이다.** 프론트는 착수 대상 밖이라 `frontend/` 코드와 `frontend/docs/` 3종은 드리프트 지적 대상이 아니다 — 갱신하지 않기로 한 문서를 "낡았다"고 올리지 않는다.
- **기준선 수치는 부재.** 옛 기준선이던 Swagger `"00. MVP 사용 API"` 태그 17개는 **2026-08-24 방향 전환으로 무효**(태그 체계 자체가 폐기). 새 기준선은 Phase 1 이후 `IMPLEMENTATION_PLAN` §2.3 의 테이블 수와 §3.3 의 대조 테스트 3종이 대신한다.
- **`backend/docs/*.html`은 대조 대상이 아니다.** `CODE_CONVENTIONS.html` 등은 `reference.md`의 사람용 렌더이며 **원칙만 동기화하고 자동 동기화하지 않는다**(의도된 설계). HTML이 Markdown과 다르다는 지적은 올리지 않는다.
- **`backend/docs/학원 통학버스 통합관리 시스템.docx` 는 기획 원본(불변)** 이라 코드와 어긋나는 게 정상이다. 대조 대상이 아니다. (`projectInfo.md` 는 2026-08-24 삭제)
- 주기·기본값은 `backend/src/main/resources/application.yml`을 **직접 읽어** 대조한다(미커밋 수정분이 자주 있다).
- 결과는 `backend/report/YYYY-MM-DD-주제.md`로 남긴다. **문서와 코드 어느 쪽도 고치지 않는다.**

---

## 알려진 함정


- **2026-08-25** — **리뷰어·검증자에게 "응답 자체가 보고서다, 별도 파일을 만들지 마라" 라고 지시하면 전송 유실 시 산출물이 통째로 사라진다.** 구현자는 커밋이 남아 복구되지만 리뷰어는 **아무 흔적도 남지 않는다** — 한 세션에서 리뷰 판정 회수를 위해 재요청한 사례가 3회, 두 번 연속 실패해 리뷰어를 교체한 사례가 1회. **대응 — 리뷰어에게도 `.superpowers/sdd/<plan>/` 아래 판정 파일을 먼저 쓰게 하고(git-ignored 라 인덱스 미오염) 그 다음 응답으로도 보내게 한다.** 조율자 컨텍스트 보호(응답에 본문 복사 금지)와 충돌하지 않는다 — 조율자는 필요할 때만 파일을 읽는다
- **2026-08-25** — **에이전트가 작업을 마치고도 최종 보고 메시지만 유실되는 경우가 잦다.** 증상 — `idle_notification` 은 오는데 결과 보고가 부재. **커밋·워크트리는 정상**인 경우가 대부분이라 작업 유실이 아니라 **전송 유실**이다. 이 세션에서 5회 발생(`p1-t8-review` · `p1-t9-impl2` · `p1-t6t8-rereview1` 등). **대응 — 죽었다고 판단하지 말고 `SendMessage` 로 "판정을 다시 보내라" 고 요청한다.** 요청 시 판정 대상·최우선 확인 항목을 **다시 실어 보내야** 한다(그쪽 컨텍스트가 남아 있어도 재확인 비용이 싸다). 재착수시키면 같은 작업을 두 번 하게 된다
- **2026-08-25** — **이 머신은 `pmset` 의 `sleep` 이 `1`(유휴 1분)이라 백그라운드 에이전트가 작업 중 죽는다.** 증상은 `API Error: Your computer went to sleep mid-response` 이고, 한 세션에서 **4회 발생**했다. 도구 호출 사이 유휴가 1분을 넘기는 긴 작업(테스트 실행·컴파일)에서 특히 잘 걸린다. **병렬 에이전트를 띄우기 전에 `nohup caffeinate -i -m -s &` 로 절전을 억제하고 `pmset -g assertions | grep PreventSystemSleep` 으로 확인**한다. 되돌리는 법은 `pkill caffeinate` — `pmset` 설정 자체는 건드리지 않는다. ⚠ **이 사망을 코드 결함이나 에이전트 결함으로 오분류하지 마라** — 워크트리 상태를 확인해 잔여물이 없으면 그대로 재착수한다. resume 보다 **신규 에이전트**가 낫다(죽은 세션의 컨텍스트 무결성을 신뢰할 근거가 부재)
작업 중 발견한 이 저장소 특유의 함정을 누적한다. 근거(파일:라인, 명령, 날짜)를 같이 남긴다. **날짜가 붙은 항목은 그 시점의 기록**이라 대상 파일이 이후 삭제됐을 수 있다 — 교훈만 취한다.

- **2026-07-28** *(대상 문서 `MVP_API_SPEC.md` 는 이후 삭제 — 교훈만 유효)* — `MVP_API_SPEC.md:503`(§8 비고)이 "버스 위치는 Mock 소스가 없어 기사가 직접 보고해야 한다"고 서술하지만 **사실과 반대**다. `location/source/MockBusLocationSource.java:20,32`가 `app.location.bus-mock.enabled` 기본 `true`(`application.yml:60-61`)로 3초마다 버스 좌표를 자동 생성한다. 같은 문서 `:171`(`"origin":"MOCK"`)·`:191`과도 모순. **문서를 근거로 위치 기능 동작을 판단하면 틀린다.**
- ~~**2026-07-28** — `git symbolic-ref --short refs/remotes/origin/HEAD` 가 실패한다~~ → **2026-08-25 해소.** 같은 명령이 `origin/main` 을 정상 반환한다. 그 사이에 `origin/HEAD` 가 설정된 것으로 보인다. **낡은 함정 항목을 근거로 절차를 건너뛰지 마라 — 명령으로 확인하는 편이 맞다.**
- **2026-07-28** — 문서 검증 에이전트에게 문서만 지정하면 **`backend/report/` 의 기존 보고서를 먼저 찾아 읽는다**(`docs-drift-auditor` 실측). 그러면 "기존 지적 N건 재현"이 독립 재현이 아니게 된다. 교차검증이 목적이면 프롬프트에 **기존 보고서 열람 금지**를 명시한다.
- **2026-07-28** — 문서 검증은 **문서 전체를 한 에이전트에 맡기지 말고 섹션별로 쪼개 병렬로 돌린다.** `MVP_API_SPEC.md` 실측: 전체 패스 1개 = 신규 1건 / 섹션 패스 3개 = 신규 11건. 전체 패스는 계약 일치 여부 확인용으로만 쓴다.
- **2026-07-28** — 전역 `rtk` hook 이 `grep`·`ls` 출력을 압축해 내용을 삼키는 경우가 있다(`grep -n '^#' reference.md` → `19 matches in 0 files` 만 출력). 파일 목차·목록을 확보할 땐 Read 툴을 쓰거나 `rtk proxy '<원본명령>'` 으로 우회한다.
