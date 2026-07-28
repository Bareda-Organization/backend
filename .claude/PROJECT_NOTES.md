# PROJECT_NOTES — School-Bus

전역 에이전트 7개(`convention-auditor` · `debugger` · `diff-reviewer` · `docs-drift-auditor` · `security-reviewer` · `test-runner` · `test-writer`)가 이 저장소에서 동작할 때 참조하는 **사실 노트**다. 절차·판단기준은 전역 에이전트 정의(`~/.claude/agents/*.md`)에 있고, 여기에는 **이 프로젝트에서만 참인 값**만 적는다. 에이전트를 프로젝트에 복제하지 않는다.

**프로젝트 전용 에이전트 2개**가 `.claude/agents/` 에 따로 있다 — `ui-implementer` · `design-system-auditor`(2026-07-29 신설). 전역 7개에 **없는 역할**이라 복제가 아니다. 전역 에이전트로 대체되면 삭제한다.

작성일 2026-07-28 / 검증 방식: 소스 직접 확인(앱 미기동)

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
- **알려진 예외 — 지적 금지**: 엔티티 패키지 표준은 `entity/`지만 **`notification`·`routing` 두 모듈은 역사적 이유로 `domain/`을 쓴다.** 일괄 rename은 범위 밖으로 확정(`PROJECT_MASTER_PLAN.md` §12.3).
- **CQRS**: Command(생성·수정·삭제)는 **Query를 호출하지 않는다**. Projection은 읽기 모델만 만들고 비즈니스 로직을 두지 않는다.
- **계층 책임**: Controller는 검증·인증사용자 확인·서비스 호출만 / Service는 HTTP·Redis·Kafka·JPA를 직접 알지 않고 Port(spec) 경유 / Repository는 JPA 접근만 / 외부 기술은 `infrastructure/`.
- **DTO**: Entity를 직접 반환하지 않는다. Request DTO → Service → Response DTO.
- **Event 이름은 과거형** (`LocationUpdatedEvent`, `StudentBoardedEvent`). Command·Query 어휘를 이벤트명에 쓰지 않는다. 서비스 간 직접 체이닝 호출 금지, Kafka 경유 우선.
- **응답 규약**: `ApiResponse<T> { success, data, message }` 3필드뿐 — **머신리더블 `errorCode` 필드는 없다**(의도된 설계). 예외는 `BusinessException` + `ErrorCode` enum(8종), 전역 처리는 `GlobalExceptionHandler`. `@Valid` 실패는 `findFirst()`로 **첫 필드 오류 1개만** `"필드명: 메시지"` 형식으로 반환한다.
- **마이그레이션**: 새 컬럼/테이블은 `src/main/resources/db/migration/V{n}__설명.sql`을 **새로 추가**한다. **`V1__init_schema.sql` 수정 금지.** 로컬 데모 시드는 `db/migration-local/`(local 프로파일에서만 로드, prod 미적용).
- **`package-info.java`를 두지 않는다** (패키지 레벨 애너테이션이 필요할 때만 예외).

---

## security-reviewer

- **인증 방식**: JWT Bearer. `JwtAuthenticationFilter`를 `UsernamePasswordAuthenticationFilter` **앞에** 삽입, 세션 `STATELESS`, CSRF 비활성 (`global/security/SecurityConfig.java`).
- **공개 경로(permitAll)** — 이 목록이 늘어나면 반드시 근거를 따진다:
  `/api/auth/**` · `/actuator/health` · `/ws/**` · `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`. 그 외 `anyRequest().authenticated()`, 미인증은 403이 아니라 **401**(`HttpStatusEntryPoint`).
