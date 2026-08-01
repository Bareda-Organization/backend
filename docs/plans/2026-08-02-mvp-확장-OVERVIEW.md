# MVP 확장 — 선탑자 · 학부모 · 배차 시뮬레이션 · 관제 상세 (OVERVIEW)

> **에이전트에게:** 이 문서는 **공용 앵커**다. 백엔드·프론트 어느 쪽 작업을 맡든 **여기 §1~§5 를 먼저 읽고**,
> 자기 모듈 계획서(`backend/docs/plans/2026-08-02-mvp-확장-BACKEND.md` 또는
> `frontend/docs/plans/2026-08-02-mvp-확장-FRONTEND.md`)로 넘어간다.
> **용어·불변조건·API 계약·enum 은 이 문서에만 있다.** 모듈 계획서는 중복하지 않고 `[OVERVIEW §x]` 로 참조한다.
> 계약을 바꿔야 하면 **이 문서를 먼저 고치고** 모듈 계획서를 따라 고친다.

- 작성일: 2026-08-02
- 근거: 코드 실측 (`backend/src/main/java/src/backend/**`, `frontend/lib/**`, 2026-08-02 기준)
- 상위 사양(SoT): [`docs/PRODUCT_SPEC.md`](../PRODUCT_SPEC.md) · [`docs/USER_FLOWS.md`](../USER_FLOWS.md) · [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md) · [`docs/API_SPEC.md`](../API_SPEC.md)

---

## 0. 이 확장이 하는 일 (한 문단)

**승하차 판정 주체를 기사에서 선탑자로 옮기고**, 학부모에게 **실시간 관측 + 등하원 위치 변경 신청** 창구를 열고,
관리자에게 **버스 단위 종합 상세**와 **배차 변경 전후 경로 비교**를 준다.
기술적 중심은 **"저장하지 않고 노선을 재계산해 거리·시간 델타를 내는 엔진" 하나**이며,
관리자의 배차 비교와 학부모 위치변경 자동판정이 **그 엔진을 공유**한다.

### 요구사항 ↔ 태스크 매핑

| # | 요구사항 | 백엔드 | 프론트 |
|---|---|---|---|
| 1 | 기사 메인에 본인 운행 상태(등원/하원/미운행) 표시 | — | `FE-0`(대부분 완료분 커밋) · `FE-3` |
| 2 | 운행 중일 때만 노선 경로 지도 표시 | — | `FE-0` · `FE-3` |
| 3 | 승하차지별로 학생을 묶어 옆 탭에 표시 | — | `FE-0` · `FE-4` |
| 4 | 학생 표시 항목 = 이름·사진·승하차지 | `BE-1` `BE-2` `BE-6` | `FE-4` |
| 5 | 선탑자를 사용자 계층으로 추가, 승하차 판정 이관 | `BE-1`~`BE-3` | `FE-1` `FE-3` `FE-4` |
| 6 | 관리자 버스 정보에 선탑자 표기 | `BE-6` `BE-7` | `FE-5` |
| 7 | 버스 클릭 → 경로 강조 + 인원 종합 정보(지도 동시 노출) | `BE-6` | `FE-5` |
| 8 | 배차 인원 추가·수정·삭제 + 경로/시간/거리 비교 후 선택 | `BE-4` `BE-5` | `FE-6` |
| P1 | 학부모: 버스 실시간 위치 + 자녀 등하원 상태 | `BE-8` `BE-9` | `FE-7` `FE-8` `FE-10` |
| P2 | 학부모: 등하원 위치 변경 요청(자동 판정) | `BE-10` | `FE-9` |
| — | 회원가입 권한 상승 차단(선결 보안) | `BE-11` | — |

---

## 1. 확정된 결정 (뒤집지 말 것)

