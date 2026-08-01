# 학원 통학버스 통합관리 — API 명세서

이 문서는 **프론트(Flutter) ↔ 백엔드(Spring Boot) 계약서**이자, Claude·개발자가 매 세션 참조하는 **Markdown 단일 소스(SoT)** 다.
서술은 전부 **소스 코드 실측**에 기반한다. 코드와 문서가 다르면 코드가 맞다 — 그 경우 이 문서를 고친다.

## 표기 규칙

| 표기 | 의미 |
|---|---|
| ✅프론트 사용 | 현재 Flutter 앱이 실제로 호출한다 |
| ⬜프론트 미사용 | 백엔드에만 존재한다(선행 구현분) |
| ✅ 구현 | 요청→DB/외부연동→응답까지 실제 로직이 동작한다 |
| ⚠ 부분 | 동작하지만 고정값·누락된 후속 처리가 있다(각 항목의 **주의** 참조) |
| `검증 없음 ⚠` | 테넌트(학원) 격리 검증이 서버에 없다 |
| ※ 미확인 | 이번 실측 범위에서 확인하지 못한 사항 |

- 필수 표기 `✔` 는 서버가 **거부**하는 경우만 붙인다(`@NotNull`·`@NotBlank` 등). 애너테이션이 없으면 생략 가능하다.
- 모든 요청·응답 본문은 `application/json`(UTF-8).

## 자매 문서

| 문서 | 역할 |
|---|---|
| `backend/docs/PROJECT_MASTER_PLAN.md` | 기획 요구사항 + 모듈별 진행 추적(단일 소스) |
| `backend/docs/reference.md` | 백엔드 코드 컨벤션(spec/impl, CQRS, 이벤트 규칙) |
| `frontend/docs/FLUTTER_CODE_CONVENTIONS.md` | 프론트 코드 컨벤션 |
| `backend/docs/MVP_API_SPEC.md` | (구) MVP 17개 한정 명세 — 본 문서가 흡수했다 |

## Swagger UI 접속법

1. `cd backend && ./gradlew bootRun` (사전에 `docker compose up -d postgres redis kafka`)
2. 브라우저에서 `http://localhost:8080/swagger-ui/index.html`
3. `POST /api/auth/login` 을 실행해 응답의 `data.accessToken` 을 복사
4. 우측 상단 **Authorize** 버튼에 붙여넣으면 이후 모든 요청에 `Authorization: Bearer <token>` 이 자동으로 붙는다

> Swagger 태그 `00. MVP 사용 API` 는 springdoc **그룹이 아니라 그냥 태그**다. `GroupedOpenApi` 빈이 0개이고 `springdoc.group-configs` 설정도 없다 — 각 메서드에 `@Operation(tags = {...})` 를 손으로 붙여 모은 것이라, **새 API 를 만들어도 자동으로 포함되지 않는다.** 태그가 붙은 오퍼레이션은 총 18개다.

---

## 1. 공통 규약

### 1.1 Base URL · 인증 헤더

| 항목 | 값 |
|---|---|
| REST Base URL | `http://localhost:8080` (프론트는 `API_BASE_URL` dart-define, 비우면 같은 출처 상대경로) |
| 전 API 접두사 | `/api` |
| 인증 방식 | JWT Bearer — `Authorization: Bearer <accessToken>` |
| access 토큰 수명 | **900초 (15분)** — `jwt.access-token-validity-seconds` |
| refresh 토큰 수명 | **1209600초 (14일)** — `jwt.refresh-token-validity-seconds` |
| WebSocket | `ws(s)://<host>/ws/location` (STOMP, SockJS 미사용) |

**인증 없이 호출 가능한 경로** (`SecurityConfig`, `permitAll`)
- `/api/auth/**` — signup·login·refresh 전부
- `/actuator/health` — ⚠ **열려 있지만 엔드포인트가 존재하지 않는다**(아래 참조)
- `/ws/**` — HTTP 핸드셰이크 단계만 열려 있고, 실제 인증은 STOMP CONNECT 프레임에서 한다(16장)
- `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`

그 외 전부 `authenticated()` 이며, 역할 인가는 컨트롤러 메서드의 `@PreAuthorize` 로 건다.

> ⚠ **헬스체크 경로를 기대하지 마라.** `SecurityConfig` 에 `/actuator/health` 가 `permitAll` 로 열려 있지만, **`spring-boot-starter-actuator` 의존성이 `build.gradle` 에 없다** — 즉 엔드포인트 자체가 매핑되지 않아 호출하면 404 다. 로드밸런서·컨테이너 헬스체크를 이 경로로 붙이면 항상 실패한다. 쓰려면 actuator 의존성을 먼저 추가해야 한다.

**CORS**: `app.cors.allowed-origins` 목록에 등록된 오리진만, `/api/**` 경로에 한해 허용한다.

**토큰 안의 정보**: 로그인 시점에 `UserTenantRole` 전체를 조회해 `"tenantId:ROLE"` 문자열 배열(`memberships` 클레임)로 인코딩한다. 이후 모든 API 는 DB 재조회 없이 이 클레임만으로 테넌트·역할을 판단한다.

> **흔한 오해**: "관리자 역할을 바꿔주면 즉시 반영되겠지" — 아니다. 멤버십 변경은 **다음 로그인 또는 `POST /api/auth/refresh` 시점**에야 토큰에 반영된다. 발급된 access 토큰은 15분간 옛 권한 그대로다.
>
> 또한 **로그아웃/토큰 무효화 API 가 없다.** 발급된 refresh 토큰은 만료(14일) 전까지 계속 재사용 가능하며 블랙리스트·rotation 로직이 없다. 프론트의 로그아웃은 단말 저장소를 비우는 것뿐이다.

### 1.2 성공 응답 래퍼

모든 성공 응답은 아래 단일 래퍼로 감싼다 (`global/response/ApiResponse`).

```json
{ "success": true, "data": { }, "message": null }
```

| 필드 | 타입 | 설명 |
|---|---|---|
| success | boolean | 성공 시 항상 `true` |
| data | T | 실제 페이로드. 반환값이 없는 API 는 `null` |
| message | String | **성공 시 항상 `null`** |

- 팩토리가 `ok(data)` / `fail(message)` 둘뿐이라 **성공 메시지를 담을 수단이 없다.** 프론트에서 "저장했습니다" 같은 토스트를 서버 메시지로 띄우려 하면 항상 null 이 온다 — 문구는 클라이언트가 갖고 있어야 한다.
- 각 컨트롤러 메서드가 `ApiResponse<T>` 를 **직접 반환**한다(자동 래핑 Advice 없음). 프로젝트 전체에서 `ResponseEntity` 를 쓰는 곳은 `GlobalExceptionHandler` 하나뿐이다(실측).
- **따라서 성공 HTTP 상태는 예외 없이 전부 `200 OK` 다.** POST 생성도 `201` 이 아니다.

### 1.3 에러 응답 포맷 + 에러 코드 전체 목록

에러도 **같은 래퍼**를 쓴다.

```json
{ "success": false, "data": null, "message": "담당 기사만 처리할 수 있습니다" }
```

> **응답 body 에 에러 코드 필드가 없다.** `success`/`data`/`message` 3필드가 전부이고 `code` 가 없다. 프론트는 **HTTP 상태 코드 + 한글 메시지 문자열**로만 분기할 수 있다. 에러 종류를 기계적으로 구분하려면 메시지 문자열 매칭 외에 방법이 없다.

**전역 예외 핸들러 4개** (`GlobalExceptionHandler`, `@RestControllerAdvice`)

| 잡는 예외 | HTTP 상태 | message |
|---|---|---|
| `BusinessException` | ErrorCode 별 가변(400/401/403/404/409/500) | 던질 때 준 커스텀 메시지, 없으면 ErrorCode 기본 메시지 |
| `AccessDeniedException` (`@PreAuthorize` 거부) | **403** | `"접근 권한이 없습니다"` (고정) |
| `MethodArgumentNotValidException` (`@Valid` 실패) | **400** | **첫 번째 필드 에러만** `"필드명: 기본메시지"` |
| `Exception` (catch-all) | **500** | `"서버 오류가 발생했습니다"` (고정, 상세는 서버 로그에만) |

**에러 코드 enum 전체 8개** (`global/error/ErrorCode`)

| 코드 | HTTP | 기본 메시지 |
|---|---|---|
| `INVALID_INPUT` | 400 | 입력값이 올바르지 않습니다 |
| `UNAUTHORIZED` | 401 | 인증이 필요합니다 |
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호가 올바르지 않습니다 |
| `FORBIDDEN` | 403 | 접근 권한이 없습니다 |
| `NOT_FOUND` | 404 | 대상을 찾을 수 없습니다 |
| `CONFLICT` | 409 | 이미 처리된 요청입니다 |
| `DUPLICATE_EMAIL` | 409 | 이미 가입된 이메일입니다 |
| `INTERNAL_ERROR` | 500 | 서버 오류가 발생했습니다 |

enum 이름은 응답에 실리지 않는다(위 경고 참조). 숫자 코드값(`E4001` 류)도 정의돼 있지 않다.

**주의 3가지**
1. **인증 실패(401)만 래퍼를 타지 않는다.** 토큰이 없거나 만료된 요청은 Security 필터 체인의 `HttpStatusEntryPoint(UNAUTHORIZED)` 가 먼저 응답하므로 **body 가 비어 있는 순수 401** 이다. 프론트가 401 을 JSON 파싱하려 하면 빈 문자열을 만난다.
2. **검증 실패 시 필드 에러가 여러 개여도 하나만 내려간다** (`findFirst()`). 폼 전체를 필드별로 표시하는 UI 는 서버 응답만으로 만들 수 없다.
3. 미인증 요청은 관례적인 403 이 아니라 **401** 이다.

### 1.4 HTTP 상태 코드 사용 규칙

| 상태 | 발생 상황 |
|---|---|
| `200` | **모든 성공**(조회·생성·수정 구분 없음) |
| `400` | `@Valid` 실패, `INVALID_INPUT` — 예: "tenantId가 필요합니다", "학생과 버스의 학원이 다릅니다" |
| `401` | 토큰 없음/만료(**body 없음**), 로그인 실패, refresh 토큰 부적합 |
| `403` | 역할 불일치(`@PreAuthorize`), 테넌트 소속 아님, 담당 기사 아님, 자녀 아님 |
| `404` | 대상 리소스 없음 |
| `409` | 상태 전이 위반(이미 처리된 요청·이미 진행 중인 운행·잔류 학생), 이메일 중복 |
| `500` | 처리되지 않은 예외 |

- `201`, `204`, `422` 는 **사용하지 않는다.**
- `PUT` 과 `DELETE` 메서드를 쓰는 엔드포인트가 **전체에 0개**다. 수정은 전부 `PATCH`, 삭제 API 는 존재하지 않는다.

### 1.5 ⚠ 날짜·시간 직렬화 — 오프셋이 없다

**가장 중요한 함정이다. 반드시 읽고 클라이언트를 맞춰라.**

확인된 사실(실측):
- `application.yml` 전체에 `spring.jackson.*` 키가 **없다**.
- 프로젝트 전체에서 커스텀 `ObjectMapper`·`JavaTimeModule` 등록, `@JsonFormat` 사용이 **0건**이다.
- `TimeZone`/`Asia/Seoul` 하드코딩이 없고, `docker-compose.yml` 에도 `TZ` 환경변수가 없다.
- 응답 DTO 의 시각 필드는 전부 `LocalDateTime` / `LocalDate` / `LocalTime` — **오프셋을 담을 수 없는 타입**이다.
- DB 컬럼도 `timestamp(6)` / `date` / `time(0)` 이며 `timestamptz` 가 아니다.
- `@DateTimeFormat(iso = DATE)` 이 쓰인 곳은 전부 **요청 쿼리 파라미터 파싱용**이고 응답 직렬화와 무관하다.

**따라서 실제 응답 형태:**

| 타입 | 예시 문자열 |
|---|---|
| `LocalDateTime` | `"2026-07-29T14:30:00"` — `Z` 도 `+09:00` 도 **붙지 않는다** |
| `LocalDate` | `"2026-07-29"` |
| `LocalTime` | `"17:30:00"` |

**클라이언트 영향과 회피법**

1. 서버가 내보내는 값은 `LocalDateTime.now()` 결과, 즉 **JVM 기본 타임존의 벽시계 시각**이다. 호스트에서 `./gradlew bootRun` 하면 KST, 컨테이너로 띄우면 대개 UTC — **실행 방식에 따라 저장값 자체가 9시간 달라진다.**
2. 이 문자열을 클라이언트가 UTC 로 해석하면(Dart `DateTime.parse(...).toUtc()`, 문자열 뒤에 `Z` 를 붙이는 처리, JS `new Date(...)` 계열) 정확히 **+9시간 어긋난다.**
3. **`spring.jackson.time-zone` 을 설정해도 해결되지 않는다** — `LocalDateTime` 직렬화에는 애초에 오프셋이 붙지 않기 때문이다.
4. **현재 규약(클라이언트가 지켜야 할 것)**: 오프셋 없는 시각 문자열은 **"서버 JVM 로컬 시각"** 으로 간주하고, 로컬 시각으로만 파싱한다(Dart `DateTime.parse()` 를 그대로 쓰고 `toUtc()`/`toLocal()` 변환을 하지 않는다).
5. **절대 시각이 필요한 화면은 서버 값을 쓰지 말 것.** 현재 프론트 관제 화면은 위치 신선도("몇 초 전")를 서버 `recordedAt` 이 아니라 **클라이언트 수신 시각**으로 계산해 이 문제를 우회하고 있다.
6. 근본 해결은 (a) DTO 를 `OffsetDateTime`/`Instant` 로 바꾸고 DB 를 `timestamptz` 로 옮기거나, (b) 위 4번 규약을 양쪽에 명문화하는 것 중 하나다. **현재는 (b) 상태다.**

### 1.6 페이징 규약

