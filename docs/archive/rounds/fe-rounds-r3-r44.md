# 보관 — 프론트 라운드 FE-R3 ~ R44 (5.6~5.16)

**2026-10-01 `docs/frontend/IMPLEMENTATION_PLAN.md` 에서 원문 그대로 옮긴 보관 문서(R46-DOCS · 분기점 `bef9d3ce`).** 요약·재작성 없이 줄 단위로 옮겼고, 절 제목의 번호(`## 8.2 …` 등)도 원문 그대로라 옛 절 번호 인용이 이 파일에서 같은 번호로 찾아진다. **읽고 싶을 때만 연다** — 지금의 규칙·진행 표는 본문 ``docs/frontend/IMPLEMENTATION_PLAN.md`` 에 있다. 이 파일 안의 "위"·"아래"·"이 문서" 와 절 번호 인용은 옮기기 전 문서 기준이며, 본문에 남은 절(`7` 횡단 규칙 · `8.73` 이후 등)을 가리킬 수 있다.

| 항목 | 내용 |
|---|---|
| 옮긴 절 | 5.6(`FE-R3`) · 5.7(`R4` 병합) · 5.8(`R31`) · 5.9(`R32`) · 5.10(`R33`) · 5.11(`R34`) · 5.12(`R35`) · 5.13(`R36-FE`) · 5.14(`R39`) · 5.15(`R41-UI`·`R42`·`R43`) · 5.16(`R44`) |
| 기간 | 2026-09-14 ~ 2026-09-30 |
| 줄 수 | 원문 1117줄(아래 머리말·구분선 제외) |

---

## 5.6 ⚖ `FE-R3` 목표 표 — 미구현 화면 2건 + 웹 시험 격리 + 정본 결손 (2026-09-14 계획 · **착수 전**)

### 이 라운드가 덮는 것 — **F5 가 실서버 호출로 잡은 5건의 뒤처리**

| F5 발견 | 지금 상태 | 어디서 닫나 |
|---|---|---|
| 1 로그인 실패 5회 차단 무효 | **미수정** | `BE-R2` 목표 1~8 (`docs/archive/rounds/be-rounds-r1-r4.md §8.3`) |
| 2 도착 처리 응답 키 이름 불일치 | **앱만 고침** — 정본에 `next_stop` 내부 필드가 부재 | `BE-R2` 목표 13(`BE-C`) |
| 3 사진 없는 학생에서 명단 화면 사망 | **앱만 고침** — 정본은 `photo_url` 필수인데 **시드 6명 전원 `null`** | `BE-R2` 목표 14(`BE-C`) |
| 4 결정된 승인 건의 `null` 필드 접근 | **웹만 고침** — 정본은 6필드 필수인데 서버가 의도로 `null` | `BE-R2` 목표 15(`BE-C`) |
| 5 정본↔코드 어긋남 9건 | **계획서 문면 3건은 조율자가 고침 · 정본 결손 6건 잔존** | 이 절의 `DOC` 좌석(목표 15~19) |

⚠ **2·3·4 를 "고쳤다" 로 세지 마라.** 셋 다 **클라이언트가 서버 쪽으로 맞춰 준 증상 처치**이고
**정본은 여전히 다른 말을 하고 있다.** 정본을 읽고 만드는 다음 소비자는 같은 자리에서 또 죽는다.



백엔드 갈래(`BE-R2` — `Ruling 282` + 백엔드·Redis 격리)는 `docs/archive/rounds/be-rounds-r1-r4.md §8.3` 에 있다.
**두 갈래는 파일이 겹치지 않아 동시에 돌릴 수 있다.**

### ⚠ 착수 전 정정 — F5 이월 2번은 **오기였다**

`W1` 이 *"`A-15`(경유 지점)·`RTE-09`(정차 순서 최적화)는 API 함수만 있고 부르는 화면이 부재"* 라고
적었으나 **둘 다 화면에 붙어 있다**(2026-09-14 조율자 재계수).

| 것 | 실측 |
|---|---|
| 경유 지점(`A-15`·`§5.15`) | `features/route/components/RunWaypointPanel.tsx` — `RouteDetail.tsx:79` 가 마운트 |
| 정차 순서 최적화(`RTE-09`·`§5.9`) | `features/route/components/RouteStopsPanel.tsx` — `RouteDetail.tsx:77` 이 마운트 |

⇒ **웹에 새로 만들 화면은 없다.** 남은 것은 그 화면들이 안고 있는 **사양 공백 2건**(목표 9)과
**학부모 앱의 진짜 미구현 2건**(목표 1~8)이다.

⚠ **이것이 이 저장소에서 "미구현" 오보의 두 번째다**(첫째는 `Ruling 272` 의 `A-14`).
**판정문의 "부재" 주장은 옮겨 적기 전에 `grep` 으로 한 번 더 센다** — `phase-goal-loop.md §6.3` 이 이미 요구한다.

### 실제로 비어 있는 것 — 학부모 앱 2건 (실측)

| 것 | 실측 | 백엔드 |
|---|---|---|
| `§3.10` 노선 상세(`P-08`·`S-04`) | `route_detail_screen.dart` **14줄 자리표시** — *"이번 범위가 아니다"* 주석 | **구현돼 있다** (`StudentRouteController`) |
| `§3.11` 버스 위치 REST(`P-07`·`S-02`) | `grep -rn 'bus-position' lib` **0건** — `LiveMapScreen` 이 WS 만 쓴다 | **구현돼 있다** (`StudentBusPositionController`) |

⚠ **둘 다 백엔드가 이미 있다 — 프론트만 붙이면 된다.** 새 엔드포인트를 만들지 마라.

### 목표 표 — 전항 통과가 완료 조건

| # | 완료 조건 | 검증 (이 명령·단언이 통과해야 끝) |
|:-:|---|---|
| 1 | **`RouteDetailScreen` 이 `§3.10` 을 실제로 그린다** | 자리표시 제거. 회차 요약·기사·동승자·본인 승하차지·정차 목록 |
| 2 | ⚠ **표시 범위가 `§3.10` 문면 그대로다** | **승차지 이전 2개 · 승차지 · 하차지만.** 전체 정차 목록을 그리면 미통과 — 다른 집 아이의 승하차지가 노출된다(`C-08` 계열) |
| 3 | **`ETA`·승하차지별 인원이 부재하다** | `§3.10` 이 명시적으로 뺀 값이다. **그리면 미통과** |
| 4 | **`confirmed=false` 면 "확정 전" 배지** | 고정 노선을 그리되 확정 전임을 표시. 에러 화면으로 가지 않는다 |
| 5 | **`escort.phone` 연락 버튼이 있고 기사 연락처는 부재** | `§3.10` — 학부모→기사 직접 연락은 범위 밖 |
| 6 | **`LiveMapScreen` 이 `§3.11` 을 첫 진입에 호출한다** | 지금은 WS 만 쓴다. **REST 스냅샷 → WS 갱신** 순서. ⚠ **WS 를 REST 로 갈아치우지 마라** — 정본이 둘 다 규정한다 |
| 7 | **신호 유실·미등원 표현** | 마지막 수신 후 **2분**이면 "마지막 확인 위치 · N분 전"(`Ruling 208`) · `absent` 면 "오늘은 버스를 이용하지 않습니다" · `run_status != moving` 이면 좌표 부재를 에러가 아니라 **상태로** 표시 |
| 8 | **실서버 계약 시험 + 예외 경로** | `§3.10`·`§3.11` 을 실백엔드로 호출. `404 STUDENT_NOT_FOUND` · `403 FORBIDDEN`(연결 부재 자녀) 재현. **건너뜀 0** |
| 9 | **웹 사양 공백 2건을 판정한다** | ①`RunWaypointPanel` 이 **회차 id 를 손으로 받는다**(오늘 회차 카탈로그가 그 화면에 부재) ②**배포된 경유 지점 목록 조회 엔드포인트가 부재**해 세션 메모리로 버틴다. ⚠ **코드를 고치기 전에 "정본 공백인가 화면 설계 문제인가" 를 가르고 판정을 적어라** — 정본 개정이 필요하면 조율자에게 올린다 |
| 10 | **웹 계약 시험이 알려진 상태에서 시작한다** | 사용자 지시(2026-09-14). 앱 2종은 F5 에서 **조건부 `/dev/reset` + 직렬 실행**으로 이미 갖췄다 — **웹만 비어 있다.** 같은 형태로 맞춘다 |
| 11 | ⚠ **같은 명령을 연달아 네 번 — 네 번 다 실패 0 · 건너뜀 0** | 앱·웹 두 갈래 각각. 사이에 **어떤 손질도 없이.** 네 회차 수치를 전부 적는다. **이것이 10번의 진짜 완료 조건이다** |
| 12 | **정적 분석 새 지적 0** | 웹 `npm run lint` **0건**(기준선 0) · `parent-app` `flutter analyze` **1건**(기준선 1) |
| 12.1 | **초기화 도우미가 로그인 실패를 삼키지 않는다** | 📌 F5 이월 6번. 두 앱의 `test/support/real_backend_target.dart` 가 `DioException` 을 통째로 삼켜 **`401` 까지 조용히 넘긴다** — 그러면 시드가 위험한 상태로 남는데 아무도 모른다. **연결 거부(백엔드 미기동)만 삼키고 나머지는 던진다.** ⚠ **`P3` 가 두 앱 파일을 함께 고친다**(`W3` 와 겹치지 않는다) |
| 13 | 조율자 — **병합 후 단독 전체 실행** | 실패 0 · 건너뜀 0. 착수 전 — 웹 **247** · 매니저 **118** · 학부모 **104** · `core` **48** · `ui` **71** |
| 14 | 조율자 — **시드 서버 오염 0** | `schoolbus` 의 `emergency_alert` **1건** = 정본 `V2__seed_data.sql` 값 |

### 좌석 `DOC` — **정본 결손 6건** (F5 가 보고만 하고 닫지 못한 것)

**F5 좌석 셋이 정본을 직접 세어 어긋남 9건을 신고했다.** 그중 **계획서 문면 3건은 조율자가 고쳤고**
(`§3.1` 5곳 · `§3.3` 3곳 · `report/page.tsx` 주석 1곳), **정본 자체의 결손 6건이 남았다.**

⚠ **파생본만 고치고 정본을 두면 다음 세션이 정본을 읽고 같은 오기를 다시 만든다** — `§6.1` 이 이미
겪은 형태다(해소된 것이 미해소로 남아 판정을 새로 만들게 한 사고).

| # | 결손 | 신고자 | 성격 |
|:-:|---|---|---|
| ⓐ | `A-12`(§5.14 회차 배치 변경)에 **대응 유저플로우 부재** | `W1` | `UF-M-06` 은 기초 데이터 CRUD 라 성격이 다르다 |
| ⓑ | `A-16`(비상 알림 수신)에 **대응 유저플로우 부재** | `W1` | `UF-X-08` 과 겹치는지 판정 필요 |
| ⓒ | `A-17`(학원 설정)에 **대응 절 0건** | `W1`·이전 세션 | 이미 두 번 신고됐다 |
| ⓓ | `§6.14`(회차 강제 확정)에 **기능 ID·유저플로우 둘 다 부재** | `W2` | ⚠ 계획서의 `O-06` 은 **관제(§6.8·6.9)에 이미 쓴 코드의 중복** — 새 ID 가 필요한지부터 판정 |
| ⓔ | `§6.6`·`§6.7`(관계자 계정 관리)에 **전속 흐름 부재** | `W2` | 기능 ID 는 `O-02` 로 공유하나 흐름이 없다 |
| ⓕ | `§2.1~2.4`(학원 검색·가입·가입상태·재신청)에 **학부모 앱 실서버 시험 부재** | `P` | 정본 결손이 아니라 **검증 결손** — 함께 닫는다 |

| # | 완료 조건 | 검증 |
|:-:|---|---|
| 15 | **ⓐ~ⓔ 를 "문서 누락" 과 "의도된 부재" 로 가른다** | 하나씩 판정하고 **근거를 적는다.** ⚠ **새 기능 ID 를 함부로 만들지 마라** — `CLAUDE.md` 가 금지한다. 기존 ID 로 덮이면 그렇게 적는다 |
| 16 | **누락으로 판정한 것만 정본에 절을 신설한다** | `UF-X-09`(2026-09-13 신설 당시 이름은 `UF-X-05`) 신설 때와 같은 형식(`Ruling` 번호 · 날짜 · 기존 ID 참조). **화면·API·기능 ID 가 이미 있는데 흐름만 없는 형태**라 새 기능을 만드는 것이 아니다 |
| 17 | **ⓓ 는 기능 ID 중복부터 판정한다** | `O-06` 이 `§6.8`·`§6.9`(전체 관제)와 `§6.14`(강제 확정) **둘에 붙어 있는지** `FEATURE_SPEC` 에서 직접 센다. 중복이면 어느 쪽이 원래 주인인지 근거로 가른다 |
| 18 | **ⓕ — `§2.1~2.4` 실서버 계약 시험을 더한다** | `parent-app` 에 4개 엔드포인트. **건너뜀 0** · 되돌릴 수 없는 상태를 소비하지 않는다 |
| 19 | **정본을 고쳤으면 파생본을 같은 회차에 맞춘다** | 계획서 `§3.1`·`§3.3`·`§3.4` 표. ⚠ **정본만 고치고 표를 두면 이 라운드가 만든 어긋남이 하나 더 생긴다** |

⚠ **`DOC` 좌석은 `docs/USER_FLOWS.md`·`docs/FEATURE_SPEC.md` 와 계획서 표만 고친다** — 코드를 건드리지 않는다.
⚠ **`BE-C`(`docs/API_SPEC.md`)와 파일이 겹치지 않는다** — 동시에 돌려도 된다.

### 좌석 배정 — **3좌석 병렬**

| 좌석 | 대상 | 목표 | 전용 자원 |
|:-:|---|---|---|
| `P3` | `parent-app` 화면 2건 + **두 앱의 초기화 도우미** | 1~8 · 11(앱) · 12(앱) · **12.1** | 포트 `8152` · DB `schoolbus_fer3_p` |
| `W3` | `academy-web` 판정 + 시험 격리 | 9 · 10 · 11(웹) · 12(웹) | 포트 `8153` · DB `schoolbus_fer3_w` |
| `DOC` | `USER_FLOWS`·`FEATURE_SPEC` + 계획서 표 · `§2.1~2.4` 시험 | 15~19 | 포트 `8154` · DB `schoolbus_fer3_d` |

⚠ **`BE-R2` 와 동시에 돌린다(2026-09-14 사용자 지시).** 포트가 `8152` 에서 겹쳐 있던 것을 고쳤다 —
`BE-R2` 의 `BE-C` 가 **`8155`** 로 옮겼고 `FE-R3` 쪽 `8152`·`8153`·`8154` 는 그대로다.
**백엔드 갈래는 `8150`·`8151`·`8155`, 프론트 갈래는 `8152`·`8153`·`8154`.**

⚠ **`P3` 는 지도를 새로 만들지 않는다** — `docs/frontend/IMPLEMENTATION_PLAN.md §8.3.1` 의 포트 규칙대로 화면 코드가 `NaverMap`·`NMarker` 를
직접 쓰면 미통과다. `lib/core/map/map_surface.dart` 계약을 그대로 쓴다.
⚠ **`P3` 는 `baraeda_core`·`baraeda_ui` 를 고쳐야 하면 먼저 보고한다** — 공유 패키지라 `W3` 와 겹칠 수 있다.

### 각 좌석에 공통으로 실을 것

1. ⚠ **인용을 사실로 믿지 마라** — 이 표의 "미구현" 도 인용이다. **착수 직후 `grep` 으로 직접 세고
   어긋나면 보고하라.** 이번 라운드 자체가 그 오보를 고치려고 생겼다
2. ⚠ **되돌릴 수 없는 호출을 소비하는 시험을 만들지 마라** — F5 에서 세 좌석이 밟았다(`phase-goal-loop.md §5.4`)
3. **계수는 `hidden:false` AND 이름이 `loading `·`(setUpAll)`·`(tearDownAll)` 이 아닌 것만**
4. **`--dart-define=API_BASE_URL=…/api/v1`** · 웹은 **`NEXT_PUBLIC_API_BASE_URL`** — 없으면 적재 실패한다
5. **판정문은 메인 저장소 `.claude/fer3/report-<좌석>.md`.** ⚠ **파일 쓰기가 거부되면 전문을 메시지로 보내라**(F5 에서 7좌석 전부 거부됐다)

### ✅ 병합 후 단독 전체 실행 — 6종 전부 실패 0 · 건너뜀 0 (2026-09-14 조율자)

**다른 시험이 하나도 돌지 않는 것을 확인하고 시작했다** — 이 라운드에서 연결 상한 초과가
반복됐으므로 **이 회차가 유일하게 증거 능력이 있는 측정**이다(`Ruling 286` 확정 근거).

| 대상 | 검사 | 실패 | 건너뜀 | 착수 전 | 비고 |
|---|:-:|:-:|:-:|:-:|---|
| 백엔드 | **1,303**(222클래스) | 0 | 0 | 1,302 | `BE-A` 신규 1건 · 전용 DB `schoolbus_verify` · `4m 11s` |
| 관계자 웹 | **248**(69파일) | 0 | 0 | 247 | `W3` 신규 1건 · `56.3s` |
| 학부모 앱 | **113** | 0 | 0 | 104 | `P3` 신규 **9건**(계약 5 + 가입 4) |
| 매니저 앱 | **118** | 0 | 0 | 118 | 변동 부재 |
| `baraeda_core` | **40** | 0 | 0 | 48 | ⚠ 아래 |
| `baraeda_ui` | **71** | 0 | 0 | 71 | 변동 부재 |

#### ⚖ Ruling 286 확정 — 목표 11 **통과**

단독 실행에서 **연결 상한 초과가 한 건도 나지 않았다.** `BE-B` 가 신고한 3회 실패가
**동시 실행 탓이었다는 것이 이 회차로 확인**된다. 조건부 판정을 **통과로 확정한다.**

#### ⚠ 조율자가 병합 후 실행에서만 잡은 것 — 시험이 **특정 좌석 DB 에 묶여 있다**

`real_backend_p5_test.dart` 의 `404 STUDENT_NOT_FOUND` 시험이 **다른 포트를 향하면 실패한다.**

```
@8153(schoolbus_fer3_w) → ❌ 실패 1건
@8152(schoolbus_fer3_p) → ✅ 통과
```

**기제** — 그 시험은 `setUpAll` 에서 `docker exec ... psql -d schoolbus_fer3_p` 로 픽스처를 심는데,
**앱이 붙는 곳은 `API_BASE_URL` 이 가리키는 서버**다. 둘이 갈리면 **픽스처는 A 에 심기고 검사는
B 를 읽는다.** ⇒ **`schoolbus_fer3_p` 가 사라지는 순간 이 시험은 영구히 실패한다.**

⚠ **좌석이 이식성 한계를 자바독에 적어 뒀으나 실패 형태를 잘못 예측했다** — *"컨테이너 이름이
다르면 **건너뜀**"* 으로 적었는데, **컨테이너 이름이 맞고 DB 만 다르면 실패**다. 건너뜀보다 낫다
(조용하지 않다) 하지만 **다음 세션이 코드 결함으로 오진할 형태**다.

⇒ **이월** — 픽스처 DB 를 `API_BASE_URL` 에서 유도하거나, 백엔드에 그 상태를 만드는 수단을 두거나,
시드에 "연결은 살아 있는데 학생만 소프트 삭제" 상태를 넣는다. **셋 중 무엇이든 좌석 DB 의존을 없앤다.**
⚠ **그때까지 `schoolbus_fer3_p` 를 지우면 안 된다.**

#### ⚠ `baraeda_core` 가 48 → 40 으로 **줄었다** — 계수 규약 차이

착수 전 인용값은 **48**, 이번 실측은 **40**. **줄어든 것은 검사가 아니라 계수 방식으로 보인다** —
이번 계수는 `hidden:false` **AND** 이름이 `loading `·`(setUpAll)`·`(tearDownAll)` 이 아닌 것만 센다.
**같은 규약을 앞선 회차가 일부만 적용했을 가능성**이 크다(이 저장소에서 **7번째** 같은 형태).

⚠ **`P3` 의 "147 대 153" 도 같은 뿌리다.** 조율자가 같은 규약으로 재니 **113**이고,
**착수 전 104 + 좌석이 더한 9건 = 113 으로 정확히 대사된다.** ⇒ **113 이 맞고 147 이 과다 계수다.**
**`baraeda_core` 도 같은 형태로 보이나 대사할 증감이 부재해 단정하지 않는다 — 미확인으로 남긴다.**

### ✅ 병합 후 수정 — 예측했던 웹 시험 2건을 사양 동작으로 돌렸다 (`bc6125ce`)

**조율자가 병합 전에 예측한 그대로 났고, 예측대로 처리됐다.**
앞선 라운드가 `Ruling 282` 결함을 **정직하게 기록**해 둔 것(`[백엔드 결함 기록]`)이,
결함이 고쳐지자 거짓이 됐다.

| 위치 | 옛 단언(결함 기록) | 새 단언(사양 `§1.4`) |
|---|---|---|
| `auth/api/realBackend.test.ts:266~322` | `remaining_attempts` **5회 내내 `4`** · 5회 실패 뒤 로그인 **`200`** | **`4·3·2·1`** · 5회째 **`403 AUTH_ACCOUNT_BLOCKED`** · 그 뒤 **옳은 비밀번호도 `403`** |
| `admin/api/realBackend.test.ts:174~180` | (구조 유지) | **판단 근거 주석만 교체** — *"카운터 결함 때문에 막혀 있다"* → *"재차단 수단이 부재하다"* |

⚠ **`admin` 쪽 구조를 그대로 둔 것이 맞다** — 시드 계정으로 분기하는 형태는 **재차단 수단이
부재해서** 그렇게 짠 것이고 그 판단은 여전히 유효하다. **낡은 것은 "왜 그렇게 했는가" 뿐이었다.**

**좌석 실측** — 착수 전 `curl` 로 기대값 재현(`4·3·2·1` → `403`) 후 **연속 4회** 전부
`69 files · 248 tests · 실패 0 · 건너뜀 0`(50.6~51.0s). `npm run lint` 0건.

⚠ **차단이 실제로 걸리는데도 회차가 마르지 않는 이유를 좌석이 실측으로 확인했다** —
`globalSetup` 의 `/dev/reset` 이 매 실행 DB 를 시드로 되돌리므로 `Date.now()` 접미사 계정이
회차마다 새로 만들어진다. **4회 종료 후 그 계정이 마지막 회차분 1건만 남은 것**이 근거다.
⇒ **`W3` 가 같은 회차에 만든 초기화 장치가 자기 시험을 마르지 않게 지켰다.**

### ✅ `FE-R3` 병합 완료 (2026-09-14) — 네 갈래 충돌 0건

```
05a790e7 Merge branch 'fer3-p'
da307c60 Merge branch 'fer3-w'
522c96bd Merge branch 'ber2-b'
d18f6ac4 Merge branch 'ber2-a'
```

**병합 직후 `compileJava`·`compileTestJava` 통과를 먼저 확인했다** — `git merge` 성공은 텍스트가
합쳐졌다는 뜻이지 코드가 성립한다는 뜻이 아니다(`parallel-agents-git.md §10.5` 의 실제 사고).

#### ✅ `P3` 종결 — 커밋 4개 · 조율자 독립 확인

| 커밋 | 내용 |
|---|---|
| `cc320d4e` | 화면 2건(`route_detail_screen`·`live_map_screen`) · 783줄 |
| `3010cb80` | 목표 8 — `§3.10`·`§3.11` 실서버 계약 시험 · 337줄 |
| `36620a27` | 목표 18(재배정분) — `§2.1~§2.4` 가입 시험 · 284줄 |
| `7fdf6903` | 목표 12.1 — 두 앱 초기화 도우미가 `401` 을 삼키지 않게 · 16줄 |

**연속 5회 측정 전부 `147 · 실패 0 · 건너뜀 0`** · `flutter analyze` 착수 전 기준(1건) 복귀.

##### ⚠ 조율자가 목표 2(개인정보)를 직접 확인했다 — 이 라운드에서 가장 위험한 조항

**화면이 정차 목록을 자르지 않는다.** 서버가 좁혀 보낸 것을 그대로 그린다.
⇒ **그 전제가 참인지 서버에서 직접 확인했다.**

```
StudentRouteQueryService.java:178
  return entries.subList(Math.max(0, myIndex - 2), myIndex + 1);
:52  판단 근거 — "승차지 이전 2개 · 승차지 · 하차지만"(§3.10)
```

✅ **서버가 실제로 좁힌다. 좌석 주장이 맞고, 화면이 그 근거를 주석에 적어 뒀다.**

