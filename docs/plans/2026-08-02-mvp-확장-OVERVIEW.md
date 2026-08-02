# MVP 확장 — 선탑자 · 학부모 · 배차 시뮬레이션 · 관제 상세 (OVERVIEW)

> **에이전트에게:** 이 문서는 **공용 앵커**다. 백엔드·프론트 어느 쪽 작업을 맡든 **여기 §1~§5 를 먼저 읽고**,
> 자기 모듈 계획서로 넘어간다 — 색인은
> [`backend/docs/plans/README.md`](../../backend/docs/plans/README.md) ·
> [`frontend/docs/plans/README.md`](../../frontend/docs/plans/README.md) 에 있다
> (계획서는 단계(P1·P2·P2b·P3 / P4·P5a1·P5a2·P5b·P5c)별로 나뉘어 있다).
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

여기에 **두 번째 축**이 붙는다(2026-08-02 추가). 관리자가 **학생·학부모·기사·선탑자를 앱에서 직접 만들고 고치고 내리며**,
**학생↔학부모** 와 **기사↔선탑자** 를 **손으로 짝지을 수 있게** 한다. 앞의 축이 "운행을 잘 굴리는 일"이라면,
이 축은 **"운행에 등장하는 사람들을 갖추는 일"** 이고, 실제로는 전자의 **전제 조건**이다 —
선탑자 계정이 없으면 승하차 이관(요구 5)도, 학부모 앱(P1·P2)도 시연할 사람이 없다.

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
| A1 | 관리자: 학생·학부모·기사·선탑자 **계정 CRUD** | `BE-12` `BE-13` | `FE-11` |
| A2 | 관리자: 학생↔학부모 · 기사↔선탑자 **수동 매칭** | `BE-14` (+`BE-7`) | `FE-12` (+`FE-5`) |
| — | 회원가입 권한 상승 차단(선결 보안) | `BE-11` | — |

---

## 1. 확정된 결정 (뒤집지 말 것)

