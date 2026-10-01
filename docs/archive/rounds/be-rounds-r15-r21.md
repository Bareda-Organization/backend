# 보관 — 백엔드 라운드 R15~R21 (8.23~8.35)

**2026-10-01 `docs/IMPLEMENTATION_PLAN.md` 에서 원문 그대로 옮긴 보관 문서(R46-DOCS · 분기점 `bef9d3ce`).** 요약·재작성 없이 줄 단위로 옮겼고, 절 제목의 번호(`## 8.2 …` 등)도 원문 그대로라 옛 절 번호 인용이 이 파일에서 같은 번호로 찾아진다. **읽고 싶을 때만 연다** — 지금의 규칙·진행 표는 본문 ``docs/IMPLEMENTATION_PLAN.md`` 에 있다. 이 파일 안의 "위"·"아래"·"이 문서" 와 절 번호 인용은 옮기기 전 문서 기준이며, 본문에 남은 절(`7` 횡단 규칙 · `8.73` 이후 등)을 가리킬 수 있다.

| 항목 | 내용 |
|---|---|
| 옮긴 절 | 8.23 ~ 8.35(`R15` 목표 표부터 `R21` 결과까지) |
| 기간 | 2026-09-19 ~ 2026-09-20 |
| 줄 수 | 원문 1056줄(아래 머리말·구분선 제외) |

---

## 8.23 ⚖ `R15` 목표 표 — 도로 경로 + 지도 개편 + 알림 발송 시점 (2026-09-19 착수)

**근거** — `Ruling 309`(docs/archive/rounds/be-rounds-r5-r14.md §8.21) · `Ruling 310`(docs/archive/rounds/be-rounds-r5-r14.md §8.21) · `Ruling 308`(docs/archive/rounds/be-rounds-r5-r14.md §8.20). 셋을 한 라운드로 묶은
이유는 docs/archive/rounds/be-rounds-r5-r14.md §8.21 "R15 범위" 에 적혀 있다.

### 조율자 선행 실측 (발주 전 확인분 — 갈래는 인용으로 보고 직접 다시 센다)

| 확인한 것 | 실측 |
|---|---|
| `path` 를 버리는 지점 | `NaverDirectionsGateway.java:L125-127` — `summaryOf(response)` 의 `distance`·`duration` 만 꺼내 `StraightLineLegs.distribute` 로 넘긴다 |
| `RoadLeg` 의 모양 | `record RoadLeg(int distanceMeters, int durationSeconds)` — **좌표 자리 부재** |
| 저장처 후보 | `route_version` 테이블에 `fallback_used boolean` 이 **이미 있다**(`V1__init_schema.sql:446`) |
| §5.19 실재 | `run/controller/StaffRunRouteController.java:L39` · `run/dto/StaffRunRouteResponse.java` — **실재한다** |
| 버스 목록 재료 | ⭐ **`GET /staff/runs/live` 는 `status='moving'` 만 준다**(§5.18). 4종 전부를 주는 것은 **`GET /staff/runs?service_date=`**(§5.10) 이고 프런트에 `getRuns()` 가 **이미 있다**(`features/schedule/api/index.ts:141`) |
| 출발 선점 | `RunStopRepository.claimDeparture`(조건부 UPDATE) · `ProximityNotificationService.judgeDeparture:L155` |
| 즉시 발송 지점 | `BoardingNotificationListener.appendRiderStatusChanged` · `appendRiderStatusReverted` · `appendRiderNoShow` |

### ⚖ Ruling 311 — 출발 시점 발송은 **학부모 알림에만** 적용한다

`Ruling 308` 이 `BRD-04`(미승차)를 범위에 넣었으나, §4.6 표의 미승차 알림은 **학부모 갈래와 관계자
갈래 둘**이다. **관계자 갈래는 즉시 발송을 유지한다.**

- **근거** — 관계자 알림은 되돌리기로 뒤집히는 *결과 통보* 가 아니라 **현황 신호**다. 늦추면 그 신호의
  쓸모가 사라진다(관계자는 실시간 관제 화면을 따로 본다)
- `Ruling 308` 이 막으려던 것은 **같은 학부모가 번복된 알림을 여러 번 받는 것**이고, 관계자 카운트는
  그 대상이 아니다

### ⚖ Ruling 312 — 마지막 승하차지는 **운행 종료가 출발로 갈음**한다

`Ruling 308` 의 폴백(*"다음 승하차지 도착 시 강제 발송"*)은 **마지막 승하차지에 다음이 없어** 그대로
두면 알림이 **영원히 안 나간다**. `Ruling 307` 초안이 냈던 구멍과 **같은 형태**다.

⇒ **운행 종료(`run.finish`) 시 도착·미출발로 남은 승하차지 전부에 `claimDeparture` 를 강제 적용**한다.

---

### T1 — 도로 경로 파싱·저장·제공 (Ruling 309) · 백엔드

**고정 계약 — T2 가 이 이름 그대로 소비한다. 바꾸려면 조율자에게 질문하라.**

```
GET /staff/runs/{runId}/route  (§5.19) 응답에 두 필드 추가
  "road_path":     [{"lat": 37.1234, "lng": 127.1234}, ...]   // 순서 있는 좌표 배열
  "fallback_used": false                                       // route_version.fallback_used 를 그대로
```

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | `NaverDirectionsGateway` 가 `route.traoptimal[].path` 를 파싱해 좌표를 싣는다 | 고정 응답 본문(`path` 3점 이상)을 물려 **좌표 개수와 첫·끝 값**을 대조. ⚠ **네이버는 `[경도, 위도]` 순서** — 뒤집어 넣으면 바다 위로 간다. 뒤집힌 값을 넣는 변형을 심어 실패를 확인 |
| 2 | `RoadLeg` 이 좌표를 나른다 | `record RoadLeg(int distanceMeters, int durationSeconds, List<GeoPoint> path)` 형태. **호출부 전수**를 `graft callers RoadLeg --depth all` 로 먼저 세고 보고 3항에 개수를 적는다 |
| 3 | **구간 분할을 이어 붙여도 좌표가 중복되지 않는다** | `maxWaypoints` 를 넘겨 2구간 이상으로 쪼개지는 입력. **이음매 좌표가 1번만** 나타난다. ⚠ 이 검사가 없으면 화면에 경로가 겹쳐 그려진다 |
| 4 | `route_version.road_path` 에 배포 시점의 전체 좌표가 저장된다 | `V1__init_schema.sql` 에 `road_path jsonb NULL` 추가(**새 테이블 부재** — 컬럼 하나). 저장 후 다시 읽어 개수·순서 대조 |
| 5 | §5.19 응답이 `road_path`·`fallback_used` 를 위 계약대로 낸다 | 컨트롤러 검사에서 **JSON 키 문자열을 직접** 대조(`$.road_path[0].lat`) |
| 6 | **근사 경로도 좌표를 준다** — `fallback_used=true` + 승하차지 좌표 그대로 | `forceFallback=true` 로 요청해 `road_path` 가 **비어 있지 않고** `fallback_used=true` |
| 7 | `StubMapRouteClient` 도 좌표를 만든다 | local·demo 에서 화면이 빈 경로를 받지 않는다 |
| 8 | 전체 실행 실패 0 · 오류 0 · **건너뜀 0** · `UP-TO-DATE`/`FROM-CACHE` 0 | `build/test-results/test/TEST-*.xml` 에서 직접 계수 |

- ⚠ **`V1` 을 고치면 체크섬이 바뀐다** — 보존 DB(`schoolbus`)를 포함해 재구성이 필요하다. 워크트리
  전용 DB 만 쓰고, `schoolbus` 는 **건드리지 마라**
- **범위 밖** — §4.3(매니저 앱)에는 넣지 않는다. Ruling 310 은 관계자 웹만 대상이다
- **구간별 거리·시간 근사값**(`StraightLineLegs.distribute`)은 **이번 범위 밖**이다. 좌표만 다룬다

### T2 — 지도 화면 개편 3종 (Ruling 310) · 관계자 웹

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | `MapSurface` 가 `polylines` 를 받는다 | `features/map/types.ts` 에 `MapPolyline { id, points: {lat,lng}[], kind }`. **화면은 `naver.maps.*` 를 모른다**(기존 경계 유지 — `mapAdapterBoundary.test.ts` 가 그것을 검사한다) |
| 2 | 3개 화면 전부 **지도가 상단 가득 + 우측 버스 목록** | `DashboardPage` · `TodayRunPage` · `MonitoringPage`. 각 화면의 `.styled` 에서 배치를 바꾼다 |
| 3 | 목록이 **4종 상태를 전부** 보인다 — `idle`(대기) · `confirmed`(확정) · `moving`(운행 중) · `finished`(운행 종료) | ⭐ **`getRunsLive()` 만 쓰면 `moving` 뿐이라 이 조건이 성립하지 않는다.** `getRuns(오늘)`(§5.10)로 목록을 만들고 `getRunsLive()` 의 위치를 `run_id` 로 합친다. **`finished` 가 목록에 남는지**를 단독 검사로 못박는다 |
| 4 | 버스를 고르면 **그 노선이 지도에 그려진다** | §5.19 를 불러 `road_path` 를 `polylines` 로 넘긴다. 선택 해제도 검사 |
| 5 | **`fallback_used=true` 면 "근사 경로" 를 화면에 표시** | 표시하지 않으면 사용자가 직선을 실제 경로로 믿는다(Ruling 309) |
| 6 | 전체 실행 실패 0 · 건너뜀 0 | 기준 **265건**(docs/archive/rounds/be-rounds-r5-r14.md §8.22) — 인용이다. 직접 세라 |

- ⚠ **T1 의 계약은 위 코드 블록이 전부다.** T1 이 아직 안 끝났어도 그 이름으로 붙여 두고 진행하라.
  실제 응답과 어긋나면 **고치지 말고 조율자에게 질문**한다
- **§5.19 를 부르는 프런트 클라이언트는 부재하다** — 새로 만든다(`features/route/api`)
- ⚠ **지도 SDK 인증은 `localhost:3000` 에서만 된다**(`CLAUDE.md`). 다른 포트로 띄우면 401

### T3 — 알림 발송 시점 이동 (Ruling 308 · 311 · 312) · 백엔드

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | `boarded`·`alighted` 가 **그 자리에서 알림을 만들지 않는다** | `appendRiderStatusChanged` 직후 `notification_log` 가 **0건**. ⚠ WebSocket 방송(`RiderChangedBroadcastListener`)은 **그대로 둔다** — 저쪽은 현황 갱신이지 알림이 아니다 |
| 2 | **`claimDeparture` 가 1행을 갱신한 직후**에만 알림이 적재된다 | `ProximityNotificationService.judgeDeparture` 가 `StopDepartedEvent` 를 발행. **선점이 0행이면 발행 부재** |
| 3 | 그 승하차지의 **확정 결과**가 학생별로 1건씩 나간다 | 승차→되돌리기→승차 를 반복해도 출발 후 알림은 **학생당 1건**. 내용은 **마지막 상태** |
| 4 | **되돌리기 정정 알림이 사라진다** | `appendRiderStatusReverted` 제거. `BOARDING_CANCELED`·`ALIGHTING_CANCELED` 적재가 **0건**(Ruling 308 이 Ruling 219 를 대체) |
| 5 | **미승차(BRD-04) 학부모 갈래도 출발 시점으로 옮긴다** | `appendToGuardians` 가 출발 시점 경로로 이동 |
| 6 | ⭐ **관계자 미승차 알림은 즉시 유지**(Ruling 311) | `appendToStaff` 는 **그대로**. 이동시키면 실패하는 검사를 남긴다 |
| 7 | **폴백 — 다음 승하차지 도착 시 강제 발송** | 출발 판정이 안 된 채 다음 승하차지에 도착하면 이전 승하차지에 `claimDeparture` 를 강제. `RunArrivalCommandService.arrive` |
| 8 | ⭐ **마지막 승하차지도 발송된다**(Ruling 312) | 운행 종료 시 도착·미출발 전부에 강제 적용. **단독 검사로 못박는다** — R14 에서 같은 형태의 구멍을 사용자가 잡았다 |
| 9 | **출발 시점에 결과가 없는 학생은 발송 대상 부재** | `waiting` 인 채 출발한 학생에게 알림이 나가지 않는다 |
| 10 | 전체 실행 실패 0 · 오류 0 · **건너뜀 0** | 기준 **1,334건**(docs/archive/rounds/be-rounds-r5-r14.md §8.22) — 인용이다. 직접 세라 |

- **새 컬럼 부재** — 멱등의 유일한 근거는 `claimDeparture` 의 조건부 UPDATE 다. `notified_at` 류를
  새로 만들지 마라
- ⚠ **`NTF-01`·`NTF-02`·`BRD-04`·`BRD-05` 의 정본 문면(`FEATURE_SPEC §4.15`)은 이미 Ruling 308 로
  개정돼 있다** — 문서를 다시 고치지 않는다. 코드를 그 문면에 맞춘다

### 갈래 배정 · 충돌