| 조항 | 조율자 계수 |
|---|---|
| 목표 2 — 승차지 이전 2개까지만 | ✅ 서버가 `subList` 로 보장 |
| 목표 3 — `ETA`·인원 부재 | ✅ 화면·도메인 모델 **양쪽 0건**(단어 경계 계수) |
| 목표 5 — 기사 연락처 부재 | ✅ **0건** · 주석에 *"`RouteDriver` 에 `phone` 이 부재"* |

⚠ **조율자가 한 번 오판할 뻔했다** — `grep -c 'eta'` 가 **20** 을 반환해 "ETA 를 그린다" 로 읽을
뻔했다. **`Detail` 안의 글자를 센 것**이다(`D-eta-il`). 단어 경계(`\beta\b`)로 다시 세니 **0**.
⇒ **`phase-goal-loop.md §6.0` 이 말하는 "구조를 세는 계수에 `grep` 을 쓰지 마라" 가
조율자 쪽에서도 그대로 성립한다.**

##### ⚠⚠ 이월 — 목표 2·3·5 가 **단언으로 고정되지 않았다** (다음 라운드 최우선)

**발주문이 요구한 것** — *"이 셋은 '없는 것을 확인하는' 검사 조건을 함께 만들어라.
있는 것을 확인하는 검사만 만들면 나중에 누가 더 그려도 아무도 모른다."*

**실제로 된 것** — 좌석과 조율자가 **코드를 읽어서** 확인했다. **단언은 부재하다.**
좌석이 스스로 신고했다 — *"별도 부정 검사(그 값이 응답에 있어도 안 그리는지)는 아직 자동화된
테스트로 박아두지 않았다."*

⇒ **지금 상태는 다음 사람이 `ETA` 를 그려 넣어도 아무 검사도 빨개지지 않는다.**
**목표 2 는 개인정보 노출(다른 집 아이의 승하차지)이라 특히 그렇다.**

⚠ **이번 라운드에 만들지 않은 이유는 판단이 아니라 범위다** — 2026-09-14 사용자가
**"병합까지만 진행"** 으로 끊었고, 이 단언은 **병합된 트리를 초록으로 만드는 데 필요한 것이
아니라 추가 검증**이다. `W3` 의 웹 시험 2건만 병합 단위에 넣었다.

**다음 라운드에 만들 것 — 응답에 값이 있어도 화면이 안 그리는지를 단언한다**

| # | 단언 |
|:-:|---|
| 1 | 응답 `stops[]` 에 **승차지 이전 3개 이상**이 실려 와도 화면이 **2개까지만** 그린다 |
| 2 | 응답에 `eta`·`student_count` 가 실려 와도 화면에 **그 값이 나타나지 않는다** |
| 3 | 응답에 기사 연락처가 실려 와도 **연락 버튼이 동승자 것만** 뜬다 |

⚠ **①은 서버가 이미 좁히므로 "서버가 안 좁혔을 때" 를 가정한 가짜 응답으로 만들어야 한다** —
`StudentRouteQueryService:178` 이 바뀌거나 다른 소비자가 생겼을 때를 잡는 것이 목적이다.
**판단 근거는 `.claude/fer3/report-P3.md` §1 에 있다 — 다음 담당이 그대로 받아라.**

##### 📌 `P3` 가 판단을 넘긴 것 2건

| # | 항목 | 조율자 판정 |
|:-:|---|---|
| 1 | `manager-app` 의 `flutter analyze` 오류 20여 건(`offline_queue` — 생성 코드 부재로 보임) | ✅ **해소 — 작업 폴더 한정 현상이었다.** 조율자가 **병합 트리에서 직접 돌리니 `11 issues` 전부 `info` · 오류·경고 `0`** 이고 `OfflineQueueDatabase` 오류는 **한 건도 없다.** 원인 — `.gitignore:66` 이 `frontend/**/*.g.dart` 를 무시해 **생성 코드가 작업 폴더에 복제되지 않는다**(git 이 무시하는 파일은 `git worktree add` 가 안 가져온다 — `parallel-agents-git.md §12` 와 같은 기제). 메인에는 그 파일이 실재하고 최신이다. ⇒ **`build_runner` 재실행 불요** |
| 2 | 검사 수 **147**(이번 5회) 대 **153**(이전 세션 보고) 불일치 | **좌석이 "추정이지 확인이 아니다" 로 적은 것이 맞다.** 5회가 전부 147 로 일치하므로 **현재 값은 147 이 정본**이고, 153 의 출처는 옛 로그가 있어야 가른다 |

⚠ **`docker exec school-bus-postgres-1` 컨테이너 이름을 시험이 하드코딩했다**(좌석 자진 신고).
다른 환경에서는 그 이름이 달라 **예외가 삼켜져 조용히 건너뛴다**(실패가 아니다).
⇒ **`§5.2 4.1`(건너뜀도 통과가 아니다)에 걸리는 형태라 이월로 남긴다.**

### ⚠⚠ 병합 전 확정 — `BE-A` 의 수정이 웹 시험 2건을 깨뜨린다 (2026-09-14 조율자 예측)

**`W3` 도 `BE-A` 도 잘못하지 않았다.** 두 갈래가 **같은 결함의 양쪽 끝**을 잡고 있어서 생기는 충돌이고,
**병합 전에 미리 잰 것**이다(`parallel-agents-git.md §18`·`§0.3` 이 말하는 "병합 후에만 드러나는 것" 을
이번에는 앞당겨 잡았다).

**기제** — 앞선 라운드가 `Ruling 282` 결함을 **정직하게 기록**해 뒀다. 통과하는 가짜 시험을 만드는 대신
*"현재 관측된 동작을 그대로 적는다"* 를 골랐고 이름에 **`[백엔드 결함 기록]`** 을 달았다.
**그 선택이 옳았고, 그래서 지금 결함이 고쳐지자 그 기록이 거짓이 됐다.**

| 파일 | 지금 검사하는 것 | `BE-A` 병합 후 |
|---|---|---|
| `academy-web/src/features/auth/api/realBackend.test.ts:303` | `remaining_attempts` 가 **5회 내내 `4`** | **2회차부터 `3·2·1`** → 실패 |
| 같은 파일 `:309~313` | 5회 실패 뒤 정상 로그인이 **`200`** | **`403 AUTH_ACCOUNT_BLOCKED`** → 실패 |
| `academy-web/src/features/admin/api/realBackend.test.ts:174~180` | (검사는 통과) | **판단 근거 주석이 낡는다** — *"차단 경로가 백엔드 카운터 결함 때문에 막혀 있다"* 가 거짓이 된다 |

⚠ **이것이 `phase-goal-loop.md §5` 의 *"단언이 결함을 고정하고 있지 않은지 보라"* 의 실례다.**
그 규칙은 *"테스트를 옳은 동작 쪽으로 고쳤을 때 실패한다면 그 단언은 결함을 굳히고 있다"* 고 적는데,
**여기서는 구현이 옳은 쪽으로 갔고 단언이 그대로 남아 같은 일이 벌어진다.**

**처리 — 병합 뒤에 `W3` 가 고친다. 조율자가 직접 고치지 않는다.**

1. **`BE-A` → `BE-B` → `W3` 순으로 병합한 뒤**, 병합된 트리에서 고친다.
   ⚠ **지금 고칠 수 없다** — `W3` 작업 폴더에는 `BE-A` 의 수정이 없어 **고친 단언을 검증할 서버가 부재**하다
2. 고칠 것 — ①`[백엔드 결함 기록]` 이름과 주석을 걷어내고 **사양(`§1.4`) 동작으로** 단언을 바꾼다
   (`4·3·2·1` → 5회차 `403 AUTH_ACCOUNT_BLOCKED`) ②`admin` 쪽 판단 근거 주석을 갱신한다
3. ⚠ **`admin` 쪽 시험 자체는 건드리지 마라** — 시드 계정 `driverBlocked` 로 분기하는 구조는
   **재차단 수단이 부재해서** 그렇게 짠 것이고(마르는 자원 회피) 그 판단은 여전히 유효하다.
   **낡은 것은 "왜 그렇게 했는가" 뿐이다**
4. 고친 뒤 **연속 네 번**을 다시 돌린다 — 차단이 실제로 걸리면 그 계정은 되돌아오지 않으므로
   `Date.now()` 기반 신규 계정 생성이 회차마다 실제로 새 계정을 만드는지 확인한다

⚠ **`W3` 를 이 작업 전에 정리하지 마라** — 그 파일의 맥락을 가진 유일한 담당이다.

## 5.7 ✅ `R4` 병합 + 단독 전체 실행 — 6종 전부 실패 0 · 건너뜀 0 (2026-09-17 조율자)

세 갈래(`r4-photo` · `r4-assert` · `r4-null`)를 `--no-ff` 로 병합. **파일이 한 건도 겹치지 않아
충돌 0건**이고, 병합 직후 `compileJava compileTestJava` 를 먼저 돌려 통과를 확인한 뒤 측정했다.

| 병합 커밋 | 갈래 | 내용 |
|---|---|---|
| `0d5f46a6` | `r4-photo` | `StudentRow` 사진 표시 — 사진·명단 육안 대조 복구 |
| `6c4102ab` | `r4-assert` | 학부모 앱 개인정보 보호 3항 단언 고정 + 시험의 좌석 DB 의존 제거 |
| `ea12d070` | `r4-null` | 사양이 `●` 인데 서버가 `null` 을 보내던 필드 2건 수정 |

### 실측 — 다른 시험이 하나도 돌지 않는 상태에서

| 대상 | 검사 | 실패 | 건너뜀 | 착수 전 | 대사 |
|---|:-:|:-:|:-:|:-:|---|
| 백엔드 | **1,305**(222클래스) | 0 | 0 | 1,303 | `r4-null` 신규 2 · 전용 DB `schoolbus_verify` · `3m 46s` |
| 관계자 웹 | **248**(69파일) | 0 | 0 | 248 | 변동 부재 · `52.6s` |
| 학부모 앱 | **116** | 0 | 0 | 113 | `r4-assert` 신규 3 |
| 매니저 앱 | **118** | 0 | 0 | 118 | 변동 부재 |
| `baraeda_core` | **48** | 0 | 0 | 40 | ⚠ 아래 — 계수 규약 차이가 아니었다 |
| `baraeda_ui` | **77** | 0 | 0 | 71 | `r4-photo` 신규 6 |

**프론트 4종은 전용 서버 `:8180`(DB `schoolbus_r4run`)에 붙여 돌렸다** —
`--dart-define=API_BASE_URL=http://localhost:8180/api/v1`,
학부모 앱은 `--dart-define=FIXTURE_DB=schoolbus_r4run` 을 함께 줬다.

⚠ **`R4` 가 내내 빼놓았던 `real_backend` 태그 묶음(매니저 24 · 학부모 27)이 이 회차에서 처음 돌았다.**
`r4-photo` 갈래가 `--exclude-tags real_backend` 로 측정했다고 자진 신고한 그 묶음이다.

### ⚖ Ruling 287 — `baraeda_core` 48 대 40 은 **계수 규약 차이가 아니라 실행 범위 차이였다**

`FE-R3` 회차가 *"줄어든 것은 검사가 아니라 계수 방식으로 보인다"* 로 적고 **미확인으로 남긴** 항목을
닫는다. **틀린 추정이었다.**

원인은 **실서버 계약 시험 3파일이 그 회차에서 실행되지 않은 것**이다.

| 파일 | `test()` |
|---|:-:|
| `test/integration/real_backend_auth_test.dart` | 4 |
| `test/integration/baraeda_websocket_client_connect_test.dart` | 3 |
| `test/integration/ws_forbidden_subscribe_close_code_test.dart` | 1 |
| **합** | **8** |

**48 − 40 = 8 로 정확히 대사된다.**

⚠ **조율자가 같은 실수를 이 회차에서 한 번 재현했다** — `baraeda_core` 를 `API_BASE_URL` 없이
돌려 **3파일이 적재 단계에서 실패**했다(`+40 -3`). 그 실패 문면이 원인을 그대로 알려 줬다.
⇒ **`requireRealBackendBaseUrl()` 이 던지도록 만든 구조가 값을 했다** — 인자 누락이
조용한 오염이 아니라 **적재 실패**로 드러났고, 그 덕에 미확인 이월이 닫혔다.
`parallel-agents-git.md §13.2` 가 *"문구가 아니라 구조로 막아야 한다"* 고 적은 그 자리의 실례다.

⚠ **`baraeda_core` 도 `--dart-define=API_BASE_URL` 이 필수다** — 앱 2종만 그런 것이 아니다.
`§5.6` 이하의 표준 실행 명령에 이 패키지가 빠져 있었다.

## 5.8 ✅ `R31` 목표 표 — 백엔드 전체 검사 반영 (2026-09-26 계획 · **2026-09-26 완료** · 메인 `bc828bc2` · 결과 `§5.8.8`)

**백엔드 전체 검사(2026-09-25~26)의 1차·2차·W12 수정이 바꾼 계약을 프론트 3제품에 맞추는 라운드.** 이 절은 계획만 고정 — 코드 미수정.

| 입력 | 위치 |
|---|---|
| 사양 판정 | `docs/archive/rounds/be-rounds-r22-r41.md §8.45`~`§8.51`(`Ruling 327`~`353`) |
| 사양 변경 | `git diff 1d34c7d1^ 67f2c8e5 -- docs/` + 판정 반영 커밋 `21e10e26` · `d66b814e`(기준점 앞이라 따로 읽음) |
| 코드 쪽 계약 | 병합 커밋의 DTO·컨트롤러·`ErrorCode`·WS 리스너 diff · 추적 원장 `backend/report/2026-09-25-백엔드-전체-검사.md` · 갈래 보고서 `backend/report/review-2026-09-25/FIX-*.md`(둘 다 git 추적 밖 — 메인 저장소) |

- **라운드 이름 `R31`** — `R5`~`R30` 이 이미 프론트 라운드 번호(`docs/archive/rounds/be-rounds-r5-r14.md §8.5` 이하). `FE-R5` 로 두면 2026-09-17 `R5` 와 이름 충돌
- ⚠ **모든 행은 `67f2c8e5` 의 코드·사양에서 직접 확인한 결과.** `docs/archive/rounds/be-rounds-r22-r41.md §8.45` "프론트 영향" 표 · 작업 지시서 · 갈래 보고서는 인용이라 출발점으로만 씀. 이미 반영된 것은 근거와 함께 `docs/archive/rounds/fe-rounds-r3-r44.md §5.8.1.2` 에서 닫음

### 5.8.1 변경 목록

적용 순서 — **서버 먼저**(서버가 이미 바뀜 · 프론트가 뒤처짐) · **앱 먼저**(프론트가 두 형태를 다 받게 한 뒤 서버) · **한 단위**(같은 회차에 함께).

#### 5.8.1.1 할 일 — 프론트 18건 + 서버 1건

| # | 출처 | 바뀐 계약 | 지금 코드 — 확인 근거 | 프론트가 할 일 | 적용 순서 | RED |
|:-:|---|---|---|---|---|:-:|
| **M1** | BR-016 · `Ruling 341` · `91adb811` | `API_SPEC §4.2` `students[].status` 에 **`absent` 가 `change=removed` 행으로 등장** — 버스 간 이동으로 빠진 학생을 명단에 빨강으로 남김(`§9.4`) | `RiderStatus` 에 `absent` 부재 — `run_enums.dart:48~49` 주석이 "명단에서 개인 행 자체가 빠지는 값" 이라는 **낡은 전제** · `roster_response.dart:97~98` 이 모르는 값을 `waiting` 으로 대체 → **다른 버스로 옮긴 학생에게 [탑승]·[미승차] 버튼 노출**(누르면 `409 RIDER_TRANSITION_NOT_ALLOWED`). `roster_screen.dart:396` 은 `added` 표기만 | `absent` 값 추가 · `removed` 행은 `BaraedaBadgeTone.removed` "금일 삭제" · 조작 버튼 부재 · 집계 제외. 운행 화면(`drive_mode`)의 명단 소비처도 같은 판정 | 서버 먼저(병합 완료) | ✅ |
| **M2** | BR-054 · `Ruling 332` | `§4.6` 응답 `no_show_case.case_id` — **서버는 아직 숫자**(`RiderStatusUpdateResponse.java:30` `Long caseId`) | `rider_update_result.dart:27` → `NoShowCase.fromJson`(`roster_response.dart:51` `case_id as String`) — **[미승차] 응답 파싱이 지금 실패하는 상태.** 서버 기록은 성공 · 앱은 형변환 예외라 `roster_screen.dart:109` 의 `on Failure` 에 안 잡힘. 실서버 시험(`real_backend_manager_endpoints_test.dart`)은 시드 `no_show` 행만 써서 이 응답을 안 읽음 | `case_id` 를 `asIdString` 으로 흡수 — 같은 파일 `rider_id`(`rider_update_result.dart:19`)와 같은 방식 | **앱 먼저** — B1 전에 | ✅ |
| **M3** | `Ruling 345` · `4e250da2` | `§4.6` `409 RIDER_TRANSITION_NOT_ALLOWED` 신설 — 같은 상태 재요청 포함 | 서버 문구 "허용되지 않는 상태 전이입니다"(`ErrorCode.java:265`)가 그대로 표시(`failure_messages.dart:7~29` 분기 부재) · 실패 시 명단 재조회 부재(`roster_screen.dart:109~111`, 재조회는 성공 갈래 `:102~103` 에만) → 낡은 화면이 낡은 채 남음 | 문구 "이미 처리된 학생입니다 — 명단을 새로 불러왔습니다" + 이 코드면 `rosterProvider` 무효화 | 서버 먼저 | ✅ |
| **M4** | `Ruling 340` · `400d0f19` | `§4.4` `409 RUN_CANCELED` · 취소 회차는 `§4.1` 목록에서 제외 | 서버 문구 "취소된 회차입니다"(`ErrorCode.java:236`) 표시 — 뜻은 맞음. 실패 갈래에 목록 재조회 부재(`drive_mode_screen.dart:105~107`) → 취소된 카드가 남음 | `failure_messages.dart` 등록 + 이 코드면 `todayRunsProvider` 무효화 | 서버 먼저 | ✅ |
| **M5** | `Ruling 329` · `UF-X-04` | 전화번호 복구 `503 RECOVERY_UNAVAILABLE` — 학부모·학생·매니저는 **학원 관계자 경유**(`§5.22`) | 매니저 앱에 복구 진입점·안내 부재(`features/auth/presentation/` 에 `recover` 0건). 학부모 앱·웹은 반영 완료(`§5.8.1.2`) | 로그인 화면에 "비밀번호를 잊으면 학원에 초기화를 요청" 안내 한 줄. **전화번호 복구 화면은 만들지 않음**(SMS 연동 전 `503`) | 서버 먼저 | 선택 |
| **C1** | BR-083 · `534abb76` | `§7` — 세션은 연결한 access 토큰 만료 시각에 `ERROR` 프레임 `TOKEN_EXPIRED` 로 닫힘 · 재발급(`§2.6`) 후 재연결이 계약 | `baraeda_websocket_client.dart:22~29` 자바독이 "서버가 만료를 이유로 세션을 능동적으로 끊지 않는다" — **낡은 전제.** 재연결마다 저장 토큰을 다시 읽기만 함(`:118`) · `onStompError`(`:136`)는 `FORBIDDEN` 만 판정. access 수명 15분(`backend/src/main/resources/application.yml:243`) → **REST 호출이 없는 화면(학부모 앱 실시간 지도 — 첫 진입 REST 1회뿐)은 15분 뒤 같은 만료 토큰으로 6회 재연결 후 `gaveUp` → "연결 끊김"** | `TOKEN_EXPIRED` 프레임이면 재발급을 먼저 부르고 재연결 · 재발급 실패(refresh 만료·퇴사)면 로그인 만료로 넘김 · 자바독 정정 | 서버 먼저(병합 완료) | ✅ |
| **W1** | BR-054 · `Ruling 332` | 응답 본문 식별자 전부 JSON 문자열(`§1.1`) — **서버 미적용**(원장 `미착수`) | 식별자 `number` 선언 **170줄** · 식별자 `Number()` **17곳**(계수 명령·목록 `§5.8.1.3`). 지도 선택이 `Number(markerId)` → `Number.isInteger` → `run.runId ===`(`DashboardPage.tsx:225~226` · `MonitoringPage.tsx:240~241` · `TodayRunPage.tsx:138·184`) — 서버만 문자열로 바꾸면 **버스·정차지를 눌러도 선택 안 됨**(오류 없이) | raw 타입을 `string \| number` 로 받고 **매핑 함수에서 `asIdString`**(`shared/lib/ws/asIdString.ts`, 이미 존재) · 도메인 타입 `string` · 식별자 `Number()` 제거. 경로 조립은 그대로 | **한 단위 — 안에서 웹 흡수 먼저, 서버(B1) 뒤.** 흡수가 끝나면 서버 전환 순간에 깨지는 창이 없음 | ✅ |
| **W2** | BR-083 · `534abb76` | C1 과 같은 계약 | `academyRealtimeClient.ts:142` 도 재연결 때 저장 토큰을 읽기만 하고 `:160` `handleStompError` 는 `FORBIDDEN` 만. 대시보드·관제는 REST 7초 폴링(`DashboardPage.tsx:47` · `MonitoringPage.tsx:43`)이 토큰을 갱신해 대개 회복 — **재발급 전의 재연결 시도는 만료 토큰으로 실패하고, 폴링 없는 화면이 구독하면 회복 수단 부재** | `TOKEN_EXPIRED` 면 `refreshAccessToken()`(`http/refreshClient.ts:35` — 동시 재발급 1회로 묶는 구조 이미 존재) 후 재연결 | 서버 먼저 | ✅ |
| **W3** | BR-157 · `f859ba68` | `§7` 학원·관리자 채널에 `emergency_canceled`(`emergency_id` · `bus_no` · `canceled_at`) 등재 — 서버는 이미 발행(`exception/command/EmergencyBroadcastListener.java:40`) | `wsEventType.ts` 알려진 종류에 부재 → 무시. `emergency_raised` 가 띄운 "비상 상황 발생" 알림(`DashboardPage.tsx:265~268` · `MonitoringPage.tsx:279`)이 **기사가 1분 안에 취소해도 남음** | 종류 추가 + 같은 알림을 "비상 알림 취소 — {호차}" 로 교체(`§4.14` "취소 사실도 수신자에게 통지") | 서버 먼저 | ✅ |
| **W4** | BR-047 · `Ruling 342` | `§5.10` 회차 응답 · `§6.8` 관제 응답에 `consecutive_failures` | 두 raw 타입에 부재(`schedule/api/index.ts:38` `RawRun` · `admin/api/runsLive.ts`) — 표시 0 | 일일 회차 목록(`RunDayList`) · `(admin)` 강제 확정 화면(`ForceConfirmPage` — `§6.8` 사용)에 "확정 N회 연속 실패"(0 이면 부재). `UF-O-07` 진입 재료 | 서버 먼저 | ✅ |
| **W5** | BR-116 · `Ruling 343` | `PATCH /staff/buses/{id}` 응답 `warnings[]`(`CAPACITY_BELOW_ASSIGNED` · `run_id` · `assigned_count` · `student_capacity`) — 저장은 성공, 경고만 | `bus/api/index.ts:56~57` 이 `RawBus` 로만 읽어 `warnings` 버림 | 수정 성공 뒤 경고 표시 — 배치 경고와 같은 `AlertBanner` 형태(`ManagerAssignmentDialog.tsx:114~118`) | 서버 먼저 | ✅ |
| **W6** | `Ruling 328` · `UF-O-03` | `§6.10` 목록에 `role` · `status_before_block` · 해제 후 상태 = 차단 직전 상태 | `admin/api/blockedAccounts.ts:4~12` raw 타입에 두 필드 부재 — 서버는 발행(`account/dto/BlockedAccountResponse.java:18~19`). `UF-O-03` 이 "목록에 역할·차단 직전 상태 표시" 를 요구 | 열 2개 추가 — 해제 결과("해제 후: 승인 대기")를 누르기 전에 보임 | 서버 먼저 | ✅ |
| **W7** | `Ruling 327` · `86927828` | `§4.3`·`§5.19` `stops[]` 마지막에 학원 항목(`is_destination=true`, 등원만) | `map/routeDisplayState.ts:21` 이 `is_destination` 을 모름 → 학원 자리에 **정차지 마커가 하나 더 찍힘** — 끝점 `destination` 마커(`:59`)와 겹침(`FIX-H.md §2` 관측). 동작 결함 부재 · 표시 중복 | 정차지 마커 산출에서 `is_destination` 항목 제외 | 서버 먼저 | ✅ |
| **W8** | `Ruling 339` · BR-022·023 | `§5.13` 배치 중이면 **역할 변경도** `409 MANAGER_ASSIGNED` | 서버 문구가 삭제 전용 "회차에 배치된 매니저는 삭제할 수 없습니다"(`ErrorCode.java:117`) — `ManagerForm.tsx:46` 이 그대로 표시 → 역할 변경 거부에 "삭제" 문구 | 수정 화면에서 이 코드면 "배치 중인 매니저는 역할을 바꿀 수 없습니다 — 배치를 먼저 해제" | 서버 먼저 | ✅ |
| **W9** | `Ruling 338` · BR-052 | `§5.9` 운행 중 회차가 서는 승하차지의 **좌표** 수정 `403 CHANGE_WINDOW_CLOSED`(이름은 허용) | `RouteStopsPanel.tsx:217` 이 서버 문구 "지금은 변경할 수 없는 시간입니다"(`ErrorCode.java:204`) 표시 — 거부 이유 불명 | 이 화면에서 이 코드면 "운행 중인 회차가 서는 승하차지라 위치를 바꿀 수 없습니다 — 운행이 끝난 뒤 다시" | 서버 먼저 | ✅ |
| **W10** | `35a91ccf`(BR-114) · `Ruling 329`·`340`·`345` | `API_SPEC §8` 사전 **76개** | `shared/lib/http/apiErrorCodes.ts` **58개 — 18개 누락**: `ACADEMY_COORDINATES_MISSING` · `ADDRESS_VERIFICATION_UNAVAILABLE` · `DELAY_DUPLICATE` · `DUPLICATE_NOTIFICATION` · `DUPLICATE_WEEKLY_ADDRESS` · `ENDPOINT_NOT_FOUND` · `INTERNAL_ERROR` · `METHOD_NOT_ALLOWED` · `RECOVERY_UNAVAILABLE` · `RIDER_TRANSITION_NOT_ALLOWED` · `ROUTE_NOT_CONFIGURED_FOR_RUN` · `RUN_CANCELED` · `RUN_NOT_DUE` · `RUN_NOT_IDLE` · `STOP_ALREADY_DEPARTED` · `STUDENT_NOT_IN_RUN` · `TRANSFER_ALREADY_STAGED` · `WAYPOINT_NOT_FOUND`. 계수 — `§8` 표 첫 열(`awk '/^## 8\./,/^## 9\./'`) ↔ 파일 문자열 `comm`. 런타임은 `ApiError.code` 가 `string` 이라 동작 영향 부재 | 18개 추가 + **`§8` 과 이 파일을 대조하는 시험 1개** — 파일 머리 주석의 "정본과 대조해 갱신" 을 사람 손에 맡기지 않음 | 서버 먼저 | ✅ |
| **P1** | `Ruling 208` · `Ruling 349` | 유실 판정 = 마지막 수신 후 2분(`§3.11`) · 과부하 때 `position` 방송은 버려질 수 있음 | 2분 판정이 **첫 진입 REST 응답에만** 걸림(`live_map_screen.dart:194~207`). WS 로 좌표를 받던 중 끊기면 마지막 좌표와 "현재 위치 · HH:mm:ss 기준"(`:371`)이 그대로 남고 "마지막 확인 위치 · N분 전" 으로 바뀌지 않음. 몇 초 공백을 오류로 다루지 않는 점은 맞음 | 표시 중인 좌표의 `received_at` 이 2분을 넘으면 유실 문구로 전환 — 시각은 `clockProvider` 로 주입 | — (앱 단독) | ✅ |
| **P2** | BR-024 · `FEATURE_SPEC` 정책 상수 "자녀 연결 코드 입력 — 보호자당 10분에 5회" | `§3.4` 시도 상한 · 중복 코드 거부가 `403 LINK_CODE_INVALID` 에 합류(응답으로 구별하지 않음) | `child_link_screen.dart:93` "코드가 올바르지 않거나 만료됐습니다" 뿐 — 상한에 걸린 학부모가 새 코드를 받아도 최대 10분 실패(`FIX-B.md §2`) | 오류 아래 고정 안내 "여러 번 틀리면 10분 동안 입력이 막힙니다 · 계속 안 되면 자녀 앱에서 코드를 다시 발급". 응답을 가르지 않아 코드 실재 노출 부재 | 서버 먼저 | 선택 |
| **B1** | BR-054 · `Ruling 332` | 서버 응답 식별자 문자열 전환 | 원장 `미착수` · `StudentRunsResponse.java:20` 등 `Long` — 응답·뷰·봉투 레코드 **66파일 · 약 104필드**(`grep -rE '\b(Long\|long) [a-zA-Z]*(Id\|id)\b'` · `*Response*`·`*View*`·`*Payload*`·`*Envelope*`) | (백엔드 갈래) `WebSocketEnvelope.runId` 자바독 정정 포함(`FIX-F.md §2`) · `§4.6` `rider_id`·`case_id` 포함 | **W1 병합 뒤** | 백엔드 |