| ID | 결정 | 근거 |
|---|---|---|
| **D-A** | 선탑자는 **6번째 정식 역할**(`ATTENDANT`). 전용 앱 화면을 갖는다 | 사용자 지시 (A안) |
| **D-B** | **모든 버스에 선탑자가 배정돼 있다고 가정한다.** 미배정 폴백을 만들지 않는다 | 사용자 지시. 시드가 이 불변조건을 보장한다(`BE-1`) |
| ~~**D-C**~~ | ~~선탑자·기타 계정은 **데이터(시드)로만** 만든다. 앱 내 계정 생성 화면을 만들지 않는다~~ → **D-M 으로 뒤집힘(2026-08-02)** | 사용자 지시 |
| **D-D** | 스키마는 **`V1__init_schema.sql` 을 직접 수정**한다. 새 마이그레이션(V6…)을 추가하지 않는다 | 사용자 지시. 로컬 DB는 영속 볼륨이 없어 매번 재구성된다 |
| **D-E** | `ddl-auto: validate` 는 **유지**한다 | 끄면 엔티티↔스키마 불일치를 앱이 조용히 통과시킨다 |
| **D-F** | 학생 앱은 **이번 범위 밖**. 학부모 지도는 **버스 위치 + 자녀 등하원 상태**만 보여준다 | 자녀 좌표를 보내는 주체(학생 앱)가 없어 `/api/locations/children` 은 항상 빈 응답이다 |
| **D-G** | 사진은 **URL 문자열 컬럼**(`photo_url`)만 둔다. 업로드 API·파일 스토리지는 범위 밖 | 저장소에 스토리지 인프라가 없다 |
| **D-H** | P2 자동 적용 임계 = **소요시간 +300초 이내 AND 총거리 +1000m 이내** (둘 다 만족) | OR면 "거리 1km인데 20분 늘어난 경로"가 통과한다 |
| **D-I** | 배차 비교(요구 8)와 P2 자동판정은 **같은 시뮬레이션 엔진**을 쓴다 | 연산이 동일하다. 두 벌로 갈라지면 판정 기준이 어긋난다 |
| **D-J** | 기사에게서 뺏는 것은 **기록 권한뿐**이다. 운행 세션 시작·종료와 버스 위치 보고는 **기사가 유지**한다 | 요구 5는 승하차 판정만 언급한다 |
| **D-K** | 학생의 **등원 좌표를 `Student` 자체 컬럼**(`pickup_lat/lng/address`)으로 갖는다. `boardingStop`(공유 `Stop` 참조)은 그대로 두고 **좌표가 있으면 그것을 우선**한다 | P2는 "등**하**원 위치 변경"을 요구하는데, 등원 좌표가 여러 학생이 공유하는 `Stop` 뿐이면 **한 명의 변경이 같은 정류장의 다른 학생까지 움직인다.** 하원만 지원하는 것은 요구 미달이다 |
| **D-L** | `BE-6`의 `GET /api/buses/{id}` 는 **마스터 데이터만** 담는다(버스·기사·선탑자·당일 계획·명단). **운행 세션과 승하차 기록은 넣지 않는다** — 관제 화면은 그 둘을 별도 provider로 계속 조회한다 | 갱신 주기가 다르다. 세션·승하차는 운행 중 계속 바뀌어 폴링 대상이지만 버스·명단은 그렇지 않다. 합치면 **무거운 응답 전체를 폴링 주기로 다시 받는다.** "4개 조회를 1건으로"는 과장이었고, 실제로 줄어드는 건 2개다 |
| **D-M** | **D-C 를 뒤집는다.** 관리자는 앱 화면에서 학생·학부모·기사·선탑자를 **직접 만들고 고치고 내린다.** 시드는 데모 초기값 역할만 남는다 | 사용자 지시(요구 A1). 시드로만 계정을 만들면 학원이 신규 기사 한 명을 넣을 때마다 개발자가 SQL 을 고쳐야 한다 |
| **D-N** | **학생은 로그인 계정(`User`)을 만들지 않는다.** 관리자 화면이 다루는 학생은 `Student` 레코드다. `Student.userId` 는 계속 `null` 로 둔다 | D-F 로 학생 앱이 범위 밖이라 로그인할 화면이 없다. 쓰지도 않을 계정을 만들면 **비밀번호가 걸린 유령 계정**만 늘어난다 |
| **D-O** | **어떤 삭제 API도 `app_user`·`student` 행을 물리 삭제하지 않는다.** 구성원은 **학원 멤버십(`UserTenantRole`) 해제**, 학생은 **`active=false` 비활성화**다 | 두 테이블은 `bus.driver_id`·`student_guardian`·`ride_event` 등이 참조한다. 물리 삭제는 FK 위반으로 실패하거나(운 좋은 경우) **과거 승하차 기록의 주체를 지워** 감사 추적을 끊는다 |
| **D-P** | **기사↔선탑자 매칭 전용 테이블을 만들지 않는다.** 두 사람의 연결은 **`Bus.driver` + `Bus.attendant` 가 유일한 매개**다 | 별도 `driver_attendant` 표를 두면 "버스가 말하는 짝"과 "매칭표가 말하는 짝"이 갈라진다. 실제로 함께 타는 근거는 **같은 버스에 배정됐다는 사실 하나**뿐이다 |

### ⚠️ 이 확장이 뒤집는 기존 사양 (문서 갱신 대상)

1. `PRODUCT_SPEC §2` — *"동승보호자는 MVP에서 별도 역할로 분리하지 않고 기사 앱 흐름에 흡수"* → **뒤집힌다**(D-A)
2. `PRODUCT_SPEC §4.1` — *"기사·학부모가 직접 합의한 변경도 학원 승인을 거쳐야 효력이 생긴다"* → **위치 변경에 한해 무승인 자동 적용/자동 거부**로 개정(D-H)
3. `CLAUDE.md` — *"기존 `V1__init_schema.sql` 수정 금지"* → **개발 단계에 한해 허용**으로 개정(D-D)
4. **이 계획서의 `D-C`** — *"계정은 시드로만"* → **관리자 계정 관리 화면 도입**으로 개정(D-M)

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
| **구성원(Member)** | `User` + 그 학원의 `UserTenantRole` 한 쌍. `/api/members` 가 다루는 단위. **학생은 여기 들어가지 않는다**(D-N) |
| **비활성 학생** | `student.active = false`. 명단·배차·시뮬레이션에서 빠지지만 과거 승하차 기록은 그대로 남는다 |

