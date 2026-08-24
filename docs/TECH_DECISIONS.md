# 바래다 (BARAEDA) — 기술 선택과 불채택

**한 줄 정의:** 각 구간에 어떤 라이브러리·프레임워크를 쓰고 **무엇을 쓰지 않기로 했는지**, 그리고 그 근거를 남기는 문서.

| 항목 | 내용 |
|---|---|
| 문서 버전 | v1.0 |
| 작성일 | 2026-08-24 |
| 기준 | ARCHITECTURE v1.0 · ERD v1.0 · 사양 4종 v1.0 |
| 성격 | **결정 기록.** 구현 착수 시 판단이 갈릴 지점을 미리 고정 |

**자매 문서** — [FEATURE_SPEC.md](./FEATURE_SPEC.md) · [PRD.md](./PRD.md) · [USER_FLOWS.md](./USER_FLOWS.md) · [API_SPEC.md](./API_SPEC.md) · [ARCHITECTURE.md](./ARCHITECTURE.md) · [ERD.md](./ERD.md)

## 0. 이 문서가 있는 이유

**불채택 결정은 근거를 남기지 않으면 반드시 다시 제안된다.** "배치니까 Spring Batch", "상태가 있으니 StateMachine" 은 이름만 보고 내리기 쉬운 판단이라, 왜 아닌지를 적어두지 않으면 구현 중에 되살아난다.

문서 경계 — **무엇을 만드는가**는 `ARCHITECTURE.md`, **어떤 도구로 만드는가**는 이 문서. 구조 설명을 여기 복제하지 않고 절 번호(`ARCHITECTURE §9`)로 참조한다.

---

## 1. 결론 요약

| 구간 | 채택 | 불채택 |
|---|---|---|
| 인증·인가 | Spring Security — **역할·권한까지만** | Security 로 학원 격리 |
| 권한 모델 | **RBAC** — 권한 상수 enum + 역할↔권한 매핑(코드) | DB 권한 테이블 · Security ACL · 토큰에 권한 적재 |
| enum 표기 | DB·wire **소문자 snake_case** · Java 상수 대문자 · 공유 컨버터로 연결(§6.2) | `@Enumerated(STRING)` · 표기를 한 층에 맞춰 통일 |
| 토큰 전송 | access = `Authorization` 헤더(앱·웹 공통) · refresh = 앱은 기기 보안 저장소 · 웹은 **HttpOnly 쿠키** | access 를 쿠키로 전송 · 앱에 HttpOnly 적용 · CSRF 동기화 토큰 |
| 민감 데이터 | 역할별 응답 DTO + 조회 시 마스킹 + L3 감사 로그 | 엔티티 직렬화 · `@JsonIgnore` 의존 |
| 배치 | `@Scheduled` + `ThreadPoolTaskExecutor` + ShedLock | **Spring Batch** · Quartz |
| 상태 전이 | **JPA 엔티티 메서드** + 허용 전이 상수 | **Spring StateMachine** |
| 경합 전이 | JPA **`@Modifying` 조건부 UPDATE** | 변경 감지(dirty checking)로 처리 |
| 외부 지도 API | Resilience4j (TimeLimiter · CircuitBreaker · Bulkhead) | 무방비 동기 호출 |
| 이벤트 | **트랜잭셔널 아웃박스**(즉시 발송 + 워커 재시도) | `AFTER_COMMIT` 단독 · 트랜잭션 안 발송 |
| 시각 | **`Clock` 빈 주입** · 타입은 **`OffsetDateTime`**(§6.1) | `LocalDateTime.now()` 직접 호출 · `LocalDateTime` 필드 |
| 조회 | **전 구간 JPA** — 무거운 조회는 DTO 프로젝션 + fetch join | JdbcClient · QueryDSL · MyBatis |
| 추상화 | 교체 축만 `spec`/`impl` 인터페이스 분리 (ARCHITECTURE §3.2.1) | 단순 CRUD 까지 인터페이스 |
| 응답 | 역할별 DTO + 공통 응답 봉투 | 엔티티 직렬화 |
| 객체 생성 | 엔티티 = **정적 팩토리** · DTO = **`record`** · 픽스처 = 빌더 (ARCHITECTURE §3.2.3) | 엔티티에 `@Builder`·`@Setter` |
| 테스트 | Testcontainers + Awaitility | H2 |

**이 시스템의 성격** — "대용량 데이터 가공"이 아니라 **"정해진 시각에 소량을 정확히 한 번 처리"**. 무거운 프레임워크를 넣으면 개념만 늘고 얻는 것이 없다. 투자할 곳은 **동시성 제어 · 시각 주입 · 외부 호출 보호** 셋.

---

## 2. 인증·인가 — Spring Security 의 경계

**결정:** Security 는 "누구이고 어떤 역할인가"까지만 답하고, "이 학원 자원인가"는 저장소가 답한다.

### 2.1 "Spring Security" 가 가리키는 범위

Spring Security 는 기능이 넓어 "쓴다"만으로는 범위가 안 잡힌다. **요청이 컨트롤러에 닿기 전에 지나는 필터 체인**이 본체이고, 그 위에 여러 부가 기능이 얹혀 있다. 이 프로젝트가 쓰는 것은 아래 6개뿐이다.

| 쓰는 것 | 무엇 | 어디에 |
|---|---|---|
| `SecurityFilterChain` | 경로별 인증 필요 여부 선언 | 비인증 허용 5개 경로 vs 나머지 (API_SPEC §1.2) |
| **커스텀 JWT 인증 필터** | 헤더의 토큰을 검증해 인증 주체를 만듦 | jjwt 로 파싱, 실패 시 `401 TOKEN_EXPIRED` |
| **커스텀 `AuthorizationManager`** | 계정 상태 게이트 ①층 | `pending`·`rejected`·`blocked` 차단, 허용 목록 방식 |
| `@PreAuthorize` 메서드 보안 | 역할 권한 ②층 | 역할 6종 × 기능 |
| `PasswordEncoder` (BCrypt) | 비밀번호 해싱·대조 | 가입·로그인·비밀번호 변경 |
| STOMP `ChannelInterceptor` | WebSocket **구독 시점** 인가 | 연결만 인증하고 구독 경로를 검사하지 않으면 토큰 보유자가 남의 채널을 구독 |

| 안 쓰는 것 | 왜 |
|---|---|
| OAuth2 / OIDC / `oauth2-resource-server` | 자체 발급 토큰. 외부 인증 제공자 부재 |
| Security 의 `formLogin()` 필터 · 세션 · `RememberMe` | **stateless** — 토큰 기반이라 서버 세션 부재. ⚠ 제품 사양의 "form 로그인"(C-01)과 **다른 말** — 아래 참조 |
| CSRF 토큰 (`CsrfTokenRepository`) | 인증 수단이 `Authorization` 헤더라 대상 밖. **웹의 refresh 쿠키 1개는 예외이나 CSRF 토큰이 아니라 `SameSite=Strict` 로 막는다** — 근거는 §2.4. Spring Security 의 CSRF 필터는 비활성화 유지 |
| Spring Security **ACL** | 도메인 객체 단위 권한 테이블. 학원 격리는 조건 한 줄이라 ACL 은 과함 (③층은 저장소가 담당) |
| `@Secured` · `@RolesAllowed` | `@PreAuthorize` 하나로 통일 — 표현식을 쓸 수 있어 조건이 늘어도 형태가 유지 |

⚠ **"form 로그인"이라는 말이 두 가지를 가리킨다.**

| 말 | 뜻 | 이 프로젝트 |
|---|---|---|
| 제품 사양의 **form 회원가입·로그인** (C-01) | 아이디·비밀번호를 **폼에 입력**해 인증. 코드 로그인·발급 계정과 대비되는 개념 | **채택** — 전 인원이 이 방식 |
| Spring Security 의 **`formLogin()`** | 세션 기반 인증 필터(`UsernamePasswordAuthenticationFilter`). 로그인 페이지 렌더 · 리다이렉트 · `JSESSIONID` 발급 | **미채택** |

