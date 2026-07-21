# MVP_RELEASE_TRACKER — 백엔드 MVP 보완 갭 우선순위 체크리스트

> **문서 성격 (2026-07-21 전면 개편):** `PROJECT_MASTER_PLAN.md`가 프로젝트 전체 단일 소스(SoT)다. 이 문서는 그 하위 문서로, **"엔티티~컨트롤러는 있지만 요구사항 대비
비어있는" 백엔드 안전/알림 갭(G1~G6)만 추적**하는 실행 체크리스트다. 큰 그림·요구사항·모듈 완료 정의는 `PROJECT_MASTER_PLAN.md`, 코드 컨벤션은 `reference.md`를 따른다.

> **진행 상황(2026-07-21 갱신):** P0(G2·G3·G1)·F2·Flyway·Swagger·env 보안 조치는 `main`에 커밋 완료(상세는 `git log`). **F4는
구현+curl E2E 검증까지 완료됐으나 아직 미커밋**(다음 커밋 요청 시 전용 커밋으로 반영). G5(테스트)·G4·G6·F1·F3는 아직 `[ ]` 미체크.
**같은 날 사용자가 §0의 MVP 기능 스코프를 확정** — F1~F4가 새 최우선 순위(§2 P(MVP))로 추가됨.

> **설계 확정(2026-07-21):** F1~F4 전체 구현 계획을 확정하고 아래 §2에 반영했다. 순서는 **F2 → F4 → F1 → F3**(사용자 확정). 설계 확인이 필요했던
> 2건도 결정됨 — **F3 수신자**: 기존 studentId 기준 push 경로 재사용(스키마 변경 없음), 배포된 `RoutePlan`의 첫 정차 학생을 알림 키로 써서 그 학생의 담당 기사(=배포 대상 버스
> 기사)에게 push 도달(학부모도 함께 받으나 사용자가 MVP 이후 학부모 알림 추가 예정이라 허용). **F4 흐름**: 트래커 원안의 "즉시 `assignedBus` 갱신"을 정정 —
> 자동배차·경로를 **제안(RECOMMENDED)** 으로 먼저 관리자에게 제공 → 검토 → **승인(confirm) 시 배차 확정 + 배포**.

> **F2·F4 구현+검증 완료(2026-07-21):** §2 P(MVP)의 F2·F4가 모두 완료됐다(아래 체크박스 참조). F2는 커밋 완료, **F4는 아직 미커밋**.
> **다음 착수점은 F1**(버스 단위 실시간 위치) — 위 §2 F1 항목의 "설계 확인 필요" 사항부터 다시 확인하고 시작한다.

## 0. MVP 출시 범위 확정 (사용자 정의, 2026-07-21)

사용자가 MVP에서 **실제로 출시할 기능을 아래로 한정**했다. 기존에 이미 구현된 기능(학생/학부모 앱 API, G1~G3 안전 기능 등)은 축소·제거하지 않고 그대로 둔 채, 아래 기능들을 "출시 가능한 상태"
로 만드는 데 필요한 **추가 작업만** 이 문서에 리스트업한다.

**대상 사용자 계층**: 버스기사/선탑자(= `DRIVER` 롤로 흡수 — `Role` enum에 별도 선탑자 롤 없음, 동승보호자는 기사 앱 흐름에 흡수하기로 이미 결정됨) · 관리자/학원관리자(=
`ACADEMY_ADMIN`/`PLATFORM_ADMIN`, 이미 존재)

**기능별 현재 구현 상태 (실제 코드 대조, 2026-07-21):**