| 갈래 | 모델 | 건드리는 곳 | 충돌 |
|---|---|---|---|
| `r15-t1` | `claude-sonnet-5[1m]` · high | `routing/map/**` · `routing/pipeline` · `V1` · `run/dto`·`run/query`(§5.19) | — |
| `r15-t2` | `claude-sonnet-5[1m]` · high | `frontend/apps/academy-web/**` | — |
| `r15-t3` | `claude-sonnet-5[1m]` · high | `location/proximity` · `notification/command` · `boarding/**` · `run/command` | — |

**세 갈래가 파일을 공유하지 않는다.** T2 만 T1 의 **응답 계약**에 의존하며, 그 계약은 위 코드 블록에
고정돼 있다 — 파일 의존이 아니라 이름 의존이라 병렬로 간다.

### ⚖ Ruling 313 — 지도 개편 요구를 **배치**와 **4종 상태 목록** 두 갈래로 가른다 (2026-09-19, R15-T2 질문에서)

`r15-t2` 가 목표 표 T2-3(4종 상태)의 성립 조건을 조사하다 **`MonitoringPage` 에는 그 재료가 부재**함을
찾아냈다. 조율자가 정본에서 직접 확인해 아래로 판정한다.

| 요구 | 적용 범위 |
|---|---|
| **배치 개편** — 지도 상단 가득 + 우측 버스 목록 + 클릭 시 노선 | ⭐ **3개 화면 전부** (Ruling 310 그대로) |
| **4종 상태 목록**(`idle`·`confirmed`·`moving`·`finished`) | **`DashboardPage`·`TodayRunPage` 2개만** |

**`MonitoringPage` 가 `moving` 만 보이는 것은 결함이 아니라 그 화면의 정의다.**

- `§6.8 GET /admin/academies/{id}/runs/live` 문면 — *"**`moving` 회차가 관제 대상**"*. 기능은 `O-05`
  **실시간 경로 추적**이고 이 화면은 **플랫폼 관리자**용이다(학원 관계자용 `§5.3`·`§5.18` 과 다른 역할)
- ⚠ **필터만 넓힐 수 없다** — `§6.8` 응답이 `stops[].eta` · `destination_eta` · `est_depart_time` 을
  **필수(●)** 로 요구하는데 `idle` 회차에는 그 값이 **존재하지 않는다.** 넓히면 응답 계약이 깨진다.
  한 줄짜리 변경이 아니라 **별도 설계 결정**이다 ⇒ **이월**

### ⭐ 같은 질문이 목표 표의 오기를 하나 고쳤다

T2 표 3항은 목록 재료로 **`getRuns()`(§5.10)** 를 쓰라고 적었으나, **`getDashboard()`(§5.3) 의 `runs[]` 에
`run_status` enum 이 이미 `idle`·`confirmed`·`moving`·`finished` 4종으로 있다** — 배치 인력까지 같은
응답에 실려 온다. ⇒ **`getDashboard()` 를 쓰고 `getRuns()` 는 쓰지 않는다.**

조율자가 §5.10 까지만 확인하고 §5.3 을 안 본 것이다. **갈래의 실측이 목표 표를 이긴다**
(`phase-goal-loop §6` — 목표 표의 수치·경로는 인용이지 사실이 아니다).

### ⚖ Ruling 314 — 기능간 `import` 금지는 **이 저장소에서 이미 지켜지지 않는 규칙**이다. T2 의 선택을 수용한다 (2026-09-19)

`r15-t2` 가 *"`features/run`·`features/admin` 이 `features/route` 의 `getRunRoute` 를 가져다 쓴 것은
`docs/frontend/CONVENTIONS_REACT.md:122` 의 **'기능끼리 서로 import 하지 않는다'** 를 어긴다"* 고 **자진 신고**했다.

**조율자 실측 — 메인 트리(T2 변경 이전)에 이미 12건이 있다.**

| 방향 | 건수 |
|---|:-:|
| `schedule → bus` | 4 |
| `run → map` · `route → schedule` · `route → bus` | 각 2 |
| `run → auth` · `admin → map` | 각 1 |

⇒ T2 가 만든 `run → route`·`admin → route` 는 **새 전례가 아니라 기존 관행과 같은 모양**이다.

**⭐ 결정적 근거 — `shared/` 로 올리면 더 구체적인 규칙을 어긴다.**
같은 문서가 바로 아래 절에서 *"**API 호출은 기능 안에서만** … 각 기능의 `api/` 가 그 클라이언트를 쓴다"*
고 정한다. `getRunRoute` 는 API 클라이언트이므로 `shared/` 로 옮기면 **그 규칙과 정면으로 충돌**한다.
두 규칙이 충돌하는 자리에서는 **대상을 특정한 쪽(API 절)이 이긴다.**

- **이월** — `CONVENTIONS_REACT.md:122` 가 실제 코드와 12건 어긋난다. 문서를 실태에 맞게 고치거나
  예외 조건을 명시해야 한다. **문서가 낡은 것이지 코드가 틀린 것이 아니다**
- ⚠ **자진 신고가 이 판정의 유일한 입력이었다** — T2 가 적지 않았으면 규칙 위반으로 남았을 것이고,
  다음 사람이 그것을 근거로 `shared/` 로 옮겨 API 규칙을 깨뜨렸을 것이다

### ⚠ 관계자 웹 검사 수 265 의 정체 — 병합 검증에 **백엔드 기동이 필요하다**

`r15-t2` 는 **198건**(실패 0·건너뜀 0)을 보고하고 *"docs/archive/rounds/be-rounds-r5-r14.md §8.22 의 265 는 `realBackend` 를 포함한 수치로 추정"*
이라고 적었다. 조율자가 확인한 결과 **맞다**:

- docs/archive/rounds/be-rounds-r5-r14.md §8.22 계열 기록이 일관되게 **"69파일"** 로 적혀 있고, T2 워크트리 실측은 **일반 56파일 + `realBackend` 13파일 = 69**
- `realBackend` 검사는 **살아 있는 백엔드 + `NEXT_PUBLIC_API_BASE_URL`** 을 요구한다

⇒ **병합 후 전체 실행에서 관계자 웹이 265 를 채우려면 백엔드를 먼저 띄워야 한다.**
띄우지 않고 198 만 보고 "통과" 로 판정하면 **67건이 한 번도 안 돈 채 넘어간다**(`phase-goal-loop §5.2 4.1`).

## 8.24 ⚖ `R15` 결과 — 도로 경로 + 지도 개편 + 알림 발송 시점 (2026-09-19 **완료**)

| 갈래 | 결과 |
|---|---|
| `r15-t1` | **네이버 `path` 파싱·저장·제공**(Ruling 309) — `RoadLeg` 에 좌표 · `route_version.road_path jsonb` 신설 · §5.19 에 `road_path`·`fallback_used` · **실 API 실측 검증** |
| `r15-t2` | **지도 화면 3종 개편**(Ruling 310·313) — 지도 상단 가득 + 우측 버스 목록 + 클릭 시 도로 경로 · `MapSurface` 에 `polylines` |
| `r15-t3` | **알림 발송 시점 이동**(Ruling 308·311·312) — `StopDepartedEvent`·`StopDepartureService` 단일 진입점 · 정정 알림 제거 · **등원 최종 지점 중복 발송 수정** |

### 검사 수 — 병합 후 단독 전체 실행

| 대상 | 기준(docs/archive/rounds/be-rounds-r5-r14.md §8.22) | 실측 |
|---|---:|---:|
| 백엔드 | 1,334 | **1,342**(228클래스) |
| 관계자 웹 | 265 | **275** |

**실패 0 · 오류 0 · 건너뜀 0 · `UP-TO-DATE`/`FROM-CACHE` 0.**
⚠ 관계자 웹은 **백엔드를 띄운 상태**(전용 DB `sb_r15_boot` · 포트 8130)에서 재고, **연달아 2회** 돌려
둘 다 275/275 임을 확인했다(§5.4 의 "마르는 자원" 형태를 이 회차에 실제로 밟았기 때문).

### ⭐ 실 API 실측이 가장 큰 값을 했다 — 조율자 누락을 사용자 규칙이 잡았다

`r15-t1` 의 첫 보고는 **건너뜀 3건**이었고 *"기존 NCP 자격증명 미보유 Live 검사"* 로 분류했다. 분류는
옳았으나 **원인이 조율자 잘못**이었다 — `git worktree` 는 **git 이 무시하는 파일을 받지 않아**
`backend/.env` 가 워크트리에 부재했다(Skill `parallel-agents §12.1` 이 이미 적어 둔 함정).

⚠ **그 결과가 심각했다** — 건너뛴 `NaverDirectionsClientLiveTest` 는 **이번 변경에 가장 직접적인 검사**다.
`path` 파싱을 검증한 대상이 **좌석이 만든 고정 응답**뿐이라, 실제 API 와 어긋나면 **둘 다 일관되게
틀린 상태**가 어떤 검사로도 안 드러난다.

⇒ 조율자가 `.env` 를 3개 워크트리에 넣고 **후속 Dispatch 로 실측을 시켰다.** 결과(2026-09-19 실측,
시청→강남역): `code=0` · **`path` 322점** · **`[경도, 위도]` 순서** · 전 좌표 한반도 범위(위도 33~39 ·
경도 124~132) 안. **고정 응답과 실 API 가 일치해 코드 수정은 불필요했고, 그 사실을 회귀 검사로 고정**했다.

### ⭐ 자진 신고가 운영 결함 1건을 막았다 (`r15-t3`)

`r15-t3` 이 보고서 2항에 *"등원 최종 지점에서 기존 자동하차 알림과 신규 출발 알림이 같은 학생에게
중복 발송될 수 있다"* 를 적었다. **조율자가 확인한 결과 실제 결함이었고 두 겹이었다.**

| # | 문제 |
|:-:|---|
| 1 | **중복** — `alightAllBoarded` 가 `ALIGHTING` 을 보낸 직후 `forceAllRemaining` 이 같은 학생을 `ALIGHTED` 로 읽어 **`ALIGHTING` 을 또** 적재. `dedup_key` 형태가 달라 **Outbox 2차 방어선도 못 막는다** |
| 2 | **내용 오류** — 학생이 **승차한** 정차지의 출발 알림이 *"하차"* 로 나간다 |

**발생 조건** — 등원 + 정차지 출발이 운행 중에 판정되지 않아(신호 유실) 운행 종료 시 강제 적용되는 경우.
**정상 흐름에서는 안 나서 기존 검사가 못 잡았다.**
⇒ 수정: `forceAllRemaining` 을 `alightAllBoarded` **앞으로** 옮겼다(`d30c733b`). 재현 검사를 새로 만들고
순서를 되돌리면 그 검사만 `[alighting, alighting]` 으로 실패하는 것을 확인했다.

### ⚠ 병합 후에만 드러난 것 1건 — **R15 이전부터 있던 잠재 결함**

전체 실행에서 `features/admin/api/realBackend.test.ts` 의 §6.11 시험이 **1건 실패**했다(`expected 0 to
be greater than 0`). **단독 실행은 16/16 통과.**

**원인** — `features/emergency/api/realBackend.test.ts` 가 시드의 **유일한** 비상 신고(`emergency_id=1`)를
확인 처리(`ack`)하는데 **되돌릴 API 가 부재**하다. 뒤에 도는 파일이 미확인 0건을 보고 실패한다 —
`phase-goal-loop §5.4` 의 **"마르는 자원"** 형태 그대로다.

- ⚠ **R15 가 그 두 파일을 건드리지 않았다**(`git diff c323975a..HEAD` 빈 결과). **R15 가 추가한 프런트
  검사 10건으로 파일 순서가 바뀌면서 드러난 것**이다
- **수정** — 소비하는 쪽이 `afterAll` 에서 시드를 재구성한다(`7ad34033`). `run/api/realBackend.test.ts`
  가 이미 쓰던 **같은 근거·같은 헬퍼**를 재사용했다(새 장치 미생성)

### 새 판정 — `Ruling 311`~`314`

`311` 관계자 미승차 알림은 즉시 유지 · `312` 마지막 승하차지는 운행 종료가 출발로 갈음 ·
`313` 지도 개편을 배치/4종 상태로 분리 · `314` 기능간 `import` 는 이 저장소에서 이미 관행

### 이월

| 항목 | 사유 |
|---|---|
| ⭐ **`MonitoringPage` 4종 상태 미적용** | `§6.8` 이 `moving` 전용이고 응답이 `stops[].eta`·`destination_eta` 를 **필수**로 요구해 `idle` 회차를 담을 수 없다. **`§6.8` 응답 설계 변경이 선행**(Ruling 313) |
| `CONVENTIONS_REACT.md:122` 가 실태와 12건 어긋남 | 기능간 `import` 금지 조항. **문서가 낡은 것**(Ruling 314) |
| 구간별 거리·시간 근사값 | `StraightLineLegs.distribute` — R14 에서 이월, R15 범위 밖 |
| `§4.3`(매니저 앱)에 `road_path` 부재 | Ruling 310 이 관계자 웹만 대상이라 의도적 제외 |

## 8.25 ⚖ `R16` 목표 표 — `§6.8` 을 넓혀 관제 화면에 4종 상태를 띄운다 (2026-09-19 착수)

**근거** — `Ruling 313` 이 `MonitoringPage` 의 4종 상태를 **이월**로 남겼다. 사용자 지시로 그 이월을 닫는다.

### ⚠⚠ `Ruling 313` 의 근거 한 줄을 **정정한다** — 조율자 오판