| ID | 결정 | 근거 |
|---|---|---|
| **D-A** | 선탑자는 **6번째 정식 역할**(`ATTENDANT`). 전용 앱 화면을 갖는다 | 사용자 지시 (A안) |
| **D-B** | **모든 버스에 선탑자가 배정돼 있다고 가정한다.** 미배정 폴백을 만들지 않는다 | 사용자 지시. 시드가 이 불변조건을 보장한다(`BE-1`) |
| **D-C** | 선탑자·기타 계정은 **데이터(시드)로만** 만든다. 앱 내 계정 생성 화면을 만들지 않는다 | 사용자 지시 |
| **D-D** | 스키마는 **`V1__init_schema.sql` 을 직접 수정**한다. 새 마이그레이션(V6…)을 추가하지 않는다 | 사용자 지시. 로컬 DB는 영속 볼륨이 없어 매번 재구성된다 |
| **D-E** | `ddl-auto: validate` 는 **유지**한다 | 끄면 엔티티↔스키마 불일치를 앱이 조용히 통과시킨다 |
| **D-F** | 학생 앱은 **이번 범위 밖**. 학부모 지도는 **버스 위치 + 자녀 등하원 상태**만 보여준다 | 자녀 좌표를 보내는 주체(학생 앱)가 없어 `/api/locations/children` 은 항상 빈 응답이다 |
| **D-G** | 사진은 **URL 문자열 컬럼**(`photo_url`)만 둔다. 업로드 API·파일 스토리지는 범위 밖 | 저장소에 스토리지 인프라가 없다 |
| **D-H** | P2 자동 적용 임계 = **소요시간 +300초 이내 AND 총거리 +1000m 이내** (둘 다 만족) | OR면 "거리 1km인데 20분 늘어난 경로"가 통과한다 |
| **D-I** | 배차 비교(요구 8)와 P2 자동판정은 **같은 시뮬레이션 엔진**을 쓴다 | 연산이 동일하다. 두 벌로 갈라지면 판정 기준이 어긋난다 |
| **D-J** | 기사에게서 뺏는 것은 **기록 권한뿐**이다. 운행 세션 시작·종료와 버스 위치 보고는 **기사가 유지**한다 | 요구 5는 승하차 판정만 언급한다 |
| **D-K** | 학생의 **등원 좌표를 `Student` 자체 컬럼**(`pickup_lat/lng/address`)으로 갖는다. `boardingStop`(공유 `Stop` 참조)은 그대로 두고 **좌표가 있으면 그것을 우선**한다 | P2는 "등**하**원 위치 변경"을 요구하는데, 등원 좌표가 여러 학생이 공유하는 `Stop` 뿐이면 **한 명의 변경이 같은 정류장의 다른 학생까지 움직인다.** 하원만 지원하는 것은 요구 미달이다 |
| **D-L** | `BE-6`의 `GET /api/buses/{id}` 는 **마스터 데이터만** 담는다(버스·기사·선탑자·당일 계획·명단). **운행 세션과 승하차 기록은 넣지 않는다** — 관제 화면은 그 둘을 별도 provider로 계속 조회한다 | 갱신 주기가 다르다. 세션·승하차는 운행 중 계속 바뀌어 폴링 대상이지만 버스·명단은 그렇지 않다. 합치면 **무거운 응답 전체를 폴링 주기로 다시 받는다.** "4개 조회를 1건으로"는 과장이었고, 실제로 줄어드는 건 2개다 |

### ⚠️ 이 확장이 뒤집는 기존 사양 (문서 갱신 대상)

1. `PRODUCT_SPEC §2` — *"동승보호자는 MVP에서 별도 역할로 분리하지 않고 기사 앱 흐름에 흡수"* → **뒤집힌다**(D-A)
2. `PRODUCT_SPEC §4.1` — *"기사·학부모가 직접 합의한 변경도 학원 승인을 거쳐야 효력이 생긴다"* → **위치 변경에 한해 무승인 자동 적용/자동 거부**로 개정(D-H)
3. `CLAUDE.md` — *"기존 `V1__init_schema.sql` 수정 금지"* → **개발 단계에 한해 허용**으로 개정(D-D)

---

## 1.5 커밋 규약 — 모든 태스크에 암묵 적용

모듈 계획서의 개별 단계에 커밋 명령을 반복해 적지 않는다. **아래가 모든 태스크에 걸리는 규칙이다.**