| # | 기능(사용자 서술)                        | 상태            | 근거                                                                                                                                                                                                                                                                                                                                                   | 관련 갭   |
|---|-----------------------------------|---------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| 1 | 버스의 실시간 위치 제공                     | ❌ 미구현(버스 단위)  | `location` 모듈이 철저히 **학생 단위**(`LocationPing.studentId`, `location/dto/LocationPing.java:16-22`) — `Bus` 엔티티엔 좌표 필드 자체가 없다. `LocationQueryService.getBusLocations`(`location/query/LocationQueryService.java:62-69`)도 "그 버스 배정 학생들 각자의 위치 목록"일 뿐 버스 자체의 단일 위치가 아니다                                                                                     | **F1** |
| 2 | 이벤트(결석·승하차 위치변경)로 인한 노선 최적화       | 🟡 부분구현       | `RoutingReplanEventConsumer`(`routing/infrastructure/impl/RoutingReplanEventConsumer.java:29-40`)가 결석(`attendance-approved`)·일정변경(`schedule-result`) 승인 시엔 이미 국소 replan(Phase 6e). 하지만 배정 버스·정류장·하차지 변경(`StudentCommandService.updateAssignment`/`updateDropoff`, `student/command/StudentCommandService.java:86-104`)은 이벤트를 발행하지 않아 재최적화가 트리거되지 않는다 | **F2** |
| 3 | 노선 변경 시 기사에게 수정 노선 배포             | 🟡 부분구현       | 배포(`RoutingCommandService.publish()`) + 기사 pull 조회(`GET /api/route-plans/driver/{busId}`)는 이미 있음. 단 **배포 시점에 기사에게 알림이 안 감**(RECOMMENDED 단계의 `ROUTE_RECOMMENDED` 알림만 있고 `publish()`엔 알림 트리거가 없음) — 기사는 폴링해야 새 노선을 안다                                                                                                                                  | **F3** |
| 4 | 배차 최적화(버스 인승별 인원배분 + 최적 경로별 인원배분) | ❌ 미구현(가장 큰 갭) | `RoutingCommandService.generate()`는 **이미 특정 버스로 배정된 로스터**의 정차 "순서"만 최적화(단일 차량 TSP)한다. 정원 초과 시 다른 버스로 재배분하지 않고 그냥 예외를 던진다(`routing/command/RoutingCommandService.java:146-149`). 학생→버스 배정은 100% 관리자 수동 지정(`StudentCommandService.updateAssignment`)                                                                                                   | **F4** |
| 5 | 학생 승하차 여부                         | ✅ 구현 완료       | `rideevent` 모듈(`BOARD`/`ALIGHT`/`HANDOVER`, 2026-07-21 G3에서 `HANDOVER`까지 보강)로 이미 충족. 추가 작업 불필요                                                                                                                                                                                                                                                       | —      |

**우선순위(사용자 확정, 2026-07-21)**: F2(기존 Phase 6e 패턴에 배선만 추가, 가장 저비용) → F4(가장 큰 신규 기능, 이 MVP의 핵심 차별점) → F1(위치 출처를 기사 단말로) → F3(마무리).
F1·F4의 설계 확인 항목도 결정 완료 — 상세는 위 "설계 확정" 콜아웃 + 아래 §2 각 항목 참조.

## 📌 폐기된 이전 재정의 (참고용, 실행 안 됨)

2026-07-18에 이 문서는 "역할 2그룹화(DRIVER+ATTENDANT / ACADEMY_ADMIN+PLATFORM_ADMIN) + Flutter 드라이버 앱 + 실시간 버스위치(기사 GPS)"로의 MVP
재정의를 담았었다. **이 재정의는 실행되지 않고 폐기됐다** — 실제 구현(Phase 0~7)은 원래 5계층 계획을 그대로 따랐고, `drivesession` 모듈 완료 기록(
`PROJECT_MASTER_PLAN.md` §12.1)에 "ATTENDANT는 구현하지 않음(코드베이스에 그런 Role이 없고, 동승보호자는 기사 앱 흐름에 흡수 확정돼 있음)"이라고 명시돼 있다. 이 문서의
나머지는 그 폐기된 계획을 아래 내용으로 전면 교체한 것이다.

## 📌 저장소 구조 추천 (참고, 실행 항목 아님)

`backend`/`frontend`가 로컬 전용 단일 git(원격 없음) 모노레포로 관리 중이며, `frontend`는 아직 Mock 데이터 기반 데모(1커밋, 백엔드 API 미연동)이고 CI/CD 파이프라인이
없다. **지금은 모노레포 유지를 추천** — 분리로 얻을 독립 배포 이점이 아직 없고 관리 비용만 는다. **재검토 시점**: ① frontend가 실제 백엔드 API 연동을 시작, ② Vercel 등 독립 배포
주기가 필요, ③ 팀 분리로 PR 흐름을 나눠야 할 때.

---

## 1. 백엔드 보완 갭 (실제 코드 대조로 확인, 2026-07-21)

`PROJECT_MASTER_PLAN.md` §12.1은 "15개 모듈 완료(엔티티~컨트롤러)"라고 선언하지만, 요구사항(§5~§7) 대비 아래 로직이 실제로 비어있다.