`Ruling 313` 은 *"`§6.8` 응답이 `stops[].eta`·`destination_eta`·`est_depart_time` 을 **필수(●)** 로 요구하는데
`idle` 회차에는 그 값이 존재하지 않으므로, 필터만 넓히면 응답 계약이 깨진다. **한 줄짜리 변경이 아니다**"*
라고 적었다. **틀렸다.**

**조율자가 사양 표의 `●` 표기만 보고 구현을 안 읽었다.** 실제 구현은 **이미 비-운행 회차를 견딘다**:

| 우려한 필드 | 실제 구현 |
|---|---|
| `stops[]` | `orderedStopsOf()` 가 확정 노선 부재 시 **`List.of()` 반환** — 예외 부재 |
| `destination_eta` | `destinationEtaOf()` 가 `estDurationMin == null` 이면 **`null` 반환** |
| `est_depart_time` | `run.getStartedAt()` — 미시작이면 **`null`** |
| `position` | 신호 부재·유실이면 **`null`**(기존 규칙) |
| `run_status` | **이미 응답 필드로 존재** |

⇒ **`●` 는 "JSON 키가 반드시 존재한다" 는 뜻이지 "값이 반드시 있다" 가 아니다.** 이 저장소는 `null` 도
직렬화한다(§6.8 예시 자체가 `"eta": null` 을 담고 있다).

⚠ **재발 방지 — 사양 표의 표기로 구현 가능성을 판정하지 마라. 구현을 열어서 확인한다**
(`phase-goal-loop §6` 의 "파생본을 판정 기준으로 쓰지 않는다" 와 같은 계열 — **사양도 코드에 대해서는 파생본이다**).

### 📌 함께 발견한 결함 — `§6.8` 에 **날짜 조건이 부재**하다

`AdminAcademyLiveQueryService.live()` 는 `findAllByAcademyIdAndStatusOrderByDepartTimeAsc(academyId, MOVING)`
를 부른다 — **날짜로 좁히지 않는다.** `moving` 회차가 오늘 것뿐이라 지금은 드러나지 않지만,
상태 조건을 빼는 순간 **과거 회차 전부**가 딸려 온다.

⭐ 대응 수단이 **이미 있다** — `RunRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc`.
`§5.18`(`StaffRunLiveQueryService`)이 그것을 쓰고 있다(`LocalDate.now(clock)` → 조회 → `moving` 필터).
**새 조회 메서드를 만들지 않는다.**

### 목표 표

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | `§6.8` 이 **오늘 회차 4종 상태 전부**를 돌려준다 | `idle`·`confirmed`·`moving`·`finished` 각 1건을 심고 **4건 전부** 나오는지. `run_status` 값도 대조 |
| 2 | ⭐ **어제·내일 회차는 나오지 않는다** | 날짜 조건 부재 결함을 못박는다. **이 검사가 없으면 과거 전부가 딸려 온다** |
| 3 | **기존 검사를 뒤집는다** — `운행중이_아닌_회차는_목록에_나오지_않는다` | 그 검사는 **지금 동작을 고정**하고 있다. 이름·단언을 새 계약으로 바꾼다(`phase-goal-loop §3` — 단언이 결함을 굳히는 자리) |
| 4 | 비-운행 회차의 필드가 **규칙대로 빈다** | `idle` → `stops` **빈 배열** · `position` `null` · `est_depart_time` `null`. `finished` → `stops` 채워짐 · `arrived_at` 있음 |
| 5 | 정렬은 `depart_time` 오름차순 유지 | 기존 계약 |
| 6 | **정본 `§6.8` 문면을 고친다** | *"`moving` 회차가 관제 대상"* 문장을 바꾸고, 비-운행 회차에서 비는 필드를 표로 적는다 |
| 7 | `MonitoringPage` 가 **4종 상태를 보인다** — `finished` 포함 | `finished` 가 목록에 남는지 **단독 검사**로 못박는다(Ruling 310 사용자 확정) |
| 8 | 백엔드 전체 실패 0 · 오류 0 · **건너뜀 0** | 기준 **1,342**(§8.24) — 인용이다. 직접 세라 |
| 9 | 관계자 웹 전체 실패 0 · 건너뜀 0 | 기준 **275**(§8.24). ⚠ **백엔드를 띄운 상태로** 돌린다 |

- **범위 밖** — `§5.18`(관계자용)은 그대로 둔다. `DashboardPage`·`TodayRunPage` 는 이미 `§5.3` 으로 4종을 받는다
- **`Ruling 313` 의 표(배치 3종 / 4종 상태 2종)는 이 작업으로 무효가 된다** — 완료 시 3종 전부 4종 상태

## 8.26 ⚖ `R16` 결과 — `§6.8` 을 오늘 4종 상태로 넓힘 (2026-09-19 **완료**)

`Ruling 313` 이 남긴 이월(**`MonitoringPage` 4종 상태 미적용**)을 닫았다. **`Ruling 313` 의 근거 한 줄은
오판이었고 `§8.25` 에서 정정했다** — 사양 표의 `●` 를 "값이 반드시 있다" 로 읽었으나 실제 구현은
이미 비-운행 회차를 견디고 있었다.

### ⚖ Ruling 315 — `§6.8` 은 **그 학원의 오늘 회차를 상태와 무관하게 전부** 돌려준다

`idle`·`confirmed`·`moving`·`finished` 4종이 모두 담기며 **운행이 끝난 차량도 목록에 남는다**
(`Ruling 310` 사용자 확정). 날짜는 **오늘 고정**이고 질의 파라미터를 두지 않는다 — 과거 조회는 `§5.10`.

### 📌 함께 고친 결함 — `§6.8` 에 **날짜 조건이 아예 없었다**

옛 구현은 `findAllByAcademyIdAndStatusOrderByDepartTimeAsc(academyId, MOVING)` 로 **상태만** 걸렀다.
`moving` 이 사실상 오늘 것뿐이라 드러나지 않았을 뿐이고, **상태 조건을 빼는 순간 과거 회차 전부가
딸려 왔을 것**이다. ⇒ `§5.18` 이 이미 쓰던 `findAllByAcademyIdAndServiceDate...` 를 그대로 재사용했다
(**새 조회 메서드 미생성**).

### 검사 수 — 단독 전체 실행

| 대상 | 기준(§8.24) | 실측 |
|---|---:|---:|
| 백엔드 | 1,342 | **1,344**(228클래스) |
| 관계자 웹 | 275 | **277** |

**실패 0 · 오류 0 · 건너뜀 0.** 관계자 웹은 **백엔드를 띄운 상태**(전용 DB `sb_r16_boot` · 포트 8130)로
**연달아 2회** 돌려 둘 다 277/277.

### ⭐ 음성 대조가 **조율자 자신의 검사 구멍**을 잡았다

`다른_날짜_회차는_목록에_나오지_않는다` 에 날짜를 하루 미는 변형을 심었더니 **그 검사가 통과했다.**
세 회차가 전부 `confirmed` 라 **개수(1건) + 상태(confirmed)** 만으로는 어제·오늘·내일이 구별되지 않았다.
⇒ 단언을 **`run_id`** 로 바꿔 못박았고, 같은 변형을 다시 심으니 잡혔다(실패 7건 → 8건).

**교훈 — "몇 건인가" 가 아니라 "어느 건인가" 를 검사해야 범위 조건이 고정된다.**

변형 2(`moving` 필터 부활)는 **새 검사 3개만 실패하고 기존 10개는 통과** — 새 검사가 실제로 새로운
검증력을 더했다는 증거다.

### ⭐ 실서버 계약 검사가 프런트 회귀를 잡았다

`§6.8` 이 넓어지자 **배치 전(`idle`·`confirmed`) 회차**가 목록에 들어왔고, 그 회차는 매니저 배치가 아직
없어 `driver`·`escort` 가 `null` 이다. 관계자 웹의 변환 계층은 **`moving` 만 오던 시절의 가정**으로
`raw.name` 을 바로 읽어 **`TypeError` 로 죽었다.**

- 단위 검사는 전부 초록이었다 — **가짜 응답이 옛 모양이라** 드러나지 않는다
- **`getAcademyRunsLive` 실서버 계약 검사가 유일한 탐지 수단**이었다
- ⇒ 변환·타입을 `null` 허용으로, 화면은 **"미배치"** 로 표시(관제에서 배치 누락은 관리자가 봐야 하는 정보다)
- 정본 `§6.8` 에 **`driver`·`escort` 도 `null` 일 수 있음**을 명시

⚠ **`●` 표기는 "키의 존재" 이지 "값의 존재" 가 아니다** — 이 회차에 **두 번** 같은 형태를 밟았다
(조율자의 Ruling 313 오판 · 프런트의 옛 가정).

### 이월 해소·잔여

| 항목 | 상태 |
|---|---|
| ~~`MonitoringPage` 4종 상태 미적용~~ | ✅ **해소**(Ruling 315) |
| `CONVENTIONS_REACT.md:122` 가 실태와 12건 어긋남 | 잔여(Ruling 314) |
| 구간별 거리·시간 근사값 | 잔여 |
| `§4.3`(매니저 앱)에 `road_path` 부재 | 잔여(의도적 제외) |

## 8.27 ⚖ `R17` 계획 — 출발 판정 검사 보강 + 구간 ETA 정확도 + 정리 (2026-09-19 작성 · **착수 전**)

> 🔴 **이 절은 맥락이 없는 새 세션이 그대로 실행할 수 있게 쓴다.** 조율자는 아래 §0 부터 순서대로 따른다.

### 0. 조율 세션이 착수 전에 하는 것 (순서 고정)

1. **Skill `orchestration` 을 먼저 호출**하고, 거기 지시대로 `orca skills get orchestration` 으로
   바이너리가 주는 가이드를 받는다. **CLI 를 기억으로 다루지 않는다** — 2026-09-19 에 그러다 영구 대기에 빠졌다
2. **이 목표 표가 커밋돼 있는지 확인**한다 — `git log --oneline -1`. 워크트리는 **추적 파일만** 가져가므로
   커밋 전에 만들면 갈래가 완료 조건을 못 읽는다(R13 실제 사고)
3. 전용 DB 3개를 만든다
   ```bash
   for db in sb_r17_t1 sb_r17_t2 sb_r17_t3; do
     docker exec school-bus-postgres-1 psql -U schoolbus -d postgres -c "CREATE DATABASE $db"
   done
   ```
4. **Run 을 만들고 갈래 3개를 한 번에 띄운다** — 모델은 아래 배정표대로
   ```bash
   orca orchestration run-create --objective "R17 — 출발 판정 검사 + 구간 ETA + 정리"
   orca orchestration worker-start --spec "<지시>" --worktree new-top-level --name r17-t1 \
     --setup skip --agent claude --model 'claude-sonnet-5[1m]' --effort high --task-title "r17-t1-...-sonnet1m"
   ```
5. ⚠⚠ **워크트리를 만든 직후 무시 대상 파일을 복사한다** — R15 최대 사고의 재발 방지
   ```bash
   for w in r17-t1 r17-t2 r17-t3; do
     cp backend/.env /Users/mskim/orca/workspaces/School-Bus/$w/backend/.env
     cp frontend/apps/academy-web/.env.local /Users/mskim/orca/workspaces/School-Bus/$w/frontend/apps/academy-web/.env.local
   done
   ```
   **없으면 NCP 자격증명이 필요한 검사가 종료 코드 `0` 으로 조용히 건너뛴다.** R15 에서 이번 변경에 가장
   직접적인 검사가 그렇게 안 돌았다
6. 발주 직후 **막아서 기다리는 호출**을 건다 —
   `orchestration check --wait --types "worker_done,escalation,question" --timeout-ms 2400000`.
   ⚠ **생존 신호(heartbeat)가 큐 앞을 막으므로** 주기적으로 걷어낸다(그것만 든 배치는 `--ack` 한다)

### 배정표

| 갈래 | 모델 | 건드리는 곳 | 겹침 |
|---|---|---|---|
| `r17-t1` | `claude-sonnet-5[1m]` · high | `location/proximity` · `routing/repository` **시험** | — |
| `r17-t2` | `claude-sonnet-5[1m]` · high | `routing/map/impl`(`StraightLineLegs`·`NaverDirectionsGateway`) | — |
| `r17-t3` | `claude-sonnet-5[1m]` · high | `notification/domain` · `docs/frontend/CONVENTIONS_REACT.md` · `docs/frontend/IMPLEMENTATION_PLAN.md` | — |

**셋이 파일을 공유하지 않는다.** ⚠ **T3 은 `docs/IMPLEMENTATION_PLAN.md` 를 건드리지 않는다** — 조율자가 쓴다.

---

### T1 — 출발 판정의 검사 공백 2건 (운영 결함 위험이 가장 큼)

