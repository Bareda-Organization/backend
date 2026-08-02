# 학원 통학버스 통합관리 — 유저플로우

> 이 문서는 **"누가 → 어떤 화면에서 → 무엇을 누르면 → 어떤 API가 호출되고 → 무엇이 바뀌는가"** 를 끝까지 추적한다.
> 화면·API·상태 전이는 전부 **코드 실측** 기준이며, 기획서에만 있고 코드에 없는 것은 B부로 분리했다.
>
> - 자매 문서: [`docs/PRODUCT_SPEC.md`](./PRODUCT_SPEC.md) (무엇을 만드는가) · [`backend/docs/PROJECT_MASTER_PLAN.md`](../backend/docs/PROJECT_MASTER_PLAN.md) (기획 원문 + 진행 추적) · [`backend/docs/reference.md`](../backend/docs/reference.md) (코드 컨벤션)
> - 이 문서는 Claude·개발자가 매 세션 참조하는 **Markdown SoT**다. 사람이 브라우저로 보는 HTML 렌더는 별도로 만든다.

---

## 0. 읽는 법

### 0.1 표기 규칙

| 표기 | 뜻 |
|---|---|
| ✅ | 프론트 화면 + 백엔드 API가 **둘 다** 있고 실제로 이어져 동작한다 |
| ⬜ | 백엔드 API는 있으나 **프론트 화면이 없다** (또는 그 반대) — 사용자가 도달할 수 없다 |
| ⚠ | 동작은 하지만 정확성·안전성에 구멍이 있다 |

### 0.2 부(部) 구분

- **A부 — 구현된 플로우**: 앱을 켜서 손으로 따라갈 수 있는 여정. 현재 화면이 있는 역할은 `DRIVER`(2화면), `ACADEMY_ADMIN`·`PLATFORM_ADMIN`(각 4화면, 두 역할의 화면은 동일) **3개뿐**이다.
- **B부 — 설계됐으나 미구현인 플로우**: 기획서에 있고 백엔드도 대부분 구현돼 있으나, 프론트 화면이 없어 **사용자가 실행할 수 없는** 여정. 각 항목에 "백엔드는 어디까지 돼 있는가"를 실측으로 명시했다.
- **C장 — 갭 요약**: A와 B 사이에서 사용자 여정이 실제로 끊기는 지점.

### 0.3 플로우 표기 형식

각 플로우는 다음 4블록으로 쓴다.

1. **전제** — 이 플로우를 시작할 수 있는 조건(역할·데이터)
2. **단계 표** — `사용자 행동 / 화면 / 호출 API / 결과(어떤 엔티티·상태가 바뀌는가)`
3. **분기·예외** — 에러 응답과 앱의 복구 동작
4. **⚠ 이 플로우의 한계** — 동작하되 신뢰할 수 없는 지점

### 0.4 전 API 공통 규약 (모든 단계에 적용)

- 성공·실패 모두 `{success, data, message}` 단일 래퍼. 성공 시 `message`는 **항상 null**이고, 실패 시 `data`가 null이다.
- **응답에 에러 코드 필드가 없다.** 프론트는 HTTP 상태코드 + 한글 메시지 문자열로만 분기한다.
- **인증 실패(401)만 예외** — Security 필터 체인의 `HttpStatusEntryPoint`가 처리하므로 **body 없는 빈 응답**이다. 래퍼를 타지 않는다.
- 생성(POST)도 **200**이다. 201을 반환하는 엔드포인트가 없다.
- **페이징이 없다.** 모든 목록 API가 전건을 반환하고 정렬은 서버가 고정한다.
- 시각 필드는 전부 `LocalDateTime`/`LocalDate`/`LocalTime` — **오프셋 없는 ISO 문자열**(`"2026-07-29T14:30:00"`)이다. 오프셋을 실을 타입 자체가 아니므로 "오프셋 없는 문자열 = KST 로컬시각"으로 읽어야 한다.
- 테넌트 격리는 3가지 방식뿐이다: ① 관리자 조회 → `TenantGuard.resolveTenantId` ② 기사 → `bus.driver`/`session.driverId` 와 JWT `userId` 대조 ③ 학생·학부모 → `student.user_id`/`student_guardian` 관계. **③은 tenantId를 클라이언트가 보내는 경로 자체가 없어 위조 여지가 없다.**

### 0.5 테스트 계정 (로컬 시드, 비밀번호 전부 `password`)

| 이메일 | 역할 | 소속 | 이 문서에서 도달 가능한 화면 |
|---|---|---|---|
| `driver@school.com` | DRIVER | 한빛학원 | A2·A3 |
| `admin@school.com` | ACADEMY_ADMIN | 한빛학원 | A4~A7 |
| `platform@school.com` | PLATFORM_ADMIN | 전역(tenant=NULL) | A4~A7 (+ 학원 선택) |
| `student@school.com` | STUDENT | 한빛학원 | **없음** — 로그인 후 "준비 중" 배너 |
| `parent@school.com` | PARENT | 한빛학원 | **없음** — 로그인 후 "준비 중" 배너 |

시드는 `db/migration-local/V2__seed_data.sql`(local 프로파일 전용). 학원 3·버스 3·학생 6·정류장 3·보호자연결 2가 들어 있고, **운행 기록·노선계획·알림 시드는 없다** — A2·A5를 직접 돌려야 데이터가 생긴다.

---

## 1. 역할별 진입 지도

로그인 성공 직후 어디에 떨어지는가. 판정은 `RoleRedirect.resolve()` 한 곳에서만 한다.

| 역할 | MVP 지원 | 랜딩 경로 | 접근 가능한 화면 | 하단 탭 |
|---|---|---|---|---|
| `DRIVER` | ✅ | `/driver/route` | 2개 | 오늘의 노선 · 승하차 명단 |
| `ACADEMY_ADMIN` | ✅ | `/admin/monitor` | 4개 | 관제 지도 · 배차 · 노선 · 알림 |
| `PLATFORM_ADMIN` | ✅ | `/admin/monitor` | 4개 (학원 관리자와 **동일 화면**) | 동일 |
| `STUDENT` | ⬜ | `/login` 에 머무름 | **0개** | — |
| `PARENT` | ⬜ | `/login` 에 머무름 | **0개** | — |

**리다이렉트 판정 순서** (`role_redirect.dart:18-60`)

1. 토큰 복원 중(`isRestoring`) → splash 이외 전부 `/` 로 붙잡아 둔다
2. `session == null` → `/login`
3. 로그인은 됐으나 `!role.isSupportedInMvp`(학생·학부모) → `/login` 에 머물게 하고 화면에 "준비 중" 배너를 띄운다
4. 로그인 상태로 `/login` 또는 `/` 에 있으면 → 역할 홈으로
5. 경로 접두어 가드 — `/admin*` 은 `role.isAdmin`, `/driver*` 은 `role == DRIVER`

**두 관리자 역할의 유일한 차이는 tenant 결정 방식이다.**

- `ACADEMY_ADMIN` — JWT 클레임의 `tenantId` 고정. 화면에 읽기 전용 배지만 보인다.
- `PLATFORM_ADMIN` — `GET /api/tenants` 로 전체 학원 목록을 받아 드롭다운에서 선택한다. 단, 관제·알림 화면은 이 선택을 따르지 않는다(→ C7).

**학생·학부모의 실제 화면**: `LoginScreen` 안의 `_UnsupportedRoleNotice` 배너 + "다른 계정으로 로그인" 버튼. 로그인 자체는 성공하고 토큰도 발급되므로, **Swagger나 직접 호출로는 학생·학부모 API를 전부 쓸 수 있다** — 막힌 건 화면뿐이다.

---

# A부. 구현된 플로우

## A1. 인증 — 로그인 · 세션 복원 · 토큰 만료 · 로그아웃

**전제**: 없음. 앱의 모든 여정이 여기서 시작한다.

### A1-1. 로그인 ✅

| # | 사용자 행동 | 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 앱 실행 | `/` (splash) | — | `AuthController.build()` → `restore()`. 저장소의 accessToken을 파싱만 하고 **만료 검사는 하지 않는다** |
| 2 | (토큰 없음) | → `/login` | — | `session == null` 이므로 라우터가 로그인으로 보낸다 |
| 3 | 이메일·비밀번호 입력 후 `로그인` | `/login` | `POST /api/auth/login` | 서버가 `UserTenantRole` 전체를 읽어 `"tenantId:ROLE"` 배열로 인코딩한 access·refresh 토큰 발급. **이후 모든 API의 테넌트 판단은 DB 재조회 없이 이 클레임에서 복원된다** |
| 4 | (자동) | — | — | `SecureTokenStorage.save()` — 두 토큰을 **항상 함께** 저장(refresh 회전 대응). iOS Keychain / Android Keystore / Web은 WebCrypto |
| 5 | (자동) | — | — | `JwtDecoder.toSession()` 이 **토큰 payload를 직접 파싱**해 세션을 만든다 — 사용자 정보 조회 API가 없기 때문. MVP 지원 역할을 우선 선택하고, 없으면 첫 멤버십 |
| 6 | (자동, 역할이 `DRIVER`일 때만) | — | `GET /api/buses/me` | busId를 세션에 얹는다. **실패해도 로그인은 성공** — 이후 명단·노선 화면에서 busId가 없어 빈 상태가 된다 |
| 7 | (자동) | → 역할 홈 | — | 관리자 → `/admin/monitor`, 기사 → `/driver/route` |

### A1-2. 세션 복원 (재실행) ✅

앱을 다시 켜면 3~6단계를 건너뛰고 저장된 accessToken을 파싱해 바로 역할 홈으로 간다. **만료 검사를 하지 않으므로**, 만료된 토큰이면 첫 API 요청이 401을 받고 A1-3으로 넘어간다.

### A1-3. 토큰 만료 · 자동 재발급 ✅

| # | 계기 | 처리 | 결과 |
|---|---|---|---|
| 1 | 아무 API가 401 | `AuthInterceptor.onError` | 저장된 refreshToken으로 `POST /api/auth/refresh` **1회** 시도 |
| 2 | 재발급 성공 | — | 새 토큰 저장 후 **원 요청을 그대로 재시도**. 사용자는 아무것도 못 느낀다 |
| 3 | 재발급 실패 | `onSessionExpired()` | 저장소 clear → `AuthController.state = null` → 라우터가 `/login` 으로 |

- 동시에 401이 여러 개 터져도 `_inFlightRefresh` 로 **재발급을 1회로 합친다.**
- 재시도는 인터셉터가 없는 별도 Dio + `auth_retried` 플래그로 무한 루프를 막는다.
- 재발급 시 서버가 멤버십을 **DB에서 다시 읽어** 재인코딩한다 → 권한이 바뀌었으면 이때 반영된다.
- STOMP도 CONNECT 실패 시 같은 경로로 토큰을 재발급받고 재시도한다.

### A1-4. 로그아웃 ✅

앱바 우측 계정 팝업 → `로그아웃` → `TokenStorage.clear()` + 상태 null → 라우터가 `/login` 으로.

**분기·예외**