**페이징 규약이 없다. 페이징을 쓰는 엔드포인트가 하나도 없다.**

- 전체 소스에서 `Pageable` / `PageRequest` / `Page` 참조가 **0건**(grep 실측).
- 모든 목록 API 는 `ApiResponse<List<T>>` 로 **전건을 한 번에** 내려준다. `page`/`size`/`sort` 파라미터가 없다.
- 정렬은 서버가 리포지토리 메서드 이름에 고정해 두었다(`...OrderByCreatedAtDesc`, `...OrderByOccurredAtDesc`, `...OrderByTargetDateDesc`, `...OrderByRequestedDateDesc`). **클라이언트가 정렬 기준을 바꿀 수 없다.**
- 결과적으로 운행 이력·알림 이력·SOS 이력처럼 누적되는 목록은 학원 규모에 비례해 무한히 커진다. 데모 규모에서는 문제없지만 **운영 전환 시 반드시 손대야 할 지점**이다.

### 1.7 enum 값 사전

요청·응답에 등장하는 enum 은 **전부 문자열(`@Enumerated(STRING)`)** 로 오간다.

| enum | 상수 | 의미 |
|---|---|---|
| `Role` | `STUDENT` / `PARENT` / `DRIVER` / `ACADEMY_ADMIN` / `PLATFORM_ADMIN` | 사용자 5계층 |
| `RideType` | `BOARD` / `ALIGHT` / `HANDOVER` | 승차 / 하차 / 보호자 인계완료 |
| `RideSource` | `QR` / `NFC` / `MANUAL` / `CORRECTION` | 기록 출처. **현재 코드가 만드는 값은 `MANUAL`·`CORRECTION` 둘뿐** |
| `NotificationType` | `BOARD_DONE` / `ALIGHT_DONE` / `HANDOVER_DONE` / `APPROACH` / `NO_SHOW` / `SOS` / `SCHEDULE_RESULT` / `CONNECTION_LOST` / `ROUTE_RECOMMENDED` / `ROUTE_PUBLISHED` | 알림 종류 10종 |
| `ApprovalStatus` | `PENDING` / `APPROVED` / `REJECTED` | attendance·schedule 공용 승인 상태 |
| `AttendanceType` | `ABSENCE` / `LEAVE` | 결석(하루) / 휴원(기간) |
| `DriveSessionStatus` | `IN_PROGRESS` / `COMPLETED` | 운행 세션. **단방향, 재개 불가** |
| `RouteDirection` | `PICKUP` / `DROPOFF` | 등원 / 하원. routing·drivesession 공용 |
| `RoutePlanStatus` | `DRAFT` / `RECOMMENDED` / `APPROVED` / `PUBLISHED` | 노선 계획 상태. **PUBLISHED 이후 철회 없음** |
| `SosStatus` | `OPEN` / `ACKNOWLEDGED` / `RESOLVED` | 단방향. **OPEN→RESOLVED 직행 불가** |
| `LocationOrigin` | `GPS` / `MOCK` | 위치 출처. **서버가 항상 `GPS` 로 기록한다**(5·3장 주의 참조) |

**상태 전이 규칙 요약** (위반 시 전부 `409 CONFLICT`)

```
RoutePlanStatus   DRAFT ─┐
                          ├─ approve ─> APPROVED ─ publish ─> PUBLISHED  (종착)
                  RECOMMENDED ─┘
DriveSessionStatus (생성) ─> IN_PROGRESS ─ end ─> COMPLETED  (종착)
SosStatus          (생성) ─> OPEN ─ acknowledge ─> ACKNOWLEDGED ─ resolve ─> RESOLVED  (종착)
ApprovalStatus     (생성) ─> PENDING ─ approve/reject ─> APPROVED | REJECTED  (종착)
RideType           상태머신 없음 — 순서·중복 검증이 서버에 전혀 없다 (5장)
```

---

## 2. 인증 (Auth) — `/api/auth`

컨트롤러 전체가 `permitAll` 대상이며 메서드에 `@PreAuthorize` 가 하나도 없다.

### `POST /api/auth/login` — 로그인  ✅프론트 사용

| | |
|---|---|
| 권한 | 없음(`permitAll`) |
| 테넌트 격리 | 해당 없음(로그인 자체) |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| email | String | ✔ | `@NotBlank @Email` |
| password | String | ✔ | `@NotBlank` |

**응답** `200`

| 필드 | 타입 | 설명 |
|---|---|---|
| accessToken | String | 수명 15분. `Authorization: Bearer` 에 사용 |
| refreshToken | String | 수명 14일 |

```json
// 요청
{ "email": "driver@school.com", "password": "password" }

// 응답
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
  },
  "message": null
}
```

**주의**
- 사용자 없음과 비밀번호 불일치를 **구분하지 않고** 둘 다 `401 INVALID_CREDENTIALS "이메일 또는 비밀번호가 올바르지 않습니다"` 로 응답한다(계정 존재 여부 노출 방지).
- **사용자 정보 조회 API(`/me` 류)가 없다.** 프론트는 accessToken 의 payload 를 직접 디코드해 세션을 만든다 — 클레임은 `sub`(userId), `email`, `memberships`(`"tenantId:ROLE"` 배열), `type`. `PLATFORM_ADMIN` 은 tenantId 가 빈 문자열이라 `":PLATFORM_ADMIN"` 형태로 온다.

### `POST /api/auth/refresh` — 토큰 재발급  ✅프론트 사용

| | |
|---|---|
| 권한 | 없음(`permitAll`) |
| 테넌트 격리 | 해당 없음 |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| refreshToken | String | ✔ | `@NotBlank` |

**응답** `200` — 로그인과 동일하게 `accessToken`·`refreshToken` 을 **둘 다 새로** 발급한다.

**주의**
- 재발급 시 사용자를 DB 에서 다시 읽어 멤버십을 재인코딩한다 → **권한 변경이 반영되는 유일한 경로**다.
- 토큰 파싱 실패 시 `401 "유효하지 않은 토큰입니다"`, access 토큰을 넣으면 `401 "refresh 토큰이 아닙니다"`.
- **rotation·블랙리스트가 없다.** 옛 refreshToken 도 만료 전까지 계속 유효하다.

### `POST /api/auth/signup` — 회원가입  ⬜프론트 미사용

| | |
|---|---|
| 권한 | 없음(`permitAll`) — **누구나 호출 가능** |
| 테넌트 격리 | **검증 없음 ⚠** — 요청 body 의 `tenantId`·`role` 을 그대로 신뢰한다 |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| email | String | ✔ | `@NotBlank @Email` |
| password | String | ✔ | `@NotBlank` — **길이·복잡도 제약 없음(1글자도 통과)** |
| name | String | ✔ | `@NotBlank` |
| phone | String | | 검증 없음 |
| tenantId | Long | | `PLATFORM_ADMIN` 이면 생략, 그 외 역할은 서비스에서 null 검사 |
| role | Role | ✔ | `@NotNull` |

**응답** `200` — `data: null`

**주의**
- ⚠ **보안 결함**: 미인증 엔드포인트인데 `role` 을 자유롭게 지정할 수 있고, `tenantId` 는 "그 학원이 존재하는가"만 확인한다. **"이 사람이 그 학원에 가입해도 되는가"에 대한 검증이 전혀 없어, 누구나 임의 학원의 `ACADEMY_ADMIN` 으로 self-signup 할 수 있다.**
- 프론트는 이 API 를 의도적으로 쓰지 않는다(계정은 관리자가 `POST /api/members` 로 생성한다는 방침). 다만 그 관리자 화면도 아직 없다(11장·17장 참조).
- 이메일 중복 시 `409 DUPLICATE_EMAIL`. 비밀번호는 BCrypt 해싱.

---

## 3. 위치 (Location) — `/api/locations`

**저장소 특성 (전 엔드포인트 공통 — 먼저 읽을 것)**

| 대상 | 구현 | 보관 |
|---|---|---|
| 학생 위치 | `RedisLocationRepository` (`@Primary`) | 키 `loc:{studentId}`, **최신 1건만**, TTL = `app.location.tick-ms`(기본 3000ms)×3 ≈ **9초** |
| 버스 위치 | `InMemoryBusLocationRepository` | `ConcurrentHashMap`, **최신 1건만**, 앱 재시작 시 휘발 |

> **위치 "이력 조회" API 는 존재하지 않는다.** 두 저장소 모두 최신 1건만 갖고, 학생 위치는 마지막 보고 후 약 9초가 지나면 사라진다. 궤적을 그리려면 클라이언트가 폴링 결과를 직접 누적해야 한다.

### `POST /api/locations/bus` — 버스 위치 보고  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | 담당 기사 검증 — `bus.driver` ≠ JWT `userId` 면 `403 "담당 기사만 보고할 수 있습니다"`. tenantId 는 버스에서 파생하므로 위조 불가 |
| 구현 상태 | ⚠ 부분 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| busId | Long | ✔ | `@NotNull` |
| lat | Double | ✔ | `@NotNull`. **위경도 범위 검증 없음** |
| lng | Double | ✔ | `@NotNull`. 동일 |

**응답** `200` — `data: null`

**주의**
- ⚠ **저장은 되지만 이벤트를 발행하지 않는다.** 학생 위치(`POST /api/locations`)와 달리 Kafka→STOMP push 로 이어지지 않아, 관리자 관제는 `GET /api/locations/buses` **3초 폴링**에 의존한다.
- ⚠ `origin` 이 **항상 `GPS` 로 하드코딩**된다. 기사 앱이 Mock 좌표를 보내도 서버 기록은 `GPS` 이므로, 응답의 `origin` 으로 실제 Mock 여부를 판별할 수 없다.
- MVP 기본 좌표 소스는 이 경로가 아니라 서버 측 Mock 이다 — `DriverGpsSource` 는 `app.location.bus-gps.enabled=false` 로 기본 비활성이고, `LocationSimulationScheduler` 가 활성 소스만 주기적으로 tick 한다.

### `GET /api/locations/buses` — 학원 버스 위치 목록(관제)  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` (규칙은 아래 박스) |
| 구현 상태 | ✅ 구현 |

**요청** — query

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| tenantId | Long | | `PLATFORM_ADMIN` 은 **필수**(생략 시 400), `ACADEMY_ADMIN` 은 생략 시 본인 소속으로 대체 |

**응답** `200` — `List<BusLocationView>`

| 필드 | 타입 | 설명 |
|---|---|---|
| busId | Long | |
| busName | String | |
| lat / lng | double | |
| recordedAt | LocalDateTime | **오프셋 없음**(1.5 참조) |
| origin | LocationOrigin | 실질적으로 항상 `GPS` |

> **`TenantGuard.resolveTenantId(admin, requested)` 규칙 (관리자 조회 전반 공통)**
>
> | 요청자 | requested | 결과 |
> |---|---|---|
> | PLATFORM_ADMIN | null | `400 "tenantId가 필요합니다"` |
> | PLATFORM_ADMIN | 지정 | 통과 — **어느 학원이든 조회 가능** |
> | ACADEMY_ADMIN | null | 본인 소속(`primaryTenantId`)으로 자동 대입. 소속 없으면 `403` |
> | ACADEMY_ADMIN | 지정 | 소속이 아니면 `403` |

**주의**
- **좌표가 아직 없는 버스는 응답에서 빠진다.** "버스 3대인데 응답 2건"이 정상이다. 전체 버스 목록과 병합해 "위치 미보고"를 구분하려면 `GET /api/buses` 를 함께 호출해야 한다(프론트가 그렇게 하고 있다).

### `POST /api/locations` — 학생 위치 보고  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('STUDENT')")` |
| 테넌트 격리 | JWT `userId` → 본인 Student 조회 → 그 학생의 tenantId 사용. 위조 불가 |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| lat | Double | ✔ | `@NotNull`. 범위 검증 없음 |
| lng | Double | ✔ | `@NotNull`. 범위 검증 없음 |

**응답** `200` — `data: null`

**주의**
- 저장 후 `LocationUpdatedEvent` → Kafka `location-updated` → `LocationPushConsumer` 가 STOMP 로 push 한다(16장). **버스 위치와 달리 실시간 경로가 살아 있는 유일한 위치 API 다.**
- `origin` 은 `GPS` 로 고정 기록된다.

### `GET /api/locations/me` — 본인 최신 위치  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('STUDENT')")` |
| 테넌트 격리 | JWT `userId` 기준 본인만. 타인 조회 경로 없음 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `LocationView(studentId, studentName, lat, lng, recordedAt, origin)`

**주의**
- 좌표가 없으면 빈 응답이 아니라 `404 "아직 위치 정보가 없습니다"` 다. **마지막 보고 후 약 9초(Redis TTL)가 지나면 이 에러가 난다.**

### `GET /api/locations/children` — 자녀 위치 목록  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PARENT')")` |
| 테넌트 격리 | tenantId 를 쓰지 않고 **관계 기반** — `student_guardian` 으로 연결된 학생만. 위조 불가 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `List<LocationView>`

**주의**
- **최신 좌표가 없는 자녀는 결과에서 조용히 제외된다.** "자녀 3명인데 응답 2건"이 정상 동작이다.

### `GET /api/locations/bus/{busId}` — 담당 버스 탑승 학생 위치  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | 담당 기사 검증 — `bus.driver` ≠ JWT `userId` 면 `403 "담당 기사만 조회할 수 있습니다"` |
| 구현 상태 | ✅ 구현 |

**요청** — path `busId: Long` (필수)
**응답** `200` — `List<LocationView>` (해당 버스에 **배정된** 학생들의 최신 위치)

### `GET /api/locations` — 학원 전체 학생 위치(관제)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택, 위 TenantGuard 규칙)
**응답** `200` — `List<LocationView>`

---

## 4. 운행 세션 (DriveSession) — `/api/drive-sessions`

