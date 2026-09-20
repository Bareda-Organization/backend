# Phase 3 완료 근거 — 조건 7항 실증

정본은 `p3-goal-table.md` 의 목표 7항. 판정 시점 2026-08-26, 커밋 `93773ab`
(최초 판정은 `9fb3ca7` 이고, 수정 라운드 1(Ruling 146-3·147)의 반영분을 조건 2·4 에 표시).
**테스트 이름의 유사성이 아니라 단언 본문을 소스로 확인한 결과**이며, 파일:줄 번호는 그 커밋 기준.

## 판정 요약

| # | 완료 조건 | 판정 | 실증 테스트 |
|:-:|---|:-:|---|
| 1 | 흐름 완주(등록 → 승인 → 로그인 → 학부모 승인·거절) | **실증** | `AcademyOnboardingFlowTest` |
| 2 | 두 번째 관계자 승인 `409` + 동시 2요청 성공1·실패1 | **실증** | `AdminStaffApprovalControllerTest` · `StaffApprovalConcurrencyTest` |
| 3 | 거절 계정이 재신청으로 `pending` 복귀 + 큐 재등장 | **실증** | `SignupReapplyQueueTest` |
| 4 | 학원 비활성화 후 기존 계정 로그인 유지 · 신규만 차단 | **실증**(기사·관계자 두 축) | `AcademyDeactivationTest` |
| 5 | 차단 해제 후 로그인 + 처리자·일시 이력 + 카운터 0 | **실증** | `AdminBlockedAccountControllerTest` |
| 6 | 계정 상태 게이트 거부측을 Phase 3 신규 핸들러로 확대 | **실증** | `AuthFlowIntegrationTest` |
| 7 | 접근 회수 경로마다 refresh 토큰 전량 무효화 | **실증** | `AdminStaffAccountControllerTest` |

**미실증 0항.** 직전 판정에서 조건 4 에 남겼던 실증 범위 비고(`staff` 역할 로그인 경로 미검사)는
수정 라운드 1 에서 단언으로 닫음 — 아래 §4 참조.

## 공통 실행 명령과 출력

```
cd backend && ./gradlew test --rerun-tasks \
  --tests '*AcademyOnboardingFlowTest' --tests '*AdminStaffApprovalControllerTest' \
  --tests '*StaffApprovalConcurrencyTest' --tests '*AcademyStaffQuotaConcurrencyTest' \
  --tests '*SignupReapplyQueueTest' --tests '*AcademyDeactivationTest' \
  --tests '*AdminBlockedAccountControllerTest' --tests '*AdminStaffAccountControllerTest' \
  --tests '*AuthFlowIntegrationTest' --tests '*AccountStatusGateInterceptorTest' \
  --tests '*SignupApprovalControllerTest' --tests '*ErrorCodeCatalogTest'
```

```
BUILD SUCCESSFUL in 17s

AcademyDeactivationTest                tests=  4 failures=0 errors=0
AcademyStaffQuotaConcurrencyTest       tests=  1 failures=0 errors=0
AdminStaffAccountControllerTest        tests= 16 failures=0 errors=0
AcademyOnboardingFlowTest              tests=  1 failures=0 errors=0
AuthFlowIntegrationTest                tests=  4 failures=0 errors=0
SignupReapplyQueueTest                 tests=  2 failures=0 errors=0
AdminBlockedAccountControllerTest      tests= 10 failures=0 errors=0
AdminStaffApprovalControllerTest       tests= 12 failures=0 errors=0
SignupApprovalControllerTest           tests= 25 failures=0 errors=0
StaffApprovalConcurrencyTest           tests=  1 failures=0 errors=0
ErrorCodeCatalogTest                   tests=  2 failures=0 errors=0
AccountStatusGateInterceptorTest       tests=  8 failures=0 errors=0
TOTAL 86
```

테스트 전체 묶음 — `cd backend && ./gradlew test --rerun-tasks` → `BUILD SUCCESSFUL in 1m 42s`,
XML 집계 **`classes=70 tests=414 skipped=0 failures=0 errors=0`**(실패 클래스 부재).

---

## 조건 1 — 흐름 완주

**실증 테스트** — `AcademyOnboardingFlowTest#학원_등록부터_학부모_승인과_거절까지_한_흐름으로_완주한다`
(`backend/src/test/java/src/backend/account/AcademyOnboardingFlowTest.java:69`)

