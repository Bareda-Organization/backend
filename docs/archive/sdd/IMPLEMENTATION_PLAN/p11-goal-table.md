# Phase 11 목표 표 — 예외 · 비상 알림

**작성 2026-09-01 · 착수 전 고정.** 분기점은 착수 시점의 `feat/baraeda-rebuild` HEAD.

⚠ **이 표는 파생본이다.** 정본은 `docs/IMPLEMENTATION_PLAN.md` Phase 11 절과 `docs/API_SPEC.md`. **어긋나면 정본이 이긴다.** 아래 수치는 조율자가 정본에서 직접 센 값이니 착수 시 다시 세어 대조하고 어긋나면 보고하라.

---

## 0. ⚠ 착수 전 재계수 — 조율자가 이미 확인했다. **없는 것을 새로 만들지 마라**

Phase 10 에서 이월 note 의 낡은 "없음" 이 이미 있는 것을 새로 만들 뻔한 전례가 있다(Ruling 206). 그래서 먼저 센다.

### 이미 있는 것 — 재사용한다

| 층 | 실재 | 위치 |
|---|---|---|
| **테이블 5종** | `academy_setting` · `no_show_case` · `no_show_contact` · `emergency_alert` · `exception_report` | `V1__init_schema.sql` (Phase 1 산출물) |
| **엔티티 9개** | `EmergencyAlert` · `EmergencyType` · `ExceptionReport` · `ExceptionReportType` · `NoShowCase` · `NoShowContact` · `NoShowDecision` · `ContactAttemptType` · `ContactResult` | `exception/entity/` |
| **엔티티 1개** | `AcademySetting` | `academy/entity/` |
| **저장소 1개** | 🔴 **`NoShowCaseRepository` 가 이미 있다** | `exception/repository/` |

```bash
find backend/src/main/java/src/backend/exception -name '*.java'   # 기대 10개(entity 9 + repository 1)
grep -n 'CREATE TABLE' backend/src/main/resources/db/migration/V1__init_schema.sql | grep -iE 'emerg|no_show|exception|academy_setting'
```

### 부재한 것 — 이번에 만든다

`exception` 모듈의 **서비스 · 컨트롤러 · DTO** 전부. 저장소는 `NoShowCase` 것만 있고 **`EmergencyAlert`·`NoShowContact`·`ExceptionReport` 것이 부재**. `AcademySetting` 은 **엔티티만** 있고 저장소·서비스·컨트롤러가 부재.

⚠ **신설 마이그레이션은 `shedlock` 테이블 하나뿐이다.** 나머지 테이블은 이미 있다. **다음 번호는 `V4`** — `V1`·`V3`(`migration/`)와 `V2`(`migration-local/`)가 이미 있어 **번호가 겹치면 `local` 프로파일에서 Flyway 가 기동을 거부한다**(Phase 10 T3 이 실제로 밟았다).

---

## 1. 완료 조건 — **13항.** 정본 10항 + Ruling 212 의 2항 + 전체 실측 1항

| # | 무엇이 통과하면 끝인가 | 근거 | 좌석 |
|:-:|---|---|:-:|
| **1** | `no_show` 처리 후 **학원별 대기 시간**이 경과하고 무응답이면 관계자에 보고(`no_show_escalated`) | `EXC-01` · `M-13` | T1 |
| **2** | **학원별로 다른 대기 값이 각각 적용됨** — 시드 2학원으로 검증. ⚠ **코드 상수로 박으면 이 항이 거짓**이 된다 | `A-17` · `TECH_DECISIONS §12.2` | T1 |
| **3** | 연락 시도 이력이 **`no_show_contact` 에 적재**. `result=answered` 면 카운트다운 중단 | `§4.8` | T1 |
| **4** | `GET`·`PATCH /staff/academy-settings` 로 `no_show_wait_minutes` 조회·수정. **허용 범위 밖 값은 `422 VALIDATION_FAILED`** | `§5.21` | T1 |
| **5** | **기사·동승자 둘 다** 비상 발신 성공 — 역할 제한 부재 | `§4.14` · `M-15` | T2 |
| **6** | 비상 알림이 **관계자·메인 관리자에 동시** 수신되고 **알림 설정과 무관하게** 발송 | `§5.16` · `§6.11` · `C-17` | T2 |
| **7** | **학부모·학생 계정은 비상 알림 미수신** | `C-17` | T2 |
| **8** | 발신 시점 **위치·회차·호차·연락처가 자동 첨부** | `M-15` | T2 |
| **9** | 발신 **1분 이내 취소 성공, 1분 초과 거절.** 취소 사실도 통지되고 **이력은 존치** | `§4.14` | T2 |
| **10** | 관계자 `[확인]` 응답이 **발신자 앱에 반영**되고 처리 이력 적재 | `§5.16` · `A-16` | T3 |
| **11** | 관계자 **미확인 상태가 메인 관리자 콘솔에 경과 시간과 함께** 노출(`staff_acked` · `elapsed_since_raised`) | `§6.11` · `O-07` | T3 |
| **12** | **인스턴스 2개가 같은 스케줄러 주기를 돌 때 판정이 1회만 수행** — 락 미획득 쪽은 수행 없이 건너뜀 | **Ruling 212** · `TECH_DECISIONS §3.2` | T4 |
| **13** | **락 보유 인스턴스가 죽어도 다음 주기에 다른 인스턴스가 이어받음** — 영구 점유 부재 | **Ruling 212** | T4 |
| **14** | 전체 테스트 묶음 **단독 실행** 실패 0 (동시성 시험의 부하 의존 실패는 별도 분류) | 횡단 규칙 22 | 조율자 |