#### 5.8.1.2 이미 반영 — 닫음

| 출처 | 계약 | 근거 |
|---|---|---|
| BR-111 · `Ruling 335` | 학생 채널 `run_started`·`run_ended` 인원수 제외 — **앱 먼저 → 서버 순서 불요**(양쪽 병합 완료) | 공유 패키지 `ws_payloads.dart:126·149` `as int?` · 학부모 앱 표시 제거 · 서버 학생 채널 전용 payload(`run/command/RunStartedBroadcastListener.java:48~50·61`) — 전부 병합 `7a991bb0`. 매니저 앱은 REST 모델만(`start_run_result.dart:17` `int?`) |
| BR-005 · `Ruling 329` | `503 RECOVERY_UNAVAILABLE` · `§5.22` 관계자 초기화 | 학부모 앱 `account_recovery_screen.dart:59` · 웹 `LoginForm.tsx:127~129` · 웹 초기화 `AccountPasswordResetDialog`(학생 `StudentForm.tsx:292` · 매니저 `ManagerList.tsx:192`) · `auth/api/passwordReset.ts` — 병합 `6dab9f26`. 매니저 앱 안내만 M5 |
| BR-006 · BR-068 · `Ruling 334`·`336` | ③구간 `waiting` 한정 · ②구간 켜기 `403 CHANGE_WINDOW_CLOSED` | 학부모 앱 `run_card.dart:75~78` 이 켜기·끄기로 문구 분기 — 병합 `8717550f`. 매니저 앱은 `rider_changed` 수신 시 명단 재조회뿐이라 영향 부재 |
| BR-055 · BR-056 | `§3.10` 기사·동승자 `◐` · `§3.11` 유실 시 좌표 부재 | `route_detail_screen` "미배치" · 전화 버튼 생략(`1bf434ab`) · 유실 문구는 좌표 부재로 판정(`live_map_screen.dart:194~207`) |
| BR-082 · BR-081 | `guardian_phone` `○` · `§4.2` `no_show_case` | 매니저 `roster_response.dart:93` `as String?` · `no_show_case` 파싱 — 병합 `68ca788d`. 웹 `run/api/roster.ts:9` `string \| null` |
| BR-154 · `Ruling 344` | `§4.11` `change_ids[]` 삭제 | 매니저 `roster_api.dart:63~66` 본문 없이 호출 — 병합 `df1c1186`(`FIX-I`) |
| BR-002 · `Ruling 327` | `§4.2`·`§4.5` `stop_id = run_stop.id` · 학원 항목 | 매니저 앱은 명단 `stop_id` 를 그대로 돌려보냄 — 앱 수정 없이 학원 도착 버튼 생성(`FIX-H.md §4` `drive_mode_destination_test.dart` 12/12). `stop_arrived` 소비처 3곳(학부모 `live_map_providers.dart:180` · 웹 `DashboardPage.tsx:259` · `MonitoringPage.tsx:271`)은 id 비교 없이 이름 표시·재조회만. 매니저 앱 "도착지" 표기는 선택 보완 — 이 라운드에서 제외 |
| BR-033 | 보호 경로 401 본문 `TOKEN_EXPIRED` | 웹 `httpClient.ts:95~97` 이 이 코드로 재발급 — 서버 수정(`7a991bb0`)으로 동작 시작. Flutter `ApiClient` 는 상태 코드만 봐 영향 부재 |
| BR-156 · BR-155 | 본문 없는 삭제 `204` · `§5.4` 배열 | 웹 `httpClient.ts` 가 `204` 처리 · `roster.ts` 가 배열 — 문서만 정정 |
| BR-051 · `Ruling 325` | `§5.15` `preview_token` 필수 | **해당 없음** — 웹의 경유 지점 호출부 부재(`Ruling 325` 로 편성 화면에서 뺌 · `grep waypoints src` → 주석 1건). 화면을 되살릴 때 미리보기 응답의 `preview_token` 을 돌려보낼 것 |
| BR-109 | `§4.14` 발신 좌표 `lat`·`lng` | 매니저 `emergency_raise_request.dart:31~32` 이미 전송 |
| BR-119 | `MANAGER_DOUBLE_BOOKED` 구간 판정 | 웹은 서버 경고 문구를 그대로 표시(`ManagerAssignmentDialog.tsx:69`) — 서버 문구 갱신으로 충족 |
| BR-167 (W12) | Redis 장애 때 위치 조회가 `run_position` 최신 행으로 대체 | 서버가 대체 행에도 2분 판정(`StudentBusPositionQueryService.java:86`) — 오래된 행은 유실로 나감 · `current_stop_name` 은 비어 나가고 앱은 `null` 허용. 학부모 앱 표시 시각은 `received_at`(`live_map_screen.dart:371`) · 웹은 7초 폴링의 `last_seen_at` |
| `Ruling 350` (W12) | 화면용 지도 칸 1개 — 칸이 없으면 직선 근사 | 웹 `routeDisplayState` 가 `fallback_used` 로 "근사 경로" 안내 이미 표시 |
| `§1.7` 키 충돌 `422` · `§2.2` 72바이트 · `§3.7` 14건 · `§4.12` 속도·방향 범위 · `§4.14` 재전송 `200` | 입력 검증 강화 | 앱이 만들 수 없는 입력(멱등키는 동작마다 새로 생성 · 요일표 7×2 고정) 또는 서버 문구 표시로 충분. `§4.12` — 매니저 앱 실 GPS 소스가 아직 부재(`core/location/position_source.dart` `UnavailablePositionSource` 하나). **붙일 때 iOS 의 음수 속도·방향(측정 불가 표시)을 빼고 보낼 것** |

#### 5.8.1.3 W1 이 없앨 식별자 `Number()` 17곳 — 2026-09-26 계수

`grep -rn 'Number(' src --include=*.ts --include=*.tsx`(시험 제외 **30곳**) 중 식별자만. 나머지 13곳은 좌표·정원·분·인원수.

- `app/(staff)/change-approval/[id]/page.tsx:8` · `app/(staff)/route/[id]/page.tsx:8`
- `busId` — `schedule/components/ScheduleForm.tsx:127` · `RunAddForm.tsx:99` · `route/components/RouteForm.tsx:107`
- `admin/components/MonitoringPage.tsx:240·374` · `AuditLogPage.tsx:43·44` · `ForceConfirmPage.tsx:100`
- `run/components/ManagerAssignmentDialog.tsx:65·66` · `TodayRunPage.tsx:138·184` · `DashboardPage.tsx:225` · `ForcedAddDialog.tsx:56`
- `approval/components/SignupDecideDialog.tsx:58`

식별자 `number` 선언 170줄의 계수 — `grep -rnE '\b[a-zA-Z]*(Id|_id|id)\??: number' src --include=*.ts --include=*.tsx | grep -v '\.test\.'`(raw 타입 · 도메인 타입 · 함수 인자 포함). 이름별 상위 — `id` 33 · `runId` 28 · `stopId` 10 · `run_id` 10 · `accountId` 8.

### 5.8.2 막힌 것

| 무엇 | 막는 것 | 풀리면 할 일 |
|---|---|---|
| FCM 실토큰 등록(`Ruling 331` · `API_SPEC §2.11`) | **Firebase 프로젝트 · 서비스 계정 키 · 앱 설정 파일(`google-services.json` · `GoogleService-Info.plist`) · 웹 푸시 키 — 사용자 작업.** 프론트 3종 어디에도 Firebase SDK 부재(`pubspec.yaml`·`package.json` 0건). 지금은 UUID 를 단말 토큰으로 등록(`device_registration_panel.dart:27`) → 서버가 FCM 거부를 받아 행 정리 — 기능 손상 부재 · 푸시 미도착 | 앱 2종·웹 FCM SDK · 실토큰 등록 · 토큰 갱신 시 재등록. **같은 단위에 `§2.7` 로그아웃 `device_id` 동봉** — 지금 `auth_api.dart:91~101` 은 `refresh_token` 만. 키 없이도 선행 가능하나 효과는 실발송 뒤라 함께 묶음 |
| 서버 식별자 문자열 전환(B1) | 백엔드 갈래 필요 — **선행 아님, W1 뒤** | `§5.8.4` 배정대로 |
| `§5.5` 결정된 승인 건의 전 기간 조회(BR-075 미수정분) | 사양 판정 대기 — 페이징 봉투를 넣으면 응답 모양 변경(`FIX-C.md §2`) | 판정 뒤 웹 `approval/api/changeApprovals.ts` 동반 |
| `§6.9` `guardian_phone` `●` 인데 서버가 `null` 가능(`FIX-E.md §2`) | 사양·서버 판정 | 웹 `RunRosterDialog` 는 이 필드를 그리지 않아 화면 영향 부재 — 판정만 |

### 5.8.3 LOGIN · OBS2 병합 뒤 확인 항목

두 작업 창의 변경은 아직 main 에 부재 — 의도는 작업 지시서 `backend/report/review-2026-09-25/BRIEF-LOGIN.md` · `BRIEF-OBS2.md` 로 읽음.

#### 5.8.3.1 LOGIN — 로그인 응답 계약 불변 확인

지시서 불변식상 계약은 그대로 — `401 INVALID_CREDENTIALS` + `details.remaining_attempts` 4·3·2·1 → 5회째 `403 AUTH_ACCOUNT_BLOCKED` · 차단 계정은 비밀번호와 무관하게 `403` · 퇴사 관계자 거부는 대조 통과 뒤. **계약이 바뀌었으면 이 라운드의 첫 항목으로 올림.**

접점 전수 — `grep -rnE "remaining_attempts|remainingAttempts|AUTH_ACCOUNT_BLOCKED|INVALID_CREDENTIALS|AUTH_STAFF_INACTIVE|auth/login|blocked-accounts|/unblock" apps packages`(83줄 · 28파일).

| 갈래 | 운영 코드 | 실서버 계약 시험 |
|---|---|---|
| 웹 | `auth/components/LoginForm.tsx:39~47·73~74`(잔여 횟수 · 차단 · 퇴사 분기) · `admin/components/UnblockConfirmDialog.tsx` · `admin/api/blockedAccounts.ts` | `features/auth/api/realBackend.test.ts:266~319`(실패 4회 `remaining_attempts` 4·3·2·1 → 5회째 `403` → 맞는 비밀번호도 `403`) · `features/admin/api/realBackend.test.ts:188~215`(차단 재현 → 해제 → 로그인) · 로그인 도우미 `shared/testing/rawRestLogin.ts` · `realBackendReset.ts` |
| 학부모 | `features/auth/presentation/login_screen.dart:94~99` · `blocked_screen.dart` · `settings/presentation/password_change_screen.dart`(`§2.8` 의 `401 INVALID_CREDENTIALS`) | `test/support/real_backend_target.dart:80` — 로그인 도우미, 실서버 파일 전부가 지남 |
| 매니저 | `features/auth/presentation/login_screen.dart:95~100` · `blocked_screen.dart` | `test/support/real_backend_target.dart:64·115` |
| 공유 패키지 | `auth/auth_api.dart` · `auth/account_status.dart` | `test/integration/real_backend_auth_test.dart:204~229`(목표 9 — `driverBlocked` 맞는 비밀번호 → `403`) · `test/support/driver_blocked_seed_reset.dart:30~48` |

- ⚠ `driver_blocked_seed_reset.dart:30` 주석이 "`assertNotBlocked()` 가 비밀번호 대조보다 **먼저**" 를 근거로 적음 — LOGIN 이 대조를 잠금 밖으로 옮기면 **순서 서술이 낡을 가능성.** 탐침은 맞는 비밀번호라 결과(`403`)는 불변식 2 로 유지 — 주석만 확인
- 퇴사 관계자(`AUTH_STAFF_INACTIVE`)는 웹 단위 시험(`LoginForm.test.tsx`)뿐 — 시드(`V2__seed_data.sql`)에 퇴사 관계자 계정이 부재해 프론트 실서버 확인 수단 부재. 백엔드 불변식 4 시험 소관

#### 5.8.3.2 OBS2 · W12 — WebSocket 계약 불변 확인

- OBS2 의 `WebSocketConfig` 송신 실행기 지표 등록은 동작 불변이 의도. 이미 병합된 후속(`4d82467c`)이 세션 송신 버퍼 64KB · 송신 시간 10초를 명시 — 넘는 세션은 서버가 닫고 클라이언트는 재연결(웹 `wsBackoffPolicy.ts` · 공유 패키지 `ws_backoff_policy.dart` — 1·2·4·8·16·30초 6회)
- `docker-compose.observe.yml`(OBS2 가 삭제)을 가리키는 **프론트 문서·스크립트 0건** — `grep -rn "docker-compose.observe\|prometheus-load" .` 결과는 `infra/` · `backend/load/r1_round.sh` · `docs/backend/LOAD_TESTING.md` 뿐(OBS2 범위). 정정 항목 부재
- 위치 방송 누락(`Ruling 349`) — 몇 초 공백을 오류로 다루는 화면 부재(웹은 마커 갱신만 늦음 · 학부모 앱은 마지막 좌표 유지). **2분 유실 표시는 학부모 앱이 첫 진입에만 판정** → P1
- WS 접점 전수 — `grep -rlE "BaraedaWebSocketClient|AcademyRealtimeClient|createStompClient|/ws\b|wsUrl|stomp" apps packages`(29파일). 실서버 계약 시험 5파일:

| 갈래 | 실서버 계약 시험 |
|---|---|
| 웹 | `src/shared/lib/ws/wsRealBackendAuth.test.ts` |
| 공유 패키지 | `test/integration/baraeda_websocket_client_connect_test.dart` · `ws_forbidden_subscribe_close_code_test.dart` |
| 매니저 | `test/integration/real_backend_manager_channel_test.dart` |
| 학부모 | `test/integration/real_backend_p3_test.dart` |

#### 5.8.3.3 실행 — 병합 뒤 조율자 1회 (전용 서버 · 주소 명시 · 건너뜀 0)

⚠ **주소 인자를 빠뜨리면 적재 단계에서 실패** — `realBackendTarget.ts` · `real_backend_target.dart` 3벌이 던짐(`§5.5` 목표 0). 기본값 8080 은 조율자 시드 서버라 쓰지 않음.

```bash
# 전용 백엔드 — LOGIN·OBS2 병합 main 을 전용 포트·전용 DB 로
cd backend && ./gradlew bootRun --args='--server.port=<전용포트> --spring.datasource.url=jdbc:postgresql://localhost:15432/<전용DB>'

# 웹 — 주소는 호스트까지(/api/v1 은 시험이 붙임)
cd frontend/apps/academy-web && NEXT_PUBLIC_API_BASE_URL=http://localhost:<전용포트> \
  npx vitest run src/features/auth/api/realBackend.test.ts src/features/admin/api/realBackend.test.ts \
  src/shared/lib/ws/wsRealBackendAuth.test.ts

# Flutter 3종 — 주소는 /api/v1 까지
cd frontend/packages/baraeda_core && flutter test --dart-define=API_BASE_URL=http://localhost:<전용포트>/api/v1 \
  test/integration/real_backend_auth_test.dart test/integration/baraeda_websocket_client_connect_test.dart \
  test/integration/ws_forbidden_subscribe_close_code_test.dart --reporter json
cd frontend/apps/manager-app && flutter test --dart-define=API_BASE_URL=http://localhost:<전용포트>/api/v1 \
  test/integration/real_backend_manager_channel_test.dart --reporter json
cd frontend/apps/parent-app && flutter test --dart-define=API_BASE_URL=http://localhost:<전용포트>/api/v1 \
  --dart-define=FIXTURE_DB=<전용DB> test/integration/real_backend_p3_test.dart --reporter json
```

- 판정 — 웹은 vitest 요약의 `skipped` **0**. Flutter 는 `--reporter json` 의 `testDone` 중 `hidden:false` 이고 이름이 `loading `·`(setUpAll)`·`(tearDownAll)` 이 아닌 것만 세어 `skipped:true` **0**(`§5.6` 공통 3)
- ⚠ **계약 시험이 되돌릴 수 없는 상태를 소비함** — 웹 차단 재현은 계정 하나를 5회 실패시키고, 관리자 해제 시험은 `driverBlocked` 를 풀어 공유 패키지 목표 9 를 깸(`driver_blocked_seed_reset.dart` 가 조건부 `/dev/reset` 으로 복구). **같은 DB 로 두 번 돌리기 전에 시드 재구성**
- 백엔드 서버는 확인 직후 내림(`lsof -iTCP:<전용포트>` 빈 결과)

### 5.8.4 갈래 배정

앱·패키지 단위로 가름 — 파일 겹침 0. **동시 3좌석 상한**(`§5.5` — 4좌석은 메모리 부족 실측). 부하 측정 · LOGIN · OBS2 가 끝난 뒤 착수.

| 회차 | 좌석 | 대상 | 항목 | 규모(추정) | 순서 · 겹침 |
|:-:|:-:|---|---|---|---|
| 1 | `W-ID` | `academy-web` 식별자 | W1 | 약 50파일 · 기계적 치환 + 지도 선택 3화면 | `DashboardPage`·`MonitoringPage` 가 회차 2 `W-UI` 와 겹쳐 회차를 가름 |
| 1 | `M` | `manager-app` | M1~M5 | 5건 · 모델 2 · 화면 2 · 문구 | — |
| 1 | `C` | `baraeda_core` | C1 | 1건 · WS 클라이언트 + 재발급 진입점 | 학부모·매니저 앱이 의존 — 병합 뒤 두 앱 단위 시험 재실행 |
| 2 | `B-ID` | `backend` | B1 | 응답 레코드 66파일 · WS 봉투 | **`W-ID` 병합 뒤.** 백엔드 전체 시험은 병합 뒤 조율자 1회(`FIX_COMMON §3`) |
| 2 | `W-UI` | `academy-web` 나머지 | W2~W10 | 9건 | `W-ID` 병합 뒤 |
| 2 | `P` | `parent-app` | P1 · P2 | 2건 | `C` 병합 뒤(실시간 지도가 공유 패키지 클라이언트를 씀) |

- 적용 순서 요약(프론트 18건) — **앱 먼저 2건**(M2 · W1 → 그다음 B1) · **서버 먼저 15건**(서버 병합 완료 · 프론트만 남음 — M1·M3~M5 · C1 · W2~W10 · P2) · **앱 단독 1건**(P1)
- FCM 단위(`§5.8.2`)는 키가 풀린 뒤 별도 회차 — 앱 2종·웹에 걸쳐 회차 1·2 와 같은 파일(`device_registration_panel.dart` · `auth_api.dart`)을 건드림

### 5.8.5 목표 표 — 전항 통과가 완료 조건

- 좌석은 **단위 시험만** 돌림 — 실서버 시험(`test/integration/` · `real_backend*` · `realBackend.test.ts`)은 좌석이 돌리지 않음(`FIX_COMMON §3` · 주소 누락 사고 3회)
- 착수 직후 기준값을 잼 — 마지막 기록은 `docs/archive/rounds/be-rounds-r22-r41.md §8.46`(웹 vitest 351 통과 · 77 건너뜀 · `baraeda_core` 41 · `baraeda_ui` 83 · 학부모 106 · 매니저 115)이고 그 뒤 병합(`FIX-I` 등)으로 달라졌을 가능성
- RED ✅ — `IMPLEMENTATION_PLAN §4.6`(메인) TDD. 고치기 전 실패를 눈으로 보고 실패 문면을 보고서에

