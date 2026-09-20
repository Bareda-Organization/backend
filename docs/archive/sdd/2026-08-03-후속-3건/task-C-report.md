# Task C 리포트 — R3: §7 위반 제거 · 트랜잭션 내 외부 HTTP 판단 · §6 예외 문서화

- 브랜치: `feat/mvp-expansion-backend` (워크트리 없이 그대로 작업)
- 시작 HEAD: `79b46a7` → 종료 HEAD: `e1c8337`
- 커밋 3개
  - `a5b8711` refactor(backend): 로스터 조회를 공유 읽기 계층으로 내려 §7 위반 제거 [R3-2-1]
  - `b8815f7` docs(backend): 위치변경 쓰기 트랜잭션이 지도 API 를 기다리는 위험을 코드에 기록 [R3-2-2]
  - `e1c8337` docs(backend): 위치변경→재계산 동기 호출을 §6 의 명시된 예외로 규정 [R3-2-3]
- 결과: **2-1 수정 완료 / 2-2 의도적으로 하지 않음(근거 기록) / 2-3 완료**

---

## 0. 먼저 확인한 사실 (브리프 §1 검증)

브리프의 전제를 코드에서 다시 확인했고, 전부 사실이었다.

- `appliedPlanId` 는 API 계약이다. `LocationChangeCommandService:121` → `republishForBus` 반환값이
  `LocationChangeRequest.appliedPlanId` 컬럼에 저장되고 `LocationChangeRequestResponse.appliedPlanId` 로 나간다.
  → **동기 호출을 유지**했고, 그 계약을 테스트로 고정했다(`republishForBus_returnsIdOfNewlyPublishedVersion`).
- 트랜잭션 안 외부 HTTP 는 `republishForBus` 만의 문제가 아니다. 같은 트랜잭션의
  `RoutePlanSimulationService.compare`(4단계)도 `RoutePlanComputer.computeRoute` → `MapRouteClient` 로 HTTP 를 친다.

추가로 브리프에 없던 사실 2가지를 찾았다.

1. §7 위반은 **1건이 아니라 2건**이었다. `RoutingCommandService.buildPlan` 의
   `getActiveRoster(busId, date)` 뿐 아니라 `autoAssign` 의 `getActiveRosterForTenant(tenantId, date)` 도
   같은 Query 서비스를 부른다. 둘 다 처리했다.
2. `getActiveRoster` 의 javadoc 이 "다른 모듈은 아직 이 메서드를 호출하지 않는다" 라고 적혀 있었지만
   실제 호출자는 3곳이었다(`RoutingCommandService.buildPlan`, `RoutePlanSimulationService.computeCore`,
   `DriveSessionQueryService.getRoster`). 옮기면서 이 낡은 서술도 사라졌다.

---

## 1. 2-1 — §7 위반 제거: 해법 (b) 변형을 택했다

### 선택

후보 중 **(b) 로스터 계산을 별도 컴포넌트로 추출**을 골랐다. 단, "기존 Query 서비스가 위임"이 아니라
**메서드를 통째로 옮기고 호출자 4곳을 전부 새 컴포넌트로 돌렸다**.

- 신규: `backend/src/main/java/src/backend/attendance/roster/ActiveRosterReader.java`
  - `forBus(Long busId, LocalDate date)` ← 구 `getActiveRoster`
  - `forTenant(Long tenantId, LocalDate date)` ← 구 `getActiveRosterForTenant`
- `AttendanceQueryService` 에서는 두 메서드와 `StudentRepository` 의존을 제거했다(결석·휴원 조회 전용으로 남음).
- 호출자 4곳 변경: `RoutingCommandService`(2곳) · `RoutePlanSimulationService` · `DriveSessionQueryService`.

### 근거

- **위임만 남기면 문제가 안 없어진다.** Query 서비스에 껍데기가 남으면 다음 사람이 그걸 다시 Command 에서
  부를 수 있고, "명단 규칙이 두 곳에 있는 것처럼 보이는" 상태가 된다. 실제 호출자가 4곳뿐이라 전부 옮기는
  비용이 낮았다.
