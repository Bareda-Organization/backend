# MVP_API_SPEC.md 재검증 — `docs-drift-auditor` 첫 실전 투입

- **일자**: 2026-07-28 (오후)
- **선행 보고서**: `2026-07-28-mvp-api-spec-검증.md` (오전, `general-purpose` 4개, 10건 발견)
- **이번 방식**: 신설 전역 에이전트 **`docs-drift-auditor`** 4개 병렬 (전체 패스 1 + 섹션 집중 패스 3)
- **코드 수정 없음 · 문서 수정 없음** — 보고 전용

---

## 0. 총괄

| 지표 | 결과 |
|---|---|
| 오전 10건 재검증 | **10건 전부 사실 확인, 반증 0건** |
| 신규 발견 | **12건** |
| 누적 드리프트 | **22건** |
| 런타임 확인 필요(`미확인`) | 1건 |
| 계약 레벨(경로·역할·DTO 필드·enum·에러코드) 드리프트 | **여전히 0건** |
| MVP 태그 개수 | **17개 재확인** (기준선 유지) |

**코드 결함은 이번에도 0건이다.** 22건 전부 문서가 코드를 부정확하게 서술한 문제이며, 고칠 대상은 `MVP_API_SPEC.md` 한 파일이다.

### 투입 구성

| 에이전트 | 범위 | 발견 |
|---|---|---|
| `drift-mvp-api-spec` | §1~§9 전체 | 오전 10건 재확인 + 신규 1 + 미확인 1 |
| `drift-s4-rideevent` | §4 승하차 | 신규 3 (오전 4건 포함 재현) |
| `drift-s5-routing` | §5 배차 | 신규 7 (오전 5건 포함 재현, 총 12항목) |
| `drift-s678-notif-ws` | §1·6·7·8 | 신규 1 (§6·§7 전 항목 일치) |

---

## 1. 신규 발견 12건

### 상 — 프론트가 문서대로 구현하면 실제로 깨진다

#### NEW-1. §4.1 — 등하원 왕복 시 **하원 알림이 통째로 누락**된다
오전에는 "dedup 중복이면 skip된다"까지만 잡았으나, `stopId` 기본값과 겹치면서 **MVP 일상 시나리오에서 실제로 터지는 것**이 확인됐다.

- `RideEventCommandService.java:63-64` — `stopId` 생략 시 type과 무관하게 **항상 학생의 "승차" 정류장**(`boardingStop`)으로 채운다. 하차·하원에도 같은 값이 박힌다.
- dedupKey = `타입:studentId:날짜:stage`, `stage` = `"stop"+stopId` (`DomainEventNotificationConsumer.java:38`)
- 결과:
  - 등원 BOARD(집앞, stopId 생략) → `BOARD_DONE:1:2026-07-28:stop1` → 알림 **O**
  - 하원 BOARD(학원앞, stopId 생략) → **동일 키** → 알림 **X**, WS push **X**, 응답은 **200 + 새 RideEvent id**
- 문서는 `stopId`를 "생략 가능"으로만 적고 이 결과를 언급하지 않는다. **프론트가 "200이면 학부모에게 알림이 갔다"고 가정하면 하원 알림이 전부 사라진다.**
- 회피법: 요청에 `stopId`를 명시.

#### NEW-2. §4 전 엔드포인트 — 잘못된 입력이 400이 아니라 **500**으로 나간다
- `GlobalExceptionHandler`에 핸들러가 `BusinessException` · `AccessDeniedException` · `MethodArgumentNotValidException` · `Exception`(catch-all, `:48-53`) 넷뿐이다.
- **`MethodArgumentTypeMismatchException` · `HttpMessageNotReadableException` 핸들러가 없어** catch-all에 걸린다:
  - `?date=2026-13-99` → 500
  - `"type":"BOARDED"` (enum 오타) → Jackson `InvalidFormatException` → 500
- 추가로 **§4.1 예외 표에 `400 필드 누락` 행 자체가 없다**(`RecordRideRequest.java:11-13`에 `@NotNull` 3개). 형제 절인 §3.2는 이 행을 명시하고 있어 §4.1만 빠졌다.
- 실제 응답 코드는 기동 후 1회 확인 권장(정적 판정). 단 **400 행 누락은 기동 없이도 확정적**이다.

