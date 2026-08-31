# 바래다 (BARAEDA) — API 명세서

학원 통학버스 운행·학생 등하원 관리 플랫폼의 **엔드포인트 계약서**. 공통 규약 · 도메인별 엔드포인트 · WebSocket · 에러 코드 사전 · enum 사전을 담음.

| 항목 | 내용 |
|---|---|
| 문서 버전 | v1.0 |
| 작성일 | 2026-08-24 |
| 기준 | 바래다 API명세서 v2.1 · 기능정의서 v2.1 · PRD v2.1 · 유저플로우 v2.1 (2026-08-24) |
| 프로토콜 | REST + JSON, Bearer 토큰. 실시간은 WebSocket 병행 |

**자매 문서** — [FEATURE_SPEC.md](./FEATURE_SPEC.md) · [PRD.md](./PRD.md) · [USER_FLOWS.md](./USER_FLOWS.md) · [ARCHITECTURE.md](./ARCHITECTURE.md) · [ERD.md](./ERD.md) · [TECH_DECISIONS.md](./TECH_DECISIONS.md)

**신규 설계 결정 (2026-08-24 승인 완료)** — 아래는 기획 원본에 근거가 부재하나 API 구현에 필요해 이 문서에서 처음 정한 값. 베이스 경로·필드 명명·시각/좌표 표기(§1.1) · `X-Client-Version`·`X-Request-Id`(§1.3) · 멱등키 보존 24시간(§1.7) · 페이징 규약(§1.8) · `INVALID_CREDENTIALS.details.remaining_attempts` · 에러 코드 명칭 `STAFF_QUOTA_EXCEEDED`·`MANAGER_ASSIGNED`.

**[조정 중] 표기** — 노선 최적화·배차 정책이 미확정이라 **경로 · 권한 · 목적만 예약**. 요청·응답 세부는 배차 정책 확정 후 기술.

---

## 0. 문서 경계

| 문서 | 담는 것 | 담지 않는 것 |
|---|---|---|
| **API_SPEC.md** (이 문서) | 공통 규약, 엔드포인트별 메서드·경로·권한·요청·응답·에러, WebSocket 채널·이벤트, 에러 코드 사전, enum 사전 | 기능 정의 원문, 권한 매트릭스 전문, 정책 근거, 화면 조작 순서 |
| `FEATURE_SPEC.md` | 공통 규칙 C-01~C-17, 도메인 모델·상태머신, 계층별 기능 정의, 권한(RBAC)·민감 데이터 등급 | 요청·응답 필드 |
| `PRD.md` | 배경·목표, 정책 근거, 우선순위, NFR, KPI | 엔드포인트 계약 |
| `USER_FLOWS.md` | 역할별 조작 순서, 분기·차단, 알림 매트릭스 | 엔드포인트 계약 |

각 엔드포인트에 붙은 기능 ID(`ATT-01` · `P-03` 등)는 **참조 표기**. 정의 원문은 FEATURE_SPEC 을 봄.

---

## 1. 공통 규약

### 1.1 기본 형식

| 항목 | 규칙 |
|---|---|
| 베이스 경로 | `/api/v1` — 이 문서의 모든 경로는 이 접두사 생략 표기 |
| 요청·응답 본문 | `application/json; charset=utf-8` 고정. **예외 — 학생 사진 업로드(§5.11)만 `multipart/form-data`**(JSON 파트 + 파일 파트). 파일은 이미지 3종(`jpeg`·`png`·`webp`), 상한 5MB 🆕 |
| 필드 명명 | `snake_case` |
| 식별자 | 서버 발급 문자열. 경로 파라미터 `{id}` · `{runId}` · `{stopId}` · `{riderId}` |
| 성공 상태 | 조회·수정 `200`, 생성 `201`, 본문 없는 처리 `204` |
| 시각 표기 | ISO-8601 + 오프셋 (`2026-08-24T08:30:00+09:00`). 서비스 기준 시간대 `Asia/Seoul` |
| 날짜 표기 | `YYYY-MM-DD`. `date` 쿼리 파라미터 미지정 시 서버 기준 당일 |
| 좌표 | `lat` · `lng` (WGS84, 소수점 6자리) |

 이 절 전체가 신규 설계 제안 — 기획 원본에 대응 서술 부재.

### 1.2 인증 · 토큰 (C-14)

| 항목 | 규칙 |
|---|---|
| 인증 헤더 | `Authorization: Bearer {access_token}` — **앱·웹 공통.** access 토큰은 쿠키로 전송하지 않음 |
| access 토큰 | 단기. 만료 시 `401 TOKEN_EXPIRED` |
| refresh 토큰 | 장기. `POST /auth/refresh` 로 access 재발급 — 자동 로그인의 근거. **전달 수단은 클라이언트 종류로 가름** (§1.2.1) |
| 무효화 | refresh 만료 · 로그아웃 · 계정 차단 시 무효화 후 재로그인 요구 (`401`) |
| 비인증 허용 경로 | `GET /academies/search` · `POST /auth/signup` · `POST /auth/login` · `POST /auth/refresh` · `POST /auth/recover` **5개만** |

#### 1.2.1 refresh 토큰의 전달 수단

| 클라이언트 | 판정 근거 | 서버 → 클라이언트 | 클라이언트 → 서버 |
|---|---|---|---|
| **앱** (매니저 · 학부모 · 학생) | `X-Client-Type: app` 또는 헤더 부재(기본값) | 응답 본문 `refresh_token` | 요청 본문 `refresh_token` |
| **웹** (관계자 웹 · 메인 관리자 콘솔) | `X-Client-Type: web` | `Set-Cookie: refresh_token=…` | 쿠키 자동 동봉 (`credentials: include`) |

**웹 응답 본문에 `refresh_token` 을 함께 담지 않음** — 담으면 페이지 스크립트가 읽을 수 있어 HttpOnly 가 무의미.

**쿠키 속성** — 4개 전부 필수이며 하나라도 빠지면 결함.

| 속성 | 값 | 빠지면 |
|---|---|---|
| `HttpOnly` | — | 페이지 스크립트가 `document.cookie` 로 탈취 가능 |
| `Secure` | — | 평문 구간에서 전송돼 중간자에 노출. `http://localhost` 는 브라우저가 보안 컨텍스트로 취급하므로 로컬 개발에서도 유지 |
| `SameSite` | `Strict` | 외부 사이트가 유발한 요청에 쿠키가 동봉돼 CSRF 성립 |
| `Path` | `/api/v1/auth` | `/api/v1` 전 요청에 쿠키가 붙어 노출 지점이 증가. 베이스 경로가 `/api/v1`(§1.1)이므로 접두사를 포함해야 `/api/v1/auth/refresh` 에 실제로 동봉된다 |

`Max-Age` 는 refresh 만료 시각과 일치.

⚠ **웹 콘솔과 API 는 같은 등록 도메인(eTLD+1) 아래 배포해야 함** — `app.<도메인>` · `api.<도메인>`(DEPLOYMENT §2.9)이 이 조건을 충족. 서로 다른 사이트로 갈라 배포하면 `SameSite=Strict` 에서 쿠키가 전송되지 않아 웹 자동 로그인이 동작하지 않음. `SameSite=None` 으로 낮추면 CSRF 방어가 사라지므로 대안이 아님.

⚠ **CORS 는 출처를 명시해야 함** — 쿠키를 동봉하는 요청에는 `Access-Control-Allow-Origin: *` 를 쓸 수 없고 `Access-Control-Allow-Credentials: true` 가 필요 (`app.cors.allowed-origins`).

### 1.3 공통 헤더

| 헤더 | 방향 | 필수 | 설명 |
|---|---|:-:|---|
| `Authorization` | 요청 | 조건부 | 비인증 허용 경로 5개 외 전부 필수 |
| `Content-Type` | 요청 | ● | 본문이 있는 요청에 `application/json` |
| `X-Client-Version` | 요청 | ○ | 앱 버전. 강제 업데이트 판정용 |
| `X-Client-Type` | 요청 | ○ | `app` · `web`. **`POST /auth/login` 에서만 의미를 가짐** — refresh 를 본문으로 줄지 쿠키로 줄지 판정 (§1.2.1). 미전달 시 `app`. `refresh`·`logout` 은 쿠키 존재 여부로 판정하므로 불필요 |
| `X-Request-Id` | 요청·응답 | ○ | 요청 추적 식별자. 미전달 시 서버 생성 후 응답에 반영 |

### 1.4 계정 상태 게이트 (C-01 · 3.6)

| 상태 | 로그인 | API 접근 |
|---|---|---|
| `pending` | 성공 — 토큰 발급 | `GET /auth/signup-status` · `POST /auth/logout` · **`GET /me`**(§2.10) · **`POST`·`DELETE /me/devices`**(§2.11). 그 외 전 API `403 AUTH_PENDING` |
| `active` | 성공 | 역할별 권한 범위 |
| `rejected` | 성공 | `pending` 의 것 + `POST /auth/signup/reapply` **1개 추가**. 대기 화면에 거절 사유 노출 — 재신청은 거절 이후에만 가능. 거부 시 `403 AUTH_REJECTED`(§8.1) |

⚠ **2026-08-25 정정 — 이 표가 원래 `pending` 을 "2개만" 으로 적어 `§2.10`·`§2.11` 과 모순이었다.** 두 절이 각각 명시한다 — `§2.10` "**전 역할 공통이며 `pending`·`rejected` 도 호출 가능** — 대기 화면이 상태를 알아야 함", `§2.11` "**인증된 전 역할(`pending` 포함 — 승인 결과 알림이 대상)**". **두 절의 근거가 구체적이고 기능적이라 이쪽이 이긴다** — `/me` 가 없으면 앱 재실행 후 `role`·`status` 재취득 수단이 부재해 **대기 화면 분기 자체가 성립하지 않고**(`§2.6` 응답이 토큰 2개뿐), `/me/devices` 가 없으면 **승인 결과 푸시를 받을 단말이 등록되지 않는다.** "2개" 라는 수치는 그 두 절이 신설되기 전 판의 잔존으로 보인다.
| `blocked` | 실패 `403 AUTH_ACCOUNT_BLOCKED` | 접근 부재 — 해제는 메인 관리자 |

**판정 위치는 서버 인가 계층** (C-01 · FEATURE_SPEC §3.6). 채택 근거는 PRD §6.4.

### 1.5 학원 격리

- 모든 자원은 **호출 계정의 소속 학원(academy) 범위로 격리**. 타 학원 자원 요청은 `403 ACADEMY_SCOPE_VIOLATION`.
- academy 는 **토큰에서 서버가 결정**. 요청 본문·쿼리로 받은 academy 식별자는 신뢰 대상 밖.
- 예외는 메인 관리자(`/admin/**`) — 전 학원 범위. 학원 지정은 경로 파라미터로 명시.
- 학부모는 연결된 자녀(`GuardianStudent`) 범위, 학생은 본인 범위, 매니저는 **배치된 회차** 범위로 추가 축소.

### 1.6 3구간 판정 (C-04)

기준은 **회차 출발 시각**. 판정 주체는 서버 시계.

| 구간 | 창 | 처리 |
|---|---|---|
| ① | 출발 **30분 전**까지 | 승인 없이 즉시 반영 + 노선 재최적화 |
| ② | 30분 안쪽 ~ 출발 전 | 관리자 승인 경유 — **승인 시 재최적화·재배포**, 거절 시 기존 경로 유지. **회차당 1회**, 소진 시 `403 CHANGE_LIMIT_REACHED` |
| ③ | 운행 시작 후 | 노선 변경 부재. 미등원(`riding=false`)만 승인 없이 즉시 수용 — 해당 승하차지는 경유하되 미정차(`skipped`). 그 외는 `403 CHANGE_WINDOW_CLOSED` |

- 확정 배치는 실행 시점 최신값을 읽되 **판정 기준은 출발−30분 시계**. 배치 지연에도 마감 시각은 불변.
- ② 구간 요청이 **출발 시각 도달 또는 `Run.status` → `moving` 중 먼저 오는 시점**까지 미처리로 남으면 서버가 **자동 거절** — 재최적화 없이 **기존 노선 유지** + 학부모 통지, **횟수 미소진**.
- 서버 처리 실패 시 기존 상태 복구 + **횟수 미소진** (C-10).

### 1.7 멱등성

| 항목 | 규칙 |
|---|---|
| 대상 | ① 승하차 처리 `PATCH /runs/{runId}/riders/{riderId}` (BRD-06 오프라인 큐) ② **비상 발신** `POST /runs/{runId}/emergency` (EXC-04 — 통신 두절 상태 발신이 복구 후 중복 도착 가능) |
| 키 | 요청 본문 `client_key` — 단말이 생성하는 UUID |
| 재전송 | 동일 `client_key` 재수신 시 **중복 무시**하고 최초 처리 결과를 `200` 으로 반환 |
| 보존 | 회차 종료 후 24시간 |

### 1.8 페이징

목록 조회에 공통 적용. 실시간 관제·운행 명단은 페이징 미적용(전량 반환).

| 파라미터 | 타입 | 기본 | 설명 |
|---|---|---|---|
| `page` | integer | 0 | 0 기점 |
| `size` | integer | 20 | 최대 100 |
| `sort` | string | 엔드포인트별 | `{필드}:{asc\|desc}` |

응답 봉투 — `items[]` · `page` · `size` · `total_count` · `has_next`.

### 1.9 처리 결과 통지 (C-10)

- 모든 쓰기 요청은 **서버 2xx 확인 뒤에만** 클라이언트가 "처리되었습니다" 표시. 타임아웃·5xx 는 "처리되지 않았습니다" 명시.
- **낙관적 UI 금지** — 응답 전 상태 선반영 부재.
- 쓰기 응답은 변경 후 자원 상태를 그대로 반환해 클라이언트 재조회 부재.

### 1.10 에러 응답 형식

HTTP 상태 코드 + 본문. 본문 형태는 전 엔드포인트 공통.

