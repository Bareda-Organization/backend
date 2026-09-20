# SDD ledger — plan: docs/superpowers/plans/2026-08-21-관리자-운영현황.md

Spec: docs/superpowers/specs/2026-08-21-관리자-운영현황-design.md (읽음)
Branch: feat/mvp-deployment · BASE(Task1) = f75bc06

## Pre-flight 충돌 스캔

| 검사 | 결과 |
|---|---|
| T1 ↔ T2 | 파일 중복 없음(operations/domain vs drivesession/). 병렬 가능 |
| T3 ↔ T4 ↔ T5 | **셋 다 `operations/controller/OperationsController.java` 를 수정** → 순차 필수 |
| T3·T4 → T1 | `BoardingStatusResolver` 시그니처 의존. T1 선행 |
| T3·T5 → T2 | 진행 중 세션 필터 사용. T2 선행 |
| T2 하위호환 | `status` 생략 시 기존 동작 유지 명시됨 — 프론트가 이미 전체 이력을 받아 고르는 구조라 깨지면 안 됨 |
| T6 | 전부 완료 후 |

Ruling(사전): 프론트 화면은 이 계획 범위 밖이다. Task 6 에서 "관리자 웹에서 볼 수 있다"고 적지 않는다.
  — 이유: API 만 생기고 화면은 없다. 이번 세션에서 반복 확인한 문서-현실 어긋남의 전형적 형태다.
  — 틀렸을 때 비용: 다음 세션이 화면이 있다고 오인.

## 진행
Task 1: 완료 (ops-t1, commit 64d8654) — 298개 통과(288+10). BoardingStatus enum + BoardingStatusResolver 순수함수
Task 1: ⚠ **Task 3·4·5 로 반드시 전달할 것** — `resolve()` 가 임계값을 **인자로 받는** 구조라,
  호출부가 `NotificationThresholds` 의 미승차 상수를 실제로 넘기는지 확인해야 한다.
  임의의 값을 넘기면 **화면과 알림이 서로 다른 기준으로 "누락"을 말하게 된다.**
  → Task 3·4·5 브리프에 이 확인을 명시하고, 리뷰 항목에도 넣는다.
Task 1: fix round 1/5 (commit d20469e) — ALIGHT-only 조합 + 임계 직전 경계 테스트 추가. 10 → 12개
  ⚠ 실행 중 Spring 컨텍스트 테스트 9개 실패 → **환경 문제**(postgres 크래시 후 recovery mode). 구현자가 정확히 분류.
  컨트롤러가 복구 확인 후 `./gradlew test --rerun-tasks` 로 **305개 전부 통과** 직접 확인
Task 2: 리뷰 완료 (ops-t2-review) — 스펙 ✅ / 품질 승인 (Important 1 · Minor 1)
  확인: 하위호환 위임 · DB 레벨 WHERE 필터(메모리 아님) · 기존 메서드와 중복 아님 ·
  @PreAuthorize 가 진입 전 걸려 status 추가가 인가 우회 경로를 안 만듦 · Swagger 낡은 서술 정정됨
Task 2: Important — 관리자 엔드포인트 테스트가 **이번 작업 이전부터 0개**(간접 커버조차 없음).
  Ruling: **Task 3·5 착수 전에 메운다.** 이유 — 두 Task 가 바로 이 관리자 조회를 쓴다. 검증 안 된 경로 위에 쌓으면 안 된다.
Task 2: fix round 1/5 (commit 0082a3a) — 관리자 경로 테스트 7개 추가(쿼리서비스 4 + 컨트롤러 3). 312개 통과
  **TenantGuard 학원 격리**(본인 소속 해석 + 타 학원 999L → FORBIDDEN)를 처음으로 고정
Task 2: minor (deferred): `?status=BOGUS` 가 400 아닌 500 — MethodArgumentTypeMismatchException 핸들러 부재.
  MON-15 와 동일한 전역 갭이며 쿼리 파라미터도 같은 메커니즘. MON-15 처리 시 함께 잡는다
Task 1: fix round 1/5 (2 addressed, 0 open; commit d20469e) — 임계 3점(직전 9:59 PENDING · 정각 10:00 MISSED · 초과 10:01 MISSED)
  재리뷰 확인: `>=` 가 `>` 로 뒤집히면 정각 케이스가 실패하는 형태 · ALIGHT-only javadoc 근거가 실제 코드와 일치
Task 1: complete (commits 609416c..d20469e, review clean, 0 parked)
Task 2: fix round 1/5 (4 addressed, 0 open; commit 0082a3a)
  재리뷰 확인: TenantGuard 테스트가 **실제 게이트**(belongsToTenant(999L)=false → FORBIDDEN, mock 무마 아님) ·
  403 도 @CanMonitorOperations 권한 불일치로 실제 발생 · 껍데기 테스트 0건
