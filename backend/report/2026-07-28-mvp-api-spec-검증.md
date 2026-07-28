# MVP_API_SPEC.md ↔ 실제 코드 대조 검증 보고서

- **일자**: 2026-07-28
- **검증 대상**: `backend/docs/MVP_API_SPEC.md` (535줄, 자칭 "코드 직접 대조 2026-07-22")
- **기획 기준**: `backend/docs/MVP_RELEASE_TRACKER.md` (MVP 범위 단일 소스)
- **방법**: 4개 에이전트 병렬 소스 대조 (앱 미기동, Docker 미사용)
- **코드 수정 없음** — 보고 전용

---

## 0. 총평

| 구분 | 결과 |
|---|---|
| MVP 엔드포인트 개수 | **17개 — 문서·트래커·코드 3자 일치** ✅ |
| 엔드포인트 구성 | 로그인 2 · 위치 2 · 승하차 4 · 배차 7 · 알림 2 — 트래커 §Swagger 정정분과 완전 일치 ✅ |
| 태그 오염 | `"00. MVP 사용 API"` 태그가 비-MVP 엔드포인트에 붙은 사례 **0건** ✅ |
| 발견된 불일치 | **10건** (치명 0 / 중 4 / 경 6) |

경로·역할·DTO 필드·enum·에러코드 같은 **계약(contract) 레벨은 사실상 완벽**하다. 발견된 불일치는 전부 *부수효과·순서·예외표* 서술에 몰려 있으며, 프론트가 문서만 보고 구현하면 오해할 소지가 있는 항목이다.

### MVP 태그 17개 전수 (코드 실측)

| 컨트롤러 | 개수 | 엔드포인트 |
|---|---|---|
| AuthController | 2 | `POST /api/auth/login`, `POST /api/auth/refresh` |
| LocationController | 2 | `POST /api/locations/bus`, `GET /api/locations/buses` |
| RideEventController | 4 | `POST /api/ride-events`, `GET /me`, `GET /children`, `GET /bus/{busId}` |
| RoutingController | 7 | `POST /auto-assign`, `POST /auto-assign/confirm`, `PATCH /{id}/approve`, `PATCH /{id}/publish`, `GET /{id}`, `GET /api/route-plans`, `GET /driver/{busId}` |
| NotificationController | 2 | `GET /api/notifications`, `GET /api/notifications/children` |

미태깅 확인(의도대로 제외됨): `POST /api/auth/signup`, `POST /api/locations/report`, `GET /api/locations`, `GET /api/locations/bus/{busId}`, `POST /api/ride-events/{id}/correction`, `GET /api/ride-events`, `POST /api/route-plans/generate`.

---

## 1. §2 Auth · §3 Location — 불일치 0건 ✅

- 로그인/재발급 경로·공개설정(`SecurityConfig:60`)·검증 애너테이션·`{accessToken, refreshToken}` 응답 일치.
- **"refreshToken도 회전된다"** 주장 사실 — `AuthQueryService:69-74`에서 access·refresh 둘 다 재발급.
- JWT 유효기간 900초 / 1209600초 — `application.yml:80-81` 일치.
- **"위치 없는 버스는 배열에서 빠진다"** 주장 사실 — `BusLocationQueryService:35-38`의 `flatMap(Optional)`.
- **"`POST /api/locations/bus`는 WS push 없음"** 주장 사실 — 저장 경로에 `SimpMessagingTemplate` 호출 자체가 없음.
- 401/403/404 메시지 문자열 전부 문자 단위 일치.

> 참고(오류 아님): §3.2로 기사가 직접 보고한 좌표는 `LocationOrigin.GPS`로 저장(`BusLocationCommandService:40`)되므로, 그 직후 §3.1 조회 시 `origin`이 예시의 `"MOCK"`이 아니라 `"GPS"`로 나온다.

---

## 2. §4 RideEvent — 불일치 4건

### R-1 (중) `stopId` 생략 시 fallback이 항상 성립하지 않음
- **문서**: "생략 시 학생 기본 승차 정류장"으로 채워짐
- **실제**: 학생에게 `boardingStop`이 없으면 `stopId`가 **null로 저장·응답**
- `rideevent/command/RideEventCommandService.java:63`

### R-2 (중) 알림 발행이 무조건적이 아님 (dedup으로 skip)
- **문서**: "기록 성공 시 type에 따라 알림이 발행되고 WebSocket으로 실시간 push된다"
- **실제**: `dedupKey`(알림타입+studentId+날짜+`stop{id}`|`bus{id}`) 중복 시 조용히 skip — 같은 학생·같은 정류장·같은 날의 **2번째 BOARD는 200 + RideEvent 저장은 되지만 알림/push 없음**
- `notification/command/impl/NotificationCommandServiceImpl.java:40`, 키 조립 `notification/infrastructure/impl/DomainEventNotificationConsumer.java:38`