- **패키지 위치는 어제 정립된 `access/` 와 같은 논리다.** `query/` 에 두면 호출자에 Command 가 섞이는 순간
  §7 위반이 재발한다. 판정(authorization)이면 `access/`, 명단 같은 읽기 규칙이면 `roster/` —
  둘 다 "command 도 query 도 아닌 공유 계층"이다. 이 규칙을 `reference.md` §3 에 명문화했다.
- **(c) 아무것도 안 한다를 고르지 않은 이유**: 이 호출은 §7 의 자구만 어긴 게 아니다.
  `buildPlan` 은 쓰기 경로이고, 같은 트랜잭션에서 방금 수정한 `Student` 를 다시 읽어(auto-flush) 계산에 쓴다 —
  즉 "읽기 서비스를 쓰기 흐름의 일부로 소비"하고 있어 §7 이 막으려던 결합 그 자체다. 게다가 위반이 2건이었다.

### 동작 보존

- 필터 로직(`APPROVED` 신고만 제외)·`@Transactional(readOnly = true)`·호출 순서를 그대로 옮겼다.
- `readOnly = true` 는 REQUIRED 라 쓰기 트랜잭션 안에서 불리면 그 트랜잭션에 참여한다(오늘과 동일).
  이 성질에 `LocationChangeCommandService` 의 "좌표 갱신 → 재계산" 순서가 기대고 있어서
  새 클래스 javadoc 에 명시해뒀다.

### 범위 관리

`RoutingCommandService`(god class, 메서드 20)는 **의존 1개 교체 + 호출 2줄**만 건드렸다. 해체는 하지 않았다.

---

## 2. 2-2 — 트랜잭션을 쪼개지 않았다 (의도적 미수행)

### 결론

**하지 않는 것이 맞다고 판단했다.** 대신 위험을 `LocationChangeCommandService` 클래스 javadoc 에
"알려진 위험 — 쓰기 트랜잭션이 외부 지도 API 를 기다린다 (2026-08-03 확인, 미해결)" 절로 기록했다.

### 위험의 크기 (실측 근거)

- `create` 전체가 `@Transactional` 하나. 그 안에서 경로 재계산이 **최대 2번**(`compare` + `republishForBus`).
- 재계산 1번 = HTTP 1회가 아니다. `RoutePlanComputer.resolveRoute:124` 가 `routing.max-waypoints`(기본 7)를
  넘으면 구간을 나눠 반복 호출한다. 25인승 만석·하원이면 waypoint 26개 → 청크 약 5회.
- 호출당 타임아웃은 `WebClientConfig` 기준 connect 3s / response·read·write 5s.
- 즉 REPLANNED 경로 하나가 **HTTP 약 10회**, 최악 수십 초 동안 쓰기 트랜잭션과 DB 커넥션을 점유한다.

### 쪼개지 않은 이유 (코드 근거)

1. **원자성이 깨진다.** 오늘은 `applyCoordinates` → `republishForBus` → 감사 행 저장이 한 트랜잭션이라,
   재계산이 실패하면(로스터 0명·좌표 없는 학생·지도 API 오류 — `RoutingCommandService:344`, `:406`) 전부 롤백된다.
   쪼개면 "좌표는 바뀌었는데 노선은 옛 것"이 커밋되어 남고, 보상 로직이 없다. 원래 문제보다 나쁘다.
2. **dirty checking 이 조용히 사라진다.** `applyCoordinates` 는 `GuardianAccess` 가 돌려준 영속 `Student` 를
   그냥 수정한다. `application.yml:15` 가 `open-in-view: false` 라 트랜잭션 밖에서는 detached 이고,
   변경이 **예외 없이** 유실된다.
3. **읽기 순서 제약이 보이지 않게 생긴다.** `republishForBus` → `buildPlan` → `ActiveRosterReader.forBus` 는
   같은 트랜잭션의 auto-flush 덕분에 방금 바꾼 좌표를 본다. 경계를 나누면 "좌표 커밋이 먼저여야 한다"는
   제약이 코드에 안 보이는 채로 남는다.