**불변조건 (코드가 지켜야 하는 것)**

- **I-1.** 모든 `bus` 행은 `attendant_id IS NOT NULL` 이다 (시드가 보장. 스키마 제약은 걸지 않는다 — 버스 생성 API가 선탑자 없이도 생성 가능해야 하므로)
- **I-2.** 승하차 기록(`POST /api/ride-events`)은 **그 버스에 배정된 선탑자 본인**만 가능하다
- **I-3.** 시뮬레이션은 **어떤 행도 저장하지 않는다.** 저장은 `apply` 계열 API에서만 일어난다
- **I-4.** P2 자동 적용은 **당일 운행 세션이 아직 없을 때만** 가능하다(시작·종료 무관하게 세션이 있으면 거부)
- **I-5.** 노선 변경은 언제나 `version + 1` 인 **새 행**이다. 기존 행을 수정하지 않는다
- **I-6.** 한 기사·선탑자는 **같은 학원에서 동시에 2대 이상의 버스에 배정되지 않는다.** 배정 API가 거부한다(`BE-14`)
- **I-7.** 참조 중인 구성원은 멤버십을 해제할 수 없다. **버스에 기사·선탑자로 배정** 중이거나 **학생의 보호자로 연결**돼 있으면 `409` 로 거부하고, 먼저 그 연결을 끊게 한다(`BE-12`)
- **I-8.** 삭제 계열 API(`DELETE /api/members/{id}`, `DELETE /api/students/{id}`)는 **`app_user`·`student` 행을 물리 삭제하지 않는다**(D-O)
- **I-9.** `active = false` 인 학생은 **명단·배차·시뮬레이션 대상에서 제외**된다. 조회 계층이 기본으로 걸러낸다(`BE-13`)

> **`location_change_request` 컬럼 정본(2026-08-02 `BE-1` 구현으로 확정)** — `BE-10` 엔티티는 여기에 맞춘다.
> `delta_distance_m` · `delta_duration_s` · `new_lat`(NN) · `new_lng`(NN) · `target_date`(NN) · `applied_plan_id` ·
> `created_at` · `id` · `requested_by`(NN) · `student_id`(NN) · `tenant_id`(NN) · `updated_at` ·
> `decision`(NN, CHECK) · `direction`(NN, CHECK) · `new_address` · `reason`.
> 초안이 쓰던 `lat`/`lng`/`label`/`result_route_plan_id` 는 **폐기**다.

---

## 3. 데이터 모델 변경 (전체)

수정 대상은 **2개 파일뿐**이다(D-D).

### 3.1 `backend/src/main/resources/db/migration/V1__init_schema.sql`

| 테이블 | 변경 |
|---|---|
| `app_user` | `photo_url varchar(255)` **추가** |
| `student` | `photo_url varchar(255)` · `phone varchar(255)` · `pickup_lat float(53)` · `pickup_lng float(53)` · `pickup_address varchar(255)` **추가** |
| `student` | `active boolean not null default true` **추가** (D-O) |
| `bus` | `attendant_id bigint` **추가** + FK → `app_user` |
| `user_tenant_role` | `role` CHECK 목록에 `'ATTENDANT'` **추가** |
| `location_change_request` | **신규 테이블** |

### 3.2 `backend/src/main/resources/db/migration/V5__notification_log_add_route_published_type.sql`

`notification_log.type` CHECK 목록에 `'LOCATION_CHANGE_RESULT'` **추가**
(V5가 이 제약을 마지막으로 재정의하는 파일이라 여기를 고친다. V3·V4는 건드리지 않는다.)