둘은 이름만 같다. 이 프로젝트는 **아이디·비밀번호를 REST 엔드포인트(`POST /auth/login`)로 받아 JWT 두 개를 반환**하고, 이후 요청은 `Authorization: Bearer` 헤더로 인증한다 (C-14 · API_SPEC §2.5). 화면이 폼이라는 점에서 "form 로그인"이 맞고, Security 의 `formLogin()` 필터를 쓰지 않는다는 점에서 그 기능은 미채택이다.

**비유하자면 건물 입구의 검문소다.** 신분증을 확인하고(인증), 출입증 등급을 본다(역할). 그러나 **"이 서류가 당신 부서 것인가"는 검문소가 알 수 없다** — 서류를 꺼내오는 사무실(저장소)이 확인해야 한다. 그것이 ③층을 Security 밖에 두는 이유다.

`ARCHITECTURE §5.1` 의 3층을 어디에 구현하는가.

| 층 | 구현 위치 | 근거 |
|---|---|---|
| ① 계정 상태 게이트 | Security **필터/인터셉터 한 곳** — 허용 목록 + 기본 차단 | `pending` 은 2개 API만 허용. 컨트롤러에 흩으면 새 엔드포인트마다 누락 가능성 |
| ② 역할 권한 | Security **메서드 보안** — `@PreAuthorize` | 역할 6종 × 기능. 선언적으로 |
| ③ 자원 소속 | **저장소·서비스 계층** | 학원 격리 · 보호자↔자녀 · 매니저↔회차 |

**③을 Security 로 하지 않는 이유 둘.**

1. `@PreAuthorize` 로 학원 격리를 걸려면 판정 시점에 DB 를 조회해야 하고, 같은 데이터를 인가에서 한 번 서비스에서 또 한 번 읽는다.
2. **목록 조회는 판정 대상이 아직 없다.** 단건은 "이 자원이 내 학원 것인가"를 물을 수 있지만 목록은 물을 자원이 존재하지 않는다. 목록은 **쿼리에 `academy_id` 조건을 저장소가 강제**하는 방식이라야 한다 (`ARCHITECTURE §6.1`).

**JWT** — 자체 발급 토큰이라 `oauth2-resource-server` 로 전환하지 않는다. 표준 리소스 서버의 이득(발급자 검증·JWKS 회전)이 없고 커스텀 클레임 처리만 번거로워진다. 현행 jjwt + 커스텀 필터 유지.

### 2.2 RBAC 구현

**결정:** 권한 상수 카탈로그([FEATURE_SPEC §6.2](./FEATURE_SPEC.md))를 코드 enum 으로 두고 `@PreAuthorize` 가 그 상수를 검사한다. DB 권한 테이블·Spring Security ACL 을 쓰지 않는다.

```java
public enum Permission {
    STUDENT_READ_BASIC, STUDENT_READ_SENSITIVE, STUDENT_READ_PHOTO, STUDENT_WRITE,
    ROSTER_READ, BOARDING_WRITE, BOARDING_REVERT,
    RUN_START, RUN_ARRIVE, DELAY_NOTIFY,
    EMERGENCY_RAISE, EMERGENCY_ACK, EXCEPTION_REPORT,
    ROUTE_READ, ROUTE_MANAGE, INTENT_WRITE, CHANGE_REQUEST_WRITE, CHANGE_APPROVE,
    SIGNUP_APPROVE, MANAGER_MANAGE, BUS_MANAGE, SCHEDULE_MANAGE,
    MONITOR_ACADEMY, MONITOR_ALL, NOTIFICATION_LOG_READ,
    ACADEMY_MANAGE, STAFF_APPROVE, ACCOUNT_UNBLOCK, AUDIT_READ
}

// 역할 → 권한. 이 파일 하나가 FEATURE_SPEC §6.2 의 정본 구현
static final Map&lt;Role, Set&lt;Permission&gt;&gt; ROLE_PERMISSIONS = Map.of(
    ESCORT, EnumSet.of(ROSTER_READ, STUDENT_READ_BASIC, STUDENT_READ_PHOTO,
                       BOARDING_WRITE, BOARDING_REVERT, DELAY_NOTIFY,
                       EMERGENCY_RAISE, EXCEPTION_REPORT, ROUTE_READ),
    // DRIVER 에는 BOARDING_* 와 DELAY_NOTIFY 가 부재 — 운전 중 조작 차단 (C-06 · M-05)
    DRIVER, EnumSet.of(ROSTER_READ, STUDENT_READ_BASIC, STUDENT_READ_PHOTO,
                       RUN_START, RUN_ARRIVE,
                       EMERGENCY_RAISE, EXCEPTION_REPORT, ROUTE_READ)
    // ...
);
```

```java
@PreAuthorize("hasPermission('BOARDING_WRITE')")
```

**권한을 토큰에 싣지 않는다.** JWT 에는 `role` 만 넣고 권한은 서버가 매핑에서 유도한다. 토큰에 권한 목록을 실으면 **매핑을 고쳐도 기존 토큰이 옛 권한을 그대로 들고 다닌다** — refresh 만료까지 최대 며칠. 역할 하나는 계정 상태와 함께 어차피 검증하므로 유도 비용이 없다.

**DB 권한 테이블을 쓰지 않는 이유** — 역할 6종이 사양 고정값이고 "관계자 권한 등급"은 스코프에서 제외됐다(학원당 관계자 1명, FEATURE_SPEC §7). 테이블로 빼면 운영에서 권한이 바뀌어 **사양 위반이 런타임에 허용**된다. 권한 구성을 화면에서 보여줄 필요가 생기면 읽기 전용 조회 API 로 노출하면 된다.

### 2.3 민감 데이터 — 응답 조립에서 가른다

권한이 자원에 닿는 것과 **그 자원의 전 필드를 주는 것은 다르다** (FEATURE_SPEC §6.3).

| 규칙 | 구현 |
|---|---|
| 엔티티를 직렬화하지 않는다 | **역할별 응답 DTO**. 엔티티 직렬화는 필드가 하나 늘 때마다 전 역할에 자동 노출 — 개인정보 사고의 상수 원인 |
| 마스킹은 조회 시점에 | 매니저 앱 응답 조립에서 `010-2XXX-8814` 로 변환. DB 에는 원본 보관(관계자가 봐야 함) |
| L3 조회는 감사 로그 | 누가·언제·어느 학생의 어떤 등급을 봤는지 (SYS-01 · `audit_log`) |
| 로그에 개인정보 미기록 | 식별자만 남기고 값은 DB 에서 조회 (§13.2) |

⚠ **`@JsonIgnore` 로 가리지 않는다.** 같은 엔티티가 여러 역할의 응답에 쓰이면 애너테이션 하나로는 역할별 분기가 불가능하고, 내부 호출에서는 여전히 값이 실려 다닌다.

**역할 문자열을 컨트롤러에 흩지 않는다.** 권한 판정은 위 상수를 거치고 역할↔권한 매핑을 한 파일에 모은다.

---

## 2.4 토큰 전송 — access 는 헤더, refresh 는 클라이언트별로 가른다

**결정:** access 토큰은 앱·웹 모두 `Authorization: Bearer` 헤더로 전송한다. refresh 토큰만 전달 수단을 가르며, 앱은 응답 본문 + 기기 보안 저장소, 웹은 `HttpOnly` 쿠키다. 계약은 [API_SPEC §1.2.1](./API_SPEC.md), 정책 본문은 [FEATURE_SPEC C-14](./FEATURE_SPEC.md), 채택 이유는 [PRD §6.4](./PRD.md).

### 2.4.1 HttpOnly 가 앱에서 하는 일이 없는 이유

`HttpOnly` 는 **페이지 스크립트가 `document.cookie` 로 그 쿠키를 읽지 못하게** 하는 플래그다. 즉 방어 대상은 XSS 하나다.

| 환경 | XSS 존재 | 쿠키 저장소의 주인 | HttpOnly 의 효과 |
|---|---|---|---|
| 브라우저 (관계자 웹 · 메인 관리자 콘솔) | 존재 | 브라우저 런타임 | **유효** — 스크립트가 값을 읽을 수단이 사라짐 |
| Flutter 앱 (매니저 · 학부모 · 학생) | 부재 (DOM 부재) | 앱 프로세스 자신 | **부재** — 서버가 플래그를 붙여도 `dio` 가 그대로 읽음 |