| # | 좌석 | 완료 조건 | 검증 |
|:-:|:-:|---|---|
| 1 | `W-ID` | 식별자 `number` 선언 0 | `§5.8.1.3` 의 계수 명령 → **0줄**. raw 타입은 `string \| number` 순서로 적어 이 정규식에 안 걸림 |
| 2 | `W-ID` | 식별자 `Number()` 0 | `§5.8.1.3` 목록 17곳 → 0 |
| 3 | `W-ID` | ✅ **서버가 숫자든 문자열이든 지도 선택이 동작** | 대시보드·관제·금일 운행의 마커 클릭 시험에 **문자열 id 응답** 가짜 데이터 → 지금 코드의 선택 실패를 먼저 봄 → 흡수 뒤 통과. 숫자 id 응답도 통과 |
| 4 | `W-ID` | 정적 분석·시험 | `npx tsc --noEmit` 새 오류 0(기존 1건 `src/app/layout.tsx(13,50) LayoutProps` — `.next` 생성 타입 부재, `FIX-D.md §2` — 소유 밖) · `npm run lint` 0건 · `npx vitest run` 실패 0 |
| 5 | `M` | ✅ M1 | `status: absent` · `change: removed` 학생을 담은 명단 JSON 으로 위젯 시험 — "금일 삭제" 배지 · [탑승]·[미승차] 부재. 지금 코드의 버튼 노출을 먼저 봄 |
| 6 | `M` | ✅ M2 | `case_id: 12`(숫자)인 `§4.6` 응답으로 `RiderUpdateResult.fromJson` — 지금 형변환 예외 → 흡수 뒤 `'12'` |
| 7 | `M` | ✅ M3 · M4 | 가짜 저장소가 `409 RIDER_TRANSITION_NOT_ALLOWED` 를 던지면 문구 + `rosterProvider` 재조회 1회 · `RUN_CANCELED` 면 `todayRunsProvider` 재조회 |
| 8 | `M` | M5 · 정적 분석 · 시험 | 안내 문구 위젯 시험 · `flutter analyze` 새 지적 0 · `flutter test --exclude-tags real_backend` 실패 0 |
| 9 | `C` | ✅ C1 | 가짜 STOMP 가 `ERROR message:TOKEN_EXPIRED` 뒤 소켓을 닫으면 **재발급 콜백 1회 → 새 토큰으로 CONNECT.** 지금 코드는 같은 토큰으로 재연결(실패를 먼저 봄). 재발급 실패면 `gaveUp` 이 아니라 로그인 만료. 본보기 `test/websocket/baraeda_websocket_client_reconnect_test.dart` |
| 10 | `C` | 자바독 정정 · 의존 앱 | `baraeda_websocket_client.dart:22~29` 정정 · `flutter analyze` 새 지적 0 · 학부모·매니저 앱 `flutter test --exclude-tags real_backend` 실패 0 |
| 11 | `B-ID` | ✅ B1 | 응답 JSON 의 `id`·`*_id` 가 전부 문자열임을 **실제 직렬화로** 검사하는 규약 시험 1개 — 새 레코드가 `Long` 으로 새면 실패. 요청은 숫자·문자열 둘 다 수용(`Ruling 332`) 1건씩. `FIX_COMMON §3` 컨트롤러 규약 시험 동반 |
| 12 | `W-UI` | ✅ W2 | `academyRealtimeClient.test.ts` — `TOKEN_EXPIRED` 프레임 → `refreshAccessToken` 1회 → 새 토큰 CONNECT |
| 13 | `W-UI` | ✅ W3 | 대시보드·관제 — `emergency_raised` 뒤 `emergency_canceled` 수신 → 알림이 취소 문구로 |
| 14 | `W-UI` | ✅ W4 · W5 · W6 · W7 | 각 1건 — 연속 실패 N 표시(0 이면 부재) · 정원 경고 표시 · 차단 목록 열 2개 · 학원 항목 정차지 마커 제외 |
| 15 | `W-UI` | ✅ W8 · W9 | 코드별 문구 2건 — `MANAGER_ASSIGNED`(수정 화면) · `CHANGE_WINDOW_CLOSED`(승하차지 저장) |
| 16 | `W-UI` | ✅ W10 | `apiErrorCodes.ts` ↔ `docs/API_SPEC.md §8` 대조 시험 — 지금 18개 누락으로 실패를 먼저 봄 |
| 17 | `W-UI` | 정적 분석·시험 | 4번과 같음 |
| 18 | `P` | ✅ P1 | `clockProvider` 를 움직여 WS 좌표 수신 뒤 **2분** → "마지막 확인 위치 · 2분 전". **1분 59초**는 좌표 유지 |
| 19 | `P` | P2 · 정적 분석 · 시험 | 안내 문구 위젯 시험 · `flutter analyze` 새 지적 0 · `flutter test --exclude-tags real_backend` 실패 0 |
| 20 | 조율자 | **병합 후 단독 전체 실행** — 실패 0 · 건너뜀 0 | 백엔드 · 웹 · 매니저 · 학부모 · `baraeda_core` · `baraeda_ui`. 전용 포트 · 전용 DB · 주소 인자 명시(`§5.8.3.3` 형식) · 동시 실행 좌석 0 |
| 21 | 조율자 | **시드 서버 오염 0** | 회차 시작·종료의 `schoolbus` `emergency_alert` 건수 = 정본 `V2__seed_data.sql` 값 |

### 5.8.6 디자인 킷과 부딪히는 곳 — `§4`

| 항목 | 킷 | 판정 |
|---|---|---|
| M1 | `ui_kits/manager-app/ManagerScreens.jsx:235` `Badge tone="removed"` "금일 삭제" | **일치** — `baraeda_ui` `BaraedaBadgeTone.removed` 그대로 |
| P1 | `ui_kits/parent-app/ParentScreens.jsx:78` "현재 위치 · 대치사거리 · 2분 전" — 성공 갈래만 | `docs/` 기준(`API_SPEC §3.11` · `Ruling 208`) — 유실 갈래는 `§8.1` 규칙대로 |
| W3 · W4 · W5 · W6 | 비상 취소 · 연속 실패 · 정원 경고 · `(admin)` 화면 킷 부재(`§4` 2·4번) | `§8.1` 규칙대로 |
| 나머지 | 문구 · 데이터 매핑 | 해당 없음 |

### 5.8.7 정정 — 이 절을 쓰며 발견한 낡은 문장 (원문 미수정)

| 위치 | 낡은 문장 | 사실 |
|---|---|---|
| 이 문서 `§2` 디렉터리 트리 | `frontend/CONVENTIONS_REACT.md` · `frontend/IMPLEMENTATION_PLAN.md` | 2026-09-20 `docs/frontend/` 로 이동(`CLAUDE.md` 문서 통합 표) |
| 이 문서 `§5` 머리 | `~/.claude/rules/parallel-agents-git.md §0` | 경로 부재 — Skill `parallel-agents` 로 이관  ✅ 2026-09-26 정정 |
| `docs/archive/rounds/be-rounds-r22-r41.md §8.45` 프론트 영향 `332` 행 | "앱 2종은 … 영향 부재" | 서버 전환 **뒤**에는 맞음. **지금은** 매니저 앱 `§4.6` `case_id` 가 숫자를 못 받는 결함 존재(M2) |
| 같은 행 | "숫자 선언 77곳 · `Number()` 변환 9곳" | 계수 명령 부재로 대조 불가 — `§5.8.1.3` 명령 기준 170줄 · 17곳 |
| 같은 표 `335` 행 | "앱 먼저 → 서버" | 양쪽 병합 완료(`7a991bb0`) |
| `docs/archive/rounds/be-rounds-r22-r41.md §8.46` "남은 것" | 프론트 후속 "경유 지점 `preview_token`" | 웹 호출부 부재(`Ruling 325`) — 해당 없음 |
| `baraeda_websocket_client.dart:22~29` 자바독 | 서버가 만료를 이유로 세션을 끊지 않음 | `534abb76` 이후 끊음 — C1 에서 정정 |
| `manager-app/lib/core/run/run_enums.dart:48~49` 주석 | `absent` 는 명단에서 빠짐 | `91adb811` 이후 `removed` 행으로 남음 — M1 에서 정정 |

### 5.8.8 ✅ 결과 — 12갈래 병합 · 전체 실행 1회 + 실패분 재실행으로 0 → 후속 병합 뒤 재실행은 아래 "최종 연동 실행" (2026-09-26 조율자)

Orca Run `run_a57d9b1df2cf` · 작업 창 전부 `claude-sonnet-5[1m]` `high` · 지시서·보고서 `.claude/r31/`(git 추적 밖). 동시 3좌석으로 시작해 사용자 지시(*"왜 에이전트 추가로 안 띄우냐"*)로 5좌석까지 늘림 — 관측 스택을 내린 뒤 메모리 여유 35~43% 유지.

| 병합 | 갈래 | 범위 |
|---|---|---|
| `3687b8a7` | M | M1~M5(목표 5~8) |
| `eb964e3e` | C | C1 — `TokenRefresher` 단일 창구 · 동시 재발급 1회 · 실패 시 `sessionExpired`(목표 9·10) |
| `a9e8d594` | BE-B | Swagger 경로 id 예시 11개 컨트롤러 · `ARCHITECTURE §3.3` 예외 등재(B 이월) |
| `8e555035` | W-ID | W1(목표 1~4) — 선언 170 → 0 · `Number()` 17 → 0 · 지도 선택 `kind` 판정 |
| `a5ab270b` | P | P1 · P2(목표 18·19) + 로그아웃 확인 대화 + WS 세션 만료 → 로그인 |
| `f3480654` | M-B | 매니저 로그아웃 확인 · WS 세션 만료 · **실제 GPS**(`geolocator`, 앞 화면 범위) |
| `2ca9e7a9` | B-ID | B1(목표 11) — 전역 직렬화 모듈 `IdentifierJsonConfig`(`Ruling 357`) |
| `f41e3bca` | P-F | 이벤트 없이 2분 지나도 유실 재표시 · 승인 대기 로그아웃 확인 |
| `9c2ff12d` | BE-B2 | `exception_report` 시드 1행 · `SeedFixtures` 상수 누락 탐지 검사 |
| `81ab80bb` | M-F | 비상 발신 좌표 · 운행 화면 꺼짐 방지(`Ruling 356`) |
| `c61f6af7` | W-UI | W2~W10(목표 12~17) + 웹 `recoverAccount` 실서버 시험 정정 |
| `bc828bc2` | RB-FIX | 실서버 계약 시험 18건을 바뀐 계약에 맞춤(아래) |

**단독 전체 실행 — 다른 시험 0 · `down`+`up` 재구성 뒤 · 백엔드는 전용 DB · 프론트는 전용 서버 `:18131`(DB `r31_contract`)**

| 대상 | 검사 | 실패 | 건너뜀 | 비고 |
|---|:-:|:-:|:-:|---|
| 백엔드 | **1,668**(295클래스) | 0 | 0 | 4m 29s |
| 관계자 웹 | 448(92파일) | 14 → **0** | 0 | 실서버 포함 · 단위만 371 |
| 매니저 앱 | 164 | 3 → **0** | 0 | 실서버 포함 · 단위만 140 |
| 학부모 앱 | 148 | 1 → **0** | 0 | 실서버 포함 · 단위만 120 · `FIXTURE_DB` |
| `baraeda_core` | 57 | 0 | 0 | 실서버 포함 |
| `baraeda_ui` | 83 | 0 | 0 | |

- ⚠ **첫 전체 실행의 실패 18건은 전부 실서버 계약 시험의 낡은 단언** — 응답 식별자 숫자(웹 14 · `Ruling 332`·`357`) · 확정 전 회차 비상 발신(매니저 2 · `409 RUN_NOT_CONFIRMED` BR-109) · 같은 상태 재요청(매니저 1 · `Ruling 345`) · 전화번호 복구(학부모 1 · `503` `Ruling 329`). `/dev/reset` 뒤 실패분만 다시 돌려도 같아 시드 소진이 아님을 확인. **좌석은 실서버 시험을 돌리지 않는 규칙이라 계약을 바꾼 갈래의 여파가 병합 뒤에만 드러난다** — 다음 계약 변경 라운드는 실서버 시험 정정 갈래를 처음부터 목록에 둔다. RB-FIX 뒤 실패했던 파일만 재실행: 웹 7파일 40 · 매니저 17 · 학부모 4 전부 통과(단언 삭제 0 · 방어 시험 2 추가)
- 목표 21(시드 서버 오염 0) — 회차 내내 `:8080` 미기동 · 조율자 시드 DB `schoolbus` 테이블 0 유지(좌석 전부 전용 자원)
- 판정 `Ruling 355`~`357`(`docs/archive/rounds/be-rounds-r22-r41.md §8.53`) · 원장 BR-054 → 수정 `2ca9e7a9`

**남은 것**

| 무엇 | 비고 |
|---|---|
| 실기기 확인 | 위치 권한 문구 · 거부/서비스 꺼짐 안내 · 실제 좌표가 `run_position` 에 쌓이는지 · 운행 중 화면이 안 꺼지는지 · 비상 좌표가 `emergency_alert` 에 찍히는지 · 권한 거부 상태의 비상 발신(좌표 없이 접수) |
| 백그라운드 위치 송신 | `Ruling 356` 으로 범위 밖 — iOS `Always`·`UIBackgroundModes` · Android `ACCESS_BACKGROUND_LOCATION`·포그라운드 서비스 |
| FCM · 배포 · SMS | 사용자 보류(`§5.8.2`) |

**후속 (같은 날 · 사용자 "D 제외하고 진행")** — 판정 대기 2건을 `Ruling 358`·`359`(`docs/archive/rounds/be-rounds-r22-r41.md §8.54`)로 닫고 구현.

| 병합 | 갈래 | 범위 · 실측 |
|---|---|---|
| `50d06a6a` | PF2 | 학부모 지도 2분 타이머 재예약 시험 2건 — 결함 부재(처음부터 통과) · 재예약 가드를 깨면 1건만 실패(음성 대조) · 학부모 122 통과 |
| `45b129d1` | SPEC | `§5.5` 승인 목록 `§1.8` 페이징(`Page` + `Pageable` — DB 에서 자름 · `pending` 마감 임박 순 · 결정 건 최근 결정 순 · `size` 초과 `422` 선례 그대로) + 웹 `Pagination` 부품 재사용 · `§6.9` `guardian_phone` `○`(서버는 원래 `null` — 문서·시험 고정). 병합 트리 — 백엔드 관련 22클래스 124 · 웹 단위 372 · 웹 실서버(`approval`·`admin`) 23 · 전부 실패 0 · 건너뜀 0 · tsc 0 |

**최종 연동 실행 (2026-09-26 23:45 · main `625647fb` · 사용자 "백엔드 전체검사 + 프론트 API 연결 테스트, 화면은 제외")** — 후속 병합(SPEC · PF2 · BE-N · BG · BG2) 뒤 처음으로 **전부를 한 번에** 돌린 판정용 실행. 다른 시험 0 · 백엔드는 전용 DB · 프론트는 전용 서버 `:18131`(DB `r31_contract`, 시드 새로 구성)

| 대상 | 검사 | 실패 | 건너뜀 | 그중 실서버 API 호출 |
|---|:-:|:-:|:-:|---|
| 백엔드 | 1,673(297클래스) | **5** | 0 | — · 실패 5건 전부 외부 원인(아래) |
| 관계자 웹 | 449(92파일) | 0 | 0 | 77(14파일 전부) |
| 매니저 앱 | 182 | 0 | 0 | 26 |
| 학부모 앱 | 150 | 0 | 0 | 28(`FIXTURE_DB`) |
| `baraeda_core` | 57 | 0 | 0 | 9 |
| `baraeda_ui` | 83 | 0 | 0 | — |

- ⚠ **백엔드 실패 5건 = 네이버 `Directions 15` 사용량 한도 초과** — `RunConfirmationServiceLiveTest` 1 · `NaverDirectionsClientLiveTest` 4. 같은 키로 직접 호출하니 `map-direction-15` 가 `{"errorCode":500,"message":"사용량이 한도를 초과했습니다."}`(HTTP 400), 경유지 없는 `map-direction` 은 성공. 20:00 실행에서는 같은 시험이 통과 — 그 사이 코드의 지도 경로는 불변(SPEC·BE-N 은 승인 목록·알림만). **코드 결함이 아니라 외부 한도.** 한도가 풀릴 때까지 **확정 배치가 직선 근사로 떨어진다**(`Ruling 350` 폴백) — NCP 콘솔에서 이용 한도·요금 확인 필요(프론트 계획 `§8.3` 요금 확인 과제와 같은 자리)
- 조율자 시드 서버 오염 0 — 회차 내내 `:8080` 미기동 · `schoolbus` 테이블 0 유지
- 화면(브라우저·시뮬레이터) 확인은 사용자 지시로 제외

## 5.9 ✅ `R32` 목표 표 — UX 점검 반영 · 매니저 앱 운행 화면 지도 (2026-09-30 계획 · **2026-09-30 완료** · 결과 `§5.9.5`)

**2026-09-30 화면 3종 UX 점검(코드 읽기 · 51건)을 고치고, 매니저 앱 운행 화면 가운데의 빈 지도 자리를 채우는 라운드.** 사용자 지시 *"ux 개선 진행 · 오케스트레이터 통해서 실행 · 매니저앱에서 특정 회차 들어갈 때 화면 중앙에 지도 … 비워져 있었어"*. 이 절은 계획만 고정 — 코드 미수정. 범위 판정 `Ruling 365`(`docs/archive/rounds/be-rounds-r22-r41.md §8.60`).

| 입력 | 위치 |
|---|---|
| 점검 결과 | 점검 기준 `0e1d93fc` · 요약 원장은 조율자 기억(`school-bus-ux-audit-2026-09-30`) — 아래 표가 그 전문을 갈래별로 옮긴 것 |
| 조율자 재확인 | 매니저 비상 신고 진입 부재(`emergency_screen.dart` 주석 "죽은 라우트") · 학부모 일정 진입이 배지 하나 · 백엔드가 학부모·기사의 웹 로그인을 역할로 막지 않음(토큰 발급 실측) |

- ⚠ **표의 줄 번호는 `0e1d93fc` 기준 인용.** 좌석이 착수 때 심볼 이름으로 다시 찾는다
- 심각도 — 높음 = 막히거나 되돌릴 수 없는 실수 · 중간 = 헤맴 · 낮음 = 거슬림

### 5.9.1 변경 목록

#### 관계자 웹 — 갈래 `W`

| # | 심각도 | 지금 | 할 일 | 근거(인용) |
|:-:|:-:|---|---|---|
| W1 | 높음 | 학부모·학생·기사·동승자 계정이 웹 로그인을 통과해 `/login`↔`/dashboard` 를 오감 | 로그인 응답 역할이 `staff`·`system_admin` 이 아니면 세션을 비우고 "학부모·학생·매니저는 앱을 이용해 주세요" 안내 | `features/auth/lib/navigation.ts:43-57` · `LoginForm.tsx` |
| W2 | 중간 | 관계자 화면 판정이 `/dashboard` 만 검사 — 메인 관리자가 `/student` 등을 직접 치면 관계자 화면이 열림 | 관계자 경로 전부 나열(관리자 쪽 `ADMIN_PATH_SEGMENTS` 와 같은 방식) | `navigation.ts:51` |
| W3 | 높음 | 학생·기사·동승자 가입 승인에 학생·매니저 ID 직접 입력 — 목록에 ID 미표시라 사실상 승인 불가 | 이름 검색 → 고르는 목록 | `features/approval/components/SignupDecideDialog.tsx:124-142` |
| W4 | 중간 | 가입 승인 대화상자 역할이 `student`·`driver` 영문 | 한글 역할명 | `SignupDecideDialog.tsx:121-123` |
| W5 | 높음 | 실시간 비상 알림이 대시보드 한 줄 — 다른 알림에 덮임 · 유형 영문 · 목록 링크·확인 버튼 부재 · 사이드바 건수 부재 | 관계자 전 화면 팝업(확인 전까지 유지) · 한글 유형 · 비상 목록 링크 · 사이드바 '비상 알림' 건수(`SideNav` badge) | `features/run/components/DashboardPage.tsx:268,280` · `app/(staff)/layout.tsx` |
| W6 | 높음 | 관계자 비상 목록에 연락처 부재 · 자동 갱신 부재 · 위치가 좌표 숫자 | 관리자 화면의 상세 대화상자·주기 갱신 재사용 · 위치는 지도 링크 | `features/emergency/components/EmergencyList.tsx:76` · 본보기 `admin/components/EmergencyDetailDialog.tsx:39` · `EmergencyAlertsPage.tsx:63` |
| W7 | 중간 | 변경 승인 상세의 처리 기한이 시각만 — 남은 시간 부재(A-05) | 남은 분·초 | `features/approval/components/ChangeApprovalDetail.tsx:239` |
| W8 | 중간 | "새로고침 후 다시 확인" 안내에 버튼 부재 · 불러오기 실패에 재시도 부재 | '다시 불러오기' 2곳 | `ChangeApprovalDetail.tsx:212-218,242-243` |
| W9 | 중간 | 목록 11곳+ 시각이 ISO 원문 | 공용 시각 포맷 1곳 → 열 렌더에 연결(`RunDayList` 방식 참고) | `ScheduleList.tsx:72` · `NotificationList.tsx:92` · `SignupApprovalPage.tsx:64` · `ReportList.tsx:60` · `EmergencyList.tsx:76` · `EmergencyAlertsPage.tsx:79` · `AuditLogPage.tsx:80,89` · `MemberApprovalsPage.tsx:45` · `ForceConfirmPage.tsx:77-78` · `BlockedAccountsPage.tsx:63` · `DashboardPage.tsx:473` · `TodayRunPage.tsx:452` |
| W10 | 중간 | 목록 13곳이 0건이면 머리글만 · 행이 키보드로 안 열림 | `RosterTable` 한 곳에 빈 목록 문구 + 행 `tabIndex`·Enter | `shared/ui/transit/RosterTable.tsx` |
| W11 | 중간 | 대시보드 '미탑승 확인 대기 N건' 띠에 처리 화면 링크 부재 | 해당 운행 금일 운행 화면 링크 | `DashboardPage.tsx:386-391` |
| W12 | 중간 | 학원을 비활성으로 저장하면 확인 없이 소속 전원 차단(UF-O-04 는 인원 확인 창 요구) | 비활성 저장 전 확인 창(인원을 줄 API 가 없으면 경고 문구만 — 보고서에 판단 기록) | `features/admin/components/AcademyFormDialog.tsx` |
| W13 | 낮음 | 이탈 경고가 노선 편집에만 | 학생 폼·학원 설정에도 `leaveGuard` | `shared/lib/navigation/leaveGuard.ts` |
| W14 | 낮음 | 가입 대기 화면 불러오기 실패에 버튼 부재 | '다시 시도' | `features/auth/components/SignupStatusPanel.tsx:44,59` |
| W15 | 낮음 | `USER_FLOWS UF-M-08` 이 `Ruling 325` 로 제거된 A-15 웹 화면을 아직 설명 | 문서 정정 | `docs/USER_FLOWS.md` |

#### 학부모·학생 앱 — 갈래 `P`

| # | 심각도 | 지금 | 할 일 | 근거(인용) |
|:-:|:-:|---|---|---|
| P1 | 높음 | 부모 연결 코드 생성(S-05)이 일정 화면에만 있고 학생은 그 화면에 갈 길 부재 | 학생 홈에 '부모 연결 코드' 진입 | `schedule/presentation/schedule_screen.dart:131` · `home/presentation/widgets/pending_change_badge.dart:25-29` |
| P2 | 높음 | 일정 화면(주간 주소 · 변경 신청) 진입이 '처리 대기 N건' 배지뿐 — 0건이면 진입 불가 | 홈에 항상 보이는 '일정' 진입 | `home/presentation/home_screen.dart:128-144` |
| P3 | 높음 | 자녀 연결 화면이 자녀 0명일 때만 — 둘째 연결 불가 | 자녀 선택 옆 '자녀 추가' | `home_screen.dart:86-94` |
| P4 | 높음 | 탑승 스위치가 확인 없이 OFF · 카드 전체가 지도 이동 영역 · 확정까지 남은 시간 부재 | OFF 로 바꿀 때만 확인 · 스위치 줄은 지도 이동 제외 · 남은 시간 | `home/presentation/widgets/run_card.dart:77,95-96,121-126` |
| P5 | 높음 | 자녀를 바꿔도 주간 주소 입력칸·고른 운행이 이전 자녀 값 유지 → 다른 아이 이름으로 저장 위험 | 두 위젯에 자녀 ID 를 key 로 — **지금 코드의 값 유지를 위젯 시험으로 먼저 봄**(재현 안 되면 `잘못 짚음`) | `schedule/presentation/widgets/weekly_address_editor.dart:36-41` · `change_request_panel.dart:33` |
| P6 | 중간 | 학생 실시간 지도에 노선 상세 버튼 부재(학부모는 있음) | 학생에게도 표시 | `live_map/presentation/live_map_screen.dart:119-137` |
| P7 | 중간 | 오류 띠 4곳·연결 끊김 띠에 다시 시도 부재 · 당겨서 새로고침 부재 | 다시 시도 버튼 · 홈 `RefreshIndicator` | `live_map_screen.dart:218-226` · `home_screen.dart:83,137,158,186` |
| P8 | 중간 | 날짜·시각이 `DateTime` 원문 3곳 | `DateFormat` + `toLocal()` | `run_card.dart:59` · `change_request_panel.dart:82` · `child_link/presentation/child_link_screen.dart:173` |
| P9 | 중간 | 승인 대기 화면이 상태를 처음 한 번만 조회 | '상태 다시 확인' | `auth/presentation/pending_approval_screen.dart:43-45` |
| P10 | 중간 | 알림을 누르면 읽음만 — 관련 화면 이동 부재 | 알림 종류별 이동 | `home/presentation/widgets/notification_list.dart:48,52-55` |
| P11 | 중간 | 주간 주소 빈 목록에 '추가' 부재 | 빈 화면 '추가' | `weekly_address_editor.dart:95-97` |
| P12 | 중간 | 변경 요청이 오늘 운행만 선택 · 제출 불가 이유 부재 | 사양(P-06 · `UF-P-06`)이 날짜 선택이면 날짜 선택 · 부족한 입력 안내 | `change_request_panel.dart:106-126,180` |
| P13 | 낮음 | 차단 안내 문구가 학원 관리자·메인 관리자를 섞음 | 문의처 하나로(`UF-X-04` · `Ruling 329`) | `auth/presentation/blocked_screen.dart:31-33` |
| P14 | 낮음 | 입력 도중 뒤로가기에 확인 부재(`PopScope` 0건) | 주소·변경 요청 입력 화면에만 | — |
| P15 | 낮음 | 배지에 화면 읽기 설명 부재 | `Semantics(label: '처리 대기 N건')` | `pending_change_badge.dart` |