| ID     | 요구사항 근거                                        | 확인된 코드 상태                                                                                                                                                                                                                                                                       |
|--------|------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **G1** | §7 알림 임계값(근접 5분 전, 미승차 10분)                    | `NotificationType.APPROACH`/`NO_SHOW`(`notification/domain/NotificationType.java:6-7`) enum만 존재, 발행 트리거 없음. `PROJECT_MASTER_PLAN.md` §12.1 routing "다음 착수점"에도 "사용자 확정 필요"로 명시돼 있음                                                                                               |
| **G2** | §6 차내 잔류 방지(운행종료 전 탑승 0 확인, 미하차 시 종료 차단)       | `DriveSession.end()`(`drivesession/entity/DriveSession.java:79-85`)가 잔류 학생 확인 없이 무조건 `COMPLETED` 전이                                                                                                                                                                             |
| **G3** | §5 학생 상태머신(탑승예정→…→하차완료→**보호자 인계완료**→운행완료, 8단계) | `RideType`(`rideevent/entity/RideType.java`)은 `BOARD`/`ALIGHT` 2종뿐 — "하차완료 ≠ 보호자 인계완료" 구분이 코드에 없음                                                                                                                                                                               |
| **G4** | §6 태그/단말기 오류 오프라인 처리·차량고장 전용 대응                | `RideSource`(`QR`/`NFC`/`MANUAL`/`CORRECTION`)에 오프라인 동기화 개념 없음. `sos` 모듈은 범용이라 차량고장 전용 플로우가 아님                                                                                                                                                                                  |
| **G5** | 회귀 안전망                                         | 테스트 파일 10개뿐(`auth`/`global.event`/`global.security`/`location`/`rideevent`/`routing.engine`(유닛)/`tenant`/`user`) — `attendance`/`schedule`/`sos`/`drivesession`/`bus`/`route`/`student`/`notification` 등은 curl 수동검증만 있고 자동 테스트가 전혀 없음(각 모듈 완료 기록에 반복적으로 "전용 테스트 파일은 만들지 않음" 명시) |
| **G6** | NCP 실키 검증                                      | `NaverMapRouteClient`가 실키 없이 스펙만으로 구현(코드 주석에 명시). 2026-07-21 사용자가 실키를 `.env.example`에 채워 검증 가능해짐. ⚠️ 코드 주석: "NCP Direction 15는 waypoint 총합 5개 제한, 6d의 15개 청킹 상한과 별도 재검토 필요"                                                                                                     |

**우선순위 (사용자 확정, 2026-07-21): 안전 기능(G2·G3·G1) → 테스트(G5) → 나머지(G4·G6)**

---

## 2. 백로그 (우선순위 순, 커밋 단위 체크리스트)

> `PROJECT_MASTER_PLAN.md` Phase 체크리스트와 동일 형식 — 각 항목 완료 시 검증 방법·커밋 해시를 병기한다.

**P(MVP) · §0에서 확정한 MVP 필수 기능 갭 (신규 최우선, 2026-07-21)**

- [x] **F2 — 배정/하차지 변경 시 자동 재최적화 배선** (2026-07-21 완료, 미커밋): `StudentCommandService`에 `ApplicationEventPublisher` 주입.
  `updateAssignment`(변경 전 `oldBusId` 캡처, 실제 필드 변경이 있을 때만 발행)·`updateDropoff`(배정 버스가 있을 때만 발행)가 각각 신규
  `StudentAssignmentChangedEvent`(tenantId/studentId/oldBusId/newBusId/serviceDate)·`StudentDropoffChangedEvent`(tenantId/studentId/busId/serviceDate)를
  발행하도록 배선(토픽명은 `KafkaEventPublisher.toTopic`가 클래스명에서 자동 유도, 신규 설정 불필요). `RoutingCommandService`의 버스별 replan 루프를
  `replanForBus(bus, triggerStudentId, triggerStudentName, eventDate)`로 추출해 기존 `replanForStudent`와 신규
  `replanForAssignmentChange(studentId, oldBusId, newBusId, eventDate)`가 공유 — 후자는 old·new 버스 각각 replan하되 두 값이 같으면(정류장만
  변경) `Objects.equals`로 한 번만 수행해 외부 경로 API 중복 호출을 막는다. `RoutingReplanEventConsumer`에 `student-assignment-changed`/
  `student-dropoff-changed` 리스너 2개 추가(기존 `GROUP_ID` 재사용). notification 모듈·DB 마이그레이션 변경 없음(기존 `ROUTE_RECOMMENDED` 경로 재사용).
  <br>**curl E2E 검증(한빛학원 시드 데이터, 2026-07-21):** ① 변경 없는 요청(`{}`)→ 이벤트 미발행(버전·알림 불변) 확인 ② 김민준 하차지 변경 → bus1
  PICKUP/DROPOFF 버전 각 +1(v2→v3, v1→v2) + `ROUTE_RECOMMENDED` 알림 2건 발행 확인 ③ 이서연 승차정류장만 변경(같은 버스 내)→ 버전 정확히 +1씩(중복
  replan 없음, 알림 정확히 +2건만 추가) 확인 ④ 박도윤 bus1→bus2 재배정 → **이전 버스(bus1) PICKUP/DROPOFF·신규 버스(bus2) PICKUP 3개 모두** 버전
  +1(알림 +3건), 양쪽 로스터도 정확히 갱신(bus1은 박도윤 제거 2명, bus2는 박도윤 포함 4명) 확인 ⑤ bus2 DROPOFF(당일 계획 없음)는 그대로 미생성(에러 없이
  안전하게 스킵) 확인. `./gradlew compileJava`+`test` 26/26 통과, 서버 로그에 replan 실패·예외 없음.