- 로그인 실패(이메일 없음 / 비밀번호 불일치) → **둘 다 401 `INVALID_CREDENTIALS`** "이메일 또는 비밀번호가 올바르지 않습니다". 서버가 두 경우를 구분해 알려주지 않는다(계정 존재 여부 노출 방지).
- 학생·학부모 계정으로 로그인 → **성공한다.** 토큰도 발급된다. 다만 라우터가 `/login` 에 붙잡아 두고 "준비 중" 배너를 띄운다.
- `PLATFORM_ADMIN` 은 멤버십의 tenantId가 빈 문자열(`":PLATFORM_ADMIN"`)로 인코딩되며, `JwtDecoder` 가 이 케이스를 따로 처리한다.

**⚠ 이 플로우의 한계**

- **서버 측 로그아웃이 없다.** 백엔드 `AuthController` 는 signup·login·refresh 3개뿐이고 토큰 무효화(블랙리스트·회전 기록) 로직이 없다. 로그아웃해도 **이미 발급된 refresh 토큰은 만료 전까지 계속 유효**하다.
- **회원가입 화면이 없다.** `POST /api/auth/signup` 은 `permitAll` 로 열려 있으나 앱에 진입점이 없다. 그런데 그 API는 요청 body의 `tenantId`·`role` 을 그대로 신뢰하므로, **누구나 임의 학원의 `ACADEMY_ADMIN` 으로 self-signup 할 수 있다** — 화면이 없는 게 오히려 유일한 방어막인 상태다.
- 웹 빌드에서는 XSS 시 토큰 탈취가 가능하다. accessToken 15분 수명이 유일한 완화 장치다.
- 세션의 사용자 이름·소속 학원명은 토큰 클레임에 있는 것만 쓴다 — **사용자 프로필 조회 API 자체가 없다.**

---

## A2. 기사 — 운행 시작부터 종료까지 (주 시나리오)

**전제**: 역할 `DRIVER`, 담당 버스가 배정돼 있어야 한다(`GET /api/buses/me` 가 busId를 준다). 버스에 `driver_id` 가 비어 있으면 이 플로우 전체가 시작되지 않는다.

| # | 사용자 행동 | 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 하단 탭 `승하차 명단` | `/driver/roster` | `GET /api/buses/me` | 담당 버스 1대. **여러 대여도 id 오름차순 첫 번째만** 반환. 없으면 404 "담당 버스가 없습니다" |
| 2 | (자동) | `/driver/roster` | `GET /api/drive-sessions/bus/{busId}` | 해당 버스의 **전체 운행 이력**을 받아 앱이 `IN_PROGRESS` 를 골라낸다. 있으면 3을 건너뛰고 5로 |
| 3 | (자동, 운행 전 화면) | `DriveStartView` | `GET /api/ride-events/bus/{busId}` | 직전 운행 요약(처리 인원)을 계산해 카드에 표시 |
| 4 | 방향 선택(`등원`/`하원`) 후 `운행 시작` | `DriveStartView` | `POST /api/drive-sessions/start`<br>`{busId, direction, serviceDate?}` | **`DriveSession` 신규 생성** — `status=IN_PROGRESS`, `startedAt=now()`, `tenantId`는 요청이 아니라 `bus.tenant` 에서 파생. 같은 날·같은 버스·같은 방향의 `PUBLISHED` `RoutePlan` 이 있으면 `routePlanId` 로 **자동 연결**, 없으면 null로 두고 시작은 허용 |
| 5 | (자동) | `/driver/roster` | `GET /api/drive-sessions/{id}/roster` | 당일 명단. `ActiveRosterReader.forBus` 를 거쳐 **APPROVED 결석자가 제외**된다. `PICKUP` 이면 `student.boardingStop` 의 이름·좌표, `DROPOFF` 면 `dropoffAddress`/`dropoffLat`/`dropoffLng` 를 위치로 준다 |
| 6 | (자동) | `/driver/roster` | `GET /api/ride-events/bus/{busId}?date=` | 오늘 기록을 받아 학생별 현재 상태(미승차/승차완료/하차완료/인계완료)를 계산 |
| 7 | 학생 행의 `승차`/`하차`/`인계` 버튼 | `/driver/roster` | `POST /api/ride-events`<br>`{busId, studentId, type}` | **`RideEvent` 신규 생성** — `source=MANUAL` 하드코딩, `occurredAt=now()` 고정, `stopId` 생략 시 학생의 기본 승차 정류장으로 대체. type별로 `StudentBoardedEvent`/`RideCompletedEvent`/`HandoverCompletedEvent` 발행 → Kafka → 알림 파이프라인 |
| 8 | (자동) | `/driver/roster` | `GET /api/ride-events/bus/{busId}?date=` | 명단 재조회. **낙관적 갱신을 하지 않으므로** 버튼을 누르면 왕복 2회가 끝난 뒤에야 행이 바뀐다 |
| 9 | 상단 "다음 정차" 카드 탭 | → `/driver/route` | — | A3으로 이동(로컬 라우팅) |
| 10 | `운행 종료` → 시트에서 확인 | `EndDriveSheet` | `PATCH /api/drive-sessions/{id}/end` | **`DriveSession.status = COMPLETED`, `endedAt = now()`**. 종료 요약(처리 인원·소요시간)을 보여준다 |

**분기·예외**

- **4에서 409 "이미 진행 중인 운행이 있습니다"** — `(busId, direction, serviceDate)` 조합에 `IN_PROGRESS` 세션이 이미 있을 때. 앱은 에러를 띄우지 않고 **2단계 재조회로 복구**해 진행 중 세션 화면으로 들어간다.
- **10에서 409 "하차 처리되지 않은 학생이 있어 운행을 종료할 수 없습니다"** — **차내 잔류 방지**. 세션 `startedAt`~현재 사이 해당 버스의 승하차 기록을 학생별로 훑어 **마지막이 `BOARD` 인 학생이 하나라도 있으면** 종료를 막는다. 기사는 해당 학생을 하차·인계 처리한 뒤 다시 눌러야 한다.
- **403 "담당 기사만 처리할 수 있습니다"** — 1·2·5·7에서 `bus.driver` 가 JWT `userId` 와 다를 때. 10은 `session.driverId` 기준이라 메시지가 "본인이 시작한 운행만 종료할 수 있습니다"로 다르다.
- **400 "학생과 버스의 학원이 다릅니다"** — 7에서 `student.tenant` ≠ `bus.tenant` 일 때.
- 5에서 학생의 정류장/하차좌표가 없으면 위치 3필드가 모두 null로 온다 → 지도에 찍히지 않는다.

**⚠ 이 플로우의 한계**

- **승하차 순서 검증이 서버에 없다.** 승차 기록 없이 하차를 보내도 서버는 200을 준다. 이미 하차한 학생을 또 하차시켜도 막지 않는다. 앱이 **버튼을 하나만 노출하는 방식으로 막고 있을 뿐**이라, Swagger나 다른 클라이언트에서는 순서가 뒤엉킨 기록을 만들 수 있다. 유일한 간접 방어는 10단계의 잔류 검사다.
- **QR·NFC 경로가 미구현이다.** `RideSource` enum에 `QR`/`NFC` 가 정의돼 있지만 `source` 가 `MANUAL` 로 하드코딩돼 **이 API로는 절대 생성되지 않는다.** 기획서의 "명단 + 사진 + NFC/RFID + QR 병행" 중 실제로 되는 건 기사 수동 하나다.
- **`occurredAt` 이 항상 `now()`다.** 통신이 끊겼다 복구된 뒤 "아까 3시에 탔던 것"으로 기록할 수단이 없다. 과거 시각 기록은 정정 API(→ B5)로만 가능한데 그 화면이 없다.
- **잘못 누르면 되돌릴 수 없다.** `POST /api/ride-events/{id}/correction` 이 구현돼 있으나 프론트가 호출하지 않는다(→ B5).
- **"현재 진행 중인 세션" 전용 API가 없다.** 2단계가 버스 전체 이력을 받아 앱에서 `IN_PROGRESS` 를 찾는 구조라, 운행이 쌓일수록 응답이 커진다. 게다가 이 목록은 **다른 기사가 만든 세션도 포함**한다(필터가 버스 담당 여부뿐).
- **세션 경계를 `driveSessionId` 가 아니라 `busId` + 시간창으로 판별한다.** `RideEvent` 에 세션 FK가 없어서다. "버스당 `IN_PROGRESS` 는 1개"라는 전제에 의존하므로, 그 전제가 깨지면 잔류 검사·요약 집계가 어긋난다.
- **배차를 확정하지 않고 운행을 시작하면 `routePlanId` 가 null이 되고, 그 세션은 APPROACH·NO_SHOW 자동 판정 대상에서 통째로 제외된다**(→ B7, C6).
- 명단은 조회 시점 스냅샷이다. 운행 중 관리자가 결석을 승인해도 **기사가 새로고침하기 전까지 반영되지 않는다.**

---

## A3. 기사 — 오늘의 노선 확인 · 위치 전송

**전제**: 역할 `DRIVER` + busId. 노선이 보이려면 관리자가 **A5(배차 확정)를 이미 마쳐** `PUBLISHED` 계획이 있어야 한다.

| # | 사용자 행동 | 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 하단 탭 `오늘의 노선` | `/driver/route` | `GET /api/route-plans/driver/{busId}?serviceDate=` | 담당 버스의 당일 `PUBLISHED` 계획 목록(등원·하원). 지도에 polyline + 정차 마커, 하단에 정차 시트 |
| 2 | (자동, 병합) | `/driver/route` | — | A2의 명단 상태를 함께 watch해 정차별 진행률(방문/미방문)을 표시 |
| 3 | `등원`/`하원` 세그먼트 | `/driver/route` | — | **로컬 상태만 변경.** 재조회하지 않는다 |
| 4 | 위치 카드의 `전송` 스위치 On | `DriverLocationCard` | (아래 5 반복) | 5초 주기 타이머 시작 |
| 5 | (자동, 5초마다) | — | `POST /api/locations/bus`<br>`{busId, lat, lng}` | 서버가 `InMemoryBusLocationRepository`(`ConcurrentHashMap`)에 **버스당 최신 1건만** 덮어쓴다. `tenantId` 는 `bus.tenant` 에서 파생, `origin` 은 **`GPS` 로 하드코딩** |
| 6 | `Mock`/`실 GPS` 세그먼트 | `DriverLocationCard` | — | 좌표 생성 소스 교체. **기본값은 Mock** |
| 7 | 앱을 백그라운드로 | — | — | 타이머 자동 pause. 복귀하면 resume |
| 8 | 카메라 버튼(확대·축소·내 위치·전체 경로) | `/driver/route` | — | 지도 카메라 로컬 조작. API 호출 없음 |
| 9 | 당겨서 새로고침 | `/driver/route` | `GET /api/route-plans/driver/{busId}` | 1을 재실행 |

**분기·예외**

- **403** — `bus.driver` ≠ JWT `userId` 일 때. 1(조회)·5(보고) 양쪽 모두.
- **노선이 없으면** 빈 상태 화면이 뜬다. 관리자가 배차를 확정하지 않았거나, 확정했더라도 `serviceDate` 가 다르면 비어 있다.
- **Mock 소스는 노선 polyline을 따라 왕복하는 가상 좌표를 만든다**(약 60m/read ≈ 43km/h). 따라서 **노선이 없으면 Mock이 예외를 던져 위치 전송 자체가 중단된다.** 실 GPS로 전환하면 노선 없이도 전송된다.
- 5의 응답은 `data = null` 인 빈 성공이다. 서버가 좌표 유효 범위(위경도)를 **검증하지 않는다** — 어떤 숫자든 저장된다.