**왜 이 단언이 조건을 실증하는가** — 정본 5단계가 전부 존재하고, 각 단계의 **입력이 앞 단계 응답값**이라
"등록한 학원에 실제로 가입할 수 있는가" 가 검사 대상.

| 정본 단계 | 줄 | 뒤 단계로 넘기는 값 |
|---|:-:|---|
| 메인 관리자 로그인 | 70 | `adminToken` (시드 자격을 쓰는 유일한 지점) |
| 학원 등록 `POST /admin/academies` | 72 | 응답의 `academy_id`(:78) · **서버가 생성한** `code`(:79) |
| — 생성 코드로 가입용 검색 왕복 | 81 | `code` → `GET /academies/search` 결과의 `id` 가 `academy_id` 와 동일 |
| 관계자 가입 요청 승인 | 86 | `academy_id` 로 가입(:83) → `code` 로 큐에서 식별(:84) → `request_id` |
| 관계자 로그인 | 93 | 방금 승인한 자격으로 `staffToken` |
| 학부모 요청 **수락** | 99 | `staffToken` · `request_id`(:97) · `studentId`(:94) |
| 학부모 요청 **거절** | 109 | `reject_reason` 필수 경로까지 밟음 |

**"독립 호출 6개" 와 갈리는 지점** — ①`관계자_요청_식별자()`(:167)가 시드 목록이 아니라
`$.data.items[?(@.academy.code == '<생성 코드>')]` 로 걸러 `hasSize(1)` 을 단언 — 등록한 학원의 요청이
메인 관리자 큐에 실제로 떠야 통과. ②말미(:120~125)의 `guardian_student ⋈ guardian` 조인이
`g.academy_id = <등록한 학원>` 조건을 달아 1행을 요구 — 승인이 만든 연결이 **그 학원 아래** 놓였는지까지 봄.

**호출 순서가 정본 나열과 다른 곳 2건** — ①학부모 가입 요청 생성(:96·:106)은 정본 문면에 부재하나
승인·거절 대상이 없으면 그 단계가 성립 불가. ②학생 1건 삽입(:94)이 관계자 로그인 뒤에 옴 —
AUTH-11 수락이 요구하는 연결 대상.

**우회 1건** — 학생 레코드는 raw INSERT(`학생을_심는다()`, :191). 학생 등록 API(STU-01)가 Phase 5 소유라 부재.
판정은 보고서 ① 참조.

## 조건 2 — 두 번째 관계자 승인 `409`

**①선검사 축** — `AdminStaffApprovalControllerTest#이미_active_관계자가_있는_학원의_관계자_가입_요청을_승인하면_409_STAFF_QUOTA_EXCEEDED_다`
(`.../account/controller/AdminStaffApprovalControllerTest.java:148`)

`POST /api/v1/admin/staff-signup-requests/{id}/decide` + `{"accept": true}` 로 두드려
`409` · `$.error.code == STAFF_QUOTA_EXCEEDED` 를 본다(:149~151). **응답 단언만으로 끝내지 않음** —
`재직_관계자_수(1L) == 1`(:153)과 `계정_상태(...) == "pending"`(:154)이 붙어, 409 를 돌려주면서 행은 만들어 버린
구현을 배제. 그 구현에서는 다음 조회부터 관계자가 둘.

**②동시성 축** — `StaffApprovalConcurrencyTest#같은_학원의_관계자_승인_요청_두_건이_동시에_들어오면_성공_1건_실패_1건이다`
(`.../account/controller/StaffApprovalConcurrencyTest.java:103`)

`CountDownLatch` 로 두 승인 호출을 같은 시점에 출발(:104~109). 단언 4개 —
성공 200 이 **정확히 1건**(:118), 실패가 **500 이 아니라 409**(:121), 오류 코드가 `STAFF_QUOTA_EXCEEDED`(:124),
그리고 응답과 무관하게 **DB 잔존 재직자 1명**(:129). 애플리케이션 선검사만 있는 구현은 두 요청이 서로의
미커밋 INSERT 를 못 봐 둘 다 검사를 지나고 조건부 UNIQUE 가 `500` 을 내므로 :121 에서 실패.