- **태스크 하나가 끝나면 반드시 커밋한다.** 태스크는 "리뷰어가 독립적으로 승인/반려할 수 있는 최소 단위"로 잡혀 있으므로, 태스크 경계가 곧 커밋 경계다.
- 한 태스크 안에서도 **실패 테스트 작성 → 구현 → 통과**가 끝날 때마다 커밋해도 좋다. 커밋을 아끼지 않는다.
- 커밋 전에 반드시 통과시킬 것:
  - 백엔드 — `cd backend && ./gradlew test`
  - 프론트 — `cd frontend && flutter analyze && flutter test`
- 메시지 형식(저장소 관례):

```
feat(backend): 선탑자 역할 추가와 승하차 기록 권한 이관 [BE-3]
fix(frontend): 학부모 지도 구독 해제 누락 수정 [FE-8]
```

  - 타입은 `feat` / `fix` / `refactor` / `test` / `docs` / `chore`
  - 스코프는 `backend` 또는 `frontend`
  - 제목은 **한국어**, 끝에 태스크 ID를 `[BE-3]` 처럼 대괄호로 붙인다 — 나중에 어떤 계획의 산출인지 추적된다
  - 본문이 필요하면 **왜 그렇게 했는지**를 쓴다(무엇을 했는지는 diff가 말한다)
- ⚠️ **스키마를 만지는 커밋**(`BE-1`)은 `V1__init_schema.sql` 을 고치므로, 커밋 메시지 본문에 **"반영하려면 `docker compose down` 후 `up` 필요"** 를 반드시 적는다. 이걸 빠뜨리면 다른 사람이 `start` 만 하고 Flyway 검증 실패로 헤맨다.

## 2. 용어 · 불변조건

| 용어 | 뜻 |
|---|---|
| **선탑자(ATTENDANT)** | 버스에 동승해 학생 승하차·보호자 인계를 **판정·기록**하는 사람. `Bus.attendant` 로 버스 1대에 1명 |
| **기사(DRIVER)** | 운전·운행 세션 시작/종료·버스 위치 보고. **승하차를 기록하지 않는다**(조회만) |
| **baseline** | 비교의 기준 = 해당 버스·방향의 **현재 최신 `RoutePlan`** |
| **candidate** | 변경안을 반영해 **저장하지 않고** 계산한 노선 |
| **delta** | `candidate − baseline` 의 거리(m)·소요시간(s)·정차 수 차이 |

**불변조건 (코드가 지켜야 하는 것)**

- **I-1.** 모든 `bus` 행은 `attendant_id IS NOT NULL` 이다 (시드가 보장. 스키마 제약은 걸지 않는다 — 버스 생성 API가 선탑자 없이도 생성 가능해야 하므로)
- **I-2.** 승하차 기록(`POST /api/ride-events`)은 **그 버스에 배정된 선탑자 본인**만 가능하다
- **I-3.** 시뮬레이션은 **어떤 행도 저장하지 않는다.** 저장은 `apply` 계열 API에서만 일어난다
- **I-4.** P2 자동 적용은 **당일 운행 세션이 아직 없을 때만** 가능하다(시작·종료 무관하게 세션이 있으면 거부)
- **I-5.** 노선 변경은 언제나 `version + 1` 인 **새 행**이다. 기존 행을 수정하지 않는다

---

## 3. 데이터 모델 변경 (전체)

수정 대상은 **2개 파일뿐**이다(D-D).

### 3.1 `backend/src/main/resources/db/migration/V1__init_schema.sql`

| 테이블 | 변경 |
|---|---|
| `app_user` | `photo_url varchar(255)` **추가** |
| `student` | `photo_url varchar(255)` · `phone varchar(255)` · `pickup_lat float(53)` · `pickup_lng float(53)` · `pickup_address varchar(255)` **추가** |
| `bus` | `attendant_id bigint` **추가** + FK → `app_user` |
| `user_tenant_role` | `role` CHECK 목록에 `'ATTENDANT'` **추가** |
| `location_change_request` | **신규 테이블** |

### 3.2 `backend/src/main/resources/db/migration/V5__notification_log_add_route_published_type.sql`

`notification_log.type` CHECK 목록에 `'LOCATION_CHANGE_RESULT'` **추가**
(V5가 이 제약을 마지막으로 재정의하는 파일이라 여기를 고친다. V3·V4는 건드리지 않는다.)

