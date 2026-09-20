> ⚠ **작성 주체 — 이 보고서는 좌석 `p11-t2-fix1-sonnet` 이 작성한 전문이다.**
> harness 가 서브에이전트의 파일 쓰기를 거부해 좌석이 메시지로 보낸 것을 **조율자가 그대로 옮겨 적었다.**
> 조율자가 쓴 문장은 이 인용 블록과 맨 끝의 「조율자 판정」 절뿐이다.

# Phase 11 T2 수정 라운드 1 — 보고서

작성 주체: p11-t2-fix1-sonnet (워크트리 `/Users/mskim/Desktop/PJ/school-bus-wt/p11-t2`, 브랜치 `p11-t2`, 시작 tip `cf541ce`)

## ①판단 근거

- **필드명 정정 위치**: `elapsedSecondsSinceRaised` → `elapsedSinceRaised` 는 `AdminEmergencyItemResponse` record 컴포넌트 이름 하나만 바꾸면 되는 문제였다. 계산 로직(`AdminEmergencyQueryService#toItem`)의 지역변수 `elapsedSeconds` 는 그대로 두고 record 생성자 호출부만 새 이름에 맞춰 값을 넘기게 했다 — 지역변수 이름까지 바꾸는 것은 이번 수정 범위(응답 계약)를 벗어난다고 판단해 손대지 않았다.
- **목표 11 시험 배치**: `AdminEmergencyControllerTest` 를 `admin/controller` 패키지에 신설했다. `AdminEmergencyController` 가 그 패키지의 유일한 컨트롤러라 기존 컨트롤러 시험과 이름 충돌이 없었고, `EmergencyControllerTest`(신고 발신 측, `exception/controller`)와 패키지를 분리해 "발신"과 "관리자 조회"가 서로 다른 소유 경로(브리프 상 admin·exception 모두 T2 소유)임을 드러냈다.
- **목표 10 시험 배치**: `EmergencyBroadcastListenerTest` 를 `global/websocket` 패키지에, `RiderChangedBroadcastListenerTest` 와 나란히 뒀다. 그 테스트가 이미 확립한 패턴(Mockito mock 으로 `WebSocketBroadcastGateway` 대체, Spring 컨텍스트 미기동)을 그대로 따랐다 — 실제 STOMP 왕복이 필요한 검증이 아니라 "어느 목적지로 몇 번 호출됐는가"만 보면 되는 시험이라 컨텍스트 기동 비용이 불필요했다.
- **시각 고정 방법**: `AdminEmergencyControllerTest` 에 `@TestConfiguration` + `@Bean @Primary Clock fixedClock()` 을 내부 클래스로 둬 미래 고정 시각(`2030-04-01T03:00:00Z`)을 썼다. 시드 데이터의 "오늘" 값에 기대지 않기 위함이며, `received_at`/`acked_at`/`canceled_at` 을 시험마다 `JdbcTemplate` 로 직접 옮겨 "종료 시점 우선순위" 세 갈래(now/ackedAt/canceledAt)가 서로 다른 수치(120/200/300초)로 갈리게 설계해 분기 오류를 명확히 잡게 했다.
- **시드 오염 대응**: `GET /admin/emergencies` 는 `@AcademyScopeExempt` 라 로컬 시드(V2)의 기존 `emergency_alert` 행이 항상 응답에 섞인다. 목록 크기·순서에 기대지 않고 JsonPath 필터(`$.data.emergencies[?(@.id == N)].field`)로 내가 만든 행만 골라 검증했다.
- **§6.11 필드 대조표** (정본: `docs/API_SPEC.md:1900` 부근 §6.11, 실측 대상: `AdminEmergencyItemResponse`):

  | 정본 필드 (snake_case) | 응답 실제 필드 | 판정 |
  |---|---|---|
  | `staff_acked` | `staffAcked` → `staff_acked` | 일치 |
  | `elapsed_since_raised` | (수정 전) `elapsedSecondsSinceRaised` → `elapsed_seconds_since_raised` | **불일치 — 이번에 수정** |
  | `academy`(id/name/contact) | `AcademyInfo(id, name, contact)` | 일치 |
  | `id`/`run_id`/`bus_no`/`type`/`memo`/`lat`/`lng`/`rider_count`/`occurred_at`/`received_at`/`acked_at`/`canceled_at` | 동일 이름 매핑 | 일치 |
  | `raised_by`/`contacts`/`direction`/`acked_by` (정본 §5.16 원본 필드) | **응답에 없음** | 아래 ②에 기재, 이번 라운드 범위 밖으로 판단 |

