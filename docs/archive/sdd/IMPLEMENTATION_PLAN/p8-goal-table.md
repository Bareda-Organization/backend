# Phase 8 완료 조건 — 목표 표 (착수 전 고정)

출처: `docs/IMPLEMENTATION_PLAN.md` Phase 8 절 완료 조건 **12개**(`:826`~`:837`, 직접 계수)
\+ Phase 7 이월 **5건**(`:845`~`:849`) + 전체 실측 **1항** = **18항**.

작성 시점 2026-08-30, Phase 7 마감 직후. 브랜치 `feat/baraeda-rebuild` · 분기점 **`4da464e`**.
착수 전 판정 **Ruling 195~199**(아래 "착수 전 판정" 절)을 전제로 쓰였다. **표만 읽고 착수하지 마라.**

## 분기점 실측값 (2026-08-30, 조율자 직접 계수)

| 항목 | 값 | 세는 법 |
|---|:-:|---|
| 프로덕션 핸들러 | **56** | `grep -rhoE '^\s*@(Get\|Post\|Put\|Patch\|Delete)Mapping' --include='*Controller.java' src/main/java \| wc -l` |
| 컨트롤러 | **20** | `find src/main/java -name '*Controller.java' \| wc -l` |
| 테스트 클래스 파일 | **135** | `find src/test -name '*Test.java' \| wc -l` |
| 테이블 | **39** | `V1__init_schema.sql` 의 `CREATE TABLE` |

⚠ **이 Phase 는 엔드포인트가 늘어난다** — Phase 7 과 반대다. 아래 §"신설 핸들러" 가 예상치이고,
판정 시에는 **예상치가 아니라 실측치**를 적는다.

## 범위 — 기능 ID 와 엔드포인트

`ATT-01·02·03` · `REQ-01~05` · `RTE-03·04·06·10` · `A-05·A-06·A-15` · `C-04` · `C-05`.

**신설 핸들러 (예상 8개)**

| 경로 | 정본 | 기능 ID |
|---|---|---|
| `PATCH /students/{id}/runs/{runId}/intent` | `API_SPEC:496` §3.6 | ATT-01·02 |
| `POST /students/{id}/change-requests` | `API_SPEC:565` §3.8 | REQ-01·02 |
| `GET /students/{id}/change-requests` | `API_SPEC:590` §3.9 | REQ-03 |
| `GET /staff/approvals` | `API_SPEC:1208` §5.5 | REQ-04·05 |
| `GET /staff/approvals/{id}` | `API_SPEC:1208` §5.5 | REQ-04 |
| `POST /staff/approvals/{id}/decide` | `API_SPEC:1271` §5.6 | REQ-04 |
| `POST /staff/runs/{runId}/forced-add` | `API_SPEC:1293` §5.7 | RTE-06 |
| `POST /staff/runs/{runId}/waypoints` + `DELETE .../{waypointId}` | `API_SPEC:1550` §5.15 | RTE-10 |

**범위 밖으로 확정한 것** — 근거는 아래 "착수 전 판정".

- `POST /staff/students/{id}/transfer` (RTE-07 · A-07) → **별도 단위** (Ruling 197)
- `/ws/academy/{id}/live` 채널의 `approval_requested` 방송 → **Phase 10** (Ruling 195)
- `POST /runs/{runId}/start` 트랜잭션 안의 동기 자동 거절 **배선** → **Phase 9** (Ruling 196)
- 매니저 → `Assignment` 범위 축소 → **Phase 9** (Ruling 199)

---

## 착수 전 판정 — Ruling 195~199

⚠ **다섯 건 모두 "완료 조건 문면은 있는데 그것을 검사할 코드가 이번 Phase 에 없다" 는 형태다.**
Phase 7 이 같은 형태로 두 번 틀렸고(목표 3·9) 둘 다 아래 사람이 잡았다. 그래서 이번 표에는
**"검사 수단이 이번 Phase 소유인가" 열**을 세웠고, 아래는 그 열을 채우다 걸린 것들이다.