**공통 응답 DTO** — `DriveSessionResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | 세션 id |
| tenantId | Long | |
| busId | Long | |
| driverId | Long | 세션을 시작한 기사 userId |
| direction | RouteDirection | `PICKUP` / `DROPOFF` |
| serviceDate | LocalDate | 운행일 |
| routePlanId | Long | nullable — 계획 없이 시작하면 null |
| status | DriveSessionStatus | `IN_PROGRESS` / `COMPLETED` |
| startedAt | LocalDateTime | |
| endedAt | LocalDateTime | nullable |

### `POST /api/drive-sessions/start` — 운행 시작  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | 담당 기사 검증 — `bus.driver` ≠ JWT `userId` 면 `403 "담당 기사만 처리할 수 있습니다"`. tenantId 는 버스에서 파생 |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| busId | Long | ✔ | `@NotNull` |
| direction | RouteDirection | ✔ | `@NotNull`. `PICKUP`(등원) / `DROPOFF`(하원) |
| serviceDate | LocalDate | | 생략 시 `LocalDate.now()` |

**응답** `200` — `DriveSessionResponse`

**주의**
- 같은 `(busId, direction, serviceDate)` 에 `IN_PROGRESS` 세션이 이미 있으면 `409 "이미 진행 중인 운행이 있습니다"`. 프론트는 이 409 를 받으면 목록을 재조회해 기존 세션에 붙는 식으로 복구한다.
- 같은 날 같은 버스·방향의 **`PUBLISHED` 노선 계획이 있으면 자동으로 `routePlanId` 에 연결**하고, 없으면 null 로 두고 **시작 자체는 허용**한다.

### `PATCH /api/drive-sessions/{id}/end` — 운행 종료  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | 세션 소유자 검증 — `session.driverId` ≠ JWT `userId` 면 `403 "본인이 시작한 운행만 종료할 수 있습니다"` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `DriveSessionResponse` (`status=COMPLETED`, `endedAt` 채워짐)

**주의**
- **차내 잔류 방지 검사가 있다.** 세션 `startedAt`~현재 사이의 해당 버스 승하차 기록을 학생별로 훑어 **마지막 기록이 `BOARD` 인 학생이 하나라도 있으면** `409 "하차 처리되지 않은 학생이 있어 운행을 종료할 수 없습니다"` 로 막는다.
- 이 판별은 `driveSessionId` 컬럼이 아니라 **busId + 시간창**으로 세션 경계를 잡는다 — "버스당 IN_PROGRESS 세션은 1개뿐"이라는 전제에 의존한다.
- 이미 `COMPLETED` 인 세션을 다시 종료하면 `409 "이미 종료된 운행입니다"`.

### `GET /api/drive-sessions/{id}/roster` — 세션 명단  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | 세션 소유자 검증 — `403 "본인이 시작한 운행만 조회할 수 있습니다"` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(세션 id, 필수)

**응답** `200` — `List<DriveSessionRosterEntry>`

| 필드 | 타입 | 설명 |
|---|---|---|
| studentId | Long | |
| name | String | |
| location | String | `PICKUP` 이면 승차 정류장 이름, `DROPOFF` 면 하차지 주소 |
| lat / lng | Double | 위 location 에 대응하는 좌표. nullable |

**주의**
- **당일 `APPROVED` 결석 신고가 있는 학생은 자동으로 빠진다**(`attendance` 모듈의 `getActiveRoster` 사용).
- `direction` 에 따라 위치 3필드의 출처가 통째로 바뀐다: `PICKUP` → 학생의 `boardingStop` 이름·좌표 / `DROPOFF` → `dropoffAddress`·`dropoffLat`·`dropoffLng`. **해당 정보가 없으면 세 필드 모두 null 이다.**
- 학생 사진 필드는 없다(`Student` 엔티티에 없음).

### `GET /api/drive-sessions/bus/{busId}` — 버스 운행 이력(기사)  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | 담당 기사 검증 — `403 "담당 기사만 처리할 수 있습니다"` |
| 구현 상태 | ✅ 구현 |

**요청** — path `busId: Long`(필수)
**응답** `200` — `List<DriveSessionResponse>`, `startedAt` **내림차순**

**주의**
- ⚠ **"현재 진행 중인 세션" 전용 API 가 없다.** 프론트는 이 이력 전체를 받아 클라이언트에서 `IN_PROGRESS` 를 찾아 쓴다.
- 기간 필터·페이징이 없어 **해당 버스의 전체 이력**이 한 번에 나온다. 필터가 "버스 담당 여부"뿐이므로 **다른 기사가 만든 세션도 포함**된다.

### `GET /api/drive-sessions` — 학원 운행 이력(관리자)  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택, TenantGuard 규칙)
**응답** `200` — `List<DriveSessionResponse>`, `startedAt` 내림차순

**주의**
- **기간·버스·상태 필터와 페이징이 전부 없다.** 학원 전체 이력이 한 번에 나온다. 프론트는 이걸 받아 클라이언트에서 busId 로 필터링해 상세 패널을 그린다.

### (참고) 스케줄러 — 엔드포인트 아님

`ApproachNoShowScheduler` 가 **15초마다**(`app.drivesession.approach-check-ms: 15000`) 진행 중인 세션을 훑어 `APPROACH`(접근) / `NO_SHOW`(미탑승) 알림 이벤트를 발행한다.

- ⚠ **`IN_PROGRESS` + `PICKUP` 세션만 대상이다** — 하원(`DROPOFF`)의 미하차·미인계는 자동 감지되지 않는다(학원에서 이미 승차한 상태로 출발한다는 전제의 의도적 설계).
- ⚠ **`routePlanId` 가 null 인 수동 운행은 제외**된다. ETA 근거가 없어 두 알림이 아예 나가지 않는다.
- ⚠ ETA 는 실시간 버스 좌표가 아니라 **`session.startedAt + RoutePlanStop.etaSeconds` 근사**다. 운행이 지연되면 판정이 실제와 어긋난다.
- 매 틱 전수 재평가하지만 알림 측 `dedupKey` 멱등 덕분에 학생당 1회만 발송된다.
- **운행 세션을 시간 경과로 자동 종료하는 스케줄러는 없다.** 기사가 종료를 누르지 않으면 `IN_PROGRESS` 로 남아 계속 스캔 대상이 된다.

---

## 5. 승하차 (RideEvent) — `/api/ride-events`

같은 기록을 역할별로 다른 범위에서 조회하도록 엔드포인트를 분리했다.

**공통 응답 DTO** — `RideEventResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | |
| tenantId / studentId / busId | Long | |
| stopId | Long | nullable |
| type | RideType | `BOARD` / `ALIGHT` / `HANDOVER` |
| occurredAt | LocalDateTime | |
| lat / lng | Double | nullable |
| source | RideSource | `MANUAL`(신규 기록) / `CORRECTION`(정정 기록) |
| correctedBy | Long | 정정자 userId. 정정 기록에만 |
| correctedAt | LocalDateTime | 정정 기록에만 |
| originalRef | Long | 원본 RideEvent id. 정정 기록에만 |

### `POST /api/ride-events` — 승하차 기록  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | 이중 검증 — (1) `bus.driver` = JWT `userId`, (2) 학생과 버스의 tenantId 일치(`400 "학생과 버스의 학원이 다릅니다"`). 저장 tenantId 는 버스에서 파생 |
| 구현 상태 | ⚠ 부분 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| busId | Long | ✔ | `@NotNull` |
| studentId | Long | ✔ | `@NotNull` |
| type | RideType | ✔ | `@NotNull`. `BOARD` / `ALIGHT` / `HANDOVER` |
| stopId | Long | | 생략 시 학생의 기본 승차 정류장, 그것도 없으면 null |
| lat | Double | | 검증 없음 |
| lng | Double | | 검증 없음 |

**응답** `200` — `RideEventResponse`

```json
// 요청
{ "busId": 1, "studentId": 1, "type": "BOARD", "lat": 37.5010, "lng": 127.0275 }

// 응답
{
  "success": true,
  "data": {
    "id": 41, "tenantId": 1, "studentId": 1, "busId": 1, "stopId": 1,
    "type": "BOARD", "occurredAt": "2026-08-01T08:12:33",
    "lat": 37.501, "lng": 127.0275, "source": "MANUAL",
    "correctedBy": null, "correctedAt": null, "originalRef": null
  },
  "message": null
}
```

**주의**
- ⚠ **승차 없이 하차를 보내도 서버가 200 을 반환한다.** 순서·중복 검증(`BOARD` 없이 `ALIGHT`, 연속 `BOARD`, 이미 하차한 학생 재하차)이 **서버에 전혀 없다.** 현재는 프론트가 버튼을 하나만 노출해 막고 있을 뿐이라, 다른 클라이언트나 직접 호출은 막히지 않는다. 간접 방어는 운행 종료 시점의 잔류 검사(4장)뿐이다.
- ⚠ `occurredAt` 을 요청에서 받지 않고 **항상 `LocalDateTime.now()`** 로 박는다. 과거 시각으로 기록하려면 정정 API 를 써야 한다.
- ⚠ `source` 가 **항상 `MANUAL` 하드코딩**이다. enum 에 `QR`/`NFC` 가 있지만 **이 API 로는 절대 생성되지 않는다 — QR/NFC 스캔 경로는 미구현이다.**
- type 에 따라 `StudentBoardedEvent` / `RideCompletedEvent` / `HandoverCompletedEvent` 를 발행해 알림 파이프라인으로 넘긴다.

### `GET /api/ride-events/bus/{busId}` — 버스 하루치 기록(기사)  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | 담당 기사 검증 — `403 "담당 기사만 처리할 수 있습니다"`. tenantId 는 조회 조건에 쓰지 않음 |
| 구현 상태 | ✅ 구현 |

**요청**

| 위치 | 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | busId | Long | ✔ | |
| query | date | LocalDate | | ISO `YYYY-MM-DD`. 생략 시 오늘 |

**응답** `200` — `List<RideEventResponse>`, `occurredAt` 오름차순

**주의**
- 하루 범위는 `[date 00:00, date+1 00:00)` 이다. **등원·하원 기록이 한 응답에 섞여 온다** — 세션 구간으로 나누는 건 클라이언트 몫이다.

### `GET /api/ride-events` — 학원 기간 기록(관리자)  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` + **쿼리 조건에 저장 컬럼 `tenantId` 가 직접 들어가는 유일한 조회** |
| 구현 상태 | ✅ 구현 |

**요청** — 전부 query, 전부 선택

| 필드 | 타입 | 설명 |
|---|---|---|
| tenantId | Long | TenantGuard 규칙 |
| studentId | Long | 지정 시 학생 단위 필터 |
| from | LocalDate | ISO DATE. 생략 시 오늘 |
| to | LocalDate | ISO DATE. 생략 시 오늘 |

**응답** `200` — `List<RideEventResponse>`, `occurredAt` 오름차순. 정정 기록도 함께 포함된다.

**주의**
- ⚠ **버스(busId) 필터가 없다.** 프론트는 학원 전체 기록을 받아 클라이언트에서 `busId` 로 거른다.
- `from > to` 검증이 없다(그냥 빈 결과가 된다). 기간은 `[from 00:00, to+1 00:00)`. 페이징 없음.

### `POST /api/ride-events/{id}/correction` — 기록 정정  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('DRIVER', 'ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | PLATFORM_ADMIN 통과 → `belongsToTenant(원본.tenantId)` 통과 → 해당 버스 담당 기사 통과 → 아니면 `403` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(원본 기록 id, 필수)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| type | RideType | ✔ | `@NotNull` |
| occurredAt | LocalDateTime | | 생략 시 **원본 시각 승계** |
| stopId | Long | | 생략 시 **원본 stopId 승계** |
| lat | Double | | 생략 시 **null 로 저장**(승계하지 않음) |
| lng | Double | | 동일 |

**응답** `200` — 새로 만들어진 **정정 기록**(`source=CORRECTION`, `correctedBy`, `correctedAt`, `originalRef` 채워짐)

**주의**
- **원본을 덮어쓰지 않는다(append-only).** 정정하면 행이 하나 늘어나며, 관리자 조회 응답에는 원본과 정정본이 **둘 다** 나온다. 화면에서 최종값만 보이려면 `originalRef` 로 클라이언트가 접어야 한다.
- ⚠ `lat`/`lng` 만 fallback 규칙이 다르다 — 생략하면 원본 좌표를 잃고 null 이 된다.
- ⚠ **인가가 `belongsToTenant` 기반이라, 같은 학원 소속이기만 하면 담당이 아닌 버스의 기록도 기사가 정정할 수 있다.**
- 정정의 정정(체인)에 대한 제한이 없다.

### `GET /api/ride-events/me` — 본인 하루치 기록(학생)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('STUDENT')")` |
| 테넌트 격리 | JWT `userId` → 본인 Student 만. 타인 조회 경로 없음 |
| 구현 상태 | ✅ 구현 |

**요청** — query `date: LocalDate`(선택, ISO DATE, 생략 시 오늘)
**응답** `200` — `List<RideEventResponse>`, `occurredAt` 오름차순

### `GET /api/ride-events/children` — 자녀 하루치 기록(학부모)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PARENT')")` |
| 테넌트 격리 | **관계 기반** — `student_guardian` 으로 연결된 자녀만. 자녀가 없으면 빈 리스트 |
| 구현 상태 | ✅ 구현 |

**요청** — query `date: LocalDate`(선택, ISO DATE, 생략 시 오늘)
**응답** `200` — `List<RideEventResponse>` (형제자매가 있으면 합쳐서 나온다)

---

## 6. 배차·노선계획 (Routing) — `/api/route-plans`

**당일 운행 계획**을 다루는 신규 모듈이다. 7장의 레거시 `route` 모듈과 테이블·엔티티가 완전히 분리돼 있다(7장 말미 비교표 참조).