### 3.3 `backend/src/main/resources/db/migration-local/V2__seed_data.sql`

- 선탑자 계정 3개 + `ATTENDANT` 역할 + 버스 3대 전부에 `attendant_id` 배정 (**I-1**)
- 기존 계정·학생에 `photo_url`, 학생에 `phone` 채우기
- ⚠️ **시드는 이제 "최소 데모 상태"만 만든다**(D-M). 계정을 늘리고 싶으면 시드가 아니라 **관리자 화면**(`FE-11`)에서 만든다.
  단 **I-1(모든 버스에 선탑자)** 은 여전히 시드가 보장한다 — 앱에서 선탑자를 만들 수 있게 됐다고 해서 초기 상태가 비어도 되는 것은 아니다

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

### 4.2 신규 엔드포인트 (16개 — 신규 14 + 확장 2)

| 메서드 · 경로 | 권한 | 태스크 | 용도 |
|---|---|---|---|
| `POST /api/route-plans/simulate` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-5` | 배차 변경안 비교(미저장) |
| `POST /api/route-plans/simulate/apply` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-5` | 비교안 채택 → 배정 커밋 + 승인 + 배포 |
| `GET /api/locations/children/buses` | `PARENT` | `BE-9` | 자녀가 탄 버스들의 최신 위치 |
| `POST /api/location-change-requests` | `PARENT` | `BE-10` | 등하원 위치 변경 신청(자동 판정) |
| `GET /api/location-change-requests/children` | `PARENT` | `BE-10` | 내 신청 이력 |
| `GET /api/location-change-requests` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-10` | 학원 신청 이력 |
| `GET /api/members/{id}` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-12` | 구성원 상세(연결 현황 포함) |
| `PATCH /api/members/{id}` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-12` | 이름·전화·사진·역할 수정 |
| `PATCH /api/members/{id}/password` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-12` | 관리자 강제 비밀번호 재설정 |
| `DELETE /api/members/{id}` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-12` | **학원 멤버십 해제**(계정은 남는다, D-O) |
| `PATCH /api/students/{id}` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-13` | 학생 이름·전화·사진 수정 |
| `DELETE /api/students/{id}` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-13` | **비활성화**(`active=false`, D-O) |
| `DELETE /api/students/{id}/guardians/{guardianUserId}` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-14` | 학생↔학부모 **연결 해제** |
| `GET /api/members/{id}/students` | `ACADEMY_ADMIN, PLATFORM_ADMIN` | `BE-14` | 학부모 기준 **역방향** 자녀 목록 |

`GET /api/buses/{id}` (`BE-6`) 와 `PATCH /api/buses/{id}/assignment` (`BE-7`) 는 **경로 유지 + 응답/요청 확장**이다.

### 4.2.1 요구 A1·A2 가 **이미 있는 것 위에** 올라간다는 점

새로 만드는 것과 이미 있는 것을 섞지 않도록 실측 현황을 못 박는다(2026-08-02 코드 기준).

| 기능 | 현재 상태 | 이번에 하는 일 |
|---|---|---|
| 구성원 **생성** | ✅ `POST /api/members` (`MemberCommandService.register`) | `ATTENDANT` 역할 허용만 확인(`BE-2` 가 enum 추가) |
| 구성원 **목록** | ✅ `GET /api/members?tenantId=&role=` | `active`/연결 현황 필드 보강 |
| 구성원 **상세·수정·삭제** | ⬜ 없음 | `BE-12` 가 신설 |
| 학생 **생성·목록·상세** | ✅ `POST` · `GET` · `GET /{id}` | 그대로 |
| 학생 **수정·삭제** | ⬜ 없음 (`PATCH /{id}/assignment`·`/{id}/dropoff` 만 있다) | `BE-13` 이 신설 |
| 학생↔학부모 **연결** | ✅ `POST /api/students/{id}/guardians` (`student_guardian` 테이블) | 그대로 |
| 학생↔학부모 **해제·역방향 조회** | ⬜ 없음 | `BE-14` 가 신설 |
| 기사↔버스 배정 | ✅ `PATCH /api/buses/{id}/assignment` (`driverId`) | `BE-7` 이 `attendantId` 추가 |
| 기사↔선탑자 매칭 | — | **전용 API 를 만들지 않는다**(D-P). `BE-7` 의 배정 하나로 끝난다. `BE-14` 는 **중복 배정 금지(I-6)** 만 얹는다 |
| 관리자 계정/매칭 **화면** | ⬜ 없음 (관리자 화면 4개: 관제·배차·노선·알림) | `FE-11` `FE-12` 가 신설 |