**왜 먼저인가** — 나머지 항목은 품질·문서인데 이것만 **잘못되면 학부모 알림이 안 나가거나 두 번 나간다.**

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | **`claimDeparture` 동시성 검사**를 만든다 | 본보기가 이미 있다 — `RunStopProximityClaimConcurrencyTest`(근접 선점). **같은 형태로 출발 선점을 검사**한다: 두 스레드가 같은 정차 항목을 동시에 선점해도 **1회만 성공**. 새 장치를 만들지 말고 그 클래스의 구조를 따른다 |
| 2 | **출발 판정 대상 선정을 검증**한다 | `findFirstArrivedNotDeparted` 는 *"도착·미출발 중 `seq` 최솟값 1건"* 이다. **신호 유실로 과거 정차지가 여러 개 쌓인 상황**을 만들어, ①`seq` 가 가장 작은 것이 대상이 되는지 ②나머지가 다음 틱에 순서대로 처리되는지 검사 |
| 3 | ⭐ **쌓인 것이 운행 종료에 전부 해소되는지** | `forceAllRemaining`(Ruling 312)이 **여러 건**을 한 번에 처리하는 경로. 1건짜리 검사만 있으면 누락이 안 보인다 |
| 4 | 전체 실행 실패 0 · 오류 0 · **건너뜀 0** | 기준 **1,344**(§8.26) — 인용이다. 직접 세라 |

- **이 갈래는 코드를 거의 안 고친다.** 검사만 더한다. 고칠 것이 나오면 그것이 이 갈래의 수확이다
- 검사 명령 — `./gradlew test -PtestDbUrl=jdbc:postgresql://localhost:15432/sb_r17_t1 --rerun`

### T2 — 구간별 거리·시간을 **실제 도로 좌표**에서 낸다 (ETA 정확도)

**문제** — `StraightLineLegs.distribute` 가 네이버의 **총합**을 **직선거리 비율**로 구간에 배분한다.
정상 응답일 때조차 그렇다. 그 값이 **학부모 화면의 도착 예정 시각**이 된다.

⚠⚠ **착수 전에 반드시 확인할 것 — 이것이 이 갈래의 성립 조건이다.**
지금 파싱하는 것은 `Traoptimal(Summary summary, List<List<BigDecimal>> path)` **둘뿐**이다.
코드 주석은 *"NCP 가 경유지별 구간 값을 주지 않는다"* 고 적었으나 **그 주장을 직접 확인하지 않았다.**

**⇒ 실 API 를 직접 불러 응답 전문을 보고, 아래 중 무엇이 오는지 실측하라.**
`backend/.env` 에 자격증명이 있고, R15 가 만든 `NaverDirectionsClientLiveTest` 가 본보기다.

| 후보 | 있으면 |
|---|---|
| `summary.waypoints[]` (경유지별 위치·인덱스) | `path` 를 그 인덱스로 잘라 **구간별 실제 도로 거리**를 낸다 |
| `guide[]` 의 `pointIndex`·`distance`·`duration` | 같은 방식 + **구간별 시간도 실측값** |
| `section[]` | 도로 구간이지 경유지 구간이 아닐 수 있다 — **확인하고 판단하라** |
| 아무것도 없다 | ⭐ **그 사실을 실측으로 고정하고(회귀 검사) 차선으로 간다** — 직선거리 비율 대신 **`path` 에서 잰 실제 도로 거리 비율**로 배분. 좌표는 이미 있으므로 이것만으로도 개선된다 |

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | **실 API 응답에 무엇이 오는지 실측하고 보고서 1항에 적는다** | 추측 금지. `curl` 원문 또는 Live 검사로 확인 |
| 2 | 구간별 거리가 **직선 비율이 아니라 도로 기준**이 된다 | 굽은 경로를 물려 **직선거리 비율 배분과 값이 달라지는지** 대조. 같으면 아무것도 안 바뀐 것이다 |
| 3 | **갈린 값의 합은 총합과 정확히 같다**(기존 계약 유지) | 잔차를 마지막 구간에 몰지 않는 기존 규칙을 깨지 마라 |
| 4 | **폴백 경로는 그대로** | 지도 API 장애 시 직선 근사는 유지한다(`TECH_DECISIONS §8`) |
| 5 | 전체 실행 실패 0 · 오류 0 · **건너뜀 0** | `.env` 가 있으므로 Live 검사가 **실제로 돌아야** 한다 |

- ⚠ **응답 레코드를 넓히면 기존 고정 응답 검사가 깨질 수 있다** — `graft callers` 로 먼저 세라
- ⚠ 실 API 가 간헐 503 을 낸다(R14 이월). **코드 결함이 아니다** — 재시도하고 검사를 약화시키지 마라
- 검사 명령 — `./gradlew test -PtestDbUrl=jdbc:postgresql://localhost:15432/sb_r17_t2 --rerun`

### T3 — 정리 묶음 (알림 문구 1건 + 낡은 문서 2건)

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | **`change_decided` 알림 문구에 자녀 이름을 넣는다** | `ChangeRequestAutoRejectedNotificationListener` 계열. R14 가 정본의 "토글" 문구를 좁게 읽어 제외했고 **확신 70% 로 자진 신고**한 항목이다. **다자녀 가정이 같은 시각에 두 자녀 결과를 받으면 구분할 수단이 부재**하다. `ATT-03` 이행 형태를 따른다(R14-T1 이 토글 6종에 한 것) |
| 2 | **`docs/frontend/CONVENTIONS_REACT.md:122` 를 실태에 맞게 고친다** | *"기능끼리 서로 import 하지 않는다"* 가 **실제로 12건 어긋난다**(`schedule→bus` 4 · `run→map` 2 · `route→schedule` 2 · `route→bus` 2 · `run→auth` 1 · `admin→map` 1). **직접 다시 세고** 어긋나면 보고하라. ⚠ **코드를 고치지 마라 — 문서가 낡은 것이다**(Ruling 314). 예외 조건을 명시하는 방향으로 고친다 |
| 3 | ⭐ **`docs/frontend/IMPLEMENTATION_PLAN.md` 의 진행 표를 실태에 맞게 고친다** | **5건이 "🔜 착수 전"·"미구현" 으로 남아 있는데 이미 완료**다(2026-09-19 조율자 실측). 인용이다 — **직접 확인하고 어긋나면 보고하라** |
| 4 | 관계자 웹 전체 실패 0 · 건너뜀 0 | 기준 **277**(§8.26). ⚠ **백엔드를 띄운 상태로** 돌린다 |
| 5 | 백엔드 전체 실패 0 · 오류 0 · 건너뜀 0 | 문구 변경의 파급 확인 |

**목표 3 의 대상 — 조율자 실측(인용이다. 직접 확인하라)**

| 표기 | 실측 |
|---|---|
| `BE-R2` "🔜 계획 완료·착수 전" | **완료** — `§8.3` 에 *"✅ `BE-A` 종결 — `Ruling 282` 수정"*(2026-09-14) |
| `FE-R3` "🔜 계획 완료·착수 전" | 대상 2건이 **구현됨**(아래) |
| `RouteDetailScreen` "자리표시 14줄" | **291줄** + 검사 222줄 |
| `LiveMapScreen` "`§3.11` REST 미구현" | **403줄** + `bus_position_api`·저장소 구현 |
| `arrive` 학생 본인 수신 · `bus_no` 확장 | 둘 다 **구현됨**(R14) |

---

### 1. 완료 후 조율자가 하는 것 (순서 고정)

1. **각 갈래의 보고를 독립 실측으로 검증**한다 — `build/test-results/test/TEST-*.xml` 에서 직접 계수.
   **보고서 수치를 그대로 옮기지 않는다**
2. **병합** — T1 → T2 → T3 순(겹침이 없어 순서는 무관하나 기록을 위해 고정)
3. `./gradlew compileJava compileTestJava` — 충돌 해소 병합 뒤 필수
4. **백엔드 전체 단독 실행** — 새 DB(`sb_r17_final`)로
5. ⚠ **관계자 웹은 백엔드를 띄우고 돌린다** — 안 띄우면 `realBackend` 14파일이 실패한다
   ```bash
   ./gradlew bootRun --args='--server.port=8130 --spring.datasource.url=jdbc:postgresql://localhost:15432/sb_r17_boot --spring.profiles.active=local'
   cd frontend/apps/academy-web && NEXT_PUBLIC_API_BASE_URL=http://localhost:8130 npx vitest run
   ```
   ⚠ **`pnpm` 은 PATH 에 부재**하고 의존성은 `frontend/apps/academy-web/node_modules` 에 있다 — `npx vitest`
6. **연달아 2회** 돌려 둘 다 같은 수치인지 본다(마르는 자원 형태를 R15 에서 실제로 밟았다)
7. **정산** — 각 갈래에 재사용·`worker-retain`·`worker-release` 중 정확히 하나.
   `worker-list --terminal-state reclaimable` 이 0건이 되기 전에 턴을 끝내지 않는다
8. **자원 정리** — 프로세스 먼저 멈추고, 워크트리·브랜치·DB 순. ⚠ **`WITH (FORCE)` 금지** ·
   **`schoolbus` 보존**. `docker inspect` 로 `RestartCount` 가 안 늘었는지 확인
9. 결과를 **`§8.28`** 로 기록하고 기억 파일(`school-bus-r17-done`)을 쓴다

### 2. 이 회차에서 특히 조심할 것 (앞선 회차의 실제 사고)

| 사고 | 재발 방지 |
|---|---|
| 워크트리에 `.env` 부재 → **핵심 검사가 조용히 건너뜀** | §0-5 를 **반드시** 한다. 완료 조건의 "건너뜀 0" 이 그 탐지 장치다 |
| **개수만 세는 단언은 범위 조건을 못 잡는다** | R16 실측 — 날짜를 하루 밀어도 "1건" 단언은 통과했다. **"어느 건인가"(id)를 검사하라** |
| 사양 표의 `●` 를 "값이 반드시 있다" 로 오독 | **`●` 는 키의 존재다.** 구현을 열어서 확인하라 — 같은 형태를 R16 에 두 번 밟았다 |
| 끝난 워커에 `terminal send` 로 후속 지시 | **새 Dispatch 로 준다.** 안착한 워커는 지시를 새 작업으로 받지 않는다 |
| 보고서 2항의 자진 신고를 흘려보냄 | **R13·R14·R15 세 회차 연속으로 유일한 탐지 수단이었다.** 반드시 판정하라 |

---

## 8.28 ⚖ `R17` 결과 — 출발 판정 검사 + 구간 ETA + 정리 (2026-09-19 **완료**)

**메인 `c9a92ca9`** · 백엔드 **1,357** / 관계자 웹 **277** · 실패 0 · 오류 0 · **건너뜀 0** ·
충돌 0건 · 갈래 3개 + 후속 1개 · 전부 `claude-sonnet-5[1m]` · `high`(요청값=실제값 실측 확인).

### 목표 판정

| 갈래 | 목표 | 판정 | 근거 |
|---|---|:-:|---|
| `T1` | 1 `claimDeparture` 동시성 | ✅ | `RunStopDepartureClaimConcurrencyTest` — 조건부 UPDATE 의 `departedAt IS NULL` 제거로 RED 확인 |
| | 2 선정 순서(`seq` 최솟값) | ✅ | `RunStopDepartureSelectionTest` — `ORDER BY` 반전으로 RED. **개수가 아니라 id 비교** |
| | 3 `forceAllRemaining` 전량 해소 | ✅ | `StopDepartureForceAllRemainingTest` — 반복문에 `break` 삽입으로 RED |
| `T1b` | 1 이벤트 발행 검사 | ✅ | `StopDepartureForceAllRemainingEventsTest` — `StopDepartedEvent` 3건을 **`stopId` 집합**으로 검사 + 이미 선점된 건의 재발행 금지 |
| | 2 `judgeDeparture` 종단 간 선정 | ✅ | `ProximityNotificationServiceDepartureSelectionTest` — 정차지 3개를 1km 이상 떨어뜨려 선정·거리판정 결합을 검사 |
| `T2` | 1 실 API 응답 실측 | ✅ | ⭐ **코드 주석의 미확인 주장이 뒤집혔다**(아래) |
| | 2 도로 기준 구간값 | ✅ | `NaverDirectionsGatewayLegsTest` + Live 회귀 — 실측 8,116m vs 직선비율 8,503m, **387m(4.6%) 차이** |
| | 3 합 = 총합 | ✅ | 누적 반올림 방식 유지. 중간 구간 누적 오차를 새 단언으로 고정 |
| | 4 폴백 유지 | ✅ | `StraightLineLegs.approximate` 미변경. `distribute` 는 대체 경로로 역할만 재정의 |
| `T3` | 1 `change_decided` 자녀 이름 | ✅ | `ChangeDecidedComposerTest`·`ChangeAutoRejectedComposerTest`. `ATT-03` 이행 형태 |
| | 2 `CONVENTIONS_REACT.md:122` 정정 | ✅ | **실측 15건**(인용 12건이 오류 — 아래) · 계층형 예외 조항 추가 |
| | 3 프론트 계획서 낡은 표기 | ✅ | 5건 정정(`BE-R2`·`FE-R3`·`LiveMapScreen`·`RouteDetailScreen`·학생 전용 분기 `§3.10`) |

### Ruling