**⚠ 이 플로우의 한계**

- **Mock이 앱과 서버 양쪽에 있다.** 앱 쪽 기본 좌표 출처가 Mock인 데다, **서버도 독립적으로 버스 좌표를 만들고 있다** — `MockBusLocationSource`(`app.location.bus-mock.enabled=true`)가 `LocationSimulationScheduler` 의 3초 틱에 얹혀 삼각파 보간 좌표를 저장한다. 즉 **기사가 전송을 켜지 않아도 관제 지도에 버스가 움직인다.** 같은 저장소(버스당 최신 1건)를 쓰므로 기사 앱과 서버 Mock이 **서로 덮어쓴다.** 실 GPS로 넘어가려면 앱 토글만이 아니라 서버 설정(`bus-mock.enabled=false` + `bus-gps.enabled=true`)도 함께 꺼야 한다.
- **기사 앱이 보낸 좌표는 `origin` 이 토글과 무관하게 항상 `GPS` 로 저장된다.** 서버 Mock 경로는 `MOCK` 으로 남으므로, 관제 상세 패널의 "출처: 단말/시뮬레이터" 표기는 **"누가 마지막에 썼는가"를 보여줄 뿐 기사 앱의 Mock 여부는 반영하지 못한다.**
- **버스 위치는 서버 재시작 시 전부 소실된다.** Redis 구현이 없고 in-memory 맵이다(학생 위치는 Redis를 쓰지만 TTL 9초).
- **버스 위치 보고는 이벤트를 발행하지 않는다.** 학생 위치는 Kafka → STOMP push로 이어지지만 버스는 그 경로가 없어, 관리자 관제가 **3초 REST 폴링**에 의존한다(→ A4).
- **재배차가 일어나면 `version` 이 올라간 새 계획 행이 생기는데 서버가 방향으로만 정렬해 준다.** 앱이 직접 최신 version을 골라내 방어하고 있다 — 서버 정렬 수정이 정공법이라고 코드 주석이 명시한다.
- **노선 계획 응답에 정류장 이름과 학원(depot) 좌표가 없다.** `RoutePlanStop` 은 `studentId` + 좌표 + ETA뿐이라, 운행 전에는 정차를 "N번 정차"로 표시하고 학원 위치는 **polyline의 첫 점을 학원으로 간주**해 추정한다. 정류장 이름은 A2의 명단에서만 온다.
- ETA는 `세션 startedAt + etaSeconds` 근사다. 실제 출발이 지연되면 전부 밀린다.

---

## A4. 관리자 — 버스 관제

**전제**: 역할 `ACADEMY_ADMIN` 또는 `PLATFORM_ADMIN`. 의미 있는 화면이 되려면 기사가 A3에서 위치 전송을 켜고 있어야 한다.

| # | 사용자 행동 | 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 로그인 직후 / 탭 `관제 지도` | `/admin/monitor` | `GET /api/buses?tenantId=` | 학원 버스 목록(이름·차량번호·좌석·배정인원·담당기사·보험만료) |
| 2 | (자동, 1 직후 순차) | `/admin/monitor` | `GET /api/locations/buses?tenantId=` | 최신 좌표 목록. **좌표가 없는 버스는 서버가 아예 제외**하므로, 앱이 1의 목록과 병합해 "위치 미보고"로 구분한다 |
| 3 | (자동, 3초마다) | `/admin/monitor` | `GET /api/locations/buses?tenantId=` | **위치만** 재조회. 버스 목록은 다시 받지 않는다. **WebSocket이 아니라 폴링이다** |
| 4 | 버스 타일 또는 지도 마커 탭 | `/admin/monitor` | (아래 5~8) | `select(busId)` — 로컬 상태. 우측/하단에 `BusDetailPanel` 이 열린다 |
| 5 | (패널 조립) | `BusDetailPanel` | `GET /api/buses?tenantId=` | 버스 명부(이름 해석) |
| 6 | (패널 조립) | `BusDetailPanel` | `GET /api/drive-sessions?tenantId=` | 학원 전체 운행 이력을 받아 **앱이 busId로 필터**해 현재 세션(있으면 `IN_PROGRESS`)을 찾는다 |
| 7 | (패널 조립) | `BusDetailPanel` | `GET /api/ride-events?tenantId=&from=&to=` | 학원 전체 당일 기록을 받아 **앱이 busId + 세션 시간창으로 필터**해 탑승 현황을 계산 |
| 8 | (패널 조립) | `BusDetailPanel` | `GET /api/route-plans?tenantId=&busId=` | `PUBLISHED` 최신본만 골라 경로를 그린다 |
| 9 | `전체 보기` | `/admin/monitor` | — | `select(null)` — 패널 닫고 전체 fit |
| 10 | `전체 새로고침` | `/admin/monitor` | 1 + 2 | 버스 목록까지 재조회 |
| 11 | 앱을 백그라운드로 | — | — | 폴링 pause. 복귀 시 resume |

**분기·예외**

- **`PLATFORM_ADMIN` 이 `tenantId` 를 생략하면 400 "tenantId가 필요합니다".** 그래서 앱이 폴백값을 넣는데, 그 값이 **하드코딩 `1`(한빛학원)** 이다.
- `ACADEMY_ADMIN` 은 `tenantId` 를 생략하면 서버가 본인 소속으로 채운다. 남의 학원 id를 넣으면 403.
- 버스가 0대면 "버스 관리에서 버스를 먼저 등록하면…" 이라는 빈 상태 안내가 뜬다 — **그 화면은 존재하지 않는다**(→ C1).
- 기사가 위치 전송을 끄면 좌표는 사라지지 않고 **마지막 값이 그대로 남는다**(TTL이 없는 in-memory 맵). 앱은 신선도를 계산해 "오래됨"으로 표시한다.
- **기사가 아무도 앱을 켜지 않아도 버스가 움직인다.** 서버의 `MockBusLocationSource` 가 3초마다 좌표를 만들기 때문이다(→ A3의 한계). 데모에는 편하지만, **"이 마커가 실제 기사 단말인가 서버 시뮬레이터인가"를 화면에서 확신할 수 없다.**

**⚠ 이 플로우의 한계**

- **플랫폼 관리자의 tenant가 화면마다 어긋난다.** 지도·목록은 **항상 `tenantId=1` 고정**인데, 같은 화면의 상세 패널·노선 조회는 드롭다운 선택값(`adminTenantProvider`)을 쓴다. 배차·노선 화면에서 다른 학원을 골라도 **관제 지도만 1번 학원을 계속 보여준다.** 관제 화면에는 학원 선택 UI 자체가 없고 읽기 전용 태그만 있다.
- **`recordedAt` 에 타임존이 없어 절대 시각을 화면에 쓸 수 없다.** 서버가 `LocalDateTime` 을 그대로 직렬화하므로 9시간 어긋난다. 앱은 신선도를 **클라이언트 수신 시각** 기준으로 대체 계산한다 — 즉 "서버가 언제 기록했는지"는 화면에 표시되지 않는다.
- **필터·집계를 전부 클라이언트가 한다.** `GET /api/drive-sessions` 와 `GET /api/ride-events` 에 busId 필터가 없어 학원 전체를 받아 앱에서 거른다. 날짜 단위라 등원·하원 기록이 섞여 오고, 세션 구간 분리도 앱 몫이다. 페이지네이션도 없다.
- **3초 폴링이다.** 버스가 늘어나면 그대로 부하가 된다. STOMP는 알림에만 쓰이고 위치 채널 구독 코드는 프론트에 없다.
- `onboard` 는 "현재 탑승 인원"이 아니라 **배정 인원**이다(`student.assigned_bus_id` 개수). 실제 탑승 인원은 7의 기록을 앱이 집계해 따로 만든다.

---

## A5. 관리자 — 자동 배차 (제안 → 검토 → 확정)

**전제**: 역할 `ACADEMY_ADMIN`/`PLATFORM_ADMIN`. **학원에 depot 좌표(`tenant.lat/lng`)가 설정돼 있어야 하고**, 버스가 1대 이상, 좌표를 가진 학생이 1명 이상 있어야 한다. 이 플로우의 결과물이 A3(기사 노선)의 입력이다.

| # | 사용자 행동 | 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 탭 `배차` | `/admin/dispatch` | `GET /api/buses?tenantId=`<br>`GET /api/students?tenantId=` | 이름 해석용 보조 조회(버스명·학생명) |
| 2 | 방향(`등원`/`하원`)·운행일 선택 | `/admin/dispatch` | — | 로컬 상태 변경 + **이전 제안 폐기**. 서버에 만들어 둔 RECOMMENDED 계획은 지워지지 않고 DB에 남는다 |
| 3 | `제안 받기` | `/admin/dispatch` | `POST /api/route-plans/auto-assign`<br>`{tenantId, direction, serviceDate?}` | 서버가 ① 활성 로스터(APPROVED 결석자 제외) 조회 → ② `SweepAssigner` 로 버스 배정 → ③ `HeuristicRouteEngine` 으로 정차 순서 최적화 → ④ 외부 경로 API로 실거리·ETA 획득 → ⑤ **버스마다 `RoutePlan`(status=`RECOMMENDED`) 생성.** 학생 배정(`student.assigned_bus`)은 **아직 커밋하지 않는다** |
| 4 | 제안 검토 | `/admin/dispatch` | — | 버스별 카드 + 지도 미리보기(polyline·정차 순서). 좌표가 없어 빠진 학생은 `excludedStudentNames` 로 별도 표시 |
| 5 | `이대로 확정하기` | `/admin/dispatch` | `POST /api/route-plans/auto-assign/confirm`<br>`{planIds}` | 한 트랜잭션에서 ① 각 정차 학생에게 **`student.assignBus(bus)` 커밋** → ② `approve()`(→`APPROVED`) → ③ `publish()`(→`PUBLISHED`) → ④ `RoutePlanPublishedEvent` 발행. **이 시점부터 기사 앱(A3)에 노선이 보인다** |
| 6 | `노선 목록에서 보기` | → `/admin/routes` | — | A6으로 이동 |
| 7 | `다른 방향 배차하기` | `/admin/dispatch` | — | `reset()` — 화면 초기화 |

**배차 알고리즘 실측 (3단계의 내부)**

- **버스 배정 = `SweepAssigner`** — depot 기준 학생 좌표의 **방위각으로 정렬**한 뒤, 버스를 **id 오름차순**으로 순회하며 `seatCapacity` 만큼 순차로 잘라 담는다. 반영하는 제약은 **좌석 정원 하나뿐**이다.
- **정차 순서 = `HeuristicRouteEngine`** — sweep 정렬(결정적 초기 순서) → nearest-neighbor → 2-opt(최대 1000패스). 거리는 **Haversine 직선거리**이고 이 단계에서 외부 API를 부르지 않는다.
- **실거리·ETA = `MapRouteClient`** — 순서 확정 후 1회 호출. 기본 provider는 `naver`(NCP Direction 15)이고, 키가 없거나 `routing.provider=osrm` 이면 OSRM 공개 데모 서버로 폴백한다. waypoint가 `routing.max-waypoints`(기본 7)를 넘으면 구간을 나눠 여러 번 호출하고 이어붙인다.
- **방향 처리** — `DROPOFF` 는 `[depot, ...순서]`, `PICKUP` 은 최적 순서를 뒤집어 `[...역순, depot]`. "대칭거리에서는 depot 고정 최적경로를 뒤집어도 총거리가 같다"는 성질에 기대 최적화를 1회만 수행한다.