### R-3 (중) 알림 경로가 동기가 아니라 Kafka 비동기
- **문서**: 기록 성공과 알림 발행이 동기적인 것처럼 서술
- **실제**: `publishEvent` → `@TransactionalEventListener(AFTER_COMMIT)` → Kafka → `@KafkaListener`. **Kafka 미기동이면 POST는 200이어도 알림이 오지 않는다**
- `global/event/TransactionalDomainEventRelay.java:24`, 발행부 `rideevent/command/RideEventCommandService.java:78`

### R-4 (경) §4.1 예외표 나열 순서 ≠ 실제 검사 순서
- **문서**: 400 → 403 → 404 순 나열 (평가 순서로 오해 가능)
- **실제**: 버스 404 → 담당기사 403 → 학생 404 → 테넌트 400. **담당 기사가 아니면 studentId가 틀려도 403이 먼저** 반환
- `rideevent/command/RideEventCommandService.java:52-61`

> ✅ 일치: 엔드포인트 4개 경로·역할, 필수/선택 필드(`busId`·`studentId`·`type`만 `@NotNull`), 응답 13개 필드 순서, `source=MANUAL` 고정, 알림 매핑 3종, push destination 2종, 에러 메시지 5종, `date` 생략 시 오늘·시간 오름차순, `RideType`/`RideSource` enum.

---

## 3. §5 Routing (F4) — 불일치 5건

### G-1 (중) §5.1 예외표의 `400 tenantId가 필요합니다`는 이 엔드포인트에서 발생 불가
- **문서 L328**: `400 INVALID_INPUT | tenantId가 필요합니다 | PLATFORM_ADMIN이 tenantId 생략`
- **실제**: `AutoAssignRequest.tenantId`가 `@NotNull`이라 `@Valid`에서 먼저 차단 → 실제 메시지는 `"tenantId: <기본메시지>"` 포맷. `TenantGuard` 문자열엔 도달하지 않음
- `routing/dto/AutoAssignRequest.java:11`, `global/error/GlobalExceptionHandler.java:38-45`, `global/tenant/TenantGuard.java:25`

### G-2 (경) §5.5 목록에는 그 400이 **누락**돼 있음 (G-1 행이 실제로 필요한 자리)
- **문서 L380**: 목록 예외로 `403 FORBIDDEN`만 기재
- **실제**: `list()`의 `tenantId`는 `@RequestParam(required=false)`라 `TenantGuard`를 그대로 타므로 PLATFORM_ADMIN 생략 시 400 발생
- `routing/controller/RoutingController.java:104`, `routing/query/RoutingQueryService.java:45`

### G-3 (경) §5.6 정렬 방향 서술이 반대
- **문서 L386**: `(등원/하원 방향순 정렬)` — PICKUP 우선으로 읽힘
- **실제**: `...OrderByDirectionAsc` + `@Enumerated(STRING)` → varchar ASC이므로 **DROPOFF(하원)이 먼저**, PICKUP(등원)이 나중
- `routing/repository/spec/RoutePlanRepository.java:22`, `routing/entity/RoutePlan.java:49-51`

### G-4 (중) §5.1 — 배정 대상 0명 / 빈 버스 케이스 미기재
- **문서**: `plans[]`가 항상 채워지는 것처럼 서술
- **실제**: 전원이 좌표 미보유로 제외되면 에러가 아니라 **200 + `{plans: [], excludedStudentNames:[...]}`**. 또 배정 0명인 버스는 계획을 아예 만들지 않아 `plans` 길이 < 버스 대수 가능 (**프론트가 `plans[0]`을 전제하면 깨짐**)
- `routing/command/RoutingCommandService.java:161-163`, `:177-179`

### G-5 (경) §5.2 — confirm이 RECOMMENDED 전용이 아님
- **문서 L338**: "검토를 마친 RECOMMENDED 계획들을 확정한다"
- **실제**: `RoutePlan.approve()`가 DRAFT도 허용 → `/generate`로 만든 DRAFT도 confirm 통과 (409 조건 서술 자체는 정확)
- `routing/entity/RoutePlan.java:100`

> ✅ 일치: 7개 경로·메서드·`@PreAuthorize`, `RoutePlanResponse` 12필드 + `StopEntry(seq,studentId,lat,lng,etaSeconds)` 순서까지, `RoutePlanStatus`/`RouteDirection`, `ConfirmAutoAssignRequest` `@Schema(example="[1, 2]")`(트래커 정정 반영 확인), auto-assign 시 `assignedBus` 미변경 + confirm에서만 커밋, auto-assign 에러 3종과 검사 순서, confirm의 커밋→approve→publish 단일 처리 + `ROUTE_PUBLISHED` 발행, 409 두 메시지, publish 알림 대상(담당기사+학부모), 기사 조회의 PUBLISHED 한정·403·404.