> ⚠️ **가장 흔한 오해: "학생 계정"과 "구성원 계정"이 같은 것이라는 착각.**
> 학부모·기사·선탑자는 `app_user` + `user_tenant_role` 이지만, **학생은 `student` 라는 별도 테이블**이다.
> 그래서 `GET /api/members?role=STUDENT` 로는 학생 명단이 나오지 않는다 — 학생은 `/api/students` 다.
> 화면은 하나여도 **호출하는 API 는 두 벌**이라는 걸 `FE-11` 이 전제한다.

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
// ⚠️ 컴포넌트 순서는 이 선언이 정본이다 — seatCapacity 는 delta 다음 마지막 자리다.
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
            String label,               // PICKUP = pickupAddress → 없으면 boardingStop.name / DROPOFF = dropoffAddress
                                        // 전부 비면 "좌표 지정". 라벨 우선순위는 좌표 우선순위(D-K)와 같은 순서다
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

### 4.6 계정 CRUD · 매칭 DTO 계약 — 이 정의가 유일한 출처다 (`BE-12`~`BE-14`)

```java
// user/dto/UpdateMemberRequest.java (BE-12)
// 전달된 필드만 갱신한다(null = 그대로). email 은 로그인 ID라 바꾸면 중복 검사를 다시 한다.
public record UpdateMemberRequest(
        @Email String email,
        String name,
        String phone,
        String photoUrl,
        Role role) {}                   // 역할 변경 = user_tenant_role.role 갱신

// user/dto/ResetPasswordRequest.java (BE-12)
// 관리자가 대신 초기화하는 경로라 현재 비밀번호를 묻지 않는다.
public record ResetPasswordRequest(
        @NotBlank @Size(min = 8) String newPassword) {}

// user/dto/MemberDetailResponse.java (BE-12)
// 목록용 MemberResponse 를 확장한다. 화면이 "지울 수 있는가"를 한 번의 조회로 판단하게 한다(I-7).
public record MemberDetailResponse(
        Long userId, String email, String name, String phone, String photoUrl,
        Role role, Long tenantId,
        List<BusRef> assignedBuses,     // 기사·선탑자로 배정된 버스들
        List<StudentRef> guardedStudents) {  // 보호자로 연결된 학생들

    public record BusRef(Long busId, String plateNumber, boolean asAttendant) {}
    public record StudentRef(Long studentId, String name, String relation) {}
}

// student/dto/UpdateStudentRequest.java (BE-13)
// 배차(assignment)·하차지(dropoff)는 기존 전용 PATCH 가 담당한다. 여기는 인적 정보만이다.
public record UpdateStudentRequest(
        String name,
        String phone,
        String photoUrl) {}
```

**응답 규약 3가지 — 어기면 화면이 조용히 틀린다.**

1. `DELETE /api/members/{id}` 는 **`UserTenantRole` 행만 지운다.** 응답은 `ApiResponse<Void>`.
   `bus.driver_id`·`bus.attendant_id`·`student_guardian` 중 하나라도 이 유저를 가리키면
   **`ErrorCode.CONFLICT` 로 거부**하고 메시지에 **막고 있는 대상**을 적는다(예: *"3호차 기사로 배정돼 있습니다"*).
   막연히 "삭제할 수 없습니다"만 주면 관리자가 무엇을 먼저 풀어야 하는지 모른다.
2. `DELETE /api/students/{id}` 는 `active=false` 로 바꾸고 **갱신된 `StudentResponse` 를 돌려준다**(`204` 아님).
   화면이 재조회 없이 목록에서 바로 회색 처리할 수 있어야 한다.