⚠ **정본 대조 결과 — 완료 조건 10항이 §4.13(예외 보고 쓰기)·§5.20(읽기)을 덮지 않는다.** 범위(`EXC-02`·`EXC-03`·`M-14`)에는 있는데 완료 조건에 문면이 부재하다. **Phase 10 의 Ruling 211 과 같은 형태**(범위보다 좁은 완료 조건)라 아래 2항을 신설한다.

| # | 무엇이 통과하면 끝인가 | 근거 | 좌석 |
|:-:|---|---|:-:|
| **15** | `POST /runs/{runId}/reports` 로 보호자 부재(`guardian_absent`)·현장 상황 보고 접수 → **`201` + 관계자 즉시 통지.** `type=guardian_absent` 면 `rider_id` 필수 | `§4.13` · `EXC-02`·`EXC-03` | T3 |
| **16** | `GET /staff/reports`·`/{id}` 로 조회 — **`type`·`date`·`run_id` 필터** 동작 | `§5.20` · `M-14` | T3 |

**최종 16항.**

---

## 2. 신설 핸들러 — **11개 예상 (78 → 89).** 정본에서 직접 셈

| # | 엔드포인트 | 절 | 권한 |
|:-:|---|---|---|
| 1 | `POST /runs/{runId}/riders/{riderId}/no-show-contacts` | `§4.8` | **동승자 전용** |
| 2 | `POST /runs/{runId}/reports` | `§4.13` | 기사·동승자 |
| 3 | `POST /runs/{runId}/emergency` | `§4.14` | 배치된 기사 **또는** 동승자 |
| 4 | `DELETE /runs/{runId}/emergency/{id}` | `§4.14` | 〃 |
| 5 | `GET /staff/emergencies` | `§5.16` | 학원 관계자 |
| 6 | `POST /staff/emergencies/{id}/ack` | `§5.16` | 학원 관계자 |
| 7 | `GET /staff/reports` | `§5.20` | 학원 관계자 |
| 8 | `GET /staff/reports/{id}` | `§5.20` | 학원 관계자 |
| 9 | `GET /staff/academy-settings` | `§5.21` | 학원 관계자 |
| 10 | `PATCH /staff/academy-settings` | `§5.21` | 학원 관계자 |
| 11 | `GET /admin/emergencies` | `§6.11` | 메인 관리자 |

⚠ **11개를 만들면 계정 상태 게이트 거부측 목록에도 11줄이 늘어야 한다.** `AccountStatusGateEndpoints.DENIED_WHEN_REJECTED` + `AuthFlowIntegrationTest` 의 `hasSize(78)` → **89**. **Phase 9·10 에서 연속으로 누락된 자리다** — 좌석들이 그 파일을 안 건드려 충돌이 안 나고 조용히 빠진다. **개수 단언이 유일한 탐지 수단이다.**

---

## 3. ⚠ 이 Phase 에서 가장 의심스러운 지점

### 3.1 대기 시간을 **코드 상수로 박으면 목표 2 가 거짓**이 된다