**분기·예외**

- **버스 0대 → 400 "배차 가능한 버스가 없습니다"**
- **전체 좌석 합계 < 배정 대상 학생 수 → 400** (정원 초과)
- **좌표를 가진 학생 0명 → 200이지만 `plans` 가 비고 `excludedStudentNames` 만 온다.** 에러가 아니라 빈 제안이다.
- **depot(`tenant.lat/lng`) 미설정 → 400 "학원 위치(depot)가 설정되지 않았습니다".** 학원을 만들 때 좌표를 생략하면 이 상태가 된다.
- **5에서 409 → 앱이 "이미 확정됨"으로 처리하고 버튼을 잠근다.** 계획이 이미 `PUBLISHED` 라 상태 전이가 거부된 경우다.
- **학생이 한 명도 배정되지 않은 버스는 계획 자체를 만들지 않는다** — 제안 목록에서 그 버스가 통째로 빠진다.
- `PLATFORM_ADMIN` 은 `tenantId` 필수(400), `ACADEMY_ADMIN` 은 남의 학원 지정 시 403.

**⚠ 이 플로우의 한계**

- **기획서 4장의 최적화 제약이 대부분 반영되지 않는다.** 실제로 반영되는 건 `seatCapacity` 하나다. 미반영: 시간창(등원 도착 마감·하원 출발시각), 버스 간 부하 분산, **형제자매 동일 차량**, 학생별 최대 탑승시간, 최대 도보거리, 기사 배정 여부, 보험 만료, 진행방향 반대편 하차 방지. 앞 버스부터 정원까지 꽉 채우므로 **마지막 버스가 비는 편향이 구조적으로 발생한다.**
- **`Route.assignCapacity`(노선 배정 정원)를 배차 판단에 쓰지 않는다.** 그 값은 경고 표시(`overCapacity`)에만 쓰이고, 실제 배정·거부는 `Bus.seatCapacity` 로만 한다. 두 값은 동기화되지 않는다.
- **개별 승인/배포 UI가 없다.** `PATCH /{id}/approve`·`/publish`·`POST /generate` 가 구현돼 있으나 프론트는 auto-assign + confirm 경로만 쓴다. 즉 **"제안을 일부만 골라 확정"이나 "수동으로 계획 만들기"가 앱에서 불가능**하다.
- **확정을 되돌릴 수 없다.** `PUBLISHED` 이후 상태 전이가 존재하지 않는다(취소·철회 메서드 없음). 잘못 확정하면 다시 배차해 `version` 이 올라간 새 행을 만드는 수밖에 없고, 낡은 계획 행은 계속 남는다.
- **배포 알림이 우회 구현이다.** 알림 파이프라인이 `studentId` 기준이라, `RoutePlanPublishedEvent` 는 **계획의 첫 정차 학생을 대표로 삼아** 알림 1건만 만든다. 게다가 기사에게는 알림 화면이 없어 도달하지 않는다(→ C9).
- **2에서 "이전 제안 폐기"는 화면에서만 일어난다.** 서버의 `RECOMMENDED` 계획은 남아 노선 목록(A6)에 계속 보인다.
- `excludedStudentNames` 로 빠진 학생은 **아무 조치 없이 배차에서 사라진다.** 좌표를 채워 넣을 화면이 없어(→ B6) 앱 안에서는 해결할 방법이 없다.

---

## A6. 관리자 — 노선 계획 조회

**전제**: 역할 `ACADEMY_ADMIN`/`PLATFORM_ADMIN`. A5를 최소 1회 실행해야 볼 게 생긴다.

| # | 사용자 행동 | 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 탭 `노선` | `/admin/routes` | `GET /api/route-plans?tenantId=&busId=` | 계획 목록(`createdAt` 내림차순). 카드에 방향·상태·버전·총거리·총시간 |
| 2 | (자동) | `/admin/routes` | `GET /api/buses?tenantId=` | 버스 이름 해석 |
| 3 | 버스 필터 ChoiceChip | `/admin/routes` | 1 재호출 | **서버 재조회**(클라이언트 필터가 아니다) |
| 4 | 카드 탭 | → `/admin/routes/{id}` | `GET /api/route-plans/{id}` | 같은 위젯 위에 상세 패널. 경로 지도 + 정차 순서(seq·studentId·ETA) |
| 5 | 새로고침 | `/admin/routes` | 1 재호출 | — |
| 6 | (빈 상태) `배차 화면으로` | → `/admin/dispatch` | — | A5로 이동 |

**분기·예외**

- `/admin/routes/:id` 는 목록 라우트의 **자식**이라 같은 위젯을 그린다. 화면이 path parameter를 직접 읽어 상세 패널을 열지, 별도 화면으로 전환하지 않는다.
- 남의 학원 계획 id를 넣으면 403(`ACADEMY_ADMIN`). `PLATFORM_ADMIN` 은 어느 학원이든 통과한다.
- 계획이 0건이면 빈 상태 + A5로 가는 버튼.

**⚠ 이 플로우의 한계**

- **정류장 이름이 없다.** `RoutePlanStop` 은 `studentId` + 좌표 + `etaSeconds` 뿐이라, 정차를 "N번 정차"로만 표시한다. 학생 이름은 별도 조회로 붙여야 한다.
- **상태·기간 필터가 없다.** `DRAFT`/`RECOMMENDED`/`APPROVED`/`PUBLISHED` 와 **모든 version 이 한 목록에 섞여** 내려온다. 재배차를 여러 번 하면 낡은 행이 계속 쌓이고, 어느 게 오늘 실제로 도는 계획인지 화면에서 구분이 어렵다.
- **읽기 전용이다.** 이 화면에서 계획을 승인·배포·삭제·수정할 수 없다.
- 페이지네이션이 없어 전건이 내려온다.

---

## A7. 관리자 — 알림 확인 (이력 + 실시간)

**전제**: 역할 `ACADEMY_ADMIN`/`PLATFORM_ADMIN`. 알림이 생기려면 A2(승하차 기록)나 A5(배포), 또는 B7의 자동 판정이 먼저 일어나야 한다.

| # | 사용자 행동 | 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 탭 `알림` | `/admin/notifications` | `GET /api/notifications?tenantId=` | 학원 알림 이력(`createdAt` 내림차순). 유형·학생·메시지·시각 |
| 2 | (자동) | `/admin/notifications` | `WS /ws/location` (STOMP CONNECT) | CONNECT 프레임의 **`Authorization: Bearer` 네이티브 헤더**로 1회 인증. 재연결마다 저장소에서 토큰을 새로 읽는다 |
| 3 | (자동) | `/admin/notifications` | `SUBSCRIBE /topic/tenant/{tenantId}/notifications` | 실시간 피드 구독. 새로 들어온 항목은 하이라이트 표시 |
| 4 | `새 표시 지우기` | `/admin/notifications` | — | 하이라이트만 해제(읽음 처리가 아니다) |
| 5 | `새로고침` | `/admin/notifications` | 1 + 2 + 3 재실행 | `invalidateSelf()` — **구독까지 재생성**한다 |
| 6 | 연결 상태 칩 탭 | `/admin/notifications` | 5와 동일 | 끊긴 상태에서 수동 재연결 |
| 7 | 다른 탭으로 이동 | — | STOMP DISCONNECT | provider가 autoDispose라 구독 취소 + `gateway.disconnect()` |

**알림이 만들어지는 경로 (서버 측)**

각 모듈이 도메인 이벤트를 발행하면 `NotificationCommandService.notify` 가 ① `dedupKey` 중복 체크 → ② `NotificationLog` 저장(DB unique 제약이 최종 방어) → ③ 등록된 모든 `NotificationSender` 로 fan-out 한다. `WebSocketNotificationSender` 는 **학생 본인·보호자들·담당 기사의 개인 큐(`/user/queue/notifications`) + 학원 토픽(`/topic/tenant/{id}/notifications`)** 네 방향으로 동시에 보낸다.

`NotificationType` 10종: `BOARD_DONE` · `ALIGHT_DONE` · `HANDOVER_DONE` · `APPROACH` · `NO_SHOW` · `SOS` · `SCHEDULE_RESULT` · `CONNECTION_LOST` · `ROUTE_RECOMMENDED` · `ROUTE_PUBLISHED`

**분기·예외**

- **SUBSCRIBE 인가 검사** — `/topic/tenant/{id}/**` 는 `PLATFORM_ADMIN` 이거나 (`ACADEMY_ADMIN` 이면서 해당 학원 소속)이어야 한다. 아니면 "이 학원의 관제 채널을 구독할 권한이 없습니다"로 거부된다. **`STUDENT`·`PARENT`·`DRIVER` 는 이 토픽을 구독할 수 없다.**
- CONNECT에 Authorization 헤더가 없으면 연결 자체가 거부된다. access 토큰이 아니어도 거부.
- 재연결은 **3초 간격, 연속 5회 실패하면 포기**한다. 이후에는 사용자가 6을 눌러야 한다.
- 재연결에 성공하면 등록된 구독을 **전부 재-SUBSCRIBE** 한다.
- `ACADEMY_ADMIN` 은 1에서 `tenantId` 를 생략한다(서버가 채움). `PLATFORM_ADMIN` 은 필수.

**⚠ 이 플로우의 한계**

- **알림 발송 API가 없다.** `NotificationController` 는 조회 2개뿐이고, 발송은 다른 모듈이 서비스를 직접 호출해 트리거한다. 즉 **관리자가 임의 공지를 보낼 수단이 앱에도 API에도 없다.**
- **읽음/안읽음 상태가 없다.** `NotificationLog` 에 그런 컬럼이 없어 4는 클라이언트 하이라이트 해제일 뿐이다. 다시 들어오면 전부 "읽은 것"처럼 보인다.
- **플랫폼 관리자는 알림도 학원 1로 고정된다.** 드롭다운 선택을 따르지 않는다(관제와 같은 하드코딩 문제, → C7).
- **기사·학부모·학생에게 가는 개인 큐(`/user/queue/notifications`)를 구독하는 화면이 하나도 없다.** 구독 코드는 프론트에 있으나 그 코드를 쓰는 화면이 없어 **도달 불가능한 경로**다. 서버는 열심히 보내지만 받는 앱이 없다(→ C9).
- 페이지네이션이 없어 이력이 쌓이면 전건이 그대로 내려온다.

---

# B부. 설계됐으나 미구현인 플로우

여기 있는 플로우는 **기획서에 있고 백엔드도 대부분 구현돼 있으나, 프론트 화면이 없어 사용자가 실행할 수 없다.**
각 항목의 "백엔드 실측" 블록은 *프론트만 붙이면 되는가, 백엔드도 손대야 하는가* 를 판단하기 위한 것이다.

> **B7만 성격이 다르다.** 예외 자동 판정(미승차·근접·SOS 에스컬레이션)은 **미구현이 아니라 구현돼 있고 지금도 자동 실행 중**이다. 사용자가 누르는 플로우가 아니라 서버가 스스로 도는 판정이라 여기 묶었을 뿐이며, B7의 쟁점은 "없다"가 아니라 **"돌지만 제약이 있고, 결과를 볼 화면이 없다"** 이다.