### 3.3 `backend/src/main/resources/db/migration-local/V2__seed_data.sql`

- 선탑자 계정 3개 + `ATTENDANT` 역할 + 버스 3대 전부에 `attendant_id` 배정 (**I-1**)
- 기존 계정·학생에 `photo_url`, 학생에 `phone` 채우기

### 3.4 반영 방법 (⚠️ 매번)

V1을 고치면 Flyway 체크섬이 바뀌므로 **컨테이너를 제거해야 한다.**

```bash
# 사용자에게 요청할 것 — 에이전트가 직접 실행하지 않는다
docker compose down
docker compose up -d postgres redis kafka
```

`stop`/`start` 는 데이터가 남아 **Flyway validation 실패**로 앱이 뜨지 않는다.

### 3.5 엔티티 증감

`@Entity` **16 → 17개** (`LocationChangeRequest` 신규). `Role` **5 → 6계층**.

---

## 4. API 계약 (신규·변경 전체)

### 4.1 권한 변경 (기존 엔드포인트)

| 엔드포인트 | 이전 | 이후 |
|---|---|---|
| `POST /api/ride-events` | `DRIVER` | **`ATTENDANT`** |
| `POST /api/ride-events/{id}/correction` | `DRIVER, ACADEMY_ADMIN, PLATFORM_ADMIN` | **`ATTENDANT, ACADEMY_ADMIN, PLATFORM_ADMIN`** |
| `GET /api/ride-events/bus/{busId}` | `DRIVER` | `DRIVER, ATTENDANT` |
| `GET /api/drive-sessions/{id}/roster` | `DRIVER` | `DRIVER, ATTENDANT` |
| `GET /api/drive-sessions/bus/{busId}` | `DRIVER` | `DRIVER, ATTENDANT` — ⚠️ **아래 사슬 참조** |
| `GET /api/buses/me` | `DRIVER` | `DRIVER, ATTENDANT` |
| `GET /api/route-plans/driver/{busId}` | `DRIVER` | `DRIVER, ATTENDANT` |

`POST /api/drive-sessions/start` · `PATCH /{id}/end` · `POST /api/locations/bus` 는 **`DRIVER` 유지**(D-J).

⚠️ **선탑자 명단 화면이 뜨려면 사슬 3개가 모두 열려 있어야 한다.**

```
GET /api/buses/me            → 내 버스 id
GET /api/drive-sessions/bus/{busId}  → 지금 진행 중인 세션 id   ← 이 한 칸이 막히면 아래가 불가능
GET /api/drive-sessions/{id}/roster  → 명단
```

세션 id 를 알려주는 경로가 이것뿐이라, 이걸 빼면 `/roster` 를 열어줘도 **부를 수가 없어 화면이 통째로 뜨지 않는다.**
(전용 "진행 중 세션" API 가 없어 전체 이력을 받아 앱이 고르는 기존 구조 때문이다 — 개선은 이번 범위 밖.)

### 4.1.1 `DriveSessionRosterEntry` 에 `photoUrl` 추가

**요구 4가 "이름·사진·승하차지"를 명시**하는데, 그 화면은 선탑자 앱이다. 현재 `DriveSessionRosterEntry(studentId, name, location, lat, lng)`
에는 사진이 없고, 사진이 있는 `BusDetailResponse.RosterEntry`(`BE-6`)는 **관리자 전용**이라 선탑자가 부를 수 없다.

→ `DriveSessionRosterEntry` 에 `String photoUrl` 을 추가한다(`BE-3` 범위). 값은 `Student.photoUrl`(`BE-2`가 만든다) 그대로.
이것 없이는 **요구 4를 만족할 수 없다.**

### 4.2 신규 엔드포인트 (8개)