**공통 응답 DTO** — `RoutePlanResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | |
| tenantId / busId | Long | |
| direction | RouteDirection | `PICKUP` / `DROPOFF` |
| status | RoutePlanStatus | `DRAFT` / `RECOMMENDED` / `APPROVED` / `PUBLISHED` |
| version | int | 재계산 시 기존 행을 고치지 않고 version+1 인 **새 행**을 만든다 |
| serviceDate | LocalDate | |
| totalDistanceM | double | 외부 경로 API 가 준 총 도로거리(m) |
| totalDurationS | double | 총 소요시간(초) |
| polyline | String | 인코딩된 경로 폴리라인 |
| stops | List\<StopEntry\> | seq 오름차순 |
| createdAt | LocalDateTime | |

`StopEntry(seq: int, studentId: Long, lat: double, lng: double, etaSeconds: long)` — `etaSeconds` 는 출발 기준 **누적** 소요(초).

> ⚠ **응답에 정류장 이름과 학원(depot) 좌표가 없다.** `stops` 는 학생 좌표 목록일 뿐이라, 프론트는 정류장 이름을 운행 명단에서 따로 얻고 학원 위치는 **polyline 의 첫 점을 depot 으로 간주**해 표시한다.

### `POST /api/route-plans/auto-assign` — 자동 배차(제안)  ✅프론트 사용

테넌트 전체 활성 로스터를 버스별로 자동 배정하고 버스마다 `RECOMMENDED` 계획을 만든다. **제안 단계라 학생 배정은 아직 커밋하지 않는다.**

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, req.tenantId)` 가 첫 줄. 이후 조회는 전부 그 tenantId 로 제한 |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| tenantId | Long | ✔ | `@NotNull` |
| direction | RouteDirection | ✔ | `@NotNull` |
| serviceDate | LocalDate | | 생략 시 오늘 |

**응답** `200`

| 필드 | 타입 | 설명 |
|---|---|---|
| plans | List\<RoutePlanResponse\> | 버스별 `RECOMMENDED` 계획 |
| excludedStudentNames | List\<String\> | 좌표가 없어 배정에서 빠진 학생 이름 |

```json
// 요청
{ "tenantId": 1, "direction": "DROPOFF", "serviceDate": "2026-08-01" }

// 응답 (요약)
{
  "success": true,
  "data": {
    "plans": [
      {
        "id": 12, "tenantId": 1, "busId": 1, "direction": "DROPOFF",
        "status": "RECOMMENDED", "version": 1, "serviceDate": "2026-08-01",
        "totalDistanceM": 4820.0, "totalDurationS": 613.0, "polyline": "...",
        "stops": [
          { "seq": 1, "studentId": 1, "lat": 37.501, "lng": 127.0275, "etaSeconds": 240 },
          { "seq": 2, "studentId": 3, "lat": 37.5045, "lng": 127.031, "etaSeconds": 470 }
        ],
        "createdAt": "2026-08-01T09:02:11"
      }
    ],
    "excludedStudentNames": ["강서준"]
  },
  "message": null
}
```

**주의**
- **좌표 없는 학생은 예외를 던지지 않고 `excludedStudentNames` 로 보고하고 제외**한다. 화면에서 이 목록을 반드시 보여줘야 조용한 누락을 막을 수 있다.
- 실패 조건: 버스 0대 → `400 "배차 가능한 버스가 없습니다"`, 총 좌석정원 초과 → `400`, 좌표 있는 학생이 0명 → **에러가 아니라** 빈 `plans` + `excluded` 반환.
- 학생이 한 명도 배정되지 않은 버스는 **계획 자체를 만들지 않는다.**

### `POST /api/route-plans/auto-assign/confirm` — 배차 확정  ✅프론트 사용

검토를 마친 `RECOMMENDED` 계획들을 확정한다. **학생 `assignedBus` 커밋 + approve + publish 를 한 트랜잭션에서 연속 수행**한다.

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | planId 마다 조회 후 `TenantGuard.resolveTenantId(admin, plan.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| planIds | List\<Long\> | ✔ | `@NotEmpty` |

**응답** `200` — `List<RoutePlanResponse>` (전부 `PUBLISHED`)

**주의**
- **프론트는 `approve`·`publish` 를 개별로 부르지 않는다** — 이 하나로 끝낸다.
- 이미 확정된 계획을 다시 confirm 하면 상태 가드에 걸려 `409` 다. 프론트는 이를 "이미 확정됨"으로 처리하고 버튼을 잠근다.
- 배포 시 `RoutePlanPublishedEvent` 를 발행하는데, ⚠ 알림 파이프라인이 studentId 기준이라 **계획의 첫 정차 학생을 대표로 삼는 우회 구현**이다 — 결과적으로 담당 기사 알림이 계획당 1건만 나간다.

### `GET /api/route-plans` — 노선 계획 목록  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` 로 정한 tenantId 가 **리포지토리 조회 조건에 직접 포함**된다(busId 만 넘겨도 tenant 조건이 함께 걸림) |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택), `busId: Long`(선택)
**응답** `200` — `List<RoutePlanResponse>`, `createdAt` 내림차순

### `GET /api/route-plans/{id}` — 노선 계획 상세  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, plan.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수)
**응답** `200` — `RoutePlanResponse` (정차 순서 포함)

### `GET /api/route-plans/driver/{busId}` — 기사 당일 노선  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` |
| 테넌트 격리 | TenantGuard 미사용. **담당 기사 본인** 단위 — `bus.driver` ≠ JWT `userId` 면 `403` |
| 구현 상태 | ✅ 구현 |

**요청**

| 위치 | 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | busId | Long | ✔ | |
| query | serviceDate | LocalDate | | ISO DATE. 생략 시 오늘 |

**응답** `200` — 해당 날짜의 **`PUBLISHED`** 계획 목록(등원/하원), `direction` 오름차순

**주의**
- ⚠ **정렬이 `direction` 기준뿐이라 최신 version 이 앞에 온다는 보장이 없다.** 재배차가 일어나면 version 이 올라간 새 행이 생기므로, 클라이언트가 방향별로 **최고 version 을 직접 골라야 한다**(프론트가 그렇게 방어하고 있다). 서버 정렬 수정이 정공법이다.

### `POST /api/route-plans/generate` — 단일 버스 계획 생성(DRAFT)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | 버스를 먼저 로드한 뒤 `TenantGuard.resolveTenantId(admin, bus.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| busId | Long | ✔ | `@NotNull` |
| direction | RouteDirection | ✔ | `@NotNull` |
| serviceDate | LocalDate | | 생략 시 오늘 |

**응답** `200` — `RoutePlanResponse` (`status=DRAFT`)

**주의** — 아래 사전 조건에 걸리면 전부 `400 INVALID_INPUT` 이다.
- 로스터가 0명
- **학원 depot 좌표(`tenant.lat/lng`)가 없음** → `"학원 위치(depot)가 설정되지 않았습니다"`
- 로스터 인원 > `bus.seatCapacity`
- 좌표가 없는 학생이 한 명이라도 포함 (auto-assign 과 달리 **여기서는 제외가 아니라 실패**다)

### `PATCH /api/route-plans/{id}/approve` — 계획 승인  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, plan.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `RoutePlanResponse` (`status=APPROVED`)

**주의** — `DRAFT`/`RECOMMENDED` 가 아니면 `409 "초안·추천 상태에서만 승인할 수 있습니다"`.

### `PATCH /api/route-plans/{id}/publish` — 계획 배포  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, plan.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `RoutePlanResponse` (`status=PUBLISHED`)

**주의**
- `APPROVED` 가 아니면 `409 "승인된 계획만 배포할 수 있습니다"`.
- 배포 즉시 `GET /api/route-plans/driver/{busId}` 에 노출되고, 같은 날 같은 버스·방향 운행 시작 시 `routePlanId` 로 자동 연결된다.
- **`PUBLISHED` 이후 취소·철회 전이가 없다.** 되돌리려면 새 계획을 만들어야 한다.

### 6.x 자동 배차·경로 최적화 알고리즘 실태

문서 소비자가 "무엇이 최적화되고 무엇이 안 되는지" 오해하지 않도록 실측 내용을 적는다.

**(1) 버스 배정 — `SweepAssigner`** (`BusAssigner` 의 유일한 구현체)

depot 기준으로 학생 좌표의 **방위각(bearing)을 계산해 정렬**한 뒤, 버스를 **id 오름차순**으로 순회하며 `seatCapacity` 만큼 **순차로 잘라 담는다.**

| 반영하는 제약 | 반영하지 **않는** 제약 |
|---|---|
| 좌석 정원(`Bus.seatCapacity`) | 시간창(등원 도착·하원 출발 시각) — 함수 시그니처에 시간 입력 자체가 없다 |
| | 버스 간 이동거리·소요시간 균형 — **앞 버스부터 정원까지 꽉 채우므로 마지막 버스가 비는 편향이 구조적으로 발생한다** |
| | 노선(`Route`)·정류장 seq — route 모듈 데이터를 전혀 참조하지 않는다 |
| | 기사 배정 여부·보험 만료 — 필터가 없어 **기사 없는 버스도 후보에 들어간다** |
| | `Route.assignCapacity` — 배정 판단에는 `seatCapacity` 만 쓴다 |

총 좌석정원보다 학생이 많으면 배정 자체가 실패한다(`400`).

**(2) 정차 순서 최적화 — `HeuristicRouteEngine`** (`RouteEngine` 의 유일한 구현체)

3단계: **sweep 정렬(결정적 초기 순서) → nearest-neighbor(그리디) → 2-opt(open-path, 최대 1000 pass)**

- 거리 함수는 **Haversine 직선거리**다. 최적화 단계에서 외부 API 호출은 **0회**.
- 실제 도로거리·ETA 는 순서 확정 후 `MapRouteClient` 를 한 번 호출해 받는다.
- 방향 처리: `DROPOFF` 는 `[depot, ...순서]`, `PICKUP` 은 최적 순서를 뒤집어 `[...역순, depot]`. "대칭거리에서는 depot 고정 최적경로를 뒤집어도 총거리가 같다"는 성질에 기대 최적화를 **1회만** 수행한다.
  - ※ 이 가정은 Haversine(대칭)에서는 성립하지만 **실제 도로망(일방통행 등 비대칭)에서는 보장되지 않는다.**
- 반영하지 않는 것: 학생별 승하차 소요시간(dwell time), 도로 통행제한, 시간창.

**(3) 외부 경로 API — `MapRouteClient` 포트, 구현체 2개**

| 구현체 | 활성 조건 | 특징 |
|---|---|---|
| `NaverMapRouteClient` | `routing.provider=naver` (**application.yml 기본값**) | NCP Direction 15, `option=trafast`. 키는 `NAVER_DIRECTIONS_KEY_ID`/`NAVER_DIRECTIONS_KEY` 환경변수이며 **기본값이 빈 문자열이라 미설정 시 인증 실패한다** |
| `OsrmMapRouteClient` | `routing.provider=osrm` 또는 property 부재 시 fallback | 공개 데모 서버 `router.project-osrm.org`, 키 불필요 |

- ⚠ NCP 는 leg 별 duration 을 주지 않아 **구간 Haversine 거리 비례로 근사**한다 → 개별 stop 의 `etaSeconds` 는 근사값이다. OSRM 은 leg duration 을 직접 주므로 근사가 없다.
- 연속 중복 좌표(같은 정류장 학생)는 호출 전 접고, 접힌 leg 는 duration 0 으로 되살린다.
- waypoint 가 `routing.max-waypoints`(기본 7)를 넘으면 **경계를 공유하는 구간으로 쪼개 여러 번 호출**하고 거리·시간·polyline 을 병합한다. ⚠ 이때 결과는 전역 최적 경로가 아니라 **"이어붙인 경로"** 다.

**(4) 이벤트 기반 국소 재계산(replan) — API 아님**

`RoutingReplanEventConsumer` 가 Kafka 토픽 4개(`attendance-approved`, `schedule-result`, `student-assignment-changed`, `student-dropoff-changed`)를 구독한다.

- 재계산 조건: 해당 학생 배정 버스의 방향별 **최신 계획의 `serviceDate` 가 이벤트 날짜와 같을 때만.**
- 결과는 기존 행 수정이 아니라 **version+1 인 새 `RECOMMENDED` 행 생성**이다 → 기사 조회 API 가 최신 version 을 보장하지 않는 문제와 직결된다(위 driver 엔드포인트 주의).
- 실패(로스터 0명·정원 초과·경로 API 오류)해도 기존 계획을 유지하고 warn 로그만 남긴다.
- ⚠ 학생 재배정/하차지 변경 이벤트의 `serviceDate` 가 **항상 `LocalDate.now()`** 라, 과거·미래 날짜 계획은 replan 대상이 되지 않는다.

---

## 7. 노선 (Route — 레거시) — `/api/routes`

**4개 엔드포인트 전부 프론트가 쓰지 않는다.** 현재 이 모듈의 실질 역할은 "정류장 마스터 데이터 + 정원 초과 경고 표시"뿐이다. 당일 운행 순서는 6장의 `routing` 이 매번 새로 만든다.

### `GET /api/routes` — 노선 목록  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택)

**응답** `200` — `List<RouteResponse>`

| 필드 | 타입 | 설명 |
|---|---|---|
| id / tenantId | Long | |
| name | String | |
| assignCapacity | int | 배정 정원 |
| assignedCount | int | 이 노선을 운행하는 버스들에 배정된 학생 수 합 |
| overCapacity | boolean | `assignedCount > assignCapacity` |

**주의**
- `assignedCount` 계산이 **N+1 쿼리** 구조다(테넌트 전 버스를 가져와 자바에서 필터한 뒤 버스마다 학생 조회).

### `GET /api/routes/{id}/stops` — 정류장 목록  ⬜프론트 미사용

| | |
|---|---|
| 권한 | **`@PreAuthorize` 없음** — `authenticated()` 만 적용되어 학생·학부모·기사 포함 **인증된 모든 사용자**가 호출 가능 |
| 테넌트 격리 | **검증 없음 ⚠** |
| 구현 상태 | ✅ 구현 (기능은 동작, 격리는 결함) |

**요청** — path `id: Long`(노선 id, 필수)

**응답** `200` — `List<StopResponse>`, `seq` 오름차순

