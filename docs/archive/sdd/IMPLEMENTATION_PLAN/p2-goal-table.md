# Phase 2 완료 조건 — 목표 표 (착수 전 고정, Ruling 40)

출처: `docs/IMPLEMENTATION_PLAN.md` Phase 2 절의 완료 조건 **11개**. **이 표가 정본이고 명령을 지어내지 마라.**
작성 시점: 2026-08-25, Phase 1 진행 중. **Phase 2 착수 전에 이 표를 확정하고, 착수 후 목표를 늘리지 않는다.**

## 목표 11항

| # | 완료 조건 | 실행 가능한 명령 · 단언 | 무엇이 깨지면 이 문장이 거짓이 되는가 |
|:-:|---|---|---|
| 1 | Swagger 흐름 완주 — 학원 검색 → 가입 → `pending` 조회 → 로그인 → 토큰 재발급 → 로그아웃 | 통합 테스트 1개가 이 순서를 **한 흐름으로** 밟고 각 단계 2xx. `MockMvc`/`RestTestClient` + Testcontainers | 중간 단계가 404·401 로 끊김 · 앞 단계 응답값이 뒤 단계 입력으로 안 이어짐 |
| 2 | `pending` 토큰으로 허용 **5개** 외 전부 `403 AUTH_PENDING` | 허용 5개(`GET /auth/signup-status` · `POST /auth/logout` · `GET /me` · `POST /me/devices` · `DELETE /me/devices/{token}`)는 2xx, **그 밖의 실제 엔드포인트 최소 3개**에 대해 `403` + 본문 `code == "AUTH_PENDING"` | **허용 목록이 아니라 차단 목록으로 구현**하면 새 엔드포인트가 자동으로 열린다 — 그 사고를 잡으려면 "그 밖" 을 하드코딩 1개가 아니라 여러 개로 확인해야 함. ⚠ 2026-08-25 Ruling 98 로 2개 → **5개**. `POST`·`DELETE /me/devices` 는 **핸들러 2개**로 센다 |
| 3 | `rejected` 토큰은 **6개**까지 허용 | 허용 6개(`pending` 의 5개 + `POST /auth/signup/reapply`) 2xx, 그 밖 `403 AUTH_REJECTED`. **`pending` 토큰으로 `reapply` 를 부르면 `403`** | 상태별 허용 집합이 하나로 합쳐지면 `pending` 이 재신청까지 할 수 있게 됨 — 마지막 단언만이 그것을 가른다. ⚠ 거부 응답 코드는 `AUTH_PENDING` 이 아니라 **`AUTH_REJECTED`**(§8.1, Ruling 86) |
| 4 | `blocked` 계정 로그인 시 `403 AUTH_ACCOUNT_BLOCKED` | `POST /auth/login` 에 `driverBlocked` 자격 → `403` + `code == "AUTH_ACCOUNT_BLOCKED"` | 401(자격 오류)로 응답하면 사용자가 비밀번호를 계속 시도 |
| 5 | 로그인 실패 누적이 상한 도달 시 계정 차단, **성공 시 카운터 초기화** | 실패를 상한까지 반복 → 상태가 `blocked` 로 전이. **별도로** 실패 몇 회 후 성공 → `failed_attempts == 0` 확인 | **후자가 없으면 "한 번 실패하면 영원히 누적" 구현이 통과.** 상한값은 `C-11` 로 참조하고 테스트에 숫자를 박지 않는다 |
| 6 | A학원 `staff` 토큰으로 B학원 자원 조회 시 `403 ACADEMY_SCOPE_VIOLATION` — **목록 조회에서도 성립** | ①단건 조회(B의 자원 id 지정) → `403` ②**목록 조회 → 응답에 B 자원이 0건** | ⚠ **②가 핵심이다.** 목록에서 조건 하나가 빠져도 단건은 여전히 403 이라 ①만으로는 통과한다. 시드의 학원 B 독립 계통이 이 단언의 재료다 |
| 7 | `X-Client-Type: web` 로그인 응답 — 본문에 `refresh_token` **부재** + `Set-Cookie` 4속성 전부 | 응답 JSON 에 `refresh_token` 키 부재. `Set-Cookie` 에 `HttpOnly`·`Secure`·`SameSite=Strict`·**`Path=/api/v1/auth`** **4개 각각** 확인 | 속성 하나가 빠진 경로는 **로컬에서 재현되지 않는다**. 4개를 각각 단언해야 함 |
| 8 | 쿠키만 담은 `POST /auth/refresh` 가 access 재발급 성공 · 본문·쿠키 **모두 부재** 시 `401 TOKEN_EXPIRED` | ①쿠키만 전송 → 200 + 새 access ②본문·쿠키 둘 다 없이 → `401` + `code == "TOKEN_EXPIRED"` | ②가 없으면 "아무 요청에나 토큰을 발급" 하는 구현이 통과 |
| 9 | 웹 로그아웃 응답에 `Max-Age=0` 쿠키 삭제 지시 | `POST /auth/logout`(web) 응답 `Set-Cookie` 에 `Max-Age=0` | 삭제 지시가 없으면 브라우저에 refresh 쿠키가 남음 |
| 10 | `ControllerAuthorizationConventionTest` — 인가 애너테이션 누락 핸들러 **0건** | `./gradlew test --tests '*ControllerAuthorizationConventionTest' --rerun-tasks` 통과. **대상 핸들러 수가 0이 아님을 함께 단언** | ⚠ **컨트롤러가 0개면 "검사 대상 부재로 항상 초록"** 이다(Phase 0 에서 실제로 그 상태였다). 대상 수 하한 단언이 없으면 이 조건은 아무것도 보장하지 않는다 |
| 11 | 역할↔권한 부여표 테스트 — `FEATURE_SPEC §6.1` 매트릭스와 **1:1 대조** | 부여표의 (역할 × 권한) 집합이 매트릭스와 **양방향** 일치. 부여표에 있는데 매트릭스에 없는 것도 실패 | 한 방향만 보면 **과다 부여**를 놓친다 — 권한이 넘치는 쪽이 모자란 쪽보다 위험하다 |