프론트 전체가 호출하는 REST 엔드포인트는 **22개**이고, 백엔드 매핑은 **68개**다 — **사용률 32%.** 나머지 대부분이 이 B부에 해당한다.
참고로 **프론트의 `ApiClient` 에는 `put`·`delete` 메서드가 아예 없다.** 쓰기 호출은 login·refresh·위치보고·승하차·운행시작·운행종료·배차 **7개뿐**이다.

---

## B1. 학부모 — 자녀 등하원 확인 ⬜

기획상 학부모는 이 서비스의 **주 수요자**다(자녀가 탔는지, 어디쯤인지, 무사히 내렸는지). 현재 화면 0개.

**의도된 플로우**

| # | 사용자 행동 | (있어야 할) 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 로그인 | `/parent/home` | `POST /api/auth/login` | 세션 |
| 2 | 홈 진입 | `/parent/home` | `GET /api/ride-events/children?date=` | 자녀(형제자매 포함) 오늘 승하차 기록 |
| 3 | 지도 보기 | `/parent/map` | `GET /api/locations/children` | 자녀 최신 위치 |
| 4 | 알림함 | `/parent/notifications` | `GET /api/notifications/children` | 자녀 관련 알림 이력 |
| 5 | 실시간 수신 | (전역) | `SUBSCRIBE /user/queue/notifications` | 승차완료·하차완료·근접·미승차·SOS push |
| 6 | 신고·요청 | → B3 / B4 | — | — |

**백엔드는 어디까지 돼 있는가 (실측)**

- **학부모 전용 엔드포인트 6개가 전부 구현돼 있다**: `GET /api/ride-events/children`, `GET /api/locations/children`, `GET /api/notifications/children`, `GET /api/attendance-exceptions/children`, `GET /api/schedule-change-requests/children`, `GET /api/sos-events/children`. 여기에 쓰기 2개(B3·B4)가 더 있다.
- 전부 `@PreAuthorize("hasRole('PARENT')")` + **`student_guardian` 관계 기반 격리**다. tenantId를 클라이언트가 보내는 경로가 없어 위조 여지가 없다. 시드에도 형제자매 케이스(이부모 → 김민준·이서연)가 들어 있어 바로 테스트할 수 있다.
- 실시간 push 대상 해석(`PushTargetResolver`)이 이미 보호자를 포함한다 — `WebSocketNotificationSender` 가 모든 보호자의 `/user/queue/notifications` 로 보낸다. **프론트에 그 구독 코드도 이미 있다**(`_FeedScope` 의 `usesPersonalQueue`). 쓰는 화면만 없다.
- **자녀 위치는 학생 앱이 없어도 값이 나온다** — MVP 기본 설정에서 **서버가 스스로 학생 좌표를 만들고 있기 때문**이다. `LocationSimulationScheduler` 가 3초마다 돌며 `MockLocationSource`(`app.location.mock.enabled=true`)가 **배정 버스 + 승차 정류장이 모두 있는 학생**의 좌표를 정류장↔학원 사이 삼각파로 보간해 `ingest(..., origin=MOCK)` 한다. 이후 경로(Redis 저장 → `LocationUpdatedEvent` → Kafka → STOMP push)는 실 GPS와 **완전히 동일**하다. 즉 이 화면은 **붙이는 즉시 움직이는 데이터를 볼 수 있다.**
- ⚠ 단 **좌표를 못 받는 자녀는 응답에서 조용히 제외된다.** Redis TTL이 약 9초(`tick-ms 3000` × 3)라 소스가 멈추면 곧 사라지고, 배정 버스나 승차 정류장이 없는 학생은 애초에 Mock 대상이 아니다 → "자녀 3명인데 응답 1건"이 정상 동작이다. `GET /api/locations/me` 는 좌표가 없으면 404 "아직 위치 정보가 없습니다"를 낸다.
- ⚠ **위치 이력이 없다.** Redis에 학생별 **최신 1건만** 남고 이력 조회 API 자체가 존재하지 않는다. "오늘 아이가 어떤 경로로 갔는지"는 조회할 수 없다.
- ⚠ 기획 7장의 "학부모에게 **자녀 관련 제한 위치만** 제공" 원칙은 API 설계에 반영돼 있다 — 학부모가 버스 전체 위치를 보는 엔드포인트는 없다.

**결론**: **프론트만 붙이면 되는 영역이다.** 위치까지 포함해 서버 쪽 파이프라인이 전부 살아 있으므로(Mock 소스 → Redis → Kafka → STOMP), 화면을 붙이면 곧바로 동작한다. 실 GPS 전환은 `prod` 프로파일에 이미 준비돼 있고 **설정 스위치만 바꾸면 되는 상태**다 — 학생 앱(B2)은 실데이터로 넘어갈 때 필요해진다.

---

## B2. 학생 — 본인 기록 · SOS ⬜

**의도된 플로우**

| # | 사용자 행동 | (있어야 할) 화면 | 호출 API | 결과 |
|---|---|---|---|---|
| 1 | 앱 실행(백그라운드) | (전역) | `POST /api/locations` 또는 STOMP `/app/location` | 본인 좌표 보고 → Redis 저장 → `LocationUpdatedEvent` → Kafka → 학부모·기사·관리자에게 push |
| 2 | 홈 | `/student/home` | `GET /api/ride-events/me?date=` | 오늘 내 승하차 기록 |
| 3 | 내 위치 | `/student/home` | `GET /api/locations/me` | 최신 좌표 1건 |
| 4 | **SOS 버튼** | `/student/sos` | `POST /api/sos-events` | `SosEvent` 생성(`status=OPEN`) → `SosTriggeredEvent` → 학부모·관리자 즉시 알림 |
| 5 | 내 SOS 이력 | `/student/sos` | `GET /api/sos-events/me` | 발신 이력 + 처리 상태 |

**백엔드는 어디까지 돼 있는가 (실측)**

- 학생 전용 엔드포인트: `POST /api/locations`, `GET /api/locations/me`, `GET /api/ride-events/me`, `POST /api/sos-events`, `GET /api/sos-events/me` — **5개 전부 구현**. 격리는 `student.user_id` ↔ JWT `userId` 로, 타인 조회 경로 자체가 없다.
- **위치 보고는 REST와 STOMP 두 경로가 있고 같은 서비스 메서드를 공유한다.** STOMP는 `@MessageMapping("/location")`(전송 목적지 `/app/location`).
- ⚠ **STOMP 경로에는 역할 검사가 없다.** REST는 `hasRole('STUDENT')` 로 막히지만 이 채널은 **인증만 되면 어떤 역할이든 호출 가능**하다(실제로는 `userId` 로 `Student` 를 못 찾으면 404로 실패). 학생 앱을 붙일 때 같이 손봐야 할 지점이다.
- ⚠ **`SosTriggerRequest` 에 검증 애너테이션이 하나도 없다.** `@Valid` 는 붙어 있으나 `lat`/`lng` 에 `@NotNull` 이 없어 **좌표 없이도 SOS가 생성된다** — "어디서 눌렀는지 모르는 SOS"가 정상 저장된다.
- 위치 연결 끊김 감지도 이미 있다: `LocationSessionRegistry` 가 학생별 STOMP 세션을 추적하고, 끊긴 뒤 `loss-grace-seconds`(기본 30초)를 넘기면 `StudentConnectionLostEvent` → `CONNECTION_LOST` 알림.
- 프론트에 **`sos` 모듈이 아예 없다**(`features/` 하위에 디렉터리 자체가 없음).

**결론**: **프론트만 붙이면 되나, 붙이기 전에 STOMP 역할 검사와 SOS 좌표 검증 2건을 백엔드에서 고쳐야 한다.**

---

## B3. 결석 신고 → 승인 → 명단 반영 ⬜

**이 사슬은 백엔드에서 실제로 끝까지 연결돼 있다.** 끊긴 건 양 끝의 화면뿐이다.

**의도된 플로우**

| # | 사용자 | 행동 | (있어야 할) 화면 | 호출 API | 결과 |
|---|---|---|---|---|---|
| 1 | 학부모 | 자녀·유형(결석/휴원)·날짜·사유 입력 후 신고 | `/parent/absence` | `POST /api/attendance-exceptions` | `AttendanceException` 생성 — `status=PENDING` **강제**(클라이언트가 지정 불가), `tenantId` 는 학생 레코드에서 서버가 채움 |
| 2 | 관리자 | 대기 목록 확인 | `/admin/absence` | `GET /api/attendance-exceptions?tenantId=` | 학원 신고 이력(`targetDate` 내림차순) |
| 3 | 관리자 | `승인` | `/admin/absence` | `PATCH /api/attendance-exceptions/{id}/approve` | `status=APPROVED`, `processedBy` 기록 → **`AttendanceApprovedEvent` 발행** |
| 4 | (자동) | — | — | Kafka `attendance-approved` | `RoutingReplanEventConsumer` 가 받아 해당 학생 배정 버스의 **노선 국소 재계산** → `version+1` 인 새 `RECOMMENDED` 계획 생성 |
| 5 | (자동) | — | — | — | 기사 명단(A2-5)의 `ActiveRosterReader.forBus` 가 **그 날짜 APPROVED 결석자를 제외**한다 → 명단에서 자동으로 빠진다 |
| 6 | 학부모 | 결과 확인 | `/parent/absence` | `GET /api/attendance-exceptions/children` | 처리 상태 확인 |

**백엔드는 어디까지 돼 있는가 (실측)**

- **엔드포인트 5개 전부 구현.** `POST`(PARENT) / `approve`·`reject`(ADMIN) / `children`(PARENT) / 목록(ADMIN).
- 격리: 학부모는 `requireGuardianOf` — 남의 자녀 studentId를 body에 넣으면 403 "자녀가 아닙니다". 관리자는 대상 레코드의 tenantId 역검사.
- 상태 가드: `PENDING` 이 아니면 409 "이미 처리된 신청입니다". **`APPROVED`/`REJECTED` 는 종착 상태로 되돌리기가 없다.**
- **4·5의 연결은 실제로 동작한다** — 명단 제외(`ActiveRosterReader.forBus`)는 `DriveSessionQueryService`(기사 명단)와 `RoutingCommandService`(배차)가 둘 다 호출하고 있다.
- ⚠ **반려(`reject`)는 이벤트를 발행하지 않는다.** 승인과 비대칭이라 **반려 시 학부모에게 결과 통지가 가지 않는다.** (같은 구조인 schedule 모듈은 반려에도 이벤트를 쏜다.)
- ⚠ **목록에 status·기간 필터가 없다.** "대기중만 보기"를 서버가 지원하지 않아 전건을 받아 프론트가 걸러야 한다.
- ⚠ replan 조건이 좁다 — **해당 학생 배정 버스의 방향별 최신 계획의 `serviceDate` 가 이벤트 날짜와 같을 때만** 재계산한다. 즉 내일 결석을 오늘 신고하면(오늘자 계획만 있으면) 노선 재계산은 일어나지 않는다. 다만 5의 명단 제외는 날짜 기준이라 정상 작동한다.
- 프론트에 `attendance` 모듈이 없다. 기사 명단 화면이 "결석 신고된 학생은 자동으로 빠집니다"라고 **안내만 하고 있다** — 신고를 넣을 화면이 없으니 실제로는 항상 전원 명단이다.