4. **결과 알림이 유실된다.** `finish` 가 발행하는 `LocationChangeResultEvent` 는
   `TransactionalDomainEventRelay` 가 `AFTER_COMMIT` 에서만 Kafka 로 릴레이하고
   (`DomainEventNotificationConsumer.onLocationChangeResult` 가 `@KafkaListener` 로 소비),
   Spring 의 `@TransactionalEventListener` 는 기본값 `fallbackExecution = false` 라
   트랜잭션 밖 발행이면 **리스너가 아예 실행되지 않는다** → 학부모 결과 알림이 조용히 사라진다.
5. **무엇보다 실효가 없다.** `create` 에서 `@Transactional` 을 떼도 HTTP 는 여전히 트랜잭션 안이다 —
   `RoutePlanSimulationService.compare` 는 그 자체가 `@Transactional(readOnly = true)` 이고,
   `republishForBus` 는 `@Transactional` 이며 둘 다 내부에서 지도 API 를 친다.
   이 경계 하나만 바꿔서는 커넥션 점유 시간이 거의 그대로다.

### 검토했다가 버린 대안

- **`compare` 의 계산 결과를 재사용해 재계산 1회로 줄이기**(`applySimulation` 이 이미 쓰는 `SimulationOutcome` 패턴).
  버린 이유: 시뮬레이션은 좌표 없는 학생을 **조용히 제외**하고(`RoutePlanSimulationService:108`),
  `buildPlan` 은 같은 상황에서 **예외로 죽인다**(`RoutingCommandService:406`). 또 시뮬레이션은 정원 초과를
  허용해 보여준다. 재사용하면 이 검증 차이만큼 관측 가능한 동작이 바뀐다 — "동작이 조금이라도 달라지면 하지 마라"에 걸린다.
- **`create` 를 비트랜잭션 + 트랜잭션 헬퍼 빈으로 분해.** 위 1~4가 전부 걸린다.

### 진짜 해법 (별도 작업 제안)

"계산 중에는 커넥션을 쥐지 않는" 구조 — 로스터·계획 로딩을 짧은 트랜잭션에서 끝내고, 지도 API 는 트랜잭션 밖에서
부른 뒤, 저장만 다시 짧은 트랜잭션으로 여는 것. `RoutePlanSimulationService` 와 `RoutingCommandService` 를
함께 손대야 하므로 god class 해체와 묶어 계획하는 편이 낫다. javadoc 에 그때까지의 완화책도 적었다
(지도 API 타임아웃 5초를 늘리지 말 것, 커넥션 풀 크기와 `routing.max-waypoints` 를 함께 볼 것).

---

## 3. 2-3 — §6 의 명시된 예외로 규정

- **호출 지점**(`LocationChangeCommandService:121` 위)에 왜 이벤트가 아닌지를 적었다 —
  반환값이 곧 응답 계약(`appliedPlanId`)이라 비동기화하면 항상 null 이 되고 REPLANNED 의 뜻이
  "재계산·재배포됨"에서 "예약됨"으로 바뀐다(Swagger 문서 포함). 후속 파급(기사 알림)은 직접 호출이 아니라
  `republishForBus` 안의 `RoutePlanPublishedEvent` 로 흐른다는 것도 함께 적었다.
- 같은 주석에 **순서 의존**(좌표 갱신이 재계산보다 먼저여야 한다)도 명시했다. 이건 2-2 에서 발견한 제약이라
  코드에 남겨두는 게 맞다고 판단했다.
- `reference.md` §6 에 예외 조건을 4줄로 추가했다(§3 에 `access/` 를 넣었을 때의 밀도·톤에 맞춤):
  허용 기준은 "호출 결과가 동기 응답 계약의 일부일 때", 지킬 것은 ① 호출 지점 근거 주석
  ② 응답에 필요한 값만 동기로 받고 후속 파급은 이벤트로.
- `reference.md` §7 에도 한 줄 추가: Command 에 조회가 필요하면 Query 서비스 대신 §3 공유 읽기 계층으로 내린다.

---

## 4. 새로 쓴 테스트가 고정하는 것