`§4.8` 문면이 *"**3분** 경과 후 최종 판단"* 이라 **고정값처럼 읽힌다.** 그러나 `FEATURE_SPEC A-17`·`ERD:986` 은 **"기본 3분, 학원별 조정"** 이고 `academy_setting.no_show_wait_minutes` 가 그 저장소다. **3분은 기본값이지 상수가 아니다.**

- **시드 2학원에 서로 다른 값을 넣고 각각 적용되는지** 검사하라. 한 학원만 검사하면 상수를 박아도 통과한다
- 전역 상수(30분 · ±10분 · 14일 · 5회)는 **이 설정의 대상 밖**이다. 학원이 바꿀 수 있게 하면 사양이 흔들린다

### 3.2 "무엇이 일어나지 않는다" 형태가 또 있다 — **목표 7**

*"학부모·학생 계정은 비상 알림 미수신"* 은 **알림 발송이 통째로 죽어 있어도 통과**한다.

⚠ **짝인 존재 단언을 같은 시험 안에 두어라** — **관계자·메인 관리자는 받는다**(목표 6). 두 갈래를 각각 심어라: ①수신 대상 필터를 지우면 **부재 단언이 실패** ②발송 호출을 no-op 으로 하면 **존재 단언이 실패**. 둘 다 잡혀야 성립한다.

### 3.3 스케줄러는 **판정 로직만 검사되기 쉽다** — Phase 10 이월 ②

Phase 10 의 `ProximityNotificationScheduler` 가 **부르는 쪽이 통째로 미검증**인 채 끝났다(R3 지적). 이번 미승차 에스컬레이션 스케줄러도 같은 형태다.

- **판정 메서드 직접 호출**뿐 아니라 **배치 계층**(대상 필터 · 배치 크기 · 회차별 예외 격리)을 검사하라
- 본보기 — `RunConfirmationSchedulerTest` 가 `scheduler.confirmDueRuns()` 를 직접 호출한다
- ⚠ **Phase 10 의 그 공백도 이번에 함께 메울지 판정하라**(이월 ②). 범위를 늘리는 것이 맞는지 근거와 함께 보고

### 3.4 ShedLock 은 **새 의존성 + 새 테이블 + 전 스케줄러 배선**이다

- 의존성 좌표는 `TECH_DECISIONS §3.2` 에 있다. **거기서 읽어라**
- **`shedlock` 테이블은 `V4`** 로 만든다(§0 참조)
- ⚠ **"전 스케줄러 배선" 의 대상을 먼저 세라** — `grep -rln '@Scheduled' backend/src/main/java`. 확정 배치는 조건부 UPDATE 로 이미 안전하다고 정본이 명시하므로 **그 자리에 락을 덧붙일지도 판정 대상**이다

  ⚠ **정정 (2026-09-01, T4 실측) — ~~`@Scheduled` 보유 8파일 중 실제 주기 작업 6개 · 관측·캐시 2개~~ → 실제는 **5개 + 3개**.**

조율자의 `grep -rln '@Scheduled'` 가 **자바독의 `{@code @Scheduled}` 인용까지 세었고**(`phase-goal-loop §5.1`), 아래 표에 **아직 만들어지지 않은 미승차 에스컬레이션 스케줄러를 한 행으로 넣고** 그것을 8 안에 포함해 셌다(`phase-goal-loop §6` — 행 수 ≠ 항목 수). **`SchedulerHealthMetrics`·`ScheduledTaskMetricsAspect`·`ApprovalPreviewCache` 는 애너테이션이 0건**이다. 좌석에게 실은 *"착수 시 다시 세어 대조하라"* 가 이 오기를 잡았다.

  | 파일 | 락 대상인가 |
  |---|---|
  | `run/scheduler/RunConfirmationScheduler` | ⚠ **정본이 "조건부 UPDATE 로 이미 안전"** 이라 명시한 확정 배치. 덧붙일지 판정 |
  | `location/scheduler/ProximityNotificationScheduler` | ✅ **정본이 이름으로 지목**(`TECH_DECISIONS §3.2`) |
  | `schedule/scheduler/DailyRunGenerator` | 판정 필요 |
  | `notification/scheduler/NotificationOutboxWorker` | 판정 필요 |
  | `request/scheduler/ChangeRequestAutoRejectionScheduler` | 판정 필요 |
  | **이번에 만들 미승차 에스컬레이션 스케줄러** | ✅ **정본이 이름으로 지목** |
  | `observability/metrics/SchedulerHealthMetrics` · `observability/aspect/ScheduledTaskMetricsAspect` · `request/preview/spec/ApprovalPreviewCache` | 관측·캐시 — **대상 밖일 가능성.** 열어서 확인하라 |
