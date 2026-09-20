# Phase 13 목표 표 — 모니터링 · 관제

**작성 2026-09-03 · 착수 전 고정.** 분기점은 착수 시점의 `feat/baraeda-rebuild` HEAD **`b7b155f`**(Phase 12 완료 + 워커 힙 2048m).

⚠ **이 표는 파생본이다.** 정본은 `docs/IMPLEMENTATION_PLAN.md` Phase 13 절과 `docs/API_SPEC.md §5.3`·`§5.4`·`§5.18`·`§6.8`·`§6.9`·`§7.1`. **어긋나면 정본이 이긴다.** 아래 수치는 조율자가 정본·코드에서 직접 센 값이니 착수 시 다시 세어 대조하고 어긋나면 보고하라.

⚠ **`docs/` 는 2026-09-02 부터 git 추적 대상 밖이다**(`.gitignore` `**/docs/`). 워크트리에 따라오지 않으므로 **정본은 메인 저장소 절대 경로 `/Users/mskim/Desktop/PJ/School-Bus/docs/` 로 `cat` 하라.** `find` 로 찾지 마라.

---

## 0. ⚠ 착수 전 재계수 — 조율자가 이미 확인했다. **없는 것을 새로 만들지 마라**

Phase 10·11·12 에서 낡은 "없음" 이 이미 있는 것을 새로 만들 뻔한 전례가 세 번 있다(Ruling 206·213·217). 그래서 먼저 센다.

### 🔴 이미 **완료된** 것 — 다시 만들지 마라

| 대상 | 상태 |
|---|---|
| **`§5.4 GET /staff/runs/{runId}/roster`**(A-04 · RST-03) | ✅ **Phase 9 산출물.** `boarding/controller/StaffRosterController` + `RosterQueryService.staffRoster` + `@CanReadStaffRoster`. `guardian_phone` 원문 · `absent` 존치 · `404 RUN_NOT_FOUND` 전부 구현. 정본 Phase 13 절이 참조로 적었을 뿐이고 **이 표에서는 검증만 한다**(목표 2) |
| **WebSocket 관제 채널 구독 인가** | ✅ **Phase 10 산출물.** `StompAuthChannelInterceptor` 에 `^/topic/academy/(\d+)/live$` · `^/topic/admin/live$` 패턴과 시험 2종(`StompChannelAuthorizationTest` · `StompAcademyScopeSubscriptionTest`). **관제 구독은 이미 열려 있다** |
| **관제 채널 방송 7종** | ✅ `global/websocket/` 리스너 7개 — `position` · `rider_changed` · `stop_arrived` · `run_started` · `run_ended` · `approval_requested` · `emergency_raised`. `rider_changed` 5초 이내 반영(MON-03)은 **이미 이 경로**다 |
| **`run_stop.eta` 값** | ✅ **확정 배치(Phase 7)가 채운다** — `RouteEtaSchedule`(노선 계산 ④단계)이 정차지별 도착 예정 시각을 내고 `RunStop.forStop(..., eta)` 가 받는다. **관제 ETA 의 계산 수단은 이미 있다.** 부재한 것은 그 값을 **읽어 응답에 싣는 층**뿐이다 |
| **신호 유실 2분 판정** | ✅ `StudentBusPositionQueryService.STALE_THRESHOLD = Duration.ofMinutes(2)`(Ruling 208). `§5.18` 의 `last_seen_at` 은 **같은 값**을 써야 한다 — `API_SPEC:644` 가 "갈라 두면 관제에는 경고가 떴는데 학부모 화면은 정상으로 보이는 구간이 생긴다" 고 명시 |
| **확인 응답(`ack_driver`·`ack_escort`) 근거** | ✅ `Assignment.ackedRouteVersionId`·`ackedAt`. 판정식은 `ERD:632` — *"미확인 = 이 값이 NULL 이거나 `current_version_id` 와 불일치"*. ~~`§5.19` 응답의 `ack{driver, escort}` 가 이미 같은 판정을 하고 있을 것이다 — 찾아서 재사용하라~~ → **정정 (Ruling 238, 2026-09-03): 조율자의 낡은 추정이었다. `§5.19` 는 미구현이고 `ackedRouteVersionId` 를 읽는 코드는 엔티티뿐이다. T1 이 `RunAckChangesCommandService` 쓰기 경로의 판정을 거꾸로 읽어 직접 구현한다.** `§5.19` 자체는 오픈 이슈 `X` |
| **변경분(`added`·`removed`)** | ✅ `RunRider.change`(`ChangeType`). `skipped` 는 `run_stop` 전용 |
| **미승차 에스컬레이션 진행** | ✅ Phase 11 `NoShowCase`(`started_at`·`expires_at`·`resolved_at`·`decision`·`escalated_at`) + `NoShowCaseRepository` |
| **관리자 전 학원 조회 본보기** | ✅ `admin/query/AdminEmergencyQueryService` + `EmergencyAlertRepository` 의 `@AcademyScopeExempt(reason = "§6.x 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5)…")`. **전 학원 조회 메서드는 이 애너테이션이 필수**다 — 없으면 `AcademyScopeRepositoryConventionTest` 가 막는다 |
| **인가 애너테이션 `@CanMonitorAll`** | ✅ `MONITOR_ALL`(메인 관리자만). 첫 사용처 `AdminEmergencyController`. **§6.8·§6.9 에 그대로 붙인다** |
| **권한 상수** | ✅ `Permissions.MONITOR_ACADEMY`(`RolePermissions:96`, 관계자) · `MONITOR_ALL`(`:111`, 메인 관리자) |
| **최신 좌표 캐시** | ✅ Redis `RunPositionRedisValue`(`lat`·`lng`·`recordedAt`·`receivedAt`·`currentStopName` **5필드, 공유 계약 — 임의로 늘리지 마라**) + 읽기 본보기 `location/proximity/RunPositionReader` |
| **에러 코드 3종** | ✅ `FORBIDDEN` · `ACADEMY_NOT_FOUND` · `RUN_NOT_FOUND` 전부 `ErrorCode` 에 실재. **이 Phase 는 `ErrorCode` 를 고치지 않는다** |
| **응답에 필요한 엔티티 필드** | ✅ `Student.photoUrl`·`studentPhone`·`className`·`note` · `Manager.phone` · `Run.startedAt`·`estDurationMin`·`originName`·`destinationName` · `RunStop.arrivedAt`·`eta`·`change` |