| 필드 | 타입 | 설명 |
|---|---|---|
| id / routeId | Long | |
| name | String | 정류장 이름 |
| seq | int | 노선 내 순서 |
| lat / lng | double | |

**주의**
- ⚠⚠ **전체 API 중 유일하게 테넌트 격리가 완전히 비어 있는 엔드포인트다.** 컨트롤러가 `AuthUser` 를 **받지도 않고** 곧장 `routeId` 로 정류장을 조회한다. **다른 학원의 routeId 를 넣으면 그 학원 정류장의 이름과 좌표가 그대로 노출된다.** 아무 역할의 계정으로도 가능하다.
- 정류장 이름·좌표는 학생 승하차 지점, 즉 개인정보에 준하는 데이터다. 운영 전환 전 반드시 `AuthUser` 주입 + 노선의 tenantId 검증을 추가해야 한다.

### `POST /api/routes` — 노선 생성  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, req.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| tenantId | Long | | 생략 시 요청자 소속 학원 |
| name | String | ✔ | `@NotBlank` |
| assignCapacity | int | ✔ | `@Positive` |

**응답** `200` — `RouteResponse` (생성 직후라 `assignedCount` 는 항상 0)

### `POST /api/routes/{id}/stops` — 정류장 추가  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | 노선을 로드해 `TenantGuard.resolveTenantId(admin, route.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(노선 id, 필수)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| name | String | ✔ | `@NotBlank` |
| seq | int | ✔ | `@PositiveOrZero` |
| lat | double | | 검증 애너테이션 없음 |
| lng | double | | 검증 애너테이션 없음 |

**응답** `200` — `StopResponse`

**주의**
- ⚠ **`seq` 중복·연속성 검증이 없다.** 같은 seq 를 여러 번 넣을 수 있고, 그러면 정렬 결과 순서가 비결정적이다.

### 7.x `route`(레거시) ↔ `routing`(신규) 공존 구조

| 구분 | `route` 모듈 | `routing` 모듈 |
|---|---|---|
| 테이블 | `route`, `stop` | `route_plan`, `route_plan_stop` |
| tenant 참조 | `@ManyToOne` FK | **FK 없는 평문 `Long` 컬럼** |
| 순서 의미 | `Stop.seq` — 고정 노선의 정류장 순서 | 매 운행마다 엔진이 새로 계산한 정차 순서 |
| 정원 개념 | `Route.assignCapacity` — **경고 표시 전용** | `Bus.seatCapacity` — **실제 배정·거부 판단** |

- 두 모듈이 실제로 겹치는 지점은 **`PICKUP` 방향 승차 좌표 한 군데**뿐이다: `Student.boardingStop` 이 `route` 모듈의 `Stop` 이고, routing 이 그 `Stop` 의 lat/lng 만 읽어 쓴다. `DROPOFF` 는 `Student.dropoffLat/Lng` 를 쓰므로 route 모듈과 무관하다.
- **`Stop.seq` 는 routing 이 쓰지 않는다** — 순서는 엔진이 다시 계산한다.
- ⚠ **정원이 두 갈래로 이원화돼 있고 서로 동기화되지 않는다.** `Route.assignCapacity` 를 아무리 조정해도 자동 배차 결과는 바뀌지 않는다(배차는 `seatCapacity` 만 본다).
- ⚠ 한 버스가 `Bus.route`(고정 노선)와 `RoutePlan`(당일 계획) **두 개의 순서를 동시에 가질 수 있으며, 둘의 정합성을 맞추는 코드가 없다.**

---

## 8. 버스 (Bus) — `/api/buses`

클래스 레벨 `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN','PLATFORM_ADMIN')")` 가 걸려 있고, `/me` 만 메서드 레벨로 덮어쓴다.

**공통 응답 DTO** — `BusResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| id / tenantId | Long | |
| name | String | "3호차" |
| plateNumber | String | 차량번호. nullable |
| seatCapacity | int | 물리 좌석 수 |
| assignCapacity | Integer | 운행 노선의 배정 정원. **노선이 없으면 null** |
| onboard | int | ⚠ **"현재 탑승 인원"이 아니라 이 버스에 배정된 학생 수**다 |
| overCapacity | boolean | `assignCapacity != null && onboard > assignCapacity`. 노선이 없으면 항상 false |
| driverId / driverName | Long / String | nullable |
| routeId / routeName | Long / String | nullable |
| insuranceExpiry | LocalDate | nullable |

### `GET /api/buses/me` — 내 담당 버스(기사)  ✅프론트 사용

기사 앱이 자기 `busId` 를 알아내는 진입점이다.

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('DRIVER')")` — 메서드 레벨이 클래스 레벨 관리자 규칙을 **덮어쓴다**. 즉 **관리자는 이 API 를 호출할 수 없다** |
| 테넌트 격리 | TenantGuard 미사용. JWT `userId` 로 조회하므로 구조적으로 본인 것만 나온다 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `BusResponse`

**주의**
- ⚠ **담당 버스가 여러 대여도 id 오름차순 첫 1대만 반환한다.** 다중 배차 기사는 지원되지 않는다.
- 담당 버스가 없으면 `404 "담당 버스가 없습니다"`.

### `GET /api/buses` — 학원 버스 목록  ✅프론트 사용

| | |
|---|---|
| 권한 | 클래스 레벨 `hasAnyRole('ACADEMY_ADMIN','PLATFORM_ADMIN')` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택)
**응답** `200` — `List<BusResponse>`

**주의**
- `onboard` 가 버스마다 별도 쿼리라 **N+1** 이다.
- 관제 화면은 이 목록과 `GET /api/locations/buses` 를 병합해 "위치 미보고" 버스를 구분한다.

### `GET /api/buses/{id}` — 버스 상세  ⬜프론트 미사용

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, bus.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수)

**응답** `200` — `BusDetailResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| bus | BusResponse | 요약 정보 |
| roster | List\<RosterEntry\> | `RosterEntry(studentId, name)` — 배정 학생 명단 |

### `POST /api/buses` — 버스 생성  ⬜프론트 미사용

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, req.tenantId)`. `routeId` 는 같은 학원 소속인지 검증 |
| 구현 상태 | ⚠ 부분 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| tenantId | Long | | 생략 시 요청자 소속 학원 |
| name | String | ✔ | `@NotBlank` |
| plateNumber | String | | 검증 없음 |
| seatCapacity | int | ✔ | `@Positive` |
| driverId | Long | | 담당 기사 userId |
| routeId | Long | | 운행 노선 |
| insuranceExpiry | LocalDate | | |

**응답** `200` — `BusResponse`

**주의**
- ⚠ **`driverId` 는 존재 여부만 확인하고 역할·테넌트를 검증하지 않는다.** 학부모 계정이나 타 학원 기사의 userId 를 담당 기사로 지정할 수 있다.
- `routeId` 는 반대로 소속 학원 일치까지 검증하며, 다르면 `400`.

### `PATCH /api/buses/{id}/assignment` — 배차 변경  ⬜프론트 미사용

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | 버스 로드 시 TenantGuard, `routeId` 는 버스 소속 학원과 일치 검증 |
| 구현 상태 | ⚠ 부분 |

**요청** — path `id: Long`(필수)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| driverId | Long | | 검증 애너테이션 없음 |
| routeId | Long | | 검증 애너테이션 없음 |

**응답** `200` — `BusResponse`

**주의**
- ⚠ **null 은 "변경 없음"으로 해석된다 — 배정을 해제할 수단이 없다.** 기사를 떼려면 다른 기사로 바꾸는 수밖에 없다.
- 둘 다 null 이면 아무것도 바뀌지 않고 현재 상태를 그대로 반환한다(에러 아님).
- ⚠ `driverId` 역할·테넌트 미검증은 생성과 동일하다.

---

## 9. 학생 (Student) — `/api/students`

클래스 레벨 `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN','PLATFORM_ADMIN')")` — **전 메서드 관리자 전용**이며 오버라이드가 없다.

**공통 DTO**

`StudentResponse(id, tenantId, name, userId, assignedBusId, boardingStopId, dropoffAddress, dropoffLat, dropoffLng)`
`StudentDetailResponse(student: StudentResponse, assignedBusName, boardingStopName, guardians: List<GuardianEntry>)`
`GuardianEntry(guardianUserId, name, relation)`

### `GET /api/students` — 학원 학생 목록  ✅프론트 사용

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택)
**응답** `200` — `List<StudentResponse>`

**주의** — 페이징 없음(전건 반환). 프론트는 배차 화면에서 학생 이름 사전(directory)으로 쓴다.

### `POST /api/students` — 학생 등록  ⬜프론트 미사용

배정(버스·정류장)과 보호자 연결을 한 번에 처리한다.

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, req.tenantId)`. `assignedBusId`·`boardingStopId` 는 소속 학원 일치 검증(불일치 시 `400`) |
| 구현 상태 | ⚠ 부분 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| tenantId | Long | | 생략 시 요청자 소속 학원 |
| name | String | ✔ | `@NotBlank` |
| userId | Long | | 학생 로그인 계정 id |
| assignedBusId | Long | | |
| boardingStopId | Long | | 기본 승차 정류장 |
| guardians | List\<GuardianLink\> | | `GuardianLink(guardianUserId: Long ✔, relation: String)` |

**응답** `200` — `StudentDetailResponse`

**주의**
- ⚠ **`userId` 는 존재 확인조차 하지 않고 그대로 저장된다.** DB 에도 `student.user_id` FK 제약이 없어, 없는 userId 를 넣어도 통과한다.
- ⚠ `guardians[].guardianUserId` 는 존재 여부만 확인하고 **역할이 `PARENT` 인지, 같은 학원인지 검증하지 않는다.**

### `GET /api/students/{id}` — 학생 상세  ⬜프론트 미사용

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, student.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수)
**응답** `200` — `StudentDetailResponse` (버스·정류장 이름, 보호자 포함)

### `PATCH /api/students/{id}/assignment` — 재배정  ⬜프론트 미사용

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | 학생 접근 검증 후, 버스·정류장은 **학생의 tenantId 기준**으로 일치 검증 |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| assignedBusId | Long | | 검증 애너테이션 없음 |
| boardingStopId | Long | | 검증 애너테이션 없음 |

**응답** `200` — `StudentResponse`

**주의**
- ⚠ **null 은 "변경 없음" — 배정 해제 수단이 없다**(버스 API 와 동일한 한계).
- 실제로 값이 바뀐 경우에만 `StudentAssignmentChangedEvent` 를 발행해 routing 이 국소 재계산한다.
- ⚠ 그 이벤트의 `serviceDate` 가 **항상 오늘**이라, 미래 날짜로 만들어 둔 계획은 재계산되지 않는다.

### `PATCH /api/students/{id}/dropoff` — 하차지 설정  ⬜프론트 미사용

`DROPOFF` 방향 노선 계산이 이 좌표를 쓴다.

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, student.tenantId)` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| dropoffAddress | String | | 검증 없음 |
| dropoffLat | Double | ✔ | `@NotNull`. **범위 검증 없음** |
| dropoffLng | Double | ✔ | `@NotNull`. **범위 검증 없음** |

**응답** `200` — `StudentResponse`

**주의** — 배정 버스가 있을 때만 `StudentDropoffChangedEvent` 를 발행한다(→ routing replan). 하차 좌표가 없는 학생은 `DROPOFF` 자동 배차에서 `excludedStudentNames` 로 빠진다(6장).

### `POST /api/students/{id}/guardians` — 보호자 연결 추가  ⬜프론트 미사용

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | 학생 쪽만 검증(`TenantGuard`). **보호자 쪽은 검증 없음 ⚠** |
| 구현 상태 | ⚠ 부분 |

**요청** — path `id: Long`(학생 id, 필수)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| guardianUserId | Long | ✔ | `@NotNull` |
| relation | String | | "모"/"부"/"조부" 등. 검증 없음 |

**응답** `200` — `StudentDetailResponse`

**주의**
- ⚠ 보호자 user 는 **존재 여부만** 확인한다 — 역할이 `PARENT` 인지, 같은 학원인지 검증하지 않는다. **타 학원 계정을 자녀 보호자로 연결하면 그 계정이 `/children` 계열 API 로 학생 위치·승하차 이력을 볼 수 있게 된다.**
- 애플리케이션 레벨 중복 연결 방지가 없다(DB unique 제약 `(student_id, guardian_id)` 이 최종 방어이며, 위반 시 `500` 으로 나온다).

---

## 10. 학원 (Tenant) — `/api/tenants`

**이 컨트롤러만 `TenantGuard` 를 쓰지 않는다** — 테넌트 자체가 대상 리소스이고, 대부분 플랫폼 관리자 전용이라 **역할이 곧 격리 경계**다.

**공통 응답 DTO** — `TenantResponse(id: Long, name: String, lat: Double, lng: Double)`
`lat`/`lng` 는 학원 위치(depot)이며 routing 노선 계산의 기준점이다.

### `GET /api/tenants` — 전체 학원 목록  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` |
| 테넌트 격리 | 해당 없음 — `findAll()` 전체 반환. 역할 자체가 경계 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `List<TenantResponse>`

**주의** — 프론트는 플랫폼 관리자의 **학원 선택 드롭다운**을 이 API 로 채운다. 페이징 없음.

### `POST /api/tenants` — 학원 생성  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` |
| 테넌트 격리 | 해당 없음 (`AuthUser` 를 받지 않는다) |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| name | String | ✔ | `@NotBlank` |
| lat | Double | | 학원 depot 위도. 검증 없음 |
| lng | Double | | 학원 depot 경도. 검증 없음 |

**응답** `200` — `TenantResponse`

**주의**
- 이름 중복 시 `409`.
- ⚠ **lat/lng 를 생략하면 depot 미설정 상태가 되고, 그 학원은 노선 계획 생성이 전부 `400 "학원 위치(depot)가 설정되지 않았습니다"` 로 실패한다.** 학원을 만들면 좌표를 반드시 채워라.