**결론**: **프론트 2화면(학부모 신고 / 관리자 승인)만 붙이면 사슬이 완성된다.** 백엔드에서 같이 고칠 것은 반려 이벤트 누락 1건.

---

## B4. 등하원 시간 변경 요청 → 승인 ⬜

**의도된 플로우** (구조가 B3과 거의 1:1 대칭이다)

| # | 사용자 | 행동 | (있어야 할) 화면 | 호출 API | 결과 |
|---|---|---|---|---|---|
| 1 | 학부모 | 자녀·날짜·희망시각·사유 입력 | `/parent/schedule` | `POST /api/schedule-change-requests` | `ScheduleChangeRequest` 생성 — `status=PENDING` 강제 |
| 2 | 관리자 | 대기 목록 | `/admin/schedule` | `GET /api/schedule-change-requests?tenantId=` | 요청 이력(`requestedDate` 내림차순) |
| 3 | 관리자 | `승인` / `반려` | `/admin/schedule` | `PATCH /{id}/approve` 또는 `/reject` | 상태 전이 + **양쪽 모두 `ScheduleResultEvent` 발행** → `SCHEDULE_RESULT` 알림 |
| 4 | 학부모 | 결과 확인 | `/parent/schedule` | `GET /api/schedule-change-requests/children` | — |

**백엔드는 어디까지 돼 있는가 (실측)**

- **엔드포인트 5개 전부 구현.** 격리·상태 가드는 B3과 동일 패턴(409 "이미 처리된 요청입니다").
- 반려도 이벤트를 발행한다 — **이 점만은 B3보다 낫다.**
- **승인하면 노선 재계산까지 이어진다 — B3(결석 승인)과 같은 경로다.** `approve()` 가 `ScheduleResultEvent` 를 발행하고(`ScheduleCommandService.java:58-64`), `RoutingReplanEventConsumer.onScheduleResult` 가 `schedule-result` 토픽에서 받아 `APPROVED` 일 때만 `replanForStudent(studentId, requestedDate)` 를 호출한다(`RoutingReplanEventConsumer.java:36-42`). 결과로 새 `RECOMMENDED` `RoutePlan` 이 생성된다.
- ⚠ 다만 **희망 시각 자체(`requestedTime`, 예: 17:30)는 전파되지 않는다.** `replanForStudent` 는 `requestedDate` 만 쓰고 정류장 순서를 다시 계산할 뿐이다. 즉 "그날 명단을 다시 짠다"까지는 되지만 "17:30에 맞춘다"는 안 된다.
- 애초에 **운행 시각을 담는 모델 자체가 없다.** `RoutePlan` 은 `serviceDate` + 정차별 `etaSeconds` 만 갖고 있고, 계획의 출발 시각은 "세션을 시작한 시각"으로 사후 결정된다. 즉 승인된 희망시각을 반영하려면 **모델부터 손봐야 한다.**
- ⚠ 기획 4장의 임시 변동 절차 5단계(신청 → **시스템 정원·노선 영향 자동확인** → 승인/거절 → 기사·동승보호자 전달·확인 → 학부모 최종 안내) 중 **2·4단계가 통째로 없다.** 승인 전 영향 분석도, 기사에게 전달·확인받는 단계도 구현돼 있지 않다.

**결론**: **프론트만 붙여서는 완성되지 않는다.** 화면을 붙이면 "요청하고 승인받는 흉내"까지는 되지만 운행이 실제로 바뀌지 않는다. 시각 모델 도입이 선행돼야 한다.

---

## B5. 승하차 기록 정정 ⬜

**의도된 플로우**

| # | 사용자 | 행동 | (있어야 할) 화면 | 호출 API | 결과 |
|---|---|---|---|---|---|
| 1 | 기사/관리자 | 잘못 기록된 항목 선택 | `/driver/roster` 상세 또는 `/admin/rides` | — | 원본 `RideEvent` id 확보 |
| 2 | 기사/관리자 | 올바른 유형·시각·정류장 입력 후 `정정` | 정정 시트 | `POST /api/ride-events/{id}/correction` | **원본을 덮어쓰지 않고 정정 기록을 새로 만든다** — `source=CORRECTION`, `correctedBy=요청자`, `correctedAt=now`, `originalRef=원본 id` |
| 3 | — | 이력 확인 | `/admin/rides` | `GET /api/ride-events?tenantId=&from=&to=` | 원본과 정정본이 **둘 다** 조회된다(append-only) |

**백엔드는 어디까지 돼 있는가 (실측)**

- **`POST /api/ride-events/{id}/correction` 1개가 구현돼 있다.** 권한은 `hasAnyRole('DRIVER','ACADEMY_ADMIN','PLATFORM_ADMIN')`.
- body 필드: `type`(필수), `occurredAt`·`stopId`(생략 시 **원본 값 승계**), `lat`/`lng`(선택).
- ⚠ **`lat`/`lng` 는 생략해도 원본을 승계하지 않고 그대로 null로 저장된다** — `stopId`·`occurredAt` 의 fallback과 처리가 다르다. 정정하면 좌표가 사라지는 셈이다.
- ⚠ **인가에 결함이 있다.** 검사가 "PLATFORM_ADMIN이거나 `belongsToTenant(원본.tenantId)`" 이므로, **같은 학원 소속이기만 하면 담당이 아닌 버스의 기록도 기사가 정정할 수 있다.**
- ⚠ 정정의 정정(체인)에 제한이 없고, 이미 정정한 원본을 또 정정하는 것도 막지 않는다.
- 프론트는 이 API를 호출하지 않는다. 앱은 "기록은 되돌릴 수 없다"고 **안내만 한다.**

**결론**: **프론트만 붙이면 되나, 인가 검사와 좌표 승계 2건은 같이 고쳐야 한다.** 법정 운행기록의 정확성이 걸린 부분이라 우선순위가 낮지 않다.

---

## B6. 기초 데이터 등록 (버스 · 학생 · 회원 · 학원) ⬜

**이것이 없으면 A부 전체가 시작되지 않는다.** 현재 데이터는 Flyway 로컬 시드로만 존재한다.

**의도된 플로우**

| # | 사용자 | 행동 | (있어야 할) 화면 | 호출 API | 결과 |
|---|---|---|---|---|---|
| 1 | 플랫폼 관리자 | 학원 등록 | `/platform/tenants` | `POST /api/tenants` | `Tenant` 생성. **lat/lng를 생략하면 depot 미설정이 되어 A5 배차가 400으로 실패한다** |
| 2 | 플랫폼 관리자 | 학원 위치 지정 | `/platform/tenants/{id}` | `PATCH /api/tenants/{id}/location` | depot 좌표 설정. **학원 관리자는 자기 학원 depot도 설정할 수 없다**(PLATFORM_ADMIN 전용) |
| 3 | 관리자 | 기사·학부모·학생 계정 생성 | `/admin/members` | `POST /api/members` | `User` + `UserTenantRole` 생성(BCrypt). `PLATFORM_ADMIN` 역할은 이 경로로 등록 불가(400) |
| 4 | 관리자 | 노선·정류장 등록 | `/admin/routes/master` | `POST /api/routes`<br>`POST /api/routes/{id}/stops` | `Route` + `Stop`. 정류장은 학생의 기본 승차지가 된다 |
| 5 | 관리자 | 버스 등록 | `/admin/buses` | `POST /api/buses` | `Bus` 생성(이름·차량번호·좌석수·담당기사·노선·보험만료) |
| 6 | 관리자 | 배차 변경 | `/admin/buses/{id}` | `PATCH /api/buses/{id}/assignment` | 담당 기사·운행 노선 변경 |
| 7 | 관리자 | 학생 등록 | `/admin/students` | `POST /api/students` | `Student` 생성 + 버스·정류장 배정 + 보호자 연결을 한 번에 |
| 8 | 관리자 | 하차지 좌표 설정 | `/admin/students/{id}` | `PATCH /api/students/{id}/dropoff` | `dropoffLat/Lng` — **하원 배차 계산의 입력.** 이게 없으면 그 학생은 A5에서 `excludedStudentNames` 로 빠진다 |
| 9 | 관리자 | 재배정 | `/admin/students/{id}` | `PATCH /api/students/{id}/assignment` | 버스·정류장 변경 → `StudentAssignmentChangedEvent` → 노선 국소 replan |
| 10 | 관리자 | 보호자 연결 | `/admin/students/{id}` | `POST /api/students/{id}/guardians` | `student_guardian` — **B1 학부모 플로우 전체의 전제**다 |

**백엔드는 어디까지 돼 있는가 (실측)**

- **엔드포인트 15개가 전부 구현돼 있다** (tenant 4 · member 2 · bus 5 · student 6 중 조회 제외 쓰기 경로 포함). 스텁은 없다.
- 격리는 대부분 `TenantGuard` 로 잡혀 있다 — 학원 관리자가 남의 학원에 계정·버스·학생을 심을 수 없다.
- ⚠ **`driverId` 검증이 없다.** `POST /api/buses`·`PATCH /{id}/assignment` 는 `driverId` 를 **존재 여부만** 확인하고 **역할이 DRIVER인지도, 같은 학원인지도 검사하지 않는다.** 학부모나 타 학원 기사를 담당 기사로 지정할 수 있다.
- ⚠ **`POST /api/students` 의 `userId`(학생 계정)는 존재 확인조차 없이 그대로 저장된다.** 보호자(`guardianUserId`)는 존재만 확인하고 역할·테넌트 검증이 없으며, 중복 연결 방지도 없다.
- ⚠ **배정 해제 수단이 없다.** 버스·기사·정류장 배정 API가 전부 "null = 변경 없음"으로 해석하므로, 한 번 배정하면 **앱은커녕 API로도 해제할 수 없다.**
- ⚠ **수정·삭제 API가 거의 없다.** `MemberController` 는 생성·조회뿐이라 **기사 퇴사 처리·계정 비활성화가 불가능**하다. 전 모듈 통틀어 `DELETE` 매핑이 0개다.
- ⚠ **`GET /api/routes/{id}/stops` 는 격리 검증이 없다.** `AuthUser` 를 받지 않아 **인증된 아무나 타 학원 정류장 이름·좌표를 읽을 수 있다.**
- ⚠ `POST /api/routes/{id}/stops` 는 `seq` 중복·연속성을 검사하지 않는다.
- 프론트에는 이 화면이 하나도 없고, `ApiClient` 에 `put`·`delete` 메서드조차 없다. 관제 화면의 빈 상태가 **"버스 관리에서 버스를 먼저 등록하면…" 이라고 안내하지만 그 화면이 존재하지 않는다.**

**결론**: **프론트 화면이 가장 많이 필요한 영역이자, 백엔드 검증 결함도 가장 많은 영역이다.** 화면을 붙이면 위 검증 구멍이 곧바로 실사용 경로에 노출된다.

---

## B7. 예외 자동 판정 — 구현돼 자동 실행 중이나 제약이 있다 ✅⚠

**이 장만 성격이 다르다.** 사용자 행동이 아니라 **서버가 스스로 도는 판정**이라 화면 유무와 무관하게 이미 동작한다.