```bash
# 착수 시 직접 대조하라
ls /Users/mskim/Desktop/PJ/School-Bus/backend/src/main/java/src/backend/monitoring/      # 기대: .gitkeep 하나 — 모듈이 비어 있다
grep -c 'eta' backend/src/main/java/src/backend/routing/pipeline/RouteEtaSchedule.java   # 0 이 아니어야 한다
grep -n 'STALE_THRESHOLD' backend/src/main/java/src/backend/student/query/StudentBusPositionQueryService.java
grep -n 'ADMIN_LIVE_PATTERN\|ACADEMY_LIVE_PATTERN' backend/src/main/java/src/backend/global/security/StompAuthChannelInterceptor.java
```

### 부재한 것 — 이번에 만든다

- **`monitoring` 모듈 전체** — `.gitkeep` 뿐이다(`ARCHITECTURE §3.1` 이 "관계자 대시보드 · 메인 관리자 관제" 로 지정)
- **핸들러 4개** — `§5.3` · `§5.18` · `§6.8` · `§6.9`(아래 §2)
- 🔴 **리포지토리는 만들지 않는다 (Ruling 238)** — 조회 메서드는 **기존** `AssignmentRepository`·`ManagerRepository`·`NoShowCaseRepository`·`RunRiderRepository`·`RunRepository` 에 더한다. 이 저장소는 47개 전부 엔티티와 1:1 이고 두 번째 리포지토리 선례가 없다(Ruling 222). "쓰기 소유" 는 데이터 소유이지 파일 접촉 금지가 아니다
- **`@CanMonitorAcademy` 애너테이션** — `MONITOR_ACADEMY` 권한 상수는 있는데 **메타 애너테이션이 없다**(`authz/` 34개 중 부재). `@CanMonitorAll` 을 본떠 만든다 — **T1 소유**
- 🔴 **관제 채널 `position.eta` 값** — `PositionBroadcastListener.ControlPayload` 가 상수 `null` 을 싣는다(Phase 10 이월 ④, 자바독이 *"Phase 13 이 이 필드를 채우면 된다"* 로 명시). **T2 소유**

⚠ **신설 마이그레이션은 부재한다.** 이 Phase 는 **읽기 전용 프로젝션**이라 테이블·컬럼을 만들지 않는다. 다음 번호는 `V7` 이지만 쓸 일이 없어야 한다 — 필요하다고 판단되면 **만들기 전에 보고하라.**

⚠ **정정 (Ruling 236, 2026-09-03)** — T3 이 보고했고 허가했다. **`V7` 하나**(`emergency_alert.position_recorded_at`, nullable)를 T3 이 만든다. T1·T2 는 여전히 마이그레이션을 만들지 않는다.

---

## 1. 완료 조건 — **14항.** 정본 7항 + 재계수 신설 6항 + 전체 실측 1항