## ②우려·확신 없는 지점

- **`raised_by`/`contacts`/`direction`/`acked_by` 필드 부재**: 정본 §5.16(신고 발신 원본 필드)에는 있으나 `AdminEmergencyItemResponse` 에는 없다. 다만 이 누락은 이번 라운드에서 새로 생긴 것이 아니라, 같은 구조를 가진 형제 클래스 `EmergencyStaffItemResponse`(목표 8, 게이트 리뷰 R2 에서 이미 "충족"으로 판정됨)와 동일한 기존 설계다. 브리프가 지시한 것은 "필드명 정정 + §6.11 필드 대조" 였고 이 누락은 §6.11 자체의 필드 목록에는 없는 항목(§5.16 전용)이라, **이번 Ruling 214 범위의 결함이 아니라 별도 판단이 필요한 사전 존재 사실**로 보고 손대지 않았다. 확신도는 80% — 조율자가 §6.11 대조를 "그 절에 나열된 필드만" 로 좁게 읽었다면 맞는 판단이고, "관리자 콘솔이 노출해야 할 필드 전체" 로 넓게 읽었다면 이 4개 필드도 추가해야 하는 별도 태스크가 된다.
- **`AdminEmergencyQueryService` 지역변수 `elapsedSeconds`** 이름이 record 필드명과 어긋난 채(변수는 `elapsedSeconds`, 필드는 `elapsedSinceRaised`) 남아 있다. 기능에는 영향 없으나 가독성 관점에서 지적될 수 있다 — 이번 수정 범위를 "응답 계약(직렬화 키)" 으로 좁게 잡아 건드리지 않았다.

## ③실측 3줄

- 최종 커밋: `04d89ca`(`87d951c` 다음), 분기점 `cf541ce..HEAD` 커밋 수 2
- 음성 대조 중 관측된 실패 테스트 클래스/메서드: `AdminEmergencyControllerTest.확인된_신고는_경과시간이_확인_시각에서_멈춘다`·`AdminEmergencyControllerTest.취소된_신고는_경과시간이_취소_시각에서_멈춘다`(결함 2), `EmergencyBroadcastListenerTest.확인_방송은_발신자_채널에도_반영된다`(결함 3), `EmergencyBroadcastListenerTest` 3개 메서드 전부(결함 4, 공유 헬퍼라 접수·확인·취소 모두 영향), `AdminEmergencyControllerTest.학원_관계자는_메인관리자_콘솔을_호출할_수_없다`(결함 5)
- 최종 실행(XML 결과 파일 기준): `AdminEmergencyControllerTest` tests=4 failures=0 errors=0, `EmergencyBroadcastListenerTest` tests=3 failures=0 errors=0. `grep -rc 'elapsedSecondsSinceRaised' backend/src/main` → 0건. `git status --porcelain` → 빈 결과.

## ④심은 변형 목록

| # | 심은 것 | 위치 | 잡은 시험 | 결과 |
|---|---|---|---|---|
| 1 | 필드명을 `elapsedSecondsSinceRaised` 로 되돌림 | `AdminEmergencyItemResponse.java` | 목표 11 JSON 키 시험(경과시간 3개) | 3/4 실패(인가 시험만 통과) — 원복 확인 |
| 2 | 경과 시간 종료 시점을 항상 `now` 로(취소·확인 우선순위 제거) | `AdminEmergencyQueryService.java` `toItem` | 확인/취소 분기 시험 2건만 | 2/4 실패, 나머지 2건(미확인·인가) 통과 — 원복 확인 |
| 3 | ack 방송의 `managerRun` 발신을 주석 처리(no-op) | `EmergencyBroadcastListener.broadcastAcked` | `확인_방송은_발신자_채널에도_반영된다` | 1/3 실패 — 원복 확인 |
| 4 | 학생 채널을 `sendToStaffAndAdmin`(공유 헬퍼) 에 추가 | `EmergencyBroadcastListener.sendToStaffAndAdmin` | 3개 시험 전부 | 3/3 실패 — 원복 확인 |
| 5 | `@CanMonitorAll` 인가 애너테이션 제거 | `AdminEmergencyController.list()` | `학원_관계자는_메인관리자_콘솔을_호출할_수_없다` | 1/4 실패 — 원복 확인 |