### Ruling 195 — `approval_requested` 는 **푸시와 WebSocket 두 경로**이고, 이 Phase 는 푸시만 소유한다

완료 조건 `:827` 은 "관계자 **채널**에 `approval_requested` 방송" 이라고 적는다. 정본을 직접 보면
같은 이름이 **두 곳**에 있다.

| 정본 | 경로 | 소유 Phase |
|---|---|---|
| `API_SPEC:2106` §9.7 알림 종류 | **푸시 알림** — 수신자 관계자 | **Phase 8** |
| `API_SPEC:1920` §7 · `:1943` §7.1 | **WebSocket** `/ws/academy/{id}/live` 방송 | **Phase 10** |

**WebSocket 채널 4종은 Phase 10 산출물**이다(`IMPLEMENTATION_PLAN:915` 범위 · `:919` 완료 조건).
현재 코드의 STOMP 엔드포인트는 `/ws/location` **하나**이고 `/ws/academy/{id}/live` 는 부재하다
(`global/config/WebSocketConfig.java:41`). 즉 이 Phase 에는 **방송을 검사할 대상 자체가 없다.**

⇒ 목표 2 를 **푸시 적재 + 도메인 이벤트 발행**으로 고정한다. `NotificationType.APPROVAL_REQUESTED`
enum 은 이미 실재하므로(`notification/entity/NotificationType.java:34`) 검사 수단이 지금 있다.
**WS 방송은 Phase 10 완료 조건에 등재**한다.

### Ruling 196 — ②구간 자동 거절의 두 시점 중 **운행 시작 쪽 배선은 Phase 9 소유**

`ARCHITECTURE:492` §9.6 은 "운행 시작은 `POST /runs/{runId}/start` **트랜잭션 안에서 동기 종결**,
출발 시각 도달분만 폴링" 이라고 못 박는다. 그런데 **`POST /runs/{runId}/start` 는 Phase 9 산출물**이다
(`API_SPEC §4.4` · `IMPLEMENTATION_PLAN:869` 참조 = `API_SPEC §4`). 현재 `run` 모듈의 핸들러는
목록·임시 추가·취소 **3개뿐**이고 start 는 부재하다(`run/controller/StaffRunController.java`).

⇒ **이 Phase 가 소유하는 것** — ①출발 시각 도달분 폴링(30초) ②`moving` 전이 시 대기 건을 종결하는
**도메인 서비스**와 그 단위 검사. **Phase 9 로 이월** — 그 서비스를 start 트랜잭션 안에서 호출하는
**배선**과 "폴링만 두면 시작 직후 승인이 통과하는 창" 이 실제로 닫혔다는 실측.

⚠ Phase 9 완료 조건에 **"운행 시작 직후 도달한 ②구간 승인이 `409 CHANGE_WINDOW_CLOSED` 로 거부된다"**
를 반드시 등재할 것. 등재하지 않으면 이 축은 영원히 검사되지 않는다.

### Ruling 197 — `§5.8 transfer`(RTE-07 · A-07)는 **이 Phase 범위 밖**, `§5.7 forced-add` 는 부분 해제

Phase 8 절의 **범위** 행은 `RTE-07` · `A-07` 을 싣는데 **완료 조건 12항 어디에도 대응 문면이 없다**
(`:826`~`:837` 직접 계수). 정본 쪽을 보면 `API_SPEC:1309` §5.8 이 여전히 **`[조정 중]`** 이고
`API_SPEC:2141` §10 표에도 남아 있다 — 즉 **정본이 미확정으로 못 박은 항목**이다.