### 중 — 오해 소지가 크다

#### NEW-3. §1.1·§1.3 — 미인증 401은 **봉투가 없는 빈 본문**이다
- 문서 `:28` "모든 REST 응답은 `ApiResponse<T>` 한 포맷", `:62` `UNAUTHORIZED | 401 | 인증이 필요합니다`
- 실제: `SecurityConfig.java:67` `HttpStatusEntryPoint(UNAUTHORIZED)` — **상태코드만 쓰고 본문을 안 쓴다.** `JwtAuthenticationFilter.java:49-52`도 위조·만료 토큰에 예외를 던지지 않고 `SecurityContext`만 비우므로 `GlobalExceptionHandler`를 타는 경로가 없다.
- `ErrorCode.UNAUTHORIZED`가 봉투로 실제 반환되는 곳은 **`POST /api/auth/refresh` 실패뿐**(`AuthQueryService.java:59,62,65`).
- §1.2가 "401 수신 시 refreshToken으로 재발급"을 안내하므로, **프론트가 401 body를 파싱해 분기하면 빈 본문에서 실패한다.**
- 403은 정상(`GlobalExceptionHandler:32-36`이 봉투로 반환).

#### NEW-4. §4.1 — 알림 수신 기사가 **기록한 기사가 아닐 수 있다**
- `PushTargetResolver.java:45-50`은 기사 대상을 `student.getAssignedBus().getDriver()`로 구한다(RideEvent의 `busId`가 아님).
- 그런데 `record()`는 학생↔버스의 **학원 소속만** 검사하고(`:59-61`) 학생이 그 버스에 배정됐는지는 검사하지 않는다.
- → 다른 버스 학생을 기록하면 알림은 그 학생의 배정 기사에게 가고, **기록한 기사는 개인 큐로 못 받는다.**

#### NEW-5. §5.5 — 목록 응답에도 `stops[]`가 전부 실린다 (+ N+1)
- 문서 `:378`은 "상세. 정차 순서(`stops[]`)까지 포함한 상세"라 해서 목록엔 없는 것처럼 읽힌다.
- 실제: 목록·상세 모두 같은 `RoutePlanResponse.from()`(`RoutePlanResponse.java:33-38`)을 쓴다(`RoutingQueryService.java:49`). **응답 크기 예측이 어긋나고**, 목록 조회에서 계획 수만큼 stop 조회가 도는 N+1이 발생한다(별도 이슈).

#### NEW-6. §5.5 — 목록 정렬 순서가 **아예 서술되지 않았다**
- 실제: `findByTenantIdOrderByCreatedAtDesc` / `findByTenantIdAndBusIdOrderByCreatedAtDesc`(`RoutePlanRepository.java:17-19`) → **`createdAt` 내림차순(최신 우선)**
- auto-assign을 두 번 호출하면 이전 `RECOMMENDED` 계획이 그대로 누적되므로(`:292-295`, 버전만 +1 된 새 행), 프론트가 "가장 최근 제안"을 고르려면 이 정렬 보장이 문서에 있어야 한다.

#### NEW-7. §5.2 — 부분 실패 시 **전체 롤백**이 미서술
- `confirmAutoAssign`이 단일 `@Transactional`(`:188`)이라 `planIds` 중 하나라도 409/404면 **앞서 확정된 계획의 학생 배정까지 전부 롤백**된다.
- "여러 건을 한 번에" 처리하는 API인데 이 원자성 보장이 문서에 없다.

### 경 — 정확도 보완