앱의 실제 위협은 XSS 가 아니라 **기기 탈취 · 루팅 · 앱 데이터 추출**이고, 대응 수단은 OS 키체인(iOS Keychain · Android Keystore)이다. 그래서 앱은 `flutter_secure_storage` 를 유지한다 (`ARCHITECTURE §2.2`).

### 2.4.2 access 까지 쿠키로 옮기지 않는 이유 셋

1. **전 엔드포인트가 CSRF 방어 대상이 된다.** refresh 만 쿠키면 방어할 경로가 `/auth` 3개이고, access 까지 쿠키면 69개 전부다.
2. **STOMP 구독 인가가 흔들린다.** WebSocket 핸드셰이크는 브라우저에서 `Authorization` 헤더를 붙일 수 없어 이 프로젝트는 CONNECT 프레임 헤더로 토큰을 넘긴다 (`ARCHITECTURE §10.2`). access 를 쿠키 전용으로 만들면 그 경로가 성립하지 않는다.
3. **앱과 웹의 인증 경로가 완전히 갈린다.** 서버가 두 벌의 인증 필터를 갖게 되고, 한쪽만 고쳐지는 결함이 생긴다.

refresh 하나만 쿠키로 내리면 **탈취 시 노출되는 것은 수명이 짧은 access 뿐**이라 실익의 대부분을 얻으면서 변경 범위는 `/auth/login` · `/auth/refresh` · `/auth/logout` 3개에 갇힌다.

### 2.4.3 CSRF 를 토큰이 아니라 `SameSite` 로 막는 이유

쿠키가 하나 생기면 CSRF 가 성립할 수 있다. **`HttpOnly` 는 읽기만 막고 사용은 막지 못하기 때문**이다 — 공격자 페이지가 값을 못 읽어도 `fetch('/api/auth/refresh', {credentials:'include'})` 를 호출하면 브라우저가 쿠키를 대신 붙인다.

대응은 셋을 겹친다.

| 수단 | 막는 것 |
|---|---|
| `SameSite=Strict` | 외부 사이트가 유발한 요청에 쿠키 미동봉 — CSRF 성립 자체를 차단 |
| CORS 허용 출처 명시 | 공격자 페이지가 응답 본문(새 access 토큰)을 읽지 못함 |
| `Path=/api/auth` | 쿠키가 붙는 요청을 3개 엔드포인트로 한정 |

**CSRF 토큰(동기화 토큰)은 도입하지 않는다.** 이 쿠키가 붙는 경로가 3개뿐이고 그중 상태를 바꾸는 것은 refresh 회전과 로그아웃이며, `SameSite=Strict` 가 이미 요청 도달 자체를 막는다. 토큰 방식을 얹으면 발급·검증·재발급 경로가 늘고 stateless 전제와 부딪힌다.

⚠ **`SameSite=Strict` 는 웹 콘솔과 API 가 같은 등록 도메인(eTLD+1)일 때만 성립한다.** 배포 구성이 `app.<도메인>` · `api.<도메인>`(`DEPLOYMENT §2.9`)이라 충족한다. 콘솔을 별도 사이트로 옮기면 이 결정이 무너지므로, 옮길 때 이 절을 먼저 재검토한다.

### 2.4.4 흔한 오해

> "쿠키로 보내면 토큰이 안전해진다."

**보관 위치를 바꾼 것이지 탈취 경로를 없앤 것이 아니다.** `HttpOnly` 단독으로는 CSRF 를 막지 못하고, `Secure` 없이는 평문 구간에서 노출되며, 서버측 무효화 수단이 없으면 유출된 refresh 를 회수할 방법이 부재하다. 이 프로젝트는 `refresh_token` 테이블(`ERD`)로 무효화 축을 이미 갖고 있고, 그 위에 §2.4.3 의 세 수단을 겹쳐야 의미가 성립한다.

---

## 3. 배치 — Spring Batch 불채택

**결정:** `@Scheduled` + `ThreadPoolTaskExecutor` + 조건부 UPDATE. Spring Batch 미사용.

**Spring Batch 는 "많은 행을 청크로 나눠 읽고-가공하고-쓰는" 도구다.** 확정 배치는 성격이 다르다.

| 기준 | Spring Batch 가 맞는 일 | 확정 배치 (RTE-02) |
|---|---|---|
| 처리 단위 | 수만~수백만 행을 청크로 | 회차 **수십 건**, 건당 독립 |
| 실행 계기 | 잡을 기동 | **회차마다 다른 시각 도래** |
| 상태 관리 | `JobRepository` 메타 테이블 6개 | `run.status` 컬럼 하나 |
| 재시작 | Step 단위 재개 | 실패 회차만 `idle` 복귀 → 다음 틱 재시도 |

넣으면 `JobRepository` 스키마 6개가 생기고, "매 30초 도래분 처리"를 표현하려면 Job 파라미터를 매번 다르게 만들어야 한다(같은 파라미터로는 재실행이 막힌다). **얻는 것 없이 개념이 두 겹 늘어난다.**

**Quartz 도 불채택** — 클러스터 모드로 분산 락을 대신할 수 있으나 스케줄러 전체를 갈아엎어야 한다. 인스턴스 1개 전제인 현 단계에 비용이 이익을 초과.

### 3.1 구성

1. `@Scheduled(fixedDelay = 30_000)` 으로 도래분 조회 (`ARCHITECTURE §9.1`)
2. 조회된 회차를 `ThreadPoolTaskExecutor` 에 던져 **동시 실행 수 제한** — 지도 API 레이트리밋에 맞춤
3. 회차 단위 `try/catch` 격리, 실패 시 `idle` 복귀
4. 전이는 조건부 UPDATE 로 멱등 (§5)

### 3.2 ShedLock — 지금 넣는다

인스턴스가 1개인 지금은 필요 없다. 그러나 2대로 늘리는 순간 미승차 판정·근접 알림 스케줄러가 **인스턴스 수만큼 중복 발송**한다. 테이블 1개 + 애너테이션 1개라 비용이 거의 없고, 나중에 넣으려면 전 스케줄러를 손봐야 한다.

```
implementation 'net.javacrumbs.shedlock:shedlock-spring'
implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template'
```

확정 배치는 §5 의 조건부 UPDATE 로 이미 안전하므로 ShedLock 이 불필요하다. **락이 필요한 것은 "여러 인스턴스가 같은 일을 중복해서 하면 곤란한" 나머지 스케줄러**다.

---

## 4. 상태 전이 — JPA 엔티티에서 다룬다

**결정:** 상태 값과 전이 규칙을 **JPA 엔티티 안**에 두고 Spring StateMachine 을 쓰지 않는다. 단 **경합이 있는 전이는 §5 로 예외 처리**한다.

### 4.1 Spring StateMachine 불채택 근거

상태는 4계열뿐이고 계열당 전이가 4~5개다 — `RunRider`(5) · `Run`(4) · `Account`(4) · `ChangeRequest`(4).

StateMachine 은 **머신 인스턴스를 만들고 상태를 복원(persister)** 하는 구조라, 엔티티마다 머신을 붙이면 JPA 와 상태 저장이 이중화된다. 가드·액션을 빈으로 등록하는 비용도 크다.

**결정적 근거는 규모가 아니라 경합 전이의 존재다.** StateMachine 은 메모리에서 전이를 판정하고 persister 가 나중에 저장하므로 **읽기 → 판단 → 쓰기가 갈라진다.** 이 시스템의 상태 절반은 여러 실행이 같은 행을 동시에 노리므로 그 창을 열 수 없다 (§5).

| 상태 계열 | 경합 | StateMachine 적용 |
|---|---|---|
| `RunRider` (승하차) | 부재 — 단일 사용자 조작 | 가능 |
| `Account` | 부재 | 가능 |
| **`Run`** (`idle`→`confirmed`→`moving`→`finished`) | **존재** — 확정 배치 · 운행 시작 · 종료 판정 | **불가** |
| **`ChangeRequest`** | **존재** — 자동 거절 폴링 | **불가** |