| 메서드 · 경로 | 권한 | 태스크 | 용도 |
|---|---|---|---|
| `POST /api/route-plans/simulate` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-5` | 배차 변경안 비교(미저장) |
| `POST /api/route-plans/simulate/apply` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-5` | 비교안 채택 → 배정 커밋 + 승인 + 배포 |
| `GET /api/locations/children/buses` | `PARENT` | `BE-9` | 자녀가 탄 버스들의 최신 위치 |
| `POST /api/location-change-requests` | `PARENT` | `BE-10` | 등하원 위치 변경 신청(자동 판정) |
| `GET /api/location-change-requests/children` | `PARENT` | `BE-10` | 내 신청 이력 |
| `GET /api/location-change-requests` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-10` | 학원 신청 이력 |

`GET /api/buses/{id}` (`BE-6`) 와 `PATCH /api/buses/{id}/assignment` (`BE-7`) 는 **경로 유지 + 응답/요청 확장**이다.

### 4.3 신규 STOMP 목적지 (2개)

| 목적지 | 구독자 | 태스크 |
|---|---|---|
| `/topic/tenant/{tenantId}/bus-locations` | 관리자 | `BE-8` |
| `/user/queue/bus-location` | 학부모 | `BE-8` |

### 4.4 공용 DTO 계약 — 이 정의가 유일한 출처다

```java
// routing/domain/PlannedRoute.java — 저장되지 않은 계산 결과 (BE-4)
public record PlannedRoute(
        List<Long> stopStudentIds,      // 정차 순서대로의 학생 id
        List<LatLng> stopPoints,        // 같은 순서의 좌표
        List<Long> stopEtaSeconds,      // 같은 순서의 누적 ETA(초)
        double totalDistanceM,
        double totalDurationS,
        String polyline) {}

// routing/dto/StudentOverride.java — 변경안 한 건 (BE-4)
public record StudentOverride(
        Long studentId,
        OverrideAction action,          // ADD | REMOVE | MOVE
        Double lat,                     // MOVE·ADD 일 때 필수, REMOVE 면 null
        Double lng) {
    public enum OverrideAction { ADD, REMOVE, MOVE }
}

// routing/dto/RoutePlanComparison.java — 비교 결과 (BE-4)
public record RoutePlanComparison(
        Snapshot baseline,              // 현재 최신 계획. 없으면 null
        Snapshot candidate,
        Delta delta,                    // baseline 이 null 이면 delta 도 null
        int seatCapacity) {             // 그 버스의 물리 좌석 수. baseline 유무와 무관하게 항상 채운다

    public record Snapshot(
            Long routePlanId,           // candidate 는 항상 null(미저장)
            Integer version,            // candidate 는 항상 null
            double totalDistanceM,
            double totalDurationS,
            List<StopView> stops,
            String polyline) {}

    public record StopView(
            int seq, Long studentId, String studentName,
            String label,               // 정류장명(PICKUP) 또는 하차지 주소(DROPOFF)
            double lat, double lng, long etaSeconds) {}

    public record Delta(
            double distanceM,           // candidate − baseline
            double durationS,
            int stopCount) {}
}
```

**정원 초과는 시뮬레이션에서 예외로 죽이지 않는다.** `candidate.stops().size() > seatCapacity` 로 **화면이 판단**한다 —
비교 화면이 정원 때문에 통째로 죽으면 관리자가 "왜 안 되는지"를 볼 수 없다. 프론트가 정원을 따로 조회하지 않아도
되도록 응답 하나로 자족시킨다.

⚠️ **단, `simulate/apply`(저장)와 `BE-10` 자동 적용은 정원 초과를 거부한다.** 비교는 보여주기만 하지만 저장은
`Bus.seatCapacity` 를 넘길 수 없다 — 기존 `buildPlan` 도 같은 검사를 한다. **"보여주기는 관대, 저장은 엄격"** 이 규칙이다.

### 4.5 `RoutePlanSimulationService` 시그니처 — `BE-4` 가 제공, `BE-5`·`BE-10` 이 소비

**메서드가 2개다. 호출자가 다르기 때문이다.**

```java
// REST 진입점(BE-5). 관리자 권한·테넌트 격리를 여기서 검사한다.
RoutePlanComparison simulate(AuthUser admin, SimulateRoutePlanRequest req);