- 목표 12·13 을 **어떻게 검사할지가 이 좌석의 최대 난점**이다. 인스턴스 2개를 실제로 띄우는 대신 **락 획득·미획득 두 경로를 각각 태우는** 방식이 현실적이다 — 무엇을 택하든 근거를 보고서에 적어라

### 3.5 `RiderNoShowEvent` 경로의 방송 — Phase 10 이월 ⑤ **여기서 판정한다**

`RiderChangedBroadcastListener` 자바독이 *"미승차로 잔여 0명 도달 경로는 다루지 않았다"* 고 스스로 적었다. **`API_SPEC §7.1` 의 `rider_changed` 트리거 표를 직접 대조**해 `no_show` 가 트리거인지 판정하라. 트리거면 이번 범위이고, 아니면 그렇게 보고하라.

---

## 4. 좌석 분할 — 4개 병렬

⚠ **2026-09-01 정정 (Ruling 214) — 아래가 유효한 분할이다.** 폐기된 값은 이 절 끝에 적었다.

| 좌석 | 담당 목표 | 소유 테이블 | 신설 핸들러 | 게이트 |
|:-:|---|---|:-:|:-:|
| **T1** | 1·2·3·4 — 미승차 에스컬레이션 · 학원 설정 | `no_show_case` · `no_show_contact` · `academy_setting` | 3 | `hasSize(81)` |
| **T2** | 5·6·7·8·9·**10·11** — 비상 알림 **전량** | `emergency_alert` | 5 | `hasSize(83)` |
| **T3** | **15·16** — 예외 상황 보고 | `exception_report` | 3 | `hasSize(81)` |
| **T4** | 12·13 — ShedLock · 전 스케줄러 배선 | `shedlock`(신설 `V4`) | 0 | 미변경 |

**분할 기준은 테이블 소유권이다 — 한 테이블을 쓰기하는 좌석은 하나뿐이다.**

### 폐기된 분할과 그 이유

~~T2 = 목표 5·6·7·8·9(발신·취소) · T3 = 목표 10·11·15·16(확인 응답·관제 노출·예외 보고)~~

이 분할은 **T2 와 T3 가 `emergency_alert` 를 둘 다 쓰기**하게 만든다 — T2 가 `cancel()`, T3 가 `ack()` 를 **같은 엔티티 파일**에 더하고 **`EmergencyAlertRepository` 를 둘이 신설**한다. 옛 §4 는 이것을 *"T3 이 T2 의 `emergency_alert` 를 **읽는다**"* 로 적었으나 `ack` 는 읽기가 아니라 쓰기다.

**Phase 10 의 Critical 이 정확히 이 형태였다** — 좌석 경계에 계약이 명시되지 않아 세 좌석이 각자 합리적인 포맷을 골랐고 전 시험이 초록인 채 운영 파손이 남았다. 그때의 해소책은 "계약을 명시하고 전파" 였는데, **이번 경계는 계약 전파로 막히지 않는다** — 값이 아니라 **같은 파일을 둘이 고치는 것**이 문제이기 때문이다. 그래서 소유권을 T2 에 몰았다.

⚠ **목표는 하나도 늘거나 줄지 않았다.** 16항 그대로이고 좌석 배정만 옮겼다(`phase-goal-loop §1` 은 목표 증감을 막는 규칙이지 작업 분배를 고정하는 규칙이 아니다).

⚠ **남은 좌석 간 결합 2건은 발주문에 명시했다.**

| 결합 | 처리 |
|---|---|
| T1 의 새 스케줄러가 **T4 의 ShedLock 배선 대상** | T1 은 락 없이 만들고 자바독에 남긴다. T4 는 배선 명세(애너테이션 형태·`name` 규칙·`lockAtMostFor` 근거)를 보고한다. **조율자가 병합 후 수정 라운드로 부착**시킨다 |
| T3 의 "관계자 즉시 통지" 가 **`NotificationType` 18종에 부재**할 수 있다 | 새 값이면 `notification_log.type` CHECK 마이그레이션이 필요한데 **`db/migration/` 은 T4 단독 소유.** T3 은 `§9.7` 대조 후 **조율자에게 보고하고 멈춘다** |