### `GET /api/tenants/{id}` — 학원 상세  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | TenantGuard 대신 인라인 검사 — 플랫폼 관리자가 아니면서 소속도 아니면 `403` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수)
**응답** `200` — `TenantResponse`

### `PATCH /api/tenants/{id}/location` — 학원 위치(depot) 설정  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` |
| 테넌트 격리 | 해당 없음 (`AuthUser` 를 받지 않는다) — 서비스 계층 검증도 없음 |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| lat | Double | ✔ | `@NotNull`. **범위 검증 없음** |
| lng | Double | ✔ | `@NotNull`. **범위 검증 없음** |

**응답** `200` — `TenantResponse`

**주의**
- ⚠ **학원 관리자는 자기 학원의 depot 도 설정할 수 없다.** 플랫폼 관리자만 가능하므로, 신규 학원 온보딩은 플랫폼 관리자를 거쳐야 한다.

---

## 11. 회원 (Member) — `/api/members`

클래스 레벨 `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN','PLATFORM_ADMIN')")` — 이 컨트롤러만 클래스 단위로 권한을 걸고 메서드에는 애너테이션이 없다.

**공통 응답 DTO** — `MemberResponse(userId, email, name, role, tenantId)`
- `tenantId` 는 null 가능(플랫폼 관리자 멤버십은 tenant 가 null).
- **비밀번호는 응답에 포함되지 않는다.**

### `POST /api/members` — 구성원 등록  ⬜프론트 미사용

계정 생성 + 학원·역할 부여를 한 트랜잭션에서 처리한다. **관리자가 계정을 만드는 정식 경로**다(`/api/auth/signup` 이 아니다).

| | |
|---|---|
| 권한 | 클래스 레벨 `hasAnyRole('ACADEMY_ADMIN','PLATFORM_ADMIN')` |
| 테넌트 격리 | `TenantGuard.resolveTenantId(admin, req.tenantId)` — 학원 관리자는 **남의 학원에 계정을 심을 수 없다** |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| email | String | ✔ | `@NotBlank @Email` |
| password | String | ✔ | `@NotBlank` — **길이·복잡도 제약 없음** |
| name | String | ✔ | `@NotBlank` |
| phone | String | | 검증 없음 |
| tenantId | Long | | 생략 시 요청자 소속 학원 |
| role | Role | ✔ | `@NotNull`. **`PLATFORM_ADMIN` 은 불가** |

**응답** `200` — `MemberResponse`

**처리 순서와 실패 코드**
1. `role == PLATFORM_ADMIN` → `400 "플랫폼 관리자는 이 경로로 등록할 수 없습니다"`
2. TenantGuard 통과
3. 학원 없음 → `404 "학원을 찾을 수 없습니다"`
4. 이메일 중복 → `409 DUPLICATE_EMAIL`
5. BCrypt 해싱 후 `app_user` + `user_tenant_role` 저장(실패 시 함께 롤백)

### `GET /api/members` — 구성원 목록  ⬜프론트 미사용

| | |
|---|---|
| 권한 | 클래스 레벨 관리자 전용 |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| tenantId | Long | | TenantGuard 규칙 |
| role | Role | | 지정 시 해당 역할만 |

**응답** `200` — `List<MemberResponse>`

**주의**
- ⚠ **구성원 CRUD 중 C·R 만 있다.** 수정(U)·삭제(D) 엔드포인트가 없어 **기사 퇴사 처리, 역할 변경, 비밀번호 재설정을 API 로 할 수 없다.** DB 직접 수정이 유일한 방법이다.

---

## 12. 알림 (Notification) — `/api/notifications`

**조회 전용 컨트롤러다. 발송 API 가 없다.** 알림은 다른 모듈(rideevent·sos·routing·drivesession 등)이 도메인 이벤트를 발행하면 `NotificationCommandService` 가 자동으로 만든다.

**공통 응답 DTO** — `NotificationResponse(id, tenantId, studentId, type, message, createdAt)`
`type` 은 `NotificationType` 10종(1.7 참조).

### `GET /api/notifications` — 학원 알림 이력(관리자)  ✅프론트 사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택, TenantGuard 규칙)
**응답** `200` — `List<NotificationResponse>`, `createdAt` **내림차순**

**주의** — 기간·타입 필터와 페이징이 없다. 알림은 계속 누적되므로 응답이 무한히 커진다.

### `GET /api/notifications/children` — 자녀 알림함(학부모)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PARENT')")` |
| 테넌트 격리 | tenantId 검증 없음. **`student_guardian` 관계가 격리 경계**. 자녀가 없으면 빈 리스트 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `List<NotificationResponse>`, `createdAt` 내림차순 (형제자매 알림이 합쳐서 나온다)

### 12.x 알림 멱등(`dedupKey`) — 참고

같은 상황에서 알림이 두 번 나가지 않도록 **2단 방어**가 들어가 있다.

1. 저장 전에 `existsByDedupKey` 로 선(先) 체크 → 있으면 조용히 return
2. DB `notification_log.dedup_key` **unique 제약**이 최종 방어. 동시 틱 경쟁으로 제약 위반이 나면 예외를 삼키고 debug 로그만 남긴다
3. 통과한 건만 등록된 모든 `NotificationSender`(로그 + WebSocket)로 fan-out 한다

덕분에 `ApproachNoShowScheduler`·`SosEscalationScheduler` 처럼 **매 주기 전체를 다시 훑는 스케줄러**가 있어도 학생당 1회만 발송된다.
※ 미확인: 각 호출부가 실제로 조합하는 dedupKey 문자열 포맷(규칙은 "유형+학생+대상일자+정류장/단계").

---

## 13. SOS — `/api/sos-events`

**프론트에 SOS 화면이 하나도 없다 — 6개 엔드포인트 전부 미사용이다.**

**공통 응답 DTO** — `SosEventResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| id / tenantId / studentId | Long | |
| status | SosStatus | `OPEN` / `ACKNOWLEDGED` / `RESOLVED` |
| lat / lng | Double | nullable |
| occurredAt | LocalDateTime | |
| acknowledgedBy / acknowledgedAt | Long / LocalDateTime | nullable |
| resolvedBy / resolvedAt | Long / LocalDateTime | nullable |

### `POST /api/sos-events` — SOS 발신(학생)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('STUDENT')")` |
| 테넌트 격리 | 요청에 tenantId 가 없다. JWT `userId` → 본인 Student → 그 학생의 tenantId 를 서버가 박는다. **위조 불가** |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| lat | Double | | ⚠ 검증 애너테이션 없음 |
| lng | Double | | ⚠ 검증 애너테이션 없음 |

**응답** `200` — `SosEventResponse` (`status=OPEN`)

**주의**
- ⚠ **`@Valid` 는 붙어 있지만 DTO 필드에 제약이 하나도 없다.** 좌표를 생략하거나 null 로 보내도 통과하고, **좌표 없는 SOS 가 그대로 저장된다.** 긴급 상황인데 위치를 모르는 기록이 남을 수 있으니 클라이언트가 반드시 채워 보내야 한다.
- 학생 레코드가 없는 계정이면 `404 "학생 정보를 찾을 수 없습니다"`.
- 저장 후 `SosTriggeredEvent` → Kafka → 알림 파이프라인.

### `PATCH /api/sos-events/{id}/acknowledge` — 확인  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | 대상 레코드의 tenantId 역검사 — 플랫폼 관리자가 아니면서 소속도 아니면 `403` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `SosEventResponse` (`status=ACKNOWLEDGED`, `acknowledgedBy`/`acknowledgedAt` 채워짐)

**주의** — `OPEN` 이 아니면 `409 "이미 확인된 SOS 입니다"`.

### `PATCH /api/sos-events/{id}/resolve` — 종료  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | acknowledge 와 동일(레코드 tenantId 역검사) |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `SosEventResponse` (`status=RESOLVED`)

**주의** — ⚠ **`OPEN` 에서 바로 종료할 수 없다.** `ACKNOWLEDGED` 가 아니면 `409 "확인되지 않은 SOS 는 종료할 수 없습니다"` — 누가 대응했는지 반드시 남기려는 의도적 설계다. 클라이언트는 acknowledge → resolve 2단계 UI 를 만들어야 한다.

### `GET /api/sos-events` — 학원 SOS 이력(관리자)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택)
**응답** `200` — `List<SosEventResponse>`, `occurredAt` 내림차순

**주의** — status 필터가 없다. "미확인(OPEN)만 보기"는 클라이언트가 걸러야 한다.

### `GET /api/sos-events/me` — 본인 SOS 이력(학생)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('STUDENT')")` |
| 테넌트 격리 | JWT `userId` → 본인 studentId 만 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `List<SosEventResponse>`, `occurredAt` 내림차순

### `GET /api/sos-events/children` — 자녀 SOS 이력(학부모)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PARENT')")` |
| 테넌트 격리 | `student_guardian` 관계 기반 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `List<SosEventResponse>`, `occurredAt` 내림차순

### 13.x SOS 에스컬레이션 — 자동 실행됨(API 아님)

- `SosEscalationScheduler` 가 **30초마다**(`app.sos.escalation-check-ms: 30000`) `OPEN` 상태 SOS 를 훑는다.
- 판정 임계값은 **3분** — `OPEN` 상태로 3분 넘게 미확인이면 `SosEscalatedEvent` 를 발행한다. 30초는 "얼마나 자주 다시 훑느냐"일 뿐이다.
- 매 주기 전체를 다시 훑지만 알림 dedupKey 멱등 덕분에 중복 알림은 나가지 않는다.
- 한 틱이 실패해도 예외를 삼키고 warn 로그만 남겨 다음 주기가 계속 돈다.
- **수동 에스컬레이션 API 는 없다.** 스케줄러가 유일한 트리거다.

---

## 14. 결석·출결 예외 (Attendance) — `/api/attendance-exceptions`

**프론트에 화면이 없다 — 5개 전부 미사용이다.** 다만 승인된 결석은 **기사 명단(`GET /api/drive-sessions/{id}/roster`)에서 자동으로 빠지므로**, 이 모듈이 운행 흐름에 실제로 영향을 준다.

**공통 응답 DTO** — `AttendanceExceptionResponse(id, tenantId, studentId, type, targetDate, reason, status, processedBy, createdAt)`
`type` 은 `ABSENCE`(결석·하루) / `LEAVE`(휴원·기간), `status` 는 `PENDING`/`APPROVED`/`REJECTED`.

### `POST /api/attendance-exceptions` — 결석·휴원 신고(학부모)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PARENT')")` |
| 테넌트 격리 | 요청에 tenantId 가 없다. `requireGuardianOf` — 자녀가 아니면 `403 "자녀가 아닙니다"`. tenantId 는 학생 레코드에서 서버가 채운다. **위조 불가** |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| studentId | Long | ✔ | `@NotNull` |
| type | AttendanceType | ✔ | `@NotNull`. `ABSENCE` / `LEAVE` |
| targetDate | LocalDate | ✔ | `@NotNull`. ISO `YYYY-MM-DD` |
| reason | String | | 검증 없음 |

**응답** `200` — `AttendanceExceptionResponse` (`status` 는 **생성자가 `PENDING` 을 강제**하므로 클라이언트가 지정할 수 없다)

**주의** — `LEAVE`(기간 휴원)인데 필드가 `targetDate` 하루뿐이다. 기간 표현 수단이 DTO·엔티티 어디에도 없다.

### `PATCH /api/attendance-exceptions/{id}/approve` — 승인  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | 대상 레코드의 tenantId 역검사 → 아니면 `403` |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `AttendanceExceptionResponse` (`status=APPROVED`, `processedBy` 채워짐)

**주의**
- `PENDING` 이 아니면 `409 "이미 처리된 신청입니다"`. **APPROVED/REJECTED 는 종착 상태로 되돌릴 수 없다.**
- `AttendanceApprovedEvent` 를 발행한다 → ① 학부모 알림 ② routing 국소 재계산(그날 계획이 있으면 그 학생을 뺀 새 `RECOMMENDED` 생성).

### `PATCH /api/attendance-exceptions/{id}/reject` — 반려  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | 승인과 동일 |
| 구현 상태 | ⚠ 부분 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `AttendanceExceptionResponse` (`status=REJECTED`)

**주의**
- ⚠ **반려는 이벤트를 발행하지 않아 학부모에게 결과 통지가 가지 않는다.** 승인과 비대칭이다(15장 schedule 은 반려에도 이벤트를 쏜다). 학부모는 앱에서 목록을 다시 조회해야 반려 사실을 안다.
- `PENDING` 이 아니면 `409`.

### `GET /api/attendance-exceptions` — 학원 신고 이력(관리자)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택)
**응답** `200` — `List<AttendanceExceptionResponse>`, `targetDate` 내림차순

**주의** — ⚠ **status·기간 필터가 없다.** "승인 대기만 보기"는 전건을 받아 클라이언트가 걸러야 한다.

### `GET /api/attendance-exceptions/children` — 자녀 신고 이력(학부모)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PARENT')")` |
| 테넌트 격리 | `student_guardian` 관계 기반 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `List<AttendanceExceptionResponse>`, `targetDate` 내림차순

### 14.x 노출되지 않은 헬퍼 (API 아님)

- `getActiveRoster(busId, date)` — 버스 배정 명단에서 그날 `APPROVED` 결석자를 제외한 **당일 실제 명단**. `GET /api/drive-sessions/{id}/roster` 가 사용한다.
- `getActiveRosterForTenant(tenantId, date)` — 테넌트 전체 활성 로스터. **자동 배차(`auto-assign`)가 사용한다.**

두 메서드에는 별도 엔드포인트가 없다.

---

## 15. 일정 변경 (Schedule) — `/api/schedule-change-requests`

구조가 14장(attendance)과 거의 1:1 대칭이다. **프론트에 화면이 없어 5개 전부 미사용이다.**

**공통 응답 DTO** — `ScheduleChangeRequestResponse(id, tenantId, studentId, requestedDate, requestedTime, reason, status, processedBy, createdAt)`