- **`Ruling 316` — NCP Directions 15 는 경유지별 구간 값을 준다.** 코드 주석의 *"NCP 가 구간 값을
  안 준다"* 는 **미확인 주장이었고 실측으로 뒤집혔다.** 경유지가 있으면
  `summary.waypoints[i].distance/duration` + `summary.goal.distance/duration` 이 **경로 순으로 구간별
  실측 거리(m)·시간(ms)** 을 그대로 담는다(`sum(waypoints[].distance) + goal.distance == summary.distance`
  를 실 호출 3회로 확인). 경유지가 없으면 `waypoints` 필드 자체가 부재. ⇒ 계획서가 후보 1로 제시한
  **`path` 인덱스 슬라이싱은 채택하지 않는다** — 추정을 다시 만드는 일이고 실측값이 이미 있다.
- **`Ruling 317` — `docs/frontend/CONVENTIONS_REACT.md` 의 기능 간 import 금지는 계층 예외를 명시한다.**
  실측 **15건**(`schedule→bus` 4 · `run→route` 2 · `run→map` 2 · `route→schedule` 2 · `route→bus` 2 ·
  `run→auth` 1 · `admin→route` 1 · `admin→map` 1). 최하위 `bus`·`map`·`auth`, 그 위 `schedule`·`route`,
  최상위 `admin`·`run`. 순환 부재. **코드가 아니라 문서를 고친다**(`Ruling 314` 와 같은 갈래).

### 관측 — 다음 회차가 쓸 것

| 관측 | 내용 |
|---|---|
| ⭐ **`.env` 선복사가 값을 했다** | `NaverDirectionsClientLiveTest` 3건이 **건너뛰지 않고 실제로 돌았다**(실 호출 1.387s). R15 최대 사고의 재발 부재 |
| ⭐⭐ **웹 `realBackend` 검사는 백엔드 부재 시 *실패가 아니라 건너뛴다*** | 음성 대조 실측 — 죽은 포트(8199)를 가리키면 `4 skipped`, **테스트 파일은 `passed` 로 집계**된다. ⇒ **"건너뜀 0" 이 유일한 탐지 장치**다. `.env` 건과 같은 형태(조용한 건너뜀) |
| **인용 수치가 또 틀렸다** | 교차 import 12건 → 실측 15건. 갈래가 다시 세라는 지시를 지켜 잡았다. **인용에 "직접 세라" 를 붙이는 규칙이 2회차 연속 값을 했다** |
| **자진 신고가 후속 작업 1개를 만들었다** | T1 의 2항(확신 60%·70%) 2건이 **둘 다 실제 공백**이었고, 조율자가 코드를 읽어 확인한 뒤 **같은 창에 후속 Dispatch** 를 붙였다. **4회차 연속으로 2항이 유일한 탐지 수단** |
| **실행 결과 오독 후보 1건을 갈래가 스스로 갈랐다** | 전체 실행 로그의 `EOFException`/`HikariPool-73 Shutdown` 스택 트레이스는 **JVM 셧다운 훅 잡음**이고 테스트 실패가 아니다(XML `failures=0 errors=0`). **환경 잡음으로 분류** |

### 이월

| # | 항목 | 근거 |
|:-:|---|---|
| 1 | **`StopDepartedEvent` 를 받은 리스너가 N건을 전부 적재하는지** 미검사 | T1b 2항(확신 65%). *"이벤트는 3건 났는데 리스너가 하나만 처리한다"* 형태. 소유가 `notification` 이라 T1 범위 밖. **위험도는 낮다** — Spring 이벤트는 건별 디스패치라 상태를 가진 리스너가 아니면 성립하지 않는다 |
| 2 | `RoadLeg.path`(도로 좌표) 자체는 여전히 **첫 leg 에만** 실린다 | T2 2항. 목표 표 5개가 전부 거리·시간(ETA)이라 좌표 분배는 범위 밖으로 판단. `pointIndex` 가 있어 기술적으로 가능 |
| 3 | Live 회귀 검사가 **여의도 경유 1개 경로**에 의존 | T2 2항. 단언이 `isNotEqualTo`(다르기만 하면 통과)라 도로 사정이 바뀌어도 견딜 것으로 판단 |
| 4 | 동시성 검사 타임아웃 20초를 기존 클래스에서 복사 | T1 2항. 새로 만든 위험이 아니라 `RunStopProximityClaimConcurrencyTest` 의 기존 flake 위험을 답습 |
| 5 | **배포(D)** | 코드·절차서 완료. 막는 것은 AWS 실물 자원 + GitHub Secret 3개 |

---

## 8.29 ⚖ `R18` 목표 표 — 지도 경로 표시 + 구간변경 전후 비교 (2026-09-19 착수)

> **출처는 사용자의 직접 시연이다.** 2026-09-19 에 관계자 웹을 눈으로 확인하며 낸 지적 5건이고,
> 추정이 아니라 **화면에서 관측된 것**이다.

### 0. 조율 세션이 착수 전에 하는 것

1. Skill `orchestration` 호출 → `orca skills get orchestration` 으로 바이너리 가이드 수령
2. 이 목표 표가 **커밋돼 있는지** 확인 — 워크트리는 추적 파일만 가져간다
3. 전용 DB 3개 — `sb_r18_a` · `sb_r18_b` · `sb_r18_c`
4. ⚠ **워크트리를 만든 직후 `.env` 를 복사한다** — `backend/.env` · `frontend/apps/academy-web/.env.local`.
   A 갈래는 **실 NCP 호출이 본체**라 없으면 아무것도 못 한다
5. ⚠ **`orca worktree create` 에 `--no-parent` 를 붙이지 않는다**(2026-09-19 사용자 지시) —
   붙이면 좌측 목록에서 메인 세션 아래로 안 접혀 찾기 어렵다
6. ⚠ **관계자 웹 검사는 `POST /dev/reset`(Flyway clean+migrate)을 부른다** — 공용 `schoolbus` DB 를
   가리키면 **사용자가 보고 있는 시연 데이터를 지운다.** 갈래마다 자기 포트·자기 DB 로 `bootRun` 한다

### 사용자 확정 (2026-09-19)

- **`Ruling 318`** — 구간변경 승인의 "예상 소요시간" 은 **노선 전체 시간**이다(출발지→마지막 정차지).
  특정 학생의 승하차지까지가 아니다. **단위는 분(minute)** 이고 화면은 전/후를 분으로 나란히 비교한다
- **`Ruling 319`** — 전후 경로는 **좌우 두 지도로 나란히** 보여준다. 한 지도에 겹치지 않는다

### 배정표

| 갈래 | 모델 | 건드리는 곳 | 겹침 |
|---|---|---|---|
| `r18-a` | `claude-sonnet-5[1m]` · high | `backend/.../routing/map/**` · `routing/pipeline/**` | — |
| `r18-b` | `claude-sonnet-5[1m]` · high | `frontend/apps/academy-web/src/features/map/**` · `features/admin/**` · `features/run/**` | — |
| `r18-c` | `claude-sonnet-5[1m]` · high | `docs/API_SPEC.md` · `backend/.../request/**`·`routing/preview/**` · `frontend/.../features/approval/**` | — |

⚠ **A 와 C 가 둘 다 노선 계산을 읽지만 A 는 `map/`·`pipeline/`, C 는 미리보기 응답 조립이다.**
C 는 A 가 고칠 파일을 **읽기만** 하고 고치지 않는다. **`docs/IMPLEMENTATION_PLAN.md` 는 조율자가 쓴다.**

---

### A — 지도에 실제 도로 경로가 안 그려지는 원인을 잡는다 (결함)

**관측된 것** — 관제 화면에서 **어떤 버스를 눌러도 경로가 안 그려진다.**

**조율자가 실측으로 좁혀 둔 것 (인용이다 — 직접 재현하라)**

| 확인 | 결과 |
|---|---|
| 시드의 `route_version` 6건 | `road_path` 가 **전부 NULL** — 시드가 행을 직접 넣어 계산 파이프라인을 안 탔다 |
| 확정 배치를 실제로 태움(회차 6) | 좌표 **3개** · `fallback_used=true` · 27분 / 8.99km — 정차지만 직선으로 이은 값 |
| 컨테이너 안에서 NCP 직접 호출 | **200 정상**(`map-direction-15/v1/driving`, 경유지 없는 2점 요청) |
| `app.routing.map.provider` | `naver` (stub 아님) |
| 백엔드 로그 | 경로 계산 관련 **경고·오류 0건** — 조용히 폴백됐다 |

⇒ **자격증명·네트워크는 멀쩡한데 코드가 폴백으로 빠진다. 그 지점을 찾는 것이 이 갈래의 본체다.**

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | **폴백 원인을 실측으로 규명하고 보고서 1항에 적는다** | 추측 금지. `NaverDirectionsGateway.legsOf` 가 던지는 예외를 실제로 잡아 원문을 적어라. ⚠ **경유지가 있는 요청**으로 재현하라 — 조율자가 확인한 200 은 경유지 **없는** 2점 요청이다 |
| 2 | **폴백이 조용하지 않게 한다** | 지도 API 가 실패해 근사로 넘어가면 **로그에 남아야 한다**. 지금은 `fallback_used=true` 가 DB 에만 있고 로그가 0건이라 운영에서 알 수단이 부재. ⚠ **경보가 아니라 로그다** — 폴백은 설계된 동작이지 장애가 아니다 |
| 3 | ⭐ **정상 경로에서 `road_path` 가 정차지 개수보다 많은 좌표를 갖는다** | 도로를 따라 굽은 좌표열이어야 한다. **`road_path` 길이 == 정차지 수** 면 직선이고 아무것도 안 고친 것이다 |
| 4 | **시드에도 계산된 노선이 들어간다** | 시연·검사에서 경로가 바로 보여야 한다. ⚠ **가짜 좌표를 손으로 박지 마라** — 실 API 응답을 한 번 받아 그 값을 시드에 고정하거나, 시드 확정 회차가 파이프라인을 타게 하라. **어느 쪽을 골랐는지와 이유를 보고서 1항에** |
| 5 | 백엔드 전체 실패 0 · 오류 0 · **건너뜀 0** | 기준 **1,357**(§8.28 인용 — 직접 세라). `.env` 가 있으므로 Live 검사가 실제로 돌아야 한다 |

- ⚠ **실 API 가 간헐 503 을 낸다** — 코드 결함이 아니다. 재시도하고 검사를 약화시키지 마라
- 검사 명령 — `./gradlew test -PtestDbUrl=jdbc:postgresql://localhost:15432/sb_r18_a --rerun`

### B — 지도 화면 (버스 아이콘 · 클릭 동작)

**관측된 것** — ①버스 아이콘이 **너무 작아 안 보인다** ②버스를 눌러도 **경로가 안 나온다**
③버스를 누르면 **그 버스를 지도 정중앙에 놓고 확대**해 주면 좋겠다.

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | **버스 아이콘이 한눈에 보인다** | 지금 크기를 먼저 재고(보고서 1항) 키운다. ⚠ **정차지·학생 마커와 구별**돼야 한다 — 버스만 키우고 나머지를 그대로 두면 관계가 무너진다. 세 종류(`bus`·`stop`·`student`)의 크기 관계를 정하고 근거를 적어라 |
| 2 | ⭐ **버스를 고르면 그 버스가 지도 정중앙 + 확대** | `MapCamera`(lat·lng·zoom)가 이미 계약에 있다. **새 장치를 만들지 말고** 그것을 옮겨라. 확대 수준은 *"그 버스 주변 정차지 1~2개가 같이 보이는"* 정도로 정하고 근거를 적어라 |
| 3 | **고른 버스의 경로가 그려진다** | 화면 코드(`MonitoringPage`)는 이미 `getRunRoute` → `polylines` 를 한다. **A 갈래가 데이터를 고치므로 B 는 데이터 부재일 때의 화면 거동**을 맡는다 — 좌표가 0개면 *"경로 정보가 아직 없습니다"* 를 띄워 **빈 지도와 구별**되게 하라. 지금은 아무 표시가 없어 결함인지 데이터 부재인지 화면에서 갈 수 없다 |
| 4 | 관계자 웹 전체 실패 0 · **건너뜀 0** | 기준 **278**(§8.28 의 277 + 루트 리다이렉트 1. 인용이니 직접 세라) |

- ⚠ **`features/map` 의 경계를 깨지 마라** — 화면은 `MapMarker`·`MapCamera`·`MapPolyline` 만 알고 `naver.maps.*` 를 몰라야 한다(`mapAdapterBoundary.test.ts` 가 이것을 지킨다)
- ⚠ **지도는 Flutter 앱 2종도 같은 계약을 쓴다**(`COMMON-B1`). 계약 타입을 바꾸면 `graft callers` 로 먼저 세라
- ⚠ **웹 검사는 자기 포트·자기 DB 로 띄운 백엔드에만 붙여라** — 공용 `schoolbus` 를 가리키면 사용자의 시연 데이터가 지워진다

### C — 구간변경 승인의 전후 비교 (사양 확장)

**관측된 것** — 예상 소요가 *"`-` → `-`"* 로만 보이고, 전후 경로를 **지도로 볼 수단이 부재**하다.