| # | 무엇이 통과하면 끝인가 | 근거 | 좌석 |
|:-:|---|---|:-:|
| **1** | `GET /staff/dashboard` — `metrics` 5종(`moving_buses`·`boarded`·`no_show`·`absent`·`unassigned_managers`) + `runs[]` 회차 표 필드 **전부**가 정본과 항목 단위로 일치. `date` 쿼리 부재 시 오늘 | `§5.3` · `MON-01·02` · `A-03` | T1 |
| **2** | 대시보드 `runs[].run_id` 로 **`§5.4` 명단이 열린다** — 기존 산출물 검증. 필드(`student_id`·`name`·`class_name`·`stop_name`·`guardian_phone` 원문·`change`·`status`·`note`)를 정본과 대조 | `§5.4` · `A-04` · `RST-03` | T1 |
| **3** | **실제 승하차 처리 경로**(`PATCH /runs/{runId}/riders/{riderId}`)를 태운 뒤 대시보드의 `boarded_count`·`metrics.boarded` 가 바뀐다. **실제 미승차 에스컬레이션**을 만든 뒤 `no_show_cases[]`(`student_name`·`stop_name`·`expires_at`)에 실리고, 해소되면 빠진다 | `§5.3` · `MON-03·04` · `NFR-02` | T1 |
| **4** | `added_count`·`removed_count` 가 `RunRider.change` 와 일치. `ack_driver`·`ack_escort` 가 **`ERD:632` 판정식**(확인 버전 = 현재 버전)을 따르고, **재배포 후 미확인으로 되돌아간다** | `§5.3` · `MON-05` · `RUN-07` | T1 |
| **5** | 학원 격리 — 관계자 토큰의 대시보드·live 응답에 **타 학원 회차가 부재**하고, 관계자가 타 학원 회차 명단을 열면 `403 ACADEMY_SCOPE_VIOLATION`, 관계자가 `§6.8`·`§6.9` 를 호출하면 `403 FORBIDDEN` | 정본 완료 조건 4 · `§1.5` · `§6.2` | T1 · T2 |
| **6** | `GET /staff/runs/live` — **`status='moving'` 회차만** 반환. `run_id`·`bus_no`·`direction`·`status`·`position{lat,lng,recorded_at}`·`current_stop`·`next_stop`·`progress{done,total}`·`delay_minutes`·`driver_name`·`escort_name` 전부. 좌표는 **Redis 최신값** | `§5.18` · `MON-07` · `A-14` — **신설**(범위 열이 명시하는데 완료 조건에 부재) | T1 |
| **7** | 위치 미수신·유실 회차는 **`position=null` + `last_seen_at`**. 유실 기준은 `StudentBusPositionQueryService.STALE_THRESHOLD` 와 **같은 값**(2분) | `§5.18` · `API_SPEC:644` · Ruling 208 — **신설** | T1 |
| **8** | `GET /admin/academies/{id}/runs/live` — 필드 전부(`run_status`·`position{lat,lng,received_at}`·`depart_time`·`est_depart_time`·`stops[]{stop_id,seq,name,lat,lng,change,arrived_at,eta}`·`destination_eta`·`driver{name,phone}`·`escort{name,phone}` **원문**). **`stops[].eta` 와 `destination_eta` 에 값이 실리고**, 도착 처리된 정차는 `eta=null` | `§6.8` · `O-05` · 정본 완료 조건 5 | T2 |
| **9** | `§6.8` 은 없는 학원에 `404 ACADEMY_NOT_FOUND`, 메인 관리자는 **어느 학원이든** 조회 가능(`403 ACADEMY_SCOPE_VIOLATION` 미발생) | `§6.8` · `§1.5` — **신설** | T2 |
| **10** | `GET /admin/runs/{runId}/roster` — `stops[]→students[]{student_id,name,photo_url,student_phone,guardian_phone,status}` **원문**. 관계자 호출은 `403 FORBIDDEN` | `§6.9` · `O-06` · `FEATURE_SPEC:959` · 정본 완료 조건 6 | T2 |
| **11** | `§6.9` 는 없는 회차에 `404 RUN_NOT_FOUND` | `§6.9` — **신설** | T2 |
| **12** | 학부모·학생 응답(`§3.10` 노선 · `§3.11` 위치 · WS 학생 채널 `position`)에 **`eta` 키 자체가 부재**. **관제 채널(academy·admin) `position.eta` 는 값이 채워진다**(지금은 상수 `null`) | 정본 완료 조건 7 · `C-08` · `§7.1` · **Phase 10 이월 ④ — 신설** | T2 |
| **13** | `§5.16`·`§6.11` 비상 알림 응답 형태를 **항목 단위로 대조**해 정본과 일치 — 어긋남 **7건**(아래 §7) | **Phase 11 이월 ①** — Phase 12 가 *"다음 Phase 목표 표에 응답 형태를 항목 단위로 대조 문면과 함께 싣는다"* 로 판정 | T3 |
| **14** | 전체 테스트 묶음 **단독 실행** 실패 0 (동시성 시험의 부하 의존 실패는 별도 분류) | 횡단 규칙 22 | 조율자 |

⚠ **정본 대조 결과 — 원장 완료 조건 7항이 아래 6개를 문면으로 담지 않았다.** 범위(`MON-01~07` · `A-14` · `O-05·06`)에는 있는데 완료 조건에 없었다. **Phase 10 Ruling 211 · Phase 11 Ruling 213 · Phase 12 Ruling 216 과 같은 형태**라 신설한다 — 목표 **6**(`§5.18` 자체) · **7**(유실 표기) · **9**·**11**(부재 상태 코드) · **12 뒷항**(관제 `eta` 채움) · **13**(이월).

⚠ **한 줄에 조건이 여럿인 항목을 특히 보라** — 목표 **3**(승하차 반영 + 에스컬레이션 표시 + 해소 시 제외) · **4**(변경분 + 확인 응답 + 재배포 후 되돌림) · **5**(부재 + `403` 두 종류) · **8**(필드 전부 + `eta` 값 + 도착 시 `null`) · **10**(원문 + `403`) · **12**(부재 + 채움). **뒤쪽을 잃는 사고가 Phase 9·11 에서 반복됐다.**