#### 매니저 앱 — 갈래 `M`

| # | 심각도 | 지금 | 할 일 | 근거(인용) |
|:-:|:-:|---|---|---|
| M1 | **사용자 지시** | 운행 화면 가운데가 "지도 자리 — 연동은 다음 라운드" 빈 상자(높이 160) | 확정 노선 도로 경로 + 승하차지 + 현재 버스 위치를 보이는 지도(M-08 · M-09 "노선"). 기존 `route_map_screen.dart` · `core/map/map_surface.dart` · `naver/naver_map_adapter.dart` 재사용 · 도착·종료 대형 버튼이 화면 밖으로 밀리지 않게 | `drive_mode/presentation/drive_mode_screen.dart:255-264` |
| M2 | 높음 | 비상 신고 화면이 라우트만 있고 진입 버튼 부재(M-15 — 기사·동승자 모두) | 홈·운행·명단 머리말에 항상 보이는 비상 버튼 | `emergency/presentation/emergency_screen.dart:26-29` · `app/router.dart:108` |
| M3 | 높음 | 종료 보고서(보호자 부재 등)가 기사 운행 화면에서만 — 동승자 보고 불가(M-14 공통) | 명단 화면에 '예외 보고' | `drive_mode_screen.dart:188,337` · `home/presentation/home_screen.dart:146-156` |
| M4 | 높음 | 노선 변경 확인 띠(M-04 공통)가 명단 화면에만 — 기사는 명단에 못 감 | 운행 화면에도 같은 띠 | `roster/presentation/roster_screen.dart:251-261` |
| M5 | 높음 | 기사 운행 화면에 다음 승하차지 이름 하나 — 남은 승하차지·명단 부재(M-08 "클릭 → 명단") | 남은 승하차지 목록(조회 전용 — 승하차 처리는 동승자만, M-12) | `drive_mode_screen.dart:319-322` |
| M6 | 높음 | 운행 시작·도착 처리가 확인 없이 전송 — 마지막 도착 = 운행 종료 | 최소 마지막 승하차지 도착에 확인 창 | `drive_mode_screen.dart:188,310,322` |
| M7 | 높음 | [미승차]가 [탑승] 옆 · 확인 없이 전송 | 확인 창 또는 짧은 취소 가능 시간 | `roster_screen.dart:320-324,482-490` |
| M8 | 중간 | 오류 원문 5곳 노출 | 기존 `describeFailure` 재사용 | `home_screen.dart:56` · `drive_mode_screen.dart:280` · `roster_screen.dart:233` · `route_map/presentation/route_map_screen.dart:72` · `offline_queue/presentation/offline_queue_screen.dart:84` |
| M9 | 중간 | 확정 전 카드가 안 눌리는 이유 부재(M-02 "출발 30분 전 확정" 안내) | "출발 30분 전 확정 후 열림 (HH:mm)" | `home_screen.dart:116-118,137` |
| M10 | 중간 | '운행 시작 가능 시간(출발 ±10분)이 아닙니다'만 — 가능 시각 부재 | "HH:mm 부터 시작 가능" | `drive_mode_screen.dart:307` |
| M11 | 중간 | 종료 보고서 메모 필수 표시 부재 · 보호자 부재 대상 빈 목록 이유 부재 · 중복 제출 가능 | 필수 표시 · 빈 목록 안내 · 제출 뒤 버튼 끄기 | `run_end/presentation/run_end_screen.dart:58,180,195` |
| M12 | 중간 | 미승차 연락 시트 '3분 경과 후에만 선택' 이 글자뿐 · 남은 시간 부재 | 대기 시간 전 선택지 끄기 · 남은 시간(대기 시간은 학원 설정값 — A-17) | `roster_screen.dart:515,591` |
| M13 | 중간 | 설정·비밀번호 변경 화면 부재(AUTH-07 · `UF-X-09` — 전 역할) | 학부모 앱 비밀번호 변경 화면을 본보기로 추가 | 학부모 앱 `password_change` |
| M14 | 낮음 | 명단의 '노선 지도' 버튼이 기사 조건이라 동승자에겐 안 보이고 기사는 명단에 안 옴 | 삭제(동승자 지도 화면 부재 — `USER_FLOWS §1`) | `roster_screen.dart:194` |
| M15 | 낮음 | 운행 중 Android 뒤로가기 확인 부재 | 운행 중에만 확인 | — |
| M16 | 확인 | 운행 화면에서 뒤로 나간 뒤 위치 송신이 계속되는지 불명 | 코드로 판정 — 끊기면 결함으로 고치고, 계속되면 근거만 보고 | `drive_mode` 위치 송신부 |

### 5.9.2 이번 범위 밖 — `Ruling 365`

| 항목 | 이유 |
|---|---|
| 관계자 웹 A-07 수동 조정(버스 간 학생 이동) 화면 | 새 화면 · `§3.3` 미등재 — 범위 판정 필요 |
| 매니저 앱 외부 내비 연결(M-09 · RUN-08) | 서버 `NavigationController` 는 있으나 카카오내비 호출에 카카오 앱 키 필요(FCM 과 같은 막힘) |
| 학부모 홈 '설정'·'알림' 배치 · 탑승 스위치 터치 크기 | 화면을 봐야 판정 — 병합 뒤 조율자 화면 확인 |

### 5.9.3 갈래 배정

앱 단위로 가름 — 파일 겹침 0. 동시 3좌석(`§5.5` — 4좌석은 메모리 부족 실측). **`frontend/packages/**` 는 세 갈래 모두 읽기만** — 공유 위젯 변경이 필요하면 앱 안에서 해결하고 보고서에 적음. `docs/` 는 세 갈래 모두 고칠 수 있음(겹치면 병합 때 조율자가 해소).

| 좌석 | 대상 | 항목 | 우선순위 |
|:-:|---|---|---|
| `W` | `frontend/apps/academy-web/**` | W1~W15 | 높음(W1·W3·W5·W6) 먼저 |
| `P` | `frontend/apps/parent-app/**` | P1~P15 | 높음(P1~P5) 먼저 |
| `M` | `frontend/apps/manager-app/**` | M1~M16 | **M1(사용자 지시) → 높음(M2~M7)** 먼저 |

### 5.9.4 목표 표 — 전항 통과가 완료 조건

- RED ✅ — 고치기 전 실패를 눈으로 보고 실패 문면을 보고서에(`docs/IMPLEMENTATION_PLAN.md §4.6`). 재현이 안 되면 고치지 않고 `잘못 짚음`
- 좌석은 **단위 시험만** — 실서버 시험은 병합 뒤 조율자(`§5.8.5` 와 같은 규칙)
- 착수 직후 기준값을 잼 — 마지막 기록 `§5.8.8`(웹 · 학부모 · 매니저 시험 수)

| # | 좌석 | 완료 조건 | 검증 |
|:-:|:-:|---|---|
| 1 | `W` | ✅ W1 · W2 | 로그인 응답 역할 `parent` 로 로그인 폼 시험 → 세션 비움 + 안내 문구 · `/student` 경로에서 `system_admin` 세션이면 관리자 쪽으로. 지금 코드의 대시보드 이동을 먼저 봄 |
| 2 | `W` | ✅ W3 · W4 | 가입 승인 대화상자 — 학생 역할이면 **ID 입력칸 부재 · 이름 검색 목록에서 골라 승인 요청 본문에 그 학생 ID** · 역할 한글 |
| 3 | `W` | ✅ W5 · W6 | `emergency_raised` 수신 → 대시보드가 아닌 화면에서도 팝업 · 다른 알림 뒤에도 유지 · 사이드바 건수 · 비상 목록 행에 연락처 |
| 4 | `W` | ✅ W7~W12 각 1건 | 남은 시간 · 다시 불러오기 · 시각 포맷(ISO 원문 부재) · 빈 목록 문구 + Enter 로 행 열기 · 미탑승 띠 링크 · 비활성 저장 전 확인 |
| 5 | `W` | 정적 분석·시험 | `npx tsc --noEmit` 새 오류 0 · `npm run lint` 0 · `npx vitest run --exclude '**/realBackend.test.ts' --exclude '**/wsRealBackendAuth.test.ts'` 실패 0 |
| 6 | `P` | ✅ P1~P3 | 학생 홈에 연결 코드 진입 · 학부모 홈에 배지 0건에도 일정 진입 · 자녀 1명 이상에도 '자녀 추가' — 지금 코드의 부재를 먼저 봄 |
| 7 | `P` | ✅ P4 · P5 | 스위치 OFF → 확인 창 · 취소하면 요청 부재 · 스위치 탭이 지도 이동 안 함 · 자녀 전환 뒤 주소 칸이 새 자녀 값 |
| 8 | `P` | ✅ P6~P12 각 1건 | 위 표의 할 일 그대로 |
| 9 | `P` | 정적 분석·시험 | `flutter analyze` 새 지적 0 · `flutter test --exclude-tags real_backend` 실패 0 |
| 10 | `M` | ✅ **M1** | 운행 화면 위젯 시험 — **"지도 자리" 문구 부재 · 지도 면(가짜 지도 어댑터) 존재 · 승하차지 핀 수 = 노선의 승하차지 수 · 버스 위치 표시.** 지금 코드의 빈 상자를 먼저 봄 |
| 11 | `M` | ✅ M2~M7 | 비상 버튼 3화면 · 동승자 명단의 예외 보고 · 운행 화면의 변경 확인 띠 · 남은 승하차지 목록 · 마지막 도착 확인 창(취소하면 요청 부재) · 미승차 확인(취소하면 요청 부재) |
| 12 | `M` | ✅ M8~M15 각 1건 | 위 표의 할 일 그대로 · M16 판정 근거 |
| 13 | `M` | 정적 분석·시험 | 9번과 같음(매니저 앱) |
| 14 | 조율자 | **병합 후 단독 전체 실행** — 실패 0 · 건너뜀 0 | 웹 · 학부모 · 매니저 · `baraeda_core` · `baraeda_ui` 단위 + 실서버(전용 포트 · 전용 DB · 주소 명시) |
| 15 | 조율자 | **화면 확인 — 운행 화면 지도** | 매니저 앱을 시뮬레이터로 띄워 운행 중 회차에 진입 → 지도에 노선·승하차지·버스가 **보이는 것을 눈으로**(검사 초록 ≠ 화면 — `R17~R21` 교훈) |
| 16 | 조율자 | 시나리오 문서 동기화 | `docs/TEST_SCENARIOS.html` §5 알려진 문제 · 해당 단계의 표시를 고친 만큼 제거 |

### 5.9.5 ✅ 결과 — 3갈래 병합 · 전체 실행 · 운행 화면 지도 눈 확인 (2026-09-30 조율자)

Orca Run `run_b2b80ef27d17` · 작업 창 3개 `claude-sonnet-5-5[1m]` `high` · 지시서·보고서 `.claude/r32/`(git 추적 밖). 01:10 께 두 창이 계정 인증 오류("organization has disabled Claude subscription access")로 동시에 멈춤 → 새 세션으로 복구 확인 뒤 `orca terminal send` 로 이어서 끝냄.

| 병합·커밋 | 내용 |
|---|---|
| `1d592713` | W — W1~W15(수정 14 · 문서 1). ⚠ **W12 점검 전제가 틀림** — 학원 비활성은 기존 사용자를 막지 않음(가입 검색 제외·신규 가입 차단만, `API_SPEC §6.3`) → 확인 창 문구를 실제 효과로 · W9 `ScheduleList` 는 ISO 아닌 `HH:mm` 이라 잘못 짚음 |
| `efdd22e7` | P — P1~P15 전부 수정. P12 는 서버가 회차를 그날 하루치만 만들어 날짜 선택 없이 "제출 불가 이유" 안내만 — **판정 대기** |
| `1ecc45c0` | M — M1~M16(수정 15 · M16 결함 확인 후 부분 완화) |
| `bbb98ad4` | 조율자 — §4.3 에 `road_path`·`fallback_used`(M 갈래 질문 답: 직선 보조선 금지) |
| `9387084c` | 조율자 — 웹 lint 오류 1건(`R32` 이전부터 있던 시험 도우미 이름) |
| `723ecc20` | 조율자 — **운행 화면 지도가 iOS 에서 앱을 종료시키던 결함**(아래) |

**실행 — 병합 뒤 단독(목표 14)** — 웹 단위 85파일 **423** · 실서버 14파일 **77** · `baraeda_core` 51 + 실서버 9 · `baraeda_ui` 85 · 학부모 173 + 실서버 28 · 매니저 213 + 실서버 26. **실패 0 · 건너뜀 0.** 실서버는 전용 포트 8182 · 전용 DB `r32_rb`. `baraeda_core` 실서버 1건(`parentPending` 이 `rejected`)은 앞선 웹 실서버 시험이 그 계정을 거절해 소비한 탓 → `/dev/reset` 뒤 그 1건만 재실행 통과(`§5.8.3.3` 의 "같은 DB 두 번 전 재구성" 과 같은 형태). `tsc` 0 · lint 오류 0(경고 5 기존) · `flutter analyze` 새 지적 0(매니저 기존 9)

**화면 확인(목표 15) — iPhone 17 Pro 시뮬레이터 · 전용 서버 · `driver011`**
- ⚠ **첫 진입에서 앱 종료** — `flutter_naver_map`(iOS) `NOverlayImage.makeOverlayImageWithPath` 가 **0바이트 핀 PNG** 를 강제 언랩하다 `SIGTRAP`. 원인은 버스 좌표 갱신(2초)마다 오버레이 동기화를 `unawaited` 로 겹쳐 시작해 같은 핀 이미지를 동시에 만든 것 — 위젯 시험은 가짜 지도라 못 봄. `SerialSync`(도는 중 요청은 끝난 뒤 1회 재실행)로 수정 · 겹침 변형을 심으면 동시 실행 4 로 시험 실패 확인
- 수정 뒤 — 운행 화면 가운데 지도에 **도로 경로(파란 선) · 승하차지 핀 1~16 · 버스** 가 노선에 맞춰 보임. 비상 버튼 · 변경 확인 띠 · 남은 승하차지 16곳 · 하단 고정 도착 버튼 확인
- 웹·학부모 화면과 매니저 나머지 화면의 "눈으로 봐야 할 곳"(갈래 보고서 2항)은 **미확인** — 팀원 시나리오(`docs/TEST_SCENARIOS.html`)로 넘김

**남은 것 — 다음 단위**

| 항목 | 내용 |
|---|---|
| M16 잔여 | 운행 화면을 나가면 위치 송신이 멈춤 — 확인 창으로 알리기만. 근본 수정은 송신을 앱 전역(운행 상태 provider)으로 |
| 지도 카메라 | 화면을 열 때 폰 위치가 노선에서 멀면(오래된 위치) 넓게 잡히고 버스가 가까워져도 다시 맞추지 않음(마커 id 구성이 바뀔 때만 맞춤) — 시뮬레이터 실측 |
| P12 | 변경 신청 날짜 선택 — 서버가 회차를 하루치만 생성해 불가. 판정 필요 |
| 매니저 승인 대기 화면 | 상태 1회 조회 — 학부모 앱만 [상태 다시 확인] 추가됨 |
| 학부모 지도 어댑터 | 같은 `unawaited` 동기화 형태(위젯 아이콘을 안 만들어 종료 원인은 없음) — 겹쳐 돌면 마커 중복 추가 가능성만 |
| 범위 밖(`§5.9.2`) | A-07 수동 조정 화면 · 외부 내비(카카오 앱 키) |

## 5.10 ✅ `R33` 목표 표 — R32 남은 것 (2026-09-30 계획 · **2026-09-30 완료** · 결과 `§5.10.3`)

사용자 지시 *"남은 것 중에 범위 밖만 빼고 수정"* — `§5.9.5` "남은 것" 표에서 범위 밖(A-07 · 외부 내비)을 뺀 5건. P12 는 `Ruling 366`(`docs/archive/rounds/be-rounds-r22-r41.md §8.61`)으로 방향을 정함. 기준 HEAD `3a4e7e4e`(조율자가 `SerialSync` 를 `baraeda_core` 로 옮긴 뒤).

### 5.10.1 변경 목록

| # | 갈래 | 지금 | 할 일 |
|:-:|:-:|---|---|
| B1 | `B` | 회차를 **그날 것만** 00:05 에 생성 — 내일 회차가 없어 학부모가 내일 하루 변경(P-06 · REQ-01 "특정 날짜")을 못 함 | 00:05 배치가 **오늘 + 내일** 을 만든다(멱등). **기동 시에도 내일 회차를 한 번 만든다** — 서버가 00:05 에 꺼져 있었거나(스테이징은 매일 05:00 재시작·초기화) 하면 하루 종일 내일 회차가 없다. 기동 시 생성도 배치와 같은 설정으로 시험에서 꺼진다 |
| B2 | `B` | 스케줄 등록·수정·삭제가 이미 만든 회차에 반영 안 됨 — 내일 회차를 미리 만들면 **"오늘 고친 스케줄이 내일에 안 먹는"** 새 결함 | 그 스케줄로 만든 **내일(오늘 이후) · 아직 시작 전(`idle`) · 취소 안 된** 회차에 반영: 출발 시각·차량·출발지·도착지·소요 시간 수정은 그대로 옮김(확정 시각 재계산) · 비활성·삭제·요일/방향 변경은 그 회차 **임시 취소**(`canceled_at`, SCH-03 과 같은 표시 — 행 삭제 부재) · 등록·활성·요일/방향 변경 뒤 내일 회차 생성(멱등). **오늘 회차는 지금처럼 건드리지 않는다** |
| P1 | `P` | 변경 신청이 오늘 회차만(R32 P12) | 날짜 선택 **오늘 · 내일** → `GET /students/{id}/runs?date=` 로 그날 회차 → 회차 고르기 → 신청(`§3.8` 계약 그대로). 내일 회차가 없으면 "내일 운행이 아직 없습니다" 안내 |
| P2 | `P` | 지도 어댑터가 마커 동기화를 `unawaited` 로 겹쳐 시작 | `baraeda_core` 의 `SerialSync` 로 한 번에 하나만(매니저 앱과 같은 형태) |
| M1 | `M` | 운행 화면을 나가면 위치 송신이 멈춤(R32 M16 — 확인 창으로 알리기만) | 송신을 **화면이 아니라 앱 전역**(운행 상태)으로 — 운행 중(`moving`)인 기사 회차는 어느 화면에서든 송신 · 운행 종료·로그아웃 때 멈춤 · 송신기는 하나. 나갈 때 경고 확인 창은 제거(이유가 사라짐) |
| M2 | `M` | 지도 카메라 — 화면을 열 때 버스 위치가 노선에서 멀면(오래된 위치) 세계 지도까지 넓어지고, 버스가 가까워져도 다시 안 맞춤(시뮬레이터 실측) | 카메라 맞춤은 **노선(승하차지·도로 경로) 기준.** 버스는 노선 사각형을 약 2km 넓힌 범위 **안일 때만** 맞춤에 넣고, 그 포함 여부가 바뀌면 다시 맞춤 |
| M3 | `M` | 승인 대기 화면이 상태를 한 번만 조회 | '상태 다시 확인'(학부모 앱 R32 P9 와 같은 동작) |

### 5.10.2 목표 표 — 전항 통과가 완료 조건

| # | 갈래 | 완료 조건 | 검증 |
|:-:|:-:|---|---|
| 1 | `B` | ✅ B1 | 배치 1회 → 오늘·내일 회차 생성 · 두 번째 실행은 0건(멱등) · 기동 시 내일 회차 생성 · 시험 컨텍스트에서는 기동 생성이 돌지 않음. 지금 코드의 "내일 0건" 을 먼저 봄 |
| 2 | `B` | ✅ B2 | 스케줄 출발 시각 수정 → 내일 `idle` 회차의 `depart_time`·`confirm_at` 변경 · **오늘 회차는 그대로** · 이미 확정·시작·취소된 회차는 그대로 · 비활성/삭제/요일 변경 → 내일 회차 `canceled_at` · 등록 → 내일 회차 생김. 각각 지금 코드의 미반영을 먼저 봄 |
| 3 | `B` | 넓은 시험 | 백엔드 전체 시험 실패 0(병합 뒤 조율자 1회) — 좌석은 `schedule`·`run` 패키지 + `§5.8.3.3` 과 `parallel-agents-git.md §18` 이 말하는 "전체를 세는 시험"(규약·계약) |
| 4 | `P` | ✅ P1 · P2 | 날짜 '내일' → 그날 회차 조회 요청의 `date` 가 내일 · 회차 없으면 안내 · 신청 본문 `run_id` 가 고른 회차 · `SerialSync` 로 동기화가 겹치지 않음 |
| 5 | `M` | ✅ M1 | 운행 화면을 나가도(다른 화면으로 이동·뒤로) 위치 송신이 계속 · 운행 종료·로그아웃에 멈춤 · 송신 요청이 두 벌로 나가지 않음. 지금 코드의 "나가면 멈춤" 을 먼저 봄 |
| 6 | `M` | ✅ M2 · M3 | 멀리 있는 버스 → 맞춤 사각형이 노선만 · 가까워지면 버스 포함으로 다시 맞춤(순수 함수 시험) · 대기 화면 '상태 다시 확인' |
| 7 | 좌석 전부 | 정적 분석·단위 시험 | `flutter analyze` 새 지적 0 · `flutter test --exclude-tags real_backend` 실패 0 · 백엔드 좌석은 해당 패키지 시험 실패 0 |
| 8 | 조율자 | 병합 뒤 단독 전체 실행 | 백엔드 전체 · 웹 · 앱 2종 · 공유 패키지 2종 단위 + 실서버(전용 포트·DB) — 실패 0 · 건너뜀 0 |
| 9 | 조율자 | **화면 확인** | 시뮬레이터 — 매니저 운행 화면에서 나갔다 돌아와도 버스가 계속 움직임(전용 서버 위치 기록 증가) · 카메라가 노선에 맞음 · 학부모 변경 신청에서 '내일' 회차가 보임 |
| 10 | 조율자 | 문서 | `USER_FLOWS UF-P-06`(R32 가 "오늘만" 으로 적은 것 되돌림) · `ARCHITECTURE §9` 회차 생성 · `API_SPEC §5.10` · 시나리오 K13·K14·K10·K16 제거 |

### 5.10.3 ✅ 결과 (2026-09-30 조율자)

Orca Run `run_97f76b021f57` · 3갈래 `claude-sonnet-5-5[1m]` high · 병합 B `8e1f6b06` · P `a33a42f9` · M `9cb7bdf0`. 준비 커밋 `3a4e7e4e`(`SerialSync` → `baraeda_core`).