- [x] **F4 — 배차 최적화(멀티버스 자동 배정, 검토 게이트)** (2026-07-21 완료, 미커밋): "즉시 배정 갱신"이 아니라 **제안(propose) → 관리자 검토 → 확정(confirm) 시 배차+배포** 2단계로 구현 완료.
  - **신규 파일**
    - `routing/domain/BusCapacity.java` — `record BusCapacity(Long busId, int seatCapacity)`. engine 포트가 JPA 엔티티에 의존하지 않도록 하는 순수 값 타입(`LatLng`와 동일 위치·성격).
    - `routing/engine/spec/BusAssigner.java` — `Optional<Map<Long, List<Long>>> assign(LatLng depot, Map<Long, LatLng> studentPoints, List<BusCapacity> buses)`. 반환 map의 key=busId, value=배정된 studentId 리스트(방문순서 아님 — 순서 최적화는 기존 `RouteEngine`이 뒤에서 별도 수행). 총원 > 총정원이면 `Optional.empty()`.
    - `routing/engine/impl/SweepAssigner.java` — `@Component implements BusAssigner`(기존 `HeuristicRouteEngine`과 동일 위치·패턴). 방위각은 직접 계산하지 않고 기존 `GeoMath.bearingDegrees(depot, point)`(0~360도, `HeuristicRouteEngine`이 이미 쓰는 유틸)를 재사용 → 정렬 후 버스를 파라미터 순서대로 순회하며 `seatCapacity`까지 채움.
    - `routing/dto/AutoAssignRequest.java` — `record(Long tenantId, RouteDirection direction, LocalDate serviceDate)`(serviceDate 생략 시 오늘).
    - `routing/dto/AutoAssignResponse.java` — `record(List<RoutePlanResponse> plans, List<String> excludedStudentNames)`.
    - `routing/dto/ConfirmAutoAssignRequest.java` — `record(List<Long> planIds)`.
    - `backend/src/test/java/src/backend/routing/engine/impl/SweepAssignerTest.java` — **유닛테스트 추가 대상**(기존 `HeuristicRouteEngineTest` 선례 — 순수 알고리즘 클래스는 테스트를 만드는 게 이 코드베이스 컨벤션). 케이스: 정원 내 균등분산 / 총원>총정원→empty / 버스 1대 / 좌표 동일점 여러 명(방위각 동률) 처리.
  - **수정 파일**
    - `attendance/query/AttendanceQueryService.java` — `getActiveRosterForTenant(Long tenantId, LocalDate date)` 신규 추가. 기존 `getActiveRoster(busId, date)`(`studentRepository.findByAssignedBusId`)와 나란히, `studentRepository.findByTenantId(tenantId)`(이미 존재) 기준으로 동일한 결석-제외 필터를 적용 — 배정 여부 무관하게 테넌트 전체를 본다.
    - `routing/command/RoutingCommandService.java`:
      - 생성자에 `BusAssigner busAssigner` 주입 추가.
      - `private RoutePlan buildPlan(Bus, RouteDirection, LocalDate, RoutePlanStatus)` → 내부에서 `attendanceQueryService.getActiveRoster(bus.getId(), serviceDate)` 호출 후 아래 신규 오버로드로 위임(기존 "로스터 0명 예외" 가드는 이 래퍼에만 유지, 동작 변화 없는 순수 리팩터).
      - `private RoutePlan buildPlan(Bus, RouteDirection, LocalDate, RoutePlanStatus, List<Student> roster)` 신규 — 로스터를 파라미터로 받는 것 외 기존 로직 그대로(좌표해석→`routeEngine.optimizeOrder`→`resolveRoute`→저장).
      - `requireDepot(Bus bus)` → `requireDepot(Tenant tenant)`로 파라미터 타입 변경(`bus.getTenant()` 호출부만 `.getTenant()` 붙여서 이관) — auto-assign은 특정 버스가 아니라 테넌트 단위로 depot을 구하므로.
      - `public AutoAssignResponse autoAssign(AuthUser admin, AutoAssignRequest req)` 신규: ① `TenantGuard.resolveTenantId(admin, req.tenantId())` ② `busRepository.findByTenantId(tenantId)` 후 **`Bus::getId` 기준 정렬**(⚠️ 정렬 없이는 버스 배열 순서가 DB 반환 순서에 좌우돼 매번 다른 버스부터 채워지는 비결정적 버그였음 — curl 검증 중 발견해 수정)(비어있으면 400) ③ `requireDepot(buses.get(0).getTenant())` ④ `attendanceQueryService.getActiveRosterForTenant(tenantId, serviceDate)` → direction별 `boardingPoint`/`dropoffPoint`로 좌표 조회, null이면 `excluded`에 이름 담고 제외 ⑤ 좌표 있는 학생 0명이면 빈 `AutoAssignResponse(List.of(), excluded)` 즉시 반환(에러 아님) ⑥ `busAssigner.assign(depot, points, busCapacities)`(empty면 400 "전체 정원 N명 초과: 대상 M명") ⑦ 버스별 배정 studentId가 1명 이상이면 `buildPlan(bus, direction, serviceDate, RECOMMENDED, busRoster)` 호출(0명 배정 버스는 계획 생성 생략) → `AutoAssignResponse(plans, excluded)` 반환.
      - `public List<RoutePlanResponse> confirmAutoAssign(AuthUser admin, List<Long> planIds)` 신규: 각 planId를 기존 `findForAdmin(admin, id)`로 로드 → `plan.getStops()`의 각 `studentId`에 대해 `studentRepository.findById(...).ifPresent(s -> s.assignBus(busRepository.getReferenceById(plan.getBusId())))`로 배정 커밋 → 기존 `plan.approve(admin.userId())` → `plan.publish(admin.userId())` 그대로 재사용(상태 가드 자동 적용, 이미 승인/배포된 planId 재전달 시 CONFLICT 409로 자연 방어).
    - `routing/controller/RoutingController.java`: `POST /api/route-plans/auto-assign`·`POST /api/route-plans/auto-assign/confirm`(둘 다 `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN','PLATFORM_ADMIN')")`).
  - **DB 마이그레이션 불필요** — `RoutePlanStatus`(RECOMMENDED/APPROVED/PUBLISHED)·`Student.assignedBus` 모두 기존 컬럼/enum 값 그대로 사용.
  - **F3(기사 알림)와의 관계**: F3가 아직 구현되지 않아 `publish()`는 상태전이만 하고 알림을 쏘지 않는다 — **F4는 F3를 기다릴 필요 없이 그대로 구현 가능**(confirm 시점에 알림이 안 갈 뿐, 배차·배포 자체는 정상 동작). F3가 나중에 `publish()`에 이벤트 발행을 추가하면 F4는 코드 변경 없이 자동으로 알림까지 받게 된다.
  - 기존 수동 배정(`updateAssignment`)·generate/approve/publish는 그대로 공존. ⚠️ §4 문서의 "파이프라인 구현·검증 완료" 문구는 순서최적화(3~5단계)만 가리키던 것으로 정정됨 — 배정(2단계 Sweep)은 이번 신규.
  - **curl E2E 검증(한빛학원, tenantId=1, 2026-07-21):** ① `POST /auto-assign`(PICKUP, bus1/2 정원 25/25) → 학생 6명 전원이 정원 넉넉한 bus1 하나에 배정(RECOMMENDED, `Student.bus_id` 불변) 확인 → `confirm` → 200 + `PUBLISHED` + DB `student.bus_id` 전원 1로 커밋 확인 → 같은 planId 재confirm → 409 CONFLICT(`RoutePlan.approve()` 상태가드 재사용) 확인 ② bus1 정원을 SQL로 3으로 축소 후 재실행 → **bus1(id 순 정렬로 먼저 채워짐)에 방위각순 3명, 나머지 3명은 bus2**로 정확히 분산 확인(정렬 버그 발견·수정 후) ③ bus1·bus2 정원을 2·2(합 4)로 축소 → `POST /auto-assign` → 400 "전체 정원(4명) 초과: 대상 6명" 확인 후 정원 25/25로 원복 ④ `direction=DROPOFF` 호출 → 하차좌표 없는 3명(최지우·정하율·강서준)이 `excludedStudentNames`에 정확히 보고되고 나머지 3명만 계획에 포함되는지 확인. `./gradlew compileJava`+`test` 30/30 통과(신규 `SweepAssignerTest` 4건 포함), 서버 로그에 실제 오류 없음(정원초과 400은 의도된 로그).