- **`/ws/**`가 permitAll인 것은 취약점이 아니다** — WebSocket 핸드셰이크엔 토큰을 못 싣는 클라이언트가 많아, 인증을 **STOMP CONNECT 프레임에서 `StompAuthChannelInterceptor`가 세션당 1회** 검증한다. 추가로 `/topic/tenant/{id}/**` SUBSCRIBE 시 테넌트 인가를 한 번 더 건다.
- **역할 인가**: `@EnableMethodSecurity` + 컨트롤러 메서드의 `@PreAuthorize`. Role 5종(STUDENT/PARENT/DRIVER/ACADEMY_ADMIN/PLATFORM_ADMIN).
- **현재 사용자 획득**: `AuthUser`(id, email, `List<Membership{tenantId, role}>`)를 컨트롤러 파라미터로 주입받는다.
- **멀티테넌트 격리 — 신규 관리자 API 리뷰 시 최우선 체크**: `global/tenant/TenantGuard.resolveTenantId(authUser, tenantId)`를 반드시 경유해야 한다. PLATFORM_ADMIN이 `tenantId`를 생략하면 400, 다른 학원 자원 접근은 403. 이 가드를 우회해 `tenantId`를 그대로 신뢰하는 코드가 크로스테넌트 유출 경로다.
- **시크릿 보관**:
  - 실제 값은 **`backend/.env`(gitignore, 커밋 금지)**. `backend/.env.example`은 **키 값을 빈 채로 유지**한다 — 여기에 실키가 들어가면 유출이다(특히 `NAVER_DIRECTIONS_KEY_ID` / `NAVER_DIRECTIONS_KEY`).
  - `JWT_SECRET`은 미설정 시 `application.yml:79`의 로컬 기본값(`local-dev-secret-change-me...`)으로 뜬다 — **prod에서 이 기본값이 쓰이면 치명적**이므로 환경변수 주입 여부를 확인한다.
  - `docker-compose.yml`의 `schoolbus/schoolbus` DB 자격증명은 로컬 전용이며 prod 프로파일은 `${DB_URL}` 등 환경변수만 쓴다.
- **CORS**: `app.cors.allowed-origins`(콤마 구분)로 `/api/**`에만 적용. local은 개발 포트 6개 기본 허용, **prod는 기본값이 비어 있어 미설정 시 전부 차단**(의도된 설계).
- **비밀번호**: BCrypt(`BCryptPasswordEncoder`). 로그인 실패 메시지는 이메일 미존재/비밀번호 불일치를 **의도적으로 구분하지 않는다**(사용자 열거 방지).

---

## debugger

**포트별 증상표** — 실패를 보면 먼저 여기를 대조한다.

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
- 로그인 계정은 Flyway 시드(`db/migration-local/V2__seed_data.sql`) 참조 — **비밀번호는 전부 `password`**.

---

## diff-reviewer

- **기준 브랜치: `main`.** `origin/HEAD`가 설정돼 있지 않으므로 `git symbolic-ref`에 의존하지 말고 `main`을 직접 쓴다. 현재 원격 추적 브랜치 구성이 없으니 `git diff main...HEAD`가 아니라 작업 트리 diff 기준으로 보는 편이 안전하다.
- **코드 그래프 도구 있음**: 루트 `.tokensave/`. 영향범위 확인은 `tokensave_impact` / `tokensave_callers` / `tokensave_affected` 를 쓰고 Explore agent를 띄우지 않는다. 툴로 부족하면 `.tokensave/tokensave.db`(테이블 `nodes`·`edges`·`files`)에 SQL로 직접 질의.
- **리뷰 시 함께 볼 것**:
  - 새 마이그레이션이 `V1__init_schema.sql`을 고치지 않았는가, 엔티티 변경에 대응하는 `V{n}` 파일이 있는가(`ddl-auto: validate`라 없으면 기동 자체가 실패한다).
  - 새 관리자 API가 `TenantGuard`를 경유하는가.
  - 새 엔드포인트를 MVP로 노출한다면 `@Operation(tags={"00. MVP 사용 API", "<원래 태그>"})` 이중 태깅이 맞는지 — 현재 MVP 태그는 **정확히 17개**이며 개수가 바뀌면 `docs/MVP_API_SPEC.md`·`docs/MVP_RELEASE_TRACKER.md`도 함께 갱신 대상이다.