**지금 사양(`API_SPEC §5.5`)이 주는 것** — `est_time_before`·`est_time_after`(예상 도착 **시각**) ·
`est_distance_before`·`est_distance_after` · `route_preview.stops_before[]`·`stops_after[]`(이름·ETA만).
**소요시간(분)도, 경로 좌표도 부재하다.**

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | **`API_SPEC §5.5` 를 확장하고 근거를 `Ruling 318`·`319` 로 적는다** | 새 필드 2쌍 — **소요시간(분)** 과 **전후 `road_path`**. ⚠ **필드 이름은 기존 `est_*_before`/`est_*_after` 형태를 따른다** — 새 이름 체계를 만들지 마라. §5.7 경유 지점 미리보기도 **같은 구조**라 함께 본다(`API_SPEC` 1805·1822행) |
| 2 | **소요시간은 노선 전체 시간(분)** (`Ruling 318`) | 출발지→마지막 정차지. **특정 학생의 승하차지까지가 아니다.** `route_version.est_duration_min` 이 이미 그 값이다 — **새로 계산하지 말고 그것을 실어라** |
| 3 | ⭐ **화면이 전/후를 분으로 나란히 비교** | *"32분 → 38분 (+6분)"* 형태. **증감 부호를 함께** 보여준다. ⚠ 값이 없을 때(결정된 건)는 지금처럼 `-` 로 두되 **왜 없는지** 한 줄 안내 |
| 4 | ⭐ **전후 경로를 좌우 두 지도로 나란히** (`Ruling 319`) | 한 지도에 겹치지 않는다. 이미 `stops_before`/`stops_after` 를 좌우로 놓는 표가 있으니 **그 구조를 따른다** |
| 5 | 백엔드 전체 실패 0 · 오류 0 · 건너뜀 0 · 웹 전체 실패 0 · 건너뜀 0 | 기준 백엔드 **1,357** · 웹 **278** (인용이니 직접 세라) |

- ⚠ **A 갈래가 `routing/map/**` 을 고친다. C 는 그 파일을 읽기만 한다** — 경로 좌표를 얻는 방법이 A 의 수정에 달려 있으면 **가정을 보고서 2항에 적고** 조율자에게 물어라
- ⚠ **`preview_token` 의 뜻을 바꾸지 마라** — 화면에서 본 결과와 배포되는 결과의 동일성을 보장하는 값이다

---

### 1. 완료 후 조율자가 하는 것

1. 각 갈래의 보고를 **독립 실측으로 검증**(`build/test-results/test/TEST-*.xml` 직접 계수)
2. 병합 — A → C → B (C 가 A 의 데이터에 기대므로 A 를 먼저)
3. `./gradlew compileJava compileTestJava`
4. 백엔드 전체 단독 실행(새 DB) **2회 연속**
5. 웹 전체 — 백엔드를 띄우고, **2회 연속**
6. ⭐ **눈으로 확인** — 스택을 올리고 관제·구간변경 승인 화면을 실제로 연다. **이 회차는 출처가 눈이라 판정도 눈이어야 한다**
7. 정산(`worker-list --terminal-state reclaimable` 이 0건) → 자원 정리 → `§8.30` 기록

---

## 8.30 ⚖ `R18` 결과 — 지도 경로 표시 + 구간변경 전후 비교 (2026-09-19 **완료**)

**메인 `1c36e424`** · 백엔드 **1,363**(2회 연속) / 관계자 웹 **296**(2회 연속) · 실패 0 · 오류 0 ·
**건너뜀 0** · 충돌 0건 · 갈래 3개 + 후속 2개 · 전부 `claude-sonnet-5[1m]` · `high`.

### ⭐ `Ruling 320` — 경로 폴백의 근본 원인

**NCP 는 인접한 두 지점의 좌표가 같으면 요청 전체를 `400`(*"출발지와 도착지가 동일합니다"*)으로
거절한다.** 등원 방향은 `origin` 이 첫 정차지와 같은 좌표라 **항상 이 조건에 걸렸고**, 그 예외가
`@Retry(fallbackMethod="unavailable")` 에 **아무 로그 없이 흡수**되어 직선거리 근사로 떨어졌다.
⇒ 게이트웨이가 **인접 중복 좌표를 걷어내고 0-leg 로 복원**하며, 폴백 시 **WARN 로그**를 남긴다.

**실측 효과** — `road_path` 좌표 수가 회차당 **0~3개 → 328~540개**.

### 목표 판정

| 갈래 | 판정 | 근거 |
|---|:-:|---|
| `A` 1 원인 규명 | ✅ | curl 실측으로 `400` 확정 |
| `A` 2 폴백 로그 | ✅ | `unavailable()` WARN |
| `A` 3 좌표 > 정차지 수 | ✅ | 328~540개 |
| `A` 4 시드에 실경로 | ✅ | 실 NCP 응답을 시드에 고정 |
| `B` 1 아이콘 크기 | ✅ | 12px 균일 → 버스 28 · 정차지 16 · 학생 10 |
| `B` 2 중앙+확대 | ✅ | `cameraForSelectedBus`. **단 확대 수준은 아래 이월 1** |
| `B` 3 경로 부재 안내 | ✅ | `routeDisplayState` |
| `B2` 세 화면 통일 | ✅ | 공유 함수로 올림. **복사본 0** |
| `C` 1~4 전후 비교 | ✅ | `est_duration_before/after`(분) · `road_path_before/after` · 좌우 두 지도 |
| `C2` §5.15 확장 | ✅ | 경유 지점 미리보기도 같은 구조 |

### ⭐⭐ 조율자 눈 확인 — 코드 검사로는 못 잡는 것

실제 브라우저(puppeteer-core + 설치된 Chrome)로 `staffA` 로그인 → `/dashboard` → 버스 클릭.

| 확인 | 결과 |
|---|---|
| 경로가 그려지는가 | ✅ **그려진다**(고치기 전에는 아무것도 없었다) |
| 클릭 시 중앙+확대 | ✅ **작동**. 축척 3km → **100m** |
| 버스 마커가 보이는가 | ✅ 보인다 |
| 🔴 **확대 수준이 적절한가** | ❌ **과하다** — 노선 전체가 화면 밖으로 나간다 |
| 🔴 **버스로 알아볼 수 있는가** | ❌ **그냥 파란 원**이다. 지도의 다른 POI 아이콘과 섞인다 |
| 🔴 **정차지가 보이는가** | ❌ **정차지 마커를 아예 안 그린다** — 화면이 `kind:"bus"` 만 넘긴다 |

⚠ **`B` 목표 2 의 판정 근거였던 *"그 버스 주변 정차지 1~2개가 같이 보이는"* 은 성립할 수 없었다** —
정차지 마커 자체가 부재하기 때문이다. **검사는 통과했는데 의도는 미달**이고, 이것이 이 회차가
**눈 확인을 완료 조건에 넣은 이유**다.

### 관측

| 관측 | 내용 |
|---|---|
| ⭐ **자진 신고가 조율자의 범위 오류를 잡았다** | `B` 가 *"`MonitoringPage` 만 고쳤다"* 고 신고 → 확인하니 그 화면은 **`(admin)` 전용**이라 `staff` 로 로그인한 사용자는 **못 본다**. 사용자가 본 것은 `/dashboard`·`/today-run` 이었다. **목표 표의 범위 지정이 틀렸고 갈래가 잡았다** |
| ⭐ **갈래가 정본의 절 번호 오기를 잡았다** | 목표 표의 "§5.7" 은 **§5.15** 가 옳았다. `C` 가 문서를 직접 열어 확인 |
| **개수만 세는 단언을 두 갈래가 각자 밟고 고쳤다** | `C`·`C2` 둘 다 `greaterThan(0)` 로는 before/after 바꿔치기가 안 잡혀 **구체값 대조**로 강화 |
| ⚠ **병합 후 웹 76건 실패는 환경이었다** | 원문이 `Unexpected token '<', "<html>` — 조율자가 **프록시(:3000)를 API 로 잘못 지정**. 백엔드 직접(:8130)으로 바꾸니 296건 전건 통과. **원문을 읽은 것이 판별 수단** |

### 이월

| # | 항목 | 근거 |
|:-:|---|---|
| 1 | 🔴 **선택 확대 수준(`SELECTED_BUS_MAP_ZOOM=16`)이 과하다** | 조율자 눈 확인. 노선이 화면 밖으로 나간다. **상수 하나만 고치면 된다** |
| 2 | 🔴 **버스 마커가 원이라 버스로 안 보인다** | 디자인 킷에 버스 아이콘 자산이 부재(`markerIcon.ts` 주석) |
| 3 | 🔴 **지도에 정차지·학생 마커를 안 그린다** | 화면이 `kind:"bus"` 만 넘긴다. `stop`·`student` 크기는 정의됐는데 **쓰는 곳이 부재** |
| 4 | 시드의 정적 `route` 가 `run` 의 학원·버스·방향 조합과 불일치 | `A` 2항 — 회차 2·3·5·7·8 은 `confirmOne` 을 태우면 `ROUTE_NOT_CONFIGURED_FOR_RUN` 으로 실패한다. **시연 데이터가 파이프라인과 분리돼 있다** |
| 5 | §5.15 실측 소요시간 절대값이 **994분** | `C2` 2항 — 버스 노선으로 16시간은 성립하지 않는다. 지오코딩 시험 데이터 문제로 추정 |
| 6 | **배포(D)** | AWS 실물 자원 + GitHub Secret 3개 |

---

## 8.31 ⚖ `R19` 목표 표 — 지도 마커·확대 수준 (2026-09-19 착수)

> **출처는 조율자의 눈 확인이다**(`§8.30` 이월 1·2·3). 코드 검사는 전부 통과했는데 **화면에서
> 의도가 미달**한 3건이고, 판정도 눈으로 해야 한다.

### 배정

| 갈래 | 모델 | 건드리는 곳 |
|---|---|---|
| `r19-m` | `claude-sonnet-5[1m]` · high | `frontend/apps/academy-web/src/features/map/**` · `features/admin/**` · `features/run/**` |

### 목표 — 4개

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | ⭐ **지도에 정차지 마커를 그린다** | 세 화면(`MonitoringPage`·`DashboardPage`·`TodayRunPage`)이 지금 `kind:"bus"` 만 넘긴다. **`kind:"stop"` 을 함께 넘겨라** — 크기(16px)·색은 `markerIcon.ts` 에 이미 정의돼 있고 **쓰는 곳만 부재**하다. 정차지 좌표는 `getRunRoute` 응답의 `stops[]` 에 `lat`·`lng` 가 이미 온다. ⚠ **선택된 회차의 정차지만** 그린다 — 전 회차를 그리면 화면이 덮인다 |
| 2 | ⭐ **확대 수준을 노선이 보이는 값으로 정한다** | 지금 `SELECTED_BUS_MAP_ZOOM=16`(축척 100m)이라 **노선 전체가 화면 밖으로 나간다**(조율자 눈 확인). ⚠ **값을 추측으로 바꾸지 마라** — 시드 노선의 정차지 좌표 범위를 **직접 계산**해(위도·경도 폭) 그 범위가 들어가는 zoom 을 근거와 함께 정하고 보고서 1항에 적어라. 네이버 zoom 은 1 증가 시 축척 절반이다 |
| 3 | **버스 마커를 버스로 알아볼 수 있게 한다** (사용자 지시 — shadcn 아이콘) | 지금 **그냥 파란 원**이라 지도의 다른 POI 아이콘과 섞인다. **`lucide-react` 를 쓴다**(shadcn/ui 표준 · 조율자가 설치·커밋 완료 `8e9149ac`). `bus`·`map-pin` 등 적절한 이름을 골라라. ⚠⚠ **외부 URL 금지** — `buildMarkerIconHtml` 은 HTML 문자열을 돌려주므로 `react-dom/server` 의 `renderToStaticMarkup` 으로 아이콘을 문자열로 만든다 |
| 3.1 | ⭐ **`Icon.tsx` 의 CDN 의존을 걷어낸다** | `src/shared/ui/core/Icon.tsx` 가 **`https://unpkg.com/lucide-static@0.428.0/icons/` 에서 SVG 를 받아온다** — CORS 로 막혀 콘솔 오류가 난다(조율자 실측). 같은 `lucide-react` 로 바꿔 **번들에서** 쓴다. ⚠ **이 컴포넌트는 앱 전체가 쓴다** — `graft callers Icon --depth all` 로 먼저 세고, 기존 `name` 계약(kebab-case 문자열)을 **깨지 마라**. 깨야 한다면 호출부를 전부 고치고 보고서 1항에 적어라 |
| 4 | 관계자 웹 전체 **실패 0 · 건너뜀 0** | 기준 **296**(§8.30 인용 — 직접 세라) |

### 조심할 것

- ⚠ **`features/map` 경계를 깨지 마라** — 화면은 `MapMarker`·`MapCamera`·`MapPolyline` 만 알고 `naver.maps.*` 를 몰라야 한다(`mapAdapterBoundary.test.ts`)
- ⚠ **세 화면이 같은 규칙을 쓴다** — `R18-B2` 가 공유 함수로 올려 뒀다. **복사본을 만들지 마라**
- ⚠ **지도 계약은 Flutter 앱 2종도 쓴다.** `types.ts` 를 바꾸면 `graft callers` 로 먼저 세라
- ⚠ **백엔드를 건드리지 마라** — 이 회차는 화면만이다
- ⚠ **조율자가 병합 후 눈으로 판정한다.** 검사 통과는 필요조건이지 충분조건이 아니다 — `R18-B` 가 검사를 다 통과하고도 의도 미달이었다

---