**서비스 계층 동반 단언** — `AcademyStaffQuotaConcurrencyTest#같은_학원에_동시에_두_요청이_들어오면_성공_1건_실패_1건이다`
(`.../academy/command/AcademyStaffQuotaConcurrencyTest.java:126`)가 같은 성질을 엔드포인트 밖에서 고정.

**2026-08-26 갱신(Ruling 147) — 같은 승인 경로의 인접 거부 코드가 바뀌었다.** 승인 **대상 계정이
`blocked`** 인 경우가 `403 AUTH_ACCOUNT_BLOCKED` 에서 **`409 SIGNUP_TARGET_BLOCKED`** 로 옮겨졌다.
조건 2 의 판정에는 영향이 부재하나(그 조건은 정원 초과 축이다) **같은 엔드포인트의 거부 어휘**라
함께 읽어야 한다 — 실증은
`SignupApprovalControllerTest#승인_대기_중_차단된_계정을_수락하려_하면_409_SIGNUP_TARGET_BLOCKED_다`
(`.../account/controller/SignupApprovalControllerTest.java:277`)이고, HTTP 상태가 403 으로 되돌아가는 것은
`ErrorCodeCatalogTest#Phase_3_이_쓰는_에러_코드_9종의_HTTP_상태가_사양과_같다` 가 잡는다.
`ErrorCode` 는 **25종**(직전까지 24종).

## 조건 3 — 거절 계정의 `pending` 복귀

**실증 테스트** — `SignupReapplyQueueTest#거절된_계정이_재신청하면_pending_으로_돌아오고_관계자_목록에_새_요청이_다시_뜬다`
(`backend/src/test/java/src/backend/account/SignupReapplyQueueTest.java:88`)

전이를 픽스처로 심지 않고 **거절 엔드포인트로 만든다**(:89 `POST /staff/signup-requests/{id}/decide`,
`accept=false`). 이어 `계정_상태() == "rejected"`(:96), 거절한 요청이 큐에서 사라짐(:97), 재신청 200 ·
`$.data.status == "pending"`(:105), `계정_상태() == "pending"`(:108).

**조건의 뒷 문장을 고정하는 단언** — :109 의 `대기_요청_식별자들()` 이 `hasSize(1)` **그리고**
`doesNotContain(FIRST_REQUEST)`. 그 도우미(:163)는 DB 가 아니라 **`GET /api/v1/staff/signup-requests` 응답**을
읽으므로 "승인 큐에 다시 뜬다" 를 그대로 검사. 계정 상태만 되돌리고 새 행을 안 만드는 구현은
`hasSize(1)` 에서, 옛 행을 재사용하는 구현은 `doesNotContain` 에서 실패.

## 조건 4 — 학원 비활성화

**실증 테스트** — `AcademyDeactivationTest`(`backend/src/test/java/src/backend/academy/AcademyDeactivationTest.java`)

| 정본 소항 | 메서드 | 줄 | 단언 |
|---|---|:-:|---|
| ①기존 계정 로그인 **200**(기사 축) | `학원을_비활성화해도_그_학원_기존_계정의_로그인은_200_이다` | 96 | 비활성화 후 `POST /auth/login` → `200` + `access_token` 비어 있지 않음 |
| ①기존 계정 로그인 **200**(관계자 축) | `비활성_학원_소속_재직_관계자의_로그인도_200_이다` | 122 | 시드 `staffC` 로 `POST /auth/login` → `200` · `status=="active"` + `access_token` 비어 있지 않음 |
| ②검색 결과에서 부재 | `학원을_비활성화하면_가입용_학원_검색_결과에서_사라진다` | 134 | 비활성화 **전** `items.length()==1`, **후** `==0` |
| ③그 학원 지정 가입 4xx | `비활성_학원을_지정한_회원가입_요청은_404_ACADEMY_NOT_FOUND_로_거부된다` | 154 | `404` + `ACADEMY_NOT_FOUND`(§8.5 정의가 "미등록 · 비활성 학원 지정") |

②는 **비활성화 전 1건**을 먼저 단언해 "항상 공집합" 구현을 배제 — 뒷 단언만 두면 검색이 늘 비어 있어도 통과.
①이 조건의 본체이며, 이것이 없으면 "비활성화 = 전면 차단" 구현이 ②③만으로 통과.