---

## 2. 신설 핸들러 — **4개 (94 → 98).** 정본에서 직접 셈

| # | 엔드포인트 | 절 | 권한 | 좌석 |
|:-:|---|---|---|:-:|
| 1 | `GET /staff/dashboard` | `§5.3` | 학원 관계자 — **`@CanMonitorAcademy`(신설)** | T1 |
| 2 | `GET /staff/runs/live` | `§5.18` | 학원 관계자 — `@CanMonitorAcademy` | T1 |
| 3 | `GET /admin/academies/{id}/runs/live` | `§6.8` | 메인 관리자 — `@CanMonitorAll`(기존) | T2 |
| 4 | `GET /admin/runs/{runId}/roster` | `§6.9` | 메인 관리자 — `@CanMonitorAll` | T2 |

⚠ **4개를 만들면 계정 상태 게이트 거부측 목록에도 4줄이 늘어야 한다.** `AccountStatusGateEndpoints.DENIED_WHEN_REJECTED`(현재 **83**) → **87** · `AuthFlowIntegrationTest` 의 `hasSize(94)` → **98** · 게이트 파일 문자열 총계(현재 **84**) → **88**.

🔴 **이 세 숫자는 서로 다른 지표다. 하나로 뭉치면 오판한다** — `hasSize` 는 **앱에서 훑은 전체 핸들러**, 문자열 총계는 `DENIED_WHEN_REJECTED` + `DENIED_WHEN_PENDING` 이 덧붙이는 1개, 거부측 목록은 그중 하나다. **Phase 9·10·11·12 에서 연속 누락된 자리이고 개수 단언이 유일한 탐지 수단이다.**
⚠ **문자열을 `grep -c` 로 세지 마라 — `-c` 는 "일치한 줄 수" 다.** `grep -oE '"(GET|POST|PUT|PATCH|DELETE) /[^"]*"' <파일> | wc -l` 을 쓴다.
⚠ **좌석 단독값** — T1 `hasSize(96)` · T2 `hasSize(96)` · T3 **미변경**(`94`). 병합에서 조율자가 98 로 합친다.

🔴 **`ControllerAuthorizationConventionTest` 가 인가 애너테이션 없는 핸들러를 막는다.** `§5.3`·`§5.18` 에 `@CanMonitorAcademy` 를 붙이지 않으면 그 시험이 실패한다 — **면제 목록에 넣어 통과시키지 마라**(Phase 11 이월 ③이 지적한 바로 그 구멍이다).

---

## 3. ⚠ 이 Phase 에서 가장 의심스러운 지점

### 3.1 🔴 ETA 는 **계획값을 읽는 것**이지 재계산이 아니다 — 목표 8·12 의 본체 (Ruling 232, **2026-09-03 사용자 확정**)

`§6.8` 예시가 `stops[].eta`·`destination_eta`·`est_depart_time` 에 값을 싣는데 **정본 어디에도 "무엇으로 계산하는가" 가 없다.** 조율자 잠정 판정 —

| 필드 | 값 | 근거 |
|---|---|---|
| `stops[].eta` | **`run_stop.eta` 저장값 그대로.** `arrived_at` 이 채워진 정차는 `null` | `PRD:96`·`:158` — *"미경유 발생 시 재최적화·ETA 재계산 부재"*, *"주행 중 노선을 다시 계산해 재배포하면 주행 위험"*. `ARCHITECTURE §8.3` 은 계산 소비자를 **배치·온디맨드 둘**로만 둔다 — 위치 수신마다 지도 API 를 부르는 세 번째 소비자는 설계에 없다 |
| `destination_eta` | **`depart_time + run.est_duration_min`**(계획) | `RouteEtaSchedule.estDurationMin` 이 출발부터 도착지까지의 총 소요다 |
| `est_depart_time` | **`run.started_at`** — `moving` 회차만 반환하므로 항상 존재 | `depart_time`(계획) 과 나란히 두면 출발 지연이 그대로 드러난다. ⚠ **이름이 "예정" 이라 어긋나 보인다** — 사용자 확정 대상 |
| `§7.1 position.eta`(관제 채널) | **다음 미도착 정차의 `run_stop.eta`** | 페이로드가 단일 값이라 "다음 정차" 가 유일하게 자연스럽다 |
| `§5.18 delay_minutes` | **최근 도착 처리된 정차의 `arrived_at − eta`** 를 분으로, 음수는 `0`. 도착 처리 전이면 `started_at − depart_time` | 실측 시각 둘의 차이라 **재현 가능**하고 지도 API 호출이 없다. 대안(다음 정차 `eta − now`)은 시계마다 값이 달라져 시험이 시각 고정에 묶인다 |

- 🔴 **위치 수신마다 지도 API 를 호출하는 형태로 만들지 마라.** 버스 수 × 5~10초 주기다. 그렇게 해야 한다고 판단되면 **보고하고 멈춘다**
- ⚠ **`run_stop.eta` 가 `null` 인 정차가 있을 수 있다**(폴백 계산 · 옛 데이터). `null` 을 그대로 내보내고 **그 사실을 자바독에** 적어라
- **시험은 `RouteEtaSchedule` 을 다시 돌리지 말고 `run_stop.eta` 를 직접 심어라** — 계산은 Phase 7 시험이 이미 지킨다