- **단위** — 백엔드 1,691(실패 5 = 네이버 `Directions 15` 한도 초과 400 — `Directions 5` 로 그 5건만 재실행 통과, `Ruling 361`) · 웹 423 · `baraeda_core` 52 · `baraeda_ui` 85 · 학부모 178 · 매니저 231 — 실패 0 · 건너뜀 0
- **실서버**(8182 · `r33_rb`) — 웹 77 · core 9 · 매니저 26 · 학부모 28 — 실패 0 · 건너뜀 0
- **실동작** — 스케줄 등록 → 내일 회차 생성(19:00 · 확정 18:30) · 출발 19:40 수정 → 내일 회차 19:40 · 확정 19:10, **오늘 회차 불변**(건수·수정 시각 동일) · 비활성 → 내일 회차 취소 표시. 시드 스케줄은 전부 수요일이라 기동 시 내일(목) 회차는 시드 R7 1건 — 정상
- **시뮬레이터**(매니저 · `driver011`) — 운행 화면 지도가 노선에 맞음(핀 1~16 · 도로 경로 · 버스) · 운행 화면을 나가 홈에서 20초 → 앱 좌표 10건(2초 간격 유지) · 운행 화면을 3번 다시 열어도 20초 11건(송신 두 벌 부재). 로그아웃 정지·학부모 '내일' 선택 화면은 시뮬레이터 입력이 먹지 않아 **미확인** — 단위 시험·API(`?date=내일` 이 R7 반환)로 대체
- 남은 것 — 앱 완전 종료 뒤 송신 자동 재개(M 보고서 판단: 로그인 복구 직후 `GET /manager/runs` 로 `moving` 회차를 찾아 채우면 됨) · 스케줄 재활성 시 취소된 내일 회차 미복원(취소 사유 구분 컬럼 필요) · 임시 회차와 출발 시각 충돌 시 500 가능 · 초기화 직후 내일 회차 부재

## 5.11 ✅ `R34` 목표 표 — 할 수 있는 개선 전부 (2026-09-30 계획 · **2026-09-30 완료** · 결과 `§5.11.3`)

사용자 지시 *"할 수 있는 코드 작업 및 개선 작업은 별도의 허락 없이 진행가능한건 없을때까지 · 오케스트레이터 적극 활용"*. 입력 = `§5.10.3` 남은 것 · `§5.9.2` 범위 밖이던 A-07 · 정적 분석 지적. 판정 `Ruling 367`(`docs/archive/rounds/be-rounds-r22-r41.md §8.62`). 기준 HEAD `2e9fd430`.

**여전히 막힌 것(이 라운드 밖 — 사용자 자원 필요)** — FCM(Firebase 키) · 외부 내비(카카오 앱 키) · SMS · 배포(AWS·도메인) · 실기기 확인 · 네이버 Directions 15 한도

### 5.11.1 변경 목록

| # | 갈래 | 지금 | 할 일 |
|:-:|:-:|---|---|
| B1 | `B` | 스케줄을 끄면 내일 회차를 취소 표시하는데, 다시 켜도 되살리지 않음(취소가 스케줄 때문인지 관계자가 직접 했는지 구별 불가 — R33 B 보고) | `run` 에 취소 출처(`staff` · `schedule`) — V1 직접 수정(개발 단계 정책) · ERD · enum 패리티. 스케줄 재활성·요일/방향 복귀 때 **스케줄이 취소한** 내일 `idle` 회차만 되살리고 계획 재적용. 관계자 취소(SCH-03)는 그대로 |
| B2 | `B` | 스케줄 출발 시각 수정이 같은 버스·날짜·방향·시각의 임시 회차와 겹치면 커밋 시 제약 위반 500 | 기존 `409 DUPLICATE_RUN` 으로 — 스케줄 변경 전체가 되돌려지고 관계자가 사유를 봄 |
| B3 | `B` | `/dev/reset` 뒤 내일 회차 없음(스테이징 초기화 버튼 — 시나리오 K17) | 초기화 뒤 **내일** 회차만 생성(오늘은 시드 그대로 — 계약 시험이 시드 상태에 기댐) |
| B4 | `B` | 데모 시드(V14) 스케줄이 전부 "적용일 요일" 이라 내일 회차가 생기지 않음 | V14 버스마다 **내일 요일** 스케줄을 더함 — 기동·초기화 때 데모 학원 30대의 내일 회차가 생겨 학부모 '내일' 변경·탑승 끄기를 스테이징에서 시험 가능. `db/migration-demo` 라 시험 DB 무관 |
| W1 | `W` | A-07 수동 조정(버스 간 학생 이동) 화면 부재 — API `§5.8 POST /staff/students/{id}/transfer` 는 있음 | 금일 운행 상세 등 관계자가 회차 명단을 보는 곳에서 확정 전(①구간) 회차의 학생 → [다른 버스로] → 같은 날짜·방향의 다른 회차 + 기존 승하차지 또는 주소 → 저장 → 양쪽 인원 전후(`impact`) 표시. 에러 코드별 문구(`§5.8` 에러 5종). `§3.3` 화면 목록 등재 |
| W2 | `W` | 목록을 불러오는 순간 "표시할 내용이 없습니다" 가 잠깐 보임(K15) | `RosterTable` 에 불러오는 중 상태 — 그동안 빈 목록 문구 대신 불러오는 중 표시. 쓰는 화면 전부 |
| W3 | `W` | lint 경고 5(`_props` 미사용) | 0 |
| P1 | `P` | 홈이 오늘 회차만 — `UF-X-06`·`UF-P-04` 는 **전날~당일** 탑승 끄기 | 홈에 **오늘 · 내일** 전환 — 내일 회차 탑승 토글(①구간 즉시 반영). 학생은 조회만 |
| P2 | `P` | 정적 분석 지적 — 학부모 3 · `baraeda_core` 87 · `baraeda_ui` 4 | 0 — 동작·공개 API 불변 |
| M1 | `M` | 운행 중 앱을 완전히 껐다 켜면 송신이 자동 재개되지 않음(K18) | 로그인 복구 직후 `GET /manager/runs`(§4.1)로 오늘 `moving` 인 **내가 기사인** 회차를 찾아 선택 — 송신 재개. 여럿이면 규칙을 정해 판단 기록 |
| M2 | `M` | 정적 분석 지적 9 | 0 |

### 5.11.2 목표 표

| # | 갈래 | 완료 조건 | 검증 |
|:-:|:-:|---|---|
| 1 | `B` | ✅ B1 · B2 · B3 | 끄기 → 취소(출처 schedule) → 켜기 → 되살아남 · 관계자가 취소한 회차는 켜도 그대로 · 임시 회차와 시각 충돌 → `409 DUPLICATE_RUN`(스케줄 불변) · 초기화 뒤 내일 회차 존재. 지금 코드의 실패를 먼저 봄 |
| 2 | `B` | B4 · 넓은 시험 | 로컬 기동 뒤 데모 학원 내일 회차 ≥ 30 · 백엔드 `schedule`·`run`·`dev` 패키지 + 전체를 세는 시험 실패 0 |
| 3 | `W` | ✅ W1 | 이동 대화상자 — 확정 전 회차만 버튼 · 요청 본문(`from_run_id`·`to_run_id`·`stop_id`/`address` 배타) · 에러 5종 문구 · 성공 시 `impact` 표시 |
| 4 | `W` | ✅ W2 · W3 | 불러오는 중에는 빈 목록 문구 부재 · lint 경고 0 · tsc 0 · vitest 실패 0 |
| 5 | `P` | ✅ P1 · P2 | '내일' 전환 → `date=내일` 조회 · 탑승 토글 요청이 내일 회차 id · 학생은 토글 부재 · `flutter analyze` 0(학부모·core·ui) · 시험 실패 0 |
| 6 | `M` | ✅ M1 · M2 | 앱 복구 직후 `moving`+기사 회차 → 송신기 가동 · `moving` 아니면 가동 안 함 · `flutter analyze` 0 · 시험 실패 0 |
| 7 | 조율자 | 병합 뒤 단독 전체 실행 + 실서버 + 화면 | 백엔드 전체 · 프론트 5종 단위 · 실서버 계약 — 실패 0 · 건너뜀 0 · 웹 A-07 화면을 브라우저로 |

### 5.11.3 ✅ 결과 (2026-09-30 조율자)

Run `run_d7daf69b57f8` · 4갈래 병합 B `7f781ccc` · W `827250d1` · P `065ea7f2` · M `70404e78`.
- 단위 — 백엔드 1,701(실패 3 = `NaverDirectionsResilienceTest` 의 "Directions 15 전환" 단언이 조율자가 준 환경변수 `NAVER_DIRECTIONS_PATH=5` 때문 — 변수 없이 그 클래스만 재실행 15/15 통과) · 웹 444 · core 52 · ui 85 · 학부모 183 · 매니저 235 · **`flutter analyze` 4종 전부 0** · tsc 0 · lint 0
- 실서버(8182 · `r34_rb`) — 웹 77 · core 9 · 매니저 26 · 학부모 28 · 실패 0 · 건너뜀 0. 기동 뒤 데모 학원 내일 회차 60(B4) · 초기화 뒤 내일 회차 61(B3) · `run.cancel_source` 실재(B1)
- 화면(브라우저) — ⚠ **두 결함 발견 → `R35`**: ①지도 키 인증이 실패하면(등록 안 된 주소) 금일 운행 화면이 통째로 죽음 — `NaverMapSurface` 의 카메라·마커·선 효과가 `window.naver` 만 보고 `.maps`(SDK 가 인증 실패 때 비움)를 안 봄 ②**A-07 [다른 버스로] 진입 불가** — 버튼이 확정 전 회차의 명단 행에 붙는데 `§5.4` 명단이 확정 전 회차에 빈 배열(명단 행은 확정 때 생김)

## 5.12 ✅ `R35` 목표 표 — R34 화면 확인에서 나온 결함 2건 (2026-09-30 계획 · **완료**)

판정 `Ruling 368`(`docs/archive/rounds/be-rounds-r22-r41.md §8.63`). 기준 HEAD `70404e78`.

| # | 갈래 | 지금 | 할 일 | 검증(RED 먼저) |
|:-:|:-:|---|---|---|
| B1 | `B` | `§5.4` 명단이 확정 전(`idle`) 회차에 빈 배열 | 확정 전이면 **예정 명단** — `§5.8` 이동이 이미 쓰는 "탑승 의사·요일별 주소 기준 예정 명단" 계산을 재사용해 같은 행 모양(`status=waiting` · `change` 부재 · 이동 대기(`staged`)는 반영 규칙을 네가 정해 기록). 확정 뒤는 지금 그대로 | 확정 전 회차 명단 ≥1행 · 탑승 OFF 학생 제외 · 확정 뒤 응답 불변 |
| W1 | `W` | 지도 키 인증 실패 뒤 효과가 `window.naver.maps` 를 null 로 읽어 화면 전체가 죽음 | 세 효과가 `window.naver?.maps` 를 확인 · 인증 실패 때 지도 참조를 비우고 준비 상태를 끔 → 기존 "지도를 불러오지 못했습니다" 대체 화면 | 준비 뒤 `naver.maps=null` + 인증 실패 신호 → 예외 없이 대체 문구 |
| W2 | `W` | (B1 에 기댐) | 확정 전 회차 명단이 행을 주면 [다른 버스로] 가 보이는지 — 가짜 응답으로 시험 1건(이미 있으면 확인만) | — |
| 조율자 | — | — | 병합 뒤 전체 + 실서버 + **브라우저로 A-07 대화상자 실제로 열기** | — |

**결과(2026-09-30)** — Run `run_bb039f2cb94a` · 병합 B `782276b0` · W `d529ffff`. 백엔드 1,705(실패 5 = 네이버 Directions 15 한도 — `Ruling 361`) · 웹 446(첫 실행 1건 실패는 백엔드 전체 시험과 동시에 돌 때만 — 단독 3회 연속 446 통과) · tsc 0 · lint 0. **브라우저(5173 · 지도 키 미등록 주소)** — 확정 전 1호차 등원 명단 2행(예정) · [다른 버스로] 2개 · 대화상자(도착 회차 · 기존 승하차지/주소 · 비고 · 저장은 고르기 전 꺼짐 · "확정 때 반영" 안내) · 지도 칸은 화면 죽음 대신 "지도를 불러오지 못했습니다". 남은 판단 — 예정 행에 이동해 온 학생을 `change=added` 로 표시할지(지금 부재)

## 5.13 `R36-FE` 목표 표 — 이동 취소 · 기한 지난 승인 · 네이버 지도 링크 · 흔들리는 웹 시험 · 정차지 없음 · 매니저 노선 변경 방송 · 연결된 학생 후보 (2026-09-30 계획 · **2026-09-30 완료** — 결과 `5.13.5`·`5.13.6`)

2026-09-30 백엔드·프론트 세션 분리(`Ruling 369` 끝 문단) 뒤 프론트 하위 조율 창의 첫 라운드. 기준 HEAD `ed60f5d8`(`mskim98/fe-main`). 판정 `Ruling 380~389` 는 이 절 `5.13.4` 에 기록. 서버 쪽 짝(BE1 이동 취소 API · BE2 예정 행 `transfer_id` · BE4 연결된 학생 승인 판정 · BE5 `stop_name` null)은 백엔드 창이 동시에 만든다 — 프론트는 가짜 응답으로 먼저 시험하고, 백엔드가 `main` 에 병합되면 받아 실서버로 확인.

### 5.13.1 변경 목록

| # | 갈래 | 지금 | 할 일 |
|:-:|:-:|---|---|
| FE1 | `W` | 예정 명단에 이동해 온 학생이 보여도 되돌릴 수단 부재(`Ruling 369`) | `§5.4` 행의 `transfer_id` 를 읽는다(`transferId: string \| null`). 값이 있는 행(= `change=added` 초록)에 **[이동 취소]** → 확인 → `DELETE /staff/transfers/{transferId}`(`§5.8.1`, `204`) → 지금 명단 다시 조회 + 출발 회차 명단 캐시도 버림. 에러 `403 CHANGE_WINDOW_CLOSED` · `404 TRANSFER_NOT_FOUND` 문구 |
| FE2 | `W` | 구간 변경 승인 상세 — 처리 기한이 지나도 [승인]·[거절] 이 눌림(서버는 기한에 자동 거절 — `Ruling 306`) | 대기(`pending`) 건의 기한이 지나면 두 버튼을 끄고 "처리 기한이 지나 자동 거절됩니다". 화면을 열어 둔 채 기한이 지나도 같은 상태로 바뀜(이미 있는 `now` 시계) |
| FE3 | `W` | 비상 알림 위치 링크가 구글 지도(`features/emergency/lib/mapLink.ts`) | 네이버 지도 웹 주소 — 좌표에 핀이 찍히는 형식. 형식 선택은 `Ruling 380` |
| FE5 | `W` | 예정 명단 행의 `stop_name` 이 `null` 이면 빈 칸(R35 B 보고) | `stopName: string \| null` · 명단 표에서 "정차지 없음" |
| FE6 | `M` | 운행 중 노선 변경 방송(`route_changed`, `§9.7` — ③구간 미등원 반영 포함)을 받아도 운행 화면의 지도·남은 승하차지가 그대로 — 화면을 다시 열어야 반영 | 방송을 받으면 그 회차 노선을 다시 불러와 지도·남은 승하차지 갱신 |
| FE4 | `T` | 웹 단위 시험이 백엔드 전체 시험과 **동시에** 돌 때만 1건 실패(R35 결과 — 단독 3회 연속 통과) | CPU 부하를 걸고 반복 실행해 그 시험을 찾고, 원인(시간 한도·실제 시계 등)을 고쳐 부하에서도 통과 |
| FE7 | `T`(BE4 판정 뒤) | 가입 승인 후보에 이미 다른 계정과 연결된 학생이 섞임(R32 W 보고 5) | 백엔드 BE4 판정을 따른다 — 서버가 거절하면 후보에서 빼거나 "이미 연결됨" 표시 + 에러 문구. 판정이 오기 전에는 착수하지 않는다 |

### 5.13.2 갈래 배정

- 1차 — `W`(FE1·FE2·FE3·FE5, 웹 · `--setup run`) + `M`(FE6, 매니저 · `--setup skip`) — 파일이 겹치지 않는다
- 2차 — `T`(FE4 + BE4 판정이 와 있으면 FE7) — FE4 는 CPU 부하를 걸어야 해서 다른 갈래의 시험을 흔들지 않게 1차 뒤로 뺀다
- 모델 `claude-sonnet-5-5[1m]` · `--effort high` · 동시 2개

### 5.13.3 목표 표 — 전항 통과가 완료 조건

| # | 갈래 | 완료 조건 | 검증 |
|:-:|:-:|---|---|
| 1 | `W` | FE1 — 버튼·요청·재조회 | `transfer_id` 있는 행에만 [이동 취소] · 누르면 `DELETE .../staff/transfers/<그 id>` 1회 · `204` 뒤 명단 재조회 요청 · 에러 2종 문구. 지금 코드에서 버튼 부재를 먼저 봄(RED) |
| 2 | `W` | FE2 | 기한 전 `pending` → 두 버튼 켜짐 · 기한 지남 → 꺼짐 + 문구 · 열어 둔 채 시계가 기한을 넘으면 꺼짐(가짜 시계) · 결정된 건은 지금 그대로 |
| 3 | `W` | FE3 · FE5 | 링크 주소가 `map.naver.com` + 좌표 · 구글 주소 부재 · `stop_name=null` 행에 "승하차지 미지정"(`Ruling 381` — 계획 초안의 "정차지 없음" 을 정정) |
| 4 | `M` | FE6 | 운행 화면에서 그 회차 `route_changed` 방송 → 노선 조회 요청 1회 더 · 남은 승하차지 목록이 새 응답으로 바뀜 · 다른 회차 방송은 무시. 지금 코드의 "안 바뀜" 을 먼저 봄 |
| 5 | `T` | FE4 | 고치기 전 — CPU 부하 속 반복 실행에서 그 시험 실패를 눈으로 봄(시험 이름·실패 원문) · 고친 뒤 — 같은 부하로 **연달아 5회** 실패 0 |
| 6 | `T` | FE7 | BE4 판정 = 서버가 이미 `409 ALREADY_LINKED` 로 거절(`R36-BE`) → 수락 실패 시 역할별 문구 3종 · 대화상자·선택 유지(`Ruling 383`) |
| 7 | 갈래 전부 | 정적 분석·단위 | 웹 `tsc` 0 · lint 오류 0 · `vitest` 실패 0 / 앱 `flutter analyze` 0 · `flutter test --exclude-tags real_backend` 실패 0 |
| 8 | 조율자 | 병합 뒤 단독 전체 실행 | 웹 · 학부모 · 매니저 · `baraeda_core` · `baraeda_ui` 단위 + 실서버 계약(백엔드 8182 · DB `fe_rb`) — 실패 0 · 건너뜀 0 |
| 9 | 조율자 | 실서버 — FE1 | 백엔드 병합을 받은 뒤: 이동 저장 → 도착 회차 예정 명단에 `transfer_id` + `added` 행 → [이동 취소] → `204` → 그 행 사라짐 · 출발 회차에 학생 복귀 |
| 10 | 조율자 | 화면 | 브라우저 — [이동 취소] 흐름 · 기한 지난 승인 상세 · 비상 링크가 네이버 지도로 열림 / 시뮬레이터 — 매니저 운행 화면이 노선 변경 방송 뒤 다시 들어가지 않아도 바뀜 |
| 11 | 조율자 | 문서 | `USER_FLOWS` A-07 흐름에 취소 · `docs/TEST_SCENARIOS.html` 알려진 문제 K15·K17·K18 제거 |

### 5.13.4 판정

- **`Ruling 380`** — 비상 알림 위치 링크는 `https://map.naver.com/p/search/<위도>,<경도>`. 갈래 W 가 후보 5개를 실제 Chrome 으로 열어 비교 — 이 형식만 PC 화면에서 좌표 자리에 핀 + 도로명 주소를 띄웠다(서울·부산·수원 시청 3곳 일치). 버린 것: `m.map.naver.com/map.naver?…&markers=` (핀은 정상이나 PC 에서 모바일 화면 — **이 형식이 깨질 때의 대안**) · `/p/entry/coordinate/` · `?c=<경도>,<위도>,…`(새 지도가 좌표를 버리고 접속 위치로 연다) · 옛 `?lng=&lat=`(핀 부재). ⚠ 네이버가 문서화한 스킴이 아니라 **검색어에 좌표를 넣은 관측 동작**이다 — 링크가 엉뚱한 곳을 열면 여기부터 본다
- **`Ruling 381`** — 예정 명단에서 `stop_name` 이 `null` 인 학생의 표기는 **"승하차지 미지정"**. 정본 용어가 "승하차지"(정류장·정차지 아님 — `FEATURE_SPEC` 방향 전환)이고, 같은 화면의 승하차지 묶음 머리가 이미 이 문구를 쓴다. 표 칸과 묶음 머리가 상수 하나를 공유
- **`Ruling 382`** — 이동 대기 행(`transfer_id` 있음)의 "조정" 칸에는 **[이동 취소] 만** 둔다. 그 학생을 다시 옮기면 서버가 `TRANSFER_ALREADY_STAGED`(`§5.8`)로 거절하므로 [다른 버스로] 는 눌러도 실패할 버튼이다. 취소 뒤 명단을 다시 불러오면 [다른 버스로] 가 돌아온다
- **`Ruling 383`** — FE7 은 **문구만**. 백엔드 판정(`R36-BE` BE4 — `Student.linkAccount`·`Manager.linkAccount` 가 이미 연결된 레코드면 `409 ALREADY_LINKED`, 계정은 `pending` 그대로)을 받아 가입 승인 실패를 역할별 문구 3종으로 보인다(학생 · 매니저 · 학부모). 학생 후보에서 빼거나 표시하려면 `GET /staff/students` 응답에 계정 연결 여부 필드가 필요한데(`§5.11` 목록에 부재 · `account_id` 는 상세에만) **새 계약을 요구하지 않는다** — 실패 문구만으로 관계자가 바로 다른 학생을 고를 수 있고 대화상자·선택이 유지된다. 매니저 후보는 목록의 `account_id` 로 **이미 걸러 낸다**(`searchManagerCandidates`)
- **`Ruling 384`** — FE4 는 **부하 재현 대신 지연 주입 재현**으로 판정한다. 자연 부하(부하 평균 23~42 · 10코어 · 백엔드 창 전체 시험 동시)에서 고치기 전 6회 전부 465/465 로 재현 실패. 원인은 정적으로 확정 — `ChangeApprovalDetail.test` 의 `shouldAdvanceTime: true` 시험 2건이 실제 경과 시간만큼 가짜 시계를 흘려, 렌더가 1초 넘게 늦으면 "남은 시간 12분 30초" 가 29초로 바뀐다. 모의 응답을 1.2초 늦춘 사본에서 옛 시험이 실패하는 것을 봤다(부하의 결정적 대역). 두 시험을 시계 정지(`useFakeTimers` + 손으로 진행)로 바꾸고 단언은 그대로. ⚠ R35 의 "1건" 이 이 시험이라는 실패 로그는 없다 — 다음에 동시 실행에서 웹 1건 실패가 또 나오면 **시험 이름을 반드시 기록**한다. 남은 위험 — `findBy*` 기본 한도 1000ms 에 기대는 시험(최장 550ms · 근거 부족으로 미수정)

- **`Ruling 385`** — 학생 사진(`API_SPEC §5.11.1` · 백엔드 `Ruling 377` — `photo_url` 이 `/api/v1/files/photos/<파일명>` 상대 경로 + 로그인 토큰 필요)은 **클라이언트가 토큰을 실어 받아 온다**. 웹 — `<img src>` 가 헤더를 못 싣으므로 `apiFetchBlob`(기존 HTTP 클라이언트의 토큰 부착·401 재발급·에러 변환을 그대로 공유) → `useProtectedImageUrl` 이 blob → `createObjectURL`, 주소 변경·언마운트 때 `revokeObjectURL` + 요청 중단. 상대 경로는 `/api/v1` 머리를 떼고 API 베이스에 붙인다(중복 방지). Flutter — `baraeda_ui` 는 `baraeda_core` 에 의존하지 않으므로 `StudentRow.photoHeaders` 로 **앱이 헤더를 넘긴다**. 매니저 앱은 명단을 다시 불러올 때마다 저장된 access 토큰을 다시 읽어 헤더를 만든다(이미지 한 장을 위한 재발급 흐름은 두지 않는다 — 실패하면 이니셜 대체 표시, 다음 명단 재조회에서 새 토큰으로 다시 그림). **옛 절대 URL(공개 주소)은 그대로 쓰고 토큰을 싣지 않는다** — 서버 전환 전후 둘 다 동작. 버린 길: 토큰을 쿼리 문자열로(로그에 남음) · 화면마다 `fetch` 직접(토큰 경로 이원화) · 서비스워커 가로채기 · `CachedNetworkImage` 새 의존