### `POST /api/schedule-change-requests` — 등하원 시간 변경 요청(학부모)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PARENT')")` |
| 테넌트 격리 | `requireGuardianOf` 로 자녀 확인 후 tenantId 를 학생 레코드에서 서버가 채운다. **위조 불가** |
| 구현 상태 | ✅ 구현 |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| studentId | Long | ✔ | `@NotNull` |
| requestedDate | LocalDate | ✔ | `@NotNull`. ISO `YYYY-MM-DD` |
| requestedTime | LocalTime | ✔ | `@NotNull`. `"17:30:00"` |
| reason | String | | 검증 없음 |

**응답** `200` — `ScheduleChangeRequestResponse` (`status` 는 생성자가 `PENDING` 강제)

### `PATCH /api/schedule-change-requests/{id}/approve` — 승인  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | 대상 레코드의 tenantId 역검사 |
| 구현 상태 | ⚠ 부분 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `ScheduleChangeRequestResponse` (`status=APPROVED`)

**주의**
- **승인하면 노선이 자동으로 다시 계산된다.** status 를 `APPROVED` 로 바꾸고 `ScheduleResultEvent` 를 발행하면(`ScheduleCommandService.java:58-64`), `RoutingReplanEventConsumer.onScheduleResult` 가 `schedule-result` 토픽에서 받아 **`APPROVED` 인 경우에만** `replanForStudent(studentId, requestedDate)` 를 호출한다(`RoutingReplanEventConsumer.java:36-42`). 그 버스·방향에 그날 최신 계획이 있으면 해당 학생을 포함해 재계산한 **새 `RECOMMENDED` `RoutePlan` 이 생성**된다(`RoutingCommandService.java:210-262`). 반려는 명단·경로에 영향이 없다.
- ⚠ 단 **요청 시각 자체(`requestedTime`, 예: 17:30)는 전파되지 않는다.** `replanForStudent` 는 `requestedDate` 만 쓰고 정류장 순서를 재계산할 뿐, 특정 시각을 stop 시간으로 반영하지 않는다. 시각 반영이 필요하면 관리자가 수동으로 조치해야 한다.
- ⚠ 새로 생성된 계획은 `RECOMMENDED` 상태다 — **승인·배포를 따로 거쳐야 기사에게 나간다.** 승인 즉시 운행에 적용되는 것이 아니다.
- `PENDING` 이 아니면 `409 "이미 처리된 요청입니다"`.

### `PATCH /api/schedule-change-requests/{id}/reject` — 반려  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | 승인과 동일 |
| 구현 상태 | ✅ 구현 |

**요청** — path `id: Long`(필수). body 없음
**응답** `200` — `ScheduleChangeRequestResponse` (`status=REJECTED`)

**주의** — attendance 와 달리 **반려도 `ScheduleResultEvent` 를 발행해 학부모에게 결과가 통지된다.**

### `GET /api/schedule-change-requests` — 학원 요청 이력(관리자)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` |
| 테넌트 격리 | `TenantGuard.resolveTenantId` |
| 구현 상태 | ✅ 구현 |

**요청** — query `tenantId: Long`(선택)
**응답** `200` — `List<ScheduleChangeRequestResponse>`, `requestedDate` 내림차순

**주의** — status·기간 필터 없음(14장과 동일).

### `GET /api/schedule-change-requests/children` — 자녀 요청 이력(학부모)  ⬜프론트 미사용

| | |
|---|---|
| 권한 | `@PreAuthorize("hasRole('PARENT')")` |
| 테넌트 격리 | `student_guardian` 관계 기반 |
| 구현 상태 | ✅ 구현 |

**요청** — 없음
**응답** `200` — `List<ScheduleChangeRequestResponse>`, `requestedDate` 내림차순

---

## 16. WebSocket (STOMP)

### 16.1 접속 규약

설정은 `global/config/WebSocketConfig` (`@EnableWebSocketMessageBroker`).

| 항목 | 값 |
|---|---|
| 엔드포인트 | `ws(s)://<host>/ws/location` — **SockJS 미사용, 네이티브 WebSocket** (`.withSockJS()` 호출 없음) |
| 허용 오리진 | `setAllowedOriginPatterns("*")` — **모든 오리진 허용**(REST 의 CORS 화이트리스트와 다르다) |
| 애플리케이션 프리픽스 | `/app` |
| 브로커 프리픽스 | `/topic`, `/queue` — **심플(인메모리) 브로커**. 외부 브로커(RabbitMQ 등) 릴레이가 아니다 |
| user 목적지 프리픽스 | `/user` |
| 하트비트 | 양방향 `app.connection.heartbeat-ms`(기본 10000ms). 전용 `ThreadPoolTaskScheduler`(poolSize 1) |
| 인바운드 인터셉터 | `StompAuthChannelInterceptor` |

> 심플 브로커는 **프로세스 인메모리**다. 백엔드를 2대 이상 띄우면 A 인스턴스에 붙은 구독자는 B 인스턴스가 발행한 메시지를 받지 못한다. 현재 구성은 단일 인스턴스 전제다.

### 16.2 인증 — CONNECT 프레임 1회

`/ws/**` 는 HTTP 레벨에서 `permitAll` 이고, 실제 인증은 **STOMP `CONNECT` 프레임의 네이티브 헤더**에서 이뤄진다(`StompAuthChannelInterceptor.preSend`).

```
CONNECT
Authorization: Bearer <accessToken>
```

| 상황 | 결과 |
|---|---|
| `Authorization` 헤더 없음 | `IllegalArgumentException("WebSocket 연결에는 Authorization 헤더가 필요합니다")` → 연결 거부 |
| access 토큰이 아님(refresh 토큰 등) | 거부 |
| 통과 | `accessor.setUser(AuthUser)` 로 **세션에 principal 을 고정**한다 |

**주의**
- ⚠ **인증은 세션 수립 시 1회뿐이고 이후 프레임을 재검증하지 않는다.** 따라서 **access 토큰이 만료돼도 이미 열린 STOMP 세션은 계속 살아 있다.** REST 는 15분 뒤 401 이 나지만 WebSocket 은 그렇지 않다 — 세션을 끊는 것 외에 강제 만료 수단이 없다.
- 클라이언트는 재연결마다 저장소에서 토큰을 새로 읽어 싣고, CONNECT 실패 시 재발급 후 재시도해야 한다(프론트가 그렇게 구현돼 있다).

### 16.3 클라이언트 → 서버 (인바운드)

인바운드 destination 은 **하나뿐**이다.

#### `SEND /app/location` — 학생 좌표 보고  ⬜프론트 미사용

`@MessageMapping("/location")` (`LocationSocketController`). REST `POST /api/locations` 와 **같은 DTO·같은 서비스 메서드**를 재사용한다.

| | |
|---|---|
| 권한 | ⚠ **없음** — 메서드에 `@PreAuthorize` 가 없다 |
| 테넌트 격리 | REST 와 동일 — tenantId 는 Student 엔티티에서 얻고 payload 에 없다. 위조 불가 |
| 구현 상태 | ✅ 구현 |

**payload** — `LocationReportRequest` (`@Valid`): `lat: Double ✔`, `lng: Double ✔`
**응답** — **없다.** 반환형이 `void` 이고 `@SendTo`/`@SendToUser` 도 없다. 결과는 Kafka `location-updated` → `LocationPushConsumer` 를 거쳐 16.4 의 목적지로 나간다.

**주의**
- ⚠ **역할 검사가 없다.** REST 대응 엔드포인트는 `hasRole('STUDENT')` 로 막히지만 **이 채널은 인증만 되면 어떤 역할이든 호출 가능**하다. 실질적 방어는 "userId 로 Student 를 못 찾으면 404" 하나뿐이다.
- ※ **버스 위치를 보고하는 STOMP 목적지는 존재하지 않는다.** `@MessageMapping` 은 `/location` 하나뿐이며, 기사 위치 보고는 REST `POST /api/locations/bus` 만 가능하다.

### 16.4 서버 → 클라이언트 (아웃바운드) — 전체 4종

| 클라이언트 구독 destination | 서버 발행 형태 | payload | 수신자 | 프론트 |
|---|---|---|---|---|
| `/user/queue/location` | `/user/{userId}/queue/location` | `LocationView` | 학생 본인 · 보호자 전원 · 담당 기사 | ⬜ 미사용 |
| `/topic/tenant/{tenantId}/location` | 동일 | `LocationView` | 해당 학원 관리자 | ⬜ 미사용 |
| `/user/queue/notifications` | `/user/{userId}/queue/notifications` | `NotificationResponse` | 학생 본인 · 보호자 전원 · 담당 기사 | ⚠ 코드는 있으나 **도달 불가** |
| `/topic/tenant/{tenantId}/notifications` | 동일 | `NotificationResponse` | 해당 학원 관리자 | ✅ 사용 |

- 개인 큐는 서버가 `convertAndSendToUser(userId, "/queue/...", payload)` 로 보내므로 실제 프레임 destination 에 userId 가 들어가지만, **클라이언트는 `/user/queue/...` 로 구독**하면 Spring 이 본인 세션으로 라우팅한다.
- payload 는 REST 응답 DTO 를 그대로 쓴다 — `LocationView(studentId, studentName, lat, lng, recordedAt, origin)`, `NotificationResponse(id, tenantId, studentId, type, message, createdAt)`. **1.5 의 날짜 규약(오프셋 없음)이 WebSocket 페이로드에도 동일하게 적용된다.**
- 알림은 **개인 큐 push 와 테넌트 토픽 push 가 동시에** 나간다(같은 알림이 두 채널로 흐른다).

**push 대상 해석 — `PushTargetResolver` 공유**

위치 push(`LocationPushConsumer`)와 알림 push(`WebSocketNotificationSender`)가 **같은 리졸버**를 쓴다. studentId 하나로 `PushTargets(studentName, studentUserId, guardianUserIds, driverUserId, tenantId)` 를 만든다.

- `driverUserId` 는 `student.assignedBus.driver` 에서 나온다 → ⚠ **학생이 버스에 배정돼 있지 않거나 그 버스에 기사가 없으면 null 이고, 기사에게는 아무것도 가지 않는다.**
- `studentUserId` 는 학생 계정이 연결돼 있을 때만 채워진다.

### 16.5 구독 권한 검증

`StompAuthChannelInterceptor` 가 `SUBSCRIBE` 프레임에서 검사하는데, **검사 대상은 `^/topic/tenant/(\d+)/.*` 패턴 하나뿐이다.**

| destination | 검증 |
|---|---|
| `/topic/tenant/{tenantId}/**` | **PLATFORM_ADMIN 이거나, ACADEMY_ADMIN 이면서 해당 tenantId 소속**이어야 통과. 아니면 `IllegalArgumentException("이 학원의 관제 채널을 구독할 권한이 없습니다")` → **STUDENT·PARENT·DRIVER 는 차단된다** |
| `/user/queue/**` (개인 큐) | **별도 검사 없음.** Spring 의 user-destination 라우팅이 CONNECT 때 고정된 principal 기준으로 본인 세션에만 배달하는 것이 보장이다 |
| 그 외 destination | 검사 없음(패턴에 걸리지 않는다) |

즉 **관제 토픽은 역할·소속으로 막고, 개인 큐는 라우팅 구조 자체로 막는다.** 개인 큐에 검사가 없는 것은 누락이 아니라 설계다 — 다른 사람의 개인 큐를 구독할 destination 문자열을 만들 수단이 클라이언트에 없다.

### 16.6 주의

- ⚠ **버스 위치는 WebSocket 으로 나가지 않는다.** `POST /api/locations/bus` 가 이벤트를 발행하지 않기 때문이다(3장). `/topic/tenant/{id}/location` 으로 흐르는 건 **학생 위치뿐**이고, 관리자 관제 지도는 3초 REST 폴링으로 동작한다.
- ⚠ 프론트에 `/user/queue/notifications` 구독 코드가 있지만 이를 쓰는 컨트롤러를 사용하는 화면이 관리자 알림 화면 하나뿐이라, **기사·학생·학부모 경로는 도달 불가능한 죽은 코드**다.
- STOMP 연결/해제는 `LocationSocketEventListener` 가 `SessionConnectedEvent`/`SessionDisconnectEvent` 로 추적해 `LocationSessionRegistry` 에 기록한다. principal 의 userId 로 학생을 역조회하므로 **학생 계정이 아닌 세션은 무시된다.**
- 학생 소켓이 끊기고 `app.connection.loss-grace-seconds`(기본 30초)를 넘기면 `ConnectionLossScheduler`(10초 주기)가 감지해 `StudentConnectionLostEvent` → `CONNECTION_LOST` 알림을 발행한다.

---

## 17. 부록 A — 프론트 사용 현황 매트릭스

역할 표기: `공개`=permitAll · `인증만`=역할 제한 없음 · `관리자`=ACADEMY_ADMIN+PLATFORM_ADMIN · `플랫폼`=PLATFORM_ADMIN 전용