**2026-08-26 갱신(Ruling 146-3) — 정본이 지목한 `staffC` 축을 실측으로 닫음.**
직전까지 ①의 재료가 자체 픽스처(`P3T1DEACT` 학원 + `p3t1deactdriver` **기사** 계정, :78~88)뿐이라
**`staff` 역할 로그인 경로**(T3 이 신설한 `academy_staff` 재직 검사를 추가로 통과해야 하는 경로)가
비활성 학원에 대해 미검사였음 — 조건 문면은 충족하나 "동작 차이 부재" 가 **예상이지 실측이 아닌**
상태. `비활성_학원_소속_재직_관계자의_로그인도_200_이다`(:122)가 그 자리를 닫음.

**시드 `staffC` 의 세 성질은 시드에서 직접 확인**(인용 아님) — `V2__seed_data.sql:28` 학원 3
`BARAEDA-C` 가 `inactive` · `:63` 계정 `staffC` 가 `role=staff` `status=active` `academy_id=3` ·
`:81` `academy_staff (3, 3, 20, 'active')`. `SeedFixturesContractTest:92·98` 이 앞 두 성질을 별도로 고정.

**이 자리가 비어 있던 이유** — T1(학원 비활성화)과 T3(재직 검사)의 교차점이라 어느 태스크도 자기
범위로 보지 않은 사각지대. 종단 태스크가 잡는 것이 맞음.

## 조건 5 — 차단 해제

**실증 테스트** — `AdminBlockedAccountControllerTest`
(`backend/src/test/java/src/backend/account/controller/AdminBlockedAccountControllerTest.java`)

| 정본 소항 | 메서드 | 줄 | 단언 |
|---|---|:-:|---|
| ①목록에 `driverBlocked` 등장 | `차단_계정_목록에_blocked_상태_계정이_등장한다` | 80 | 로그인 아이디 포함 + `academy_name`·`failed_attempts(5)`·`reason`·`blocked_at` |
| ②해제 200 ③로그인 200 | `해제하면_계정이_active_가_되고_그_계정으로_로그인이_200_이다` | 111 | `POST /admin/blocked-accounts/{id}/unblock` → `200` · `account_status=="active"`, 이어 `POST /auth/login` → `200` |
| ④`unblocked_by`·`unblocked_at` | `해제하면_unblocked_by_와_unblocked_at_이_적재된다` | 149 | 처리자가 **요청 토큰의 메인 관리자 계정 식별자**와 동일 · 일시 not null |
| ⑤`failed_attempts == 0` | `해제_직후_failed_attempts_가_0_이다` | 135 | 해제 **전** 5 를 먼저 단언(:136) 후 해제 → 0(:142) |

⑤가 **로그인을 거치지 않고 행을 직접 읽는** 것이 핵심(:127~132 주석과 일치) — 로그인을 태우면
`Account.recordLoginSuccess` 가 카운터를 0 으로 돌려 초기화 누락이 가려짐. ④의 처리자 대조는 토큰 주체와
저장값을 맞춰 봐 "아무 값이나 채운" 구현을 배제.

## 조건 6 — 계정 상태 게이트 거부측 확대

**실증 테스트** — `AuthFlowIntegrationTest`
(`backend/src/test/java/src/backend/account/AuthFlowIntegrationTest.java`)

**판정 직전 소스 직접 계수**(횡단 규칙 24) — `src/main` 의 `@RestController` 파일에서 매핑 애너테이션
(`@Get|Post|Put|Patch|DeleteMapping`)을 세면 **24개**.

| 컨트롤러 | 핸들러 |
|---|:-:|
| `AcademySearchController` | 1 |
| `AdminAcademyController` | 4 |
| `AdminStaffAccountController` | 2 |
| `AdminBlockedAccountController` | 2 |
| `AdminStaffApprovalController` | 2 |
| `AuthController` | 5 |
| `MeController` | 1 |
| `SignupApprovalController` | 2 |
| `SignupController` | 3 |
| `DeviceController` | 2 |
| **합계** | **24** |

`@PublicEndpoint` 5개(`GET /academies/search` · `POST /auth/{signup,login,refresh,recover}`) ·
`@AllowedWhenPending` 5개 · `@AllowedWhenRejected` 1개 → **`pending` 거부측 14 · `rejected` 거부측 13.**