### 3.2 `§5.18` 과 `§6.8` 은 **같은 것을 두 번 만들기 쉽다** — 좌석 둘로 갈라 뒀다

둘 다 "moving 회차 + Redis 최신 좌표 + 정차 진행" 인데 응답 형태가 다르다(`§5.18` 은 요약 · `§6.8` 은 정차 목록과 ETA 까지). **T1 이 `§5.18` 을, T2 가 `§6.8` 을 만든다.**

- ⚠ **최신 좌표 읽기·유실 판정·현재/다음 정차 판정은 둘 다 필요하다.** 각자 만들면 **판정이 두 벌**이 된다(`PositionBroadcastListener` 와 `RunPositionRedisListener` 가 이미 같은 판정을 두 번 계산하고 있고 그 이유가 자바독에 적혀 있다). ⇒ **T1 이 `monitoring/query/` 아래에 공용 판정 클래스를 먼저 만들고 이름을 보고하면 조율자가 T2 에 전파한다.** T2 는 그것이 오기 전까지 **자기 워크트리에 임시로 같은 판정을 두되 클래스 이름을 `Admin` 접두사로** 갈라 두어 병합 시 충돌이 아니라 **교체**가 되게 하라
- 🔴 **`position` 시각 필드 이름이 두 절에서 다르다** — `§5.18` 은 `recorded_at`(단말 시각), `§6.8` 은 `received_at`(서버 수신). **정본 내부 어긋남이 아니다** — Redis 값에 둘 다 있다. **각 절 문면대로** 내보내라(Ruling 235)

### 3.3 `metrics` 5종 중 **정의가 정본에 없는 것 3개** — 판정하고 근거를 자바독에 (Ruling 233)

| 필드 | 정본 문면 | 조율자 권장 | 판정 근거로 적을 것 |
|---|---|---|---|
| `metrics.boarded` | "승차 완료 인원" | **현재 `status='boarded'` 인 탑승자 수** — 회차 표 `boarded_count` 의 합과 같아야 한다 | 하차한 인원까지 세면 카드와 표가 합산 관계를 잃는다 |
| `metrics.unassigned_managers` | "배치 대기 매니저 수" | **활성 매니저 중 그날 회차 배치(`assignment`)가 하나도 없는 수** | 정본에 다른 정의가 없다. 대안("배치가 비어 있는 회차 수")은 "매니저 수" 라는 단위와 어긋난다 |
| `progress.total`(`§5.18`) | 없음 | **`skipped` 정차를 포함한 정차 항목 수**, `done` 은 `arrived_at` 이 채워진 수 | `skipped` 는 "경유하되 미정차" 라 순번을 유지한다(`PRD:158` 취소선·순번 유지) |

**추측해서 코드에 박지 말고 판정 근거를 보고하라.** 조율자 권장과 다르게 정하면 그 이유를 보고서 1항에 적는다.

### 3.4 목표 3·4 는 **"실제 경로를 태운다"** 가 본체다 — Phase 10 Critical 의 형태

세 좌석이 각자 손으로 만든 데이터를 심어 자기 쪽만 검사하면 **전 시험이 초록인데 운영이 파손**된다(Phase 10). 대시보드는 다른 모듈이 쓴 상태를 **읽기만** 하는 자리라 특히 그렇다.

- 목표 3 — **`PATCH /runs/{runId}/riders/{riderId}` 를 실제로 호출**해 `boarded` 로 바꾼 뒤 대시보드를 읽어라. `run_rider` 행을 손으로 `UPDATE` 하면 **집계 쿼리가 실제 상태값과 맞는지** 아무것도 증명하지 않는다
- 목표 3 뒷항 — 에스컬레이션은 **`NoShowCase` 를 만드는 실제 경로**(Phase 11 T1 의 미승차 처리)를 태워라. 그 경로가 시험에서 무거우면 **`NoShowCase` 행을 직접 심되 그 한계를 보고서 2항에** 적어라
- 목표 4 — `ack` 는 **재배포 후 되돌아가는 것**까지 검사하라. `acked_route_version_id` 가 현재 버전과 같은 상태에서 새 버전을 배포하면 `ack_driver=false` 가 되어야 한다. **이것이 `ERD:634` 가 "버전과 함께 기록하지 않으면 재배포 후에도 확인 완료로 남는 오판" 이라고 적은 그 사고다**

### 3.5 목표 12 는 **"무엇이 없다" + "무엇이 있다"** 두 방향이다

*"학부모·학생 응답에 `eta` 부재"* 는 **관제 응답도 통째로 비어 있으면 통과**한다. 그래서 **같은 시험에서** 관제 채널의 `eta` 가 채워지는 것을 함께 검사한다.

| 심는 것 | 실패해야 하는 것 |
|---|---|
| `ParentStudentPayload` 에 `eta` 컴포넌트를 더한다 | 학부모 채널 **키 부재** 단언 |
| `ControlPayload.eta` 를 다시 상수 `null` 로 | 관제 채널 **값 존재** 단언 — **이 단언이 없으면 Phase 10 이월 ④가 영구히 닫히지 않는다** |