| 경로 | 메서드 | 역할 | 프론트 사용 | 구현 상태 |
|---|---|---|---|---|
| `/api/auth/login` | POST | 공개 | ✅ | ✅ 구현 |
| `/api/auth/refresh` | POST | 공개 | ✅ | ✅ 구현 |
| `/api/auth/signup` | POST | 공개 ⚠ | ⬜ | ✅ 구현 |
| `/api/locations` | POST | STUDENT | ⬜ | ✅ 구현 |
| `/api/locations/me` | GET | STUDENT | ⬜ | ✅ 구현 |
| `/api/locations/children` | GET | PARENT | ⬜ | ✅ 구현 |
| `/api/locations/bus/{busId}` | GET | DRIVER | ⬜ | ✅ 구현 |
| `/api/locations` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/locations/bus` | POST | DRIVER | ✅ | ⚠ 부분 |
| `/api/locations/buses` | GET | 관리자 | ✅ | ✅ 구현 |
| `/api/drive-sessions/start` | POST | DRIVER | ✅ | ✅ 구현 |
| `/api/drive-sessions/{id}/end` | PATCH | DRIVER | ✅ | ✅ 구현 |
| `/api/drive-sessions/{id}/roster` | GET | DRIVER | ✅ | ✅ 구현 |
| `/api/drive-sessions/bus/{busId}` | GET | DRIVER | ✅ | ✅ 구현 |
| `/api/drive-sessions` | GET | 관리자 | ✅ | ✅ 구현 |
| `/api/ride-events` | POST | DRIVER | ✅ | ⚠ 부분 |
| `/api/ride-events/{id}/correction` | POST | DRIVER + 관리자 | ⬜ | ✅ 구현 |
| `/api/ride-events/me` | GET | STUDENT | ⬜ | ✅ 구현 |
| `/api/ride-events/children` | GET | PARENT | ⬜ | ✅ 구현 |
| `/api/ride-events/bus/{busId}` | GET | DRIVER | ✅ | ✅ 구현 |
| `/api/ride-events` | GET | 관리자 | ✅ | ✅ 구현 |
| `/api/route-plans/generate` | POST | 관리자 | ⬜ | ✅ 구현 |
| `/api/route-plans/auto-assign` | POST | 관리자 | ✅ | ✅ 구현 |
| `/api/route-plans/auto-assign/confirm` | POST | 관리자 | ✅ | ✅ 구현 |
| `/api/route-plans/{id}/approve` | PATCH | 관리자 | ⬜ | ✅ 구현 |
| `/api/route-plans/{id}/publish` | PATCH | 관리자 | ⬜ | ✅ 구현 |
| `/api/route-plans/{id}` | GET | 관리자 | ✅ | ✅ 구현 |
| `/api/route-plans` | GET | 관리자 | ✅ | ✅ 구현 |
| `/api/route-plans/driver/{busId}` | GET | DRIVER | ✅ | ✅ 구현 |
| `/api/routes` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/routes/{id}/stops` | GET | **인증만 ⚠** | ⬜ | ✅ 구현 |
| `/api/routes` | POST | 관리자 | ⬜ | ✅ 구현 |
| `/api/routes/{id}/stops` | POST | 관리자 | ⬜ | ✅ 구현 |
| `/api/buses/me` | GET | DRIVER | ✅ | ✅ 구현 |
| `/api/buses` | GET | 관리자 | ✅ | ✅ 구현 |
| `/api/buses/{id}` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/buses` | POST | 관리자 | ⬜ | ⚠ 부분 |
| `/api/buses/{id}/assignment` | PATCH | 관리자 | ⬜ | ⚠ 부분 |
| `/api/students` | POST | 관리자 | ⬜ | ⚠ 부분 |
| `/api/students` | GET | 관리자 | ✅ | ✅ 구현 |
| `/api/students/{id}` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/students/{id}/assignment` | PATCH | 관리자 | ⬜ | ✅ 구현 |
| `/api/students/{id}/dropoff` | PATCH | 관리자 | ⬜ | ✅ 구현 |
| `/api/students/{id}/guardians` | POST | 관리자 | ⬜ | ⚠ 부분 |
| `/api/tenants` | POST | 플랫폼 | ⬜ | ✅ 구현 |
| `/api/tenants` | GET | 플랫폼 | ✅ | ✅ 구현 |
| `/api/tenants/{id}` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/tenants/{id}/location` | PATCH | 플랫폼 | ⬜ | ✅ 구현 |
| `/api/members` | POST | 관리자 | ⬜ | ✅ 구현 |
| `/api/members` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/notifications` | GET | 관리자 | ✅ | ✅ 구현 |
| `/api/notifications/children` | GET | PARENT | ⬜ | ✅ 구현 |
| `/api/sos-events` | POST | STUDENT | ⬜ | ✅ 구현 |
| `/api/sos-events/{id}/acknowledge` | PATCH | 관리자 | ⬜ | ✅ 구현 |
| `/api/sos-events/{id}/resolve` | PATCH | 관리자 | ⬜ | ✅ 구현 |
| `/api/sos-events` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/sos-events/me` | GET | STUDENT | ⬜ | ✅ 구현 |
| `/api/sos-events/children` | GET | PARENT | ⬜ | ✅ 구현 |
| `/api/attendance-exceptions` | POST | PARENT | ⬜ | ✅ 구현 |
| `/api/attendance-exceptions/{id}/approve` | PATCH | 관리자 | ⬜ | ✅ 구현 |
| `/api/attendance-exceptions/{id}/reject` | PATCH | 관리자 | ⬜ | ⚠ 부분 |
| `/api/attendance-exceptions` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/attendance-exceptions/children` | GET | PARENT | ⬜ | ✅ 구현 |
| `/api/schedule-change-requests` | POST | PARENT | ⬜ | ✅ 구현 |
| `/api/schedule-change-requests/{id}/approve` | PATCH | 관리자 | ⬜ | ⚠ 부분 |
| `/api/schedule-change-requests/{id}/reject` | PATCH | 관리자 | ⬜ | ✅ 구현 |
| `/api/schedule-change-requests` | GET | 관리자 | ⬜ | ✅ 구현 |
| `/api/schedule-change-requests/children` | GET | PARENT | ⬜ | ✅ 구현 |
| `/app/location` (STOMP) | SEND | **인증만 ⚠** | ⬜ | ✅ 구현 |

### 집계

| 항목 | 수 |
|---|---|
| REST 엔드포인트 총계 | **68** (컨트롤러 14개) |
| STOMP `@MessageMapping` | 1 |
| 프론트 사용 | **22 (32%)** |
| 프론트 미사용 | **46 (68%)** |
| 구현 상태 ✅ | 60 |
| 구현 상태 ⚠ 부분 | 8 |
| 스텁(고정값만 반환) | **0** |

### 프론트 미사용 46개 = 백엔드 선행 구현분

미사용 46개는 "미완성"이 아니라 **화면이 아직 없어서 호출되지 않을 뿐, 백엔드는 동작하는 기능**이다. 성격별로 나누면:

| 분류 | 개수 | 내용 |
|---|---|---|
| **학생·학부모 전용 기능** | 13 | 학생 위치 보고·조회, 자녀 위치·승하차·알림·SOS 조회, 결석 신고, 일정 변경 요청 — **프론트에 학생·학부모 화면이 0개**라 통째로 잠들어 있다 |
| **관리자 마스터데이터 관리(CRUD)** | 17 | 버스·학생·회원·학원·노선 등록/수정 — 프론트 `ApiClient` 에 `put`/`delete` 메서드조차 없고 쓰기 호출은 7종뿐이다. 관제 화면이 "버스 관리에서 먼저 등록하세요"라고 안내하지만 **그 화면이 존재하지 않는다** |
| **관리자 승인·처리 워크플로** | 9 | SOS 확인/종료·조회, 결석 승인/반려·조회, 일정변경 승인/반려·조회 |
| **routing 수동 경로** | 3 | `generate`·`approve`·`publish` — 프론트는 `auto-assign` + `confirm` 한 갈래만 쓴다 |
| **기타** | 4 | 승하차 정정, 기사용 학생 위치 조회, 관리자 학생 위치 관제, self-signup |

**한눈에 보이는 결론 3가지**

1. **5계층 중 2계층(학생·학부모)의 백엔드는 완성돼 있으나 프론트가 0화면이다.** 이 13개를 붙이는 것이 프론트 확장의 최대 단일 덩어리다.
2. **계정·버스·학생을 앱 안에서 만들 방법이 전혀 없다.** `POST /api/members` 도 `POST /api/auth/signup` 도 프론트가 부르지 않아, 현재 데모는 Flyway 시드 계정에 전적으로 의존한다.
3. **SOS·결석·일정변경 3개 모듈(16개 엔드포인트)이 통째로 화면 없이 대기 중이다.** 특히 SOS 는 기획상 안전 핵심 기능이다.

---

## 18. 부록 B — 테스트 계정 · 시드 데이터

로컬 데모 시드는 `backend/src/main/resources/db/migration-local/V2__seed_data.sql` 이 넣는다. **local 프로파일에서만 적용**되며 prod 에는 들어가지 않는다.

> 로컬 postgres 에는 영속 볼륨이 없다. `docker compose down` 후 `docker compose up -d postgres redis kafka` 하면 Flyway 가 스키마(V1)+시드(V2)를 **매번 새로 구성**한다. `stop`/`start` 는 데이터가 유지되므로, 리셋하려면 반드시 `down` 을 거쳐야 한다.

### 테스트 계정 5개 — 비밀번호는 전부 `password`

| 이메일 | 이름 | 역할 | 소속 학원 |
|---|---|---|---|
| `student@school.com` | 김민준 | `STUDENT` | 한빛학원 |
| `parent@school.com` | 이부모 | `PARENT` | 한빛학원 |
| `driver@school.com` | 박기사 | `DRIVER` | 한빛학원 |
| `admin@school.com` | 한빛관리자 | `ACADEMY_ADMIN` | 한빛학원 |
| `platform@school.com` | 플랫폼관리자 | `PLATFORM_ADMIN` | **NULL (전역 역할)** |

### 시드 데이터 요약

| 테이블 | 건수 | 내용 |
|---|---|---|
| tenant | 3 | 한빛학원(37.5075/127.0355) · 가온에듀(37.4980/127.0276) · 미래코딩(37.5145/127.0300) — 좌표는 routing depot |
| route | 3 | 하원 A노선 · 하원 B노선(한빛), 가온 1노선 |
| stop | 3 | 정류장 A(seq 1) · 정류장 B(seq 2) · 학원(seq 3) — **전부 "하원 A노선" 소속** |
| bus | 3 | 3호차(기사 박기사, 25석) · 1호차(**기사 미배차**, 25석) · 2호차(가온, 기사 없음) |
| student | 6 | 김민준(계정 연결)·이서연·박도윤 → 3호차 / 최지우·정하율·강서준 → 1호차. **하차 좌표는 앞 3명만** |
| student_guardian | 2 | 이부모 → 김민준·이서연 (관계 "모") — 형제자매 데모 |
| user_tenant_role | 5 | 위 계정 5개 |

**시드에 의도적으로 심어둔 데모 조건**

- **"하원 B노선"은 `assignCapacity = 2` 인데 학생 3명이 배정돼 있다** → `GET /api/routes`·`GET /api/buses` 의 `overCapacity = true` 경고를 보기 위한 데이터다.
- **1호차는 기사가 배정돼 있지 않다** → 배차 화면 테스트용. 자동 배차는 기사 유무를 보지 않으므로(6장) 이 버스에도 계획이 만들어진다.
- **강서준 등 하차 좌표가 없는 학생**이 있어 `DROPOFF` 자동 배차 시 `excludedStudentNames` 에 잡힌다.
- **가온에듀**는 플랫폼 관리자의 크로스 테넌트 조회를 시연하기 위한 최소 데이터다.
- `ride_event`·`drive_session`·`route_plan`·`sos_event`·`notification_log`·`attendance_exception`·`schedule_change_request` 에는 **시드가 없다** — 이 테이블들은 API 를 호출해 직접 만들어야 한다.

### 빠른 시나리오 확인 순서

1. `driver@school.com` 로그인 → `GET /api/buses/me` 로 3호차 busId 확보
2. `POST /api/drive-sessions/start` (`direction: DROPOFF`) → `GET /api/drive-sessions/{id}/roster` 로 명단 확인
3. `POST /api/ride-events` 로 학생별 `BOARD` → `ALIGHT` 기록
4. **전원 하차 전에 `PATCH /api/drive-sessions/{id}/end` 를 호출하면 `409` 가 난다** — 잔류 방지 로직 확인 지점
5. `admin@school.com` 로그인 → `POST /api/route-plans/auto-assign` → `POST /api/route-plans/auto-assign/confirm` 으로 배차 확정
6. `GET /api/notifications` 로 위 과정에서 자동 생성된 알림 확인

---

## 부록 C — 스케줄러 주기 한눈에

API 는 아니지만 응답 값의 신선도·알림 발생 시점을 좌우하므로 함께 적는다. **4개 전부 `@Scheduled` 로 무조건 활성**이며(`@EnableScheduling`), 각 tick 이 `try/catch` 로 감싸여 한 번 실패해도 다음 주기가 계속 돈다.

| 스케줄러 | 주기 | 설정 키 | 하는 일 |
|---|---|---|---|
| `LocationSimulationScheduler` | **3초** | `app.location.tick-ms` | 활성 `LocationSource` 를 tick — MVP 의 Mock 좌표 생성기 |
| `ConnectionLossScheduler` | **10초** | `app.connection.loss-check-ms` | 끊긴 지 `loss-grace-seconds`(30초)를 넘긴 학생에 `CONNECTION_LOST` 발행 |
| `ApproachNoShowScheduler` | **15초** | `app.drivesession.approach-check-ms` | `IN_PROGRESS`+`PICKUP` 세션의 `APPROACH`/`NO_SHOW` 판정 |
| `SosEscalationScheduler` | **30초** | `app.sos.escalation-check-ms` | `OPEN` 상태로 **3분** 초과한 SOS 에스컬레이션 |

> ⚠ **분산 환경 안전장치가 없다.** 4개 모두 리더 선출·분산 락 없이 실행되므로 백엔드를 2대 이상 띄우면 같은 판정이 인스턴스마다 돈다. 알림 자체는 `dedupKey` unique 제약으로 중복이 막히지만 **Kafka 로는 중복 이벤트가 그대로 발행된다.** 16.1 의 인메모리 브로커 제약과 함께, 현재 구성은 단일 인스턴스 전제다.

---

## 미확인 항목

- `NotificationCommandService.notify` 호출부들이 실제로 조합하는 `dedupKey` 문자열 포맷
- `routing.max-waypoints` 청킹 경로의 실행 검증(코드만 읽었다). 관련 보고서 `backend/report/2026-07-29-route-optimization-verification.md` 존재
- 각 엔드포인트의 통합/단위 테스트 커버리지