4계열 중 2개만 StateMachine 이 되고 나머지 2개는 조건부 UPDATE 가 된다. **상태 관리가 두 방식으로 갈리는 것이 `Map` 상수 하나보다 읽기 어렵다** — 가독성을 위해 도입했다가 가독성을 잃는 형태.

**가독성 목표는 다른 방법으로 채운다** — 전이표를 엔티티 안에 묻어두지 말고 필요하면 `RiderTransitions` 같은 전용 클래스로 분리해 전이·가드를 한 파일에 모은다. StateMachine 의 "전이가 한눈에 보인다"는 이점은 가져오고 persister 이중화는 피한다. **2026-08-24 사용자 확정.**

### 4.2 무엇이 어디에 있는가 — 셋을 가른다

"상태를 JPA 로 다룬다"는 말이 세 가지를 뭉뚱그린다. 실제로는 담당이 다르다.

| 무엇 | 어디 | 왜 |
|---|---|---|
| 상태 **값의 보관** | **DB** | 회차는 며칠 단위로 존재하고 서버 재시작을 넘어야 함. 매니저 앱·관계자 웹·관제가 **같은 값을 봐야** 하고, 상태가 곧 책임 소재 근거라 영속이 필수 |
| 전이 **규칙의 판정** | **자바 코드** (엔티티 메서드) | DB 가 판정하지 않는다. 트리거·프로시저로 막으면 규칙이 DB 에 숨어 추적·테스트가 어렵고, 위반을 애플리케이션 에러 코드로 옮기기 힘듦 |
| 전이의 **원자성** | **DB** (조건부 UPDATE, §5) | 경합이 있는 전이는 "읽고 판단하고 쓰는" 사이의 창을 코드로 막을 수 없음 |

**DB 의 CHECK 제약은 "값이 정의된 5개 중 하나인가"만 본다** — `waiting → alighted` 같은 **전이**의 허용 여부는 보지 않는다. 그것이 §4.3 의 자바 코드가 하는 일이다.

### 4.3 상태 값의 계층별 위치

| 계층 | 무엇 |
|---|---|
| DB | `varchar` + CHECK 제약 (ERD §5.2) — enum 타입 대신 varchar 를 쓰는 이유는 값 추가 시 마이그레이션이 가벼움 |
| 엔티티 | `@Enumerated(EnumType.STRING)` — **`ORDINAL` 금지** |
| 도메인 | 허용 전이 상수 + 전이 메서드 |
| 서비스 | 전이 메서드 호출 + 이벤트 발행 |

**`EnumType.ORDINAL` 을 쓰면 안 되는 이유** — 순서 번호로 저장되므로 enum 상수 사이에 값을 하나 끼워 넣는 순간 기존 데이터의 의미가 통째로 어긋난다. 이 시스템은 상태가 곧 책임 소재 근거라 되돌릴 수 없는 사고가 된다.

### 4.4 전이 메서드 패턴

```java
@Entity
public class RunRider {

    private static final Map<RiderStatus, Set<RiderStatus>> ALLOWED = Map.of(
        WAITING,  Set.of(BOARDED, NO_SHOW, ABSENT),
        BOARDED,  Set.of(ALIGHTED),
        ALIGHTED, Set.of(),   // 종결
        NO_SHOW,  Set.of(),   // 종결 (C-02)
        ABSENT,   Set.of()
    );

    @Enumerated(EnumType.STRING)
    private RiderStatus status;

    /** 동승자 처리 · 자동 전이 공통 진입점. 허용되지 않은 전이는 예외. */
    public RiderStatusChanged changeTo(RiderStatus next, ActorType actor, Long actorId, Instant at) {
        if (!ALLOWED.get(this.status).contains(next)) {
            throw new InvalidTransitionException(this.status, next);
        }
        RiderStatus from = this.status;
        this.status = next;
        return new RiderStatusChanged(this.id, from, next, actor, actorId, at);
    }
}
```

**얻는 것 셋.**

1. **전이 규칙이 한 화면에 다 보인다.** `no_show → boarded` 같은 사양 위반이 첫 테스트에서 걸린다.
2. **진입점이 하나**라 이력(`rider_status_history`)을 빠뜨릴 수 없다. 전이 메서드가 이력 레코드를 반환하고 서비스가 저장한다.
3. **자동 전이도 같은 문을 통과한다** — 하원 시작 시 전원 `boarded`, 등원 종료 시 전원 `alighted` (C-07). `ActorType.SYSTEM` 으로 구분해 이력에 남긴다 (ERD `rider_status_history.actor_type`).

### 4.5 서비스 계층에서의 흐름

```java
@Transactional
public BoardingResult record(Long runId, Long riderId, RiderStatus next, String clientKey) {
    // 1) 멱등 — 같은 client_key 재수신이면 최초 결과 반환 (BRD-06)
    var seen = historyRepository.findByClientKey(clientKey);
    if (seen.isPresent()) return BoardingResult.from(seen.get());

    // 2) 전이 — 규칙 위반이면 여기서 예외
    var rider = runRiderRepository.findByIdAndRunId(riderId, runId).orElseThrow();
    var changed = rider.changeTo(next, ActorType.ESCORT, currentAccountId(), clock.instant());

    // 3) 이력 저장
    historyRepository.save(RiderStatusHistory.of(changed, clientKey));

    // 4) 이벤트 — 커밋 이후에 알림이 나간다 (§7)
    events.publishEvent(changed);

    return BoardingResult.from(changed);
}
```

**계층 책임** — `controller` 는 DTO 변환과 인가만, `command` 는 트랜잭션 경계와 이벤트 발행, **전이 판단은 엔티티**, `repository` 는 영속화. 전이 조건을 서비스에 적으면 같은 규칙이 호출 지점마다 복제된다.

### 4.6 흔한 오해

> "상태가 있으니 Spring StateMachine 을 써야 한다"

상태의 **개수**가 아니라 **전이 그래프의 복잡도**가 기준이다. 분기 조건이 많고 서브머신·병렬 상태가 필요하면 라이브러리가 이긴다. 여기처럼 전이가 선형에 가깝고 종결 상태가 명확하면 `Map` 상수 하나가 더 읽힌다.

> "엔티티에 로직을 넣으면 안 된다"

전이 규칙은 **데이터의 불변식**이지 업무 절차가 아니다. 엔티티 밖에 두면 그 엔티티를 다루는 모든 코드가 규칙을 알아야 한다.

---

## 5. 경합 전이 — JPA 로 만들 수 없는 지점

**결정:** 확정 배치·자동 거절·종료 판정처럼 **여러 실행이 같은 행을 노리는 전이**는 JPA 의 **`@Modifying` 벌크 연산**으로 처리한다. 변경 감지(dirty checking)를 쓰지 않는다.

⚠ **"JPA 를 벗어난다"는 뜻이 아니다.** `@Modifying @Query` 는 Spring Data JPA 기능이고 JPQL 로 작성한다. 갈리는 것은 **JPA 안에서 변경 감지를 쓸 것인가 벌크 연산을 쓸 것인가**이며, 경합이 있는 전이는 후자라야 한다.

