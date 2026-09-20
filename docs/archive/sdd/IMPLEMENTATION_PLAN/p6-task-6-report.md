# T6 — 동승자 자동 배정(AttendantAssigner) 완료 보고

## 1. 판단 근거

- **경계 시각 — 근무 시간 커버는 양끝 포함, 중복 배치 겹침은 반개구간.** `docs/` 에 이 축을 직접 다룬 문장은 없어 두 판정을 서로 다른 근거로 결정했다.
  - 근무 시간(`coversWindow`): 기존 `WorkHours.covers()`(Phase 5, 점 시각 판정)가 이미 양끝 포함이다 — "07:00 출발 회차를 07:00~10:00 근무자에게 붙이는 것은 정상 배치"라는 근거가 그대로 구간에도 적용된다. 종료가 근무 종료와 정확히 같은 배치를 밖으로 치면 경계에 걸친 모든 정상 배치가 자동 배정에서 매번 걸러진다.
  - 중복 배치(`BusyWindow.overlaps`): 반대로 **반개구간**을 택했다 — 앞 회차 종료와 새 회차 시작이 맞닿는 것은 정상 운영 패턴(맞배치)이고, 이를 충돌로 잡으면 실제로는 문제없는 배차가 매번 걸린다. 두 판정이 같은 "경계 포함"으로 통일돼야 한다는 근거는 없었고, 오히려 각 판정이 막으려는 사고(근무 시간 밖에 배치 vs 물리적으로 겹치는 시간대에 이중 배치)가 다르므로 반대 방향을 택했다.
- **WorkHours.coversWindow() 를 기존 manager 모듈 엔티티에 추가**(새 클래스로 재구현하지 않음) — `WorkHours` 가 자신의 저장 형태(`byWeekday`)에 대한 interval-겹침 판정의 자연스러운 소유자라고 보았고, 브리프의 "재사용, 재생성 금지" 를 "기존 클래스에 쿼리 메서드 하나를 더하는 것"까지 포함하는 것으로 해석했다. 저장 형태·기존 메서드는 건드리지 않았다.
- **선정 알고리즘**: 후보 전원을 끝까지 평가하고(첫 통과자에서 조기 종료하지 않음), 목록 순서상 첫 완전 통과자를 `managerId` 로 선정하되 그 뒤 후보도 계속 판정해 `rejections` 에 남긴다 — 담당자가 자동 배정 결과를 손으로 고칠 때 "왜 이 사람이 아니었나" 를 전원에 대해 볼 수 있어야 한다는 근거. 포트 계약 문서에는 이 알고리즘이 명시돼 있지 않다.
- **한 후보가 두 판정에 모두 걸리면 근무 시간을 먼저 본다** — 근무 시간 밖은 애초에 그 회차를 몰 수 없는 사람이고, 중복 배치는 "몰 수는 있으나 이미 다른 곳" 이라는 다른 종류의 결격이라 전자를 우선했다.
- **`workHours == null` → `OUT_OF_WORK_HOURS`**: 포트 계약의 `RejectReason` 이 2종뿐이라(수동 배치 경로의 `WORK_HOURS_NOT_SET` 같은 3번째 값이 없음) 판정 근거 부재를 근무 시간 밖으로 흡수했다.
- **자정을 넘는 회차는 시작·종료 요일이 갈리면 즉시 `OUT_OF_WORK_HOURS`** — `WorkHours` 자체가 자정을 넘는 구간을 저장하지 않으므로(Ruling, 기존 `Interval` 검증) 어떤 근무 구간도 그런 창을 커버할 수 없다는 것이 근거다.
- **구현 클래스명 `SequentialAttendantAssigner`**: 저장소에 `Default*` 접두 관례가 없고, 다른 단일 전략 클래스(`HeuristicRouteEngine`, `DailyStopResolver`)가 동작 방식을 이름에 담는 관례를 따랐다.

## 2. 우려 · 확신 없는 지점 · 지시와 다르게 판단한 것