> **먼저 못박아 둔다 — 미승차(NO_SHOW)·근접(APPROACH) 판정과 SOS 에스컬레이션은 전부 `@Scheduled` 로 실제 자동 실행된다.** 알림 멱등도 `dedupKey` unique 제약으로 구현돼 있다. 이걸 "미구현"으로 읽으면 틀린다. 아래의 쟁점은 전부 **동작하는 판정의 제약**이다.

### B7-1. 판정별 실측 상태

`@Scheduled` 는 백엔드 전체에 **4개뿐이고 전부 활성**이다(`@ConditionalOnProperty` 로 끄는 구조가 아니며, 비활성·주석처리된 것이 0개). `@EnableScheduling` 은 `BackendApplication` 에 붙어 있다. 4개 모두 `try/catch` 로 감싸 한 번의 실패가 다음 주기를 막지 않는다.

| 판정 | 기획 임계 | 구현 | 자동 실행 | 주기 |
|---|---|---|---|---|
| **미승차(NO_SHOW)** | 정류소 도착 **+10분** | ✅ | ✅ **돈다** | `ApproachNoShowScheduler` — `app.drivesession.approach-check-ms`, 기본 **15초** |
| **근접(APPROACH)** | 도착 예상 **5분 전** | ✅ | ✅ **돈다** | 위와 **같은 스케줄러** |
| **SOS 에스컬레이션** | 관리자 미확인 **+3분** | ✅ | ✅ **돈다** | `SosEscalationScheduler` — `app.sos.escalation-check-ms`, 기본 **30초** |
| **연결 끊김(CONNECTION_LOST)** | — | ✅ | ✅ **돈다** | `ConnectionLossScheduler` — 기본 **10초** 주기, grace **30초** |
| **위치 시뮬레이션 tick** | — | ✅ | ✅ **돈다** | `LocationSimulationScheduler` — 기본 **3초**. 활성 소스만 tick (→ B7-4) |
| **차내 잔류 방지** | 종료 전 탑승 0 확인 | ✅ | — | 스케줄러가 아니라 **운행 종료 시 동기 차단**(A2-10의 409) |
| **보호자 부재** | 하차위치 도착 → 대기·연락 → 관리자 보고 | ❌ **없음** | — | 이벤트·스케줄러·`NotificationType` 값이 **모두 존재하지 않는다** |
| **연속 N회 관측 게이트** | GPS 오탐 방지 | ❌ **없음** | — | 매 틱 재평가 + `dedupKey` 멱등으로 대체 |

임계값 3개(`NO_SHOW` 10분 / `APPROACH` 5분 / `SOS_ESCALATION` 3분)는 `NotificationThresholds` 에 **코드 하드코딩**돼 있다 — 설정으로 바꿀 수 없다. 값 자체는 기획 7장 표와 일치한다.

### B7-2. 미승차·근접 판정이 실제로 도는 방식

| # | 계기 | 처리 | 결과 |
|---|---|---|---|
| 1 | 15초마다 | `IN_PROGRESS` + **`PICKUP`** 세션만 조회 | 하원(`DROPOFF`)은 학원에서 이미 승차한 상태로 출발하므로 대상이 아니다 |
| 2 | — | **`routePlanId` 가 null인 세션은 건너뛴다** | 계획 없이 시작한 수동 운행은 ETA를 계산할 수 없다 |
| 3 | — | 세션 시작 이후 이 버스에서 `BOARD` 기록이 있는 학생을 제외 | 이미 탄 학생은 판정 대상이 아니다 |
| 4 | — | 정차별 `ETA = session.startedAt + stop.etaSeconds` 로 근사 | 실제 도착 시각이 아니라 **계획 기준 추정치**다 |
| 5 | `ETA-5분 ≤ now < ETA` | `ApproachEvent` 발행 | → `APPROACH` 알림 |
| 6 | `now ≥ ETA+10분` | `NoShowEvent` 발행 | → `NO_SHOW` 알림 |
| 7 | 알림 발송 | `dedupKey` 중복 체크 | 매 틱 조건을 다시 평가하지만 **학생당 최초 1회만** 실제 발송된다 |

멱등은 2단 방어다 — 앱 레벨 `existsByDedupKey` 선체크 + **DB `notification_log.dedup_key` unique 제약**(동시 틱 경쟁 시 예외를 잡아 조용히 무시).

### B7-3. SOS 에스컬레이션

30초마다 `OPEN` 상태로 **3분** 넘게 남아 있는 `SosEvent` 를 훑어 `SosEscalatedEvent` 를 발행한다. 전용 인덱스 `idx_sos_status_occurred (status, occurred_at)` 가 이 쿼리를 정확히 커버한다. **수동 실행 엔드포인트는 없다** — 스케줄러가 유일한 트리거다.

상태머신은 `OPEN → ACKNOWLEDGED → RESOLVED` 단방향이고, **`OPEN` 에서 바로 `RESOLVED` 로 건너뛸 수 없다**(누가 대응했는지 반드시 남기려는 의도, 409로 거부).

### B7-4. ⚠ 이 플로우의 한계 (판정은 돌지만 아래가 제약이다)

**① 판정 범위의 구멍**

- **NO_SHOW 판정이 등원(`PICKUP`)에만 걸려 있다.** `checkApproachAndNoShow()` 가 `IN_PROGRESS` + `PICKUP` 세션만 조회하므로 **하원의 미하차·미인계는 자동 감지되지 않는다.** "하원은 학원에서 이미 승차한 상태로 출발한다"는 이유로 의도된 설계라고 코드 주석이 밝히고 있으나, 결과적으로 **기획 6장의 3대 예외 중 '보호자 부재'가 자동 판정 대상에서 빠지는 원인**이 된다.
- **`RoutePlan` 없이 시작한 수동 운행은 판정에서 통째로 제외된다.** `routePlanId == null` 이면 건너뛴다. A2는 배차를 확정하지 않아도 운행 시작을 허용하므로, **"배차 확정 → 운행 시작" 순서를 지켰을 때만 이 판정이 작동한다.**
- **보호자 부재 플로우가 통째로 없다.** 기획 6장의 "하차위치 도착 → 대기·연락 → 연락 실패 → 관리자 보고 → 재탑승·복귀"에 대응하는 코드가 없다. `HANDOVER` 라는 `RideType` 이 있어 **"인계했다"는 기록은 가능하지만 "인계 못 했다"는 판정은 불가능**하다.

**② 판정 정확도**

- **ETA가 실시간 버스 좌표가 아니라 "세션 시작시각 + 계획 `etaSeconds`" 근사다.** 실제 `BusLocationRepository` 의 좌표를 전혀 반영하지 않으므로, **출발이 지연되면 모든 정차 ETA가 그만큼 밀려 근접·미승차 판정이 통째로 어긋난다.** 버스가 실제로 정류소에 도착했는지와 무관하게 시계만 보고 판정한다.
- 그 `etaSeconds` 자체도 근사다 — `NaverMapRouteClient` 는 leg별 duration을 제공받지 못해 **구간 Haversine 거리 비례로 배분**한다(OSRM은 실제 duration을 준다). 즉 provider에 따라 판정 시점이 달라진다.
- **연속 N회 관측 게이트가 없다.** 기획 7장이 GPS 단발 이상치 오탐 방지를 위해 명시한 조건인데, 매 틱 단발 판정 + dedup 멱등으로만 처리한다. 지금은 시간 기반 판정이라 GPS 이상치의 영향을 받지 않지만, **실 GPS 도착 판정으로 바꾸는 순간 반드시 필요해진다.**

**③ 운영 안전장치**

- **운행 세션을 자동으로 닫는 스케줄러가 없다.** 기사가 종료를 누르지 않으면 세션이 `IN_PROGRESS` 로 영원히 남고, **15초마다 계속 APPROACH/NO_SHOW 스캔 대상이 된다.** 방치된 세션이 쌓일수록 매 틱 비용이 늘고, 다음 날 같은 버스로 운행을 시작하면 A2-4의 409(중복 세션)에 걸릴 수도 있다. ※ 기사가 종료를 누르지 않는 경우의 처리 정책은 코드에서 확인되지 않는다.
- **스케줄러 4개 모두 리더 선출·분산 락이 없다.** 백엔드를 **2대 이상 띄우면 같은 판정이 인스턴스마다 중복 실행**된다. 알림 자체는 `dedupKey` unique 제약이 막아주지만 **Kafka로는 중복 이벤트가 그대로 발행된다.** 현재 리버스 프록시 설정이 이 제약을 명시적으로 경고하고 있다 — 즉 **지금 구조는 백엔드 단일 인스턴스를 전제한다.**
- **Kafka 컨슈머에 실패 처리가 없다.** `@KafkaListener` 어디에도 `errorHandler`·DLT 설정이 없어, 알림 소비 중 예외가 나면 기본 재시도 후 **메시지가 유실된다.** 판정은 정상이었는데 알림만 조용히 사라지는 경우가 생긴다.

**④ 결과를 볼 사람이 없다 (플로우 관점의 핵심)**

- 판정 결과는 `WebSocketNotificationSender` 가 **학생 본인·보호자 전원·담당 기사의 개인 큐(`/user/queue/notifications`) + 학원 토픽** 네 방향으로 정상 발송한다. 그런데 **개인 큐를 구독하는 화면이 하나도 없다** — 학부모·학생 화면은 0개고, 기사에게는 알림 화면 자체가 없다.
- 실제로 사람이 보는 건 **관리자 화면이 구독하는 학원 토픽 하나뿐**이다. 즉 "학생이 안 탔다"는 판정이 **정작 학부모에게 도달하지 않는다.** 기획 7장의 NO_SHOW 대상은 "학부모 + 관리자"인데 절반만 닿는다(→ C6).
- **판정을 사람이 뒤집을 수단도 없다.** "미승차로 찍혔는데 실제로는 탔다"를 정정하는 경로가 알림 쪽에는 없다(승하차 기록 정정은 B5, 그마저 화면이 없다).

---

# C. 플로우 관점 갭 요약

A부와 B부를 겹쳐 놓았을 때 **사용자 여정이 실제로 끊기는 지점**만 모았다. 개별 기능 누락이 아니라 **"여기서 다음으로 갈 수 없다"** 를 기준으로 골랐다.

**심각도 기준**
- **치명** — 이 상태로는 서비스 자체가 성립하지 않는다(데이터를 만들 수 없거나, 핵심 사용자 계층이 통째로 없거나, 안전 기능이 작동 불가)
- **높음** — 다른 플로우가 이미 그 기능을 전제하고 안내까지 하는데 실행 경로가 없다
- **중간** — 동작은 하나 신뢰할 수 없거나, 되돌릴 수 없거나, 화면 간 값이 어긋난다