- [ ] **F1 — 버스 단위 실시간 위치(설계 확인 필요)**: 기존 `LocationSource`/`LocationRepository` 포트 재사용. ①`LocationPing`(
  `location/dto/LocationPing.java`)에 `busId` 필드 추가(또는 병렬 타입 `BusLocationPing`) ②기사 앱이 주기 보고할 신규
  `POST /api/locations/bus`(busId+lat+lng) ③`LocationRepository.findLatestByBus(busId)` ④관리자용
  `GET /api/locations/tenant/{id}/buses`(테넌트 전체 버스 위치 목록, 지도 표시용) 신설. Mock 모드는 `MockLocationSource`가 학생별로 만들던 트레이스를 버스
  단위 1개로 단순화 가능(학생 앱이 이 MVP 스코프 밖이라 더 적합). 기존 학생 단위 `POST /api/locations`·조회 API는 그대로 유지(제거 안 함). 검증(예정): 기사 로그인 → 버스 위치
  보고 → 관리자가 테넌트 버스 위치 목록 조회 시 반영 확인.
  <br>**확정 설계(2026-07-21):** 학생 위치 모듈은 그대로 두고 **버스 단위 병렬 경로**를 미러로 추가 — `BusLocationPing`(dto) + `BusLocationRepository`(port)+`InMemoryBusLocationRepository`(버스별 최신 1건, Redis 병행은 후속) +
  `BusLocationCommandService.report(driver, busId, lat, lng)`(`bus.driver==driver.userId` 가드) + `BusLocationQueryService.getTenantBusLocations(admin, tenantId)`(`TenantGuard` 사용) + `BusLocationView`.
  엔드포인트 `POST /api/locations/bus`(DRIVER)·`GET /api/locations/buses?tenantId=`(관리자). Mock은 `MockBusLocationSource implements LocationSource`를 **추가**만 하면 됨(기존 `LocationSimulationScheduler`가 모든 소스를 폴리모픽 tick, 학생 Mock 유지) — 플래그 `app.location.bus-mock.enabled`.