| 경로 | 정본 상태 | 처분 |
|---|---|---|
| `§5.7 forced-add` | `[조정 중]` 이나 **구간·정원·주소 검증 문면이 확정**(`API_SPEC:1297`~`:1302`) | **부분 해제** — 완료 조건 `:835` 가 요구하는 것은 **구간 판정**뿐이다. 요청·응답을 이 Phase 에서 확정하고 §10 표에서 지운다 |
| `§5.8 transfer` | 목적·권한 외 전부 미확정 | **별도 단위로 등재.** transfer 는 "출발 버스 명단 제외 + 도착 버스 강제 추가 + 양쪽 재최적화" 라 **forced-add 가 확정된 뒤**라야 추측 없이 정의된다 |

**만들 것이 사라진 것이 아니라 지금 만들지 않는 것**이다 — `API_SPEC §5.8` 과 §10 표에서 지우지 않는다.

### Ruling 198 — **①구간은 재최적화를 호출하지 않는다.** 그 시점의 회차는 아직 `idle` 이다

⚠ **정본 안에서 두 문장이 어긋난다.**

| 정본 | 문면 |
|---|---|
| `FEATURE_SPEC:66` C-04 ① · `API_SPEC:534` §3.6 ① | "①구간: 즉시 반영 — `absent` 기록 · 명단 제외 · **노선 재최적화**" |
| `ARCHITECTURE §9` · `run.confirm_at` CHECK · Phase 7 목표 1 | 회차는 **출발 30분 전 도래에 확정**된다. 그 전까지 `status='idle'` 이고 `confirmed_route` 행이 **부재** |

①구간의 정의가 "출발 30분 전**까지**" 이고 확정 시각이 "출발 30분 전" 이므로, **①구간 동안 확정 노선은
존재하지 않는다.** 재최적화할 대상이 없다.

**어느 쪽이 이기는가** — `ARCHITECTURE §9` 다. 확정 시각이 파생값이 아니라 `run.confirm_at` **컬럼**이고
`ck_run_confirm_at` CHECK 가 강제하며 Phase 7 이 12항으로 실증했다. C-04 의 "재최적화" 는 **확정 노선이
있을 때만 의미가 있는 문면**이고, ①구간에는 그 상태가 발생하지 않는다.

⇒ **①구간 토글·변경 신청은 `boarding_intent`·일일 승하차지만 갱신한다.** 반영은 뒤이어 도는
확정 배치(RTE-02)가 그 값을 읽어 산출하는 것으로 이뤄진다(`ARCHITECTURE §8.1` 입력 3축).
목표 1 의 단언은 **"파이프라인 호출 0회 + 확정 후 `run_rider` 에 그 학생 부재"** 다.

**정본을 고친다** — `API_SPEC §3.6` ①행과 `FEATURE_SPEC` C-04 ① 에 이 판정을 각주로 단다.
코드를 조용히 정본에 맞추면 다음 사람이 같은 모순을 다시 만난다.

⚠ **예외를 함께 적는다** — 회차 임시 추가(`§5.10 POST /staff/runs`)로 **출발 30분 이내**에 만들어진
회차는 생성 시점에 이미 `confirm_at` 이 지나 곧바로 확정되므로 **②구간부터 시작**한다. ①구간이 아니다.

### Ruling 199 — Phase 2 이월 "매니저 → `Assignment` 범위 축소" 는 **Phase 9 소유**

`IMPLEMENTATION_PLAN:853` 이 이 항목을 Phase 8 절에 두면서 **"판정 대상인 회차 조회 경로가 이 Phase 의
산출물"** 이라고 적는다. **거짓이다** — 매니저용 회차 조회는 `API_SPEC §4.1 GET /manager/runs` 이고
`API_SPEC §4` 전체는 **Phase 9 참조**다(`IMPLEMENTATION_PLAN:869`). Phase 8 의 신설 핸들러 8개 중
매니저 앱 경로는 **0개**다.

**같은 문서의 다른 자리는 이미 Phase 9 로 적고 있다** — 진행 추적 표 Phase 2 행(`:1121`)의
"③학부모·매니저 축소 → **Phase 5·9**(Ruling 117)". 파생본 두 개가 어긋났고 **행 쪽이 옳다.**