| ID | 내용 | 근거 |
|---|---|---|
| NEW-8 | §5.1 예외표에 **외부 경로 API 실패 행이 없다** — NCP 키 미설정·쿼터 초과 시 `400 INVALID_INPUT "네이버 경로 조회에 실패했습니다"`가 나가 프론트가 "입력값 문제"로 오해한다 | `NaverMapRouteClient.java:110`, `application.yml:43`(provider 기본 naver) |
| NEW-9 | §5.2 예외표에 `@Valid` 400 행 없음 — `planIds` `@NotEmpty` 위반 시 `"planIds: <기본메시지>"` | `ConfirmAutoAssignRequest.java:10` |
| NEW-10 | §5.2 vs §5.4 **알림 수신자 서술이 서로 다르고, 둘 다 실제보다 좁다** — 실제는 학생 본인 + 보호자 전원 + 기사 개인큐 + 테넌트 broadcast | `MVP_API_SPEC.md:339` vs `:370`, `WebSocketNotificationSender.java:31-45` |
| NEW-11 | §5.1 "활성 로스터(**결석** 제외)"가 실제보다 좁음 — `ApprovalStatus.APPROVED`인 출결 예외 전부를 제외하며 `AttendanceType`은 `ABSENCE`(결석)·`LEAVE`(휴원) 2종이라 **휴원 학생도 제외**된다 | `AttendanceQueryService:76-82` |
| NEW-12 | §5.1 `excludedStudentNames` 예시가 시드로 **재현 불가** — 시드 학생 6명 전원이 `stop_id`를 가져 PICKUP에서는 항상 빈 배열. 반대로 DROPOFF는 `dropoff_lat/lng` 미설정 3명이 나와 예시의 1명과 다름 | `RoutingCommandService.java:346-358`, `V2__seed_data.sql:83-97` |

---

## 2. 미확인 1건 (런타임 확인 필요)

**§7.3 (`MVP_API_SPEC.md:471-474`) — "`/topic/tenant/{id}/**` SUBSCRIBE 자체가 거부된다"**

`StompAuthChannelInterceptor`는 인가 실패 시 CONNECT와 **동일하게** `IllegalArgumentException`을 던진다. Spring `clientInboundChannel`의 예외 처리 특성상 세션 전체가 ERROR 프레임과 함께 닫힐 가능성이 있는데(그러면 "구독만 거부"가 아니라 **재연결이 필요**해진다), 프레임워크 내부 동작이라 소스만으로 단정할 수 없다. 드리프트로 올리지 않고 런타임 확인 대상으로 남긴다.

---

## 3. 오전 10건 재검증 — 전부 사실

| ID | 요지 | 판정 |
|---|---|---|
| N-1 | §8 "버스 Mock 소스 없음"이 거짓 (기본 ON, 3초 자동 생성) | ✅ 확인 |
| G-1 | §5.1 `400 tenantId가 필요합니다` 도달 불가 (`@NotNull`이 선행 차단) | ✅ 확인 |
| G-2 | §5.5 목록에는 그 400이 누락 (두 절의 서술이 정확히 뒤바뀜) | ✅ 확인 |
| G-3 | §5.6 정렬이 실제로는 DROPOFF 먼저 (varchar ASC) | ✅ 확인 |
| G-4 | §5.1 `plans: []` / 빈 버스 케이스 미기재 | ✅ 확인 |
| G-5 | confirm이 DRAFT도 허용 | ✅ 확인 |
| R-1 | `stopId` fallback이 null일 수 있음 | ✅ 확인 |
| R-2 | dedup 시 알림 무음 skip | ✅ 확인 (→ NEW-1로 심각도 상향) |
| R-3 | 알림은 Kafka 비동기 | ✅ 확인 |
| R-4 | §4.1 예외표 순서 ≠ 실제 검사 순서 | ✅ 확인 |

**N-1 보강** — §8 비고가 거짓인 원인이 특정됐다: **prod 프로파일에서는 참**이다(`application.yml:123-124`에서 `bus-mock.enabled: false`). 문서가 프로파일 구분 없이 단정한 게 원인이다. 이로 인한 문서 내부 모순은 3곳이며(§3.1 `:171` `"origin":"MOCK"`, §3.2 `:191`, §8 폴링 근거 `:515`), **§8 비고 한 줄만 고치면 나머지 3곳은 이미 코드와 맞다.**

`G-4`는 재검증에서 두 케이스로 분리됐다: ① 전원 좌표 미보유 시 **에러가 아니라 200 + 빈 배열**(같은 조건에서 `/generate`는 400 `"생성할 로스터가 없습니다"`를 던져 **두 API의 0건 처리가 정반대**) ② 배정 0명 버스는 계획 자체가 생략돼 `plans.length ≤ 버스 대수`.

---

## 4. 일치 재확인 (드리프트 없음)