5건 모두 계획→실행→원복→`git status --porcelain` 빈 결과 확인의 1회씩 완결했다.

---

# 조율자 판정 (2026-09-02) — ②의 물음에 답한다

**좌석의 읽기는 틀렸고 결론은 맞다.** 둘을 갈라 적는다.

**틀린 부분** — `§6.11` 은 자기 절에 필드 3개(`academy` · `staff_acked` · `elapsed_since_raised`)만 나열하지만, 그 위에 **"응답 — `§5.16` 항목 + 아래"** 라고 적혀 있다. 즉 **`§5.16` 의 필드 전부가 `§6.11` 계약에 포함**된다. 따라서 "`§6.11` 자체의 필드 목록에는 없는 항목" 이라는 전제가 성립하지 않는다. **좌석이 제시한 두 갈래 중 "넓게 읽는" 쪽이 정본의 문면이다.**

**맞은 부분** — 그래도 **이번 Phase 에서 고치지 않는 것이 옳다.** 근거는 범위이지 정합이 아니다.

- 완료 조건 **16항 어디에도 `§5.16`·`§6.11` 응답 형태의 전량 대조가 없다.** 목표 11 이 이름으로 지목한 것은 `staff_acked` · `elapsed_since_raised` 둘이고 그 둘은 이제 맞다
- 목표 8(발신 시점 첨부)은 **발신 경로(`§4.14`)** 를 검사하는 항목이라 `§5.16` 목록 응답의 형태와 다른 자리다. R2 의 "충족" 판정은 그 범위에서 옳다
- `phase-goal-loop §1` — **작업 도중 목표를 늘리지 않는다.** 늘려야 하면 별도 단위로 뺀다

⇒ **이월로 등재한다(아래 「이월」).** 좌석이 확신도 80% 로 신고하고 손대지 않은 것이 정확한 처신이었다 — 이 신고가 없었으면 정본의 "`§5.16` 항목 +" 한 줄을 아무도 다시 읽지 않았다.

## 이월 — `§5.16`·`§6.11` 응답 필드가 정본과 어긋난다 (Phase 11 범위 밖)

조율자가 `EmergencyStaffItemResponse` 와 `§5.16` 표를 항목 단위로 마주 놓은 결과, **어긋남이 좌석이 신고한 4건보다 넓다.**

| 정본 `§5.16` | 코드 | 어긋남 |
|---|---|---|
| `emergency_id` | `id` | 이름 |
| `raised_by` **object**(`name`·`role`·`phone`) | `raisedByName`·`raisedByRole`·`raisedByPhone` 평면 3개 | **형태**(객체 → 평면) |
| `position` **object**(`lat`·`lng`·`recorded_at`) | `lat`·`lng` 평면 2개 | **형태** + `recorded_at` 부재 |
| `direction` | — | **부재** |
| `contacts` **array** | — | **부재** |
| `raised_at` | `occurredAt` + `receivedAt` | 이름 · 2개로 갈림 |
| `acked_by` **object** | `ackedByName` | **형태** |

⚠ **`§6.11` 이 `§5.16` 을 상속하므로 이 어긋남은 관리자 콘솔에도 그대로 적용된다.**
⚠ **이 항목은 어느 완료 조건도 검사하지 않는다** — 그래서 전 시험이 초록인 채로 남아 있었다. **다음 Phase 의 목표로 올릴 때 "응답 형태를 항목 단위로 대조" 를 문면에 넣어야** 같은 형태로 다시 빠지지 않는다.