- **`WorkHours` 에 메서드 추가가 "재사용" 의 경계를 넘었을 가능성.** 브리프는 "기존 엔티티·레포지토리 재사용, 재생성 금지" 를 명시했는데, 새 메서드 추가는 재사용과 확장의 중간 지점이다. 저장 형태·기존 동작은 바꾸지 않았지만, 리뷰어가 이를 모듈 경계 침범으로 볼 수 있어 명시적으로 표시한다.
- **`BusyWindow` 타입은 포트 계약 문서(`p6-port-contracts.md §3`)에 정의가 없다** — `AttendantCandidate.alreadyAssigned()` 가 `List<BusyWindow>` 를 요구하는 것만 명시돼 있어 직접 설계했다(생성자 검증: `start < end` 필수, `overlaps()` 반개구간). 계약 문서를 고치지 않고 계약이 요구하는 타입만 새로 만드는 것으로 판단했으나, 이 타입의 최종 소유권·검증 규칙이 코디네이터의 의도와 일치하는지는 확인받지 못했다.
- **선정 알고리즘(전원 평가 후 첫 통과자 선정)은 계약에 명시되지 않은 판단**이다 — "후보 여러 명 중 어떻게 고르나" 는 계약이 답을 주지 않아 §1 에 적은 근거로 직접 정했다. 다른 정책(예: 근무 시간이 가장 여유로운 후보 우선)이 실제 의도였을 가능성을 배제할 수 없다.
- **음성 대조 1(근무 시간 검사 항상 통과)에서 예상보다 많은 4개 테스트가 실패**했다(`rejectsWhenRunWindowExceedsWorkHoursByOneMinute`, `allCandidatesRejectedLeavesManagerIdNullWithAllReasons`, `skipsRejectedCandidateAndSelectsNextWhileKeepingRejection`, `reportsOutOfWorkHoursBeforeAlreadyAssignedWhenBothApply`). 브리프의 경고("여러 개면 파고들어라")에 따라 검토한 결과, 4개 전부가 `OUT_OF_WORK_HOURS` 판정 로직을 **서로 다른 시나리오로 독립적으로** 겨냥하고 있어(경계 초과·전원 거절·순서 건너뛰기·우선순위) 다른 Phase 6 태스크에서 실제로 있었던 반례(좌표 순서로 우연히 걸림, 앞 가드가 뒤 가드를 가림)와는 다른 형태라고 판단했다. 다만 이 판단은 내 자체 평가이고 리뷰가 별도로 재현·판정해야 한다.

## 3. 실측 3줄

- 커밋: `778b910` (`feat(routing): 동승자 자동 배정(AttendantAssigner) 구현 — 목표 9`), 워크트리 `wt-p6t6` 브랜치 `p6-t6`, 분기점 `b63716f` 일치 확인.
- 실패 테스트 클래스: 없음 — GREEN 상태에서 `SequentialAttendantAssignerTest` 전항 통과, 음성 대조 종료 후 재확인도 `BUILD SUCCESSFUL`.
- 테스트 수: `SequentialAttendantAssignerTest` 11개(전항 통과, GREEN 최초 1회 · 음성 대조 4회 각각의 원복 후 재확인 1회 총 6회 실행 모두 클린 상태에서 11/11).

## 4. 심은 변형 목록

1. `withinWorkHours()` 의 `coversWindow` 호출을 조용히 `return true` 로 대체(근무 시간 검사 항상 통과).
2. `overlapsAssignment()` 의 시간대 겹침 조건을 제거하고 `BusyWindow.start().toLocalDate()` 가 회차 시작일과 같으면 무조건 충돌로 판정(범위 한 칸 넓히기).
3. `assign()` 진입부에 `input.candidates().isEmpty()` 이면 `IllegalStateException` 을 던지는 분기 추가(0명 격리를 예외로 대체).
4. `assign()` 에서 `runEnd = runStart.plusMinutes(estDurationMin())` 대신 `runEnd = runStart` 로 대체(소요 시간 무시, 출발 시각만으로 판정).

각 변형은 개별 적용 → 테스트 실행 → 실패 확인 → 원복 → `git status --porcelain` 클린 확인의 순서로 진행했다.