---

## 4. §6 Notification · §7 WebSocket · §8 주기표 · §1 공통규약 — 불일치 1건

### N-1 (중) §8 비고 "버스는 Mock 소스가 없다"는 **사실과 반대**
- **문서 L503**: "학생 위치만 자동 생성 — 버스 위치(F1)는 **Mock 소스가 없어** 기사가 §3.2로 직접 보고해야 값이 생긴다"
- **실제**: 버스 전용 Mock 소스가 존재하고 **기본 활성**. `MockBusLocationSource implements LocationSource`(`location/source/MockBusLocationSource.java:20`), `@Value("${app.location.bus-mock.enabled:true}")`(:32), `application.yml:60-61`에도 `bus-mock.enabled: true`. 학생 Mock과 **동일한** `LocationSimulationScheduler`(`:28-41`, 3000ms)에 실려 매 틱마다 `ingest(..., LocationOrigin.MOCK)`으로 버스 좌표 자동 생성(`MockBusLocationSource.java:52-58`)
- **문서 내부 모순**: 같은 문서 §3.2(:191)는 "현재 MVP는 Mock 시뮬레이터가 대신 채움", §3.1 예시 응답(:171)도 `"origin": "MOCK"`. §8 비고만 반대로 적혀 있음
- **영향**: "3,000ms 폴링 권장" 결론 자체는 유효(버스 Mock 갱신주기가 정확히 3초). 다만 프론트가 "기사가 보고하기 전엔 버스 위치가 안 나온다"고 오해할 수 있음

> ✅ 일치: 알림 2개 엔드포인트 역할·경로, `NotificationResponse` 6필드, 최신순 정렬, 자녀 없을 때 빈 배열 / `/ws/location` + SockJS 없음, CONNECT 프레임 1회 인증, heartbeat 10초 양방향, 구독 destination 4종 실재, `/topic/tenant/{id}/**` SUBSCRIBE 추가 인가, `/app/location` 매핑, 끊김 4단계(disconnect→10초 스케줄러→30초 유예→`CONNECTION_LOST`, 재접속 시 즉시 해제) / **§8 주기 8개 값 전부 현재 디스크 `application.yml`과 일치**(미커밋 수정분은 이 8개에 영향 없음) / `ErrorCode` 8개 순서·status·메시지 완전 일치, 에러 바디에 `errorCode` 필드 없음 확정(`ApiResponse` 3필드), `@Valid` 실패는 `findFirst()`로 첫 오류 1개만, `NotificationType` 10개 순서까지, `LocationOrigin`/`Role`/`RideType`/`RideSource`/`RoutePlanStatus`/`RouteDirection` 전부 일치.

### 참고 (문서 수정 불필요, 동작 메모)
1. §7.3 "NotificationType 전체 발생 시 push"는 정확히는 **알림의 `studentId`로 대상이 해석될 때만** push된다 — `PushTargetResolver.resolve(studentId)`가 빈 Optional이면 개인 큐뿐 아니라 **테넌트 토픽 broadcast까지 통째로 skip**(`WebSocketNotificationSender.java:31,44`). 현재는 `ROUTE_RECOMMENDED`/`ROUTE_PUBLISHED`도 대표 학생 id를 싣는 구조라 정상 동작.
2. `checkOverdueDisconnections`는 알림 후 레지스트리를 지우지 않아 10초마다 재진입하지만, `dedupKey`가 최초 끊김 시각 고정 + `existsByDedupKey` 멱등 처리라 **중복 발송 없음** — 문서 서술과 실제 동작 일치.

---

## 5. 권고 조치 (우선순위)

| 순위 | 항목 | 조치 |
|---|---|---|
| 1 | N-1 | §8 비고 문장 삭제/정정 — 버스도 Mock 자동 생성(3초)임을 명시. 문서 내부 모순 해소 |
| 2 | G-4 | §5.1에 `plans: []` 및 `plans.length < 버스 대수` 케이스 명시 (프론트 크래시 예방) |
| 3 | R-2, R-3 | §4에 "dedup 시 알림 skip" + "알림은 Kafka 비동기(커밋 후)" 주석 추가 |
| 4 | G-1, G-2 | 400 행을 §5.1에서 삭제하고 §5.5로 이동 |
| 5 | R-1, R-4, G-3, G-5 | 문구 정정 (fallback 조건 / 검사 순서 / 정렬 방향 / confirm이 DRAFT도 허용) |

**모두 문서(`MVP_API_SPEC.md`) 수정 사안이며, 코드 변경이 필요한 결함은 없다.**