## 8.32 ⚖ `R20` 목표 표 — 관계자 웹 지도·승인 화면 (2026-09-19 착수)

> **출처는 사용자의 직접 시연이다.** 관계자 웹(`academy-web`)을 눈으로 보며 낸 지적이고,
> 조율자가 API·DB 로 원인을 갈라 세 갈래로 나눴다.

### 조율자 실측 — ⚠ 인용이다. 각 갈래가 직접 확인하라

| run | 상태 | 확정노선 | 좌표 | 분 | km | 폴백 |
|---:|---|:-:|---:|---:|---:|:-:|
| 1·6 | idle | ❌ | - | - | - | - |
| 2 | confirmed | ✅ | 540 | **-** | **-** | false |
| 3 | moving | ✅ | **6** | **-** | **-** | **true** |
| 4 | finished | ❌ | - | - | - | - |
| 7 | confirmed | ✅ | 328 | **-** | **-** | false |
| 8 | confirmed | ✅ | 385 | **-** | **-** | false |

**구간변경 승인 상세 API 실측** — `road_path_before` **540개** · `road_path_after` **391개** 가
**정상적으로 온다.** 그런데 **화면에 지도가 안 나온다** ⇒ **화면 결함이지 데이터 문제가 아니다.**
반면 `est_duration_before`·`est_time_before`·`est_distance_before` 는 **전부 `null`** 이다 ⇒ **시드 문제**.

### 배정표

| 갈래 | 모델 | 건드리는 곳 |
|---|---|---|
| `r20-a` | `claude-sonnet-5[1m]` · high | `backend/.../db/migration-local/V2__seed_data.sql` · `routing/**`(필요 시) |
| `r20-b` | `claude-sonnet-5[1m]` · high | `frontend/.../features/approval/**` |
| `r20-c` | `claude-sonnet-5[1m]` · high | `frontend/.../features/run/**` · `features/map/**` · `features/admin/**` |

⚠ **B 와 C 가 둘 다 `features/map` 을 읽지만 고치는 것은 C 뿐이다.** B 는 읽기만 한다.

---

### A — 시드의 노선 데이터를 실태에 맞게 채운다

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | ⭐ **`est_duration_min`·`est_distance_km` 가 시드에 채워진다** | 지금 **6건 전부 `NULL`** 이다. 그래서 구간변경 승인의 *"변경 전"* 값이 전부 빈칸이다. ⚠ **먼저 확인하라 — 실제 파이프라인(`confirmOne`)은 이 값을 채우는가?** 채운다면 시드만 빠진 것이고, 안 채운다면 그쪽이 결함이다. **어느 쪽인지 보고서 1항에** |
| 2 | ⭐ **`run 4`(운행 종료)에 확정 노선을 넣는다** | 운행이 끝난 회차인데 노선이 **부재**해 화면에서 경로가 안 나온다. `run 2` 와 같은 형태로 채운다 |
| 3 | **`run 3`(운행 중)이 실제 도로 경로를 갖는다** | 지금 좌표 **6개 · `fallback_used=true`** 라 화면에서 **직선으로 길이 아닌 곳을 지난다**(사용자 지적). ⚠ **폴백 시연 케이스를 없애지는 마라** — 어느 회차를 폴백 본보기로 남길지 정하고 **이유를 보고서 1항에** 적어라. 다만 **운행 중인 회차(3)는 실제 경로여야 한다** |
| 4 | **가짜 좌표를 손으로 박지 마라** | `R18-A` 가 한 것처럼 **실 NCP 응답을 받아 그 값을 고정**한다 |
| 5 | 백엔드 전체 실패 0 · 오류 0 · 건너뜀 0 | 기준 **1,363**(§8.30 인용 — 직접 세라). ⚠ **`V2` 를 고치면 체크섬이 바뀌어 기존 DB 는 기동 실패한다** — 새 DB 로 돌려라 |

---

### B — 구간변경 승인 화면 (지도 + 전/후 배치)

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | 🔴 **좌우 두 지도에 경로가 실제로 그려진다** | **데이터는 이미 온다**(`road_path_before` 540 · `road_path_after` 391 — 조율자 실측). 지금 **둘 다 안 보인다** ⇒ 화면 배선 결함이다. `features/map` 의 `MapSurface`·`MapPolyline` 을 쓰되 **`features/map` 을 고치지는 마라**(C 소유) |
| 2 | ⭐ **정보를 "변경 전 / 변경 후" 두 묶음으로 나눈다** (사용자 지시) | 지금은 값이 섞여 있다. **전/후를 나란히** 놓아 무엇이 달라지는지 바로 보이게 한다 |
| 3 | ⭐ **시간은 3가지만 표기한다** (사용자 지시) | **전체 소요시간 · 출발시간 · 도착시간.** 그 밖의 시간 값은 이 묶음에 넣지 마라 |
| 4 | ⭐ **소요시간은 분으로만** (사용자 지시) | *"37분"*. 시:분 혼합 표기를 쓰지 마라. 증감(`+6분`)은 유지한다 |
| 5 | **값이 없을 때 빈칸으로 두지 않는다** | `A` 가 시드를 채우기 전까지 *"변경 전"* 값이 `null` 이다. **`-` 만 찍지 말고 왜 없는지 한 줄** 안내. ⚠ **A 의 결과에 기대지 말고 `null` 을 견뎌라** |
| 6 | 관계자 웹 전체 실패 0 · 건너뜀 0 | 기준 **301**(§8.31 인용 — 직접 세라) |

---

### C — 운행 관리 지도 화면 (선택 표시 · 상태별 색)

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | ⭐ **어느 버스를 골랐는지 목록에서 보인다** (사용자 지시) | 지금은 지도만 움직이고 **우측 카드가 그대로**라 무엇을 눌렀는지 알 수 없다. 색·테두리·돌출 무엇이든 좋다 — **선택된 카드가 구별되면 된다** |
| 2 | ⭐ **상태 태그 색을 상태마다 다르게** (사용자 지시) | 지금 **확정·대기가 같은 색**이다. 4종(운행 중·운행 종료·확정·대기)을 구별한다. ⚠ **색은 그린·앰버·레드·스톤 4색 고정**(`C-09`, `docs/frontend/IMPLEMENTATION_PLAN.md` 전 제품 공통 3가지) — 새 색을 만들지 마라 |
| 3 | ⭐ **경로 색도 상태별로 다르게** (사용자 지시) | 같은 4종 구분을 지도 위 선에도 적용한다. `MapPolyline` 에 **종류를 넓히는 방식**을 쓰되(`kind` 가 지금 `"route"` 하나다) ⚠ **`types.ts` 는 Flutter 앱 2종도 쓴다** — `graft callers` 로 먼저 세고 보고서 2항에 적어라 |
| 4 | **노선이 없는 회차를 구별한다** | `대기(idle)` 회차는 **아직 확정 전이라 노선이 없는 것이 정상**이다(조율자 실측 — run 1·6). 지금은 *"경로 정보가 아직 없습니다"* 하나로 뭉뚱그린다. **"아직 확정 전"** 과 **"확정됐는데 경로가 없음"** 을 갈라 보여라 |
| 5 | **근사 경로임을 지도 위에서 알 수 있다** | 지금 안내가 **지도 밖 아래**에 작게 있어 못 보고 *"길이 아닌 곳을 지난다"* 로 읽힌다(사용자 지적). 선 자체나 지도 안에서 드러나게 하라 |
| 6 | 관계자 웹 전체 실패 0 · 건너뜀 0 | 기준 **301**(인용 — 직접 세라) |

---

### 1. 완료 후 조율자가 하는 것

1. 보고를 **독립 실측으로 검증**
2. 병합 — A → C → B (B 가 `features/map` 을 읽으므로 C 를 먼저)
3. **규칙 25 적용** — 병합 후에만 전체 실행, **2회 연속**
4. ⭐ **눈 확인** — 출처가 눈이라 판정도 눈이어야 한다(`§8.30` 이 그래서 3건을 잡았다)
5. 정산 → 자원 정리 → `§8.33` 기록

### `Ruling 321` — 확정 전 회차도 노선을 보여준다 (2026-09-19 사용자 지시)

**지금** — `GET /staff/runs/{id}/route` 가 확정 전(`idle`) 회차에 `RUN_NOT_CONFIRMED` 를 낸다.
회차 확정은 **출발 30분 전**에 일어나므로 그 전에는 화면에 아무것도 없다.

**바꿀 것** — 확정 전에도 **예정 노선**을 돌려준다.

| 항목 | 결정 |
|---|---|
| 출처 | **고정 노선**(`route`·`route_stop`) — 그 회차의 학원·버스·요일·방향으로 찾는다 |
| 구별 수단 | 응답에 **확정 여부 플래그**를 싣는다. 화면이 *"예정"* 과 *"확정"* 을 **반드시 구별**해 표시 |
| 도로 경로 | **기존 계산 파이프라인을 재사용**한다 — 새로 만들지 않는다(`route_preview` 가 확정 없이 계산하는 선례) |
| 계산 근거 | ⭐ **이미 저장된 데이터만 쓴다**(고정 노선 + 승하차지 좌표). 새 입력을 요구하지 않는다 |
| 할당량 | ✅ **걱정하지 않는다**(2026-09-19 사용자 확정) — *"관리자만 사용하고 많이 조회하지 않는다"*. **캐시를 미리 만들지 마라**(YAGNI). 나중에 조회량이 늘면 그때 붙인다 |
| 고정 노선이 없을 때 | 그 사실을 응답으로 알린다 — **조용히 빈 값을 주지 마라**. 다만 ⭐ **시드에 고정 노선을 채워 이 분기가 시연에서 기본이 되지 않게 한다**(아래) |

**⭐ 시드 보강 (2026-09-19 사용자 지시)** — *"확정 전 노선을 확인할 수 있게 테스트 데이터를 적재"*.
지금 `route` 는 `(academy_id=1, bus_id=1, sat)` **2건뿐**이라 `run 3`(2호차)·`run 5`(학원 B)는 고정 노선을
못 찾는다. **확정 전 회차가 실제로 노선을 갖도록 조합을 채운다** — 소유는 `r20-a`.

⚠ **예정 노선은 확정본과 다를 수 있다** — 확정 시점의 탑승 명단·승하차지로 다시 계산되기 때문이다.
화면이 이를 *"확정된 경로"* 로 읽히게 두면 관리자가 잘못된 기대를 갖는다.

---

## 8.33 ⚖ `R20` 결과 — 관계자 웹 지도·승인 화면 (2026-09-19 **완료**)

**메인 `09845436`** · 백엔드 **1,369**(2회 연속) / 관계자 웹 **329**(2회 연속) ·
실패 0 · 오류 0 · **건너뜀 0** · 충돌 0건 · 갈래 3개 + 후속 1개 + 조율자 편집 3건.

### 🔴 `Ruling 322` — 지도가 늦게 생기면 노선·마커·카메라가 영영 안 그려진다

**사용자 지적이 옳았고 갈래 보고가 틀렸다.** `r20-b` 는 *"좌우 지도가 이미 정상 렌더된다"* 고
보고했으나, 조율자가 실제 브라우저로 열어 보니 **지도 타일만 뜨고 경로 선이 없었다.**

**기제** — `NaverMapSurface` 의 세 effect(카메라·마커·노선)가 `mapRef.current` 가 `null` 이면
일찍 반환하는데, **지도 생성은 SDK 적재를 기다리는 비동기**라 마운트 시점에는 항상 `null` 이다.
그 뒤 **의존성이 바뀌지 않으면 effect 가 다시 돌지 않는다.**

| 화면 | 왜 그랬나 |
|---|---|
| 관제·운행 관리 | 자료를 **비동기로 받아** 상태가 뒤늦게 바뀌어 **우연히** 다시 그려졌다 |
| 구간변경 승인 상세 | 자료를 **이미 들고 마운트**해 그 한 번의 이른 실행이 전부였다 ⇒ **영영 안 그려짐** |

**해소** — 지도 생성 자체를 신호(`mapReady`)로 만들어 세 effect 가 함께 다시 돈다.
⚠ **화면마다 고치지 않는다** — 세 곳이 모두 지나는 한 자리다.

⚠⚠ **이 결함이 살아남은 이유 — SDK 배선에 검사가 하나도 없었다.** `features/map/naver/` 의 기존
검사는 전부 **순수 함수**(`markerIcon`·`routeColor`·`markerInterpolation`)였고,
**`NaverMapSurface` 자체를 보는 검사가 0건**이었다. `NaverMapSurface.test.tsx` 를 신설했다.

### 목표 판정

| 갈래 | 판정 | 근거 |
|---|:-:|---|
| `A` 시드 `est_duration`·`est_distance` | ✅ | 6건 전부 실 NCP 값으로 채움 |
| `A` `run 4` 확정 노선 · `run 3` 실경로 | ✅ | 폴백 시연은 `run 5` 로 이동 |
| `A` `depart_time` · `confirmed` 필드 | ✅ | `Ruling 321` 백엔드 |
| `B` 전/후 2열 · 3가지 시각 · 분 단위 | ✅ | 눈 확인 — `30분` / `31분 (+1분)` · 출발 `22:21`(전후 동일 배지) · 도착 `22:51`/`22:52` |
| `B2` 정차지 시각 시:분 · 배지 | ✅ | `formatClockTime` 공유 함수 |
| `C` 선택 카드 표시 · 상태별 태그·경로 색 | ✅ | 눈 확인 — 선택 카드에 테두리, 태그 4색 |
| `C` 확정 전 예정 경로 | ✅ | 눈 확인 — 회색 점선 + *"예정 경로 — 확정 시 달라질 수 있음"* 배지 |
| 🔴 **좌우 두 지도 경로** | ✅ | **`Ruling 322` 해소 후** 눈으로 확인 |