## 목표를 늘리지 않았다는 확인

위 11항은 Phase 2 절의 완료 조건을 그대로 옮긴 것이다. **산출물**(`account` 모듈 · 권한 상수 **31종** · 부여표 · 계정 상태 필터 · 저장소 격리 · 쿠키 조립기)은 새 목표가 아니라 이 조건들을 통과시키는 **수단**이다.

## 착수 전에 해소해야 하는 것

- **오픈 이슈 확인** — `docs/IMPLEMENTATION_PLAN.md §9` 에서 Phase 2 를 막는 미결정이 있는지 본다
- **Phase 1 이 남긴 이월** — `API_SPEC §9` enum 사전 누락 12건 중 `verification_code.purpose`(`login_id`·`password`)는 이미 정정됐다. `AUTH-08`(SMS 복구) 구현 시 나머지를 확인
- **`X-Client-Type` 헤더의 Swagger 노출** — Ruling 36 으로 **Phase 2 의 로그인 컨트롤러 메서드에 `@Parameter`** 로 붙이기로 확정돼 있다
- **`§3.3` 겹②③**(`SwaggerExampleSeedContractTest` · 소스 규약) — Phase 1 이 컨트롤러 0개라 미구현. **Phase 2 가 컨트롤러를 만들면 그때 의미가 생긴다.** Ruling 45 참조

## 부분 통과 처리

11항 중 하나라도 미통과면 `§8` 표는 🟡 이고 미통과 항목을 비고에 적는다. 완료 선언 시 항목마다 **실행 명령과 실제 출력**을 원장에 남긴다. 남기지 못한 항목은 통과로 세지 않는다.

---

## 2026-08-25 정정 (재개 세션, 조율자)

**파생본이 정본보다 낡아 2건을 맞췄다.** 정본은 `docs/IMPLEMENTATION_PLAN.md` Phase 2 절이고, 실측 대조 결과 정본·코드·`docs/API_SPEC.md` 3자는 이미 일치했다 — **어긋난 것은 이 표 하나뿐이다.**

| # | 옛 값 | 정정 | 근거 |
|:-:|---|---|---|
| 조건 7 | `Path=/api/auth` | **`Path=/api/v1/auth`** | Ruling 102 로 베이스 경로가 `addPathPrefix("/api/v1")` 로 확정. `API_SPEC:76`·`:304`·`:329` · `RefreshTokenCookieAssembler:16` 모두 새 값 |
| 산출물 | 권한 상수 27종 | **31종** | T1 실측. 정본도 31종 |

⚠ **이 표는 완료 판정의 기준이라 낡은 값 하나가 곧 오판이 된다.** 옛 값 그대로 검증했다면 조건 7 이 거짓으로 실패하고, 그 실패를 코드 결함으로 오진했을 것이다.