⇒ `:853` 의 note 를 Phase 9 절로 옮긴다.

---

## 목표 18항

**열의 뜻**

- **소유** — 이 조건을 검사할 코드가 **이번 Phase 산출물인가**. `예` / `분할`(일부만) / `이월`
- **근거 원본** — 파일:행. **인용이 아니라 판정 직전 다시 연다**(횡단 규칙 24)

| # | 완료 조건 | 실행 명령 · 단언 | 소유 | 근거 원본 | 무엇이 깨지면 이 문장이 거짓인가 |
|:-:|---|---|:-:|---|---|
| **1** | **①구간 토글은 의사만 갱신하고 재최적화를 호출하지 않으며, 뒤이은 확정 배치가 그 값을 반영한다** (Ruling 198) | `Clock` 을 출발 **31분 전**으로 고정 → `PATCH .../intent` `riding=false` → `200` · `result=applied` · `rider_status=absent` · `boarding_intent.riding=false`. **파이프라인 호출 0회**(스파이). 이어서 `Clock` 을 확정 시각으로 옮겨 배치 1틱 → `run_rider` 에 **그 학생 부재** | 예 | `IMPL:826` · `API_SPEC:534` · `FEATURE_SPEC:66` · Ruling 198 | 여기서 재최적화를 부르면 **`idle` 회차에 확정 노선을 만들어** 확정 배치와 산출 주체가 둘로 갈린다 |
| **2** | **②구간 요청이 승인 대기로 접수되고 관계자에게 `approval_requested` 알림이 적재된다** | `Clock` 을 출발 **20분 전**으로 고정 → `PATCH .../intent` → `200` · `result=pending_approval` · **`riding` 은 기존 값 유지** · `change_request.status='pending'` · `notification_log` 에 관계자 대상 `approval_requested` 행. **커밋 후 발행**(롤백 시 미적재) | **분할** | `IMPL:827` · `API_SPEC:2106`(푸시) · `API_SPEC:1943`(WS, **Phase 10**) | 응답에 `riding` 을 바꿔 실으면 **학부모 화면이 반영됐다고 표시**되는데 노선은 그대로다 |
| **3** | **②구간 승인 시 재최적화 → `route_version` +1 배포 → 기사·동승자 푸시** | `POST /staff/approvals/{id}/decide` `approve=true` + 유효 `preview_token` → `200` · `route_version` 이 **직전 +1** · `confirmed_route` 갱신 · `notification_log` 에 기사·동승자 대상 `route_changed` · 학부모 대상 `change_decided` | 예 | `IMPL:828` · `API_SPEC:1281`~`:1287` · `ARCHITECTURE:408` §8.5 | 버전을 올리지 않으면 **기사 단말이 어느 판을 보고 운행 중인지** 관계자가 알 수 없다 (MON-05) |
| **4** | **②구간 거절 시 기존 노선 유지 + 사유 통지** | `approve=false` + `reject_reason` → `200` · `status='rejected'` · **`route_version` 불변** · **파이프라인 호출 0회** · 학부모 대상 `change_decided` 에 사유 포함. `reject_reason` 부재면 `422 VALIDATION_FAILED` | 예 | `IMPL:829` · `API_SPEC:1285`·`:1291` | 거절인데 재최적화를 돌리면 **거절이 노선을 바꾼다** — 이름과 하는 일이 갈린다 |
| **5** | **회차당 1회 한도. 등원 회차와 하원 회차가 한도를 공유하지 않는다** | ②구간에서 같은 회차에 2번째 요청 → `403 CHANGE_LIMIT_REACHED`. **같은 학생·같은 날의 하원 회차는 여전히 1회 가능**(`200`). 카운터는 `boarding_intent.change_used_count`(CHECK `BETWEEN 0 AND 1`) | 예 | `IMPL:830` · `API_SPEC:121`·`:535` · `FEATURE_SPEC:66` | 한도 단위를 학생·날짜로 잡으면 **하원 변경이 등원 때문에 막힌다** — 사양은 회차(`Run`) 단위다 |
| **6** | **자동 거절 — 출발 시각 도달과 운행 시작 중 먼저 오는 시점에 `auto_rejected`, 기존 노선 유지, 횟수 미소진** | ①**폴링 경로**: `Clock` 을 출발 시각 도달로 옮기고 1틱 → `status='auto_rejected'` · `route_version` 불변 · `change_used_count` **0 으로 복귀** · 학부모 `change_decided`. ②**`moving` 경로**: 회차를 `moving` 으로 전이시키고 도메인 서비스 직접 호출 → 같은 결과 | **분할** | `IMPL:831` · `API_SPEC:124`·`:1289` · `ARCHITECTURE:492` · Ruling 196 | 횟수를 소진시키면 **관리자가 응답하지 않은 대가를 학부모가 치른다.** 배선 실측은 **Phase 9** 몫 |
| **7** | **서버 처리 실패 시 기존 상태 복구 + 횟수 미소진** | 재최적화 단계에서 예외를 심고 승인 호출 → `5xx` · `change_request.status` **`pending` 유지** · `route_version` 불변 · `change_used_count` 불변. **한 트랜잭션**임을 롤백으로 실증 | 예 | `IMPL:832` · `API_SPEC:126` · `FEATURE_SPEC:66` C-10 | 부분 커밋되면 **한도만 깎이고 반영은 안 된 상태**가 남아 사용자가 손쓸 수단이 없다 |
| **8** | **③구간 미등원 요청이 승인 없이 수용되고 해당 승하차지가 `skipped` — 순번 유지·재최적화 부재** | 회차를 `moving` 으로 두고 `PATCH .../intent` `riding=false` → `200` · `result=applied_no_reroute` · `run_rider.status='absent'` · 그 승하차지 잔여 0 이면 `run_stop` 이 `skipped` · **`seq` 불변** · **파이프라인 호출 0회** · `route_version` 불변 | 예 | `IMPL:833` · `API_SPEC:122`·`:536` · `FEATURE_SPEC:67` C-05 | 순번을 다시 매기면 **기사 화면의 남은 정차지 번호가 운행 중에 바뀐다** |
| **9** | **③구간 그 외 요청은 `403 CHANGE_WINDOW_CLOSED`** | `moving` 회차에 ①`riding=true`(되돌리기) ②`POST /change-requests` ③`POST /waypoints` → 전부 `403 CHANGE_WINDOW_CLOSED` | 예 | `IMPL:834` · `API_SPEC:122`·`:536`·`:588`·`:1571` · `API_SPEC:1985` §8.3 | 되돌리기를 허용하면 **이미 지나친 승하차지로 다시 가야 한다** |
| **10** | **강제 추가는 ①구간에서만 성공하고 ②구간에서 `403`** | `Clock` 31분 전 → `POST /staff/runs/{runId}/forced-add` `201`. `Clock` 20분 전 → 같은 호출이 **`403 CHANGE_WINDOW_CLOSED`**. 정원 초과 시 `409 CAPACITY_EXCEEDED`, 주소 검증 실패 시 `422 ADDRESS_VERIFICATION_FAILED`(**저장 보류**) | 예 | `IMPL:835` · `API_SPEC:1297`~`:1307` · `FEATURE_SPEC:344` A-06 · Ruling 197 | 관계자에게 예외를 주면 **30분 안쪽 추가가 승인 절차를 우회**한다 — 사양은 관계자도 예외 부재다 |
| **11** | **경유 지점 — `apply=false` 에서 확정 노선 불변, `apply=true` 배포 시 버전 증가** | `POST /staff/runs/{runId}/waypoints` `apply=false` → `200` · `route_preview` 존재 · **`confirmed_route`·`route_version` 불변** · `applied=false`. 같은 입력에 `apply=true` → `route_version` **+1** · 기사·동승자 `route_changed` | 예 | `IMPL:836` · `API_SPEC:1550`~`:1575` · `ARCHITECTURE:414` | 미리보기가 확정 노선을 건드리면 **관리자가 취소해도 기사에게 이미 나간 뒤**다 |
| **12** | **승인 대기 목록 조회에서 노선 계산이 발생하지 않는다 (호출 수 검증)** | 대기 **3건**을 만들고 `GET /staff/approvals` 1회 → **파이프라인 호출 0회**(스파이 계수). `GET /staff/approvals/{id}` 1회 → **정확히 1회**. 상세 응답에 `preview_token`·`preview_stale` 존재 | 예 | `IMPL:837` · `API_SPEC:1212` · `ARCHITECTURE:399` §8.4 | ⚠ **"느리다" 는 초록 빌드에서 안 보인다.** 호출 수를 세는 단언만이 N배 계산을 잡는다 |
| **13** | **`MANAGER_DOUBLE_BOOKED` 를 겹침 판정으로 올린다** (Phase 7 이월 1 · Ruling 193) | ⚠ **이것은 `ErrorCode` 가 아니라 `AssignmentWarningCode` 다** — 배치는 **저장되고 경고만 실린다**(`API_SPEC §8.4` `DUPLICATE_ASSIGNMENT` 행 하단). 같은 매니저를 **출발 시각이 다르지만 운행 구간이 겹치는** 두 회차에 배치 → 응답 `warnings[]` 에 `MANAGER_DOUBLE_BOOKED` **포함**. 지금 JPQL 은 `r.departTime = :departTime` 이라 **정확히 같을 때만** 잡는다 | 예 | `IMPL:845` · `PRD:122` · `USER_FLOWS:482` · `manager/domain/AssignmentConflictDetector.java:39` | 등원 직후 하원에 같은 매니저를 붙이는 형태가 **실재 가능**한데 경고가 뜨지 않는다 |
| **14** | **`Clock` 을 출발 25분 전으로 고정해도 27분 전 요청이 ①구간으로 처리되지 않는다** (Phase 7 이월 2 · Ruling 194) | 판정기에 **요청 시각이 아니라 서버 현재 시각**을 넣는다 — 25분 전 시계에서 "27분 전에 접수된" 요청도 **②구간**으로 판정. `API_SPEC:117` "판정 주체는 서버 시계" | 예 | `IMPL:846` · `API_SPEC:116`~`:124` | 접수 시각으로 판정하면 **요청을 보낸 시각을 조작해 승인을 우회**한다 |
| **15** | **`DailyRoster` 의 중복 배제 범위가 시험으로 고정된다** (Phase 7 이월 3) | ⚠ **실측된 구멍** — `null` 원소까지 거르도록 넓혀도 **전건 통과**한다. 범위를 넓히거나 좁히면 실패하도록 고친다 | 예 | `IMPL:847` · `p7-review-t7-verdict.md` | 다음 사람이 배제 범위를 바꿔도 **아무도 모른다** |
| **16** | **`AcademyScopeRule.conditionClauseContainsAcademyId` 를 직접 겨냥한 시험이 생긴다** (Phase 7 이월 4) | 지금은 프로덕션 `@Query` 에 결함을 심는 **간접 검증**만 된다. 그 메서드에 조건절 문자열을 직접 넣는 단위 시험을 세운다 | 예 | `IMPL:848` · `p7-review-t6-verdict.md` | 규약 검사기 자신이 틀리면 **그것이 지키는 학원 격리 전체가 무너진다** |
| **17** | **`route_changed` 알림의 "삭제된 매니저 제외" 조건을 처분한다** (Phase 7 이월 5) | ⚠ **판정 항목이다.** 지금 그 필터를 지워도 전건 통과한다(MGR-04 가 배치된 매니저 삭제를 차단하므로 **도달 불가능**). **살리면 도달 가능한 경로를 만들어 단언으로 고정하고, 지우면 그 근거를 Ruling 으로 남긴다.** 보고서가 근거로 든 "`accountId` 재배정 위험" 은 **부정확**하니 함께 정정한다 | 예 | `IMPL:849` · `p7-review-t3-verdict.md` | **아무도 검사하지 않는 코드**가 남으면 다음 사람이 그것을 근거로 삼는다 |
| **18** | **테스트 전체 묶음 실패 0** · 핸들러 **실측** | **신규 DB · 동시 실행 0** 에서 `./gradlew test` **1회**. 실행 전후로 postgres 재시작 횟수 확인. 실패 클래스는 **개수가 아니라 이름**을 적는다. 핸들러는 위 계수 명령으로 다시 센다 | 예 | 횡단 규칙 22·24 | 동시 실행 중의 실패 목록은 **증거 능력이 부재**(`parallel-agents-git §9`) |