⚠ **`PositionBroadcastListenerTest` 가 이미 있다.** 기존 시험이 "키 부재" 를 검사하는지 열어 보고, 검사하면 **그 시험을 넓혀라**(새 파일을 만들지 마라).

### 3.6 사진·연락처 **원문**은 `§6.9` 만이 아니다 — 무엇이 어디까지 나가는지 표로

| 엔드포인트 | `photo_url` | `student_phone` | `guardian_phone` | 근거 |
|---|:-:|:-:|:-:|---|
| `§5.4` 관계자 명단(기존) | ✕ | ✕ | **원문** | `§5.4` — "관계자 웹은 마스킹 대상 밖" |
| `§6.9` 관리자 명단 | **원문** | **원문** | **원문** | `FEATURE_SPEC:959` — 메인 관리자의 L3 접근은 **관제 목적으로 한정** |
| `§6.8` 관리자 live | — | — | — | `driver.phone`·`escort.phone` 만 원문 |

- ⚠ **`§6.9` 의 L3 조회는 Phase 14 감사 로그(SYS-01) 대상**이다 — *"조회 시 감사 로그 기록"*(`FEATURE_SPEC:956`). **이 Phase 에서 감사 기제를 만들지 마라.** Phase 14 가 AOP·인터셉터로 소급 부착한다(`§8.1` 14 행). **핸들러 자바독에 "L3 조회 — SYS-01 감사 대상" 을 적어** Phase 14 가 찾을 수 있게 하라
- `guardian_phone` 은 학생 레코드에 없다 — **연결된 학부모 계정에서 온다.** `RosterQueryService` 가 `GuardianStudentRepository.findGuardianPhonesByAcademyId` 로 해석하는 방식을 **재사용**하라. ⚠ **그 메서드는 `academyId` 로 좁혀져 있다** — 관리자 경로는 회차의 학원 ID 를 먼저 구해 넘기면 된다(격리 면제 메서드를 새로 만들 필요가 없다)

---

## 4. 좌석 분할 — 3개 병렬. **기준은 파일·인가 축이다**(Ruling 234)

이 Phase 는 **읽기 전용 프로젝션**이라 Ruling 214 의 "테이블 소유권" 축이 성립하지 않는다 — 어느 좌석도 테이블에 쓰지 않는다. 대신 **같은 파일을 두 좌석이 고치는 것**을 막는 축으로 가른다.

| 좌석 | 담당 목표 | 소유(쓰기) 파일 | 신설 핸들러 | 게이트 |
|:-:|---|---|:-:|:-:|
| **T1** | 1·2·3·4·5(관계자 쪽)·6·7 — 관계자 대시보드 · live 스냅샷 | `monitoring/` 의 `Staff*` 접두 파일 · **`authz/CanMonitorAcademy.java`(신설)** · 공용 판정 클래스 | 2 | `hasSize(96)` |
| **T2** | 5(관리자 쪽)·8·9·10·11·12 — 메인 관리자 관제 · 관제 ETA | `monitoring/` 의 `Admin*` 접두 파일 · **`global/websocket/PositionBroadcastListener.java`** · `PositionBroadcastListenerTest` | 2 | `hasSize(96)` |
| **T3** | 13 — 비상 알림 응답 형태 정합 (Phase 11 이월 ①) | `exception/dto/`·`exception/query/` 의 `Emergency*` · `admin/AdminEmergency*` · 그 시험들 | **0** | **미변경**(`hasSize(94)`) |

⚠ **남은 좌석 간 결합 3건을 발주문에 명시한다.**

| 결합 | 처리 |
|---|---|
| **T1·T2 가 같은 `monitoring/` 패키지에 파일을 만든다** | 파일명 접두사(`Staff*` / `Admin*`)로 갈라 **같은 이름을 만들지 않는다.** 하위 패키지는 저장소 관례(`controller`·`dto`·`query`)를 따르고 좌석별 하위 패키지를 새로 파지 마라 |
| **최신 좌표·유실·현재/다음 정차 판정을 둘 다 쓴다** | §3.2 — T1 이 만들고 이름을 보고, 조율자가 T2 에 전파. T2 는 `Admin` 접두 임시본으로 시작 |
| **`AccountStatusGateEndpoints`·`AuthFlowIntegrationTest`** | T1·T2 **의도한 겹침.** 각자 자기 몫 2줄을 등재하고 `.as(...)` 에 자기 절을 더한다. 병합에서 조율자가 98·88 로 합친다 |

⚠ **`ErrorCode.java` 는 아무도 건드리지 않는다** — 필요한 코드 3종이 전부 있다. **`Permissions.java`·`RolePermissions.java` 도 건드리지 않는다** — 상수와 매핑이 이미 있다.
⚠ **T3 은 `exception` 모듈만 만진다.** `monitoring/` 에 파일을 만들지 마라. `AdminEmergencyController` 가 `admin/` 에 있는 것은 Phase 11 이 정한 위치라 **옮기지 마라.**

---

## 5. 좌석 공통 규칙 — `p13-common.md` 8항 + 아래