3. `GET /api/students` 는 기본으로 **활성 학생만** 준다(I-9). 비활성까지 보려면 `?includeInactive=true`.
   기본값을 "전부"로 두면 **퇴원생이 배차·명단·시뮬레이션에 계속 끼어든다.**

**중복 배정 검사(I-6) 위치** — `BE-14` 가 `BusCommandService.assign` 에 넣는다.

```
같은 tenant 안에서
  driverId  가 이미 다른 bus 의 driver_id     → CONFLICT
  attendantId 가 이미 다른 bus 의 attendant_id → CONFLICT
자기 자신(같은 busId)으로의 재배정은 통과시킨다 — 안 그러면 노선만 바꾸는 요청이 막힌다.
```

> ⚠️ 기사와 선탑자는 **서로 다른 역할이라 각각 검사**한다. "한 사람이 A버스 기사이면서 B버스 선탑자"는
> 현실적으로 불가능하지만, 이 계획은 **역할 교차 검사까지는 하지 않는다** — `user_tenant_role` 이
> 한 사람에게 두 역할을 허용하는 구조라 교차 금지는 별도 정책 결정이 필요하고, 이번 범위 밖이다.

---

## 5. 실행 순서 · 진행 현황

의존 관계상 **`BE-1` → `BE-2` → `BE-3`** 은 반드시 순차다. 그 뒤로는 표의 "선행" 열만 지키면 병렬 가능하다.

| 단계 | 태스크 | 선행 | 병렬 가능 | 상태 |
|---|---|---|---|---|
| P0 | `FE-0` 미커밋 1,732줄 정리·커밋 | — | ✅ BE와 무관 | ✅ **완료 2026-08-02** (커밋 10건, `flutter analyze`·`test 293개` 통과) |
| P0 | `BE-11` 회원가입 권한 상승 차단 | — | ✅ | ⬜ |
| P1 | `BE-1` 스키마·시드 확장 | — | ❌ 단독 | ⬜ |
| P1 | `BE-2` `Role.ATTENDANT` + 엔티티 필드(`Student.active` 포함) | `BE-1` | ❌ 단독 | ⬜ |
| P1 | `BE-3` 승하차 권한 재배치 | `BE-2` | ❌ 단독 | ⬜ |
| P2 | `BE-4` 시뮬레이션 엔진 | `BE-2` | ✅ BE-6·BE-7·BE-8 과 | ⬜ |
| P2 | `BE-6` 버스 상세 종합 응답 | `BE-2` | ✅ | ⬜ |
| P2 | `BE-7` 배차 API 선탑자 + 참조 검증 | `BE-2` | ✅ | ⬜ |
| P2 | `BE-8` 버스 위치 실시간 push | `BE-2` | ✅ | ⬜ |
| P3 | `BE-5` 시뮬레이션 API + 채택 | `BE-4` | ⚠️ BE-9 와만 | ⬜ |
| P3 | `BE-9` 학부모 버스 위치 조회 | `BE-8` | ✅ | ⬜ |
| P3 | `BE-10` 위치 변경 요청 | `BE-4` `BE-1` | ⚠️ **`BE-5` 와 병렬 금지** | ⬜ |
| P2 | `BE-12` 구성원 상세·수정·비번재설정·멤버십 해제 | `BE-2` | ✅ BE-4·BE-6·BE-8 과 | ⬜ |
| P2 | `BE-13` 학생 수정·비활성화 API | `BE-2` | ✅ (`active` 컬럼·엔티티 필드는 `BE-1`·`BE-2` 가 이미 넣었다 — V1 을 다시 만지지 않는다) | ⬜ |
| P3 | `BE-14` 매칭 보강(보호자 해제·역방향 조회·중복배정 금지) | `BE-7` `BE-12` | ⚠️ **`BE-7` 과 병렬 금지**(같은 `BusCommandService.assign`) | ⬜ |