- **문서 반영 규칙**: 진행 상황·큰 변경은 `backend/docs/PROJECT_MASTER_PLAN.md`(단일 소스)에 반영한다. **Markdown 원본을 고쳤다고 대응 HTML을 자동 동기화하지 않는다** — HTML은 사용자가 명시 요청할 때만.
- **보고서 산출물**: 리뷰·감사·분석 결과는 대화에만 남기지 말고 `backend/report/YYYY-MM-DD-주제.md`로 쓴다. **수정 지시가 없으면 보고만 하고 코드는 건드리지 않는다.**

---

## docs-drift-auditor

이 저장소는 문서를 계약처럼 쓴다. 아래 4개가 **계약 문서**이며, 지정이 없으면 이 목록이 대상이다.

| 문서 | 성격 | 드리프트 시 영향 |
|---|---|---|
| `backend/docs/MVP_API_SPEC.md` | 프론트가 보고 구현하는 API 계약 | **가장 높음** — 필드·부수효과가 틀리면 프론트가 깨진다 |
| `backend/docs/MVP_RELEASE_TRACKER.md` | MVP 범위·진행 상태의 기준 | 범위 판단이 틀어진다 |
| `backend/docs/PROJECT_MASTER_PLAN.md` | 기획+구현계획 단일 소스 | 완료/미완 표기가 실제와 어긋난다 |
| `backend/docs/reference.md` | 코드 컨벤션 원본 | `convention-auditor`가 틀린 근거로 지적한다 |

- **기준선 수치**: Swagger `"00. MVP 사용 API"` 태그는 **정확히 17개**(로그인 2 · 위치 2 · 승하차 4 · 배차 7 · 알림 2). 개수가 바뀌면 위 4개 문서 중 앞의 둘이 함께 갱신돼야 한다. 세는 법:
  ```bash
  grep -rc '00. MVP 사용 API' backend/src/main/java --include=*.java
  ```
- **`backend/docs/*.html`은 대조 대상이 아니다.** `CODE_CONVENTIONS.html` 등은 `reference.md`의 사람용 렌더이며 **원칙만 동기화하고 자동 동기화하지 않는다**(의도된 설계). HTML이 Markdown과 다르다는 지적은 올리지 않는다.
- **`projectInfo.md`·`학원 통학버스 통합관리 시스템.docx`는 기획 원본(불변)** 이라 코드와 어긋나는 게 정상이다. 대조 대상이 아니다.
- 주기·기본값은 `backend/src/main/resources/application.yml`을 **직접 읽어** 대조한다(미커밋 수정분이 자주 있다).
- 결과는 `backend/report/YYYY-MM-DD-주제.md`로 남긴다. **문서와 코드 어느 쪽도 고치지 않는다.**

---

## ui-implementer / design-system-auditor *(프론트엔드 전용, 2026-07-29 신설)*

> 이 둘만 **`backend/` 가 아니라 `frontend/` 에서** 동작한다. 위의 Gradle·Spring 사실은 해당 없다.

| 항목 | 값 |
|---|---|
| 작업 디렉터리 | **`frontend/`** |
| SDK | Flutter **3.44.8** / Dart **3.12.2** — `/opt/homebrew/bin/flutter` (PATH 에 있다) |
| 상태관리 | `flutter_riverpod` **only**. `riverpod_annotation`/`generator` 는 analyzer 충돌로 **의도적 제외** — provider 는 손으로 선언한다 |
| 검사 명령 | `flutter analyze`(무경고) · `dart format --set-exit-if-changed lib/` · `flutter test` |
| 테스트 기준선 | **203건 전건 통과**(단위 194 + 위젯 9). 이 수가 줄면 회귀다 |

**규칙 문서 3종** — 지정 없이 "컨벤션대로"라고만 하면 이 셋을 뜻한다:

| 문서 | 무엇 |
|---|---|
| `frontend/docs/DESIGN_SYSTEM.md` | 화면이 어떻게 보여야 하는가 (토큰·상태 계약·컴포넌트) |
| `frontend/docs/FLUTTER_CODE_CONVENTIONS.md` | 코드를 어떻게 쓰는가 + §9 검사 체크리스트 |
| `frontend/docs/DESIGN_BRIEF_DRIVER_MOBILE.md` | **요구 근거**. 시안과 어긋나면 이쪽이 이긴다 |

- **체크리스트 분담**: C-1~C-7 = `convention-auditor` / **C-8 = `design-system-auditor`**. 중복 지적하지 않는다
- `ui-implementer` 는 **git 명령을 쓰지 않는다.** 커밋은 메인이 한다 — 에이전트가 보고 단계에서 죽어도 편집분이 디스크에 남게 하려는 것이다(2026-07-28 에 에이전트 4개 중 3개가 토큰 한도로 사망한 이력이 있다)
- `ui-implementer` 를 병렬로 띄울 땐 **파일 집합이 겹치지 않게** 나눈다. 담당 표는 `frontend/docs/DESIGN_MIGRATION_PLAN.md` §화면 ↔ 파일 대응표
- 워크트리 격리(`isolation: "worktree"`)를 **쓰지 않는다** — 격리 워크트리에서 죽으면 임시 디렉터리째 유실된다
- 감사 보고서는 `frontend/report/YYYY-MM-DD-주제.md` (백엔드는 `backend/report/`)
- 브라우저 확인은 `http://localhost/` (nginx :80 단일 진입점). Docker 기동은 **사용자에게 요청**하고, 옛 화면이 보이면 service worker 캐시를 의심한다(`FLUTTER_FRONTEND_PLAN.md` §9)

---

## 알려진 함정

작업 중 발견한 이 저장소 특유의 함정을 누적한다. 근거(파일:라인, 명령, 날짜)를 같이 남긴다.

- **2026-07-28** — `MVP_API_SPEC.md:503`(§8 비고)이 "버스 위치는 Mock 소스가 없어 기사가 직접 보고해야 한다"고 서술하지만 **사실과 반대**다. `location/source/MockBusLocationSource.java:20,32`가 `app.location.bus-mock.enabled` 기본 `true`(`application.yml:60-61`)로 3초마다 버스 좌표를 자동 생성한다. 같은 문서 `:171`(`"origin":"MOCK"`)·`:191`과도 모순. **문서를 근거로 위치 기능 동작을 판단하면 틀린다.**
- **2026-07-28** — `git symbolic-ref --short refs/remotes/origin/HEAD` 가 실패한다(`origin/HEAD` 미설정). 기준 브랜치를 명령으로 얻으려는 절차는 이 저장소에서 무조건 실패하므로 `main` 을 직접 쓴다.
- **2026-07-28** — 문서 검증 에이전트에게 문서만 지정하면 **`backend/report/` 의 기존 보고서를 먼저 찾아 읽는다**(`docs-drift-auditor` 실측). 그러면 "기존 지적 N건 재현"이 독립 재현이 아니게 된다. 교차검증이 목적이면 프롬프트에 **기존 보고서 열람 금지**를 명시한다.
- **2026-07-28** — 문서 검증은 **문서 전체를 한 에이전트에 맡기지 말고 섹션별로 쪼개 병렬로 돌린다.** `MVP_API_SPEC.md` 실측: 전체 패스 1개 = 신규 1건 / 섹션 패스 3개 = 신규 11건. 전체 패스는 계약 일치 여부 확인용으로만 쓴다.
- **2026-07-28** — 전역 `rtk` hook 이 `grep`·`ls` 출력을 압축해 내용을 삼키는 경우가 있다(`grep -n '^#' reference.md` → `19 matches in 0 files` 만 출력). 파일 목차·목록을 확보할 땐 Read 툴을 쓰거나 `rtk proxy '<원본명령>'` 으로 우회한다.