⚠ **"시험이 없다" 를 영문 `grep` 으로 판정하지 마라** — 이 저장소는 **시험 메서드·헬퍼 이름을 한글로 쓴다**(`알림_행수(...)` 류). 조율자가 실제로 있는 시험을 없다고 판정해 발주한 전례가 있다. **검사 대상 테이블·컬럼 문자열로 훑고 파일을 열어 확인하라.**

🔴 **시드의 "오늘" 행에 기대는 시험을 만들지 마라** — 이 Phase 는 **`serviceDate = 오늘`·`status = moving`** 인 회차가 시험마다 필요한데, 시드의 오늘 행은 DB 를 만든 날에 굳는다(`PROJECT_NOTES` 알려진 함정). **`Clock` 을 주입해 "오늘" 을 고정하고 그 날짜로 회차를 직접 만든다.** `Clock.fixed` 로 얼리면 신호 유실 2분 판정(목표 7)이 무너질 수 있으니 **`Clock.offset` 을 먼저 검토**하라(Phase 10 이 실제로 그렇게 갈랐다).

---

## 6. 실행 — 포트를 실측한 값으로 쓴다

```bash
cd <워크트리>/backend
./gradlew test --tests <클래스> -PtestDbUrl=jdbc:postgresql://localhost:15432/<배정받은_DB> --rerun
```

- ⚠ **포트 15432**(redis 16379 · kafka 29092). `application.yml:25` 기본값은 **`5432`** 이고 이 머신의 5432 는 **다른 프로젝트 postgres** 라 붙으면 `password authentication failed` 가 **전 클래스에서** 난다. 착수 시 `docker port school-bus-postgres-1` 로 **다시 실측**하라
- DB 생성은 조율자가 한다 — 역할은 **`schoolbus`**
- 🔴 **T1·T2 는 Redis 를 읽는다** — `testsupport/redis/RedisTestContainerBase`(클래스 전용 컨테이너)를 **상속**하라. 공유 `school-bus-redis-1`(16379)에 붙지 마라 — 이름공간이 없어 다른 좌석의 좌표와 섞인다. ⚠ **앱 기본값 `6379` 는 다른 프로젝트 Redis 다**(Phase 12 최종 판정 회차가 이것으로 한 번 버려졌다, Ruling 229)
- ⚠ **판정 근거는 콘솔이 아니라 `build/test-results/test/TEST-*.xml` 의 `tests=`·`failures=`·`errors=`.** `:test` 줄의 `UP-TO-DATE`·`FROM-CACHE` 는 통과가 아니라 **미실행**이다
- ⚠ **파이프·배경 실행의 종료 코드를 믿지 마라**
- **금지** — 인자 없는 `./gradlew test` · `clean` · `docker compose down` · `-v` · `DROP DATABASE`
- ⚠ **`SNAKE_CASE` 는 응답 키에 걸린다** — 시험은 **JSON 키 문자열**을 직접 검사한다. 자바 필드명만 보면 `elapsed_seconds_since_raised` ↔ `elapsed_since_raised` 형태의 어긋남을 못 잡는다(Phase 11 Critical)
- 참고 — `*ConcurrencyTest` 는 전체 실행에서 `TimeoutException` 이 나고 **단독 재실행에서 통과**한다(부하 의존, Phase 8 부터 이월. 코드 결함 아님)

---

## 7. 🔴 T3 전용 — `§5.16`·`§6.11` 비상 알림 응답 정합 (목표 13, Phase 11 이월 ①)

조율자가 Phase 11 에서 `EmergencyStaffItemResponse` 와 `§5.16` 표를 항목 단위로 마주 놓은 결과다(`report-p11-t2-fix1.md` 맨 끝). **착수 시 다시 마주 놓아 7건이 그대로인지 세라.**

| 정본 `§5.16` | 코드(2026-09-02 실측) | 어긋남 |
|---|---|---|
| `emergency_id` | `id` | 이름 |
| `raised_by` **object**(`name`·`role`·`phone`) | `raisedByName`·`raisedByRole`·`raisedByPhone` 평면 3개 | **형태**(객체 → 평면) |
| `position` **object**(`lat`·`lng`·`recorded_at`) | `lat`·`lng` 평면 2개 | **형태** + `recorded_at` 부재 |
| `direction` | — | **부재** |
| `contacts` **array** | — | **부재** |
| `raised_at` | `occurredAt` + `receivedAt` | 이름 · 2개로 갈림 |
| `acked_by` **object** | `ackedByName` | **형태** |