| # | 갭 | 심각도 | 어디서 끊기는가 |
|---|---|---|---|
| C1 | **기초 데이터를 앱에서 만들 수 없다** | 치명 | A4 관제 빈 상태가 *"버스 관리에서 버스를 먼저 등록하면…"* 이라고 안내하는데 **그 화면이 존재하지 않는다.** 학원·회원·버스·학생·정류장 등록 API 15개가 전부 구현돼 있으나 프론트에는 화면도, `ApiClient.put`/`delete` 메서드조차 없다. → 신규 학원 온보딩이 **Flyway 시드나 Swagger 직접 호출로만 가능**하다 (B6) |
| C2 | **5계층 중 2계층(학생·학부모)의 여정이 통째로 없다** | 치명 | 로그인은 성공하는데 `/login` 에 붙잡혀 "준비 중" 배너만 본다. 학부모·학생 전용 엔드포인트 11개가 유휴 상태다. A2에서 기사가 승하차를 기록하면 알림이 정상 발송되지만 **받을 앱이 없다** (B1·B2) |
| C3 | **SOS를 발신할 수도, 처리할 수도 없다** | 치명 | 에스컬레이션 스케줄러(30초 주기, 3분 임계)는 **실제로 돌고 있는데 `OPEN` 이벤트를 만들 경로가 앱에 없다.** 관리자의 확인·종료 화면도 없어 프론트에 `sos` 모듈 자체가 존재하지 않는다. 기획상 안전 핵심 기능 (B2·B7) |
| C4 | **결석 신고 → 명단 제외 사슬이 양 끝에서 끊긴다** | 높음 | 백엔드는 신고 → 승인 → replan → `ActiveRosterReader.forBus` 제외까지 **실제로 연결돼 있다.** 그런데 A2의 기사 명단 화면이 *"결석 신고된 학생은 자동으로 빠집니다"* 라고 **안내만 하고**, 신고 화면도 승인 화면도 없어 **실제로는 항상 전원 명단**이다 (B3) |
| C5 | **잘못 기록한 승하차를 되돌릴 수 없다** | 높음 | A2-7에서 오조작하면 끝이다. `POST /api/ride-events/{id}/correction` 이 구현돼 있으나 프론트가 호출하지 않고, 앱은 *"되돌릴 수 없다"* 고 안내만 한다. 법정 운행기록의 정확성이 걸린 지점 (B5) |
| C6 | **자동 판정은 도는데 결과를 볼 사람이 앱에 없다** | 높음 | 미승차·근접·SOS 에스컬레이션은 **실제로 자동 실행된다**(15초·30초 주기). 판정 결과도 학생·보호자·기사의 개인 큐로 **정상 발송된다.** 그런데 **그 큐를 구독하는 화면이 하나도 없어**(학부모·학생 0화면, 기사 알림 화면 없음) 실제로 닿는 건 관리자 토픽뿐이다. 기획 7장의 NO_SHOW 대상은 "학부모 + 관리자"인데 **절반만 닿는다** (B7-4④) |
| C6-1 | **그 미승차 판정마저 절반의 세션에만 걸린다** | 높음 | 판정 조건이 `PICKUP` + **`routePlanId != null`** 이다. A2는 배차를 확정하지 않아도 운행 시작을 허용하므로 그 경우 세션 전체가 판정에서 빠진다. 게다가 **하원(`DROPOFF`)은 아예 대상이 아니라** 미하차·미인계가 자동 감지되지 않는다 (A2·B7-4①) |
| C6-2 | **ETA가 실제 버스 위치와 무관하다** | 높음 | 판정 기준이 "세션 시작시각 + 계획 `etaSeconds`" 근사라 **실시간 버스 좌표를 전혀 보지 않는다.** 출발이 15분 늦으면 근접·미승차 판정이 통째로 15분 어긋나는데, **정작 그 버스의 실제 좌표는 서버가 이미 갖고 있다**(A4가 그걸 보여준다). 관제 화면과 판정 로직이 서로 다른 세계를 본다 (A4·B7-4②) |
| C7 | **플랫폼 관리자의 "지금 보고 있는 학원"이 화면마다 다르다** | 중간 | 배차·노선 화면은 드롭다운 선택을 따르지만, **관제 지도와 알림은 하드코딩된 학원 1로 고정**된다. 다른 학원을 골라 배차한 뒤 관제로 넘어가면 여전히 1번 학원을 본다. 관제 화면에는 학원 선택 UI 자체가 없다 (A4·A7) |
| C8 | **확정한 배차를 되돌릴 수 없다** | 중간 | `PUBLISHED` 이후 상태 전이가 존재하지 않는다. 잘못 확정하면 재배차로 `version+1` 새 행을 만드는 수밖에 없고, 낡은 계획은 A6 목록에 계속 쌓인다. 상태·버전 필터가 없어 **어느 게 오늘 도는 계획인지 화면에서 구분이 어렵다** (A5·A6) |
| C9 | **기사가 앱에서 알림을 볼 수 없다** | 중간 | 기사 셸에는 탭이 2개(노선·명단)뿐이다. `/user/queue/notifications` 구독 코드는 프론트에 **이미 있으나 쓰는 화면이 없어 도달 불가능**하다. A5에서 배차를 확정하면 `ROUTE_PUBLISHED` 알림이 담당 기사에게 발송되지만 기사는 알 수 없고, 노선 화면을 직접 새로고침해야 한다 (A5·A7) |
| C10 | **화면의 시각을 신뢰할 수 없다** | 중간 | 서버가 `LocalDateTime` 을 오프셋 없이 직렬화해 클라이언트 해석에 따라 9시간 어긋난다. A4는 이를 우회하려고 신선도를 **클라이언트 수신 시각**으로 대체 계산 중이라, **서버가 실제로 기록한 시각이 화면에 표시되지 않는다.** `spring.jackson.time-zone` 설정으로는 해결되지 않고 타입·컬럼을 바꿔야 한다 |
| C11 | **계정을 만들 정상 경로가 없는데, 비정상 경로는 열려 있다** | 중간 | 회원가입 화면도 관리자 계정 생성 화면도 없다. 그런데 `POST /api/auth/signup` 은 `permitAll` + 요청 body의 `tenantId`·`role` 을 그대로 신뢰하므로 **누구나 임의 학원의 `ACADEMY_ADMIN` 으로 가입할 수 있다.** 화면이 없는 것이 유일한 방어막인 상태다 (A1·B6) |
| C12 | **일정 변경을 승인해도 "희망 시각"은 반영되지 않는다** | 중간 | 승인하면 `replanForStudent` 로 노선 재계산까지는 이어진다(새 `RECOMMENDED` 계획 생성). 그러나 `requestedTime`(예: 17:30)은 쓰이지 않고 `requestedDate` 만 쓴다 — **운행 시각을 담는 모델 자체가 없기 때문**이다. `RoutePlan` 은 `serviceDate` + 정차별 `etaSeconds` 만 갖고 출발 시각은 세션 시작 시점으로 사후 결정된다. 화면만 붙여서는 해결되지 않는다 (B4) |
| C13 | **보호자 부재 예외가 통째로 없다** | 중간 | 기획 6장의 3대 예외 중 미승차·SOS는 구현됐으나 보호자 부재는 이벤트·스케줄러·알림 유형이 **모두 존재하지 않는다.** `HANDOVER` 기록으로 "인계했다"는 남길 수 있지만 "인계 못 했다"는 판정할 수 없다. C6-1(하원이 판정 대상이 아님)과 같은 뿌리다 (B7-4①) |
| C14 | **운행 세션이 방치되면 영원히 스캔된다** | 중간 | 기사가 A2-10(운행 종료)을 누르지 않으면 세션이 `IN_PROGRESS` 로 남고 **15초마다 계속 판정 스캔 대상이 된다.** 시간 경과로 세션을 닫는 스케줄러가 없다. 다음 날 같은 버스·방향으로 시작하려 하면 A2-4의 409(중복 세션)에 걸릴 수도 있다 — 앱은 이 409를 "진행 중 세션 복구"로 처리하므로 **기사가 어제 세션을 오늘 것으로 착각하고 이어받게 된다** (A2·B7-4③) |
| C15 | **버스 마커가 진짜 기사인지 서버 시뮬레이터인지 화면에서 알 수 없다** | 중간 | 서버의 `MockBusLocationSource` 가 3초마다 버스 좌표를 만들고, 기사 앱도 5초마다 같은 저장소에 쓴다. 둘이 **서로 덮어쓰는데** 기사 앱 경로는 `origin` 을 항상 `GPS` 로 박아 넣는다. 데모에서는 "기사가 앱을 안 켜도 버스가 움직여" 편하지만, **실 운행에서는 위치 신뢰도를 판단할 근거가 사라진다** (A3·A4) |
| C16 | **백엔드를 2대 이상 띄우면 판정이 중복된다** | 중간 | 스케줄러 4개 전부 리더 선출·분산 락이 없다. 알림은 `dedupKey` unique 제약이 막지만 **Kafka로는 중복 이벤트가 그대로 발행**된다. 즉 **현재 구조는 백엔드 단일 인스턴스를 전제**하며, 리버스 프록시 설정이 이 제약을 명시적으로 경고하고 있다. 스케일아웃 시점에 반드시 먼저 풀어야 할 매듭 (B7-4③) |

## C-1. 여정별로 본 끊김

```
[관리자 온보딩]  학원 등록 ✗ → 회원 등록 ✗ → 버스 등록 ✗ → 학생 등록 ✗ → 배차 ✅ → 관제 ✅
                 └─────────── C1: 여기서부터 앱으로 못 간다 ───────────┘

[기사 하루]      로그인 ✅ → 노선 확인 ✅ → 운행 시작 ✅ → 승하차 기록 ✅ → 운행 종료 ✅
                                                          └ C5: 오조작 복구 불가
                 └ C9: 배차 변경·배포 알림을 못 받는다

[학부모 하루]    로그인 ✅(화면 없음) → ✗ 전부
                 └───── C2 ─────┘

[안전 사고]      학생 SOS 발신 ✗ → 관리자 확인 ✗ → 3분 에스컬레이션 ✅(돌지만 트리거될 일 없음)
                 └───── C3: 스케줄러는 대기 중인데 입구가 없다 ─────┘

[결석]           학부모 신고 ✗ → 관리자 승인 ✗ → replan ✅ → 명단 제외 ✅ → 기사 명단 ✅
                 └───── C4: 백엔드는 이어져 있는데 입구가 없다 ─────┘

[미승차 감지]    운행 시작 ✅ → 15초 주기 판정 ✅ → 알림 생성 ✅ → 관리자 수신 ✅
                                      │                        └→ 학부모 수신 ✗  C6
                                      └ PICKUP + routePlan 있을 때만  C6-1
                                      └ ETA가 실제 버스 위치와 무관     C6-2
```

**출구가 없는 것과 입구가 없는 것은 다르다.** C1·C2·C3·C4는 **백엔드가 이미 완성돼 있고 화면만 없다** — 프론트 작업으로 해소된다. 반면 C6-2(ETA 근거)·C12(일정변경 반영)·C13(보호자 부재)는 **모델·로직 자체가 없어** 화면을 붙여도 해결되지 않는다. 착수 순서를 정할 때 이 둘을 섞지 않는 것이 중요하다.

## C-2. 이 문서를 갱신해야 하는 시점

- 프론트에 **새 화면·새 라우트**가 생겼을 때 → 해당 항목을 B부에서 A부로 옮기고, C장의 해당 갭을 지운다
- **API 경로·요청 필드가 바뀌었을 때** → 단계 표의 "호출 API"와 "결과"를 함께 고친다(경로만 고치면 상태 변화 서술이 어긋난다)
- **상태머신·임계값이 바뀌었을 때** → B7 표와 A2의 분기·예외를 함께 본다
- 이 문서의 API 경로는 전부 실측값이다. **추측으로 채우지 않는다** — 확인이 안 되면 "미확인"이라고 쓴다