// 내부 호출용(BE-10). 학부모 요청으로 트리거되므로 AuthUser 를 받지 않는다 —
// 인가는 호출자(LocationChangeCommandService)가 "그 학생의 보호자인가"로 이미 끝냈다.
RoutePlanComparison compare(Long busId, RouteDirection direction, LocalDate serviceDate,
                            List<StudentOverride> overrides);
```

`simulate` 는 인가를 마친 뒤 `compare` 에 위임한다. **판정 경로가 하나로 합쳐져야** 관리자 비교와 학부모 자동판정의
기준이 갈라지지 않는다(D-I).

> ⚠️ `BE-10` 은 `simulate(AuthUser, …)` 를 쓰면 안 된다. 학부모는 관리자 권한이 없어 테넌트 격리 검사에서 막힌다.

```java
// schedule/entity/LocationChangeDecision.java (BE-10)
public enum LocationChangeDecision {
    APPLIED,     // 배차·계획이 없어 좌표만 갱신. 이후 관리자가 배차한다
    REPLANNED,   // 계획이 있었고 임계 이내라 재계산해 새 version 을 배포했다
    REJECTED,    // 임계 초과 — 좌표를 바꾸지 않았다
    BLOCKED      // 당일 운행 세션이 이미 존재 — 접수하지 않았다
}
```

**임계 상수** — `schedule/domain/LocationChangeThresholds.java` (D-H)

```java
public final class LocationChangeThresholds {
    /** 총 소요시간 증가 허용치(초). 이 값을 넘으면 자동 거부한다. */
    public static final double MAX_DELTA_DURATION_S = 300;   // 5분
    /** 총 거리 증가 허용치(미터). 이 값을 넘으면 자동 거부한다. */
    public static final double MAX_DELTA_DISTANCE_M = 1000;  // 1km
    private LocationChangeThresholds() {}
}
```

> ⚠️ **AND 조건이다**(D-H). `delta.durationS() <= MAX_DELTA_DURATION_S && delta.distanceM() <= MAX_DELTA_DISTANCE_M`.
> 감소(음수 델타)는 항상 통과한다.

---

## 5. 실행 순서 · 진행 현황

의존 관계상 **`BE-1` → `BE-2` → `BE-3`** 은 반드시 순차다. 그 뒤로는 표의 "선행" 열만 지키면 병렬 가능하다.

| 단계 | 태스크 | 선행 | 병렬 가능 | 상태 |
|---|---|---|---|---|
| P0 | `FE-0` 미커밋 1,732줄 정리·커밋 | — | ✅ BE와 무관 | ✅ **완료 2026-08-02** (커밋 10건, `flutter analyze`·`test 293개` 통과) |
| P0 | `BE-11` 회원가입 권한 상승 차단 | — | ✅ | ⬜ |
| P1 | `BE-1` 스키마·시드 확장 | — | ❌ 단독 | ⬜ |
| P1 | `BE-2` `Role.ATTENDANT` + 엔티티 필드 | `BE-1` | ❌ 단독 | ⬜ |
| P1 | `BE-3` 승하차 권한 재배치 | `BE-2` | ❌ 단독 | ⬜ |
| P2 | `BE-4` 시뮬레이션 엔진 | `BE-2` | ✅ BE-6·BE-7·BE-8 과 | ⬜ |
| P2 | `BE-6` 버스 상세 종합 응답 | `BE-2` | ✅ | ⬜ |
| P2 | `BE-7` 배차 API 선탑자 + 참조 검증 | `BE-2` | ✅ | ⬜ |
| P2 | `BE-8` 버스 위치 실시간 push | `BE-2` | ✅ | ⬜ |
| P3 | `BE-5` 시뮬레이션 API + 채택 | `BE-4` | ⚠️ BE-9 와만 | ⬜ |
| P3 | `BE-9` 학부모 버스 위치 조회 | `BE-8` | ✅ | ⬜ |
| P3 | `BE-10` 위치 변경 요청 | `BE-4` `BE-1` | ⚠️ **`BE-5` 와 병렬 금지** | ⬜ |

> ⚠️ **`BE-5` 와 `BE-10` 은 같은 파일을 고친다.** 둘 다 `routing/command/RoutingCommandService.java` 에 메서드를 추가한다
> (`BE-5` → `applySimulation`, `BE-10` → `republishForBus`). 병렬로 돌리면 충돌한다. **`BE-5` 를 먼저 끝내고 `BE-10` 을 시작한다.**
| P4 | `FE-1` 역할·라우트 골격 | `FE-0` `BE-3` | ❌ 단독(다른 FE의 전제) | ⬜ |
| P4 | `FE-2` 공용 인물 카드 위젯(사진·이름·전화) | `FE-1` | ❌ 단독(4묶음이 공유) | ⬜ |
| P5 | `FE-3` 기사 앱 읽기전용화 | `FE-2` | ⚠️ **`FE-4` 와 병렬 금지** | ⬜ |
| P5 | `FE-4` 선탑자 앱 2화면 | `FE-3` `BE-3` | ⚠️ `FE-3` 다음에 순차 | ⬜ |
| P5 | `FE-5` 관리자 버스 상세 확장 | `FE-1` `BE-6` | ✅ | ⬜ |
| P5 | `FE-6` 관리자 배차 편집·비교 | **`FE-5`** `BE-5` | ⚠️ `FE-5` 다음에 순차 | ⬜ |
| P5 | `FE-7`~`FE-10` 학부모 앱 | `FE-1` `BE-9` `BE-10` | ✅ | ⬜ |
| P6 | `DOC-1`~`DOC-5` SoT 문서 갱신 → [**전용 계획서**](./2026-08-02-mvp-확장-DOC-문서갱신.md) | 전부 | ❌ 순차(같은 파일군을 만진다) | ⬜ |

### 에이전트 배치

| 태스크군 | 에이전트 | 호출 방식 |
|---|---|---|
| `BE-*` 구현 | 직접 구현 후 `test-writer`(**워크트리 격리 필수**) | 태스크 1건당 1회 |
| `BE-3` `BE-7` `BE-11` 완료 후 | `security-reviewer` | 권한·검증 변경이라 반드시 |
| `BE-*` 커밋 전 | `convention-auditor` → `diff-reviewer` | 순서 고정 |
| `FE-3`~`FE-10` | `ui-implementer` **4개 동시** (기사 / 선탑자 / 관리자 / 학부모) | 화면 묶음당 1개 |
| `FE-*` 완료 후 | `design-system-auditor` | 묶음별 |
| `DOC-*` 완료 후 | `docs-drift-auditor` | 전체 1회 |
| 테스트 실패 시 | `debugger` | 필요 시 |

> ⚠️ `ui-implementer` 병렬 실행은 **3갈래**다(기사+선탑자 / 관리자 / 학부모). 4갈래가 아니다 —
> `FE-3`(기사)과 `FE-4`(선탑자)는 `roster_student_tile.dart`·`roster_student.dart`·`driver_roster_screen.dart` 를
> **공유**하므로 한 에이전트가 순차로 처리한다.
> `FE-1`·`FE-2` 산출물(`AppRoutes`·`Role`·`RoleRedirect`·인물 카드 위젯)은 **모든 묶음이 읽기만** 하고 수정하지 않는다.

### P6 문서 갱신 (`DOC-1`~`DOC-5`)

**단계별 지시는 [`2026-08-02-mvp-확장-DOC-문서갱신.md`](./2026-08-02-mvp-확장-DOC-문서갱신.md) 에 있다.** 여기 중복하지 않는다.

| ID | 대상 |
|---|---|
| `DOC-1` | `docs/PRODUCT_SPEC.md` — 가장 무겁다(§2·§3·§4.1·§5·§7·§11·§12) |
| `DOC-2` | `docs/USER_FLOWS.md` |
| `DOC-3` | `docs/API_SPEC.md` |
| `DOC-4` | `docs/ARCHITECTURE.md` |
| `DOC-5` | `CLAUDE.md` · `docs/README.md` |

⚠️ **구현이 끝나기 전에는 손대지 않는다.** `docs/` 4종은 기획서가 아니라 **실측 기록**이고,
`PRODUCT_SPEC.md` §11 은 스스로를 *"지금 실제로 되는 것의 유일한 근거"* 로 규정한다 —
구현 전에 ✅ 를 적으면 그 자체가 허위가 된다.