---

---

## ⚠ Phase 8 진행 중 관측 — 동시성 시험 3종이 **부하와 결함을 구분하지 못한다** (2026-08-30, 이월 후보)

T1·T8 회수 직후 `e17415a` 에서 전체 묶음을 **두 번** 돌린 실측이다.

| 회차 | 소요 | 결과 |
|:-:|---|---|
| 1회 | **9분 30초**(찬 캐시 · 병합 후 첫 컴파일 포함) | **827개 중 3개 실패** — `BusRegistrationConcurrencyTest` · `AssignmentConcurrencyTest` · `RunGenerationConcurrencyTest` |
| 2회 | **1분 41초**(더운 캐시) | **827개 전건 통과** (140클래스 · 건너뜀 3) |

**환경 문제로 분류한 근거 — 추측이 아니라 실측이다.**

- 실패가 전부 **`TimeoutException`** 이고 **검사 조건 불일치가 하나도 아니다**
- `too many clients` **0건** · postgres 재시작 계수 전후 **1 → 1** · Redis 재시작 **0회**
- 3클래스를 **단독 실행하면 12초에 통과**
- 두 회차 모두 **테스트 수가 827 로 같다** — 2회차가 일부만 돈 것이 아니다

**그러나 이것을 "정상" 으로 적지 마라.** 셋 다 시간 제한이 **20초**이고 두 스레드가 경쟁하는 형태인데,
**순서를 강제하는 수단이 없어** "느린 호스트" 와 "잠금이 깨진 구현" 이 같은 실패로 나타난다.
⚠ **Phase 5 가 같은 형태를 이미 한 번 기록했다**(간헐 실패 1건 — 동시성 시험의 순서 강제 부재).
같은 것이 두 Phase 에 걸쳐 재발했으므로 **다음 Phase 목표 표에 이월 등재**한다 —
지금 초록인 것은 이 시험들이 결함을 잡는다는 증거가 아니다.