| 테스트 | 무엇을 고정하나 |
|---|---|
| `ActiveRosterReaderTest.forBus_excludesOnlyApprovedExceptions` | **승인된** 신고만 명단에서 뺀다 — PENDING·REJECTED 는 그대로 등하원한다 |
| `ActiveRosterReaderTest.forBus_differentDateException_doesNotAffectRoster` | 조회 자체가 (studentId, targetDate) 기준이라 다른 날짜 신고는 영향 없음 |
| `ActiveRosterReaderTest.forTenant_looksAtWholeTenantIncludingUnassignedStudents` | F4 자동배차용 명단은 미배정 학생도 포함(그게 배차 대상이라서) |
| `RoutingCommandServiceTest.republishForBus_returnsIdOfNewlyPublishedVersion` | **P2 동기 계약** — 반환값이 저장된 새 version 의 id, version+1 신규 행(I-5), RECOMMENDED 에서 멈추지 않고 PUBLISHED 까지(D-H), approvedBy·publishedBy 는 촉발한 학부모, 기사 알림 이벤트 발행 |
| `RoutingCommandServiceTest.republishForBus_planStopsFollowActiveRoster` | 재계산 명단은 `ActiveRosterReader` 가 정한다(이번 리팩터링의 seam) |
| `RoutingCommandServiceTest.republishForBus_emptyRoster_throwsInvalidInputAndSavesNothing` | 로스터 0명이면 **조용히 성공하지 않고** 예외로 끝난다 = 호출자가 롤백할 수 있다(2-2 원자성 논거의 근거) |
| `LocationChangeCommandServiceTest.create_withinThreshold_updatesCoordinatesBeforeRecomputing` | **순서 의존** — `republishForBus` 호출 시점에 학생 좌표가 이미 갱신돼 있다. 뒤집히면 옛 좌표로 계산된 노선이 배포되고 응답만 "반영됐다"가 된다 |

`RoutingCommandService.republishForBus` 는 이번 작업 전 테스트가 0건이었다.

---

## 5. 실행한 명령과 실제 출력

```
$ cd backend && ./gradlew compileJava compileTestJava test --rerun-tasks
BUILD SUCCESSFUL in 18s
4 actionable tasks: 4 executed
```

테스트 결과 XML 집계(`build/test-results/test/*.xml`):

```
classes=37 tests=208 failures=0 errors=0 skipped=0
```

직전 기준 36 클래스 / 201 테스트 → **37 클래스 / 208 테스트**(신규 클래스 1개, 신규 테스트 7건), 실패 0.
Docker 는 켜지 않았다(전체 스위트가 Docker 없이 통과).

```
$ git log --oneline -4
e1c8337 docs(backend): 위치변경→재계산 동기 호출을 §6 의 명시된 예외로 규정 [R3-2-3]
b8815f7 docs(backend): 위치변경 쓰기 트랜잭션이 지도 API 를 기다리는 위험을 코드에 기록 [R3-2-2]
a5b8711 refactor(backend): 로스터 조회를 공유 읽기 계층으로 내려 §7 위반 제거 [R3-2-1]
79b46a7 docs(backend): R1 후속 — 퇴원 학생 구멍 처리 완료 반영
```

미추적 파일은 커밋에 넣지 않았다.

---

## 6. 우려사항

1. **2-2 는 문제를 없앤 게 아니라 문서화만 했다.** 커넥션 풀 고갈 위험은 그대로 남아 있다.
   지도 API 가 느려지는 사건이 실제로 나면 이 경로가 먼저 무너진다. 후속 작업으로 올려두는 게 좋다.
2. **평가 보고서의 "네 문제가 함께 사라진다"는 여전히 성립하지 않는다.** 이번에 §6 위반은 "규정된 예외"가 됐고
   §7 위반은 사라졌지만, 트랜잭션 내 외부 HTTP 와 의존 깊이는 남아 있다. 보고서를 그대로 닫지 말 것.
3. **낡은 기록 문서를 손대지 않았다.** `backend/docs/PROJECT_MASTER_PLAN.md`,
   `backend/docs/MVP_RELEASE_TRACKER.md`, `backend/docs/plans/*.md` 가 아직
   `AttendanceQueryService.getActiveRoster` 를 언급한다. 이력 문서라 판단해 그대로 뒀고, 다른 에이전트가
   같은 파일을 만지고 있을 수 있어 충돌을 피했다. 진행추적 문서를 최신화할 거라면 §11~12 갱신 시 함께 처리하면 된다.