- [ ] **F3 — 노선 배포 시 기사 알림(publish 트리거 추가)**: `RoutingCommandService.publish()`(
  `routing/command/RoutingCommandService.java:94-99`)에 `RoutePlanPublishedEvent`(신규) 발행 추가 →
  `DomainEventNotificationConsumer`(`notification/infrastructure/impl/DomainEventNotificationConsumer.java`)에 리스너 추가(
  `NotificationType.ROUTE_RECOMMENDED` 재사용 또는 신규 `ROUTE_PUBLISHED` 타입) → 기존 `WebSocketNotificationSender`가
  `driverUserId()`의 `/queue/notifications`로 자동 push(배선만 추가, 신규 인프라 불필요). §3에 있던 "기사 라우팅 STOMP push 전환" 항목을 이 F3로 승격(중복
  제거). 검증(예정): 관리자 publish 호출 → 담당 기사 WebSocket 세션에 알림 도착 확인.
  <br>**확정 설계(2026-07-21):** 알림 파이프라인이 studentId 기준이라 배포 알림의 키로 **배포된 계획의 첫 정차 학생**(`plan.getStops().get(0).getStudentId()`, 배포 계획엔 정차≥1 보장)을 사용 →
  `WebSocketNotificationSender`가 그 학생의 담당 기사(=배포 대상 버스 기사) `/queue/notifications`로 push(기사 포착 O). 학부모/테넌트 병행 수신은 사용자가 허용(MVP 이후 학부모 알림 추가 예정). 신규
  `NotificationType.ROUTE_PUBLISHED` + `V5__notification_log_add_route_published_type.sql`(V4와 동일 CHECK 제약 재정의 패턴 — "배포됨(운행 시작)"은 "추천됨(검토 필요)"과 의미가 달라 신규 타입).
  `RoutePlanPublishedEvent`(→토픽 `route-plan-published`)는 `RoutingCommandService.publish()`와 F4 `confirmAutoAssign`의 publish 직후 발행. `DomainEventNotificationConsumer`에 리스너 추가, dedupKey stage=`"published:bus"+busId+":"+direction+":v"+version`.
  ⚠️ 수용된 MVP 한계: 대표 학생이 generate~publish 사이 다른 버스로 재배정되면 push 대상 기사가 어긋날 수 있음(정상 흐름에선 발생 안 함) — 정밀 대상이 필요해지면 버스/기사 전용 push 경로로 승격.

**P0 · 안전 기능 (최우선)**

- [x] **G2 — 차내 잔류 방지** (2026-07-21 완료, 미커밋): `DriveSessionCommandService.end()`가 세션 시작 이후 해당 버스의 승하차 기록을 학생별로 훑어 마지막
  기록이 `BOARD`(하차 미기록)인 학생이 있으면 `CONFLICT` 409로 종료를 차단(`requireNoOnboardStudents`). curl E2E 검증: 1명 BOARD만 기록된 상태로 종료
  시도 → 409("하차 처리되지 않은 학생이 있어 운행을 종료할 수 없습니다") 확인 → 전원 ALIGHT 기록 후 재시도 → 200 정상 종료 확인.
- [x] **G3 — 보호자 인계완료 상태 구분** (2026-07-21 완료, 미커밋): `RideType`에 `HANDOVER` 추가(제안대로 학년 제한 없이 전 학생 대상 **선택적 기록**으로 구현,
  강제/차단 정책은 보류). `HandoverCompletedEvent` 발행 → `NotificationType.HANDOVER_DONE` 알림 연결. 기존 `POST /api/ride-events`(
  `type=HANDOVER`)로 바로 기록되고 `RideEventResponse.type`으로 조회 시 ALIGHT와 구분됨. Hibernate가 V1에 생성해둔 `ride_event`/
  `notification_log`의 `type` CHECK 제약이 신규 값을 막아 `db/migration/V3__ride_event_add_handover_type.sql`+
  `V4__notification_log_add_handover_done_type.sql` 추가. curl E2E 검증: HANDOVER 기록 → 200 + `notification_log`에
  HANDOVER_DONE 1건 저장 확인.