Task 2: complete (commits 64d8654..0082a3a, review clean, 1 deferred minor)
Task 3: 착수 (동시 2개 제한 준수 — mon-final-fix 가 Gradle 사용 중이라 Task 5 는 그다음)

=== 기획 문서 대조로 설계 개정 (2026-08-21) ===
사용자가 docs/brainstorming/ 에 `바래다 PRD.dc.html`·`바래다 기능정의서.dc.html` 추가. 대조 결과:
  (1) **상태 모델이 어긋남** — 기획 C-02 확정 정책은 waiting·boarded·alighted·**absent**·no_show 5종인데
      내 초안에 `absent` 가 아예 없었다. absent(사전 OFF, 학부모 알림 없음)와 no_show(현장 미승차,
      즉시 알림+3분 에스컬레이션)는 **알림 대상·책임 소재가 다르다.**
  (2) **MON 도메인 7개 중 3개 누락** — MON-04(미승차 모아보기)·MON-05(당일 수정 노선)·MON-06(탑승 의사 집계)
  (3) **MON-05 절반은 구현 불가** — 기사·인솔자 변경 확인 응답(RUN-07)의 대응 엔티티·API 가 코드에 전무
Ruling: 설계(0dca1fb)·계획(b8192c8) 개정 + **Task 2R 신설**로 이미 완료된 Task 1 의 상태 모델을 바로잡는다.
  — 이유: Task 3 을 그대로 착수했으면 기획과 어긋난 API 위에 Task 4·5 를 쌓았을 것이다.
  — 틀렸을 때 비용: Task 1 재작업 1회. 지금 되돌리는 편이 훨씬 싸다.
Ruling: 기획이 전제하나 현재 코드에 없는 개념(회차 자동생성·3구간 규칙·미경유 정류장·탑승 의사 토글·
  변경 확인 응답)은 **"구현됨"으로 적지 않고 간극으로 남긴다**(설계 §5.4).

Task 2R: 완료 (ops-t2r, commit 5b8971a) — 318개 통과(312+6). 18개 테스트(기존12 개정 + ABSENT 우선순위3 + isStopReached3)
Task 2R: ⚠ **Task 3·4·5·5B·5C 로 반드시 전달** — `resolve()` 에 `approvedAbsenceToday` 인자가 추가됐다.
  **호출부가 당일 승인된 결석을 조회해 넘기지 않으면 `ABSENT` 가 영원히 안 나온다.**
  그러면 결석 승인된 학생이 임계 초과 시 `NO_SHOW` 로 표시되고, 관리자가 이미 안 탄다고 알린 학부모에게 전화하게 된다.
  → 각 Task 브리프와 리뷰 항목에 이 확인을 명시한다.
Task 2R: 리뷰 완료 (ops-t2r-review) — 스펙 ✅ / 품질 **승인** (Critical 0 · Important 0 · Minor 0)
  검증: 기획 C-02 원문 직접 대조 · 참조처 전수 검색으로 UPCOMING/PENDING/MISSED 잔존 0건 ·
  ABSENT 우선순위 3개 테스트(alighted 기록 있어도 · 임계 초과여도 · 세션 미시작이어도 ABSENT) ·
  javadoc 3종 유지 확인 · 임계 3점 로직·경계 불변 · 순수함수 원칙 유지
Task 2R: complete (commit 5b8971a, review clean, 0 parked)
Task 3·5: 병렬 착수 (컨트롤러 분리로 가능. 모니터링 종료로 동시 2개 여유 확보)
Task 3: 완료 (ops-t3, commit 2e0c337) — 신규 테스트 12개 통과
  ⚠ 전체 회귀에서 실패 1건(OperationsSummaryQueryServiceTest) — **ops-t5 의 미커밋 작업 중 파일**이며
  Task 3 커밋에 미포함. 병렬 실행의 정상적 중간 상태. ops-t3 가 do-not-touch 를 지켜 미수정한 것이 옳음
Task 3: minor (deferred): RoutePlan.stops 지연로딩 잠재 N+1 — 브리프 범위 밖, 설계상 수용
Task 3: 리뷰 완료 (ops-t3-review) — 스펙 ✅ / 품질 **승인** (Critical 0 · Important 0 · Minor 2)
  ★ ABSENT 조회 실증: attendanceExceptionRepository 를 학원 전체 1회 조회 → APPROVED+당일 필터 →
    학생 id Set → resolve() 에 전달. 하드코딩 false 아님. **결석자를 명단에서 빼지 않고 상태로 표시**(설계 §3 요구 그대로)
  ★ 테스트가 absent/no_show 를 **같은 조건에서 대비**: 세션 20분 전 시작 + 결석승인 → absent=1,noShow=0 /
    결석승인만 없음 → noShow=1,absent=0. **결석 유무 하나 차이로 분류가 뒤집히는 것을 고정**
  ★ N+1 테스트가 진짜: verify(times(1)) 6개 + verify(never()) 로 개별조회 미호출 확인.
    세션 조회만 times(2)(상태 수에 비례, 버스 수 아님)
  임계값 NotificationThresholds.NO_SHOW 실참조 확인 · stale 두 케이스 테스트 · attendantMissing 별도 플래그
