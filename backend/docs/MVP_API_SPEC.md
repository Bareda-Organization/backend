# MVP API 명세서

> 새 프론트엔드(Flutter로 재작성)가 실제로 호출할 **MVP 5개 기능의 API만** 정리한 명세서다. 전체 API는
> Swagger UI(`http://localhost:8080/swagger-ui/index.html`)의 **"00. MVP 사용 API"** 그룹과 정확히 1:1 대응한다(18개
> — 2026-07-28 `GET /api/buses/me` 추가로 17→18).
> 회원가입·수동 노선생성(`/generate`)·기록 정정·학생단위 위치조회 등 MVP 화면에서 안 쓰는 API는 여기 없다 —
> 필요해지면 Swagger UI에서 나머지 카테고리를 그대로 참고하면 된다.
>
> 소스: 코드 직접 대조(2026-07-22). 값이 실제와 달라지면(엔드포인트 추가/변경) 이 문서도 같이 갱신할 것.

## 목차

1. [공통 규약](#1-공통-규약)
2. [인증(Auth) · 세션 부트스트랩](#2-인증auth--세션-부트스트랩)
3. [위치(Location, F1 — 버스 단위)](#3-위치location-f1--버스-단위)
4. [승하차(RideEvent)](#4-승하차rideevent)
5. [배차·노선계획(Routing, F4)](#5-배차노선계획routing-f4)
6. [알림(Notification)](#6-알림notification)
7. [WebSocket(STOMP) — 연결·재연결·구독](#7-websocketstomp--연결재연결구독)
8. [주기(interval) 정리](#8-주기interval-정리)
9. [부록 — 테스트 계정/시드 참조](#9-부록--테스트-계정시드-참조)

---

## 1. 공통 규약

### 1.1 응답 포맷

모든 REST 응답은 아래 한 가지 포맷으로 온다(`ApiResponse<T>`).

**성공**
```json
{ "success": true, "data": { /* T */ }, "message": null }
```

**실패**
```json
{ "success": false, "data": null, "message": "사람이 읽는 한글 메시지" }
```

⚠️ **중요**: 실패 응답 body에는 `"errorCode": "NOT_FOUND"` 같은 별도의 기계판독용 코드 필드가 **없다**. 에러를 구분하는
근거는 **HTTP 상태 코드 + message 문자열** 뿐이다. 아래 표의 "내부 코드명"은 백엔드 소스 코드 상의 enum 이름(참고용)이며
응답에 그대로 실리지 않는다 — 프론트가 분기 처리할 땐 HTTP status를 기준으로 삼고, message는 사용자에게 그대로 보여주는
용도로 쓰면 된다.

### 1.2 인증

`Authorization: Bearer <accessToken>` 헤더를 모든 보호된 API에 실어야 한다.

- 발급: [`POST /api/auth/login`](#21-로그인)
- 재발급: [`POST /api/auth/refresh`](#22-토큰-재발급)
- accessToken 유효기간: **900초(15분)**
- refreshToken 유효기간: **1,209,600초(14일)**

accessToken이 15분마다 만료되므로, 프론트는 만료 임박(또는 401 수신) 시 refreshToken으로 재발급받는 흐름을 반드시
구현해야 한다.

### 1.3 공통 에러 코드

| 내부 코드명(참고용) | HTTP | 기본 message |
|---|---|---|
| `INVALID_INPUT` | 400 | 입력값이 올바르지 않습니다 |
| `UNAUTHORIZED` | 401 | 인증이 필요합니다 |
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호가 올바르지 않습니다 |
| `FORBIDDEN` | 403 | 접근 권한이 없습니다 |
| `NOT_FOUND` | 404 | 대상을 찾을 수 없습니다 |
| `CONFLICT` | 409 | 이미 처리된 요청입니다 |
| `DUPLICATE_EMAIL` | 409 | 이미 가입된 이메일입니다 |
| `INTERNAL_ERROR` | 500 | 서버 오류가 발생했습니다 |

**검증 실패(400)**: 요청 바디의 `@Valid` 검증이 실패하면 `message`는 `"필드명: 에러메시지"` 형식으로 **첫 번째 오류 하나만**
담긴다(여러 필드가 동시에 틀려도 1개만 반환되므로, 프론트는 재제출 후에도 다른 필드 오류가 추가로 나올 수 있음을
감안해야 한다).

**권한 실패(403)의 두 가지 경로** — 둘 다 HTTP 403 + 동일 포맷이지만 발생 위치가 다르다:
1. **역할 자체가 안 맞음**: 예) STUDENT 계정으로 DRIVER 전용 API 호출. Spring Security가 컨트롤러 진입 **전**에 차단하며,
   message는 항상 기본값(`접근 권한이 없습니다`) 고정이다.
2. **역할은 맞지만 담당·소속이 아님**: 예) 다른 기사의 버스 조회, 다른 학원 자료 조회. 서비스 계층에서 판단하며,
   엔드포인트별로 message가 다를 수 있다(각 절의 예외 표 참조).

### 1.4 공통 열거형(enum)

| enum | 값 |
|---|---|
| `Role` | `STUDENT` `PARENT` `DRIVER` `ACADEMY_ADMIN` `PLATFORM_ADMIN` |
| `RouteDirection` | `PICKUP`(등원 — 승차지점 순회 후 학원 도착) `DROPOFF`(하원 — 학원 출발 후 하차지 순회) |
| `RoutePlanStatus` | `DRAFT` → `RECOMMENDED` → `APPROVED` → `PUBLISHED` (순차 전이) |
| `RideType` | `BOARD`(승차) `ALIGHT`(하차) `HANDOVER`(보호자 인계완료) |
| `RideSource` | `QR` `NFC` `MANUAL`(기사 수동, MVP 기본값) `CORRECTION`(사후 정정) |
| `LocationOrigin` | `GPS`(실기기) `MOCK`(시뮬레이션, 현재 MVP 기본) |
| `NotificationType` | `BOARD_DONE` `ALIGHT_DONE` `HANDOVER_DONE` `APPROACH` `NO_SHOW` `SOS` `SCHEDULE_RESULT` `CONNECTION_LOST` `ROUTE_RECOMMENDED` `ROUTE_PUBLISHED` |

---

## 2. 인증(Auth) · 세션 부트스트랩

> 로그인 응답에는 토큰만 들어 있고 **사용자 정보(역할·userId·tenantId)를 주는 `/me` 엔드포인트가 없다.**
> 클라이언트는 accessToken(JWT) payload를 직접 디코딩해 세션을 구성한다 — 클레임은
> `sub`(userId) · `email` · `memberships`(`"tenantId:ROLE"` 배열) · `type`(`access`/`refresh`).
> 플랫폼 관리자는 소속 학원이 없어 `":PLATFORM_ADMIN"`처럼 **tenantId 자리가 빈 문자열**로 온다.
> 서명 검증은 서버가 매 요청 수행하므로 클라이언트는 화면 분기용으로만 읽으면 된다.

### 2.1 로그인

`POST /api/auth/login` — 권한: 없음(공개)

이메일/비밀번호로 로그인해 토큰 쌍을 발급받는다. 다른 모든 API 호출의 출발점.

**Request Body**

| 필드 | 타입 | 필수 | 설명 | 예시 |
|---|---|---|---|---|
| email | String | Y | 가입 이메일 | `admin@school.com` |
| password | String | Y | 비밀번호 | `password` |

**Response 200**
```json
{
  "success": true,
  "data": { "accessToken": "eyJhbGciOi...", "refreshToken": "eyJhbGciOi..." },
  "message": null
}
```

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 400 | `INVALID_INPUT` | `email: ...` / `password: ...` | 이메일 형식 오류, 필드 누락 |
| 401 | `INVALID_CREDENTIALS` | 이메일 또는 비밀번호가 올바르지 않습니다 | 가입 안 된 이메일, 또는 비밀번호 불일치(둘을 구분해 알려주지 않음 — 계정 존재 여부 노출 방지) |

### 2.2 토큰 재발급

`POST /api/auth/refresh` — 권한: 없음(공개, 유효한 refreshToken 필요)

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| refreshToken | String | Y | 로그인 응답의 refreshToken 값 |

**Response 200**: 로그인과 동일한 `{accessToken, refreshToken}` 포맷(둘 다 새로 발급 — refreshToken도 회전됨).

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 400 | `INVALID_INPUT` | refreshToken: ... | 필드 누락 |
| 401 | `UNAUTHORIZED` | 유효하지 않은 토큰입니다 | 서명 불일치·만료·형식 오류 |
| 401 | `UNAUTHORIZED` | refresh 토큰이 아닙니다 | accessToken을 여기 잘못 넣음 |
| 401 | `UNAUTHORIZED` | 인증이 필요합니다(기본 메시지) | 토큰이 가리키는 유저가 이미 삭제됨 |

### 2.3 내 담당 버스 조회 (기사)

`GET /api/buses/me` — 권한: `DRIVER` *(2026-07-28 신설)*

**기사 앱의 부트스트랩 API다.** 기사용 API는 전부 `busId`를 입력으로 받는데(§3.2, §4.4, §5.6, `/api/drive-sessions/*`)
기사가 그걸 알아낼 수단이 없어 추가했다. `GET /api/buses`(목록)는 관리자 전용이고 JWT 클레임에도 busId가 없다.
**로그인 직후 1회 호출해 `busId`를 세션에 캐시**하고 이후 기사 화면 전체에서 재사용한다.

**Request**: 없음(토큰의 userId로 담당 버스를 찾는다)

**Response 200** (`BusResponse`)
```json
{
  "success": true,
  "data": {
    "id": 1, "tenantId": 1, "name": "3호차", "plateNumber": "12가3456",
    "seatCapacity": 25, "assignCapacity": 25, "onboard": 3, "overCapacity": false,
    "driverId": 3, "driverName": "박기사", "routeId": 1, "routeName": "A노선",
    "insuranceExpiry": "2027-01-01"
  },
  "message": null
}
```

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 403 | `FORBIDDEN` | 접근 권한이 없습니다 | DRIVER 가 아닌 역할이 호출 |
| 404 | `NOT_FOUND` | 담당 버스가 없습니다 | 그 기사에게 배차된 버스가 없음(아직 관리자가 배차 안 함) |

⚠️ 도메인상 기사 1명 = 버스 1대지만 스키마에 유니크 제약이 없다 — 데이터가 꼬여 2대 이상이면 **id가 가장 작은 1대**를 반환한다(에러 아님).

---

## 3. 위치(Location, F1 — 버스 단위)

⚠️ 이 절의 API는 **버스 자체**의 좌표를 다룬다. 학생 개인 위치(`/api/locations/me` 등)는 MVP 스코프 밖이라 이 문서에
없다(필요하면 Swagger의 "08. 위치(Location)" 카테고리 참조).

### 3.1 학원 버스 실시간 위치 목록

`GET /api/locations/buses?tenantId=` — 권한: `ACADEMY_ADMIN`, `PLATFORM_ADMIN`

관제 지도에 표시할 학원 소속 버스들의 **최신 좌표 1건씩**.

**Query**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| tenantId | Long | 조건부 | `PLATFORM_ADMIN`은 필수, `ACADEMY_ADMIN`은 생략 시 본인 소속 학원 |

**Response 200**
```json
{
  "success": true,
  "data": [
    { "busId": 1, "busName": "3호차", "lat": 37.5075, "lng": 127.0355,
      "recordedAt": "2026-07-22T01:00:00", "origin": "MOCK" }
  ],
  "message": null
}
```

⚠️ **위치 보고가 한 번도 없었던 버스는 배열에서 아예 빠진다** — "버스가 없다"와 "위치가 아직 없다"를 응답만으로는
구분할 수 없다(둘 다 그냥 그 버스가 안 보임).

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 400 | `INVALID_INPUT` | tenantId가 필요합니다 | PLATFORM_ADMIN이 tenantId 생략 |
| 403 | `FORBIDDEN` | 접근 권한이 없습니다 | ACADEMY_ADMIN이 다른 학원 tenantId 요청, 또는 소속 학원 없음 |

### 3.2 기사 버스 위치 보고

`POST /api/locations/bus` — 권한: `DRIVER`

담당 기사가 자기 버스의 현재 좌표를 보고한다(실 GPS 전환 시 이 API로 대체 — 현재 MVP는 Mock 시뮬레이터가 대신 채움).

**Request Body**

| 필드 | 타입 | 필수 | 설명 | 예시 |
|---|---|---|---|---|
| busId | Long | Y | 보고 대상 버스 id | `1` |
| lat | Double | Y | 위도 | `37.5075` |
| lng | Double | Y | 경도 | `127.0355` |

**Response 200**: `{"success":true,"data":null,"message":null}`

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 400 | `INVALID_INPUT` | busId/lat/lng: ... | 필드 누락 |
| 403 | `FORBIDDEN` | 담당 기사만 보고할 수 있습니다 | 그 버스의 담당 기사가 아님 |
| 404 | `NOT_FOUND` | 버스를 찾을 수 없습니다 | busId 존재하지 않음 |

⚠️ **이 API는 WebSocket 실시간 push와 연결돼 있지 않다.** 보고된 좌표는 저장만 되고, 관리자 화면은 §3.1을
**직접 폴링**해야 새 값을 본다(§8 주기 참조). 자세한 이유는 §7 참조.

---

## 4. 승하차(RideEvent)

### 4.1 승하차 기록

`POST /api/ride-events` — 권한: `DRIVER`

기사가 학생의 승차/하차/보호자인계를 기록한다. 기록 성공 시 `type`에 따라 알림이 발행되고(`BOARD`→`BOARD_DONE`,
`ALIGHT`→`ALIGHT_DONE`, `HANDOVER`→`HANDOVER_DONE`), 이 알림은 WebSocket으로도 실시간 push된다(§7).

**Request Body**

| 필드 | 타입 | 필수 | 설명 | 예시 |
|---|---|---|---|---|
| busId | Long | Y | 버스 id | `1` |
| studentId | Long | Y | 학생 id | `1` |
| type | RideType | Y | `BOARD`/`ALIGHT`/`HANDOVER` | `BOARD` |
| stopId | Long | N | 정류장 id — 생략 시 학생 기본 승차 정류장 | `1` |
| lat | Double | N | 기록 위치 위도 | `37.5010` |
| lng | Double | N | 기록 위치 경도 | `127.0275` |

**Response 200** (`RideEventResponse`)
```json
{
  "success": true,
  "data": {
    "id": 101, "tenantId": 1, "studentId": 1, "busId": 1, "stopId": 1,
    "type": "BOARD", "occurredAt": "2026-07-22T08:05:00",
    "lat": 37.5010, "lng": 127.0275, "source": "MANUAL",
    "correctedBy": null, "correctedAt": null, "originalRef": null
  },
  "message": null
}
```

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 400 | `INVALID_INPUT` | 학생과 버스의 학원이 다릅니다 | 학생 소속 학원 ≠ 버스 소속 학원(테넌트 불일치) |
| 403 | `FORBIDDEN` | 담당 기사만 처리할 수 있습니다 | 그 버스의 담당 기사가 아님 |
| 404 | `NOT_FOUND` | 버스를 찾을 수 없습니다 / 학생을 찾을 수 없습니다 | busId·studentId 오류 |

### 4.2 본인 승하차 기록(학생)

`GET /api/ride-events/me?date=` — 권한: `STUDENT`

**Query**: `date`(LocalDate, 생략 시 오늘, 예: `2026-07-22`)

**Response 200**: `RideEventResponse[]`(그날 기록, 시간순). 기록 없으면 빈 배열.

**예외**: `404 NOT_FOUND` "학생 정보를 찾을 수 없습니다" — 로그인 계정에 연결된 학생 레코드가 없을 때(정상 시드 데이터로는
발생하지 않음, 계정-학생 연결 정합성 문제 시에만).

### 4.3 자녀 승하차 기록(학부모)

`GET /api/ride-events/children?date=` — 권한: `PARENT`

**Response 200**: `RideEventResponse[]`(형제자매 전체 합산, 그날 기록). 자녀가 없으면 **에러 없이 빈 배열**.

### 4.4 담당 버스 승하차 명단(기사)

`GET /api/ride-events/bus/{busId}?date=` — 권한: `DRIVER`

**Response 200**: `RideEventResponse[]`(그 버스의 그날 전체 명단 이력).

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 403 | `FORBIDDEN` | 담당 기사만 처리할 수 있습니다 | 담당 버스가 아님 |
| 404 | `NOT_FOUND` | 버스를 찾을 수 없습니다 | busId 오류 |

---

## 5. 배차·노선계획(Routing, F4)

> 흐름: **auto-assign(제안)** → 관리자 검토 → **auto-assign/confirm(확정)** — confirm 시점에 학생 배정이 실제로
> 바뀌고 승인·배포까지 한 번에 처리된다. `approve`/`publish`는 이미 만들어진 계획을 다루는 하위 단계 API(대개 confirm이
> 대신 처리해주므로 직접 호출할 일은 적음).

### 5.1 멀티버스 자동배정 제안

`POST /api/route-plans/auto-assign` — 권한: `ACADEMY_ADMIN`, `PLATFORM_ADMIN`

학원 전체 활성 로스터(결석 제외)를 방위각(sweep) 기준으로 버스별 분산해 `RECOMMENDED` 상태 계획(들)을 만든다.
**이 시점엔 학생의 담당 버스(`assignedBus`)가 아직 바뀌지 않는다** — 계획의 정차 목록이 "제안 로스터"를 겸한다.

**Request Body**

| 필드 | 타입 | 필수 | 설명 | 예시 |
|---|---|---|---|---|
| tenantId | Long | Y | 학원 id | `1` |
| direction | RouteDirection | Y | `PICKUP`/`DROPOFF` | `PICKUP` |
| serviceDate | LocalDate | N | 생략 시 오늘 | `2026-07-22` |

**Response 200**
```json
{
  "success": true,
  "data": {
    "plans": [ /* RoutePlanResponse[], status=RECOMMENDED */ ],
    "excludedStudentNames": ["최지우"]
  },
  "message": null
}
```
`excludedStudentNames`: 해당 방향 좌표(승차지/하차지)가 없어 배정에서 제외된 학생 이름 목록(에러 아님 — 정상 응답의 일부).

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 400 | `INVALID_INPUT` | tenantId가 필요합니다 | PLATFORM_ADMIN이 tenantId 생략 |
| 400 | `INVALID_INPUT` | 배차 가능한 버스가 없습니다 | 그 학원에 등록된 버스 0대 |
| 400 | `INVALID_INPUT` | 학원 위치(depot)가 설정되지 않았습니다 | 학원(tenant)에 lat/lng 미설정 |
| 400 | `INVALID_INPUT` | 전체 정원(N명) 초과: 대상 M명 | 전체 버스 좌석 합 < 배정 대상 학생 수 |
| 403 | `FORBIDDEN` | 접근 권한이 없습니다 | ACADEMY_ADMIN이 다른 학원 tenantId 요청 |

### 5.2 자동배정 확정

`POST /api/route-plans/auto-assign/confirm` — 권한: `ACADEMY_ADMIN`, `PLATFORM_ADMIN`

검토를 마친 `RECOMMENDED` 계획들을 확정한다 — 학생 배정(`assignedBus`) 커밋 → 승인(`APPROVED`) → 배포(`PUBLISHED`)까지
한 번에 진행되며, 배포 시 담당 기사에게 `ROUTE_PUBLISHED` 알림이 발행된다(WebSocket 포함, §7).

**Request Body**

| 필드 | 타입 | 필수 | 설명 | 예시 |
|---|---|---|---|---|
| planIds | Long[] | Y(최소 1개) | §5.1 응답의 `plans[].id` 목록 | `[1, 2]` |

**Response 200**: `RoutePlanResponse[]`(각 계획 status=`PUBLISHED`로 갱신됨)

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 404 | `NOT_FOUND` | 노선 계획을 찾을 수 없습니다 | planId 존재하지 않음 |
| 403 | `FORBIDDEN` | 접근 권한이 없습니다 | 다른 학원 소속 계획 |
| 409 | `CONFLICT` | 초안·추천 상태에서만 승인할 수 있습니다 | 이미 APPROVED/PUBLISHED인 계획을 다시 confirm(중복 호출 방지) |

### 5.3 노선계획 승인

`PATCH /api/route-plans/{id}/approve` — 권한: `ACADEMY_ADMIN`, `PLATFORM_ADMIN`

`DRAFT`/`RECOMMENDED` → `APPROVED`. **Response 200**: `RoutePlanResponse`.

**예외**: `404 NOT_FOUND`(계획 없음) / `403 FORBIDDEN`(다른 학원) / `409 CONFLICT` "초안·추천 상태에서만 승인할 수 있습니다"

### 5.4 노선계획 배포

`PATCH /api/route-plans/{id}/publish` — 권한: `ACADEMY_ADMIN`, `PLATFORM_ADMIN`

`APPROVED` → `PUBLISHED`. 배포 즉시 §5.6(기사 조회 API)에 노출되고, 계획의 첫 정차 학생을 대표로 그 학생의 담당
기사에게 `ROUTE_PUBLISHED` 알림이 간다(학부모도 함께 받음). **Response 200**: `RoutePlanResponse`.

**예외**: `404 NOT_FOUND` / `403 FORBIDDEN` / `409 CONFLICT` "승인된 계획만 배포할 수 있습니다"

### 5.5 노선계획 목록 / 상세

`GET /api/route-plans?tenantId=&busId=` — 권한: `ACADEMY_ADMIN`, `PLATFORM_ADMIN` (`busId` 생략 가능, 지정 시 그 버스만 필터)

`GET /api/route-plans/{id}` — 권한: 동일. 정차 순서(`stops[]`)까지 포함한 상세.

**예외(공통)**: 목록은 `403 FORBIDDEN`(tenantId 관련, §5.1과 동일 규칙). 상세는 추가로 `404 NOT_FOUND`(id 없음).

### 5.6 기사용 배포완료 노선

`GET /api/route-plans/driver/{busId}?serviceDate=` — 권한: `DRIVER`

담당 버스의 그날(생략 시 오늘) **배포 완료(`PUBLISHED`)** 노선만 pull 방식으로 조회(등원/하원 방향순 정렬).

**Response 200**: `RoutePlanResponse[]`

**예외**

| HTTP | 코드명 | message | 조건 |
|---|---|---|---|
| 403 | `FORBIDDEN` | 담당 기사만 조회할 수 있습니다 | 그 버스의 담당 기사가 아님 |
| 404 | `NOT_FOUND` | 버스를 찾을 수 없습니다 | busId 오류 |

### `RoutePlanResponse` 공통 응답 필드

```json
{
  "id": 1, "tenantId": 1, "busId": 1, "direction": "PICKUP", "status": "PUBLISHED",
  "version": 1, "serviceDate": "2026-07-22", "totalDistanceM": 1566.0, "totalDurationS": 397.0,
  "polyline": "[[127.0275,37.501],...]",
  "stops": [ { "seq": 1, "studentId": 1, "lat": 37.501, "lng": 127.0275, "etaSeconds": 0 } ],
  "createdAt": "2026-07-22T01:08:14"
}
```

---

## 6. 알림(Notification)

발송 자체는 다른 모듈(승하차·배차 등)이 트리거하며, 이 절은 **조회 전용**이다. 실시간으로는 §7의 WebSocket으로도
같은 내용이 push된다 — REST는 "이전 이력 + 새로고침 시 스냅샷"용으로 쓰면 된다.

### 6.1 학원 알림 이력(관리자)

`GET /api/notifications?tenantId=` — 권한: `ACADEMY_ADMIN`, `PLATFORM_ADMIN`

**Response 200**: `NotificationResponse[]`(최신순)
```json
{ "id": 1, "tenantId": 1, "studentId": 1, "type": "BOARD_DONE", "message": "김민준 학생이 승차했습니다", "createdAt": "2026-07-22T08:05:00" }
```

**예외**: `400 INVALID_INPUT`(tenantId 필요, PLATFORM_ADMIN) / `403 FORBIDDEN`(다른 학원)

### 6.2 자녀 알림함(학부모)

`GET /api/notifications/children` — 권한: `PARENT`

**Response 200**: `NotificationResponse[]`(형제자매 합산, 최신순). 자녀 없으면 빈 배열(에러 아님).

---

## 7. WebSocket(STOMP) — 연결·재연결·구독

### 7.1 연결

- **Endpoint**: `ws://<host>/ws/location` (또는 `wss://` — 프로덕션)
- **SockJS 미사용** — 순수 STOMP-over-WebSocket. 네이티브 모바일 클라이언트를 전제로 하며, 구형 브라우저의 폴백은
  고려돼 있지 않다(REST가 별도로 있으니 폴백 필요 없음).
- **인증**: STOMP `CONNECT` 프레임의 네이티브 헤더 `Authorization: Bearer <accessToken>` — **세션 수립 시 1회만** 검증한다
  (REST처럼 매 메시지마다 검사하지 않음). 이후 그 세션의 모든 송수신은 CONNECT 시점에 확인된 사용자로 처리된다.
- 인증 실패(헤더 없음/토큰 무효/만료) 시 CONNECT 자체가 거부된다(STOMP ERROR 프레임 반환, WS 연결은 닫힘).
- **Heartbeat**: 서버·클라이언트 상호 **10초**(`app.connection.heartbeat-ms`, 기본 10000ms) 간격 ping — REST 폴링보다
  훨씬 빠르게 연결 끊김을 감지하기 위한 용도.

### 7.2 재연결

서버는 재연결을 대신 처리해주지 않는다 — **재연결은 전적으로 클라이언트 책임**이다(STOMP.js 등 클라이언트 라이브러리의
`reconnectDelay` 류 자동 재시도 설정을 쓰는 걸 권장). 재연결 시 세션이 완전히 새로 생성되므로 `Authorization` 헤더를
**다시** 실어 보내야 한다(이전 세션의 인증 정보는 재사용되지 않음).

서버 쪽 "끊김 감지" 흐름(학생 위치 전용, §8 주기표 함께 참조):

1. WS 연결 해제(`SessionDisconnectEvent`) → 끊긴 시각을 메모리에 기록(`LocationSessionRegistry`)
2. 10초(`app.connection.loss-check-ms`)마다 도는 스케줄러가 기록을 훑어 판정
3. **30초**(`app.connection.loss-grace-seconds`) 넘게 끊긴 채면 `CONNECTION_LOST` 알림 발행 — 그 이내 재연결하면
   알림 없이 조용히 정리됨(터널 통과 등 순간 끊김 오탐 방지)
4. 재연결 성공(`SessionConnectedEvent`) 시 끊김 기록을 즉시 제거

### 7.3 구독 가능한 destination(서버 → 클라이언트)

| destination | 대상 | payload | 트리거 |
|---|---|---|---|
| `/user/queue/location` | 본인(학생 앱), 학부모(자녀), 담당 기사(자기 반 학생) | `LocationView` | **학생** 위치가 갱신될 때마다 |
| `/topic/tenant/{tenantId}/location` | 그 학원 소속 관리자 | `LocationView` | 위와 동일 트리거, 학원 전체로 broadcast |
| `/user/queue/notifications` | 본인/학부모/담당 기사 | `NotificationResponse` | §1.4의 `NotificationType` 전체 발생 시 |
| `/topic/tenant/{tenantId}/notifications` | 그 학원 소속 관리자 | `NotificationResponse` | 위와 동일, 학원 전체로 broadcast |

⚠️ **`/topic/tenant/{tenantId}/**` 구독은 추가 인가 검사가 있다** — `PLATFORM_ADMIN`이거나 그 학원 소속
`ACADEMY_ADMIN`만 허용되며, 아니면 `SUBSCRIBE` 자체가 거부된다. `/user/queue/**`는 Spring의 user-destination
라우팅이 "그 세션에만" 배달되도록 구조적으로 보장하므로 별도 인가 검사가 없다(애초에 다른 사람 큐를 구독할 방법이
없음).

### 7.4 클라이언트 → 서버 전송(발행)

| destination | 용도 | payload |
|---|---|---|
| `/app/location` | 학생이 REST(`POST /api/locations`) 대신 WebSocket으로 자기 위치 보고 | `LocationReportRequest {lat, lng}` |

같은 저장 로직(`LocationCommandService.reportSelf`)을 그대로 타므로 REST와 결과가 동일하다 — WS 경유가 "연결 상태
추적"까지 겸하므로, 실시간성이 중요한 화면(학생 앱)이라면 WS 경유를 권장한다.

⚠️ **F1(버스 위치)은 이 WebSocket 채널을 쓰지 않는다.** §3의 두 API(버스 위치 보고/조회)는 순수 REST이며, 위
구독 목록에 버스 위치는 없다. 버스 위치를 실시간처럼 보이게 하려면 프론트가 §3.1을 직접 폴링해야 한다(§8 참조).

### 7.5 연결 후 사용 흐름(권장 순서)

1. REST 로그인 → accessToken 확보
2. WS `CONNECT`(`/ws/location`, `Authorization` 헤더) — 성공하면 세션 수립
3. 역할에 맞는 destination 구독(§7.3)
4. **WS는 "연결된 시점 이후의 변화"만 알려준다 — 초기 화면은 반드시 REST로 먼저 채워야 한다.** 예) 알림함 화면을
   열 때 `GET /api/notifications`(또는 `/children`)로 과거 이력을 먼저 불러온 뒤, 그 이후 새로 오는 것만 WS로 덧붙인다.
5. (학생) 위치 보고는 REST 또는 WS 중 택일(§7.4)

---

## 8. 주기(interval) 정리

| 항목 | 값 | 설정 키 | 비고 |
|---|---|---|---|
| Mock 위치 시뮬레이션 tick | 3,000ms | `app.location.tick-ms` | **학생** 위치만 자동 생성 — 버스 위치(F1)는 Mock 소스가 없어 기사가 §3.2로 직접 보고해야 값이 생긴다 |
| WS heartbeat | 10,000ms | `app.connection.heartbeat-ms` | 연결 끊김 감지 기준(§7.1) |
| 연결끊김 판정 스케줄러 | 10,000ms | `app.connection.loss-check-ms` | §7.2 |
| 연결끊김 유예시간 | 30,000ms | `app.connection.loss-grace-seconds`(값은 초 단위 30) | 이 시간 넘게 끊겨야 `CONNECTION_LOST` 발행 |
| 근접/미승차 판정 스케줄러 | 15,000ms | `app.drivesession.approach-check-ms` | 도착 5분 전 `APPROACH`, 도착+10분 미승차 `NO_SHOW` |
| SOS 에스컬레이션 스케줄러 | 30,000ms | `app.sos.escalation-check-ms` | 3분 미확인 시 에스컬레이션 |
| accessToken 유효기간 | 900초(15분) | `jwt.access-token-validity-seconds` | §1.2 |
| refreshToken 유효기간 | 1,209,600초(14일) | `jwt.refresh-token-validity-seconds` | §1.2 |

**프론트 폴링 권장값(서버가 실시간으로 안 밀어주는 것)**

- **버스 위치**(§3.1 `GET /api/locations/buses`): WS push가 없으므로 프론트가 직접 주기적으로 다시 불러와야 한다.
  서버 쪽 위치 소스 자체가 갱신되는 주기가 3,000ms이므로 **3,000ms 권장**(그보다 자주 불러도 새 값이 없다).
- **노선계획**(§5.5 목록/상세): 배포되면 알림(WS)이 오므로, 그 알림을 받았을 때 1회 재조회하면 충분하다 — 배포는
  관리자가 수동으로 트리거하는 이벤트라 주기적 polling이 불필요하다.

---

## 9. 부록 — 테스트 계정/시드 참조

`docker compose down && docker compose up -d postgres redis kafka` 직후(Flyway가 시드를 매번 새로 채움) 기준.
비밀번호는 전부 `password`.

| 이메일 | 이름 | 역할 | 비고 |
|---|---|---|---|
| `student@school.com` | 김민준 | STUDENT | studentId=1, 3호차(busId=1) 배정 |
| `parent@school.com` | 이부모 | PARENT | 김민준·이서연의 보호자 |
| `driver@school.com` | 박기사 | DRIVER | 3호차(busId=1) 담당 |
| `admin@school.com` | 한빛관리자 | ACADEMY_ADMIN | tenantId=1(한빛학원) |
| `platform@school.com` | 플랫폼관리자 | PLATFORM_ADMIN | 전역(소속 학원 없음) |

상세 값(버스·정류장·학생 좌표 등)은 `backend/src/main/resources/db/migration-local/V2__seed_data.sql` 참조.