- **MVP 태그 정확히 17개** — `grep -rc '00. MVP 사용 API'` 실측: Auth 2 · Location 2 · RideEvent 4 · Routing 7 · Notification 2. 태그 오염 0건.
- **§2 Auth · §3 Location · §6 알림 · §7 WebSocket — 전 항목 일치.** 특히 §7은 endpoint·SockJS 미사용·CONNECT 1회 인증·heartbeat 양방향 10초·destination 4종·`/topic/tenant/{id}/**` SUBSCRIBE 추가 인가·`/app/location` 매핑·끊김 4단계까지 전부 코드로 확인.
- **§1.3 ErrorCode 8종** 이름·HTTP status·기본 메시지 문자 단위 일치. **§1.4 enum 7종** 값·순서까지 일치. `NotificationType` 10종 전부 실제 생산 경로 존재.
- **§8 주기 8개 값** 전부 현재 디스크 `application.yml`과 일치(미커밋 수정분 포함 확인). 임계값 5분/10분/3분도 `NotificationThresholds`와 일치.
- **§9 시드 계정** 5개의 이메일·이름·역할·`studentId=1`·`busId=1`·`tenantId=1`·형제자매·비밀번호 `password` 전부 `V2__seed_data.sql`과 일치.
- **§4 `RideEventResponse` 13필드 / §5 `RoutePlanResponse` 12필드 + `StopEntry`** 순서까지 일치.

---

## 5. 권고 조치 (우선순위)

| 순위 | 항목 | 조치 |
|---|---|---|
| 1 | NEW-1 | §4.1에 명시: "200이 알림 발송을 보장하지 않는다 — 같은 학생·날짜·type·정류장이면 두 번째부터 멱등 처리로 생략된다. **등하원 왕복을 구분하려면 `stopId`를 명시할 것**" |
| 2 | NEW-3 | §1.1에 "미인증 401만 예외적으로 빈 본문" 명시, §1.3 UNAUTHORIZED 행에 "refresh 실패에만 해당" 단서 추가 |
| 3 | N-1 | §8 비고를 프로파일 구분해 정정 (local ON / prod OFF). 문서 내부 모순 3곳 해소 |
| 4 | NEW-2 | §4.1에 `400 필드 누락` 행 추가 + 타입 불일치가 500으로 나가는 현황 주석 (핸들러 보강은 별건) |
| 5 | NEW-5·6·7 | §5.5에 "목록도 `stops[]` 포함", "`createdAt` 내림차순" 명시 / §5.2에 원자성(전체 롤백) 명시 |
| 6 | G-1·G-2 | 400 행을 §5.1에서 삭제하고 §5.5로 이동 (두 절이 뒤바뀐 상태) |
| 7 | 나머지 | 문구 정정 (G-3·G-4·G-5·R-1·R-4·NEW-4·NEW-8~12) |
| — | 미확인 1건 | 앱 기동 후 SUBSCRIBE 거부 동작 1회 확인 |

**전부 `MVP_API_SPEC.md` 수정 사안이다. 코드 변경이 필요한 결함은 확인되지 않았다.**
단, 별건으로 검토할 가치가 있는 코드 이슈 2개: ① `GlobalExceptionHandler`에 타입 불일치·JSON 파싱 실패 핸들러 부재(NEW-2) ② `RoutePlan` 목록 조회 N+1(NEW-5).

---

## 부록 — 에이전트 운용 관찰

1. **전체 패스 1개보다 섹션 집중 패스가 훨씬 깊다.** 전체 패스는 신규 1건, 섹션 패스 3개는 합계 11건을 찾았다. 문서 검증은 앞으로도 섹션 분할 병렬이 기본이다.
2. **전체 패스는 블라인드가 아니었다** — `drift-mvp-api-spec`이 `backend/report/`의 오전 보고서를 먼저 찾아 읽었다. 따라서 그 "10건 재현"은 독립 재현이 아니다. 섹션 패스 3개는 신규 발견이 많아 독립 수행으로 판단된다. **향후 교차검증 목적이면 프롬프트에 기존 보고서 열람 금지를 명시해야 한다.**
3. **4개 모두 지정된 5열 표 형식을 따르지 않았다.** 자유 서술이 가독성은 좋았으나 병합 자동화에는 부적합하다. 에이전트 정의의 보고 형식 강제를 손보거나, 형식 요구를 현실화할 필요가 있다.
4. `미확인` 규칙은 의도대로 동작했다 — 소스만으로 판정 불가한 항목을 드리프트로 올리지 않고 분리했다.