- [x] **G1 — APPROACH/NO_SHOW 알림 트리거** (2026-07-21 완료, 미커밋): `DriveSessionCommandService.checkApproachAndNoShow()`(신규
  `ApproachNoShowScheduler`가 `app.drivesession.approach-check-ms`(기본 15초) 주기 호출)가 진행 중 등원(PICKUP) 세션의 배포된
  `RoutePlan.stops`를 훑어, 정류장별 ETA(`session.startedAt + etaSeconds`) 기준 도착 5분 전이면 `ApproachEvent`, +10분 미승차면
  `NoShowEvent`를 발행 → `NotificationType.APPROACH`/`NO_SHOW` 알림 연결(`NotificationCommandService`의 dedupKey 멱등 재사용,
  `SosEscalationScheduler`와 동일한 "매 틱 재평가+멱등 저장" 패턴). curl E2E 검증: bus1(3호차)로 PICKUP `RoutePlan` 생성→승인→배포 → 세션 시작 → 근거리
  정류장(etaSeconds=112s) 학생에 APPROACH 1건 발행 확인 → `started_at`을 SQL로 11분 전으로 이동해 NO_SHOW 임계 재현 → 미승차 학생 전원(3명) NO_SHOW 각
  1건만 발행(재틱에도 중복 없음) 확인 → BOARD 기록 학생은 이후 판정에서 제외됨을 확인.

**P1 · 테스트 커버리지 (안전망)**

- [ ] **G5 — 리스크 큰 모듈부터 자동 테스트 추가**: `attendance` → `schedule` → `sos` → `drivesession` 순(승인 상태전이·권한가드·중복처리 409 케이스 우선).
  이후 `bus`/`route`/`student`/`notification` 커맨드·쿼리 서비스 단위테스트 추가

**P2 · 나머지**

- [ ] **G4 — 오프라인 태그 동기화·차량고장 전용 플로우**: `RideSource`에 오프라인 값 추가 + 통신복구 후 자동 동기화 로직 / 차량고장·사고 전용 이벤트·명단 자동전달 플로우(현재 `sos`
  범용 흐름과 분리할지 결정 필요)
- [ ] **G6 — NCP 실키 E2E 검증**: `routing.provider=naver`로 전환 후 `POST /api/route-plans/generate` curl 검증. waypoint 5개 제한 문제
  발생 시 청킹 로직(현재 15개 기준) 재조정

---

## 3. MVP 이후 업그레이드 로드맵 (신규 섹션 — 현재 코드를 기준으로 확장)

> G1~G6(MVP 백로그)와 섞이지 않도록 별도 섹션으로 관리. "MVP 출시 후, 지금 코드를 베이스로 계속 넓혀갈 것들."

- [ ] **Phase 8 — Mock → 실 GPS 전환**: `PROJECT_MASTER_PLAN.md` §11.4 Phase 8 참조. 플래그 토글 준비는 끝났고, 8장 법적 선행요건(동의 UI·이력 스키마)
  만 실제 모바일 GPS 테스트 시점까지 보류 확정
- [ ] **STUDENT/PARENT 프론트 실연동**: 현재 `frontend/`는 Mock 데이터 데모 — 실제 백엔드 API(JWT 인증·역할별 조회)로 교체
- [ ] **`consent` 모듈**: §8 개인정보·위치정보 동의 UI 계약 + 동의 이력 스키마(Phase 8의 선행요건과 연동)

> ~~기사 라우팅 STOMP push 전환~~ — 2026-07-21 §2 **F3**로 승격(MVP 필수 기능으로 확정돼 이 로드맵에서 제거).

---

## 4. 노선 최적화 알고리즘 검토 (요청한 검토 결과)

**문제 정의:** 단일 depot(학원)·정원 제약 차량경로문제(CVRP) = 학교버스 라우팅(SBRP). 단일 학원·버스 수 대·학생 수십 명 규모 → 무거운 최적솔버 불필요.

**채택 파이프라인 (cluster-first, route-second):**

1. **Geocoding** — 하차지 주소 → 좌표(네이버). 결과는 `Student`에 캐시(호출 최소화).
2. **자동배치(assign)** — **Sweep 알고리즘**(depot 기준 방위각 정렬 후 `seatCapacity`까지 채워 버스 배정). 방사형 배치에 강하고 설명이 쉽다.
3. **순서 최적화(sequence)** — 버스 내 방문 순서 = open-TSP. **nearest-neighbor 생성 + 2-opt 개선**, 거리는 **Haversine(직선)** → API 호출 0,
   즉시 계산.