---

---

## ⚠ Phase 8 에서 **같은 형태의 사각지대가 다섯 번** 나왔다 (2026-08-30) — 다음 Phase 목표 표에 실어라

전부 **게이트 리뷰가 변형을 직접 심어서만** 드러났다. 구현자 보고서도, 통과한 시험 수도,
초록 빌드도 하나도 잡지 못했다.

| 좌석 | 살아남은 변형 | 놓치던 사고 |
|:-:|---|---|
| **T3** | `DailyRoster` 생성에서 `stopOverrides` 제거 | **명단에는 바뀐 승하차지가 찍히는데 버스는 옛 경로로 돈다** |
| **T4** | `affectedStudentsOf` 를 항상 빈 목록으로 | 승인 화면의 **영향 학생이 비어도 아무도 모른다** |
| **T4** | `candidateRosterOf` 의 취소 제외 조건 무력화 | **취소 대상이 안 빠진 노선**을 전/후 대조로 보여준다 — 관리자가 "바뀌는 게 없어 보이는 화면" 을 보고 승인한다 |

| **T5** | **`preview_token` 이 문자열만 비교하고 지문을 대조하지 않음** — 리뷰가 공격 시나리오로 재현: 같은 회차 승인 대기 A·B 를 둘 다 미리보기 → A 승인 → **B 를 A 이전 토큰으로 승인하면 200 이고 A 가 지운 정류장이 되살아난다.** 버전도 오르고 토큰도 일치해 **목표 3 단언이 전부 통과**한다 | **관리자가 본 화면과 다른 노선이 배포된다** — 그 장치가 막으라고 만들어진 바로 그 사고 |
| **T7** | 강제 추가 대기 행이 **확정 배치를 거쳐 명단에 실제로 들어가는지** 검사하는 단언 부재 | **강제 추가했다고 화면에 뜨는데 그 학생이 버스 명단에 없다** |
**공통 기제 — 하나의 입력이 두 갈래로 흐르는데 한쪽만 검사된다.**