> ⚠️ **`BE-5` 와 `BE-10` 은 같은 파일을 고친다.** 둘 다 `routing/command/RoutingCommandService.java` 에 메서드를 추가한다
> (`BE-5` → `applySimulation`, `BE-10` → `republishForBus`). 병렬로 돌리면 충돌한다. **`BE-5` 를 먼저 끝내고 `BE-10` 을 시작한다.**
| P4 | `FE-1` 역할·라우트 골격 | `FE-0` `BE-3` | ❌ 단독(다른 FE의 전제) | ⬜ |
| P4 | `FE-2` 공용 인물 카드 위젯(사진·이름·전화) | `FE-1` | ❌ 단독(4묶음이 공유) | ⬜ |
| P5 | `FE-3` 기사 앱 읽기전용화 | `FE-2` | ⚠️ **`FE-4` 와 병렬 금지** | ⬜ |
| P5 | `FE-4` 선탑자 앱 2화면 | `FE-3` `BE-3` | ⚠️ `FE-3` 다음에 순차 | ⬜ |
| P5 | `FE-5` 관리자 버스 상세 확장 | `FE-1` `BE-6` | ✅ | ⬜ |
| P5 | `FE-6` 관리자 배차 편집·비교 | **`FE-5`** `BE-5` | ⚠️ `FE-5` 다음에 순차 | ⬜ |
| P5 | `FE-7`~`FE-10` 학부모 앱 | `FE-1` `BE-9` `BE-10` | ✅ | ⬜ |
| P5 | `FE-11` 관리자 계정 관리 화면(구성원+학생 CRUD) | `FE-2` `BE-12` `BE-13` | ✅ 다른 FE 묶음과 | ⬜ |
| P5 | `FE-12` 관리자 매칭 화면(학생↔학부모) | **`FE-11`** `BE-14` | ⚠️ `FE-11` 다음에 순차 | ⬜ |
| P6 | `DOC-1`~`DOC-5` SoT 문서 갱신 → [**전용 계획서**](./2026-08-02-mvp-확장-DOC-문서갱신.md) | 전부 | ❌ 순차(같은 파일군을 만진다) | ⬜ |

> ⚠️ **기사↔선탑자 배정 UI 는 `FE-12` 가 아니라 `FE-5`(관리자 버스 상세)에 있다.**
> 두 사람을 잇는 유일한 매개가 버스이기 때문이다(D-P) — 버스를 떠난 자리에서 "기사와 선탑자를 짝지어" 봐야
> 저장할 곳이 없다. `FE-12` 는 **버스가 개입하지 않는 유일한 매칭인 학생↔학부모**만 담당한다.

**`FE-11` 화면 구성 (요구 A1)** — 경로 `/admin/members`, `AppRoutes.adminMembers` 로 추가한다.

| 탭 | 데이터 출처 | 만들기 | 고치기 | 내리기 |
|---|---|---|---|---|
| 학생 | `GET /api/students` | `POST /api/students` | `PATCH /api/students/{id}` | `DELETE`(비활성) |
| 학부모 | `GET /api/members?role=PARENT` | `POST /api/members` | `PATCH /api/members/{id}` | `DELETE`(멤버십 해제) |
| 기사 | `GET /api/members?role=DRIVER` | 〃 | 〃 | 〃 |
| 선탑자 | `GET /api/members?role=ATTENDANT` | 〃 | 〃 | 〃 |

- 4개 탭 중 **학생 탭만 다른 API 를 쓴다**(§4.2.1의 오해 참고). 나머지 3개는 `role` 파라미터만 다른 같은 경로다 →
  구성원 3탭은 **하나의 위젯을 `Role` 로 파라미터화**해 재사용하고, 학생 탭만 별도로 만든다.
- 인물 표시는 `FE-2` 의 공용 인물 카드(사진·이름·전화)를 그대로 쓴다. 새로 만들지 않는다.
- 비활성 학생은 **목록에서 지우지 않고 회색 + "비활성" 배지**로 남긴다 — 색만으로 구분하지 않는다(디자인 시스템).