4. **실도로 경로(네이버)** — 확정 순서의 경유지로 **네이버 Directions(다중 경유지)** 1회 호출 → 실제 도로 polyline + 총거리 + 정류장별 ETA.
5. **당일 재계산(replan)** — 결석/하차지변경은 **해당 버스만 국소 재최적화**(2·3·4 재실행) → 관리자 추천 → 승인 → 배포.

**왜 Heuristic인가(vs OR-Tools):** N² 거리행렬을 네이버로 뽑으면 호출량 폭발 → Haversine로 배치·순서를 정하고 **네이버는 최종 순서 1개 경로에만** 호출해 비용 억제.
OR-Tools는 품질↑지만 네이티브 의존성·모델링 비용이 MVP엔 과함. `RouteEngine` 포트 뒤에 두면 콜러 수정 없이 교체 가능(`reference.md` §15 Routing = Port/MSA
후보와 일치).

**등·하원 양방향:** 등원=`boardingStop`(승차) 순서 최적화, 하원=`Student.dropoff` 좌표 순서 최적화. `RoutePlan.direction`으로 두 인스턴스를 구분해 각각
생성·승인·배포.

> **⚠️ 정정(2026-07-21):** 이전 버전은 이 문단에서 "이 파이프라인은 실제로 routing 모듈에 그대로 구현·검증 완료됐다"고 적었지만, 실제 코드 대조 결과 **2단계(자동배치/Sweep)는
구현된 적이 없다** — `RoutingCommandService.generate()`는 이미 특정 busId로 배정된 로스터를 입력받을 뿐, 여러 버스에 걸친 자동 배정 로직이 없다(정원 초과 시 예외만 던짐,
`routing/command/RoutingCommandService.java:146-149`). 실제로 구현·검증된 것은 **3~5단계(순서최적화·실도로경로·replan)뿐**이다. 2단계는 §2 **F4**로
> 신규 작업 등록됨.

---

## 5. ⚠ 리스크 / 전제

- **네이버 실키 확보(2026-07-21)** — `NAVER_DIRECTIONS_KEY_ID`/`NAVER_DIRECTIONS_KEY`를 `.env.example`에 입력했으나 **G6(NCP 실키 E2E
  검증)는 아직 미실행**. `routing.provider`는 여전히 기본값 `osrm`.
- **✅ env 키 보안 조치 완료 (2026-07-21)** — 실키가 git-tracked 파일인 `backend/.env.example`에 uncommitted 상태로 노출돼 있던 문제를 커밋 전에 정리:
    1. `backend/.env`(신규, untracked) 생성 — 실제 키 값 이전(공백 제거)
    2. `backend/.env.example`의 `NAVER_DIRECTIONS_KEY_ID`/`NAVER_DIRECTIONS_KEY`를 원래 git 히스토리 상태(빈 값)로 복원
    3. 루트 `.gitignore`의 `# Backend (Gradle / Spring Boot)` 블록에 `backend/.env` 패턴 추가
    4. `frontend/.env.local`의 네이버 지도 키(`NEXT_PUBLIC_NAVER_MAP_ID`)는 이미 git 추적 밖이라 조치 불필요
- **네이버 Directions 경유지 상한(~15)** → NCP Direction 15는 실제로는 waypoint 총합 5개 제한이 있어(코드 주석) G6 검증 시 청킹 로직 재조정 필요할 수 있음.
- **프론트(Next.js)는 백엔드 범위 밖** — 백엔드는 역할별 API 계약만 제공. 실연동은 §3 업그레이드 로드맵 항목.
- **DB 스키마** — Flyway로 전환 완료(2026-07-20). 신규 컬럼/제약 변경은 `db/migration/V{n}__설명.sql`로 추가.

---

## 6. 참고 (재사용 자산)

- 이벤트 백본: `TransactionalDomainEventRelay`·`KafkaEventPublisher`·`DomainEvent`(`global/event`) — G1(알림 트리거)도 동일 방식.
- 알림: `NotificationCommandService`의 dedupKey 멱등 헬퍼 — G1 트리거 구현 시 그대로 재사용.
- 포트 패턴 레퍼런스: `LocationSource`/`LocationRepository`(Mock/실 교체) → `RouteEngine`/`MapRouteClient` 동일 패턴.
- `attendance`/`sos` 모듈의 상태전이(`approve`/`reject`, `acknowledge`/`resolve`) 패턴 — G2·G3 구현 시 참고할 상태 가드(`CONFLICT` 409) 선례.

/Users/mskim/.claude/plans/reflective-snacking-wolf.md