| 단언 | 줄 | 무엇을 고정하는가 |
|---|:-:|---|
| `pending_토큰은_허용_5개를_통과하고_허용_밖_실제_엔드포인트_전부에서_403_AUTH_PENDING_이다` | 149 | 허용 5개 왕복 + 거부측 **14개 전부** `403 AUTH_PENDING` |
| `rejected_토큰은_..._403_AUTH_REJECTED_이다` | 199 | 허용 6개 왕복 + 거부측 **13개 전부** `403 AUTH_REJECTED` |
| `거부측_목록이_허용_목록_밖_실제_핸들러_전부와_일치한다` | 169 | 손 목록과 `RequestMappingHandlerMapping` 실측 집합의 **양방향** 일치 + 전체 24 |

거부측 목록의 정본은 `backend/src/test/java/testsupport/gate/AccountStatusGateEndpoints.java`
(`DENIED_WHEN_REJECTED` 13개 · `DENIED_WHEN_PENDING` = 그 13개 + `POST /auth/signup/reapply`).

세 번째 단언이 조건의 **"확대" 를 앞으로도 유지**시키는 장치. 다음 Phase 가 엔드포인트를 늘리고 목록을
안 고치면 앞 두 왕복은 초록인 채 검사 범위만 좁아지는데, 그 사고를 여기서 잡음(음성 대조 V3 로 실증 —
`p3-task-4-report.md` ④).

**`AccountStatusGateInterceptorTest#허용_애너테이션이_붙은_실제_핸들러_수가_pending_5개_rejected_6개다`
(`.../global/security/gate/AccountStatusGateInterceptorTest.java:133`)의 5/6 은 정정 대상 부재** —
Phase 3 이 허용 애너테이션을 새로 부착하지 않았음을 소스 계수로 확인.

**게이트 순서 실측** — `pending`·`rejected` 토큰(역할 `parent`)으로 `/admin/**` 를 부르면
`403 AUTH_PENDING`·`403 AUTH_REJECTED`. 즉 **상태 게이트가 권한 판정보다 먼저** 걸림.
대조군으로 `active` 관계자 토큰의 `GET /admin/academies` 는 `403 FORBIDDEN`
(`AdminAcademyControllerTest:344`). 판정은 보고서 ① 참조.

## 조건 7 — 접근 회수 경로의 refresh 토큰 전량 무효화

**실증 테스트** — `AdminStaffAccountControllerTest`
(`backend/src/test/java/src/backend/academy/controller/AdminStaffAccountControllerTest.java`)

| 정본이 지목한 경로 | 메서드 | 줄 | 단언 |
|---|---|:-:|---|
| `PATCH /admin/staff-accounts/{id}` `status=inactive` | `관계자_계정을_inactive_로_바꾸면_그_계정의_기존_refresh_토큰이_401_TOKEN_EXPIRED_다` | 140 | 조작 **전에 받아 둔** refresh 로 `POST /auth/refresh` → `401` · `TOKEN_EXPIRED` |
| `PATCH ...` `reset_password=true` | `비밀번호를_초기화하면_그_계정의_기존_refresh_토큰이_401_TOKEN_EXPIRED_다` | 158 | 동일 |

**짝 단언** — `이름만_수정하면_기존_refresh_토큰은_그대로_재발급된다`(:176)가 `{"name":...}` 수정 후
재발급 `200` 을 요구. 이것이 없으면 **"어떤 수정이든 무조건 전량 무효화"** 하는 구현도 위 둘을 통과하고,
그 구현에서는 연락처 오타 하나를 고칠 때마다 운행 중 관계자가 재로그인을 요구받음.

**정본이 지목한 2경로가 Phase 3 의 접근 회수 경로 전부인지 확인** — Phase 3 이 만든 나머지 상태 전이는
①가입 요청 거절(`pending → rejected`)은 허용 집합이 5 → 6 으로 **늘어** 회수가 아님
②`POST /admin/blocked-accounts/{id}/unblock` 은 접근 부여
③`PATCH /admin/academies/{id}` `status=inactive` 는 조건 4 ①이 기존 계정 접근 **유지**를 요구.
따라서 회수 경로는 위 2건이 전부.