**`FE-12` 화면 구성 (요구 A2)** — `FE-11` 학생 탭의 **상세 시트 안**에 넣는다. 독립 라우트를 만들지 않는다.

- 학생 상세 → "보호자" 섹션 → `추가`(학부모 검색 후 `POST .../guardians`) · `해제`(`DELETE .../guardians/{userId}`)
- 학부모 상세 → "자녀" 섹션 → `GET /api/members/{id}/students` 로 **읽기 전용** 표시(편집은 학생 쪽 한 곳에서만)
- ⚠️ **편집 진입점을 양쪽에 두지 않는다.** 같은 관계를 두 화면에서 고칠 수 있으면 한쪽이 낡은 상태를 덮어쓴다

### 에이전트 배치

| 태스크군 | 에이전트 | 호출 방식 |
|---|---|---|
| `BE-*` 구현 | 직접 구현 후 `test-writer`(**워크트리 격리 필수**) | 태스크 1건당 1회 |
| `BE-3` `BE-7` `BE-11` `BE-12` `BE-14` 완료 후 | `security-reviewer` | 권한·검증 변경이라 반드시. `BE-12` 는 **비밀번호 재설정·역할 변경**이 걸려 있어 특히 중요하다 |
| `BE-*` 커밋 전 | `convention-auditor` → `diff-reviewer` | 순서 고정 |
| `FE-3`~`FE-12` | `ui-implementer` **4갈래 동시** (기사+선탑자 / 관리자 관제·배차 / 학부모 / 관리자 계정·매칭) | 화면 묶음당 1개 |
| `FE-*` 완료 후 | `design-system-auditor` | 묶음별 |
| `DOC-*` 완료 후 | `docs-drift-auditor` | 전체 1회 |
| 테스트 실패 시 | `debugger` | 필요 시 |

> ⚠️ `ui-implementer` 병렬 실행은 **4갈래**다. 원래 3갈래였고 `FE-11`·`FE-12` 로 한 갈래가 늘었다.
> - `FE-3`(기사)과 `FE-4`(선탑자)는 `roster_student_tile.dart`·`roster_student.dart`·`driver_roster_screen.dart` 를
>   **공유**하므로 한 에이전트가 순차로 처리한다.
> - `FE-11`·`FE-12` 는 **새 파일만 만든다**(`features/user/**`, `features/student/presentation/**`). 기존 관리자 화면
>   (`FE-5`·`FE-6`)과 파일이 겹치지 않아 별도 갈래로 띄울 수 있다. **단 `AppRoutes` 한 파일만 겹친다** —
>   `adminMembers` 경로는 `FE-1` 이 미리 넣어두고, 이후 아무도 이 파일을 다시 만지지 않는다.
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

⚠️ **요구 A1·A2 가 `DOC-1`~`DOC-5` 전부의 분량을 늘린다.** 단계별 지시는 DOC 전용 계획서에 있다.
- `DOC-1`(`PRODUCT_SPEC`) — §11 구현 상태표의 계정 관리 행, §12 갭 목록, D-C 폐기(D-M)·D-N·D-O
- `DOC-2`(`USER_FLOWS`) — 관리자 계정 등록·수정·해제 플로우, 학생↔학부모 연결/해제 플로우
- `DOC-3`(`API_SPEC`) — `/api/members` 4건 · `/api/students` 2건 · 매칭 2건, **총 8개 엔드포인트** + `?includeInactive=`
- `DOC-4`(`ARCHITECTURE`) — 삭제 정책(D-O)과 매칭의 유일 매개(D-P)를 구조 결정으로 남긴다
- `DOC-5`(`CLAUDE.md`·`docs/README.md`) — 관리자 화면 4개 → **5개**

⚠️ **구현이 끝나기 전에는 손대지 않는다.** `docs/` 4종은 기획서가 아니라 **실측 기록**이고,
`PRODUCT_SPEC.md` §11 은 스스로를 *"지금 실제로 되는 것의 유일한 근거"* 로 규정한다 —
구현 전에 ✅ 를 적으면 그 자체가 허위가 된다.