- ⚠ **`§6.11` 이 *"응답 — `§5.16` 항목 + 아래"* 라 관리자 콘솔(`AdminEmergencyItemResponse`)이 이 어긋남을 그대로 상속한다.** 두 DTO 를 함께 고쳐라. `§6.11` 고유 3개(`academy`·`staff_acked`·`elapsed_since_raised`)는 **Phase 11 수정 라운드에서 이미 맞췄다** — 건드리지 마라
- 🔴 **`raised_at` 이 `occurred_at`+`received_at` 둘로 갈린 것은 Phase 11 T2 의 의도된 설계였다**(통신 두절 발신의 두 시각). 정본에 맞춰 `raised_at` 하나로 합칠지, 정본 문면 바깥에 둘을 남길지는 **판정 대상**이다 — `§4.14`(발신 요청)에 두 시각이 있는지 정본에서 확인하고 근거를 보고하라. **정본이 하나만 적으면 응답도 하나다** — 나머지 하나를 지우는 것이 아니라 **정본 밖 필드를 남기는 근거**를 적어야 한다
- 🔴 **시험은 JSON 키 문자열을 직접 검사해야 한다** — 이 7건이 전 시험 초록 아래 남은 이유가 **그 API 를 호출해 키를 보는 시험이 0건**이어서다. `direction`·`contacts` 처럼 **없는 필드**는 키 존재 단언이 없으면 영원히 안 잡힌다
- ⚠ **기존 시험이 옛 필드명(`raisedByName` 류)을 기대하고 있을 것이다.** 그 시험을 **옳은 동작 쪽으로** 고쳐라 — 고쳤을 때 실패하는 시험이 있다면 그 단언이 결함을 굳히고 있던 것이다(`phase-goal-loop §5`)
- `contacts` 배열의 내용이 무엇인지 `§5.16` 을 직접 읽어 판정하고 근거를 적어라

### 심을 변형

| 심는 것 | 실패해야 하는 것 |
|---|---|
| `raised_by` 를 다시 평면 3개로 | 형태 단언 |
| `contacts` 배열을 빈 목록 상수로 | 내용 단언 — **없으면 그 자체가 결함 보고** |
| `direction` 을 `null` 상수로 | 값 단언 |
| `§6.11` 응답에서 `§5.16` 상속 필드 하나를 뺀다 | 관리자 콘솔 시험 — **`§5.16` 만 검사하고 `§6.11` 을 안 보면 통과한다** |

---

## 8. 이월 · 오픈 이슈 처리

### Phase 12 이월 6건 — **이 Phase 에 흡수하지 않는다**

| # | 항목 | 이번 Phase |
|:-:|---|---|
| ① | 동시성 `TimeoutException` (Phase 8 →) | 계속 이월. 최종 실측에서 실패 클래스가 `*ConcurrencyTest` 뿐인지만 본다 |
| ② | 문구 조립기 16개가 자녀 이름을 안 넣는다 (Ruling 225, 이벤트 계약 변경 필요) | **범위 밖.** 알림 축이고 관제가 아니다 |
| ③ | 미승차 되돌리기 정정 알림 부재 (Ruling 223) | 범위 밖 |
| ④ | "중요 통지" 열거값 분류가 정본에 부재 (Ruling 226·227) | 사용자 판정 대기 — 문서 항목 |
| ⑤ | `FEATURE_SPEC` NTF-09 ↔ `USER_FLOWS:623` 팝업 대상 상충 | 사용자 판정 대기 — 문서 항목 |
| ⑥ | ID 타입 문서·코드 어긋남 (사양 `string` vs 코드 `Long`) | ⚠ **이 Phase 의 신설 응답도 같은 편차를 만든다.** 좌석은 **기존 관례(`Long`)를 따르고** 문서 정합은 별도 항목으로 둔다 — 여기서 고치기 시작하면 Phase 7 이후 15건을 함께 건드린다 |

### Phase 11 이월 3건

| # | 항목 | 이번 Phase |
|:-:|---|---|
| ① | `§5.16`·`§6.11` 응답 필드 7건 | ✅ **목표 13 으로 흡수** (T3) |
| ② | `no_show_wait_minutes` 상한이 정본에 부재 | 사양 공백 — 사용자 판정 대기. 코드에 값을 박지 않는다 |
| ③ | 규약 시험의 면제 목록이 무검증 | ⚠ **Phase 12 의 "목표 외 과제(조율자 소유)" 로 적혔는데 착수 여부를 원장에서 확인하지 못했다.** Phase 13 조율자가 `SchedulerLockConventionTest`·`ControllerAuthorizationConventionTest` 를 열어 **면제 개수 단언 또는 근거 주석 강제가 있는지 센 뒤** 없으면 이번에 닫는다. 좌석에 배정하지 않는다(횡단 파일) |

**Phase 9 이월 ⑤(T2 수정 라운드 3 재리뷰 미부착)** — 계속 미해소. 게이트 리뷰 판정 대상에 계속 싣는다.

### 오픈 이슈 — 이 Phase 를 막는 것이 **없다**

`§7.2` 표를 정본과 대조했다 — `G`·`P`·`Q`·`I`·`J` 잔여 ①·`N`·`H`·`W` 어느 것도 `MON-01~07`·`O-05·06` 을 막지 않는다. **부하 테스트 L2 는 "Phase 13 완료 후" 가 시점**이라(`§5.6`) 완료 판정 뒤에 별도로 잡는다.

⚠ **`§8.1` 의 "13 ← 12 재판정 필요" 행** — 재판정 결과 **실질 선행은 9(moving 회차·승하차)·10(위치·WS)·11(미승차 사건)** 이고 Phase 12 산출물은 쓰지 않는다(Ruling 230). 12 가 이미 완료라 진행에 영향은 없고 표의 근거만 정정한다.