4. **`ActiveRosterReader` 는 N+1 조회를 그대로 물려받았다.** 학생마다
   `findByStudentIdAndTargetDate` 를 부른다(옮기기 전과 동일). 이번 범위에서는 동작 보존이 우선이라
   손대지 않았지만, 로스터가 커지면 이 경로도 트랜잭션 점유 시간에 기여한다.

---

# 수정 라운드 1 (2026-08-03) — 리뷰 Important 2건 + Minor 2건

커밋 `5400e69`. 위 본문은 그대로 두고 아래에 덧붙인다.

## 먼저 — 리뷰 지적 2건을 직접 재확인했다

둘 다 **사실이었다.**

```
$ grep -rn "^import src\.backend\..*\.query\..*;" src/main/java/src/backend/*/command/
routing/command/RoutingCommandService.java:42:import src.backend.routing.query.RoutePlanSimulationService;
routing/command/RoutingCommandService.java:43:import src.backend.routing.query.RoutePlanSimulationService.SimulationOutcome;
schedule/command/LocationChangeCommandService.java:20:import src.backend.routing.query.RoutePlanSimulationService;
```

내 직전 커밋 메시지("§7 위반 제거")와 리포트("위반 2건 둘 다 처리")는 `AttendanceQueryService` 건에 한정된 사실인데 전체처럼 읽힌다는 지적이 맞다. 더 중요한 건 내가 새로 쓴 `reference.md` §7 문장이 이 두 지점을 명문 위반으로 만들어 놓고 아무 언급이 없었다는 것 — 규칙을 쓰면서 그 규칙을 어기는 코드를 방치한 상태였다.

**정확한 집계**(리뷰어의 "최소 4건"과 일치, 세는 단위만 명시한다):

| 단위 | 이번 작업 전 | 이번 작업 후 |
|---|---|---|
| Command→Query **주입(의존)** | 3건 | 2건 (둘 다 `RoutePlanSimulationService`) |
| Command→Query **호출 지점** | 4곳 | 2곳 |

## Important 1 — **(b)를 골랐다**: §7 에 예외를 명문화

(a) 공유 읽기 계층으로 내리기는 하지 않았다. 시뮬레이션 델타는 성격이 다르다 — ① 조회가 아니라 지도 API 가 걸린 무거운 계산 ② 결과가 Command 의 판정 근거이자 저장 대상 ③ 같은 계산을 REST 진입점(`simulate`)도 노출. 내리면 **계산이 두 벌**이 되어 외부 API 호출이 배로 늘고, 화면이 본 값과 저장된 값이 갈라진다. `applySimulation` 이 `SimulationOutcome` 을 재사용해 재계산을 피하는 현재 설계가 정확히 그걸 막고 있다.

(c) 백로그로만 남기기도 고르지 않았다 — 이 호출은 "언젠가 고칠 빚"이 아니라 **설계상 유지할 것**이라, 규칙에 예외로 적는 게 정직하다.

쓴 것(§6 예외와 같은 형식 — 판별 가능한 기준 + 의무):
- 판별 기준 3개(무거운 계산 · 판정/저장 입력 · Query 진입점 공유)를 **모두** 만족할 때만 허용
- 의무 ⓐ 그 Query 서비스는 아무것도 저장하지 않는다(`@Transactional(readOnly = true)` 유지) ⓑ 인가 주체를 양쪽 javadoc 에 명시
- "현재 해당은 2곳뿐이며 이 예외에 기대는 새 호출을 늘리지 않는다"를 못 박음
- `RoutePlanSimulationService` 클래스 javadoc 에도 같은 근거를 남겨 코드 쪽에서도 보이게 함

의무 ⓐⓑ는 **이미 충족돼 있다**(세 메서드 전부 `readOnly = true`, `compare`·`computeCandidate`·`computeCore` javadoc 이 인가 주체를 각각 명시). 새로 지킬 것을 만든 게 아니라 이미 지키던 것을 규칙으로 승격했다.