## 5. 좌석 공통 규칙 — 8항

1. 착수 전 `git log --oneline -1` 이 **기대 분기점**인지 확인. 다르면 즉시 `BLOCKED` 보고
2. **`git add` 는 파일 경로로.** `-A` · `.` 금지. 오류를 `2>/dev/null` 로 가리지 마라. 커밋 후 `git show --stat` 의 `insertions` 가 0 이 아닌지 확인
3. **서브에이전트를 띄우지 마라**
4. **결함 심기는 커밋 후에.** 변형 1회 = 원복 1회 = `git status --porcelain` 확인 1회를 묶어 돈다
5. 보고서는 **4항만** — ①판단 근거(고른 길과 **버린 길**) ②우려·확신 없는 지점 ③실측 3줄(커밋 해시 · **실패 클래스 이름** · 테스트 수) ④심은 변형 목록. 경로는 **메인 저장소의 `report-p11-t<N>.md>`**(워크트리가 아니다)
6. **파일 쓰기가 도구에서 거부되면 전문을 메시지로 보내라.** 조율자가 저장한다
7. **컴파일되는 지점마다 중간 커밋.** 이 프로젝트는 세션 한도로 끊긴 전례가 반복됐다
8. 인프라 부재로 인한 실패는 **환경 문제로 분류**해 코드 결함과 구분해 적어라

---

## 6. 실행 — 포트를 실측한 값으로 쓴다

```bash
cd backend
./gradlew test --tests <클래스> -PtestDbUrl=jdbc:postgresql://localhost:15432/<배정받은_DB> --rerun
```

- ⚠ **포트 15432**(redis 16379 · kafka 29092). `groom-shopping` 스택이 5432·6379 를 점유해 옮긴 값이고, **5432 를 쓰면 다른 프로젝트 postgres 에 붙어 `password authentication failed` 가 난다.** 착수 시 `docker port school-bus-postgres-1` 로 **다시 실측**하라
- DB 생성은 조율자가 한다 — 역할은 `postgres` 가 아니라 **`schoolbus`**
- ⚠ **판정 근거는 콘솔이 아니라 `build/test-results/test/TEST-*.xml` 의 `tests=`·`failures=`·`errors=`.** `:test` 줄의 `UP-TO-DATE`·`FROM-CACHE` 는 통과가 아니라 **미실행**이다
- **인자 없는 `./gradlew test` 금지** · **`clean` 금지** · `docker compose down` 금지 · **`-v` 절대 금지** · **`DROP DATABASE` 금지**
- ⚠ **Redis 를 쓰면 `testsupport/redis/RedisTestContainerBase` 를 상속**하라. 공유 컨테이너에 붙지 마라
- ⚠ **시드의 "오늘" 행에 기대는 시험을 만들지 마라** — DB 를 만든 날에 굳어 하루 뒤 `RUN_NOT_FOUND` 로 죽는다. 미래 날짜를 직접 지정하거나 `Clock` 을 고정한다(`.claude/PROJECT_NOTES.md` 의 알려진 함정)
- 참고 — `*ConcurrencyTest` 4종은 전체 실행에서 `TimeoutException` 이 나고 **단독 재실행에서 통과**한다(부하 의존, Phase 8 부터 이월. 코드 결함 아님)

---

## 7. 이월 8건의 이번 Phase 처리

| # | 항목 | 이번 Phase |
|:-:|---|---|
| ① | ShedLock 도입 | ✅ **목표 12·13 으로 편입** |
| ② | `ProximityNotificationScheduler` 배치 계층 무시험 | ⚠ **T4 가 범위 판정** (§3.3) |
| ⑤ | `RiderNoShowEvent` 경로 방송 | ⚠ **T1 이 정본 대조로 판정** (§3.5) |
| ③④⑥⑦⑧ | `speed`·`heading` · `eta` 값 · `RunCompletionService` 대역 · `RedisConfig` 삭제 · 동시성 부하 의존 | **계속 이월** — ④는 Phase 13, 나머지는 별도 단위 |

⚠ **Phase 9 이월 ⑤(T2 수정 라운드 3 재리뷰 미부착)도 아직 미해소다.** 이번 게이트 리뷰의 판정 대상에 실어라.