```json
{
  "error": {
    "code": "CHANGE_LIMIT_REACHED",
    "message": "금일은 변경할 수 없습니다",
    "details": {
      "run_id": "run_20260824_3_am",
      "used_count": 1,
      "limit": 1
    }
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `error.code` | string | ● | 에러 코드 사전(§8)의 값 |
| `error.message` | string | ● | 사용자 노출 문구 (한국어) |
| `error.details` | object | ○ | 코드별 부가 정보. 구조는 코드마다 상이 |

### 1.11 모든 엔드포인트 공통 에러

**인증이 필요한 전 엔드포인트에서 발생 가능**한 항목. 개별 엔드포인트의 `**에러**` 줄에는 반복 기재 부재.

| 코드 | HTTP | 발생 조건 |
|---|:-:|---|
| `TOKEN_EXPIRED` | 401 | access 토큰 만료, 또는 로그아웃·계정 차단으로 무효화 → 재로그인 요구 (§1.2) |
| `AUTH_PENDING` | 403 | `pending` 계정이 허용 2개(승인 대기 조회 `GET /auth/signup-status` · `POST /auth/logout`) 밖 호출. `rejected` 는 `POST /auth/signup/reapply` 1개 추가 (§1.4) |
| `AUTH_ACCOUNT_BLOCKED` | 403 | `blocked` 계정의 호출 — 해제는 메인 관리자 (C-11) |
| `FORBIDDEN` | 403 | 역할 권한 밖 호출 (FEATURE_SPEC §6 권한 매트릭스) |
| `ACADEMY_SCOPE_VIOLATION` | 403 | 소속 학원 밖 자원 요청 (§1.5). 메인 관리자 콘솔(§6)은 예외 |
| `VALIDATION_FAILED` | 422 | 필수 필드 누락 · 형식 위반 |

- **비인증 허용 경로 5개**(§1.2)에는 위 401·403 항목이 미적용 — `VALIDATION_FAILED` 만 해당.
- `500` 계열 서버 오류에는 클라이언트가 "처리되지 않았습니다" 표시. 성공 표시는 서버 2xx 확인 뒤에만 (C-10 · §1.9).
- 개별 엔드포인트의 `**에러**` 줄에는 **그 엔드포인트 고유의 실패 시나리오만** 기재. 단 그 경로에서 **특별한 의미**를 갖는 공통 코드는 개별 기재 — 예 승하차 처리의 `403 ESCORT_ONLY`(기사 호출 차단, §4.6) · 매니저 앱 회차 자원의 `403 FORBIDDEN`(배치되지 않은 회차, §1.5) · 관제의 학원 격리 예외(§6.8).

### 1.12 개인정보 취급

| 항목 | 규칙 |
|---|---|
| 보호자 연락처 | 매니저 앱 응답에서 **마스킹** (`010-2XXX-8814`). 관계자 웹·메인 관리자 콘솔은 원문 |
| 학생 사진 | 서버 저장. `photo_url` 은 매니저 앱 · 관계자 웹 · 메인 관리자 콘솔에만 반환 — 학부모·학생 앱 응답에 부재 |
| 타 학생 정보 | 학부모·학생 앱 응답에 타 학생의 이름·상태·인원수 부재 (C-08) |
| 위치 데이터 보유 기간 · 14세 미만 동의 | 법정 요건 검토 후 확정 — 미확정 |

---

## 2. 인증 · 가입 (AUTH)

### 2.1 GET /academies/search

가입용 학원 검색 (AUTH-02). **비인증 허용.**

**권한** 비인증 · **기능 ID** AUTH-02 · O-01

**요청 (쿼리)**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `q` | string | ● | 학원명 또는 학원 코드. **양쪽 매칭** |

**응답** — `items[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `id` | string | ● | 학원 내부 식별자. 가입 요청에 이 값을 전달 |
| `name` | string | ● | 학원명 |
| `region` | string | ● | 지역(시·군·구). 동명 학원 구분값 |
| `code` | string | ● | 학원 코드(서버 자동 생성값) |

비활성 학원은 결과에서 제외. 선택 화면 표기는 `{학원명} · {지역} · {학원 코드}`.

**정렬·상한** — `name` 오름차순(동명은 `id` 오름차순)으로 **최대 20건**. 초과분은 반환하지 않으며 절단 사실을 알리는 필드도 두지 않는다 — 클라이언트는 검색어를 좁히도록 안내한다. 이 엔드포인트는 **§1.8 페이징 규약의 예외**다: 비인증 경로라 `page`·`size` 를 열면 학원 전체 목록을 순회로 수집할 수 있고, 가입 화면의 용도는 목록 열람이 아니라 **자기 학원 1곳을 찾는 것**이라 페이징이 필요하지 않다. 정렬 기준을 고정하는 이유는 `ORDER BY` 없는 `LIMIT` 이 매 호출 다른 20건을 반환할 수 있어, **같은 검색어에 학원이 보였다 안 보였다 하는** 재현 불가능한 증상이 되기 때문이다.

**에러** — `422 VALIDATION_FAILED`(`q` 누락). 검색 결과 부재는 빈 `items[]` 로 반환 — 에러 부재. 비인증 경로라 계정 상태 항목은 미적용 (§1.11).

### 2.2 POST /auth/signup

form 회원가입 (AUTH-01, C-01). **비인증 허용.** 전 인원이 이 경로로 가입 — 코드 로그인·발급 계정 부재.

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `role` | enum | ● | `parent` · `student` · `driver` · `escort` · `staff` |
| `login_id` | string | ● | 로그인 아이디. 중복 시 `409 DUPLICATE_LOGIN_ID` |
| `password` | string | ● | 비밀번호 |
| `name` | string | ● | 이름 |
| `phone` | string | ● | 연락처. 아이디·비밀번호 복구의 인증 수단 (AUTH-08) |
| `academy_id` | string | ● | `GET /academies/search` 결과의 `id` |

**응답 `201`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `account_status` | enum | `pending` 고정 |
| `requested_at` | datetime | 신청 일시 |
| `approver` | enum | `staff`(관계자 승인) · `system_admin`(메인 관리자 승인). `role=staff` 는 `system_admin` |

`SignupRequest` 생성 + 계정 `pending`. **메인 관리자는 이 경로로 가입 불가** — 내부 발급.

**에러** — `409 DUPLICATE_LOGIN_ID` · `404 ACADEMY_NOT_FOUND`(비활성 학원 포함) · `422 VALIDATION_FAILED`

### 2.3 GET /auth/signup-status

승인 대기 화면 (AUTH-03). `pending` · `rejected` 토큰으로 호출 가능한 조회.

**권한** 전 역할 (`pending` · `rejected` 포함) · **기능 ID** AUTH-03 · P-01

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `status` | enum | ● | `pending` · `active` · `rejected` |
| `academy.name` · `academy.region` · `academy.code` | string | ● | 신청 학원 |
| `requested_at` | datetime | ● | 신청 일시 |
| `reject_reason` | string | ○ | `rejected` 일 때만 |
| `academy_contact` | string | ● | 학원 문의처 |

**에러** — §1.11 공통 항목 외 고유 에러 부재. `pending` · `rejected` 허용 경로라 `403 AUTH_PENDING` 미발생 (§1.4).

### 2.4 POST /auth/signup/reapply

거절 후 재신청 — 학원 재선택 (AUTH-03).

**권한** `rejected` 계정 · **요청** `academy_id` (string, 필수) · **응답** `status` = `pending`, `requested_at` · **에러** `409 REAPPLY_NOT_ALLOWED`(`rejected` 아닌 상태) · `404 ACADEMY_NOT_FOUND`

### 2.5 POST /auth/login

로그인 (AUTH-04·05). **비인증 허용.**

**요청** — `login_id` (string, 필수) · `password` (string, 필수) · 헤더 `X-Client-Type` (`app` · `web`, 미전달 시 `app`)

**응답**

| 필드 | 타입 | 설명 |
|---|---|---|
| `access_token` | string | 단기 토큰. 앱·웹 공통으로 본문에 담김 |
| `refresh_token` | string | 장기 토큰. **`X-Client-Type: app` 일 때만 본문에 담김** — `web` 이면 본문에서 빠지고 `Set-Cookie` 로 전달 (§1.2.1) |
| `role` | enum | `parent` · `student` · `driver` · `escort` · `staff` · `system_admin` |
| `status` | enum | `pending` · `active` · `rejected` |
| `account_id` | string | 계정 식별자 |
| `academy` | object | `id` · `name` — `system_admin` 은 `null` |

- `X-Client-Type: web` 이면 응답 헤더에 `Set-Cookie: refresh_token=…; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age={refresh 만료까지의 초}` 가 붙는다 (§1.2.1).
- `pending` · `rejected` 도 **로그인 성공 + 토큰 발급**. 접근 범위만 §1.4 로 축소.
- 실패 **5회** 누적 시 **계정 단위** 차단 — IP 차단 부재 (C-11). 이후 `403 AUTH_ACCOUNT_BLOCKED`, 해제는 메인 관리자.
- 매니저 앱은 계정에 배정된 호차가 자동 결정 — 사용자의 호차 선택 부재.

**에러** — `401 INVALID_CREDENTIALS`(`details.remaining_attempts` 포함) · `403 AUTH_ACCOUNT_BLOCKED` · `403 AUTH_STAFF_INACTIVE`(퇴사 처리된 관계자, §6.7 · §8.1)

⚠ **`AUTH_STAFF_INACTIVE` 판정은 비밀번호 대조를 통과한 뒤에 한다.** 앞에 두면 아이디만으로 "실재하고 퇴사한 관계자" 를 알려 주는 계정 열거 채널이 하나 늘고, 그 탐색은 실패 카운터를 올리지 않아 횟수 제한도 받지 않는다. `AUTH_ACCOUNT_BLOCKED` 가 대조 **앞**인 것과 갈리는데, 그쪽은 이미 상한을 채워 카운터가 더 오를 자리가 부재한 상태라 교환의 내용이 다르다.

### 2.6 POST /auth/refresh

토큰 재발급 (C-14). **비인증 허용** — refresh 토큰이 인증 수단.

**요청** — `refresh_token` (string). **앱만 본문에 담고, 웹은 `refresh_token` 쿠키로 전송**하므로 본문이 비어 있음.

**판정** — 서버는 **쿠키를 먼저 보고, 없으면 본문**을 읽는다. 쿠키로 들어온 요청은 웹으로 간주해 응답도 `Set-Cookie` 로 돌려준다 — 이 경로는 `X-Client-Type` 을 요구하지 않음.

**응답** — `access_token`(본문). 회전한 refresh 는 앱이면 본문 `refresh_token`, 웹이면 `Set-Cookie`(속성은 §1.2.1 과 동일).

**에러** — `401 TOKEN_EXPIRED`(만료·로그아웃·차단으로 무효화) → 재로그인 요구. 쿠키·본문 어디에도 refresh 가 없으면 `401 TOKEN_EXPIRED`.

### 2.7 POST /auth/logout

로그아웃 (AUTH-09). refresh 토큰 무효화. 정본 API명세서에 경로 미기재 — AUTH-09 · §1.4 의 로그아웃 허용 규칙에서 도출.

**권한** 전 역할 (`pending` 포함) · **요청** `refresh_token` (string) — §2.6 과 같이 **쿠키 우선, 없으면 본문** · **응답** `204`

**웹 응답에는 쿠키 삭제 지시가 함께 붙는다** — `Set-Cookie: refresh_token=; Max-Age=0; Path=/api/v1/auth` (속성은 발급 시와 동일해야 브라우저가 같은 쿠키로 인식). 서버측 무효화만 하고 이 헤더를 빠뜨리면 브라우저에 죽은 쿠키가 남아 다음 접속이 `401` 한 번을 더 거친다.

**에러** — `401 TOKEN_EXPIRED`(전달된 `refresh_token` 이 이미 무효화). `pending` 허용 경로라 `403 AUTH_PENDING` 미발생.

### 2.8 POST /auth/password

비밀번호 변경 (AUTH-07).

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `current_password` | string | ● | 현재 비밀번호 |
| `new_password` | string | ● | 새 비밀번호 |

**에러** — `401 INVALID_CREDENTIALS` · `422 VALIDATION_FAILED`. 성공 시 기존 refresh 토큰 전량 무효화 — 웹 호출이면 §2.7 과 같은 쿠키 삭제 지시를 함께 반환.

### 2.9 POST /auth/recover

아이디·비밀번호 복구 (AUTH-08). **비인증 허용.**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `type` | enum | ● | `login_id` · `password` |
| `phone` | string | ● | 가입 시 등록 연락처 |
| `verification_code` | string | ○ | SMS 인증 코드. 미전달 시 코드 발송 요청으로 처리 |

전화번호 인증(SMS) 복구 또는 관리자 경유 복구 요청. 관계자 계정 비밀번호 초기화는 메인 관리자 경로(§6.7).

**에러** — `404 ACCOUNT_NOT_FOUND`(미등록 전화번호) · `403 VERIFICATION_CODE_INVALID`(SMS 인증 코드 만료·불일치) · `422 VALIDATION_FAILED`(`type` 누락). 비인증 경로라 계정 상태 항목은 미적용 (§1.11).

---

### 2.10 GET /me

본인 프로필 (C-14 자동 로그인 · 계정 상태 게이트 §1.4). **전 역할 공통이며 `pending`·`rejected` 도 호출 가능** — 대기 화면이 상태를 알아야 함.

**권한** 인증된 전 역할

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `account_id` · `login_id` · `name` · `phone` | — | ● | 계정 기본 |
| `role` | enum | ● | §9.1 |
| `status` | enum | ● | `pending` · `active` · `rejected` |
| `academy` | object | ○ | `id` · `name` — `system_admin` 은 `null` |
| `student_id` | string | ○ | `role=student` 일 때 **본인 학생 레코드** |
| `manager_id` · `manager_role` | string · enum | ○ | `role=driver`·`escort` 일 때 |
| `linked_student_count` | integer | ○ | `role=parent` 일 때 연결 자녀 수 |

**이 엔드포인트가 필요한 이유 둘.** ① **학생 계정이 본인 `student_id` 를 얻을 경로가 부재** — `GET /me/students`(§3.1)는 학부모 전용이고 학생용 조회는 전부 `/students/{id}/...` 형태라, 이것이 없으면 학생 앱의 첫 화면부터 호출이 불가. ② `POST /auth/refresh`(§2.6) 응답이 토큰 2개뿐이라 **앱 재실행 후 `role`·`status` 재취득 수단이 부재** — `pending` 화면 분기가 성립하지 않음.

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 2.11 POST /me/devices · DELETE /me/devices/{token}

푸시 수신 단말 등록·해지 (NTF-12). **알림 전 종류의 전제** — 이 등록이 없으면 서버가 발송 대상 단말을 특정 불가.

**권한** 인증된 전 역할 (`pending` 포함 — 승인 결과 알림이 대상)

**요청 (등록)**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `token` | string | ● | FCM · APNs 단말 토큰 |
| `platform` | enum | ● | `android` · `ios` · `web` |
| `device_id` | string | ● | 기기 식별자. 같은 기기의 토큰 갱신 시 기존 행을 대체 |
| `app_version` | string | ○ | |

**응답** `201` — `device_id` · `registered_at`

| 처리 | 내용 |
|---|---|
| 갱신 | 같은 `(account_id, device_id)` 재등록은 토큰을 덮어씀 — 행이 늘지 않음 |
| 다기기 | 한 계정이 여러 기기 보유 가능. 발송은 **유효한 전 토큰**에 |
| 해지 | 로그아웃(§2.7) 시 해당 기기 토큰 자동 해지. `DELETE` 는 수동 해지 |
| 무효 토큰 | 발송 실패가 `NotRegistered` 계열이면 서버가 해당 행을 정리 |

**에러** — `422 VALIDATION_FAILED`

---

## 3. 학부모 · 학생 앱

경로의 `{id}` 는 학생 식별자. **학부모는 연결된 자녀 범위, 학생은 본인 범위**로 격리 — 그 외는 `403 FORBIDDEN`.

### 3.1 GET /me/students

연결된 자녀 목록 (P-02 · ATT-03).

**권한** 학부모

**응답** — `items[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `student_id` | string | ● | 학생 식별자 |
| `name` | string | ● | 자녀 이름. 알림 문구에 필수 포함되는 값 |
| `class_name` | string | ○ | 반 |
| `linked_at` | datetime | ● | 연결 시각 |

**자녀 선택 UI 는 2명 이상일 때만 노출.** 알림은 자녀 선택과 무관하게 전 자녀 수신 (ATT-03).

**에러** — §1.11 공통 항목 외 고유 에러 부재. 연결 자녀 0명은 빈 `items[]` 로 반환.

### 3.2 POST /me/students/link-requests

자녀 연결 요청 (P-02) — 학부모.

| 항목 | 값 |
|---|---|
| 권한 | 학부모 |
| 요청 | `student_login_id` (string, 필수) |
| 응답 | `201` — `link_request_id`, `expires_at` |
| 처리 | 대상 학생 앱에 연결 요청 푸시. 다자녀도 같은 프로세스 반복 |

**에러** — `404 STUDENT_NOT_FOUND` · `409 ALREADY_LINKED`

### 3.3 POST /me/link-code

인증 코드 생성 (S-05) — 학생. 연결 요청 수신 후 학생 앱이 호출.

**권한** 학생 · **요청** 본문 부재 · **응답** `201` — `code`(string) · `expires_at`(datetime)

**대기 중인 연결 요청이 선행 조건**이다 — `link_code` 는 `link_request` 에 매달리므로(ERD) 요청 없이 코드를 만들 수단이 부재. 대기 중이고 만료되지 않은 요청이 여럿이면 **가장 최근 1건**에 붙는다.

**에러** — `404 LINK_REQUEST_NOT_FOUND`(대기 중인 연결 요청 부재)

### 3.4 POST /me/students/link

코드 입력 → 서버 인증으로 연결 완료 (P-02) — 학부모.

| 항목 | 값 |
|---|---|
| 권한 | 학부모 |
| 요청 | `code` (string, 필수) |
| 응답 | `201` — `student_id`, `name` |
| 처리 | `GuardianStudent` 생성. **서버 인증** — 클라이언트 대조 부재 |

**에러** — `403 LINK_CODE_INVALID`(만료·불일치 공통) · `409 ALREADY_LINKED`(이미 연결된 자녀)

### 3.5 GET /students/{id}/runs

자녀의 당일 회차 (P-04 · S-01).

**권한** 학부모(연결 자녀) · 학생(본인) · **요청 (쿼리)** `date` (date, 선택 — 기본 당일)

**응답** — `items[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `run_id` | string | ● | 회차 식별자 |
| `direction` | enum | ● | `to_academy`(등원) · `from_academy`(하원) |
| `bus_no` | string | ● | 호차 |
| `depart_time` | datetime | ● | 출발 시각 |
| `run_status` | enum | ● | `idle` · `confirmed` · `moving` · `finished` |
| `confirmed` | boolean | ● | 확정 노선 산출 여부 — 출발 30분 전 배치 결과 |
| `riding` | boolean | ● | 탑승 의사 (ATT-01). 기본 `true` |
| `rider_status` | enum | ● | `waiting` · `boarded` · `alighted` · `absent` · `no_show` |
| `stop` | object | ● | 본인 승하차지 — `stop_id` · `name` · `address` |
| `change_quota_left` | integer | ● | **이 회차의** ② 구간 잔여 변경 횟수. 한도는 회차당 1회이며 다른 회차와 독립 |

**ETA · 탑승 인원 부재** (C-08).

**에러** — `404 STUDENT_NOT_FOUND` · `403 FORBIDDEN`(연결 부재 자녀 · 본인 아닌 학생 — §3 도입부)

### 3.6 PATCH /students/{id}/runs/{runId}/intent

회차별 탑승 토글 (ATT-01·02, P-03). 3구간 규칙의 핵심 경로.

**권한** 학부모 · **기능 ID** ATT-01 · ATT-02 · P-03 · C-04

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `riding` | boolean | ● | `false` = 미탑승 |

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `result` | enum | ● | `applied`(① 즉시 반영) · `pending_approval`(② 승인 대기 접수) · `applied_no_reroute`(③ 반영하되 노선 불변) |
| `riding` | boolean | ● | 반영된 값. `pending_approval` 이면 **기존 값 유지** |
| `rider_status` | enum | ● | `applied` + `riding=false` → `absent` |
| `change_request_id` | string | ○ | `pending_approval` 일 때 |
| `change_quota_left` | integer | ● | 잔여 횟수 |
| `deadline_at` | string | ○ | ② 구간의 승인 마감 = 회차 출발 시각. 운행이 먼저 시작되면 그 시점에 조기 마감 |

```json
{
  "result": "pending_approval",
  "riding": true,
  "rider_status": "waiting",
  "change_request_id": "creq_8812",
  "change_quota_left": 0,
  "deadline_at": "2026-08-24T08:30:00+09:00"
}
```

**구간별 처리**

| 구간 | 처리 |
|---|---|
| ① 출발 30분 전까지 | 즉시 반영 — `absent` 기록 · 명단 제외 · **노선 재최적화**. 학부모 알림 부재, 관계자 통지 |

⚠ **① 의 "노선 재최적화" 는 호출이 아니라 결과다 (2026-08-30, Ruling 198).** ①구간(출발 30분 전까지) 동안 회차는 `idle` 이고 `confirmed_route` 행이 **부재**해 재최적화할 대상이 없다 — 확정 시각이 곧 ①/② 경계이기 때문이다(`run.confirm_at` · `ck_run_confirm_at` CHECK · ARCHITECTURE §9). 따라서 ①구간 토글은 **`boarding_intent` 만 갱신**하고, 반영은 뒤이어 도는 확정 배치(RTE-02)가 그 값을 읽어 산출하는 것으로 이뤄진다(ARCHITECTURE §8.1 입력 3축). **예외** — 회차 임시 추가(§5.10)로 출발 30분 이내에 만들어진 회차는 생성 시점에 `confirm_at` 이 이미 지나 곧바로 확정되므로 **② 구간부터 시작**한다.
| ② 30분 안쪽 ~ 출발 전 | 승인 대기로 접수 + 관계자 푸시(REQ-05). 승인 시 **재최적화·재배포**(§5.6). **회차당 1회** — 단위는 회차(`Run`)이며 등원·하원이 각각 1회씩. 소진 후 `403 CHANGE_LIMIT_REACHED` |
| ③ 운행 시작 후 | `riding=false` 만 **승인 없이 즉시 수용** — `applied_no_reroute`. `absent` 기록 + 해당 승하차지를 **경유하되 정차하지 않음**(`skipped`) + 기사·동승자 푸시. **노선·순번 불변, 재최적화 부재** (C-04 ③ · C-05). `riding=true`(되돌리기)는 `403 CHANGE_WINDOW_CLOSED` |

서버 처리 실패 시 기존 상태 복구 + **횟수 미소진** (C-10).

**에러** — `403 CHANGE_LIMIT_REACHED` · `403 CHANGE_WINDOW_CLOSED`(③ 구간의 `riding=true` 되돌리기) · `404 RUN_NOT_FOUND` · `404 STUDENT_NOT_FOUND` · `403 FORBIDDEN`(연결 부재 자녀 — 학부모 전용, 학생 계정 호출 포함)

### 3.7 GET · PATCH /students/{id}/weekly-address

요일별 등하원 주소 (P-05 · STU-05·06). **기본 주소 개념 부재** — 노선 산출의 기준 (C-12).

**권한** 학부모 · **GET** 설정 화면 초기 조회. 응답은 PATCH 요청과 동일 구조

**PATCH 요청** — `entries[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `weekday` | enum | ● | `mon` · `tue` · `wed` · `thu` · `fri` · `sat` · `sun` |
| `direction` | enum | ● | `to_academy` · `from_academy` |
| `address` | string | ● | 주소 원문 |
| `address_detail` | string | ○ | 아파트 동·출입구 등 상세 위치 |

**응답** — 반영된 `entries[]` + 항목별 `lat` · `lng` · `verified`(boolean).

주소 검증(좌표 변환·유효성)을 거쳐 승하차지로 매칭·생성 (STU-05). 검증 실패 시 `422 ADDRESS_VERIFICATION_FAILED` — **저장 보류**.

**일일 변경(REQ) 우선** — 특정 날짜에 일일 변경이 있으면 그날만 우선 적용, 이후 요일별 주소로 복귀.

**에러** — `422 ADDRESS_VERIFICATION_FAILED`(주소 검증 실패 — 저장 보류) · `404 STUDENT_NOT_FOUND` · `403 FORBIDDEN`(연결 부재 자녀)

### 3.8 POST /students/{id}/change-requests

변경 신청 (REQ-01·02, P-06).

**권한** 학부모

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `type` | enum | ● | `relocate`(위치 변경) · `cancel`(탑승 취소) |
| `run_id` | string | ● | 대상 회차 |
| `new_address` | string | 조건부 | `type=relocate` 필수 |
| `reason` | string | ○ | 변경 사유 |

**응답 `201`** — `change_request_id` · `status`(`pending` · `approved`) · `result`(`applied` · `pending_approval`) · `deadline_at`

| 구간 | 처리 |
|---|---|
| ① | 즉시 반영 + 재최적화 → `status=approved`, `result=applied` |
| ② | 승인 대기 접수 — **관리자 승인을 통해서만 반영**, 승인 시 **재최적화·재배포**(§5.6). 거절 시 기존 경로 유지. 회차당 1회 |
| ③ | `403 CHANGE_WINDOW_CLOSED` |

**에러** — `403 CHANGE_WINDOW_CLOSED` · `403 CHANGE_LIMIT_REACHED` · `422 ADDRESS_VERIFICATION_FAILED` · `404 RUN_NOT_FOUND` · `404 STUDENT_NOT_FOUND` · `403 FORBIDDEN`(연결 부재 자녀)

### 3.9 GET /students/{id}/change-requests

신청 상태 조회 (REQ-03).

**응답** — `items[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `change_request_id` | string | ● | |
| `type` | enum | ● | `relocate` · `cancel` |
| `status` | enum | ● | `pending` · `approved` · `rejected` · `auto_rejected` |
| `reject_reason` | string | ○ | `rejected` 일 때 |
| `run_id` · `requested_at` · `decided_at` | — | ● / ○ | 대상 회차 · 신청 시각 · 처리 시각 |

응답 최상위에 `pending_count` 포함 — 홈 배지용. `pending` 동안 화면 안내는 **기존 승하차지 탑승**이고 처리중 뱃지를 상시 노출. `auto_rejected` 는 출발 시각 도달 또는 운행 시작으로 서버가 자동 거절한 건 — 기존 노선 유지 + 학부모 통지, 횟수 미소진 (C-04).

**에러** — `404 STUDENT_NOT_FOUND` · `403 FORBIDDEN`(연결 부재 자녀)

### 3.10 GET /students/{id}/route

상세 노선 (LOC-03, P-08 · S-04).

**권한** 학부모 · 학생 · **요청 (쿼리)** `date` (date, 선택) · `run_id` (string, 선택)

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `run_id` · `bus_no` · `depart_time` | — | ● | 회차 요약 |
| `confirmed` | boolean | ● | `false` = 고정 노선 + "확정 전" 배지 |
| `driver.name` · `escort.name` | string | ● | 기사 · 동승자 이름 |
| `escort.phone` | string | ● | **동승자 연락 버튼**용. 기사 연락처 부재 — 학부모 → 기사 직접 연락은 스코프 제외 |
| `my_stop_id` | string | ● | 본인 승하차지 |
| `stops[]` | array | ● | `stop_id` · `seq` · `name` · `address` · `lat` · `lng` · `change` |
| `stops[].change` | enum | ○ | `added` · `skipped` — **승하차지에 `removed` 부재**. 탑승자 삭제는 승하차지가 아니라 명단에 반영 (FEATURE_SPEC §3.5) |

**표시 범위 — 승차지 이전 2개 · 승차지 · 하차지만** (P-08). 승하차지별 탑승 인원 · ETA 부재 (C-08).

**에러** — `404 STUDENT_NOT_FOUND` · `404 RUN_NOT_FOUND`(`run_id` 지정 시) · `403 FORBIDDEN`(연결 부재 자녀). 확정 전은 에러 부재 — 고정 노선 + "확정 전" 배지로 반환

### 3.11 GET /students/{id}/bus-position

실시간 버스 위치 (LOC-02, P-07 · S-02).

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `run_id` · `bus_no` | string | ● | |
| `run_status` | enum | ● | `moving` 이 아니면 위치 부재 |
| `lat` · `lng` | number | ○ | 현재 좌표 |
| `received_at` | datetime | ○ | 좌표 수신 시각. 송신 주기 **5~10초** |
| `last_seen_at` | datetime | ○ | 신호 유실 시 마지막 확인 시각 — 화면은 "마지막 확인 위치 · N분 전". **유실 판정은 마지막 수신 후 2분**(2026-08-31 사용자 확정, Ruling 208). `TECH_DECISIONS §관제 경고`의 *"2분 이상 미수신"* 과 **같은 값으로 통일**한다 — 갈라 두면 관제에는 경고가 떴는데 학부모 화면은 정상으로 보이는 구간이 생긴다. ⚠ **판정 주기 10초**(`ARCHITECTURE §9`)와 다른 값이며 층이 다르다 — 주기는 얼마나 자주 보는가이고 이 값은 얼마나 오래 끊겨야 유실인가다 |
| `current_stop_name` | string | ○ | 현재 위치명 |

당일 미등원(`absent`)이면 위치 부재 + 화면 안내 "오늘은 버스를 이용하지 않습니다".

실시간 갱신은 WebSocket `/ws/students/{id}/run` (§7).

**에러** — `404 STUDENT_NOT_FOUND` · `403 FORBIDDEN`(연결 부재 자녀). `run_status` 가 `moving` 이 아니거나 당일 `absent` 인 경우는 에러 부재 — 좌표 필드 부재로 반환

### 3.12 GET /notifications

알림 목록 (NTF-08, P-09 · S-03).

**권한** 전 역할 · **요청 (쿼리)** `type` (enum, 선택) · `unread_only` (boolean, 선택) · 페이징

**응답** — `items[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `notification_id` | string | ● | |
| `type` | enum | ● | §9.7 알림 종류 |
| `title` · `body` | string | ● | **자녀 이름 필수 포함** (ATT-03) |
| `student_id` · `student_name` | string | ○ | 대상 자녀 |
| `sent_at` | datetime | ● | 발송 시각 |
| `read_at` | datetime | ○ | 읽음 시각 |
| `popup` | boolean | ● | 팝업 노출 대상 여부 (NTF-09) |
| `unread_count` | integer | ● | 봉투 레벨 — 미읽음 배지 |

보관 기간 **14일**. 설정 off 알림도 목록에 존치 — off 는 푸시만 차단.

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 3.13 PATCH /notifications/{id}/read

알림 읽음 처리 (NTF-08). 응답 `204`. 중요 통지는 이 처리가 수신 확인(NTF-10)의 근거.

**에러** — `404 NOTIFICATION_NOT_FOUND` · `403 FORBIDDEN`(타 계정 알림)

### 3.14 GET · PATCH /me/notification-settings

알림 설정 (NTF-07, P-09).

**권한** 학부모 · 학생 · **GET** 설정 화면 초기 조회

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `arrive` | boolean | ● | 버스 도착 알림 |
| `boarding` | boolean | ● | 등하원(승차·하차·운행 시작) 알림 |
| `no_show` | boolean | ● | 미승차 알림 |

**지연 알림은 설정 항목 자체가 부재** — 항상 발송 (NTF-07). off 는 푸시만 차단하고 레코드는 항상 생성.

**에러** — `422 VALIDATION_FAILED`(설정 대상 밖 항목 전달 — 지연 알림은 설정 항목 자체가 부재, NTF-07)

---

## 4. 매니저 앱 (버스기사 · 동승자)

역할이 화면·권한을 결정. **승하차 상태 변경은 동승자 전용, 도착 처리·운행 시작·종료는 기사 전용** (C-06).

### 4.1 GET /manager/runs

담당 회차 (RUN-01, M-02 · M-07 운행 카드).

**권한** 버스기사 · 동승자 · **요청 (쿼리)** `date` (date, 선택 — 기본 `today`)

**응답** — `items[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `run_id` | string | ● | |
| `bus_no` | string | ● | 호차 |
| `direction` | enum | ● | `to_academy` · `from_academy` |
| `depart_time` | datetime | ● | 출발 시각 |
| `origin` · `destination` | string | ● | 출발지 · 도착지 |
| `est_duration_min` | integer | ● | 예상 소요시간(분) |
| `run_status` | enum | ● | `idle` · `confirmed` · `moving` · `finished` |
| `confirmed` | boolean | ● | `false` 면 명단 진입 불가 |
| `confirm_at` | datetime | ○ | 확정 예정 시각 = 출발 **30분 전**. 미확정 회차는 이 값만 반환 |
| `start_window` | object | ● | `from` · `to` — 출발 시각 **±10분** |
| `added_count` · `removed_count` | integer | ● | 변경 배지 |
| `ack_required` | boolean | ● | 노선 변경 확인 응답 미완료 여부 (RUN-07) |
| `role_in_run` | enum | ● | `driver` · `escort` — 화면 구성 결정 |

**에러** — §1.11 공통 항목 외 고유 에러 부재. 배정 회차 부재는 빈 `items[]` 로 반환.

### 4.2 GET /runs/{runId}/roster

승하차지별 명단 (RST-01·02·04, M-03).

**권한** 버스기사(조회) · 동승자(조회 + 처리) · **기능 ID** RST-01 · RST-02 · RST-04

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `run_id` · `bus_no` · `direction` | — | ● | 회차 요약 |
| `counts.boarded` · `counts.waiting` · `counts.no_show` · `counts.absent_n` | integer | ● | 집계. **`absent` 는 개인 행 제외, 집계에만 존치** |
| `stops[]` | array | ● | 운행 순서(`seq`) 정렬 |
| `stops[].stop_id` · `seq` · `name` · `address` | — | ● | |
| `stops[].change` | enum | ○ | `added`(초록) · `skipped`(빨강 취소선, 순번 유지) |
| `stops[].skip_notice` | string | ○ | `skipped` 안내 문구 |
| `stops[].arrived_at` | datetime | ○ | 도착 처리 타임스탬프 |
| `stops[].students[]` | array | ● | 승하차지 단위 묶음 |

**`stops[].students[]`**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `rider_id` | string | ● | 처리 대상 식별자 |
| `student_id` · `name` | string | ● | |
| `photo_url` | string | ● | **육안 확인용** — 태그(NFC/QR) 미사용 |
| `class_name` | string | ○ | 반 |
| `guardian_phone` | string | ● | **마스킹** (`010-2XXX-8814`) |
| `note` | string | ○ | 특이사항·비고 (STU-07) |
| `can_go_alone` | boolean | ● | 혼자 귀가 가능 여부 (STU-08). 하원 하차 판단 근거 |
| `status` | enum | ● | `waiting` · `boarded` · `alighted` · `no_show` |
| `change` | enum | ○ | `added` · `removed` |
| `no_show_case` | object | ○ | `started_at` · `expires_at` — **3분** 카운트다운 (EXC-01) |

```json
{
  "run_id": "run_20260824_3_am",
  "bus_no": "3호차",
  "direction": "to_academy",
  "counts": { "boarded": 12, "waiting": 4, "no_show": 1, "absent_n": 2 },
  "stops": [
    {
      "stop_id": "stop_118",
      "seq": 4,
      "name": "한빛아파트 정문",
      "change": "skipped",
      "skip_notice": "이 승하차지는 오늘 탑승자가 없어 미정차",
      "students": []
    },
    {
      "stop_id": "stop_119",
      "seq": 5,
      "name": "중앙로 스타빌딩 앞",
      "arrived_at": "2026-08-24T08:41:12+09:00",
      "students": [
        {
          "rider_id": "rider_5521",
          "student_id": "stu_301",
          "name": "김서준",
          "photo_url": "https://cdn.example/s/301.jpg",
          "class_name": "초등 A반",
          "guardian_phone": "010-2XXX-8814",
          "note": "할머니가 데리러 옴",
          "can_go_alone": false,
          "status": "waiting",
          "change": "added"
        }
      ]
    }
  ]
}
```

`absent` 학생은 **개인 행 제외** — 승하차지별 인원을 눈으로 셀 때 실제 인원과 어긋나는 위험 차단 (RST-02·04).

**에러** — `409 RUN_NOT_CONFIRMED`(확정 전 `idle` 회차 진입) · `404 RUN_NOT_FOUND` · `403 FORBIDDEN`(배치되지 않은 회차 — §1.5 매니저 범위)

### 4.3 GET /runs/{runId}/route

운행 정보 (M-09 · LOC-03). 실시간 노선 = 확정 노선 + 미승차 반영.

**권한** 버스기사 · 동승자

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `stops[]` | array | ● | `stop_id` · `seq` · `name` · `address` · `lat` · `lng` · `change` · `student_count` |
| `current_stop` | object | ○ | 현재 이동 중 승하차지 |
| `next_stop` | object | ○ | 다음 승하차지. **`skipped` 는 건너뛰고 실제 경유지를 반환** |
| `next_stop.lat` · `next_stop.lng` | number | ● | **외부 내비게이션 앱 콜백용** |
| `skipped_notice` | string | ○ | "○○ 승하차지는 오늘 미경유" 라인 |

미경유는 표시만 — **재최적화 · ETA 재계산 · 경로 안내 부재** (C-05). 주행 판단은 기사.

**에러** — `409 RUN_NOT_CONFIRMED`(확정 전 `idle` 회차 진입) · `404 RUN_NOT_FOUND` · `403 FORBIDDEN`(배치되지 않은 회차)

### 4.4 POST /runs/{runId}/start

운행모드 시작 (RUN-02, M-10).

**권한** 버스기사 전용 (동승자 호출 시 `403 DRIVER_ONLY`) · **요청** 본문 부재

**응답** — `run_status`(`moving`) · `started_at` · `auto_boarded_count`(하원일 때)

| 처리 | 내용 |
|---|---|
| 창 | 출발 시각 **±10분** 이내에만 허용. 밖이면 `403 START_WINDOW_CLOSED` |
| 상태 | `Run.status` → `moving` |
| 부수 효과 | 위치 송신 시작 · **노선 전면 잠금**(③ 구간 진입) · 운행 시작 알림(NTF-05) |
| 하원 | 탑승자 **전원 자동 `boarded`** (C-07 · BRD-03) |

**에러** — `403 DRIVER_ONLY` · `403 START_WINDOW_CLOSED`(출발 시각 **±10분** 창 밖) · `409 RUN_ALREADY_STARTED`(이미 `moving` · `finished`) · `409 RUN_NOT_CONFIRMED` · `404 RUN_NOT_FOUND` · `403 FORBIDDEN`(배치되지 않은 회차)

### 4.5 POST /runs/{runId}/stops/{stopId}/arrive

승하차지 도착 처리 (RUN-04, M-11).

**권한** 버스기사 전용 · **요청** 본문 부재

**응답** — `arrived_at` · `next_stop`(전진된 포인터) · `is_final`(최종 지점 여부) · `run_status` · `finish_pending`(하원 잔류로 종료 보류) · `remaining[]`(`rider_id`·`name`·`stop_name`) · `auto_alighted_count`(등원 종료 시)

| 처리 | 내용 |
|---|---|
| 시점 | 도착 직전 |
| 효과 | ① 도착 타임스탬프 기록 ② 기사 화면 포인터 전진 ③ **최종 지점이면 운행 종료 판정** (C-15) |
| **종료 겸함** | `is_final=true` 일 때 — 등원: 즉시 `run_status=finished` + 전원 자동 `alighted`. 하원: 잔류 0명이면 즉시 `finished`, 미하차 존재 시 `finish_pending=true` + `moving` 유지 (RUN-06) |
| 보류 해제 | 하원 보류 중 마지막 탑승자가 `alighted` 되는 순간 **서버가 자동으로 `finished` 전이** — 기사 재조작 부재. `run_ended` 발행 |
| 중복 | 동일 승하차지 재처리 차단 — `403 DUPLICATE_ARRIVE` |
| **알림** | **이 API 는 알림을 발송하지 않음.** "곧 도착합니다" 예고 알림은 **서버가 실시간 버스 위치 기반 이벤트로 자동 발송** (NTF-04) |
| 동승자 명단 | **별개로 계속 열림** — 기사 포인터와 동승자 처리 대상은 서로 다른 값 |

**에러** — `403 DRIVER_ONLY` · `403 DUPLICATE_ARRIVE`(동일 승하차지 재처리) · `409 RUN_NOT_MOVING` · `404 STOP_NOT_FOUND` · `404 RUN_NOT_FOUND`

### 4.6 PATCH /runs/{runId}/riders/{riderId}

승하차 처리 (BRD-01·02, M-12). **동승자 전용** (C-06).

**권한** 동승자 전용 — 기사 호출 시 `403 ESCORT_ONLY` · **기능 ID** BRD-01 · BRD-02 · BRD-04 · BRD-06

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `status` | enum | ● | `boarded` · `no_show` · `alighted` |
| `verify_method` | enum | ● | `photo` · `manual` — 사진 + 명단 육안 확인. 태그 미사용 |
| `client_key` | string | ● | 오프라인 큐 멱등키 (UUID) |
| `occurred_at` | datetime | ○ | 단말 기록 시각. 오프라인 처리분의 실제 시각 |

**응답**

| 필드 | 타입 | 설명 |
|---|---|---|
| `rider_id` · `status` · `changed_at` | — | 반영 결과 |
| `no_show_case` | object | `status=no_show` 일 때 — `case_id` · `started_at` · `expires_at`(**3분** 후) |
| `stop_skipped` | boolean | 잔여 탑승자 0명 전환 여부 (C-05) |

```json
{
  "rider_id": "rider_5521",
  "status": "no_show",
  "changed_at": "2026-08-24T08:42:03+09:00",
  "no_show_case": {
    "case_id": "nsc_442",
    "started_at": "2026-08-24T08:42:03+09:00",
    "expires_at": "2026-08-24T08:45:03+09:00"
  },
  "stop_skipped": false
}
```

**연쇄 처리 (BRD-04)** — 상태 변경은 ① 학부모 푸시 ② 관계자 실시간 현황 ③ 알림 로그 **3곳에 동시 반영, 5초 이내**.

| 상태 | 학부모 알림 | 관계자 |
|---|---|---|
| `boarded` | 승차 알림 | 실시간 현황 갱신 |
| `alighted` | 하차 알림 | 실시간 현황 갱신 |
| `no_show` | **즉시 발송** | 미승차 카운트 +1 + **에스컬레이션 시작** |
| `absent` | **부재** — 학부모가 스스로 설정한 값 | 미등원 카운트 +1 |

**에러** — `403 ESCORT_ONLY` · `409 RUN_NOT_MOVING` · `422 VALIDATION_FAILED` · `404 RIDER_NOT_FOUND`(미존재 탑승자 · `absent` 로 명단에서 제외된 탑승자) · `404 RUN_NOT_FOUND`

### 4.7 POST /runs/{runId}/riders/{riderId}/revert

상태 정정 (BRD-05).

**권한** 동승자 전용 · **요청** `reason` (string, 선택) · **응답** `status`(되돌린 값) · `reverted_at`

**이력 보존** — 누가·언제·무엇을 바꿨는지 저장. 기발송 알림은 후속 처리 대상

**에러** — `403 ESCORT_ONLY` · `409 RUN_NOT_MOVING` · `404 RIDER_NOT_FOUND` · `404 RUN_NOT_FOUND`

### 4.8 POST /runs/{runId}/riders/{riderId}/no-show-contacts

미승차 연락 시도 기록 (EXC-01). 대기 시작·종료 시각, 연락 시도 이력, 최종 판단을 저장하는 요건에서 도출.

**권한** 동승자 전용

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `attempt_type` | enum | ● | `call` · `message` |
| `result` | enum | ● | `answered` · `no_answer` |
| `decision` | enum | ○ | `depart` · `retry` — **3분** 경과 후 최종 판단 |

`result=answered` 면 카운트다운 중단. **3분** 경과 + 무응답이면 관계자 에스컬레이션 보고.

**에러** — `403 ESCORT_ONLY` · `404 NO_SHOW_CASE_NOT_FOUND`(`no_show` 미처리 탑승자에 연락 기록 시도) · `404 RIDER_NOT_FOUND` · `409 RUN_NOT_MOVING`

### 4.9 POST /runs/{runId}/delay

지연 알림 (NTF-06, M-05). **동승자 전용** — 기사 호출 시 `403 ESCORT_ONLY`.

**권한** 해당 회차에 배치된 **동승자**. 기사는 발신 대상 밖 — 운전 중 문구를 고르고 다듬는 조작 자체가 위험 (C-06 과 같은 근거)

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `minutes` | integer | ● | **5분 단위**만 허용. 그 외 `422 VALIDATION_FAILED` |
| `reason` | enum | ● | `traffic` · `weather` · `vehicle_check` · `prev_stop_wait` |
| `message` | string | ○ | 프리셋 문구를 수정한 값. 미전달 시 `reason` 기반 자동 생성 |

**응답** `201` — `notified_guardians` · `notified_students` · `notified_staff`(boolean)

**수신 범위** — 셋 다 보낸다.

| 대상 | 범위 |
|---|---|
| **학원 관계자** | 전원. 학부모 문의가 학원으로 먼저 오므로 상황을 미리 알아야 함 |
| **학생 · 학부모** | **현재 승하차지 이후** 승하차지의 대상자. 이미 탑승한 학생은 제외 |
| `absent` 학생 | 대상 밖 (C-02) |

**수신 설정 대상 밖** — 지연 알림은 설정 항목 자체가 부재하고 항상 발송 (NTF-07). 미리보기는 클라이언트가 구성.

**에러** — `403 ESCORT_ONLY`(기사 호출) · `422 VALIDATION_FAILED`(`minutes` 가 **5분 단위** 아님) · `409 RUN_NOT_MOVING` · `404 RUN_NOT_FOUND`

### 4.10 운행 종료 — 전용 엔드포인트 부재 (RUN-05·06)

**기사가 호출하는 종료 API 가 부재.** 종료는 `POST /runs/{runId}/stops/{stopId}/arrive`(§4.5)의 최종 지점 처리, 또는 `PATCH /runs/{runId}/riders/{riderId}`(§4.6)의 마지막 `alighted` 로 **서버가 전이**시킴 (C-15).

| 종료 경로 | 트리거 | 전이 시점 |
|---|---|---|
| 등원 | §4.5 최종 지점 도착 처리 | 즉시 `finished` + 전원 자동 `alighted` (C-07) |
| 하원 · 잔류 0명 | §4.5 최종 지점 도착 처리 | 즉시 `finished` |
| 하원 · 미하차 잔류 | §4.6 마지막 탑승자 `alighted` | 그 시점에 `finished` — 그 전까지 `moving` 유지 |

전이와 함께 위치 송신 중단 · 관계자 종료 통지 · WS `run_ended` 발행. **미하차 상태로 `finished` 에 도달하는 경로가 부재** — 기사 조작으로 우회 불가 (RUN-06).

### 4.11 POST /runs/{runId}/ack-changes

노선 변경 확인 응답 (RUN-07, M-04).

**권한** 버스기사 · 동승자 · **요청** `change_ids[]` (array, 선택 — 미전달 시 전건 확인) · **응답** `acked_at`

미확인 상태는 관계자 화면에 표시 (MON-05). 색 표시만으로는 실제 확인 여부 관측 불가 — 응답 기록이 근거.

**에러** — `409 RUN_NOT_CONFIRMED`(확정 전 회차) · `404 RUN_NOT_FOUND` · `403 FORBIDDEN`(배치되지 않은 회차)

### 4.12 POST /runs/{runId}/position

위치 업로드 (LOC-01).

**권한** 버스기사 (운행 단말) · **주기** **5~10초** · **조건** `run_status=moving` 에서만. 그 외 `409 RUN_NOT_MOVING`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `lat` · `lng` | number | ● | 좌표 |
| `recorded_at` | datetime | ● | 단말 측정 시각 |
| `speed` · `heading` | number | ○ | 속도 · 진행 방향 |

응답 `204`. 서버는 이 좌표를 근거로 "곧 도착합니다" 예고 알림(NTF-04)을 자동 발송하고 WebSocket `position` 이벤트를 방송.

**근접 판정 기준** (2026-08-31 사용자 확정, Ruling 207) — **다음 미도착 승하차지까지 직선거리 300m 이내**로 진입한 최초 1회. 상한은 코드 상수(§7 규칙 10).

- **거리 기준인 이유** — `ARCHITECTURE §10.1` 이 *"계획 ETA 근사로 판정하면 지연 운행에서 어긋난다"* 로 시간 기준을 이미 배제한다.
- **직선거리(Haversine)인 이유** — 노선 경로 거리로 재려면 **5~10초마다 외부 도로 경로 API 를 호출**하게 되어 `§8.5 MAP_ROUTE_UNAVAILABLE` 의 보호 대상이 하나 더 늘고, 서킷이 열리면 알림이 통째로 멈춘다. 직선거리는 실제 도로 거리보다 **짧게** 나오므로 예고가 늦어지는 쪽으로 치우치며, 그 편향은 300m 를 넉넉히 잡아 흡수한다.
- **최초 1회인 이유** — 버스가 같은 반경을 들락거리면 같은 학부모에게 반복 발송된다. 중복 차단은 `notification_log.dedup_key` UNIQUE 가 맡는다(`ARCHITECTURE §11`).

**에러** — `409 RUN_NOT_MOVING` · `403 DRIVER_ONLY`(운행 단말은 기사) · `404 RUN_NOT_FOUND`

### 4.13 POST /runs/{runId}/reports

현장 상황 보고 (EXC-03) · 보호자 부재 등록 (EXC-02).

**권한** 버스기사 · 동승자

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `type` | enum | ● | `guardian_absent`(EXC-02) · `road_block` · `vehicle_issue` · `etc` |
| `memo` | string | ● | 상황 기술 |
| `rider_id` | string | 조건부 | `type=guardian_absent` 필수 |

응답 `201` — `report_id` · `reported_at`. 관계자에게 즉시 통지.

보호자 부재는 `can_go_alone=false` 학생이 대상. **MVP 범위는 보고까지** — 재승차·대체 보호자 결정·인계 완료 판정은 미도입이며 `alighted` 가 최종 상태 (FEATURE_SPEC A-10). 인계 완료까지 사건을 미종결로 두는 처리는 2단계 (PRD §10 E-05).

**에러** — `422 VALIDATION_FAILED`(`type=guardian_absent` 인데 `rider_id` 부재) · `404 RIDER_NOT_FOUND` · `404 RUN_NOT_FOUND`

---

### 4.14 POST /runs/{runId}/emergency · DELETE /runs/{runId}/emergency/{id}

비상 알림 발신·취소 (EXC-04, M-15). **기사·동승자 둘 다 발신 가능** — 안전 사안이라 역할 제한 부재.

**권한** 해당 회차에 배치된 기사 또는 동승자

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `type` | enum | ● | `accident`(사고) · `vehicle_fault`(차량 고장) · `student_emergency`(학생 응급) · `etc` |
| `memo` | string | ○ | 상황 메모. `type=etc` 이면 필수 |
| `lat` · `lng` | number | ○ | 발신 시점 좌표. 미전달 시 서버가 최신 수신 좌표로 대체 |
| `occurred_at` | datetime | ○ | 단말 기록 시각. 오프라인 발신분의 실제 시각 |
| `client_key` | string | ● | 오프라인 큐 멱등키 (UUID) |

**응답** `201` — `emergency_id` · `raised_at` · `cancelable_until`(발신 +**1분**) · `notified`(수신자 수)

| 처리 | 내용 |
|---|---|
| 첨부 | 회차 · 호차 · 발신자 · 기사·동승자 연락처 · **발신 시점 위치** · 탑승자 수를 서버가 자동 결합 |
| 수신 | **학원 관계자 + 메인 관리자 동시.** 설정 항목 부재라 항상 발송 + 팝업 (C-17) |
| 학부모·학생 | **수신 대상 밖** — 안내 시점·문구는 관계자가 판단 |
| 발신 시점 | `run.status` 가 `confirmed` 이후면 허용. 운행 중이 아니어도 가능 |
| 중복 | 차단 부재 — 상황 변화마다 재발신이 정상 |
| 취소 | `DELETE` 로 **1분 이내**만. 취소 사실도 수신자에게 통지되고 **레코드는 존치**(`canceled_at` 기록) |

**에러** — `403 FORBIDDEN`(배치되지 않은 회차) · `409 RUN_NOT_CONFIRMED` · `409 EMERGENCY_CANCEL_WINDOW_CLOSED`(취소 창 경과) · `404 EMERGENCY_NOT_FOUND` · `404 RUN_NOT_FOUND` · `422 VALIDATION_FAILED`(`type=etc` 인데 `memo` 부재)

---

### 4.15 GET /runs/{runId}/emergencies

발신한 비상 알림의 처리 상태 조회 (EXC-04, M-15). **관계자 확인 결과를 발신자에게 되돌리는 경로.**

**권한** 해당 회차에 배치된 기사·동승자

**응답** — `items[]` — `emergency_id` · `type` · `raised_at` · `cancelable_until` · `acked`(boolean) · `acked_at` · `acked_by_name` · `canceled_at`

`acked=true` 이면 매니저 앱에 **"학원이 확인했습니다"** 표시 (A-16). 실시간 반영은 WS `/ws/manager/runs/{id}` 의 `emergency_acked` 이벤트, 이 엔드포인트는 진입 시 초기 상태 조회와 폴백용.

**발신 응답을 놓친 경우의 `emergency_id` 재취득 경로**도 겸함 — 취소(§4.14 `DELETE`)에 필요.

**에러** — `403 FORBIDDEN`(배치되지 않은 회차) · `404 RUN_NOT_FOUND`

### 4.16 GET /runs/{runId}/navigation

외부 내비게이션 앱 연동 (**RUN-08**, M-09). **2026-08-26 신설 — 사용자 요청.**

확정 노선을 외부 내비 앱으로 넘기기 위한 **좌표열**을 반환한다. **MVP 는 카카오내비 단독**(2026-08-31 확정, Ruling 204). **서버는 딥링크 URL 을 만들지 않는다** — 아래 "서버가 하는 일 / 앱이 하는 일" 참조.

**권한** 해당 회차에 배치된 기사·동승자 (§4.3 과 같은 범위)

**요청 (query)**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `scope` | enum | ○ | `next`(기본 — 다음 목적지 1개) · `remaining`(남은 전 구간) |

⚠ **어느 내비 앱을 쓸지는 요청이 고르지 않는다 (Ruling 201, 2026-08-31).** 활성 공급자는 서버 설정 `app.navigation.provider` 하나가 정하고 응답이 알려준다. 요청 파라미터로 받으면 **공급자를 바꿀 때 앱을 새로 배포**해야 하는데, 정작 바뀌는 값(경유지 상한)은 서버만 아는 것이라 앱이 고를 근거가 없다.

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `provider` | enum | ● | **서버가 정한 활성 공급자.** 앱은 이 값으로 띄울 내비를 고른다 |
| `origin` | object | ○ | `lat` · `lng` · `name` — 출발지. `moving` 이면 **미반환**(앱이 현재 위치를 쓴다) |
| `waypoints[]` | array | ● | 경유지. `lat` · `lng` · `name` · `stop_id` · `seq`. **순서가 곧 주행 순서** |
| `destination` | object | ● | `lat` · `lng` · `name` · `stop_id` — 최종 목적지 |
| `truncated` | boolean | ● | 상한 때문에 **잘렸는지 여부** |
| `truncated_reason` | string | ○ | `truncated=true` 일 때만 — 앱에 표시할 안내 문구 |
| `total_remaining_stops` | integer | ● | 자르기 **전** 남은 승하차지 수. `waypoints.length + 1` 과 다를 수 있다 |

**서버가 하는 일 / 앱이 하는 일**

| 서버 | 앱 |
|---|---|
| 승하차지 순서 확정 · `skipped` 제외 · 도착 완료분 제외 · **앱별 상한만큼 자르기** · 잘린 사실 표시 | 딥링크(URL scheme) 조립 · 앱 미설치 시 스토어 폴백 · 사용자 선택 |

**딥링크를 서버가 만들지 않는 이유** — URL scheme 은 OS·앱 버전·설치 여부·스토어 폴백까지 묶인 **클라이언트 영역**이고, 서버가 만들면 스킴이 바뀔 때마다 서버를 배포해야 한다. 반대로 **자를 개수 판단은 서버가 한다** — 앱이 자르면 클라이언트마다 다르게 잘라 같은 회차가 기기마다 다른 경로로 안내된다.

**`skipped` 승하차지는 넘기지 않는다** — `C-05` 는 "미경유는 **표시만**, 재최적화·경로 안내 부재" 인데, 내비에 넘기는 것은 표시가 아니라 **주행 안내**라 실제로 가지 않을 지점을 넣으면 기사를 그리로 보낸다.

**`arrived_at` 이 찍힌 승하차지는 제외**한다 — 이미 지난 지점이다.

**`confirmed` 회차(출발 30분 전 ~ 운행 시작 전)에서도 호출할 수 있다** — 막는 것은 `idle` 뿐이다. 노선이 확정된 시점부터 기사가 경로를 미리 볼 수단이 이 엔드포인트이기 때문이며(X-01 확정 사항, Ruling 202), 이때는 아직 출발 전이라 `origin` 에 **출발지 좌표를 담아 반환**한다. `moving` 이면 `origin` 을 비워 앱이 현재 위치를 쓰게 한다.

**공급자별 승하차지 상한** (오픈 이슈 **V** 해소 — 2026-08-31 실측, Ruling 204)

| 공급자 | 넘길 수 있는 승하차지 | 근거 | MVP |
|---|:-:|---|:-:|
| `kakao` | **4** = 경유지 3 + 목적지 1 | 카카오 SDK 레퍼런스 `navigateIntent(destination, option, viaList)` 의 `viaList` 가 **"경유지 목록(최대: 3개)"** 로 명시 | ● 단독 |
| `tmap` | 1 (목적지만) | 앱 실행 스킴의 경유지 파라미터에 **공식 근거 부재** — SK 가 스킴 규격을 미공개 | ➖ 미구현 |

⚠ **앱에서 사용자가 손으로 넣을 수 있는 경유지 수(카카오 5 · 티맵 5)와 다른 앱이 넘겨 줄 수 있는 수는 별개 값이다.** 손 입력 수를 상한으로 잡으면 실제보다 넓게 잡아 조용히 잘린다.

**MVP 는 `kakao` 단독** (2026-08-31 사용자 확정). `tmap` 은 **enum 값으로만 남기고 구현하지 않는다** — 상한 1 이라 `scope=remaining` 이 사실상 `next` 와 같아져 기능이 성립하지 않는다. 티맵 전환은 어댑터 1개 추가 + 설정 변경으로 닫는다.

**에러** — `409 RUN_NOT_CONFIRMED`(확정 전 `idle` 회차) · `409 NAV_NO_REMAINING_STOP`(남은 승하차지 부재 — 전 구간 도착 완료) · `404 RUN_NOT_FOUND` · `403 FORBIDDEN`(배치되지 않은 회차)

---

## 5. 관계자 웹

학원당 관계자 **1명**. 모든 응답은 소속 학원 범위로 격리 (§1.5).

### 5.1 GET /staff/signup-requests

가입 요청 목록 (AUTH-10, A-02).

**권한** 학원 관계자 · **요청 (쿼리)** `status` (enum, 선택 — `pending` 기본) · 페이징

**응답** — `items[]` + `pending_count`(미처리 배지)

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `request_id` | string | ● | |
| `name` | string | ● | 신청자 이름 |
| `role` | enum | ● | `parent` · `student` · `driver` · `escort` |
| `phone` | string | ● | 연락처 |
| `requested_at` | datetime | ● | 신청 일시 |

`role=staff` 요청은 이 목록의 대상 밖 — 메인 관리자 경로(§6.5).

**에러** — §1.11 공통 항목 외 고유 에러 부재. 미처리 요청 0건은 빈 `items[]` 로 반환.

### 5.2 POST /staff/signup-requests/{id}/decide

수락 / 거절 (AUTH-10·11, A-02).

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `accept` | boolean | ● | `true` = 수락 |
| `reject_reason` | string | 조건부 | `accept=false` 필수 |
| `link.student_ids[]` | array | 조건부 | `role=parent` · `student` 수락 시 필수 |
| `link.manager_id` | string | 조건부 | `role=driver` · `escort` 수락 시 필수 |

**수락 시 계정 ↔ 실제 레코드 연결이 필수** (AUTH-11) — 누락 시 `422 LINK_REQUIRED`. 연결 부재 계정은 데이터 접근 불가.

**응답** — `account_status`(`active` · `rejected`) · `decided_at`. 결과는 신청자에게 알림 통지.

다자녀는 **연결 추가만** 수행 — 학부모 재가입 부재.

**에러** — `422 LINK_REQUIRED`(수락 시 학생·매니저 레코드 연결 누락) · `409 APPROVAL_ALREADY_DECIDED`(이미 처리된 요청) · `409 SIGNUP_TARGET_BLOCKED`(승인 대상 계정이 `blocked` — §8.1) · `404 SIGNUP_REQUEST_NOT_FOUND` · `404 STUDENT_NOT_FOUND`(`link.student_ids[]` 대상 부재) · `404 MANAGER_NOT_FOUND`(`link.manager_id` 대상 부재) · `403 FORBIDDEN`(`role=staff` 요청 — 메인 관리자 경로 §6.5) · `422 VALIDATION_FAILED`(`accept=false` 인데 `reject_reason` 부재)

### 5.3 GET /staff/dashboard

운행 대시보드 · 금일 현황 (MON-01·02·03·04·06, A-03).

**권한** 학원 관계자 · **요청 (쿼리)** `date` (date, 선택)

**응답**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `metrics.moving_buses` | integer | ● | 운행 중 차량 수 |
| `metrics.boarded` | integer | ● | 승차 완료 인원 |
| `metrics.no_show` | integer | ● | 미승차 인원 (MON-04) |
| `metrics.absent` | integer | ● | 미등원 인원 (MON-06) |
| `metrics.unassigned_managers` | integer | ● | 배치 대기 매니저 수 |
| `runs[]` | array | ● | 금일 회차 표 |

**`runs[]`**

| 필드 | 타입 | 설명 |
|---|---|---|
| `run_id` · `bus_no` · `direction` · `depart_time` | — | 회차 요약 |
| `driver_name` · `escort_name` | string | 배치 인력 |
| `boarded_count` / `total_count` | integer | 탑승 현재/전체 |
| `run_status` | enum | `idle` · `confirmed` · `moving` · `finished` |
| `added_count` · `removed_count` | integer | 변경분 (MON-05) |
| `ack_driver` · `ack_escort` | boolean | 기사·동승자 변경 확인 응답 여부 (RUN-07) |
| `no_show_cases[]` | array | 진행 중 에스컬레이션 — `student_name` · `stop_name` · `expires_at` |

실시간 갱신은 WebSocket `/ws/academy/{id}/live` (§7).

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 5.4 GET /staff/runs/{runId}/roster

호차별 일일 명단 (RST-03, A-04).

**응답** — `items[]` (학생 단위 표)

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `student_id` · `name` | string | ● | |
| `class_name` | string | ○ | 반 |
| `stop_name` | string | ● | 승하차지 |
| `guardian_phone` | string | ● | **원문** — 관계자 웹은 마스킹 대상 밖 |
| `change` | enum | ○ | `added`(초록) · `removed`(빨강) |
| `status` | enum | ● | `waiting` · `boarded` · `alighted` · `absent` · `no_show` |
| `note` | string | ○ | 비고 (STU-07) |

**`absent` 는 관계자 웹에서 빨강으로 계속 표시** — 매니저 앱(행 제외)과 상반. 관리자는 누가 왜 빠졌는지 확인이 필요.

**에러** — `404 RUN_NOT_FOUND`. 확정 전(`idle`) 회차도 조회 가능 — 진입 차단은 매니저 앱 전용 (M-02)

### 5.5 GET /staff/approvals · GET /staff/approvals/{id}

30분 안쪽 변경 승인 대기 목록·상세 (REQ-04·05, A-05). 접수 시 푸시 통지.

⚠ **목록과 상세를 가른 이유** — 재최적화 결과(`route_preview`)는 계산 비용이 크다. 목록 항목마다 계산하면 대기 건이 N개일 때 **한 번의 목록 조회에 N회 최적화**가 동기로 실행되어 응답이 지연. 승인 화면은 한 건씩 열므로 **목록은 요약만, 대조는 상세에서 1건만 계산** (ARCHITECTURE §8.4).

**권한** 학원 관계자 (양쪽 공통)

#### 목록 — `GET /staff/approvals`

**요청 (쿼리)** `status` (enum, 선택 — 기본 `pending`)

**응답** — `items[]` · `pending_count`. **재최적화를 실행하지 않음** — 저장된 값과 단순 집계만 반환.

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `approval_id` | string | ● | |
| `source` | enum | ● | `intent`(등하원 토글) · `change_request`(일일 스케줄 변경) |
| `student_name` · `run_id` · `bus_no` · `direction` | — | ● | 대상 |
| `deadline_at` | datetime | ● | 승인 마감 = 회차 출발 시각. **운행이 먼저 시작되면 그 시점이 실제 마감** — 카운트다운은 이 값 기준이나 `moving` 전이 시 즉시 종결 |
| `stop_name` | string | ● | 대상 승하차지 |
| `remaining_riders` | integer | ● | 해당 승하차지 잔여 인원 |
| `will_remove_stop` | boolean | ● | 승인 시 해당 승하차지가 노선에서 제거되는지 (잔여 0명) |
| `requested_at` | datetime | ● | 접수 시각 — 대기 시간 표시용 |

#### 상세 — `GET /staff/approvals/{id}`

승인 화면 진입 시 호출. **이 시점에 재최적화를 1회 실행**해 전/후 대조를 산출.

**응답** — 목록 항목 전체 + 아래.

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `route_preview` | object | ● | **재최적화 결과 미리보기** — `stops_before[]` · `stops_after[]`(각 `seq` · `stop_name` · `eta`), `reordered[]`(순서가 바뀌는 승하차지), `removed[]` |
| `est_time_before` · `est_time_after` | datetime | ● | 재최적화 전/후 예상 도착 시각 |
| `est_distance_before` · `est_distance_after` | number | ● | 재최적화 전/후 총 운행 거리(km) |
| `affected_students[]` | array | ● | 영향 학생 — `student_id` · `name` |
| `capacity` | object | ● | `student_capacity` · `assigned` — 정원 |
| `preview_token` | string | ● | 이 미리보기의 식별자. `POST .../decide` 에 그대로 전달해 **화면에서 본 결과와 배포되는 결과의 동일성**을 보장 |
| `preview_stale` | boolean | ● | 미리보기 산출 후 입력(명단·승하차지·경유 지점)이 바뀌었는지. `true` 면 재조회 안내 |

```json
{
  "items": [
    {
      "approval_id": "apv_771",
      "source": "intent",
      "student_name": "이하윤",
      "run_id": "run_20260824_3_am",
      "bus_no": "3호차",
      "direction": "to_academy",
      "deadline_at": "2026-08-24T08:30:00+09:00",
      "stop_name": "한빛아파트 정문",
      "remaining_riders": 0,
      "will_remove_stop": true,
      "requested_at": "2026-08-24T08:12:41+09:00"
    }
  ]
}
```

**에러** — §1.11 공통 항목 외 고유 에러 부재. 대기 건 0개는 빈 `items[]` 로 반환.

### 5.6 POST /staff/approvals/{id}/decide

승인 / 거절 (REQ-04, A-05).

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `approve` | boolean | ● | |
| `reject_reason` | string | 조건부 | `approve=false` 필수 |
| `preview_token` | string | 조건부 | `approve=true` 필수 — §5.5 상세에서 받은 값. 불일치·만료 시 `409 PREVIEW_STALE` 로 재확인 요구 |

**승인 시 처리** — 명단 제외(`absent`) → 잔여 0명이면 해당 승하차지를 노선에서 제거 → **노선 재최적화** → 기사·동승자에 **재배포**(확인 응답 대상, RUN-07) + 학부모 통보. 관리자는 §5.5 의 `route_preview` 로 전/후 차이를 확인한 뒤 승인 (C-04 ②).

**거절 시** — **기존 경로 유지**(재최적화 부재) + 사유와 함께 학부모 통보. `status=rejected`.

**응답** — `status`(`approved` · `rejected`) · `stop_removed`(boolean) · `route_version`(재배포된 노선 버전) · `decided_by` · `decided_at`.

**승인 이력 저장 — 책임 소재.** 누가·언제·무엇을·자동 거절 여부를 기록.

**출발 시각 도달 또는 `Run.status` → `moving` 중 먼저 오는 시점**에 미처리 요청은 서버가 **자동 거절** — 재최적화 없이 기존 노선 유지 + 학부모 통지 → `status=auto_rejected`, **횟수 미소진** (C-04). 그 시점 이후 도달한 승인 조작은 `409 CHANGE_WINDOW_CLOSED` 로 반영 부재.

**에러** — `409 APPROVAL_ALREADY_DECIDED` · **`403 CHANGE_WINDOW_CLOSED`**(운행 시작 후 도달 — ⚠ **2026-08-30 정정, Ruling 200.** 원래 `409` 로 적혀 있었으나 이 코드의 정의 자리인 **§8.3 사전이 403** 이고, 이 문서의 다른 **8곳이 전부 403**(§1.6 ③ · §3.6 · §3.8 · §5.7 · §5.8 · §5.15 · §8.3)이라 **이 한 줄만 어긋나 있었다.** `ErrorCode` 는 코드 하나에 상태 하나를 싣는 구조라 두 값을 함께 둘 수 없고, 새 코드를 만드는 것은 "새 상태값을 만들지 않는다"(`CLAUDE.md`)에 걸린다. 사전이 정의고 각 절은 사용처이므로 **사전이 이긴다**) · `409 PREVIEW_STALE`(미리보기 이후 입력 변경 — 재조회 후 재시도) · `404 APPROVAL_NOT_FOUND` · `422 VALIDATION_FAILED`(`approve=false` 인데 `reject_reason` 부재)

### 5.7 POST /staff/runs/{runId}/forced-add

노선 강제 추가 (RTE-06, A-06).

⚠ **`[조정 중]` 을 2026-08-30 부분 해제했다**(Ruling 197, §5.9 와 같은 논리). 보류 사유였던 "배차 정책 확정 후" 가 실제로 가리키던 것은 **최적화 트리거·가중치**(오픈 이슈 G)이고, 이 엔드포인트는 노선 재최적화를 부르지 않는다(Ruling 198 — ①구간의 회차는 아직 `idle` 이라 확정 노선이 부재하다) — 가중치와 무관하다. **아직 해제되지 않은 것은 §5.8(수동 조정)뿐이다**, 그쪽은 도착 버스의 재최적화·배포를 실제로 수반해 가중치 미확정의 영향을 받는다.

| 항목 | 값 |
|---|---|
| 권한 | 학원 관계자 |
| 목적 | ① 구간에서만 당일 운행에 탑승자 추가. 고정 노선 불변 |
| 구간 | **① 구간 전용** — 30분 안쪽은 관계자도 추가 불가, `403 CHANGE_WINDOW_CLOSED` |
| 정원 | 초과 시 `409 CAPACITY_EXCEEDED`. 기준은 `student_capacity`(BUS-04 — `capacity − 기사 − 동승자`) |
| 주소 | 검증 → 승하차지 매칭/신규 생성 (STU-05) |
| 반영 시점 | 저장만 하고 끝난다 — 그날 명단에 실제로 합쳐지는 것은 이후 도래하는 확정 배치(RTE-08)다(Ruling 198) |

**요청**

| 필드 | 필수 | 설명 |
|---|:---:|---|
| `student_id` | 조건부 | 기존 학생 — 검색·계정 연동으로 찾은 학생의 id. `new_student` 와 **배타적**(둘 다 없거나 둘 다 있으면 `422 VALIDATION_FAILED`) |
| `new_student.name` | 조건부 | 신규 학생 직접 입력 — 이름만 받는다. 사진 등록 등 전체 등록 절차(STU-01)는 이 경로를 거치지 않는다 |
| `address` | ● | 오늘 이 회차에서 탑승할 주소. 기존 학생이라도 평소 등록된 요일별 주소와 별개로 **이 회차 전용**으로 검증한다 |
| `note` | ○ | 비고 |

**응답 (201)**

| 필드 | 설명 |
|---|---|
| `forced_addition_id` | 강제 추가 대기 행 id |
| `run_id` | 대상 회차 |
| `student_id` | 확정된(또는 새로 만든) 학생 id |
| `stop_id` | 매칭·생성된 승하차지 id |
| `status` | 항상 `staged` — 확정 배치가 명단에 합칠 때까지의 대기 상태 |

**에러** — `403 CHANGE_WINDOW_CLOSED`(② 구간 이후 추가 — 관계자도 예외 부재) · `409 CAPACITY_EXCEEDED`(정원 초과) · `422 ADDRESS_VERIFICATION_FAILED`(주소 검증 실패 — 저장 보류) · `422 VALIDATION_FAILED`(`student_id`·`new_student` 가 동시에 없거나 있음) · `404 RUN_NOT_FOUND`

### 5.8 POST /staff/students/{id}/transfer — **[조정 중]**

수동 조정 · 버스 간 이동 (RTE-07, A-07).

**권한** 학원 관계자 · **목적** 출발 버스 명단 제외 + 도착 버스에 강제 방문지 또는 기존 승하차지 추가 → 양쪽 노선 재최적화·배포, 양쪽 영향 반환

요청·응답 세부는 배차 정책 확정 후 기술.

**에러** — `409 CAPACITY_EXCEEDED`(도착 버스 정원 초과 — 현재 인원·정원 병기) · `403 CHANGE_WINDOW_CLOSED`(② 구간 이후는 추가에 해당 — UF-M-04)

### 5.9 고정 노선 편성 · 정차 순서 최적화 (RTE-01 · RTE-09, A-08)

**권한** 학원 관계자 · **목적** 학생 요일별 주소 기반 노선 편성, 정차 순서 최적화. 당일 확정 노선(30분 전 산출)과 별개

⚠ **`[조정 중]` 을 2026-08-29 부분 해제했다**(Ruling 180, §5.10 과 같은 논리). 보류 사유였던 "배차 정책 확정 후" 가 실제로 가리키던 것은 **최적화 트리거·가중치**(오픈 이슈 G)이고, 편성 CRUD 는 `ERD route`·`route_stop` 이 컬럼·UNIQUE·CHECK 까지 확정 문면을 달고 있어 가중치와 무관하다. **아직 해제되지 않은 것은 아래 "미확정으로 남긴 것" 뿐이다.**

| 메서드 · 경로 | 기능 ID | 설명 |
|---|---|---|
| `GET /staff/routes` | RTE-01 | 목록 (§1.8 페이징). **비활성 편성도 실린다** — 편성 이력을 화면에서 되살릴 수 있어야 한다 |
| `POST /staff/routes` | RTE-01 | 편성. 응답 `201` |
| `GET /staff/routes/{id}` | RTE-01 | 상세 — 정차 순서를 `seq` 차례로 함께 싣는다 |
| `PATCH /staff/routes/{id}` | RTE-01 | 수정 — §1.9 대로 변경 후 자원 상태를 그대로 반환 |
| `DELETE /staff/routes/{id}` | RTE-01 | 삭제. **행을 지운다**(soft delete 부재) — 정차 순서도 `route_stop` FK CASCADE 로 함께 사라진다 |
| `POST /staff/routes/{id}/optimize` | RTE-09 | 정차 순서 최적화. 결과는 상세와 같은 형태 |

**`POST` · `PATCH /staff/routes` 요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `bus_id` | integer | ● | 운행 차량. 소속 학원 밖이면 `404 BUS_NOT_FOUND` |
| `weekday` | enum | ● | `mon`~`sun` (§9.8) |
| `direction` | enum | ● | `to_academy` · `from_academy` (§9.7) |
| `name` | string | ○ | 편성 이름. 최대 100자 |
| `active` | boolean | ○ | 기본 `true` |
| `stop_ids` | integer[] | ○ | 정차할 승하차지. **배열 순서가 그대로 `seq`(1부터 빈틈 없이) 가 된다** — 최적화를 호출하기 전까지 관계자가 정한 차례가 유지된다. 같은 승하차지를 두 번 담거나 소속 학원 밖 승하차지가 섞이면 `422 VALIDATION_FAILED` |

`PATCH` 는 보낸 필드만 고친다. **`bus_id`·`weekday`·`direction` 셋이 유일성 조합**이라, 그중 하나만 고쳐도 기존 편성과 충돌하면 `409 DUPLICATE_ROUTE`.

- 유일성의 근거는 **`route(bus_id, weekday, direction)` UNIQUE**(`uk_route_bus_weekday_direction`)이고 애플리케이션 선검사가 아니다. 선검사는 흔한 경우의 응답을 다듬을 뿐이고, **동시 2요청은 서로의 미커밋 INSERT 를 보지 못한 채 둘 다 선검사를 지난다** — 제약 위반을 `409` 로 번역하지 않으면 그 경합이 `500` 으로 샌다 (§5.10 일일 회차 생성과 같은 형태)
- **중복 검사는 유일성 조합을 실제로 옮기는 수정에만 걸린다.** 같은 값을 그대로 다시 보내는 `PATCH` 가 **자기 자신을 중복으로 세면** 이름만 고치는 요청이 `409` 로 막힌다
- `stop_ids` 를 보내면 **기존 정차 순서를 전부 대체**한다. 일부만 고치는 경로를 두지 않는 것은 순번이 배열 전체의 성질이라 부분 수정의 의미가 정해지지 않기 때문이다
- **`stop_ids` 를 주지 않은 편성은 정차지 없이 시작한다** — 차량·요일·방향 칸을 먼저 잡아 두고 승하차지를 나중에 채우는 조작이 실재한다. `PATCH` 에서 생략하면 기존 정차 순서를 그대로 둔다(비우려면 빈 배열을 보낸다)

**응답** — `id` · `bus_id` · `bus_no` · `weekday` · `direction` · `name` · `active`. 상세·편성·수정·최적화는 여기에 **`stops[]`**(`stop_id` · `seq` · `name` · `lat` · `lng`)를 더한다.

**`POST /staff/routes/{id}/optimize` 요청** — `origin`(`lat`·`lng`) · `destination`(`lat`·`lng`) **둘 다 필수**

⚠ **좌표를 호출자가 넘기는 것은 현재 스키마에서 유일한 선택지다**(Ruling 184). `academy`·`route` 어느 쪽에도 **좌표 컬럼이 부재**하다. 서버가 정차지 중 하나를 골라 기준점으로 쓰는 안은 **그 고름이 요청·응답 어디에도 남지 않아 산출 조건이 관측 불가**가 되어 기각했다 — `TECH_DECISIONS §8.5.1`("왜 이 순서로 돌았나를 재현 가능하게")과 정면으로 어긋난다. **Phase 7 이 `academy` 좌표 컬럼을 추가하면 이 계약이 바뀐다.**

**최적화는 명시적 호출뿐이다** — 편성·수정이 순서를 자동으로 재배열하지 않는다. 가중치가 미확정인 상태(오픈 이슈 G)에서 자동 재배열을 두면 **기준 없는 재배열이 조용히 돌아 관계자가 정한 차례가 이유 없이 뒤집힌다.**

**미확정으로 남긴 것** — 최적화 **자동 트리거**와 **가중치 기준**. 가중치는 `PRD §7.1` 이 P2 후속(F-01)에 뒀고, 오픈 이슈 G 가 닫히기 전까지 트리거를 만들지 않는다.

**에러** — `409 DUPLICATE_ROUTE`(같은 차량·요일·방향이 이미 편성됨) · `404 ROUTE_NOT_FOUND` · `404 BUS_NOT_FOUND` · `422 VALIDATION_FAILED`(`stop_ids` 중복·학원 밖) · `503 MAP_ROUTE_UNAVAILABLE`(외부 도로 경로 API 서킷 개방 — §8)

⚠ **`stop_ids` 의 두 거부 사유는 현재 구현의 응답에서 구별되지 않는다** — 중복이든 학원 밖이든 `422 VALIDATION_FAILED` 이고, 사유를 가르던 문구가 응답에 도달하지 않는다(`GlobalExceptionHandler` 가 `ErrorCode` 의 고정 문구만 싣는 저장소 전역 성질). **이 절이 사유를 둘로 적은 것은 사양이 그렇게 요구하기 때문**이고, 구현이 그것을 전달하지 못하는 것은 **별도 단위로 등재된 사안**이다 — 화면이 사유를 갈라 안내해야 하면 `ErrorCode` 를 나누는 것이 현재 유일한 수단이다.

### 5.10 운행 스케줄 · 일일 회차 (SCH-01~03, A-09)

**권한** 학원 관계자 · **목적** 요일·시간별 운행 계획 등록 → 일일 회차 자동 생성. 특정일 회차 임시 추가·취소 포함, 정규 스케줄 불변

⚠ **`[조정 중]` 을 2026-08-26 해제했다**(Ruling 153). 보류 사유였던 "배차 정책 확정 후" 가 실제로 가리키던 것은 **동승자 자동 배정**(§5.14)이고, 스케줄 CRUD·회차 생성·임시 조정은 `ERD schedule`·`run` 이 컬럼·CHECK·UNIQUE 까지 확정 문면을 달고 있어 배차 정책과 무관하다.

| 메서드 · 경로 | 기능 ID | 설명 |
|---|---|---|
| `GET /staff/schedules` | SCH-01 | 목록 (§1.8 페이징) |
| `POST /staff/schedules` | SCH-01 | 등록 |
| `PATCH /staff/schedules/{id}` | SCH-01 | 수정 — `active=false` 로 두면 다음 회차 생성부터 제외 |
| `DELETE /staff/schedules/{id}` | SCH-01 | 삭제. **행을 지운다**(soft delete 부재) — 이미 만들어진 회차는 `run.schedule_id` 가 NULL 이 되어 그대로 남는다 (`ERD` FK `SET NULL`) |
| `GET /staff/runs?service_date=` | SCH-02 | 그 날짜의 회차 목록. 생략하면 **오늘** |
| `POST /staff/runs` | SCH-03 | 특정일 회차 **임시 추가** — 스케줄에 없는 1회성 운행 |
| `DELETE /staff/runs/{id}` | SCH-03 | 특정일 회차 **임시 취소** — 행을 지우지 않고 `canceled_at` 을 채운다 |

⚠ **`GET /staff/runs` 는 `§5.18 GET /staff/runs/live` 와 다른 것이다** — 이쪽은 날짜로 보는 **회차 목록**(SCH-02 결과 확인), 저쪽은 관제용 **실시간 스냅샷**(MON-07)이다. 경로가 비슷해도 합치지 않는다.

**`POST` · `PATCH /staff/schedules` 요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `bus_id` | integer | ● | 운행 차량. 소속 학원 밖이면 `404 BUS_NOT_FOUND` |
| `weekday` | enum | ● | `mon`~`sun` (§9.8) |
| `direction` | enum | ● | `to_academy` · `from_academy` (§9.7) |
| `depart_time` | string | ● | `HH:mm`. **날짜가 없는 시각**이고, 회차 생성 시 `service_date` 와 합쳐 확정된다 |
| `origin_name` · `destination_name` | string | ● | 출발지 · 도착지. 운행 카드의 표시값 |
| `est_duration_min` | integer | ○ | 예상 소요시간(분) |
| `active` | boolean | ○ | 기본 `true`. `false` 인 스케줄은 회차를 만들지 않는다 |

`PATCH` 는 보낸 필드만 고친다. **`bus_id`·`weekday`·`direction`·`depart_time` 넷이 유일성 조합**이라, 그중 하나만 고쳐도 기존 스케줄과 충돌하면 `409 DUPLICATE_SCHEDULE`.

**응답** — `id` · `bus_id` · `bus_no` · `weekday` · `direction` · `depart_time` · `origin_name` · `destination_name` · `est_duration_min` · `active`

**`POST /staff/runs` 요청**(임시 추가) — `bus_id` · `service_date`(`YYYY-MM-DD`) · `direction` · `depart_time`(`HH:mm`) · `origin_name` · `destination_name` · `est_duration_min`(선택). 만들어진 회차는 **`schedule_id` 가 비어 있다** — 그것이 정규 스케줄에서 나온 회차와 임시 회차를 가르는 유일한 표시다.

**회차 응답 항목** — `id` · `bus_id` · `bus_no` · `schedule_id` · `service_date` · `direction` · `depart_time` · `confirm_at` · `status` · `origin_name` · `destination_name` · `est_duration_min` · `canceled_at` · `assignments[]`(`manager_id` · `name` · `role`)

- `depart_time`·`confirm_at` 은 **날짜를 포함한 시각**(`timestamptz`)이다. 스케줄의 `HH:mm` 을 `service_date` 와 합칠 때 시간대는 서비스 기준 시간대(`Asia/Seoul`, `ERD §2`)를 쓴다
- **`confirm_at` = `depart_time` − 30분**이며 파생이 아니라 저장된 컬럼이다 (`C-03` · `ERD run`). 확정 배치가 "실행 시각이 지난 회차" 를 매 실행마다 조회하기 때문에 컬럼으로 둔다
- `assignments[]` 를 함께 싣는 이유는 이 목록이 배치 화면의 읽기 축이기 때문이다 — 빼면 `§5.14` 로 배치한 결과를 되읽을 경로가 부재해진다

**일일 회차 생성 (SCH-02)** — 하루 1회 도는 배치가 그날 요일의 **`active=true` 스케줄**로 회차를 만든다. 전용 엔드포인트를 두지 않는다.

- **중복 실행은 오류가 아니라 무시다.** 재기동·수동 재실행이 정상 동작이므로 이미 있는 회차는 조용히 건너뛰고 생성 건수만 센다
- 멱등의 근거는 **`run(bus_id, service_date, direction, depart_time)` UNIQUE** 이고 애플리케이션 선검사가 아니다 — 동시 2회 실행은 서로의 미커밋 INSERT 를 보지 못한 채 둘 다 선검사를 지난다

**에러** — `404 SCHEDULE_NOT_FOUND`(`PATCH`·`DELETE` 대상 부재) · `404 BUS_NOT_FOUND`(지정 차량 부재·타 학원) · `409 DUPLICATE_SCHEDULE`(같은 `bus_id`·`weekday`·`direction`·`depart_time` 조합 중복) · `404 RUN_NOT_FOUND`(`DELETE /staff/runs/{id}` 대상 부재) · `409 DUPLICATE_RUN`(같은 차량·날짜·방향·출발 시각 회차 중복 추가) — 이상 2026-08-26 신설 (Ruling 153)

### 5.11 학생 관리 (STU-01~08, A-10)

| 메서드 · 경로 | 기능 ID | 설명 |
|---|---|---|
| `GET /staff/students?q=` | STU-01 | 목록·검색. 강제 추가 자동완성과 공용 |
| `GET /staff/students/{id}` | STU-01 | 상세 |
| `POST /staff/students` | STU-02 | 등록 |
| `PATCH /staff/students/{id}` | STU-03 | 수정 — 주소·보호자 연락처는 대상 밖 |
| `DELETE /staff/students/{id}` | STU-04 | 퇴원 soft delete — **오늘 명단은 유지**, 내일부터 제외 |

**`GET /staff/students` 응답 `items[]`** — `student_id` · `name` · `class_name` · `bus_no` · `stop_name` · `guardian_phone`

**`POST` · `PATCH` 요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `name` | string | ● | 이름 |
| `student_phone` | string | ○ | 학생 연락처 (C-13 — 휴대전화 보유 학생만 대상) |
| `photo` | file | ○ | 사진. **육안 확인 전용** — 얼굴인식 부재 |
| `gender` | enum | ○ | `male` · `female` |
| `birth_date` · `grade` | — | ○ | 생년월일 · 나이(학년) |
| `class_name` | string | ○ | 반 |
| `seat_no` | integer | ○ | 좌석 배정 |
| `note` | string | ○ | 특이사항 (STU-07) |
| `can_go_alone` | boolean | ● | 혼자 귀가 가능 여부 (STU-08) |

⚠ **관계자가 입력하지 않는 것 둘** (2026-08-24 확정, A-10).

| 항목 | 어디서 오는가 |
|---|---|
| **보호자 연락처** | 연결된 보호자 계정(`guardian` → `account.phone`)에서 조회. 학생 레코드에 복제하지 않음 — 복제하면 보호자가 번호를 바꿔도 명단이 옛 값을 표시 |
| **승하차 주소** | 학부모가 요일별 주소(§3.7)·일일 변경(§3.8)으로 등록하고 **그 시점에 검증·매칭**. 관계자는 조회만 |

계정 미연결 학생은 연락처·주소가 비어 있는 것이 정상이며, 관계자 화면이 그 상태를 드러낸다.

**에러** — `404 STUDENT_NOT_FOUND`(`GET` 상세 · `PATCH` · `DELETE` 대상 부재)

### 5.12 차량 관리 (BUS-01~04, A-11)

| 메서드 · 경로 | 기능 ID | 설명 |
|---|---|---|
| `GET /staff/buses` | BUS-01 | 목록 |
| `POST /staff/buses` | BUS-02 | 등록 |
| `PATCH /staff/buses/{id}` | BUS-03 | 수정 |

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `bus_no` | string | ● | 호차 |
| `plate_no` | string | ● | 차량번호 |
| `capacity` | integer | ● | 승차 정원 |
| `student_capacity` | integer | — | **응답 전용 — 배차 시 자동 계산** = `capacity` − 배치된 기사 − 배치된 동승자. 관계자가 입력하지 않음 (A-11) |
| `operable` | boolean | ○ | 운행 가능 여부 |

정원 검증의 기준은 `student_capacity`. 초과 시 `409 CAPACITY_EXCEEDED` — 현재 인원과 정원을 `details` 에 반환 (BUS-04). 정원 축소로 기배정 인원이 초과하면 경고.

**에러** — `409 CAPACITY_EXCEEDED`(학생 탑승 가능 인원 초과 — `details` 에 현재 인원·정원) · `409 DUPLICATE_BUS_NO`(같은 학원에 같은 호차 — 등록·수정 공통, 2026-08-26 신설 · Ruling 164) · `404 BUS_NOT_FOUND`(`PATCH` 대상 부재)

### 5.13 매니저 관리 (MGR-01~04, A-12)

| 메서드 · 경로 | 기능 ID | 설명 |
|---|---|---|
| `GET /staff/managers?q=` | MGR-01 | 목록·검색 |
| `POST /staff/managers` | MGR-02 | 등록 |
| `PATCH /staff/managers/{id}` | MGR-03 | 수정 |
| `DELETE /staff/managers/{id}` | MGR-04 | 삭제 — 배치 중이면 `409 MANAGER_ASSIGNED` |

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `name` | string | ● | 이름 |
| `phone` | string | ● | 전화번호 |
| `role` | enum | ● | `driver` · `escort` — **이 값이 앱 권한을 결정** |
| `work_hours` | object | ○ | 근무 시간. 배치 충돌 검증의 근거 (MGR-06) |

역할 변경 시 매니저 앱 화면 구성이 함께 변경. 계정 연결은 가입 승인(§5.2)의 `link.manager_id`.

**에러** — `409 MANAGER_ASSIGNED`(회차에 배치된 매니저 삭제) · `404 MANAGER_NOT_FOUND`(`PATCH` · `DELETE` 대상 부재)

### 5.14 PATCH /staff/runs/{runId}/assignment

매니저 배치 (MGR-05·06, A-12).

**권한** 학원 관계자 · **목적** 회차별 기사·동승자 배치. 시간 충돌 경고 반환

⚠ **`[조정 중]` 중 `MGR-05` 수동 배치와 `MGR-06` 충돌 경고만 2026-08-26 확정했다**(Ruling 153). **동승자 자동 배정은 여전히 미확정이며 Phase 6 소유**다 — 소요 시간(`run.est_duration_min`)이 노선 계산의 산출물이라 그것 없이는 근무 시간 충돌을 판정할 근거가 부재하다. 이 절이 규정하는 것은 **관계자가 손으로 지정하는 경로**뿐이다.

**요청** — 둘 다 선택이나 **최소 하나는 필요**하다(둘 다 비면 `422 VALIDATION_FAILED`).

| 필드 | 타입 | 설명 |
|---|---|---|
| `driver_manager_id` | integer | 기사로 배치할 매니저. 그 매니저의 `role` 이 `driver` 가 아니면 `404 MANAGER_NOT_FOUND` |
| `escort_manager_id` | integer | 동승자로 배치할 매니저. 그 매니저의 `role` 이 `escort` 가 아니면 `404 MANAGER_NOT_FOUND` |

역할이 어긋난 지정을 404 로 답하는 것은 `§5.2` 가입 승인의 `link.manager_id` 와 같은 형태다 — `manager.role` 이 곧 앱 권한이라(C-06), 기사를 동승자 자리에 넣으면 그 계정이 승하차를 기록할 수 있는지가 보는 곳마다 갈린다.

**이미 배치된 역할에 다른 매니저를 지정하면 교체**다. 관리 화면에서 담당자를 바꾸는 것이 정상 조작이라 거부하지 않는다.

**응답**

```json
{
  "run_id": 8,
  "assignments": [
    { "manager_id": 12, "name": "강기사", "role": "driver" },
    { "manager_id": 31, "name": "서동승", "role": "escort" }
  ],
  "warnings": [
    { "code": "WORK_HOURS_MISMATCH", "manager_id": 12, "role": "driver",
      "message": "근무 시간 밖입니다" }
  ]
}
```

`assignments[]` 는 이번 요청이 바꾼 것만이 아니라 **그 회차의 현재 배치 전부**다 — 기사만 바꾼 요청이 동승자를 지운 것처럼 보이지 않게 한다.

**충돌은 경고이고 차단이 부재하다** (MGR-06 · UF-M-06 · `PRD §6` · `USER_FLOWS`). **저장은 되고**(200, `assignment` 행이 실제로 생긴다) 판정 결과가 `warnings[]` 에 실린다. 근무 시간은 학원이 매니저에게 물어 적어 둔 참고값이고 당일 대체·연장이 실재하므로, 차단으로 두면 **오늘 실제로 태울 수 있는 기사를 시스템이 배치 불가로 만든다**.

**경고 3종** (2026-08-26 확정, Ruling 165 · 2026-08-30 `WORK_HOURS_MISMATCH` 판정 축 재판정)

| `code` | 무엇을 대조하나 | 언제 |
|---|---|---|
| `WORK_HOURS_MISMATCH` | 회차 시간대(`depart_time` ~ `depart_time + est_duration_min`) ↔ 그 매니저의 `work_hours` | 그 요일 키가 없거나, 어느 구간에도 들지 않을 때. **`est_duration_min` 이 비어 있으면 시간대가 출발 시각 하나로 접혀 기존과 같은 값을 낸다**(Ruling 165 ② 재판정 · Phase 7 목표 12) |
| `MANAGER_DOUBLE_BOOKED` | 그 매니저의 **같은 날 다른 배치**(`assignment` → `run` 조인) | 취소되지 않은 다른 회차와 **출발 시각이 같을** 때. `work_hours` 를 보지 않는다. **점 판정을 유지한다**(Phase 7 목표 12 판정 — 이유는 `progress.md` Phase 7 절) |
| `WORK_HOURS_NOT_SET` | — | `work_hours` 가 비어 있어 **판정할 근거가 부재**할 때 |

- **세 판정은 서로 독립이다.** 묶으면 `MANAGER_DOUBLE_BOOKED` 가 근무 시간 미기재 매니저에서 조용히 사라지는데, 근무 시간이 없다고 해서 같은 시각에 두 대를 몰 수 있는 것은 아니다
- **`WORK_HOURS_NOT_SET` 은 "경고 없음" 이 아니다.** 근무 시간은 등록 시 선택 항목이라 비어 있는 것이 정상 상태이며, 코드를 따로 두어야 클라이언트가 **"적합해서 조용한 것" 과 "판정하지 못한 것"** 을 가른다
- **충돌이 없으면 `warnings[]` 는 빈 배열**이다 — 항상 무언가를 담는 구현과 구별되어야 한다
- ⚠ **회차는 구간이 아니라 점(출발 시각)으로 판정한다.** `run.est_duration_min` 이 nullable 이고 이 시점의 회차는 노선 계산 이전이라 대개 비어 있어, 구간으로 두면 **"판정 불가" 가 조용히 "경고 없음" 이 된다.** 한계가 있다 — 07:00 출발·2시간 운행을 07:00~08:00 근무자에게 배치해도 경고가 나오지 않는다. `est_duration_min` 이 실제로 채워지는 Phase 6 에서 구간 판정으로 올릴지 재판정한다
- 시간대는 서비스 기준 시간대(`Asia/Seoul`, `ERD §2`)를 쓴다 — 요일과 시각 판정이 같은 시계를 본다
- **근무 구간의 경계는 양끝을 포함한다** — 07:00~10:00 근무자에게 07:00 회차도 10:00 회차도 경고가 아니다. 등원 회차는 근무 시작 시각에 맞춰 짜는 것이 정상이라 이 경계가 늘 밟히며, 배타로 두면 경고가 항상 켜져 있어 진짜 충돌까지 함께 묻힌다

**차단하는 것은 따로 있다.** `assignment(run_id, role)` UNIQUE 가 **회차당 기사 1명 · 동승자 1명**을 강제하며, 동시 요청 2건이 같은 역할을 채우려 하면 하나는 `409 DUPLICATE_ASSIGNMENT` 다. 배치된 매니저의 삭제는 `409 MANAGER_ASSIGNED`(§5.13)가 막는다. **경고 축과 차단 축을 섞지 않는다.**

**에러** — `404 RUN_NOT_FOUND`(대상 회차 부재·타 학원) · `404 MANAGER_NOT_FOUND`(대상 매니저 부재·타 학원·역할 불일치) · `409 DUPLICATE_ASSIGNMENT`(같은 역할을 동시에 채우려는 요청 경합, 2026-08-26 신설) · `422 VALIDATION_FAILED`(기사·동승자를 둘 다 비워 보냄)

### 5.15 POST /staff/runs/{runId}/waypoints

경유 지점 지정 (RTE-10, A-15). **출발 전 확정 노선에 특정 지점을 강제 경유지로 추가.** 학생 단위인 강제 추가(§5.7)와 달리 **지점 단위** — 탑승자 없이 경유만 필요한 경우가 대상.

**권한** 학원 관계자

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `address` | string | 조건부 | 주소 입력 방식. 좌표 미전달 시 필수 — 검증 후 좌표 변환 (STU-05) |
| `lat` · `lng` | number | 조건부 | 지도 선택 방식 |
| `label` | string | ● | 기사 화면 표시명 |
| `note` | string | ○ | 경유 사유·특이사항 |
| `apply` | boolean | ● | `false` = 미리보기만, `true` = 재최적화 결과 배포 |

**응답** — `waypoint_id` · `route_preview`(§5.5 와 동일 구조 — `stops_before[]` · `stops_after[]` · `reordered[]`) · `est_time_before`·`est_time_after` · `est_distance_before`·`est_distance_after` · `applied`(boolean)

| 처리 | 내용 |
|---|---|
| 미리보기 (`apply=false`) | 재최적화만 수행하고 **확정 노선은 불변** — 관리자가 대조를 확인하는 단계 |
| 배포 (`apply=true`) | 확정 노선 갱신 + 기사·동승자 푸시 + 확인 응답 대상 (RUN-07) |
| 구간 | **출발 전까지만** — 운행 시작 후 `403 CHANGE_WINDOW_CLOSED` (C-04 ③) |
| 해제 | 배포 전에는 취소 가능. 배포 후 제거는 `DELETE /staff/runs/{runId}/waypoints/{waypointId}` 로 동일 절차(미리보기 → 배포)를 거침 |

**에러** — `403 CHANGE_WINDOW_CLOSED`(운행 시작 후) · `422 ADDRESS_VERIFICATION_FAILED`(주소 검증 실패 — 저장 보류) · `404 RUN_NOT_FOUND` · `422 VALIDATION_FAILED`(주소·좌표 모두 부재)

### 5.16 GET /staff/emergencies · POST /staff/emergencies/{id}/ack

비상 알림 수신·확인 (EXC-04, A-16).

**권한** 학원 관계자 · **요청 (쿼리)** `status`(`open` · `acked` · `canceled`, 기본 `open`) · `date`

**응답** — `items[]` · `unacked_count`(미확인 배지)

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `emergency_id` | string | ● | |
| `type` | enum | ● | `accident` · `vehicle_fault` · `student_emergency` · `etc` |
| `memo` | string | ○ | |
| `raised_by` | object | ● | `name` · `role`(`driver`·`escort`) · `phone` |
| `run_id` · `bus_no` · `direction` | — | ● | 대상 회차 |
| `position` | object | ● | `lat` · `lng` · `recorded_at` — **발신 시점 위치** |
| `rider_count` | integer | ● | 발신 시점 탑승자 수 |
| `contacts` | array | ● | 기사·동승자 연락처 |
| `raised_at` · `acked_at` · `canceled_at` | datetime | ● / ○ / ○ | |
| `acked_by` | object | ○ | 확인한 관계자 |

`POST /staff/emergencies/{id}/ack` — 접수 응답. 발신자 앱에 "학원이 확인했습니다" 표시. 확인 이력(누가·언제) 저장. **이미 확인된 건 재확인은 `409 ALREADY_ACKED`**.

**에러** — `404 EMERGENCY_NOT_FOUND` · `409 ALREADY_ACKED`

### 5.17 GET /staff/notifications

알림 로그 (NTF-10·11, A-13).

**권한** 학원 관계자 · **요청 (쿼리)** `type` (enum, 선택) · `date` (date, 선택) · `acked` (boolean, 선택) · 페이징

**응답** — `items[]` + `unacked_count`(미확인 배지)

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `notification_id` | string | ● | |
| `sent_at` | datetime | ● | 발송 시각 |
| `bus_no` | string | ○ | 호차 |
| `recipient_name` · `recipient_role` | — | ● | 대상 |
| `type` | enum | ● | §9.7 |
| `body` | string | ● | 발송 문구 |
| `acked` | boolean | ● | 수신 확인 여부 (NTF-10) |

전송 알림 전수 조회 — 푸시 off 로 차단된 건도 레코드로 존치.

**에러** — §1.11 공통 항목 외 고유 에러 부재.

---

### 5.18 GET /staff/runs/live

전 차량 실시간 위치 (MON-07, A-14). 학원 범위.

**권한** 학원 관계자

**응답** — `runs[]` — `run_id` · `bus_no` · `direction` · `status` · `position{lat, lng, recorded_at}` · `current_stop` · `next_stop` · `progress{done, total}` · `delay_minutes` · `driver_name` · `escort_name`

**`/ws/academy/{id}/live` 의 `position` 은 증분 방송**이라 화면 진입 시 현재 위치를 그릴 **초기 스냅샷**이 부재. 이 엔드포인트가 그 자리를 채우고 이후 갱신은 WS 가 담당.

- `status='moving'` 인 회차만 반환. 위치 미수신 회차는 `position=null` + `last_seen_at`
- 좌표 갱신은 **5~10초** 주기 (LOC-01)

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 5.19 GET /staff/runs/{runId}/route

확정 노선 조회 — 관계자용 (RTE-02, A-03·A-08·A-15).

**권한** 학원 관계자

**응답** — §4.3 과 같은 구조 + `route_version` · `published_at` · `ack{driver, escort}`

매니저용 §4.3 은 **배치된 회차**로 범위가 한정(§1.5)돼 관계자가 호출하면 `403`. 관계자가 승인 화면·경유 지점 미리보기 **밖에서** 확정 노선을 보는 경로가 필요.

**에러** — `409 RUN_NOT_CONFIRMED`(확정 전) · `404 RUN_NOT_FOUND`

### 5.20 GET /staff/reports · GET /staff/reports/{id}

예외 보고 조회 (EXC-02 · EXC-03, M-14). §4.13 의 쓰기에 대응하는 읽기.

**권한** 학원 관계자 · **요청 (쿼리)** `type` · `date` · `run_id`

**응답** — `items[]` — `report_id` · `type`(§9.8 `report_type`) · `memo` · `run_id` · `bus_no` · `student_name`(`guardian_absent` 일 때) · `reported_by` · `reported_at` · `handled`(boolean) · `handled_at`

보고가 푸시 1회로만 전달되면 되짚을 수단이 부재. `ERD` 의 `exception_report.academy_id` 가 "학원 범위 조회 대상"으로 정의된 것이 이 조회를 전제.

**에러** — `404` 계열(미존재 보고)

### 5.21 GET · PATCH /staff/academy-settings

학원별 설정 (EXC-01 · M-13 · A-17).

**권한** 학원 관계자

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `no_show_wait_minutes` | integer | ● | 미승차 대기. **기본 3분**, 학원별 조정 (FEATURE_SPEC §2.1) |

`FEATURE_SPEC §2.1` 이 정책 상수 중 **유일하게 "학원별 설정"으로 규정한 값**. 조회·수정 경로가 없으면 그 규정 자체가 성립 불가.

⚠ **다른 정책 상수(30분 · ±10분 · 14일 · 5회 등)는 전역 값이라 이 엔드포인트의 대상 밖** — 학원이 바꿀 수 있게 하면 사양이 흔들림.

**에러** — `422 VALIDATION_FAILED`(허용 범위 밖 값)

---

## 6. 메인 관리자 콘솔

전 학원 범위. 학원 격리(§1.5)의 예외이며, 학원 지정은 경로 파라미터로 명시.

**기능 코드 대응** — 원본 API명세서는 이 절에 `SA-01`~`SA-06` 을 쓰나, 이 문서는 `FEATURE_LIST` 계열인 `ACAD-01`~`ACAD-06` 으로 통일. 대응은 `SA-01`→`ACAD-01` · `SA-02`→`ACAD-02` · `SA-03`→`ACAD-03` · `SA-04`→`ACAD-04` · `SA-05`→`ACAD-05` · `SA-06`→`ACAD-06` 이며 상위 계층 기능은 `O-01`(ACAD-01~04) · `O-02`(ACAD-05·06).

### 6.1 GET /admin/academies

학원 목록·검색 (ACAD-01, O-01).

**권한** 메인 관리자 · **요청 (쿼리)** `q` (string, 선택 — 학원명·코드) · `status` (enum, 선택) · 페이징

**응답** — `items[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `id` · `code` · `name` · `region` | string | ● | |
| `staff_count` | integer | ● | 관계자 계정 수 (정원 1명) |
| `user_count` | integer | ● | 소속 사용자 — 학부모·학생·매니저 합계 |
| `status` | enum | ● | `active` · `inactive` |

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 6.2 POST /admin/academies

학원 등록 (ACAD-02, O-01).

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `name` | string | ● | 학원명. 가입 검색 대상 |
| `region` | string | ● | 지역(시·군·구). 동명 학원 구분에 필수 |
| `address` · `contact` · `memo` | string | ○ | 주소 · 대표 연락처 · 내부 메모 |

**학원 코드는 서버가 자동 생성** (2026-08-24 확정). 관리자가 입력하지 않으며 응답으로 돌려받는다. 충돌은 서버가 재생성으로 흡수하므로 **클라이언트에 중복 에러가 노출되지 않는다.**

**응답** `201` — `academy_id` · **`code`**(생성값) · `name` · `region` · `warnings[]`

**학원명 + 지역 중복은 경고만** — 저장 허용. `warnings[]`(`DUPLICATE_NAME_REGION`) 포함. 분원 존재 가능성이 근거.

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 6.3 GET · PATCH /admin/academies/{id}

학원 상세 · 정보 수정 · 비활성화 (ACAD-03·04, O-01).

**GET 응답** — §6.1 항목 + `address` · `contact` · `memo` · `staff_accounts[]` · `stats`(`moving_bus_count` 등)

**PATCH 요청** — `name` · `region` · `address` · `contact` · `memo` · `status`. **`code` 는 수정 대상 밖** — 서버 생성값

| 항목 | 처리 |
|---|---|
| 코드 | 변경 경로 부재. 소속은 내부 ID 로 연결되므로 코드가 바뀌어도 기존 가입자에 무영향이나, 자동 생성값이라 바꿀 이유가 부재 |
| `status=inactive` (ACAD-04) | ① 가입 학원 검색 결과에서 제외 ② 신규 가입 요청 차단. **기존 사용자 로그인 유지** — 운행 중 로그아웃 방지 |
| 물리 삭제 | 부재 — soft delete 만 |

**에러** — `404 ACADEMY_NOT_FOUND`

### 6.4 GET /admin/staff-signup-requests

관계자 가입 요청 목록 (ACAD-05, O-02). 관계자도 form 가입, 승인 주체는 메인 관리자 (C-01).

**응답** — `items[]` — `request_id` · `name` · `phone` · `academy`(`id` · `name` · `region` · `code`) · `requested_at` · `academy_staff_count`

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 6.5 POST /admin/staff-signup-requests/{id}/decide

관계자 가입 수락 / 거절 (ACAD-05, O-02).

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `accept` | boolean | ● | |
| `reject_reason` | string | 조건부 | `accept=false` 필수 |

**학원당 1명 유지** — 이미 `active` 관계자가 있는 학원의 추가 승인은 `409 STAFF_QUOTA_EXCEEDED`.

**응답** — `account_status`(`active` · `rejected`) · `decided_at`.

**에러** — `409 STAFF_QUOTA_EXCEEDED`(학원당 관계자 **1명** 초과 승인) · `409 APPROVAL_ALREADY_DECIDED`(이미 처리된 요청) · `409 SIGNUP_TARGET_BLOCKED`(승인 대상 계정이 `blocked` — §8.1) · `404 SIGNUP_REQUEST_NOT_FOUND` · `422 VALIDATION_FAILED`(`accept=false` 인데 `reject_reason` 부재)

### 6.6 GET /admin/staff-accounts

관계자 계정 목록 (ACAD-06, O-02).

**응답** — `items[]` — `account_id` · `name` · `login_id` · `phone` · `academy_name` · `last_login_at` · `status`

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 6.7 PATCH /admin/staff-accounts/{id}

관계자 계정 관리 (ACAD-06, O-02).

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `name` · `phone` · `email` | string | ○ | 정보 수정 |
| `reset_password` | boolean | ○ | 비밀번호 초기화 — 응답에 임시 비밀번호 1회 반환 |
| `status` | enum | ○ | `active` · `inactive` — 퇴사 시 즉시 권한 회수 |

관계자 계정은 학생 개인정보 전체에 접근 — 퇴사 즉시 비활성화가 요건.

**퇴사 처리(`status=inactive`)가 하는 것은 둘이다** — ① 그 계정의 refresh 토큰 **전량 무효화**(C-14, 지금 열려 있는 세션을 끊는다) ② 이후 **로그인 거부** `403 AUTH_STAFF_INACTIVE`(§2.5 · §8.1). **①만으로는 요건이 성립하지 않는다** — 비밀번호를 아는 퇴사자가 다시 로그인하면 `role=staff` 권한을 그대로 되찾기 때문이다. 반대로 `status=active` 로 되돌리면 둘 다 즉시 풀린다 (Ruling 143).

`account.status` 는 이 전환에서 **바뀌지 않는다** — 계정 상태 4종(`pending`·`active`·`rejected`·`blocked`)에 `inactive` 가 부재하고, 퇴사는 계정의 생명주기가 아니라 **그 학원에서의 재직 여부**라 `academy_staff.status` 가 표현한다.

**에러** — `404 ACCOUNT_NOT_FOUND` · `409 STAFF_QUOTA_EXCEEDED`(`status=active` 전환 대상 학원에 이미 `active` 관계자 존재)

### 6.8 GET /admin/academies/{id}/runs/live

전체 관제 — 실시간 경로 추적 (O-05).

**권한** 메인 관리자

**응답** — `runs[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `run_id` · `bus_no` · `direction` | — | ● | |
| `run_status` | enum | ● | `moving` 회차가 관제 대상 |
| `position` | object | ○ | `lat` · `lng` · `received_at` |
| `depart_time` | datetime | ● | 출발 시각 |
| `est_depart_time` | datetime | ● | 출발 예정 시각 |
| `stops[]` | array | ● | `stop_id` · `seq` · `name` · `lat` · `lng` · `change` · `arrived_at` · **`eta`** |
| `destination_eta` | datetime | ● | 도착지 도착 예정 시각 |
| `driver` · `escort` | object | ● | `name` · `phone` — **원문** |

```json
{
  "runs": [
    {
      "run_id": "run_20260824_3_am",
      "bus_no": "3호차",
      "direction": "to_academy",
      "run_status": "moving",
      "position": { "lat": 37.501234, "lng": 127.039876, "received_at": "2026-08-24T08:44:02+09:00" },
      "depart_time": "2026-08-24T08:30:00+09:00",
      "est_depart_time": "2026-08-24T08:31:40+09:00",
      "stops": [
        { "stop_id": "stop_119", "seq": 5, "name": "중앙로 스타빌딩 앞", "arrived_at": "2026-08-24T08:41:12+09:00", "eta": null },
        { "stop_id": "stop_120", "seq": 6, "name": "행복빌라 앞", "arrived_at": null, "eta": "2026-08-24T08:47:00+09:00" }
      ],
      "destination_eta": "2026-08-24T09:02:00+09:00",
      "driver": { "name": "박정우", "phone": "010-2311-8814" },
      "escort": { "name": "최유나", "phone": "010-5522-1043" }
    }
  ]
}
```

**ETA 는 관제 전용** — 학부모·학생 앱 비노출(C-08)과 별개 축.

실시간 갱신은 WebSocket `/ws/admin/live` (§7).

**에러** — `404 ACADEMY_NOT_FOUND`. `403 ACADEMY_SCOPE_VIOLATION` 미발생 — 메인 관리자는 학원 격리의 예외 (§1.5)

### 6.9 GET /admin/runs/{runId}/roster

승하차지별 학생 리스트 (O-06).

**응답** — `stops[]` → `students[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `student_id` · `name` | string | ● | |
| `photo_url` | string | ● | |
| `student_phone` | string | ● | 학생 연락처 — **원문** |
| `guardian_phone` | string | ● | 학부모 연락처 — **원문** |
| `status` | enum | ● | 탑승 상태 |

**에러** — `404 RUN_NOT_FOUND`. 학원 격리 예외는 §6.8 과 동일

### 6.10 GET /admin/blocked-accounts

차단 계정 목록 (AUTH-06, O-03).

**응답** — `items[]`

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `account_id` · `login_id` · `name` | string | ● | |
| `academy_name` | string | ● | 소속 학원 |
| `blocked_at` | datetime | ● | 차단 일시 |
| `failed_attempts` | integer | ● | 시도 횟수 — **5회** 누적이 기준 |
| `reason` | string | ● | 차단 사유 |

**계정 단위 차단만** — IP 차단 부재 (C-11).

**에러** — §1.11 공통 항목 외 고유 에러 부재. 차단 계정 0건은 빈 `items[]` 로 반환.

### 6.11 GET /admin/emergencies

전 학원 비상 알림 (EXC-04, O-07). 학원 관계자와 **동시** 수신.

**권한** 메인 관리자 · **요청 (쿼리)** `status` · `academy_id`(선택)

**응답** — `§5.16` 항목 + 아래. WS: `/ws/admin/live` 의 `emergency_raised` 이벤트로 실시간 수신.

| 필드 | 타입 | 필수 | 설명 |
|---|---|:-:|---|
| `academy` | object | ● | `id` · `name` · `contact` — 학원 연락처 |
| `staff_acked` | boolean | ● | **학원 관계자의 확인 여부** |
| `elapsed_since_raised` | integer | ● | 발신 후 경과 초. 관계자 미응답 상황을 운영사가 즉시 인지 |

관제 지도에서 발신 회차를 강조 표시.

**에러** — §1.11 공통 항목 외 고유 에러 부재.

### 6.12 POST /admin/blocked-accounts/{id}/unblock

로그인 차단 해제 (AUTH-06, O-03).

**요청** 본문 부재 · **응답** `account_status`(`active`) · `unblocked_by` · `unblocked_at` · **이력** 처리자·일시 저장

**에러** — `404 ACCOUNT_NOT_FOUND` · `409 ACCOUNT_NOT_BLOCKED`(`blocked` 아닌 계정의 해제 시도)

### 6.13 감사 · 접속 이력

O-04 · SYS-01·02. 정본 API명세서에 경로 미기재 — 감사 로그 요건에서 도출.

| 메서드 · 경로 | 기능 ID | 응답 항목 |
|---|---|---|
| `GET /admin/audit-logs` | SYS-01 | `actor` · `action`(`read` · `update` · `delete`) · `target_type` · `target_id` · `academy_name` · `occurred_at` |
| `GET /admin/login-history` | SYS-02 | `account_id` · `login_id` · `result`(`success` · `fail`) · `ip` · `occurred_at` · `block_event` |

쿼리 파라미터 — `academy_id` · `account_id` · `from` · `to` · 페이징.

**에러** — `404 ACADEMY_NOT_FOUND`(`academy_id` 필터가 미등록 학원) · `404 ACCOUNT_NOT_FOUND`(`account_id` 필터가 미등록 계정)

---

## 7. WebSocket

REST 조회의 보완. 접속 시 `Authorization: Bearer {access_token}` 로 인증하고, 서버가 **채널별 구독 권한을 검증**. 권한 밖 채널 구독은 연결 거부(`4403`).

⚠ **아래 표의 "채널" 은 STOMP 구독 목적지(destination)이지 연결 엔드포인트가 아니다** (2026-08-31 사용자 확정, Ruling 209). 연결 엔드포인트는 **`/ws/location` 하나**이고, 클라이언트는 거기로 CONNECT 한 뒤 아래 경로를 SUBSCRIBE 한다.

- **엔드포인트를 늘리지 않는 이유** — 인가 검증은 이미 SUBSCRIBE 프레임에 붙어 있어(`StompAuthChannelInterceptor`) 그 자리를 넓히면 되지만, 엔드포인트를 4개로 늘리면 **핸드셰이크 인증을 4벌** 만들어야 하고 규칙이 갈린다.
- **`4403` 은 그대로 유지** — 구독 거부 시 STOMP `ERROR` 프레임을 보내고 세션을 닫으며, 닫는 코드가 `4403` 이다. 즉 "구독 검사 결과가 연결 종료로 나타나는" 형태다.
- ⚠ **옛 경로 `/topic/tenant/{tenantId}/**` 는 이 표로 대체되어 사라진다** — N:M 멤버십 시절 어휘이고 코드·스키마·사양은 전부 `academy` 로 정리됐다(Ruling 121). 클라이언트 계약이라 소비자가 생기는 시점까지 미뤄 뒀고, 이 표가 그 소비자다.

**목적지 문면** — 브로커 프리픽스는 `/topic` 이다. 아래 표의 `/ws/...` 표기는 채널을 가리키는 이름이고 **실제 SUBSCRIBE 경로는 오른쪽 열**이다.

| 표기 | 실제 구독 경로 |
|---|---|
| `/ws/students/{id}/run` | `/topic/students/{studentId}/run` |
| `/ws/manager/runs/{id}` | `/topic/manager/runs/{runId}` |
| `/ws/academy/{id}/live` | `/topic/academy/{academyId}/live` |
| `/ws/admin/live` | `/topic/admin/live` |

⚠ **`/ws` 를 브로커 프리픽스로 추가하지 않는다** — 그러면 연결 엔드포인트 `/ws/location` 과 구독 목적지 `/ws/students/...` 가 **같은 접두사를 쓰면서 다른 층**이 되어 읽는 사람이 구별할 수단을 잃는다.

| 채널 | 구독 권한 | 방송 이벤트 |
|---|---|---|
| `/ws/students/{id}/run` | 학부모(연결 자녀) · 학생(본인) | `position` · `stop_arrived` · `run_started` · `run_ended` |
| `/ws/manager/runs/{id}` | 해당 회차 배치 기사 · 동승자 | `rider_changed` · `stop_arrived` · `run_started` · `run_ended` · **`emergency_acked`** |
| `/ws/academy/{id}/live` | 해당 학원 관계자 | `position` · `rider_changed` · `stop_arrived` · `run_started` · `run_ended` · `approval_requested` · **`emergency_raised`** |
| `/ws/admin/live` | 메인 관리자 | `position` · `rider_changed` · `stop_arrived` · `run_started` · `run_ended` · **`emergency_raised`** |

**공통 봉투**

| 필드 | 타입 | 설명 |
|---|---|---|
| `event` | enum | 이벤트 명 |
| `run_id` | string | 대상 회차 |
| `occurred_at` | datetime | 발생 시각 |
| `payload` | object | 이벤트별 본문 |

### 7.1 이벤트별 페이로드

| 이벤트 | 트리거 | payload |
|---|---|---|
| `position` | `POST /runs/{runId}/position` (**5~10초** 주기) | `lat` · `lng` · `received_at` · `current_stop_name`. **학부모·학생 채널은 ETA 부재** (C-08), 관제 채널만 `eta` 포함 |
| `stop_arrived` | `POST /runs/{runId}/stops/{stopId}/arrive` | `stop_id` · `seq` · `name` · `arrived_at` · `next_stop_id`. 기사 포인터 전진의 방송 — 동승자 처리 명단은 불변 |
| `rider_changed` | `PATCH /runs/{runId}/riders/{riderId}` · `revert` | `rider_id` · `student_id` · `student_name` · `status` · `stop_id` · `changed_at` · `counts` · `stop_skipped`. **5초** 이내 반영 |
| `run_started` | `POST /runs/{runId}/start` | `run_status`(`moving`) · `started_at` · `auto_boarded_count` |
| `run_ended` | 서버의 `finished` 전이 (§4.10) | `run_status`(`finished`) · `finished_at` · `auto_alighted_count` |
| `emergency_raised` | `POST /runs/{runId}/emergency` | `emergency_id` · `type` · `bus_no` · `raised_by{name, role, phone}` · `position{lat, lng}` · `rider_count` · `raised_at`. **관계자·메인 관리자 채널 전용** (C-17) |
| `emergency_acked` | `POST /staff/emergencies/{id}/ack` | `emergency_id` · `acked_by_name` · `acked_at`. **매니저 채널 전용** — 발신자 앱에 "학원이 확인했습니다" 표시 (A-16) |
| `approval_requested` | ② 구간 요청 접수 (REQ-05) | `approval_id` · `student_name` · `run_id` · `stop_name` · `deadline_at`. **관계자 채널 전용** |

- 재연결 시 클라이언트는 대응 REST 조회로 전량 동기화 — 이벤트 유실 보정.
- WebSocket 은 **알림 발송 경로가 아님**. 푸시 알림은 별도 채널이며 이벤트와 수신 대상이 상이.

---

## 8. 에러 코드 사전

### 8.1 인증 · 계정

| 코드 | HTTP | 발생 조건 |
|---|:-:|---|
| `UNAUTHORIZED` | 401 | 자격 증명이 **아예 없는** 접근 — 토큰 미동봉 STOMP `CONNECT` 등. `TOKEN_EXPIRED` 와 합치지 않는 이유는 클라이언트의 다음 동작이 갈리기 때문 — 만료는 재발급을 시도할 자리이고, 부재는 로그인부터 해야 할 자리다 (2026-08-25 등재) |
| `INVALID_CREDENTIALS` | 401 | 아이디·비밀번호 불일치. `details.remaining_attempts` 로 잔여 시도 안내 |
| `TOKEN_EXPIRED` | 401 | access·refresh 만료, 로그아웃·차단으로 무효화 → 재로그인 요구 |
| `AUTH_PENDING` | 403 | `pending` 계정이 **허용 목록**(`GET /auth/signup-status` · `POST /auth/logout` · `GET /me` · `POST`·`DELETE /me/devices`, §1.4) 밖 호출. `rejected` 는 `POST /auth/signup/reapply` **1개 추가** (C-01 · §1.4). ⚠ **`pending` 에게 재신청은 허용되지 않는다** — `AUTH-03` 이 "재신청은 거절 이후에만" 을 규정 |
| `AUTH_ACCOUNT_BLOCKED` | 403 | 로그인 실패 **5회** 누적으로 계정 단위 차단. 해제는 메인 관리자 (C-11) |
| `SIGNUP_TARGET_BLOCKED` | 409 | 가입 승인(`§5.2`·`§6.5`) 대상 계정이 `blocked` — **요청 주체는 정상 권한 보유**. `pending` 계정도 로그인은 되므로(`§1.4`) 승인 대기 중 실패 5회로 차단될 수 있고, 그때 통과시키면 승인이 차단을 조용히 풀어 해제 권한(AUTH-06)을 우회한다. ⚠ **위 `AUTH_ACCOUNT_BLOCKED` 를 재사용하지 않는다** — 그쪽은 **차단된 계정 자신의 호출**(`§1.11`)이라 승인 화면에 "차단된 계정입니다. 관리자에게 문의하세요" 가 뜨면 승인자가 자신이 차단된 것으로 오해한다. ⚠ **403 이 아니라 409 인 이유** — 요청 주체는 인가돼 있고 막는 것은 **대상 자원의 상태**다. `§8.3` `APPROVAL_ALREADY_DECIDED` 와 같은 형태이며, 같은 승인 경로의 같은 성격의 거부가 403·409 로 갈리면 클라이언트가 분기를 두 벌 만든다 (2026-08-26 신설, Ruling 147) |
| `AUTH_REJECTED` | 403 | `rejected` 계정이 **허용 6개**(`pending` 의 5개 + `POST /auth/signup/reapply`) 밖 호출. `AUTH_PENDING` 과 코드를 나눈 이유 — `§1.4` 가 대기 화면에 **거절 사유**를 노출하라고 규정하는데, 두 상태가 같은 코드를 쓰면 클라이언트가 "승인 대기 중" 과 "거절됨" 을 구별할 수단이 부재 (2026-08-25 신설) |
| `AUTH_STAFF_INACTIVE` | 403 | 퇴사 처리된(`academy_staff.status='inactive'`) 관계자 계정의 **로그인**. `§6.7` 이 "퇴사 즉시 권한 회수" 를 요건으로 규정하는데, refresh 토큰 무효화만으로는 **그 순간의 세션**만 끊겨 비밀번호를 아는 퇴사자가 재로그인해 `role=staff` 권한을 그대로 되찾는다. 판정 대상은 `academy_staff` **행이 있고 그 상태가 `inactive` 인 경우뿐**이다 — 행이 부재한 것은 퇴사가 아니라 **아직 승인 전**(`§6.4` 승인 큐의 축)이라 `pending` 관계자의 대기 화면 진입을 막지 않는다. `account.status` 에는 대응 값이 부재하다(4종에 `inactive` 없음) (2026-08-26 신설, Ruling 143) |
| `DUPLICATE_LOGIN_ID` | 409 | 가입 시 로그인 아이디 중복 |
| `REAPPLY_NOT_ALLOWED` | 409 | `rejected` 아닌 상태에서 재신청 |
| `LINK_CODE_INVALID` | 403 | 자녀 연결 인증 코드 만료·불일치 (P-02 · S-05) |
| `LINK_REQUIRED` | 422 | 가입 승인 시 계정 ↔ 학생·매니저 레코드 연결 누락 (AUTH-11) |
| `ACCOUNT_NOT_FOUND` | 404 | 미존재 계정 지정 — 복구 요청의 미등록 전화번호, 관계자 계정·차단 계정 처리 대상 부재 |
| `ACCOUNT_NOT_BLOCKED` | 409 | `blocked` 아닌 계정에 차단 해제 시도 (AUTH-06) |
| `VERIFICATION_CODE_INVALID` | 403 | 아이디·비밀번호 복구의 SMS 인증 코드 만료·불일치 (AUTH-08) |

### 8.2 인가 · 격리

| 코드 | HTTP | 발생 조건 |
|---|:-:|---|
| `FORBIDDEN` | 403 | 역할 권한 밖 호출 |
| `ACADEMY_SCOPE_VIOLATION` | 403 | 소속 학원 밖 자원 요청 (§1.5) |
| `ESCORT_ONLY` | 403 | **동승자 전용 조작을 기사가 호출** — 승하차 상태 변경(C-06) · 지연 알림 발신(M-05) |
| `DRIVER_ONLY` | 403 | 운행 시작·도착 처리를 동승자가 호출 |
| `NAV_NO_REMAINING_STOP` | 409 | 외부 내비 연동 요청인데 남은 승하차지가 부재 — 전 구간 도착 완료 (RUN-08 · §4.16) |

### 8.3 시간 창 · 한도

| 코드 | HTTP | 발생 조건 |
|---|:-:|---|
| `CHANGE_WINDOW_CLOSED` | 403 | 3구간 위반 — ③ 구간(운행 시작 후)의 노선 변경·위치 변경·되돌리기·경유 지점 지정 시도, 또는 ② 구간에서 강제 추가 시도. ③ 구간의 미등원(`riding=false`)은 예외로 허용 (C-04) |
| `CHANGE_LIMIT_REACHED` | 403 | 해당 회차의 ② 구간 변경 **1회** 소진. 한도 단위는 회차(`Run`)이며 다른 회차는 미영향. 문구 "금일은 변경할 수 없습니다" (C-04) |
| `START_WINDOW_CLOSED` | 403 | 운행 시작 요청이 출발 시각 **±10분** 창 밖 (M-07) |
| `APPROVAL_ALREADY_DECIDED` | 409 | 이미 처리된 승인 건 재처리 |
| `EMERGENCY_CANCEL_WINDOW_CLOSED` | 409 | 비상 알림 취소 창(발신 +**1분**) 경과 (EXC-04) |
| `ALREADY_ACKED` | 409 | 이미 확인된 비상 알림 재확인 |
| `PREVIEW_STALE` | 409 | 재최적화 미리보기 산출 후 입력(명단·승하차지·경유 지점)이 변경 — 관리자가 화면에서 본 결과와 배포될 결과가 불일치. 재조회 후 재시도 (§5.5) |

### 8.4 운행 · 명단

| 코드 | HTTP | 발생 조건 |
|---|:-:|---|
| `DUPLICATE_ARRIVE` | 403 | 동일 승하차지 도착 처리 중복 (RUN-04) |
| `RUN_NOT_CONFIRMED` | 409 | 확정 전(`idle`) 회차의 명단·운행 진입 |
| `RUN_NOT_MOVING` | 409 | `moving` 아닌 회차에 위치 업로드·승하차 처리 |
| `RUN_NOT_FOUND` | 404 | 존재하지 않는 회차 |
| `RUN_ALREADY_STARTED` | 409 | 이미 `moving` · `finished` 인 회차에 운행 시작 요청 (RUN-02 · §9.3 운행 상태 전이) |
| `DUPLICATE_RUN` | 409 | 같은 차량·날짜·방향·출발 시각의 회차를 **임시 추가**(§5.10 `POST /staff/runs`)로 다시 만들려는 시도. 유일성 근거는 `run(bus_id, service_date, direction, depart_time)` UNIQUE 다. ⚠ **일일 회차 생성 배치(SCH-02)는 이 코드를 내지 않는다** — 배치의 중복 실행은 재기동·수동 재실행이라는 정상 동작이라 오류가 아니라 무시이고, 이미 있는 회차를 조용히 건너뛴다. 같은 제약이 두 경로에서 다르게 읽히는 것이 요점이라 여기 적어 둔다 (2026-08-26 신설, Ruling 153) |
| `DUPLICATE_ASSIGNMENT` | 409 | 한 회차의 **같은 역할**을 두 요청이 동시에 채우려 함 — `assignment(run_id, role)` UNIQUE 위반 (§5.14 · MGR-05). 순차 요청은 교체로 처리되므로 이 코드가 나오는 것은 경합뿐이다. ⚠ 근무 시간·중복 배치 충돌과 **다른 축**이다 — 그쪽은 경고이고 저장되지만(MGR-06) 이쪽은 저장 자체가 거부된다 (2026-08-26 신설, Ruling 153) |
| `RIDER_NOT_FOUND` | 404 | 미존재 탑승자, 또는 `absent` 로 명단에서 제외된 탑승자 지정 |
| `STOP_NOT_FOUND` | 404 | 해당 회차에 존재하지 않는 승하차지 지정 |
| `NO_SHOW_CASE_NOT_FOUND` | 404 | `no_show` 미처리 탑승자에 연락 시도 기록 (EXC-01) |

### 8.5 자원 · 검증

| 코드 | HTTP | 발생 조건 |
|---|:-:|---|
| `CAPACITY_EXCEEDED` | 409 | 학생 탑승 가능 인원(= 정원 − 기사 − 동승자) 초과. `details` 에 현재 인원·정원 (BUS-04) |
| `ADDRESS_VERIFICATION_FAILED` | 422 | 주소 좌표 변환·유효성 검증 실패 — **저장 보류** (STU-05) |
| `STAFF_QUOTA_EXCEEDED` | 409 | 학원당 관계자 **1명** 초과 승인 (ACAD-05) |
| `MANAGER_ASSIGNED` | 409 | 회차에 배치된 매니저 삭제 시도 (MGR-04) |
| `ACADEMY_NOT_FOUND` | 404 | 미등록·비활성 학원 지정 |
| `STUDENT_NOT_FOUND` | 404 | 미존재 학생 |
| `ALREADY_LINKED` | 409 | 이미 연결된 자녀 재연결 |
| `LINK_REQUEST_NOT_FOUND` | 404 | 학생이 인증 코드 생성(`§3.3`)을 호출했는데 **대기 중이고 만료되지 않은 연결 요청이 부재**. `link_code.link_request_id` 가 FK NN 이라 요청 없이 코드를 만들 수단 자체가 부재하다 — `§3.3` 은 이 경우를 규정하지 않았으나 빈칸은 금지가 아니라 미완이다(Ruling 143). `403 LINK_CODE_INVALID`(만료·불일치·재사용)와 코드를 나눈 이유는 **주체와 다음 동작이 다르기** 때문이다 — 저쪽은 학부모가 코드를 다시 받아야 할 자리이고, 이쪽은 **학생**에게 "부모에게 연결 요청을 다시 보내 달라"고 안내할 자리다. 404 인 것은 지목된 자원(대기 중인 요청)이 없는 형태가 `SIGNUP_REQUEST_NOT_FOUND`·`APPROVAL_NOT_FOUND` 와 같기 때문 (2026-08-26 신설, Ruling 170) |
| `VALIDATION_FAILED` | 422 | 필수 누락·형식 위반. 지연 시간이 **5분 단위**가 아닌 경우 포함 |
| `SIGNUP_REQUEST_NOT_FOUND` | 404 | 미존재 가입 요청 지정 (AUTH-10 · ACAD-05) |
| `APPROVAL_NOT_FOUND` | 404 | 미존재 승인 요청 지정 (REQ-04) |
| `EMERGENCY_NOT_FOUND` | 404 | 미존재 비상 알림 지정 (EXC-04) |
| `NOTIFICATION_NOT_FOUND` | 404 | 미존재 알림 지정 (NTF-08) |
| `MANAGER_NOT_FOUND` | 404 | 미존재 매니저 지정 (MGR-03·04) |
| `BUS_NOT_FOUND` | 404 | 미존재 차량 지정 (BUS-03) |
| `DUPLICATE_BUS_NO` | 409 | 같은 학원에 이미 있는 호차로 등록·수정 — 유일성 범위는 `(academy_id, bus_no)` 라 다른 학원의 같은 호차는 허용 (BUS-02·03) |
| `SCHEDULE_NOT_FOUND` | 404 | 미존재 스케줄 지정 (SCH-01 · §5.10 `PATCH`·`DELETE`). 다른 학원의 스케줄을 `{id}` 로 지목한 경우도 이 코드다 — 학원 조건을 쿼리에 넣어 "없음" 과 "남의 학원" 을 같은 빈 결과로 만들면 존재 여부가 응답에서 사라진다 (2026-08-26 신설, Ruling 153) |
| `MAP_ROUTE_UNAVAILABLE` | 503 | 외부 도로 경로 API 의 **서킷이 열린 상태**에서 온디맨드 계산(②구간 승인 미리보기 · 경유 지점 지정)이 호출됨 (`TECH_DECISIONS §8` · `ARCHITECTURE §8.3`). ⚠ **배치 호출은 이 코드를 내지 않는다** — 사용자가 대기 중이 아니라 직선거리 근사로 진행하고 그 사실을 `route_version.fallback_used` 에 남긴다. ⚠ **단발 타임아웃·5xx 도 이 코드가 아니다** — 온디맨드라도 폴백으로 결과를 돌려준다. 서킷 개방만 가르는 이유는 그것이 **연속 실패가 확인된 상태**라 근사값이 계속 나올 것이고, 관리자는 화면에 뜬 그 근사 경로를 실제 경로로 믿고 승인하기 때문이다. 422 가 아니라 503 인 것은 요청이 잘못된 것이 아니라 서버가 지금 처리할 수 없기 때문이며 `ADDRESS_VERIFICATION_UNAVAILABLE` 와 같은 형태다 (2026-08-29 신설, Phase 6) |
| `DUPLICATE_SCHEDULE` | 409 | 같은 `bus_id`·`weekday`·`direction`·`depart_time` 조합의 스케줄 중복 등록·수정 — 유일성 근거는 `schedule(bus_id, weekday, direction, depart_time)` UNIQUE (SCH-01 · §5.10). 422 가 아니라 409 인 것은 요청 형식이 아니라 자원이 충돌한 것이기 때문이며 `DUPLICATE_BUS_NO` 와 같은 형태다 (2026-08-26 신설, Ruling 153) |
| `ROUTE_NOT_FOUND` | 404 | 미존재 고정 노선 지정 (RTE-01 · §5.9 `GET`·`PATCH`·`DELETE`·`optimize`). **다른 학원의 편성을 `{id}` 로 지목한 경우도 이 코드다** — `SCHEDULE_NOT_FOUND` 와 같은 처리이며, 학원 조건을 쿼리에 넣어 "없음" 과 "남의 학원" 을 같은 빈 결과로 만든다 (2026-08-29 신설, Ruling 180) |
| `DUPLICATE_ROUTE` | 409 | 같은 `bus_id`·`weekday`·`direction` 조합의 고정 노선 중복 편성·수정 — 유일성 근거는 `route(bus_id, weekday, direction)` UNIQUE(`uk_route_bus_weekday_direction`)이고 애플리케이션 선검사가 아니다 (RTE-01 · §5.9). **동시 2요청은 서로의 미커밋 INSERT 를 보지 못한 채 둘 다 선검사를 지나므로**, 제약 위반을 이 코드로 번역하지 않으면 그 경합이 500 으로 샌다. 422 가 아니라 409 인 것은 `DUPLICATE_SCHEDULE`·`DUPLICATE_BUS_NO` 와 같은 형태다 (2026-08-29 신설, Ruling 180) |

---

## 9. enum 사전

### 9.1 역할 (`role`)

| 값 | 대상 | 가입 경로 |
|---|---|---|
| `parent` | 학부모 | form 가입 → 관계자 승인 |
| `student` | 학생 | form 가입 → 관계자 승인 |
| `driver` | 버스기사 | form 가입 → 관계자 승인 |
| `escort` | 동승자 | form 가입 → 관계자 승인 |
| `staff` | 학원 관계자 | form 가입 → **메인 관리자** 승인, 학원당 1명 |
| `system_admin` | 메인 관리자 | 내부 발급 — 가입 경로 부재 |

### 9.2 계정 상태 (`Account.status`)

`pending` · `active` · `rejected` · `blocked` — 접근 범위는 §1.4.

### 9.3 운행 상태 (`Run.status`)

| 값 | 라벨 | 전이 조건 |
|---|---|---|
| `idle` | 운행 전 | 초기 |
| `confirmed` | 노선 확정 | 출발 **30분 전** 배치 실행 |
| `moving` | 운행 중 | 기사가 운행모드 시작 (출발 **±10분**) |
| `finished` | 운행 종료 | 최종 도착 처리로 서버가 전이 (C-15). 하원 미하차 잔류 중에는 미전이 |

### 9.4 탑승 상태 (`RunRider.status`)

| 값 | 라벨 | 색 | 변경 주체 |
|---|---|---|---|
| `waiting` | 대기 | 스톤 | (초기값) |
| `boarded` | 탑승 완료 | 그린 | 동승자 / 자동(하원 시작) |
| `alighted` | 하차 완료 | 그린 | 동승자 / 자동(등원 종료) |
| `absent` | 미등원 | 스톤 | 시스템 (학부모 사전 OFF 결과) |
| `no_show` | 미승차 | 레드 | 동승자 |

**`absent` 와 `no_show` 는 반드시 구분** — `absent` 는 학부모 알림 부재·명단 행 제외, `no_show` 는 즉시 알림 + **3분** 에스컬레이션 (C-02).

### 9.5 변경 구분 (`change`)

| 값 | 표기 | 적용 대상 |
|---|---|---|
| `added` | 초록 하이라이트 | 탑승자 · 승하차지 |
| `removed` | 빨강 하이라이트 | 탑승자 |
| `skipped` | 빨강 + 취소선, 순번 유지 | 승하차지 — 잔여 탑승자 0명 |

### 9.6 변경 요청 상태 (`ChangeRequest.status`)

| 값 | 의미 |
|---|---|
| `pending` | 승인 대기 — 기존 승하차지 탑승 안내 |
| `approved` | 반영 완료 |
| `rejected` | 관계자 거절 — 사유 통지 |
| `auto_rejected` | 출발 시각 도달 또는 운행 시작 중 먼저 오는 시점에 서버가 자동 거절 — 기존 노선 유지 + 학부모 통지, **횟수 미소진** |

### 9.7 알림 종류 (`notification.type`)

| 값 | 트리거 | 수신자 | on/off |
|---|---|---|:-:|
| `boarding` | `boarded` | 학부모 | ● |
| `alighting` | `alighted` — **동승자 처리분과 등원 종료 자동 처리분 모두** (C-07) | 학부모 | ● |
| `no_show` | `no_show` | 학부모 + 관계자 | ● |
| `absent` | 학부모 사전 OFF · **②구간 승인** (C-04) | **관계자만** | — |
| `arrive` | 서버의 위치 기반 자동 이벤트 (NTF-04) | 학부모 · 학생 | ● |
| `delay` | `POST /runs/{runId}/delay` | **관계자** + 현재 승하차지 **이후** 학생·학부모 (M-05) | **부재 — 항상 발송** |
| `run_started` | `Run` → `moving` | 관계자 · 학부모 · 학생 (M-10 · RUN-05) | **항상 발송** — 단말 푸시 수신만 개인 설정으로 조절 |
| `run_ended` | `Run` → `finished` | 관계자 | — |
| `signup_decided` | 가입 승인·거절 | 신청자 | — |
| `change_decided` | 변경 승인·거절·자동 거절 | 학부모 | — |
| `approval_requested` | ② 구간 요청 접수 (REQ-05) | 관계자 | — |
| `intent_changed` | 학부모 토글 | 관계자 | — |
| `link_requested` | 자녀 연결 요청 (P-02) | 대상 학생 | — |
| `route_changed` | 확정 후 노선 변경 (RUN-07) | 기사 · 동승자 | — |
| `assignment_changed` | 당일 배치 변경 (MGR-05) | 해당 매니저 | — |
| `no_show_escalated` | 미승차 3분 경과·무응답 (EXC-01) | 관계자 | — |
| `emergency` | 매니저 앱 비상 발신 (EXC-04) | **관계자 + 메인 관리자** | **부재 — 항상 발송** (C-17) |
| `emergency_canceled` | 비상 발신 1분 이내 취소 | 위와 동일 | 부재 |

`absent` 학생은 `arrive` · `delay` 발송 대상 밖. off 는 푸시만 차단하고 레코드는 항상 생성 — 보관 **14일**.

### 9.8 기타 enum

| 이름 | 값 | 비고 |
|---|---|---|
| `direction` | `to_academy`(등원) · `from_academy`(하원) | 등하원 비대칭 (C-07) |
| `weekday` | `mon` · `tue` · `wed` · `thu` · `fri` · `sat` · `sun` | 요일별 주소 (P-05) |
| `verify_method` | `photo` · `manual` | 사진 + 명단 육안 확인. 태그(NFC/QR) 미사용 |
| `delay_reason` | `traffic` · `weather` · `vehicle_check` · `prev_stop_wait` | **동승자만** 전달 |
| `report_type` | `guardian_absent` · `road_block` · `vehicle_issue` · `etc` | EXC-02 · EXC-03 |
| `emergency.type` | `accident` · `vehicle_fault` · `student_emergency` · `etc` | EXC-04. **`report_type` 과 별개** — 예외 보고는 관계자 통지, 비상은 관계자+메인 관리자 동시 + 팝업 |
| `contact_attempt` | `call` · `message` / `answered` · `no_answer` | EXC-01 연락 시도 |
| `gender` | `male` · `female` | 학생 기본 정보 |
| `nav_provider` | `kakao` · ~~`tmap`~~ | 외부 내비게이션 앱 (RUN-08 · §4.16). **응답 전용** — 서버 설정이 정한 활성 공급자를 앱에 알린다(Ruling 201). **MVP 는 `kakao` 만 구현**하고 `tmap` 은 자리만 둔다 |
| `nav_scope` | `next` · `remaining` | 내비에 넘길 범위 — 다음 목적지 1개 · 남은 전 구간 (RUN-08) |
| `academy_status` | `active` · `inactive` | 비활성화해도 기존 로그인 유지 (ACAD-04) |

---

## 10. [조정 중] 항목

배차·노선 최적화 정책 확정 전까지 **경로 · 권한 · 목적만 예약**. 요청·응답 세부는 미확정.

| 경로 | 기능 ID | 목적 |
|---|---|---|
| `POST /staff/students/{id}/transfer` | RTE-07 · A-07 | 수동 조정 · 버스 간 이동 — 양쪽 노선 재최적화 |

**해제된 항목** — 이 표에서 지운 것이고 되돌아오지 않는다. 각 절의 해제 note 가 근거다.

| 경로 | 해제 시점 · 근거 | 옮겨 간 곳 |
|---|---|---|
| `GET /staff/routes` | 2026-08-29 **부분 해제** (Ruling 180) | **§5.9**. 최적화 **자동 트리거·가중치**만 미확정으로 남았다 |
| `GET /staff/schedules` | 2026-08-26 해제 (Ruling 153) | §5.10 |
| `PATCH /staff/runs/{runId}/assignment` | 2026-08-26 **부분 해제** (Ruling 153) | **§5.14**. **동승자 자동 배정**만 미확정으로 남았다 — 엔드포인트가 아니라 노선 계산 파이프라인 ⑤단계다(`ARCHITECTURE §8.2`) |
| `POST /staff/runs/{runId}/forced-add` | 2026-08-30 **부분 해제** (Ruling 197) | **§5.7**. 이 엔드포인트는 재최적화를 부르지 않아(Ruling 198) 최적화 **가중치**와 무관하다 |

⚠ **이 절은 파생본이라 정본이 닫혀도 자동으로 따라오지 않는다.** 실제로 위 3행이 해제 후에도 표에 남아 있었다(Ruling 185). **한 행을 고칠 일이 생기면 표 전체를 각 절과 대조한다** — 하나가 낡아 있으면 나머지도 낡아 있다.

관련 오픈 이슈(노선 최적화 알고리즘 기준, 승하차지 상세 관리, 지도 SDK 선정, 지오코딩 API)는 [PRD.md](./PRD.md) 참조.