- **`Ruling 386`** — 공유 패키지 검사 수정(갈래 XK)의 판단 4건. ①**재로그인 신호는 refresh 요청의 `401` 하나**(`API_SPEC §2.6` 이 401 만 적는다) — 400·403·5xx·연결 실패는 일시 장애로 보고 토큰을 남긴다. 그전에는 재발급이 네트워크 오류로 실패해도 토큰을 지워 오프라인 순간에 로그아웃됐다. ⚠ 서버가 다른 코드로 refresh 를 거절하는 경로가 생기면 그 세션은 안 지워진다 ②**요청 제한 시간 연결 10s · 송신 15s · 수신 30s**(그전 무제한 — 음영 구간에서 오프라인 대기열이 켜지지 않았다) ③**터치 크기** — `BaraedaButton.md` 44→48 · `BaraedaIconButton` 40→48 · 전화 버튼 38→48(`tapMin` 48). `sm`(36)은 **보류** — 매니저 명단 한 줄에 5개를 붙여 쓰는 조밀 배치라 48 로 올리면 화면 재설계가 필요 ④**라이트 테마 글자 색 4종**(`textTertiary`·`amberInk`·`redInk`·`statusIdle`) 대비 4.5:1 이상으로 올림. **보류** — 비텍스트 테두리 3:1(`borderDefault` 는 카드 외곽선·보조 버튼·타임라인 점이 함께 써서 값을 올리면 전 화면이 진해진다 — 입력·스위치 전용 토큰이 먼저) · 미사용 공용 위젯 8종의 앱 이관(대화상자·바닥 시트 본문 스크롤 문제를 먼저 고쳐야 함). ③④의 값은 디자인 킷 사본(`frontend/design-system/`)과 갈라진다 — `§4` 킷 불일치 목록 대상(정본 킷 갱신은 사용자 원격 작업)

- **`Ruling 387`** — 웹 관리 화면 검사 수정(갈래 XB)의 판단 2건. ①**구간 변경 [승인] 은 확인 단계를 거친다**([승인] → [승인 확정]) — 가입 승인 대화상자와 같은 형태로 맞춤. 사양이 단계를 정하지 않아 되돌릴 수 없는 조작(노선 재배포)에 확인을 둔 UX 추천안(`USER_FLOWS UF-M-02` 반영) ②**PATCH 로 선택 항목을 지울 수 없는 것**(학생 성별·생년월일, 스케줄 소요 시간 — 서버가 `null`=유지로 읽는다)은 **폼이 "지울 수 없습니다" 로 막는다**(저장은 성공한 것처럼 끝나던 것을 막음). 서버가 "`null` 명시 = 지움" 계약을 정하면 그 검사 3곳을 지운다 — 백엔드 요청(`API_SPEC §5.9·§5.10·§5.11`). ⇒ **② 는 백엔드 `Ruling 390`(키 없음=유지 · 선택 항목 `null`/빈 문자열=지움)으로 대체** — 갈래 XZ 가 폼 차단 3곳을 빼고 지운 값을 실제로 보낸다(원장 Z-01)

- **`Ruling 388`** — 매니저 앱 **로그아웃·계정 전환 때 오프라인 대기열과 회차 캐시를 비운다**(검사 F06-02 — 대기열에 계정 열이 없어, 남겨 두면 앞 계정의 승하차 처리가 다음 계정 토큰으로 재생된다). 대가로 오프라인에서 로그아웃하면 못 보낸 처리가 사라지므로 **대기열이 비어 있지 않을 때만** 로그아웃 확인 창에 "아직 보내지 못한 처리 N건은 버려집니다" 를 띄운다(원장 M2-01). 대기열 재생은 `401`·`429`·`5xx`·비 JSON 응답이면 행을 **지우지 않고 남긴다**(그전에는 확정 거부로 보고 영구 삭제) — 결정적으로 `500` 을 내는 행은 대기열 화면의 [삭제] 로 치운다(재시도 횟수 상한은 사양에 없어 두지 않음)

- **`Ruling 389`** — 웹 공용 검사 수정(갈래 XC)의 판단 3건. ①**공용 `Dialog` 는 바깥 클릭으로 닫지 않는다**(전 화면) — 입력 중이던 폼이 스치는 클릭에 사라지는 것을 막는다. Esc·[취소]·[닫기] 로 닫고, 대화상자 역할(`role="dialog"`·`aria-modal`·제목 연결)·열릴 때 초점 이동·`position: fixed` 를 함께 넣었다(검사 C00-04·F04-04). 되돌리려면 `Dialog` 에 옵션 한 줄 ②**관계자 웹 가입 대기·거절 화면에도 [상태 다시 확인]**(`UF-X-02` 를 웹에 적용) · 거절 화면의 재신청은 확인 한 단계 ③**강제 확정 표의 열 이름** — `depart_time` "출발 시각" · `est_depart_time` "출발 예정(추정)"(§6.8 정의 — 확정 시각이 아님). 확정 시각 열은 목록 응답에 `confirm_at` 이 없어 두지 못했다(백엔드 요청)

- **`Ruling 393`~`395`**(백엔드 `docs/archive/rounds/be-rounds-r22-r41.md §8.69` R37) — 프론트 검사에서 서버로 넘긴 3건이 닫힘: 관제 `runs[].confirm_at`(F03-11) · `login-history` `block_action`(N-05) · `emergency_raised` 의 `academy_id`·`academy_name`(F03-05). 웹 반영은 같은 갈래.

### 5.13.5 진행

- 갈래 W — 병합 `2bf99f90`(커밋 `f90d50d8`..`0e45ae33`). 작업 창 실측: `vitest` 89파일 462 통과 · 실패 0 · 건너뜀 0 · lint 0 · `tsc` 는 기존 `layout.tsx LayoutProps`(`.next` 생성 타입 부재) 1건뿐 · 변형 12건 전부 새 시험이 잡음. ⚠ FE1 API 층은 구현 전 실패를 못 봤고 구현 뒤 변형 3건으로 대신 확인(보고서 2항)
- 갈래 M — 병합 `6253d9e2`(커밋 `f130f7ab`..`c4567f91`). `WsEventType.routeChanged('route_changed')` 추가 · 매니저 채널이 받으면 노선·명단·운행 명단을 다시 불러옴(기존 재조회 4벌을 `_invalidateRunViews` 1벌로) · 다른 회차 봉투 무시. 매니저 239 · core 52 통과 · 실패 0 · 건너뜀 0 · analyze 0 · 변형 4건 전부 잡힘. ⚠ **서버가 매니저 채널로 `route_changed` 를 방송하지 않는다** — ②구간 승인 재최적화·경유 지점 배포 두 경로에서 화면이 아직 안 바뀐다(③미등원은 `rider_changed` 로 이미 반영). 백엔드에 방송 추가 요청 → main 이 백엔드 창에 중계(2026-09-30). 서버 병합 뒤 목표 10 의 시뮬레이터 확인
- 갈래 T — 병합 `a7484e0e`(커밋 `9bd1b25a` FE7 · `e87a1d68` FE4). 웹 465 통과 · 실패 0 · 건너뜀 0(고친 뒤 자연 부하 연달아 5회 전부 465/465) · lint 0 · `tsc` 새 오류 0. 판정 `Ruling 383`·`384`
- **병합 뒤 단독 전체 실행(목표 7·8, 조율자 · `fe-main` `f2367ea8`)** — 단위: 웹 239파일 **465** · `baraeda_core` **52** · `baraeda_ui` **85** · 학부모 **183** · 매니저 **239**(Flutter 는 `hidden:false` 기준) — 실패 0 · 건너뜀 0 · `flutter analyze` 4종 0 · lint 0 · `tsc` 는 기존 `layout.tsx LayoutProps` 1건뿐. 실서버(8182 · DB `fe_rb` · 백엔드는 `ed60f5d8` 코드 — BE1·BE2 미포함): 웹 14파일 **77** · core **9** · 매니저 **26** · 학부모 **28** — 실패 0 · 건너뜀 0. 재실행 2건: ①웹 `getChangeApprovalDetail(1)` — 네이버 Directions 15 한도 400 → 경로 회로 차단(`Ruling 361`, 환경) → 서버를 `--app.routing.map.max-waypoints=7 --app.routing.map.naver.driving-path=/map-direction/v1/driving` 로 다시 띄워 그 1건만 통과 ②학부모 `real_backend_p5` 소프트 삭제 학생 1건 건너뜀 — 조율자가 `--dart-define=FIXTURE_DB=fe_rb` 를 빠뜨림 → 붙여 그 1건만 통과. 시나리오 문서 알려진 문제 K15·K17·K18 제거(목표 11)
- **남은 것(서버 병합 대기)** — 목표 9(FE1 실서버 이동 취소) · 목표 10 화면 확인(브라우저 [이동 취소] · 시뮬레이터 매니저 노선 변경 방송 — 백엔드 `route_changed` 방송 추가 요청 중)
- **main 병합** `3ab3e267`(R36-FE 1차) · 백엔드 R36-BE 받음 `99852822`
- **목표 9 ✅ 실서버 FE1**(8182 · `99852822` 코드 · `fe_rb`) — `POST /staff/students/{id}/transfer`(1104 → 1106, 주소) `201 transfer_id="2"` → 1106 예정 명단 김철수 `change=added` · `transfer_id="2"`(문자열) → `DELETE /staff/transfers/{transferId}` `204` → 두 명단 원복(출발 회차에 학생 복귀) → 같은 id 재삭제 `404 TRANSFER_NOT_FOUND`
- **목표 10 브라우저 ✅ 일부**(5173 → 8182 · Chrome) — 2호차 하원 행 "추가 · 대기 · [이동 취소]" → 확인 대화상자("김철수 학생 — 이동 취소" · [돌아가기]·[이동 취소하기]) → `DELETE /staff/transfers/{transferId}` 1회 → 명단 재조회 → 김철수 행 사라짐(`fe1-*.png`) · 비상 알림 "지도에서 보기" = `https://map.naver.com/p/search/37.568,126.9795` · `_blank`. FE2(기한 지난 대기 건)는 서버가 기한에 곧바로 자동 거절해 화면으로 만들 수단이 없어 **가짜 시계 단위 시험으로만** 판정. ⚠ 확인 중 새 결함 5건(조작 뒤 선택 회차가 첫 회차로 튐 · 노선 없는 도착 회차의 빈 승하차지 목록 · 오해 부르는 "확정되지 않은 회차" 띠 · 공용 대화상자 역할 부재 · 취소 문구) → 전체 검사 원장 `frontend/report/review-2026-09-30/C00.md` 로 수정 라운드에서 처리. 시뮬레이터(FE6)는 백엔드 방송 병합 뒤
- **백엔드 3차 받음** `5acf319b`(main `b42b866d` — 사진 서버 `Ruling 377` · 374~378 서버 · BR 52건). 8182 를 새 코드·새 스키마로 다시 띄워 실서버 계약: 웹 **76/77** · core 9 · 매니저 26 · 학부모 28(`FIXTURE_DB=fe_rb`) — 실패 1 = 웹 `admin` `createAcademy·updateAcademy` 가 가짜 주소 "서울시 어딘가" 로 새 주소 검증(`Ruling 374`)에 `422` → 시험 데이터 수정 항목(원장 `N-04`, 갈래 XC)
- **목표 10 시뮬레이터 ✅ FE6**(iPhone 17 Pro · 매니저 앱 → 8182 · 백엔드 `route_changed` 방송 `Ruling 373` 포함) — `driverA1` 로 1호차 하원(확정) 운행 화면을 연 채, 관계자 API 로 경유 지점 배포(`POST /staff/runs/{runId}/waypoints` 미리보기 → `apply=true`) → **약 6초 뒤 화면을 다시 열지 않았는데** 지도에 새 핀 3 과 그쪽으로 늘어난 도로 경로가 그려지고 남은 승하차지 순서가 바뀜(중앙로 → 그린빌라 가 그린빌라 → 중앙로 — 재최적화 반영). 경유 지점은 학생이 없어 명단 `stops[]`(남은 승하차지 목록)에는 없고 노선(§4.3)에만 있다 — 정의대로(`fe6-1-before.png` · `fe6-2-after.png`)
- **사진 실서버·화면 ✅(`Ruling 385`)** — 김철수에게 사진 등록 → `photo_url=/api/v1/files/photos/<uuid>.png` · 토큰 없이 `401` · 토큰으로 `200 image/png` → 관계자 웹 학생 수정 폼의 사진이 `blob:` 주소로 그려짐(`naturalWidth 40` = 올린 40×40 이미지) · 사진 요청에 `Authorization: Bearer` 실림(`frontend/report/r36fe/photo-web.png`)

### 5.13.6 ② 프론트 전체 검사 → 수정 라운드 (2026-09-30)

- **검사** — 읽기 전용 창 7개(F01 웹 운행·지도·비상·보고 14 · F02 웹 관리 6기능 17 · F03 웹 인증·관리자·학원·알림·라우팅 17 · F04 웹 공용 9 · F05 학부모 앱 14 · F06 매니저 앱 18 · F07 공유 패키지 13) + 조율자 실서버·브라우저 확인 C00 5 = **107건**(CRITICAL 1 · HIGH 28 — 결과 파일 `^### ` 머리 직접 계수). 지시 `frontend/report/review-2026-09-30/COMMON.md` · 원장 `LEDGER.md`(git 밖)
- **수정** — 갈래 10개 순차 병합(동시 2): XA `1942e114` · PH(사진 `Ruling 385`) `b8620364` · XP `82b4816e` · XK `3cf39973` · XB `443f3cb7` · XM `e8e6d273` · XC `191c7b12` · XW2 `626d6ffc` · XF `ad32bc5e` · XZ `42279c0e`. 검사 중·병합 뒤 나온 새 항목(백엔드 계약 변경 N·Z · 앱 이관 K·M2·W2 · 병합 뒤 발견 X) 포함 원장 **133행 · 열린 항목 0** — 기각 5(주장 불성립·이미 막힘 — 근거는 각 `FIX-*.md`) · 보류 4(`Ruling 386` 디자인 결정 3 · 중복 폴링 F01-13)
- **판정** `Ruling 385~389`(위 `5.13.4`)
- **병합 뒤 단독 전체 실행(조율자 · `42279c0e` + 시험 1건 수정 `15802dae`)** — 단위: 웹 108파일 **650** · `baraeda_core` **69** · `baraeda_ui` **106** · 학부모 **220** · 매니저 **302** — 실패 0 · 건너뜀 0 · `flutter analyze` 4종 0 · `tsc` 0 · lint 0. 실서버(8182 · `fe_rb` · 백엔드 `a1ca4e6a` · Directions 5): 웹 **77** · core **9** · 매니저 **26** · 학부모 **28** — 실패 0 · 건너뜀 0. core 1건(로그아웃 계약)은 `Ruling 386` 매퍼 변경(`401 TOKEN_EXPIRED` → `Failure.unauthenticated`)을 모르는 옛 단언이라 단언을 새 분류로 고침(`15802dae`) — 서버 로그아웃 무효화를 빼는 결함을 심으면 실패하는 것 확인
- **화면** — 브라우저: 사이드바 진입 뒤 고른 회차가 7초 갱신 뒤에도 유지(C00-01) · 대화상자 `role=dialog`·`aria-modal`·제목 연결·초점 안쪽 · 바깥 클릭에 안 닫히고 Esc 로 닫힘(`Ruling 389`) · 학생 사진 blob 표시(`Ruling 385`). 시뮬레이터: FE6(위)
- **남은 것** — 백엔드 요청 3건(비상 방송 `emergency_raised` 에 학원 식별 · 접속 이력 `block_event` 을 block/unblock 으로 · 강제 확정 목록 `confirm_at`) · `Ruling 386` 보류 3건(비텍스트 테두리 대비 · `BaraedaButton.sm` 36 · 미사용 공용 위젯 8종) · 눈 확인이 더 필요한 것 — 학부모 지도 카메라 수동 조작 유지(F05-09) · 매니저 운행 전 위치 권한 배너(M2-02)는 실기기

## 5.14 `R39` 목표 표 — 지도의 강제 경유 지점·미경유 표기 (2026-09-30 계획 · **2026-09-30 완료** — 결과 `5.14.5`)

사용자 결정(2026-09-30) *"지도에 표기해줘"* — `PRD §10` 오픈 이슈 Q(강제 노선의 지도 표현, RTE-06·08·10) 해소. 기준 HEAD `9776c82f`(`mskim98/r39`). 이전 화면 확인(`frontend/report/r37-it/ios-mgr-06-map-before-after.png`)에서 경유 지점을 배포해도 **경로선만 우회하고 경유 지점 전용 표시가 없었다** — 모든 마커가 같은 초록 번호 핀. 판정 `Ruling 400~402`.

### 5.14.1 지도 전수 표 (`graft grep` 으로 `MapMarker` 생성 지점을 센 결과 — 2026-09-30)

| # | 제품 · 화면 | 마커 생성 지점 | 쓰는 API | 경유 지점 · 취소를 알 수 있나 |
|:-:|---|---|---|---|
| 1 | 관계자 웹 · 금일 운행 상세 | `TodayRunPage.tsx` → `buildRouteDisplayState`(`features/map/routeDisplayState.ts`) | `GET /staff/runs/{id}/route`(§5.19) `stops[]` | ✅ `is_waypoint`(신설 `Ruling 400`) · `change=skipped` |
| 2 | 관계자 웹 · 운행 관리(대시보드) | `DashboardPage.tsx` → 같은 `buildRouteDisplayState` | 같음 | ✅ 같음 |
| 3 | 관계자 웹 · 전체 관제 | `MonitoringPage.tsx` → 같은 `buildRouteDisplayState` | 같음 | ✅ 같음 |
| 4 | 관계자 웹 · 구간 변경 승인 미리보기 | `ChangeApprovalDetail.tsx` `stopsToMarkers` | `GET /staff/approvals/{id}` `route_preview.stops_before/after`(§5.5) | ❌ 경유 지점은 좌표가 `null` 이라 **마커를 못 그린다**(`API_SPEC §5.5`·기존 코드가 건너뜀) — `Ruling 402` |
| 5 | 관계자 웹 · 고정 노선 편성 | `RouteMapPanel.tsx` | `GET /staff/routes/{id}`(§5.9) | 해당 없음 — 회차 아닌 고정 노선이라 경유 지점·미경유가 없다 |
| 6 | 매니저 앱 · 운행 화면 가운데 지도 + 노선 지도 화면 | `route_map_view.dart` `RouteMapView`(둘이 공유) → `MapSurface` | `GET /runs/{id}/route`(§4.3) `stops[]` | ✅ `is_waypoint` · `change=skipped` |
| 7 | 학부모·학생 앱 · 버스 위치 지도 | `live_map_screen.dart` — **버스 마커 하나뿐**(승하차지·경유 지점을 안 그린다) | `GET /students/{id}/bus-position`(§3.11) | 해당 없음 — 그릴 정차 마커가 없다. 정차 마커를 새로 그리는 것은 이 회차 범위 밖 |

- 웹은 경유 지점을 **배포하는 화면이 없다**(`RouteOptimizeConfirmDialog` 주석뿐) — 경유 지점은 API(§5.15)로만 만들어진다. 지도 표기 대상은 위 1~3
- 계수 명령: `graft grep "MapMarker"`·`grep -rlE "data-marker-id|NaverMap|NMarker" apps packages`

### 5.14.2 변경 목록

| # | 지금 | 할 일 |
|:-:|---|---|
| R39-1 | §4.3·§5.19 `stops[]` 가 승하차지·경유 지점을 같은 모양으로 싣는다 — `stop_id`(=`run_stop.id`)·`student_count` 로 가를 수 없다 | **서버 응답에 `stops[].is_waypoint`(boolean, `●`) 추가**(`Ruling 400` — main 승인) |
| R39-2 | 웹·매니저 앱 마커가 전부 초록 번호 핀 | 경유 지점 = 번호 없는 **다른 모양** 마커 + 글자 "경유" · 미경유(`skipped`) = 흐리게 + 회색 + 번호 취소선 |
| R39-3 | 매니저 명단 머리(`{seq}. 이름`)와 지도 핀이 서버 `seq` 를 그대로 쓴다 — 경유 지점이 낀 노선은 번호가 1·3·4 로 **건너뛴다** | 지도 핀과 명단 머리를 **경유 지점을 뺀 연속 번호**로 통일(`Ruling 400`) |

### 5.14.3 목표 표 — 전항 통과가 완료 조건

| # | 완료 조건 | 검증 |
|:-:|---|---|
| 1 | 지도 전수 표(5.14.1) | 이 절의 표 · `graft grep` |
| 2 | 지도마다 경유 지점·미경유 표기 — 앱은 위젯 시험, 웹은 vitest. **RED 먼저** | 커밋 뒤 경유 지점 판별을 항상 `false` 로 바꾸면 그 시험만 실패 |
| 3 | 번호 규칙 — 경유 지점이 낀 노선에서 지도 핀 번호 = 명단 번호(웹 · 매니저) | 위젯·vitest 시험 |
| 4 | 백엔드 — 경유 지점 배포 뒤 §4.3·§5.19 응답에서 그 항목만 `is_waypoint=true` | `backend/scripts/test.sh --tests '*RunRouteControllerTest*'`(RED 확인 · 항상 `false` 변형이 그 시험만 실패) |
| 5 | 화면 — 웹 각 지도(1~3) · iOS 시뮬레이터 매니저 앱 운행 지도 스크린샷 | `frontend/report/r39/` |
| 6 | 웹 vitest 전체 실패 0·건너뜀 0(실서버 시험 제외 패턴 `§5.9.4` 목표 5) · `tsc` 0 · lint 0 · 손댄 Flutter 패키지 시험 실패 0·건너뜀 0 · `flutter analyze` 0 | 명령·수치는 5.14.5 |
| 7 | 정리 — `:8210`·`:3000` 종료 · `it_r39` 연결 0 뒤 `DROP`(`FORCE` 금지) · 띄운 시뮬레이터만 종료 · 잔여 `flutter_tester` 0 | 명령·수치는 5.14.5 |

### 5.14.4 판정

- **`Ruling 400` — 강제 경유 지점 표기 · 번호 규칙 · 서버 계약 추가** (2026-09-30, main 승인 · 사용자 결정 *"지도에 표기해줘"* 의 구체화)
  - **경유 지점** — 승하차지와 **모양이 다른** 번호 없는 마커 + 글자 **"경유"**. 색만으로 가르지 않는다(접근성 — 모양·글자가 구분 수단). 출발·도착 칩(어두운 채움)과도 달라야 해서 **흰 바탕 · 회색 테두리 칩**으로 한다
  - **미경유(`change=skipped`) 승하차지** — 흐리게(불투명도 낮춤) + 회색 핀 + 번호 취소선. 명단의 빨강 취소선과 같은 뜻 — 색이 아니라 취소선·흐림이 수단
  - **번호** — 지도 핀은 서버 `seq` 를 그대로 쓰지 않고 **경유 지점을 뺀 연속 번호**(승하차지·학원 항목의 서버 순번 순위)로 매긴다. 근거: 명단(§4.2)은 `Ruling 398` 로 경유 지점을 싣지 않고 서버 `seq` 는 경유 지점 자리를 건너뛴 채라, **둘 다 서버 값을 쓰면 번호가 1·3·4 로 비어 "2번은 어디" 가 된다.** 그래서 매니저 명단 머리도 같은 연속 번호로 바꾼다 — 지도와 명단이 같은 규칙(서버 `seq` 순 순위)이라 어긋나지 않는다
  - **서버 계약 추가**(`API_SPEC §4.3`·`§5.19` `stops[].is_waypoint`, boolean `●`) — 경유 지점 항목만 `true`. 전에는 승하차지·경유 지점이 모든 필드에서 같은 모양이라(`stop_id` 둘 다 `run_stop.id` · 경유 지점 `student_count=0` 은 인원 없는 승하차지와 같다) 프론트가 추론할 수단이 없었다. 조율자가 프론트 추론(명단 대조)안을 버리고 서버 추가안을 택했다 — 명단을 함께 불러오지 않는 웹 3화면에 추가 호출이 필요하고 취약하기 때문. 기존 필드는 그대로라 하위 호환. `API_SPEC §1.13` 표의 *"경유 지점 `stop_id` 는 항상 `null`"* 은 코드(`run_stop.id`, `Ruling 327`)와 어긋나 **코드에 맞춰 정정**