§4.4 의 전이 메서드는 **읽기 → 판단 → 쓰기** 로 갈라진다. 단일 사용자 조작(승하차 처리)은 그래도 되지만, 배치는 두 실행이 동시에 `idle` 을 읽고 둘 다 확정하는 창이 생긴다.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
    update Run r
       set r.status = :confirmed, r.confirmedAt = :now
     where r.id = :runId and r.status = :idle
    """)
int markConfirmed(@Param("runId") Long runId,
                  @Param("idle") RunStatus idle,
                  @Param("confirmed") RunStatus confirmed,
                  @Param("now") Instant now);
// 반환값 1 = 선점 성공, 0 = 다른 실행이 이미 처리 → 건너뜀
```

**애플리케이션 락·인메모리 플래그에 의존하지 않는 이유** — 인스턴스를 늘리는 순간 같은 회차가 두 번 확정된다. DB 가 단일 진입을 보장하면 증설이 조건부 UPDATE 하나로 성립한다 (`ARCHITECTURE §9.3`).

**조건부 UPDATE 를 쓰는 곳 4개**

| 전이 | 조건 |
|---|---|
| 확정 배치 | `status='idle'` → `confirmed` |
| ②구간 자동 거절 | `status='pending'` → `auto_rejected` |
| 운행 시작 | `status='confirmed'` → `moving` (±3분 창 검사와 함께) |
| 종료 판정 | `status='moving' AND finish_pending` → `finished` |

**낙관적 락(`@Version`) 은 노선 배포 버전에 쓴다** — 두 관리자가 같은 회차에 동시에 배포를 시도하는 경우. 조건부 UPDATE 는 "선점 실패 시 건너뜀"이고 `@Version` 은 "충돌 시 예외 후 재시도"라 쓰임이 다르다.

⚠ **벌크 연산은 영속성 컨텍스트를 우회한다.** 같은 트랜잭션에서 그 엔티티를 다시 읽으면 1차 캐시의 옛 값이 나온다. 위 예시처럼 `clearAutomatically = true, flushAutomatically = true` 를 붙이거나, UPDATE 이후 그 트랜잭션에서 해당 엔티티를 읽지 않는다. **이 함정이 JPA 벌크 연산의 유일한 대가**이며 그래도 변경 감지보다 안전하다.

---

## 6. 시각 — `Clock` 주입

**결정:** `java.time.Clock` 을 빈으로 등록하고 시간이 필요한 모든 곳이 주입받는다. `LocalDateTime.now()` · `Instant.now()` 직접 호출 금지.

이 시스템은 **시각이 상태를 만든다** (`ARCHITECTURE §1.3`). 검증해야 할 규칙이 전부 시각 의존이다 — 출발 30분 전 도래, ±3분 창, 두 시계 분리, ②구간 마감, 3분 카운트다운.

```java
@Bean
Clock clock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
```

```java
// 테스트 — "07:32 요청을 배치가 07:35에 처리해도 판정은 07:30 시계"를 고정
Clock at0735 = Clock.fixed(Instant.parse("2026-08-24T07:35:00Z"), SEOUL);
```

**나중에 바꾸려면 시간 쓰는 곳을 전수 수정해야 하므로 처음부터 넣는다.** 이 항목만은 "나중에"가 성립하지 않는다.

---

### 6.1 시각 타입 — `OffsetDateTime`

**결정:** 엔티티의 시각 필드는 전부 `OffsetDateTime`. `LocalDateTime` 을 쓰지 않는다.

`ERD §2` 가 전 시각 컬럼을 `timestamptz` 로 규정하고 기준 시간대를 `Asia/Seoul` 로 둔다. `LocalDateTime` 은 **오프셋을 버리는 타입**이라 `timestamptz` 와 왕복하면 값이 조용히 이동한다 — DB 는 UTC 로 저장하고 돌려줄 때 세션 시간대를 적용하는데, `LocalDateTime` 은 그 시간대 정보를 담을 자리가 없어 "몇 시인지" 만 남고 "어디 기준인지" 가 사라진다.

이 시스템에서 특히 위험한 이유는 **판정의 축이 시각**이기 때문이다. 확정 배치(출발 30분 전 도래) · 운행 시작 창 · ②구간 마감이 전부 시각 비교이고, 오프셋이 한 번 어긋나면 **비교 결과만 틀리고 예외는 발생하지 않는다.**

현재 `global/common/BaseTimeEntity` 가 `LocalDateTime` 을 쓰고 있고 **39개 엔티티가 이 클래스를 상속**하므로, Phase 1 이 엔티티를 만들기 전에 교체한다.

`Instant` 를 쓰지 않는 이유 — 저장·비교에는 충분하나 로그·응답에서 사람이 읽을 때 매번 시간대를 얹어야 한다. `Clock` 주입(§6)과의 결합도 `OffsetDateTime.now(clock)` 로 동일하다.

### 6.2 enum 표기 — DB·wire 는 소문자 snake_case, Java 상수는 대문자

**결정:** enum 값의 DB 저장 문자열과 API 응답 문자열은 **소문자 snake_case** 로 통일한다(`API_SPEC §9`). Java enum 상수는 언어 관례대로 **대문자 SNAKE_CASE** 를 유지하고, 둘은 `name().toLowerCase()` 관계로 잇는다.

```
Java 상수        DB·wire 값
SYSTEM_ADMIN  ↔  system_admin
CONFIRMED     ↔  confirmed
PENDING       ↔  pending
```

**두 층의 관례가 다른 것이 정상이다.** 대문자는 "Java 에서 이것이 상수"라는 표시이고, 소문자 snake_case 는 PostgreSQL 의 식별자 관례(따옴표 없는 식별자를 소문자로 접음)와 이 API 의 필드 명명(`academy_id` · `depart_time`)에 맞춘 것이다. 한쪽을 다른 쪽에 맞추면 **그 층의 관례가 깨진다.**

**2026-08-25 이전에는 `account.status` 하나만 대문자(`PENDING`)였다.** 33개 enum 중 32개가 소문자인데 하나만 어긋난 상태라, `@Enumerated(STRING)`(상수 이름을 그대로 저장)을 쓰면 **32개가 깨지고 그 하나만 우연히 맞는** 역방향 함정이 있었다. 사양 문서 8종의 118곳을 소문자로 통일해 해소했다.

#### 연결 수단

| 층 | 방법 |
|---|---|
| **JPA** | `CodedEnum` 을 받는 추상 `AttributeConverter` 하나 + enum 마다 `@Converter(autoApply = true)` 하위 클래스 3줄. 필드마다 애너테이션을 달지 않음 |
| **Jackson (입력)** | `ACCEPT_CASE_INSENSITIVE_ENUMS` 로 역직렬화 |
| **Jackson (출력)** | 전역 직렬화 모듈 하나로 `name().toLowerCase()` 를 내보냄. enum 마다 `@JsonValue` 를 다는 방식은 33곳에 같은 코드가 복제됨 |

**`@Enumerated(STRING)` 을 쓰지 않는 이유** — 상수 이름과 저장 문자열이 같아야만 동작하는데, 위 결정은 둘을 의도적으로 다르게 둔다. 변환을 한 곳에 모아두면 표기 규칙이 바뀔 때 고칠 곳이 컨버터 하나다.

#### 되돌아가지 않게 하는 장치

**enum 의 값 집합과 `ERD` 의 CHECK 허용값이 일치하는지 테스트로 고정한다.** 값 하나가 어긋나도 그 값이 실제로 쓰이는 순간까지 드러나지 않고, 그 순간은 대개 운영이다. Phase 1 의 스키마 대조 테스트에 포함한다.

---

## 7. 이벤트 — 트랜잭셔널 아웃박스

**결정:** 유실이 곤란한 통지는 **상태 변경과 같은 트랜잭션에서 DB 에 적재**하고, 발송은 커밋 후 즉시 시도 + 워커 재시도 2단으로 한다. `@TransactionalEventListener(AFTER_COMMIT)` **단독은 재시도 보장이 없어** 쓰지 않는다.

### 7.1 `AFTER_COMMIT` 단독의 한계

흔한 패턴은 이렇다.

```
트랜잭션 [상태 변경 → 이벤트 발행] → 커밋 → AFTER_COMMIT 리스너 → 푸시 발송
```

**커밋과 리스너 실행 사이에 앱이 죽으면 그 알림은 영원히 사라진다.** 이벤트가 메모리에만 있었으므로 "보내야 했다"는 사실 자체가 남지 않는다. 재시도할 대상을 특정할 수 없다.

Kafka 에 트랜잭션 안에서 발행하는 것도 답이 아니다 — **DB 커밋과 Kafka 발행은 원자적이지 않다.** DB 는 커밋됐는데 Kafka 발행이 실패하거나 그 반대가 되는 창이 그대로 남는다.

### 7.2 아웃박스 — `notification_log` 가 곧 아웃박스

이 프로젝트는 **별도 아웃박스 테이블이 불필요**하다. 사양이 이미 "알림 레코드는 설정 off 여도 항상 생성"(C-09 · NTF-07)을 요구하므로 `notification_log` 가 그 역할을 겸한다.

```
트랜잭션 [ 상태 변경  +  notification_log INSERT (push_state='pending') ]
              ↓ 커밋
        ① 즉시 발송 시도 (AFTER_COMMIT)  → 성공 시 push_state='sent'
              ↓ 실패·앱 크래시
        ② 아웃박스 워커 폴링             → pending 회수 후 재시도
```

| 단계 | 역할 |
|---|---|
| 트랜잭션 안 | `push_state='pending'` 행 INSERT. **상태 변경과 원자적** — 둘 다 커밋되거나 둘 다 롤백 |
| ① 즉시 발송 | 정상 경로. 커밋 직후 발송해 **5초 이내 반영**(NFR-02)을 달성 |
| ② 워커 폴링 | 안전망. `push_state='pending' AND last_attempt_at < now()-백오프` 조회 후 재시도 |
| 재시도 상한 | 초과 시 `push_state='failed'` + 경보. 레코드는 남으므로 사후 추적 가능 |

**①이 없으면 지연되고 ②가 없으면 유실된다.** 둘 다 있어야 "빠르고 안 잃는다"가 성립.

**중복은 DB 가 막는다** — `notification_log.dedup_key` UNIQUE. ①과 ②가 같은 건을 동시에 집어도 한쪽만 성공한다.

### 7.3 아웃박스를 쓰지 않는 이벤트

전부에 아웃박스를 씌우면 DB 쓰기가 불필요하게 늘어난다. **유실돼도 다음 신호가 곧 오는 것**은 즉시 발행으로 충분하다.

| 대상 | 방식 | 근거 |
|---|---|---|
| 승하차·미승차·승인 결과·노선 재배포 통지 | **아웃박스** | 유실이 곧 책임 소재 소멸 |
| **비상 알림**(EXC-04) | **아웃박스 적재 + 워커 대기 없이 즉시 발송** | 유실이 곧 사고 대응 실패이므로 적재는 필수. 다만 지연도 곤란해 커밋 직후 발송하고 재시도 간격을 다른 알림보다 짧게 (C-17) |
| 관제 WebSocket `position` | 즉시 발행 | 5~10초 뒤 다음 좌표가 옴 |
| 대시보드 실시간 갱신 | 즉시 발행 | 화면 재조회로 복구 |

### 7.4 Kafka 의 위치

아웃박스가 발송 보장을 담당하므로 **Kafka 의 역할이 더 좁아진다.** 모놀리식 + 인스턴스 1개에서 모듈 간 통신은 `ApplicationEventPublisher` 로 충분하고, Kafka 는 **서비스를 실제로 분리할 때** 또는 **관제 팬아웃이 인스턴스 경계를 넘어야 할 때** 도입한다 (`ARCHITECTURE §9.5`).

⚠ **Kafka 를 재시도 수단으로 오해하지 않는다.** Kafka 의 재시도는 "브로커에 들어간 뒤"를 보장할 뿐, **브로커에 넣기 전에 앱이 죽는 창**은 아웃박스만 막는다.

### 7.5 트랜잭션 안에서 하지 않는 것

- **외부 푸시 발송** — 실패가 상태 변경을 롤백시키면 안 됨. 승하차 기록이 푸시 실패로 사라지는 상황 (BRD-04)
- **외부 지도 API 호출** — 트랜잭션이 외부 응답 시간만큼 열려 있게 됨
- **WebSocket 발행** — 커밋 전 상태가 클라이언트에 노출

---

## 8. 외부 지도 API — Resilience4j

**결정:** 노선 계산 ③단계(도로 경로)의 외부 호출을 Resilience4j 로 감싼다.

보호 장치가 없으면 지도 API 장애가 곧 배치 전체 정지다.

| 기능 | 왜 |
|---|---|
| `TimeLimiter` | **호출자별로 다른 타임아웃** — 온디맨드(승인 화면)는 짧게, 배치는 길게 (`ARCHITECTURE §8.3`) |
| `CircuitBreaker` | 지도 API 가 죽었을 때 매 회차마다 타임아웃을 기다리지 않도록 |
| `Bulkhead` | **동시 호출 수 제한** — 동시 도래 폭주 시 레이트리밋 초과 방지 (`ARCHITECTURE §9.4`) |
| `Retry` | 일시 오류만. 서킷과 함께 |

**폴백은 직선거리 근사**로 두고, 폴백으로 계산된 노선은 그 사실을 화면에 표시한다.

**캐시** — Redis 가 이미 있으므로 `spring-boot-starter-cache` 하나만 추가하면 `@Cacheable` 로 경로 계산 결과를 재사용할 수 있다. 승인 미리보기의 재계산 억제에 직접 쓰인다.

---

## 8.5 노선 계산의 재현성과 품질 회귀 판정

**결정:** 계산 결과에 **어떤 조건으로 산출했는지**를 함께 기록하고, 알고리즘 교체 시 **숫자로 우열을 판정**하는 고정 데이터셋을 둔다.

**필요한 이유** — `ARCHITECTURE §14 R5` 가 "최적화 알고리즘 기준 미확정 … 전략 포트로 격리했으나 **기준이 정해질 때까지 품질 회귀를 판정할 수단이 부재**"로 남긴 공백이다. 전략을 교체 가능하게 만들어 놓고 판정 수단이 없으면, 바꾼 뒤 나빠졌는지 알 수 없다.

### 8.5.1 계산 스냅샷 — 배포 버전에 함께 기록

`route_version` 에 아래를 남긴다 (ERD §3.3).

| 컬럼 | 값 예시 | 용도 |
|---|---|---|
| `engine_name` | `heuristic` | 어떤 순서 최적화 전략의 산출물인지 |
| `policy_snapshot` | `{"maxWaypoints":25,"fallback":"haversine"}` | **당시 정책값**. 정책이 바뀌어도 과거 결과를 설명 가능 |
| `trigger` | `confirm_batch` · `approval` · `waypoint` · `transfer` | 누가 촉발했는지 (`source` 컬럼과 동일 축) |
| `fallback_used` | boolean | 지도 API 폴백(직선거리 근사)으로 계산됐는지 (§8) |

**정책값을 스냅샷으로 굳히는 이유** — 정책은 바뀌는데 과거 노선은 남는다. 값을 참조로만 두면 "왜 이 순서로 돌았나"를 나중에 재현할 수 없다.

### 8.5.2 품질 회귀 판정

알고리즘 교체가 상시화되므로 **"새 것이 나은가"를 숫자로 판정**한다.

| 항목 | 내용 |
|---|---|
| 고정 데이터셋 | 좌표 세트 **3종** — 도심 밀집 · 교외 분산 · 혼합. 테스트 리소스로 보유 |
| 지표 | 총 주행거리 · **최대 학생 탑승시간** · 정차 수 |
| 판정 | 기존 결과 대비 상한 기준 회귀 테스트. "지그재그보다 나쁘지 않음" 수준의 하한 테스트도 유지 |
| 외부 의존 | **지도 API 미호출** — Haversine 기준으로 구성해 비용·레이트리밋·불안정성을 회피 |

**최대 학생 탑승시간을 지표에 넣는 이유** — 총 주행거리만 보면 한 학생을 오래 태우는 해가 이길 수 있다. 이 서비스에서 나빠지면 곤란한 것은 거리가 아니라 **아이가 버스에 앉아 있는 시간**이다.

> 출처 — 폐기된 `docs/superpowers/specs/2026-08-13-노선-계산-확장구조-design.md` 의 D-8·§12. 그 문서의 나머지 결정(컷오프 6시간 · 공통 정차지 병합 · 배정 단계)은 현 사양과 어긋나 폐기했고, **대체본이 없던 이 두 조각만 옮김.**

---

## 9. 조회 — 전 구간 JPA

**결정:** 데이터 접근을 **전부 JPA 로 통일**한다. `JdbcClient` · QueryDSL · MyBatis 를 섞지 않는다.

기술을 섞으면 같은 테이블을 두 가지 방식으로 다루게 되고, 매핑·트랜잭션·테스트가 두 벌이 된다. 초기 단계에서는 **하나로 통일한 단순함이 부분 최적화보다 이득**이다.

### 9.1 JPA 로 처리하는 방법

무거운 조회에서 JPA 가 문제가 되는 것은 **연관을 순회할 때**이지 JPA 자체가 아니다. 아래 셋으로 해결한다.

| 상황 | 방법 |
|---|---|
| 응답 모양이 엔티티와 다름 (관제 · 대시보드) | **DTO 프로젝션** — `select new com.x.RunSummary(r.id, b.busNo, count(rr))` 또는 인터페이스 프로젝션. 엔티티를 통째로 로딩하지 않음 |
| 연관을 함께 써야 함 (호차별 명단) | **fetch join** 또는 `@EntityGraph` — N+1 제거 |
| 목록 + 카운트 | `Page` / `Slice`. 실시간 관제·운행 명단은 페이징 미적용(전량 반환, API_SPEC §1.8) |
| 학원 격리 | **저장소 메서드 시그니처에 `academyId` 를 필수 인자로** 둬 조건 누락을 컴파일 단계에서 막음 |

### 9.2 upsert · 멱등

`ON CONFLICT DO NOTHING` 은 JPA 표준으로 표현 불가하므로 **UNIQUE 제약 + 예외 처리**로 대체한다.

```java
try {
    historyRepository.save(RiderStatusHistory.of(changed, clientKey));
} catch (DataIntegrityViolationException e) {
    // client_key UNIQUE 위반 = 이미 처리된 요청 → 최초 결과 반환 (BRD-06)
    return BoardingResult.from(historyRepository.findByClientKey(clientKey).orElseThrow());
}
```

**멱등 보장은 DB 제약이 하고 JPA 는 그 위반을 예외로 받는다.** 조회 후 저장하는 방식(`exists` → `save`)은 두 요청이 동시에 통과하는 창이 남으므로 쓰지 않는다.

### 9.3 파티셔닝은 JPA 와 무관

`run_position` · `notification_log` · `audit_log` 의 PostgreSQL 선언적 파티셔닝(ERD §7.3)은 **DDL 수준**이라 JPA 코드에 영향이 없다. 애플리케이션은 부모 테이블만 보고, 정리는 파티션 `DROP` 으로 한다.

### 9.4 되짚어볼 지점

전 구간 JPA 의 대가를 명시해 둔다 — **전 학원 관제 조회가 커지면 튜닝 여지가 좁다.** 프로젝션·fetch join 으로 안 되는 수준(윈도 함수·재귀 CTE·복잡한 집계)이 필요해지면 그때 §15 에 따라 재검토한다. 지금은 학원 수가 적어 문제가 되지 않는다.

---

## 10. 테스트

| 대상 | 도구 |
|---|---|
| DB · Redis · Kafka | **Testcontainers** — H2 는 PostgreSQL 방언·부분 인덱스·제약 위반 예외 코드를 재현하지 못한다. §9.2 의 멱등이 예외 처리에 의존하므로 실제 PostgreSQL 이 필요 |
| 스케줄러 · 비동기 | Awaitility (이미 있음) |
| 시각 의존 규칙 | `Clock.fixed` (§6) |
| 인가 | `spring-security-test` (이미 있음) |

**반드시 테스트로 고정할 것 넷** — ① 상태 전이표 전체(§4) ② 3구간 판정 경계값(30분 전 ±1초) ③ 두 시계 분리(배치 지연 시 마감 불변) ④ 조건부 UPDATE 의 동시 실행(두 스레드가 같은 회차를 노릴 때 한쪽만 성공).

---

## 11. 추가할 의존성

```
// 스케줄 분산 락 (증설 대비)
net.javacrumbs.shedlock:shedlock-spring
net.javacrumbs.shedlock:shedlock-provider-jdbc-template
// 외부 API 보호
io.github.resilience4j:resilience4j-spring-boot3
// 캐시 (Redis 는 이미 있음)
org.springframework.boot:spring-boot-starter-cache
// 통합 테스트
org.testcontainers:testcontainers-postgresql · org.testcontainers:testcontainers-junit-jupiter
//   ⚠ Boot 4.1.0 이 관리하는 testcontainers-bom:2.0.5 의 개명된 좌표. 구 좌표(postgresql · junit-jupiter)는 이 BOM 에 부재
org.springframework.boot:spring-boot-testcontainers
```

이미 있는 것 — Security · JPA · Flyway · Redis · Kafka · WebSocket · WebClient · Validation · Actuator · Micrometer · AspectJ · Awaitility.

**아웃박스(§7)에는 추가 의존성이 부재** — `notification_log` 테이블과 `@Scheduled` 워커로 성립. 라이브러리(Debezium · Spring Modulith 이벤트 발행 로그)를 넣지 않는 이유는 발송 대상이 알림 한 종류이고 이미 레코드를 남기도록 사양이 요구하기 때문.

---

## 12. 운영

**결정:** 인스턴스 1개 · Docker Compose · EC2 1대를 유지하되, **등하원 시간대에는 배포하지 않는다.**

### 12.1 배포 창

| 구분 | 규칙 |
|---|---|
| 금지 창 | **운행 중인 회차가 존재하는 시간대** — `run.status='moving'` 이 하나라도 있으면 배포 보류 |
| 준금지 창 | 확정 배치가 도는 시각(가장 이른 출발 −30분 ~ 마지막 출발) |
| 권장 창 | 전 회차 `finished` 이후 심야 |

인스턴스가 1개라 재시작이 곧 짧은 중단이다. 그 중단이 **운행 중**에 겹치면 위치 송신이 끊기고 관제 화면에서 버스가 사라진다. 배포 파이프라인에 **`moving` 회차 존재 여부를 확인하는 게이트**를 둔다 — 사람의 기억에 맡기지 않는다.

무중단 배포는 인스턴스 증설과 함께 검토한다 (`ARCHITECTURE §9.5`). 지금 blue-green 을 넣으면 배치 이중 실행·WS 세션 분산을 동시에 풀어야 한다.

### 12.2 설정의 위치

| 값 | 위치 | 근거 |
|---|---|---|
| 학원별 임계값 (미승차 대기 **3분**) | **DB** (`academy_setting`) | 학원마다 다름 (EXC-01) |
| 전역 정책 상수 (30분 · ±3분 · 14일 · 5회) | `application.yml` 아닌 **코드 상수** | 사양이 고정한 값이라 환경별로 달라지면 안 됨. yml 로 빼면 운영에서 조용히 바뀔 수 있음 |
| 폴링 주기 · 워커 수 · 타임아웃 | `application.yml` | 부하에 따라 조정하는 값 |
| 시크릿 (DB 비밀번호 · 지도 API 키 · 푸시 인증서) | **SSM Parameter Store** | 이미지·저장소에 넣지 않음 |

⚠ **주기와 임계값을 가른다.** yml 에 두는 것은 "얼마나 자주 검사하는가"이고, "언제 발동하는가"는 사양이 정한 값이다. 이전 코드는 미승차 임계값이 코드 상수 10분이었고 학원별 설정이 불가능했다.

### 12.3 백업

| 데이터 | 정책 |
|---|---|
| 승하차 이력 · 감사 로그 | **유실 불가** — 책임 소재의 근거. PITR 활성화 |
| 노선 · 명단 | 일 단위 스냅샷으로 충분 — 재계산 가능 |
| 위치 이력 | 백업 대상 밖 — 대량이고 사후 가치가 낮음 |

복구 훈련을 한 번은 한다. **백업이 있다는 사실과 복구가 된다는 사실은 다르다.**

---

## 13. 관측

**결정:** Micrometer + Prometheus 기반. **확정 배치 지연 하나가 이 시스템의 대표 지표**다.

### 13.1 반드시 둘 지표

| 지표 | 왜 |
|---|---|
| **확정 배치 도래→완료 지연** | §3 의 밀림 누적을 감지하는 **유일한 신호**. 이 값이 늘면 워커·인스턴스를 늘려야 함 |
| **미확정 회차 수** | `confirm_at` 이 지났는데 `status='idle'` 인 회차. **0이 아니면 곧 운행 사고** |
| 배치 실패·재시도 건수 | 회차 단위로 격리되므로 실패가 로그에만 남고 응답에 미노출 |
| 지도 API 응답 시간 · 실패율 · 서킷 상태 | 계산 지연의 주 원인 |
| 알림 발송 실패 건수 | 레코드는 남고 푸시만 실패하는 경우 |
| WebSocket 연결 수 · 발행 지연 | 5초 반영(NFR-02) 달성 여부 |
| ②구간 **자동 거절** 건수 | 시스템 결함이 아니라 **운영 품질** 지표 — 관계자가 승인 화면을 안 보고 있다는 신호 (X-02) |
| 미승차 에스컬레이션 건수 | 안전 사안. 급증하면 노선·시각 편성 문제 |

**앞의 둘은 성격이 다르다** — 나머지는 "느려지고 있다"를 알리지만, `미확정 회차 수`는 **이미 사고**다. 기사가 노선을 못 받은 상태다.

### 13.2 로그

- **구조화 로그(JSON)** + `X-Request-Id` 상관 식별자 (API_SPEC §1.3). 배치는 요청이 없으므로 **회차 ID 를 상관 키**로 쓴다.
- **개인정보를 로그에 남기지 않는다** — 보호자 전화번호·학생 사진 URL·주소. 식별자만 남기고 값은 DB 에서 조회.
- 상태 전이는 로그가 아니라 **테이블**(`rider_status_history` · `audit_log`)에 남긴다. 로그는 보존 기간이 짧고 검색이 불확실해 책임 소재 근거가 되지 못한다.

### 13.3 추적

`Micrometer Tracing` + OTel 은 **선택**. 넣는다면 대상은 하나 — **배치 1회차 처리의 스팬**(도래 감지 → 입력 수집 → 계산 → 지도 API → 배포). 계산 지연이 어느 단계에서 나는지는 지표만으로 안 갈린다.

### 13.4 알럿

| 조건 | 등급 | 근거 |
|---|---|---|
| `confirm_at + 5분` 경과인데 `idle` 인 회차 ≥ 1 | **즉시** | 기사가 노선을 못 받음 → 운행 불가 |
| 미승차 에스컬레이션이 **3분 초과** 미보고 | **즉시** | 안전 (EXC-01) |
| 운행 중 회차의 위치가 **2분 이상** 미수신 | 경고 | 관제 불가 (P-07 의 "마지막 확인 위치"로 저하) |
| 지도 API 서킷 open | 경고 | 폴백으로 계산 중 — 품질 저하 |
| 알림 발송 실패율 임계 초과 | 경고 | |
| 배치 지연 p95 임계 초과 | 정보 | 증설 검토 신호 |

**알럿을 등급으로 가르는 기준은 "지금 아이가 위험한가"** 다. 시스템 지표가 아니라 그 지표가 뜻하는 현실 상황으로 판단한다.

---

## 14. 장애 대응

**결정:** 실패 모드마다 **저하 동작(degradation)** 을 미리 정의하고, 전면 정지는 DB 장애 하나로 한정한다.

### 14.1 이 시스템의 특수성

**버스는 이미 도로 위에 있다.** 서버가 죽어도 운행은 멈추지 않고, 그동안의 승하차는 어딘가에 기록돼야 한다. 그래서 **매니저 앱의 오프라인 큐(BRD-06)가 최후의 안전장치**다 — 통신이 끊겨도 명단 조회와 승하차 처리가 되고, 복구 시 `client_key` 로 멱등 동기화된다.

이 하나가 다른 모든 장애 대응의 전제다. 오프라인 큐가 없으면 서버 장애 = 승하차 기록 소실 = 책임 소재 소멸이 된다.

### 14.2 실패 모드별 대응

| 실패 | 영향 | 저하 동작 | 복구 |
|---|---|---|---|
| **지도 API 장애** | 노선 계산 ③단계 | **직선거리 근사로 폴백** + 그 사실을 화면에 표시. 배치는 계속 진행 | 서킷 닫히면 다음 회차부터 정상. 이미 배포된 노선은 재계산하지 않음 |
| **Redis 장애** | 최신 좌표 · 캐시 | 위치는 **DB 이력의 최신 행**으로 대체 조회(느리지만 동작). 캐시 미스로 계산 반복 | 자동 |
| **Kafka 장애** | 알림 팬아웃 지연 | 상태 변경은 **DB 에 정상 기록**되고 알림만 지연. 앱 알림 목록은 REST 조회라 영향 부재 | 아웃박스 워커가 `pending` 을 회수해 재발송 (§7.2) |
| **DB 장애** | **전면** | 매니저 앱은 오프라인 큐로 승하차만 지속. 나머지 제품은 조회 불가 | 복구 후 큐 동기화 (멱등) |
| **앱 인스턴스 재시작** | WS 끊김 · 배치 중단 | 클라이언트 자동 재연결. 배치는 **폴링이라 놓친 회차를 다음 틱에 자동 회수** | 자동 — 별도 절차 부재 |
| **배치 밀림** | 확정 지연 | 도래분이 다음 틱으로 이월. 지연 지표 상승 | 워커 수 증설, 그다음 인스턴스 (§3) |
| **확정 실패한 회차** | 그 회차만 노선 부재 | `idle` 복귀 후 재시도. 연속 실패 시 **관계자에 경보** | 수동 개입 (§14.3) |

**전면 정지는 DB 장애 하나뿐**이라는 점이 설계의 목표다. 나머지는 전부 "느려지거나 품질이 떨어지되 운행은 계속"으로 떨어진다.

### 14.3 수동 개입 경로 (런북)

자동 복구가 안 되는 상황을 위해 **메인 관리자 콘솔에 개입 수단**을 둔다. 운영자가 DB 를 직접 만지면 허용되지 않은 전이가 통과하고 `rider_status_history` 진입점을 우회해 이력이 미기록.

| 상황 | 개입 | 남길 것 |
|---|---|---|
| 확정이 계속 실패하는 회차 | **강제 확정** — 폴백 계산으로 배포 | 누가·언제·왜 · 폴백 여부 |
| 자동 거절이 잘못 나감 | 되돌리기 부재 — **관계자가 ①구간 경로로 다시 처리**하도록 안내 | 자동 거절 이력은 그대로 |
| 하원 종료 보류가 안 풀림 | 미하차 학생 목록 확인 후 **동승자에게 처리 요청**. 강제 종료는 두지 않음 | — |
| 운행 시작 ±3분 창을 놓침 | **미결 (X-01)** — 지연 사유 입력 허용 vs 관계자 원격 해제 |  |

⚠ **"강제 종료"를 만들지 않는다.** 미하차 상태로 회차를 끝낼 수 있는 경로가 생기는 순간 `RUN-06`(잔류 학생 방지)이 우회 가능해진다. 종료가 안 풀리는 것은 결함이 아니라 **아직 안 내린 아이가 있다는 신호**다.

### 14.4 복구 시 멱등

복구 절차가 중복 처리를 만들지 않아야 한다. 이미 준비된 장치 셋.

1. **오프라인 큐** — `client_key` UNIQUE 로 재전송 중복 무시 (API_SPEC §1.7)
2. **배치** — 조건부 UPDATE 로 이미 확정된 회차는 건너뜀 (§5)
3. **알림** — `notification_log.dedup_key` UNIQUE 로 재발행 중복 차단 (§7)

**복구 스크립트를 새로 쓰지 않는다.** 정상 경로가 이미 멱등하면 복구는 "다시 돌리기"로 끝난다.

---

## 15. 재검토 시점

| 결정 | 뒤집힐 조건 |
|---|---|
| Spring Batch 불채택 | 통계·정산처럼 **수십만 행을 가공하는 잡**이 생길 때 |
| StateMachine 불채택 | 전이에 서브머신·병렬 상태·복잡한 가드가 필요해질 때 |
| Kafka 범위 축소 | 서비스를 실제로 분리할 때 |
| 인스턴스 1개 전제 | 확정 배치 도래→완료 지연이 임계를 넘을 때 (`ARCHITECTURE §9.4`) |
| **전 구간 JPA** | 관제 조회가 프로젝션·fetch join 으로 감당되지 않을 때 — 그 시점에 해당 조회만 `JdbcClient` 로 분리 (§9.4) |
| QueryDSL 미도입 | 동적 조건 조합 화면이 늘어날 때 |