### 관측

| 관측 | 내용 |
|---|---|
| ⭐⭐ **갈래의 "정상이다" 보고를 조율자가 눈으로 뒤집었다** | `b` 가 브라우저로 확인했다고 했으나 실제로는 안 그려졌다. **보고서는 자기 채점이다** — 이 회차가 그것을 가장 선명하게 보여줬다 |
| ⭐ **자진 신고 2건이 실제 사용자 불만이었다** | `b` 의 *"정차지 `eta` 가 풀 ISO 라 지저분하다(확신 60%)"* — 조율자가 범위를 좁게 잡은 것이었다 |
| **갈래가 조율자 지시의 오독 가능성을 잡았다** | 조율자의 *"조용히 빈 값을 주지 마라"* 를 `c` 가 *"에러로 막으라는 뜻이 아니다"* 로 바로 세워 `200` + 빈 배열 계약을 정했다 |
| **규칙 25 첫 적용** | 백엔드 전체 실행 **7회 → 3회**. ⚠ 다만 조율자가 목표 표에 옛 조건을 남겨 `b`·`c` 는 웹 전체를 돌 뻔했다(`b` 만 정정) |
| ⚠ **`down` 이 전용 DB 도 지운다** | 컨테이너 재생성 후 `sb_r20_boot` 이 사라져 **웹 검사 77건이 조용히 건너뛰었다.** *"건너뜀 0"* 이 그 탐지 장치였다 |

### 이월

| # | 항목 |
|:-:|---|
| 1 | **확정 전 회차의 지도 확대가 넓다**(축척 3km) — 버스 위치가 없어 기본 카메라를 쓴다. 정차지 범위로 맞추면 된다 |
| 2 | **"확정" 태그가 빨강**이라 경고로 읽힌다. `C-09` 4색 안에서 배치 재검토 |
| 3 | 승인 상세의 **경로 지도 확대도 3km** — 경로가 작게 보인다 |
| 4 | `§5.15` 대칭 미적용 · `road_path` 값 변경이 프론트 계약에 미칠 영향 미확인(`A` 2항) |
| 5 | **배포(D)** |

---

## 8.34 ⚖ `R21` 목표 표 — 지도 마커 구별 + 운행 시각 표기 (2026-09-19 착수)

> **출처는 사용자의 직접 시연이다**(관계자 웹). 조율자가 코드·API 로 현재 상태를 확인했다.

### 조율자 실측 — ⚠ 인용이다. 직접 확인하라

| 확인 | 결과 |
|---|---|
| 버스 마커 | **전부 같은 모양·같은 색**(`markerIcon.tsx` 의 `bus` 하나). 번호·방향 구별 수단 **부재** |
| 선택 강조 | 우측 카드에만 있고 **지도 위 마커에는 부재** |
| 정차지 마커 | 세 화면 모두 `routeStopMarkers` 로 **이미 전달한다** — 안 보이면 데이터 쪽을 보라 |
| 운행 관리 테이블 | 컬럼 7개(버스·구간·상태·기사·동승·탑승·변경) — **시각 컬럼 부재** |
| 금일 운행 상세 테이블 | 컬럼 6개(이름·반·승하차지·보호자·변경·탑승) — **시각 컬럼 부재** |
| `§5.18` live 응답 | `depart`·`arrival` 계열 필드 **부재** ⇒ **백엔드 확장 필요** |

### 배정표

| 갈래 | 모델 | 건드리는 곳 |
|---|---|---|
| `r21-a` | `claude-sonnet-5[1m]` · high | `frontend/.../features/map/**` · `features/run/**` · `features/admin/**` (화면만) |
| `r21-b` | `claude-sonnet-5[1m]` · high | `backend/.../run/**`(§5.18 등) · `docs/API_SPEC.md` · 두 화면의 **테이블 컬럼만** |

⚠ **둘 다 `features/run` 을 건드린다.** `a` 는 **지도·마커**, `b` 는 **테이블 컬럼**이다.
같은 파일을 만질 수 있으니 **각자 자기 영역만 고치고, 겹치면 보고서 2항에 적어라.**

---

### A — 지도에서 버스를 구별한다

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | ⭐ **고른 버스가 지도 위에서 구별된다** (사용자 지시) | 흰 테두리·크기·그림자 무엇이든 좋다 — **선택된 마커만 달라 보이면 된다** |
| 2 | ⭐ **버스마다 구별된다 — 번호를 마커에 표기** (사용자 지시) | *"버스 번호로 같은 버스로 표기"*. 마커에 **번호가 보여야** 한다 |
| 3 | ⭐ **같은 버스라도 등원·하원이 구별된다** (사용자 지시) | *"색이나 모양으로 등하원 표기"*. ⚠ **색은 `C-09` 4색 고정** — 새 색을 만들지 마라. 색으로 안 되면 **모양**으로 가른다 |
| 4 | **고른 버스의 승하차지가 표시된다** | 세 화면이 `routeStopMarkers` 를 **이미 전달한다**(조율자 실측). **안 보이는 경우가 있으면 그 조건을 찾아 고쳐라** — 상태별(운행 중·확정·대기·종료)로 전부 확인하고 결과를 1항에 적어라 |
| 5 | 관계자 웹 **`features/map`·`features/run`·`features/admin` 범위** 실패 0 · **건너뜀 0** | ⚠ **`features/map` 은 공유 부품이다** — 계약(`types.ts`)을 바꾸면 **전체를 돌리고** 1항에 이유를 적어라 |

⚠⚠ **`MapMarker` 계약을 넓혀야 한다** — 지금은 `kind` 뿐이라 번호·방향·선택 상태를 실을 자리가 없다.
**`graft callers` 로 Flutter 앱 2종까지 먼저 세고** 영향을 보고서 2항에 적어라.
**기존 `kind` 값을 없애지 마라** — 더하는 방향으로 간다.

---

### B — 운행 시각을 테이블에 표기한다

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 1 | ⭐ **운행 관리 테이블에 출발·도착 시각** (사용자 지시) | `DashboardPage` 의 버스 테이블 |
| 2 | ⭐ **금일 운행 상세에도 같은 표기** (사용자 지시) | `TodayRunPage` |
| 3 | **표기 형식은 `시:분:초`** | 사용자 원문 — *"출발,도착시간(몇시, 몇분, 초) 표기"*. ⚠ **`formatClockTime` 은 시:분이라 그대로 쓰면 안 된다** — 그 옆에 초까지 쓰는 함수를 만들고, **두 함수의 용도 차이를 주석에 적어라** |
| 4 | ⭐ **"예정"과 "실제"를 구별한다** | `run` 에 `depart_time`(예정) · `started_at`(실제 출발) · `finished_at`(실제 종료)이 **전부 있다**. ⚠ **어느 것을 보여줄지 정하고 근거를 1항에** — 운행 전에는 실제 값이 없고, 운행 후에는 예정보다 실제가 중요하다. **둘 다 보여주는 것도 답이 될 수 있다** |
| 5 | **`§5.18` 응답에 필요한 필드를 더한다** | 지금 없다(조율자 실측). `API_SPEC` 에 적어라. ⚠ **응답 레코드를 넓히면 기존 고정 응답 검사가 깨진다** — `graft callers` 로 먼저 세라 |
| 6 | 백엔드 **`run` 범위** 실패 0 · 오류 0 · 건너뜀 0 · 웹 **`features/run` 범위** 실패 0 · 건너뜀 0 | **규칙 25** — 전체 실행은 조율자가 병합 후에 한다 |

---

### 1. 완료 후 조율자가 하는 것

1. 독립 실측 검증 → 병합 B → A → 컴파일
2. **규칙 25** — 전체 실행은 여기서만, **2회 연속**
3. ⭐ **눈 확인** — `R20` 에서 갈래 보고가 틀렸고 눈이 잡았다
4. 정산 → 정리 → `§8.35` 기록

---

## 8.35 ⚖ `R21` 결과 — 지도 마커 구별 + 운행 시각 표기 (2026-09-20 **완료**)

**메인 `94e83ccc`** · 백엔드 **1,373** / 관계자 웹 **354**(각 2회 연속) · 실패 0 · 오류 0 · **건너뜀 0** ·
충돌 0건 · 갈래 2개 + 후속 1개 + 조율자 편집 2건.

### 목표 판정 (전부 눈으로 확인)

| 갈래 | 판정 | 근거 |
|---|:-:|---|
| `A` 선택 버스 강조 | ✅ 눈 확인 — 흰 테두리 |
| `A` 마커에 버스 번호 | ✅ 눈 확인 — `2호차` 라벨 |
| `A` 등원·하원 구별 | ✅ 아이콘 모양 |
| `A` 승하차지 표시 | ✅ 초록 마커 |
| `A` **카메라 1회성 포커스**(추가 지시) | ✅ `focusKey` — 위치 갱신이 사용자 조작을 덮지 않는다. 검사 2건 신설 |
| `A` **승인 지도 승하차지 + 변경분 강조**(추가 지시) | ✅ 백엔드 좌표(`lat`·`lng`)까지 확장 |
| `B` 두 테이블에 출발·도착 | ✅ 눈 확인 |
| `B2` 예정 도착(대칭) | ✅ 눈 확인 — `예정 00:09:02 / 실제 00:11:02` → `예정 00:54:02` |

### 🔴 조율자가 눈으로 잡은 것 2건 — 검사는 전부 초록이었다

| # | 증상 | 원인 |
|:-:|---|---|
| 1 | **"예정 도착"이 전부 `-`** | `B2` 가 `run.est_duration_min`(계획값)을 읽는데 **시드가 그 컬럼을 비워 뒀다.** `R20-A` 가 채운 것은 `route_version.est_duration_min`(확정 노선의 실측)이라 **다른 값**이다. ⚠ **조율자 지시가 모호해서 갈래가 다른 출처를 골랐다** |
| 2 | ⭐ **회차 대부분이 "어제"로 들어가 대시보드가 텅 빔** | 시드의 `CURRENT_DATE` 는 **DB 세션 시간대(컨테이너 UTC)** 를 따르는데 앱의 "오늘"은 `Asia/Seoul` 이다. **한국시간 자정~오전 9시** 사이에 시드를 깔면 어긋난다(00:25 KST 실측 — 7건 중 1건만 표시) |

⇒ 둘 다 **시드 수정**으로 해소. `schedule`·`run` 에 계획 소요시간을 채우고, 날짜를
`(now() AT TIME ZONE 'Asia/Seoul')::date` 로 통일했다.

### 관측

| 관측 | 내용 |
|---|---|
| 🔴🔴 **조율자가 기다리는 호출을 안 걸어 완료 신호를 방치했다** | `A` 의 질문에 답하고 ack 한 뒤 *"⏸ 기다립니다"* 로 **턴을 끝냈다.** Orca 는 조율자 대화로 밀어넣지 않으므로 **아무것도 기다리지 않는 상태**가 됐고, 두 갈래가 끝났는데 **사용자가 지적해서야 알았다.** [[orca-check-ack-headofline]] 과 같은 사고의 재발 |
| ⭐ **갈래가 조율자 지시의 오류를 잡았다** | `B` — 조율자가 지목한 `§5.18` 은 **`moving` 회차만** 준다. 표에는 대기·종료도 있어 행을 못 채운다 ⇒ `§5.3` 으로 바꿔 구현하고 *"지시와 다른 판단"* 으로 신고 |
| ⭐ **갈래가 좌표 부재를 확인하고 멈춰 물었다** | `A` — 승인 미리보기에 `lat`·`lng` 가 없어 *"프런트만으로는 불가능"* 으로 `ask`. 억지로 맞췄으면 **삭제된 승하차지가 조용히 빠진 지도**가 나갔다 |
| ⚠ **`--build` 없이 `up -d` 하면 옛 시드가 남는다** | `V2` 는 **jar 안의 자원**이다. 시드를 고치고 `--build` 를 빠뜨려 한 번 헛돌았다 |
| ⚠ **`V2` 를 고치면 기존 테스트 DB 가 전부 막힌다** | 체크섬 불일치 → 888건 실패. **코드 결함이 아니라 재구성 신호** — 새 DB 로 돌리면 0건 |

### 이월

| # | 항목 |
|:-:|---|
| 1 | **금일 운행 상세는 학생 명단 표**라 회차 시각이 **모든 행에 반복**된다(`B` 2항, 확신 낮음). 사이드 카드가 나을 수 있다 |
| 2 | 확정 전 회차·승인 상세의 **지도 확대가 넓다**(`§8.33` 이월 1·3 계속) |
| 3 | **"확정" 태그가 빨강**이라 경고로 읽힌다(`§8.33` 이월 2 계속) |
| 4 | `§5.15` 대칭 미적용(`§8.33` 이월 4 계속) |
| 5 | **배포(D)** |

---