| 검사되던 쪽 | 검사되지 않던 쪽 |
|---|---|
| **저장 결과** (`run_rider.stop_id`) | **계산 입력** (`DailyRoster`) |
| **호출 횟수 · 토큰 · 상태 코드** | **응답 본문의 계산된 값** (`route_preview` · `affected_students`) |

⇒ **다음 Phase 목표 표를 쓸 때 항목마다 물어라 — "이 값이 흘러가는 갈래가 몇 개인가, 그 전부를
검사하는가."** 특히 **"응답 본문의 계산된 값을 보는 단언이 하나라도 있는가"** 는
`grep -c 'jsonPath("$.data.<필드>' <시험파일>` 로 셀 수 있다. T4 는 그 값이 **0** 이었다.

⚠ **`preview_token` 같은 정합성 장치가 이 구멍을 막아 주지 않는다** — 그것은
"화면에서 본 것과 배포되는 것이 같다" 를 보장할 뿐, **본 것이 틀렸으면 틀린 것을 그대로 배포한다.**

---

## 판정 규칙

1. **부분 통과는 완료가 아니다.** 18항 중 17항이면 🟡 로 남기고 미통과 항목을 함께 적는다.
   **다만 Ruling 189 대로 멈추지 않고 다음 Phase 로 가며, 미통과는 Phase 9 목표 표에 이월 등재한다**