Ruling: 리뷰어가 **내 설계 문서 오류**를 발견 — §3 만 개정하고 §2·§4.1 의 구 상태명을 안 고쳤다. 즉시 정정.
  — 이유: 이번 세션 내내 고쳐온 문서-코드 어긋남을 내가 새로 만든 것이다. 구현은 최신 명칭을 따랐으므로 코드 영향은 없다.
  — 틀렸을 때 비용: 없음(문서 내부 불일치만 해소).
Task 3: complete (commit 2e0c337, review clean, 2 deferred minor)
Task 5: 완료 (ops-t5, commit f89b6ca) — 신규 17개 통과, 전체 347개 실패 0 (앞선 실패 해소됨)
Ruling: `dispatch.crewAssignment[]` 미포함(`busesWithoutCrew[]` 로 대체)한 구현자 판단을 **수용**한다.
  — 이유: 배치 인력 자체는 Task 3 의 `crew` 필드가 담아 요약에 또 넣으면 중복이다.
    `busesWithoutCrew[]` 는 **조치가 필요한 것만** 추려 지표 카드 목적에 더 맞다(MON-01 "배치 대기").
  — 틀렸을 때 비용: 화면이 전체 배치 현황을 한눈에 보려면 버스 목록 API 를 함께 호출해야 함.
Task 5: ⚠ 병렬 작업 위험 발현 — ops-t3 가 ops-t5 의 테스트 파일에 있던 컴파일 차단 버그
  (BusLocationPing 5→6 인자)를 디스크상에서 먼저 고쳤고, 두 에이전트 변경이 한 파일에 섞였다.
  결과적으로 무해했으나(컴파일 차단 해소) **같은 파일을 두 에이전트가 만질 수 있는 구조 자체가 위험**이다.
  → 이후 병렬 배치 시 파일 집합을 더 엄격히 가른다.
Task 5: 리뷰 (ops-t5-review) — 스펙 ✅ / 품질 **미승인** (Critical 0 · Important 1 · Minor 1)
  Important: 보험 2단계 경계 미테스트 — 코드는 `isBefore(today)`/`<=30` 인데 테스트가 -6·+10·+90 일만 써
    **부등호가 뒤집혀도 통과**. 내가 브리프에서 강조한 경계가 정작 안 잡혔다
  Minor: `expected = assignedCount - absent` 에서 absent 가 배정 학생을 안 걸러 과소평가 여지
  좋은 점: busesWithoutCrew 가 missingDriver/missingAttendant 완전 구분(4조합 테스트) ·
    delayedBuses 가 필드명(delaySecondsEstimate)·Javadoc·@Operation 3곳에 추정 명시 ·
    미구현 항목을 빈 필드 없이 헤더 Javadoc 에만 기록 · N+1 없음
Ruling: **만료 당일(`insuranceExpiry == today`)은 `expiring` 이 맞다.**
  — 이유: 보험 계약은 통상 만료일 당일까지 유효하다. 그날 운행은 적법하고 다음 날부터 무보험이다.
    따라서 즉시 운행 중단(expired)이 아니라 오늘 안에 갱신(expiring) 대상이다. **근거를 코드 주석에 남기게 함.**
  — 틀렸을 때 비용: 만료 당일 차량이 "임박" 목록에 있어 관리자가 하루 늦게 조치할 가능성.
Task 5: fix round 1/5 (commit eca0a0d) — 경계 테스트 3개(0·30·31일) + expected/absent 필터 + 근거 주석. 351개 통과
Task 5: minor (deferred): `longRunningSessions` 의 3시간 임계는 판단값 — 실 운행 데이터로 재검토 필요
Task 5: fix round 1/5 — **컨트롤러가 직접 검증**(재리뷰어가 두 번 무응답)
  ✅ 경계 3점 실재: compliance_expiryIsToday_... / _daysRemaining30_... / **_daysRemaining31_excludedFromBothLists**
     31일 테스트의 javadoc 이 "이 케이스가 없으면…" 이라고 **왜 필요한지까지** 기술
  ✅ 근거 주석(:281-285): "보험은 통상 만료일 당일까지 유효 → 그날 운행은 적법, 다음 날부터 무보험.
     isBefore(today) 를 쓰는 이유가 이것이며 isEqual 이하로 바꾸면 근거가 깨진다"
  ✅ expected/absent 필터 반영
Task 5: minor (deferred): 테스트가 `LocalDate.now()` 13곳 사용 — 상대 날짜(plusDays)라 계산은 안정적이나
  자정을 걸쳐 실행되면 이론적으로 하루 어긋남. 고정 시계 주입이 정석이나 이번 범위 밖. 최종 리뷰에서 triage
Task 5: complete (commits 2e0c337..eca0a0d, 검증 완료, 2 deferred minor)