- **`Ruling 401` — 강제 추가(§5.7)는 지도에 표기하지 않는다.** 강제 추가는 **탑승자(학생) 단위**다 — 노선 응답의 승하차지 항목 `change` 는 `RunStop` 에서 오고, 코드에서 `RunStop.change` 에 값을 넣는 곳은 `skipped`(미승차 반영) 하나뿐이다(`added` 는 명단의 `RunRider.change`). 그래서 노선 응답에 판별 근거가 없다(로컬 시드 V2 만 `run_stop.change='added'` 를 직접 넣어 응답에 `added` 가 나오는 회차가 있다 — 운영 경로에는 없다). **지도는 승하차지 단위 · 강제 추가는 명단의 초록으로** 본다. 운영 경로에서 `change=added` 가 실리는 날이 오면 그때 지도 표기를 정한다
- **`Ruling 402` — 승인 미리보기(§5.5) 지도의 경유 지점은 마커를 그리지 않는다.** `route_preview.stops_*` 의 경유 지점 항목은 좌표가 `null` 이다(기존 코드가 건너뜀). 좌표를 만들어내지 않는다 — 억지로 맞추면 삭제된 승하차지가 조용히 빠진 지도가 나간다. 경로선은 그대로 우회하고 정차지 표에는 이름이 있다

### 5.14.5 진행

- (착수) 백엔드 `is_waypoint` — RED 확인: `RunRouteControllerTest` 새 시험이 `JSON path "$.data.stops[?(@.name == '주유소')].is_waypoint"` 부재로 실패 → 구현 뒤 `RunRouteControllerTest` 7 · `StaffRunRouteControllerTest` 12 통과 · 실패 0
- 백엔드 `36430c17` — 변형(경유 분기 `true`→`false`) 시 새 시험 1건만 실패(원복·트리 확인)
- 웹 `d9adab97` — vitest 109파일 **662** 통과 · 실패 0 · 건너뜀 0(`realBackend`·`wsRealBackendAuth` 제외) · `tsc` 0 · lint 0 · 변형 5건 전부 해당 시험만 실패. 손댄 파일 밖 1건: `StudentTransferDialog` 의 승하차지 선택지에서 경유 지점 제외(같은 필드를 쓰는 곳 — 시험 1건)
- 매니저 앱 `0d7ba272` — 시험 **312**(hidden:false) 통과 · 실패 0 · 건너뜀 0(`--exclude-tags real_backend`) · `flutter analyze` 0 · 변형 6건 전부 해당 시험만 실패. 경유 지점 배포 뒤 제거돼 이름·좌표가 `null` 인 행은 파서가 건너뜀(전에는 노선 전체 파싱 실패 가능)
- 화면 ✅ — 웹(:3000 → :8210 · `it_r39`): 금일 운행 상세·운행 관리·전체 관제 3화면 모두 마커 `stop-N "1"` · `waypoint-N "경유"` · `stop-N "2"`(불투명도 0.55 — 서버 seq 는 1·3) · 출발·도착 칩. iOS 시뮬레이터(iPhone 17): 매니저 앱 운행 화면·노선 지도 — 초록 핀 1 · 흐린 핀 2 · "경유" 칩 · 미경유 안내 띠. 스크린샷 `frontend/report/r39/`
- 승인 미리보기(§5.5) 지도 · 학부모 앱 지도 · 고정 노선 편성 지도는 표기 대상 없음(5.14.1)

## 5.15 `R41-UI` 목표 표 — 공유 UI 패키지 보류 3건 (2026-09-30 계획 · 기준 HEAD `bd287dfb` `mskim98/r41-ui` · 판정 `Ruling 403~405`)

`Ruling 386` 이 보류한 3건 — 사용자 지시 *"작은 개선 부분 전부 수정"*. 근거 `frontend/report/review-2026-09-30/F07.md` F07-09·10·11 · `FIX-XK.md` "미처리로 남긴 것".

### 5.15.1 변경 목록 (착수 전 실측 — `graft grep`·`grep -rlw` 로 센 결과)

| # | 지금 | 할 일 |
|:-:|---|---|
| R41-A | `borderDefault`(라이트 흰 배경 1.89 · 다크 카드 2.09)가 입력칸·스위치 꺼짐 트랙·보조 버튼·선택 칩과 카드 외곽선·타임라인 점·시트 손잡이에 **같이** 쓰인다 | 조작 요소 경계 전용 토큰 `borderControl` 신설 — 입력칸 3종(`input`·`textarea`·`select`) · 스위치 꺼짐 트랙 · 보조 버튼 · 지연 선택 칩 · 코드 입력칸만 옮긴다. 카드·타임라인 점·손잡이는 `borderDefault` 그대로 |
| R41-B | `BaraedaButton.sm` 36 — 앱 2종 **19곳**에서 쓴다(매니저 명단 `roster_screen.dart` 5곳 포함). 누르는 영역이 보이는 크기와 같다 | 보이는 크기 36 유지 · **누르는 영역만 48×48 이상**(이웃 영역과 겹치지 않음) |
| R41-C | 지시서의 "미사용 11종" 은 **낡은 목록**이다 — 웹 전용 3종(`RosterTable`·`SideNav`·`PageHeader`)은 `be58803d` 가 이미 삭제했고 `BaraedaSegmentedControl`(앱 5곳)·`BaraedaCodeInput`(`child_link_screen`)은 앱이 이미 쓴다. 실제 미사용은 `BaraedaDialog`·`BaraedaBottomSheet`·`BaraedaIconButton`·`BaraedaCheckbox`·`BaraedaSearchField`·`BaraedaTabBar` 6종 | 대화상자·바닥 시트 결함 3종(본문 스크롤 · 뒤로가기 · 낭독 배경 차단) 수정 → 앱 Material `AlertDialog` 4곳 · `IconButton` 1곳 · `showModalBottomSheet` 1곳을 공용판으로 · 앱이 Material `SegmentedButton` 을 쓰는 2곳을 `BaraedaSegmentedControl` 로 · 앱에 같은 일을 하는 Material 코드가 없는 `BaraedaCheckbox`·`BaraedaSearchField`·`BaraedaTabBar` 삭제 |

### 5.15.2 목표 표 — 전항 통과가 완료 조건

| # | 완료 조건 | 검증 |
|:-:|---|---|
| 1 | A — `borderControl` 신설 · 라이트/다크 대비 계산표(보고서) · 입력칸·스위치가 새 토큰을 쓴다는 위젯 시험. **RED 먼저** | `flutter test test/theme test/widgets/forms` · 커밋 뒤 `borderControl` 을 `borderDefault` 값으로 되돌리면 그 시험만 실패 |
| 2 | B — `sm` 버튼 누르는 영역 ≥ 48(`tester.getSize`·빗나간 탭 시험) · 이웃 버튼 누르는 영역 겹침 0. **RED 먼저** | 새 시험 · 변형(`sm` 을 36 으로 되돌림)이 그 시험만 실패 |
| 3 | C — 삭제 파일 목록 + 삭제 전 `grep -rlw` 0건 증거 · 공용판 결함 3종 시험(긴 본문 스크롤 · 뒤로가기 · 낭독 배경 차단) **RED 먼저** · 옮긴 곳마다 시험(확인·취소 · 뒤로가기) | 새 시험 · 변형이 그 시험만 실패 |
| 4 | 화면 — 매니저 명단(버튼 줄) · 라이트 입력칸 화면(학부모 로그인) · 옮긴 대화상자 1개 전/후 스크린샷 | `frontend/report/r41/ui-*.png` |
| 5 | 손댄 패키지(`baraeda_ui`·매니저·학부모) `flutter test` 실패 0·건너뜀 0 · `flutter analyze` 0 | 명령·수치는 5.15.4 |
| 6 | 정리 — `:8250` 종료 · `it_r41u` 연결 0 뒤 `DROP`(`FORCE` 금지) · 시뮬레이터 `2F82BAEA…` 종료 · 잔여 `flutter_tester` 0 | 명령·수치는 5.15.4 |

### 5.15.3 판정

- **`Ruling 403` — 조작 요소 경계 전용 토큰 `borderControl`** (A). 카드 외곽선은 장식 구분선이라 WCAG 비텍스트 3:1 대상이 아니다 — 입력칸·스위치·버튼 윤곽처럼 **그 선이 없으면 조작 요소를 못 찾는 자리**만 3:1 이상으로 올린다. `borderDefault` 값을 올리는 안은 버림(전 화면 테두리가 진해진다). 킷에 없는 토큰이라 `§4` 불일치 목록에 행 추가
- **`Ruling 404` — `sm` 버튼은 보이는 크기 36 · 누르는 영역 48** (B). 레이아웃을 바꿔 보이는 크기를 48 로 키우는 안은 버림: 앱 2종 19곳(명단 한 줄 3~4개 · 머리말 · 지도 위 칩)이 전부 커져 조밀 배치가 깨지고, 한 위젯 안에서 끝나는 해법이 있다. 대가 — 세로 배치가 12 커진다(누르는 영역이 레이아웃 박스 안에 있어야 이웃과 겹치지 않으므로 Material `MaterialTapTargetSize.padded` 와 같은 방식)
- **`Ruling 405` — 공용 위젯은 앱이 같은 일을 할 때만 둔다** (C). 공용판의 결함을 고친 뒤 앱의 Material 호출을 공용판으로 옮긴다. 옮길 곳이 없는 것(`BaraedaCheckbox`·`BaraedaSearchField`·`BaraedaTabBar`)은 삭제 — 낡은 공용 코드는 결함이 잠복한다(F07-11)

### 5.15.4 진행

- (착수) 목표 표 고정
- **A** `2262e6f0` — `borderControl` 라이트 `#828C88`(흰 카드 3.47 · 페이지 바탕 3.17) · 다크 `#6F7A76`(카드 3.77 · 페이지 바탕 4.18 · 떠 있는 면 3.35). 새 시험 위젯 14 + 대비 8 — 입력칸 테두리를 `borderDefault` 로 되돌리면 입력칸 시험 2건만 실패 · 라이트 값을 `stone300` 으로 되돌리면 대비 시험 3건만 실패
- **B** `dd3a158b` — `sm` 보이는 크기 36 · 누르는 영역 48×48. 시험 7건 — 영역 넓히기를 끄면 2건 · `HitTestBehavior.opaque` 를 `deferToChild` 로 바꾸면 1건만 실패
- **C** 공용판 결함 3종 `f13ccf5f`(시험 7건 RED → 통과 · 변형 5종이 각자 해당 시험만 실패) · 삭제 3종 `3afe13bf`(삭제 전 `grep -rlw` 앱·패키지 lib 0건) · 앱 이관 `63d67f61`(`AlertDialog` 4 · `IconButton` 1 · `showModalBottomSheet` 1 · `SegmentedButton` 2) · 화면에서 발견한 버튼 줄 결함 `f52b4cf8`
- 손댄 패키지 — `baraeda_ui` **148** · 매니저 앱 **313** · 학부모 앱 **220**(`--exclude-tags real_backend`, `hidden:false` 만 센 값) 전부 실패 0 · 건너뜀 0 · `flutter analyze` 0
- 화면 ✅ — iOS 시뮬레이터 전·후 14장 `frontend/report/r41/ui-*.png`

### 5.15.5 `R42` — R41 후속 마무리 (2026-09-30 · 기준 HEAD `2b0185cc` `mskim98/r42`)

- **목표** — ①매니저 운행 화면(확정·출발 전) 위치 권한 배너가 아래 고정 버튼 경계에 잘리는 결함 수정 ②`sm` 버튼 누르는 영역 48 이 닿은 화면 확인 ③R41-CHK·R41-UI 병합 트리 전체 시험
- **① 결과** — 원인은 여백 부족이 아니라 **배너가 스크롤 본문 맨 아래(지도·변경 배너 뒤)에 있어 첫 화면에서 스크롤 뷰 하단 경계에 잘림**(스크롤하면 전체가 보임). 배너를 스크롤 본문에서 빼 **버튼 바로 위 고정 영역**(오류 배너와 같은 자리)으로 이동. 시험 — 360×640 에서 배너 글자의 아래 끝 ≤ `운행 시작` 버튼의 위 끝(수정 전 621 > 572 로 실패 → 통과) · 배너를 본문으로 되돌리면 그 시험 1건만 실패
- **② 결과** — 시뮬레이터로 본 곳: 매니저 비상(새로고침 · 취소) · 오프라인 대기열(삭제) · 학부모 홈(로그아웃 · 설정 · 일정 · 자녀 추가) · 학생 홈(부모 연결 코드) · 실시간 버스 위치(버스 위치로) · 노선 상세(전화하기) · 알림 더 보기. 지도 위 칩 밀림 · 고정 높이 행 잘림 **0건**. 못 본 것 — 조회 실패 때 뜨는 `다시 시도`(AlertBanner 안 `sm`)
- **③ 결과** — `baraeda_core` 69 · `baraeda_ui` 148 · 매니저 앱 315 · 학부모 앱 221(`--exclude-tags real_backend`, `hidden:false` 만) 전부 실패 0 · 건너뜀 0 · `flutter analyze` 4곳 0

### 5.15.6 `R43` — 공용 버튼이 `block` 이 아닌데도 가로 전체로 늘어나는 결함 (2026-09-30 · 기준 HEAD `b756a743` `mskim98/r43`)

- **목표** — `BaraedaButton` 이 `block: false` 일 때 내용 폭이 되게 한다(디자인 킷 `Button.jsx` 는 `block ? 'flex' : 'inline-flex'`)
- **원인** — 안쪽 `Ink` 의 자식 `Center` 에 `widthFactor` 가 없어, 부모가 폭 상한만 줘도(Column 기본 정렬 · `Align` · `EmptyState`·`AlertBanner` 의 action 자리) 그 폭 전체로 늘어남. 크기 3종 전부 해당. 바깥 누르는 영역 `Center` 는 R41-UI 가 이미 고침
- **수정** — 안쪽 `Center(widthFactor: block ? null : 1)` 한 줄. 시험 `button_width_test.dart` 10건(크기 3종 × Column·Align·block + sm 누르는 영역 48) — 수정 전 6건 실패(내용 폭) → 통과 · 수정을 되돌리면 그 6건만 실패
- **사용처 감사** — 호출 69곳(앱 2종 · 패키지, 테스트 제외): `block: true` 12 · 부모가 폭을 강제(`Column stretch`·목록 항목)해 **변화 없음 23** · `Row`·`Wrap` 안이라 폭 제약 없음 **변화 없음 15** · **부모가 폭 상한만 줘서 늘어나던 곳 19 → 내용 폭으로 변경**(매니저 5 · 학부모 14). 19곳 전부 의도가 내용 폭(가운데 재시도 버튼 · 배너 안 작은 버튼 · 왼쪽 정렬 칩) — **`block: true` 를 새로 붙인 곳 0**
- **`Ruling 406` — 전폭 버튼은 부모가 아니라 `block: true` 로 요청한다** — 공용 버튼은 `block` 이 아니면 내용 폭. `Column(crossAxisAlignment: stretch)` 처럼 부모가 폭을 강제하는 자리는 그대로 전폭이 되므로 기존 전폭 화면(로그인 · 폼 제출 등 23곳)은 변화 없음. 앞으로 전폭이 필요한 새 버튼은 `block: true` 를 명시한다

## 5.16 ✅ `R44` 목표 표 — 학부모·학생 앱 알림 탭 + 알림 항목 다시 디자인 (2026-09-30 계획 · **2026-09-30 완료** — 결과 `5.16.3` · 기준 HEAD `f8023b90` `mskim98/r44` · 판정 `Ruling 408~409`)

- **사용자 지시 (2026-09-30 원문)** — *"ux적으로 알림 같은건 별도의 탭에서 모아보는게 좋아보이고 알림 박스는 다시 디자인하는게 좋아보여 구분감이 없고 애매해"*
- **현재 화면의 문제** — 알림이 홈 맨 아래에 이어 붙어 끝까지 내려야 보이고 `더 보기` 로만 늘어남 · 한 건이 화면 높이 약 1/5 인 회색 큰 카드이고 전부 같은 모양 · 알약 `곧 도착` 과 제목 `곧 도착합니다` 가 같은 말을 반복 · 자녀 이름이 제목·본문에 중복 · 안 읽음 표시가 6px 점 하나 · 종류 7가지가 같은 모양
- **범위** — 학부모·학생 앱(`frontend/apps/parent-app`) + 공용 `baraeda_ui`. 서버 변경 부재(`unread_only` 는 `§3.12` 에 이미 존재 · `모두 읽음` 일괄 API 는 부재라 만들지 않음). 킷(`design-system/`)은 알림을 홈에 둠 — 이번 사용자 지시가 이기며 `§4` 에 행 추가

### 5.16.1 목표 표 — 전항 통과가 완료 조건

| # | 목표 | 실행 · 검사 조건 |
|:-:|---|---|
| 1 | 탭 구조 | 위젯 시험(RED 먼저): 로그인 뒤 탭 3개(홈 · 알림 · 설정) · 알림 탭 배지 = 서버 `unread_count` · 로그인·가입·대기 화면에 탭 부재 · 알림 행을 누르면 기존 관련 화면으로 이동 · 홈에 알림 목록 부재 · 지도 등 하위 화면에서 탭 부재 + 뒤로가기로 원래 탭 복귀 |
| 2 | 알림 행 위젯 | 위젯 시험: 안 읽음/읽음의 배경·글자 굵기 차이 · 중요 3종(지연 · 미승차 · 노선 변경)에만 `중요` 표시 · 종류별 아이콘이 서로 다름 · 날짜 머리(오늘 · 어제 · `M월 d일(요일)`) · 낭독 문구 한 번(`안 읽음 · 중요 · 종류 · 제목 · 본문 · 시각`) · 누르는 영역 높이 48 이상 |
| 3 | 목록 동작 | 위젯 시험: `[안 읽음]` 걸러 보기가 `unread_only=true` 로 요청 · 스크롤 끝에서 다음 쪽 요청 · 당겨서 새로고침 · 읽음 처리 뒤 탭 배지 감소 · 빈 화면 · 오류 화면 + 다시 시도 |
| 4 | 화면 확인 | 백엔드 `:8280` · DB `it_r44` · 시뮬레이터 iPhone 17 Pro. 종류가 다양한 알림을 만들어 학부모 홈 · 알림 탭(전체·안 읽음) · 다크 · 학생 알림 탭의 전/후 스크린샷을 `frontend/report/r44/` 에 저장 |
| 5 | 전체 시험 | `baraeda_ui` · `baraeda_core` · `parent-app` · `manager-app` 각각 `flutter test --reporter json` 의 `hidden:false` 만 센 실패 0 · 건너뜀 0, `flutter analyze` 0 |
| 6 | 정리 | `:8280` 서버 종료 · `it_r44` 연결 0 확인 뒤 삭제(`FORCE` 금지) · 시뮬레이터 종료 · 잔여 `flutter_tester` 0 |
| 7 | 문서 | `USER_FLOWS UF-P-08`·`UF-S-01` 의 `홈 → [알림]` 을 탭 구조로 · `FEATURE_SPEC P-09`·`S-03` 화면 위치 · 이 절 · `§4` 킷 불일치 행 |

- **변형 시험(커밋 뒤)** — 결함을 심어 그 시험만 실패하는지 확인하고 원복(변형 1회 = 원복 1회 = `git status` 1회). 결과는 보고서 ④
- **정본에서 직접 센 값** — 알림 종류는 `API_SPEC §9.7` 표에서 센다(21행 — 폐지 3종 `boarding_canceled`·`alighting_canceled`·`link_requested` 포함. `absent` 이하 관계자·매니저 수신 종류는 학부모·학생 앱에 도달 부재). 중요 통지 3종은 `FEATURE_SPEC NTF-10` 이 정의처

### 5.16.2 판정

- **`Ruling 408` — 알림은 앱 아래 탭 `[알림]` 으로 모으고, 알림 한 건은 카드가 아니라 목록 행이다.** 탭은 홈 · 알림 · 설정 3칸(학부모·학생 공통, `StatefulShellRoute.indexedStack` — 탭마다 스크롤·받아 둔 목록 유지). 지도 · 일정 · 노선 · 자녀 연결 · 비밀번호 변경은 탭 밖 경로라 위에 얹히며 탭 막대를 가리고, 로그인·가입·대기·차단 화면에도 탭이 없다. 알림 탭에 서버 `unread_count` 배지(99 초과는 `99+`). 홈에서 알림 목록과 `[설정]` 버튼 삭제. 버린 안 — 홈 안 서브탭(알림이 여전히 홈의 일부 · 배지가 홈을 열어야 보임). **킷 불일치 `§4` 9번 행**(킷은 알림을 홈에 둠)
  - 행 — 종류 아이콘 원(읽음 = 옅은 색 · 안 읽음 = 꽉 찬 색) · 제목 1줄 · 본문 2줄 · 시각 · 안 읽음 4겹(행 바탕 · 굵은 제목 · 꽉 찬 원 · 점) · 중요 3종(`NTF-10`)은 왼쪽 막대 + `중요` 글자 · 날짜 머리 · 낭독 한 문장. 종류 알약 삭제(종류는 아이콘이 말하고 낭독기에만 이름을 실음). 공용 `NotificationCard` 삭제 → `NotificationTile`(`Ruling 405`)
  - 목록 — `[전체]` `[안 읽음]`(`unread_only`) 걸러 보기 · 스크롤 끝 다음 쪽 자동 받기(`더 보기` 버튼 삭제) · 당겨서 새로고침 · 자동 갱신은 탭 막대가 맡음(알림 탭을 안 열어도 배지 최신) — 주기는 30초였으나 **R46 에서 90초로 변경**(`Ruling 431`)
- **`Ruling 409` — 세부 판정 6건**
  1. **시각은 모든 행에 시각만 적는다** — 요청은 "오늘은 시각, 이전은 날짜" 였으나 날짜 머리가 이미 날짜를 말하므로 이전 행에 날짜를 또 적으면 반복이고 시각이 사라짐(한국 시간 UTC+9 기준으로 셈 — 기기 시간대와 무관)
  2. **자녀 걸러 보기는 만들지 않는다** — `§3.12` 에 student 필터가 없다(`type` · `unread_only` 뿐). 화면에서 거르면 쪽 나누기가 어긋남
  3. **`모두 읽음` 은 만들지 않는다** — 일괄 API 부재, 서버 변경 금지
  4. **홈 머리말 로그아웃은 유지** — `Ruling 362` 를 뒤집지 않음. 설정 탭에도 그대로 있음
  5. **자녀 이름은 제목·본문에 없을 때만 제목 뒤에 붙임**(`ATT-03` + 자녀 여럿 구분). 서버 제목이 승차·하차 모두 `승하차 안내` 라 아이콘·본문으로만 갈림 — 서버 변경 금지
  6. **비상(`emergency`)은 중요 3종이 아니다** — `NTF-10` 정의처가 3종. 레드 아이콘 색이 경고를 맡음

### 5.16.3 결과 (2026-09-30)

- **목표 1~3** — 위젯 시험을 RED 로 먼저 확인(구현 부재로 4개 파일 로드 실패) 뒤 구현. 탭 3칸 · 배지 · 탭 없는 화면(로그인·대기) · 하위 화면 위 탭 부재 + 복귀 · 걸러 보기 `unread_only` · 다음 쪽 자동 · 당겨서 새로고침 · 읽음 뒤 배지 감소 · 빈/오류 화면 · 30초·복귀 자동 갱신 전부 통과
- **목표 4** — 백엔드 `:8280` · DB `it_r44` · iPhone 17 Pro. 학부모 홈 · 알림 탭(전체 · 안 읽음 · 다크) · 학생 알림 탭 스크린샷 → `frontend/report/r44/`. 알림은 서버 시작 시 시드·노선 확정이 만든 것 + **직접 넣은 31건**(종류 다양화 · 실제 API 로 일으키지 못함). 다크는 학부모 앱이 라이트 고정이라 **임시 정의(되돌림)** 로 띄움. 로그인·시작 탭도 같은 임시 정의로 지정 — 시뮬레이터에 좌표 클릭이 안 닿아서
- **목표 5** — `flutter test`(`hidden:false` 만 셈, 실서버 주소 지정) `parent-app` 279 · `baraeda_ui` 228 · `baraeda_core` 78 · `manager-app` 341 — 전부 실패 0 · 건너뜀 0. `flutter analyze` 4곳 0
- **변형 7건**(커밋 뒤) — 안 읽음 바탕 제거 · 미승차를 중요에서 제외 · 걸러 보기 무시 · 읽음 뒤 건수 미감소 · 다음 쪽 끔 · 탭 막대 자동 갱신 no-op · 어제를 오늘로 — 각각 해당 시험만 실패하고 원복(변형마다 `git status` 빈 결과)
- **목표 6** — `:8280` 종료 · `it_r44` 연결 0 확인 뒤 삭제 · 시뮬레이터 종료 · 잔여 `flutter_tester` 0
- **남은 것** — 실기기 VoiceOver 로 배지·행 낭독 확인 부재(위젯 시험까지) · 킷(`design-system/`) 갱신은 사용자 몫(`§4` 9번 행)