2. **초록은 통과의 증거가 아니다.** 새 단언마다 음성 대조를 실측한다(횡단 규칙 23)
3. ⚠ **이 Phase 의 목표 1·4·8·12 는 전부 "호출이 일어나지 않는다" 를 검사한다.** 이런 단언은
   **스파이를 붙이지 않으면 자동으로 통과**한다 — 가장 위험한 형태다. 음성 대조에서
   **재최적화를 일부러 부르는 변형**을 심어 그 단언만 실패하는지 반드시 본다
4. ⚠ **시각을 고정하지 못하면 목표 1·2·5·6·8·9·10·14 를 검사할 수단이 없다.** `Clock` 빈 주입이
   이 Phase 의 전제다(`TECH_DECISIONS §6`). 시험이 `OffsetDateTime.now()` 를 부르면 그 자리에서 결함이다
5. ⚠ **`preview_token` 은 "화면에서 본 것과 배포되는 것이 같다" 를 지키는 유일한 장치다**
   (`API_SPEC:1279`). 토큰 검사를 생략하면 목표 3 은 통과하는데 **관리자가 승인한 것과 다른 노선이
   배포**된다 — 초록 빌드와 구별되지 않는다
6. **테스트 커넥션 상한을 낮추지 마라.** `minimum-idle=1` 은 유지하되 `maximum-pool-size=6` 은
   동시성 시험의 하한이다(Phase 7 `a245c32`)
7. ⚠ **정본 개정이 이 Phase 의 산출물에 포함된다** — Ruling 197(§10 표에서 `§5.7` 삭제) ·
   Ruling 198(`API_SPEC §3.6` ①행 · `FEATURE_SPEC` C-04 ① 각주) · Ruling 195·196·199(이월 등재).
   **코드만 고치고 정본을 두면 다음 Phase 가 같은 모순을 다시 만난다**