**평가 보고서**(`backend/report/2026-08-02-백엔드-모듈-평가.md`)는 원문을 지우지 않고 처리했다:
- §5 §7 행 끝에 "⚠️ 과소집계였다, 부록 A 참조" 한 마디
- §6-R3 아래에 인용 블록으로 "이 처방은 부분적으로 틀렸다" + 부록 링크
- 문서 끝에 **부록 A** 신설 — A.1 집계 정정(주입 3건·호출 4곳, 원문이 놓친 2가지), A.2 존치 2건의 근거, A.3 R3 네 문제 중 남은 2개(트랜잭션 내 외부 HTTP · 의존 깊이 11)를 백로그로 명시

## Important 2 — SoT 문서 **7곳** 정정 (내가 직접 센 수)

`docs/API_SPEC.md` 3곳(525, 1697, 1698) + `docs/USER_FLOWS.md` 4곳(153, 439, 447, 624) = **7곳**. 리뷰어 집계와 일치한다. 전부 `ActiveRosterReader.forBus` / `.forTenant` 로 **이름만** 바꿨다 — diff 는 7 insertions / 7 deletions 로 서술 변경이 없음을 확인했다. 정정 후 `docs/*.md` 에 `getActiveRoster`·`AttendanceQueryService` 잔존 0건.

내 앞선 리포트 §6-3 이 루트 `docs/` 를 누락한 것은 실수다. CLAUDE.md 기준 루트 `docs/` 4종은 SoT 이고 이력 문서가 아니다.

⚠️ **다만 손대지 않은 것이 남아 있다**: `docs/html/api-spec.html`(3곳)·`docs/html/user-flows.html`(4곳) = **7곳**이 같은 옛 심볼명을 갖고 있다. 이건 `docs/*.md` 의 사람용 HTML 렌더인데, CLAUDE.md 가 *"Markdown 원본을 바꿨다고 대응 HTML 을 자동 동기화하지 않는다 — 필요하면 사용자가 별도로 요청한다"* 고 못 박고 있어 지시 없이 건드리지 않았다. **재생성이 필요하면 별도 지시를 달라.**

## Minor 2건

- `RoutePlanSimulationService.java`(I-9 주석)·`RoutePlanSimulationServiceTest.java`(I-9 회귀 테스트 javadoc)가 부르던 `getActiveRoster` → `ActiveRosterReader.forBus`.
- `ActiveRosterReaderTest` 클래스 javadoc 에 한계를 명시: 퇴원 필터는 리포지토리 **메서드명**(`findBy...AndActiveTrue`)이 담보하는데 그 메서드를 mock 하므로, `...AndActiveTrue` 없는 메서드로 갈아끼워도 이 테스트는 통과한다 — 커밋 `ded41c8`(H-1)이 같은 축의 결함이었고 리포지토리 슬라이스 테스트가 없다는 것까지 적었다.

## Minor 1건은 지시대로 이연 — N+1 은 건드리지 않았다

## 검증

```
$ cd backend && ./gradlew test --rerun-tasks
BUILD SUCCESSFUL in 15s
4 actionable tasks: 4 executed

classes=38 tests=217 failures=0 errors=0 skipped=0
```

⚠️ **기준선이 37/208 에서 38/217 로 움직였는데 내 변경 때문이 아니다.** 작업 중 다른 에이전트가 같은 브랜치에 R1-B 커밋 2건(`776296c`, `e9304a5`)을 올렸고, 거기 포함된 `global/security/authz/RolePermissionsTest`(9 테스트)가 늘어난 전부다(37+1 클래스 / 208+9 테스트). **실패 0** 은 그대로다.

같은 이유로 커밋에는 내 파일만 담았다 — 그 에이전트가 작업 중인 `DriveSessionController.java`·`LocationController.java` 는 미커밋 상태로 그대로 두었다(스테이징하지 않음).

## 남은 우려

1. **`docs/html/*.html` 7곳**이 옛 심볼명 그대로다(위 참조). 지시가 있으면 정정한다.
2. **같은 브랜치에서 다른 에이전트와 동시 작업 중**이라 테스트 기준선이 커밋마다 흔들린다. 최종 통합 시점에 한 번 더 전체 스위트를 돌리는 게 안전하다.
3. 부록 A 가 백로그로 넘긴 **트랜잭션 내 외부 HTTP** 는 여전히 미해결이다.
