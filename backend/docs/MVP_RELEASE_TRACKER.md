# MVP_RELEASE_TRACKER — 실시간 버스위치 · 승하차 · 노선최적화

> **이 문서는 "MVP 재정의(2026-07-18)" 실행의 단일 소스(SoT)다.** 큰 그림·요구사항은 `PROJECT_MASTER_PLAN.md`, 코드 컨벤션은 `reference.md`를 따르고, **이번 MVP 범위의 진행 상태·백로그·커밋 단위 체크리스트는 여기서만 갱신**한다. 작업이 끝날 때마다 아래 상태표·체크박스를 갱신하고 완료 항목에는 커밋 해시를 병기한다.

## 이 MVP가 무엇인가 (기획 재정의 요약)

기존 5계층·다기능 기획을 **출시 가능한 최소 범위**로 축소했다. "현재 구조를 크게 바꾸지 않고" 아래만 얹어 출시한다.

1. **사용자 계층 축소 → 2개 화면 그룹**
   - 드라이버 그룹 = `DRIVER`(버스기사) + `ATTENDANT`(선탑자, 신규) → **Flutter 앱**(Android/iOS)
   - 관리자 그룹 = `ACADEMY_ADMIN`(학원관리자) + `PLATFORM_ADMIN`(관리자) → **웹**
   - `STUDENT`/`PARENT`는 MVP 보류(엔드포인트 비활성). "묶인 사용자끼리 동일 화면"은 프론트가 그룹별로 구성.
2. **실시간 버스 위치 공유** — "학생 폰 GPS" → **"기사/선탑자 폰 GPS = 버스 위치"**로 재타겟(스트림 학생 N개 → 버스 1개로 단순화). 서버→클라 push 신규.
3. **버스별 승하차 체크** — `RideEvent`(BOARD/ALIGHT) 재사용 + 화면에 **학생 얼굴·이름·하차지** 표시.
4. **노선 최적화** — 학생별 하차지 입력 → 네이버 지도 실도로 최적 노선. 당일 결석·하차지 변경 시 재계산 → 관리자 추천 → 승인 후 기사 배포. 버스 정원+경로 맞춰 학생 자동배치.

**확정 설계 결정 (2026-07-18):**
- 지도 API = **Naver 전용** (NCP Directions/Geocoding, 폴백 없음 → 실행 시 NCP 키 필수)
- 자동배치·순서 알고리즘 = **Heuristic + 포트화** (sweep 정원배치 → nearest-neighbor + 2-opt → 네이버 실도로. `RouteEngine` 포트 뒤 → 추후 OR-Tools 교체 가능)
- 최적화 방향 = **등·하원 양방향** (등원=승차정류장 기준, 하원=하차지 기준)

---

## 상태 아이콘 범례

> `✅ 완료` = 엔티티~컨트롤러(또는 해당 계층)까지 동작 · `🟡 부분` = 일부 계층만(예: 엔티티·레포만) · `⬜ 예정` = enum·설정 슬롯만/코드 없음 · `—` = 해당 없음

---

## 1. 모듈 상태 (MVP 관점)

| 모듈 | 엔티티 | 레포 | 서비스 | 컨트롤러 | 상태 | MVP에서 할 일 |
|---|:-:|:-:|:-:|:-:|---|---|
| `auth` | — | — | ✅ | ✅ | ✅ 완료 | 그대로 |
| `user` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 | `Role`에 `ATTENDANT` 추가(M0) |
| `tenant` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 | 그대로 |
| `student` | ✅ | ✅ | ✅ | ✅ | 🟡 확장필요 | `photoUrl`·하차지 좌표 추가(M1) |
| `bus` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 | `seatCapacity` 재사용 |
| `route` | ✅ | ✅ | ✅ | ✅ | 🟡 기준선만 | 정적 노선은 유지, 최적화 산출은 `routing`으로 분리 |
| `rideevent` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 | 명단 응답에 사진·하차지 노출(M1) |
| `location` | (record) | ✅ | ✅ | ✅ | 🟡 확장필요 | 버스 키·서버→클라 push·Redis 저장(M2) |
| `sos` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 | MVP 범위 밖(유지) |
| `notification` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 | `SCHEDULE_RESULT` 소비 연결(M4) |
| `attendance` | ✅ | ✅ | ⬜ | ⬜ | 🟡 스켈레톤 | 결석/하차지변경 서비스·컨트롤러(M4) |
| `routing` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ 예정 | 신규 도메인 전체(M3) |

**진행률(MVP 관점): 완료 6 / 부분/확장 5 / 예정 1** (총 12 모듈)

**모듈별 상세 메모**
- `student` — 현재 `tenant·userId·name·assignedBus·boardingStop`(승차 정류장)만. `photoUrl`·`dropoffAddress`·`dropoffLat`·`dropoffLng` 없음 → M1에서 필드+변경메서드+등록 API 추가. 등원 좌표는 `boardingStop`(lat/lng) 재사용.
- `location` — `LocationSource` 포트(Mock/PhoneGps) + `LocationRepository` 포트(in-memory) + STOMP(`/ws/location`, `/topic`)는 있으나 **서버→클라 브로드캐스트 미구현**(REST 폴링), 키가 studentId 기준 → M2에서 busId 키 + `/topic/bus/{busId}` push + Redis 저장 어댑터.
- `routing` — **코드 전무.** `application.yml`에 `routing.provider/osrm/naver` 슬롯, `build.gradle`에 `webflux(WebClient)` 의존성만 예약 → M3에서 신규.
- `attendance` — `AttendanceException`·`AttendanceType` 엔티티+repo만 존재 → M4에서 당일 예외(결석/하차지변경) 서비스·컨트롤러로 build-out.
- `notification` — `NotificationType`에 `SCHEDULE_RESULT`/`APPROACH`/`NO_SHOW` enum 자리 예약됨 → M4에서 추천안 알림 소비 연결.

---

## 2. 백로그 (권장 순서)

- [ ] **역할 2그룹화** — `Role.ATTENDANT` 추가 + 권한 그룹 매핑 + STUDENT/PARENT 보류 (M0)
- [ ] **학생 데이터 확장** — 사진·하차지 필드 + 등록 API + 명단 응답 노출 (M1)
- [ ] **실시간 버스 위치 push** — 버스 키·서버→클라 STOMP·Redis 저장 (M2)
- [ ] **routing 도메인 + 네이버 연동** — 자동배치·순서최적화·실도로 경로·RoutePlan (M3)
- [ ] **당일 변경 + 승인 워크플로** — 결석/하차지변경 → 추천 → 승인 → 배포 (M4)

---

## 3. Phase 체크리스트 (커밋 1개 단위 · 각 경계에서 `./gradlew build` green)

> `reference.md` 준수: 변경 가능부만 `spec/impl` 분리, 서비스 간 직접 호출 금지·Kafka 이벤트 경유, CQRS(Command는 Query 미호출), 이벤트명 과거형. 기존 도메인 변경은 최소·국소.

**Phase M0 · 추적 문서 + 역할 2그룹화**
- [x] `backend/docs/MVP_RELEASE_TRACKER.md` 신규(이 문서)
- [ ] `user/entity/Role.java`에 `ATTENDANT`(선탑자) 추가
- [ ] `global/security` 권한 2그룹 매핑(드라이버=DRIVER+ATTENDANT, 관리자=ACADEMY_ADMIN+PLATFORM_ADMIN), STUDENT/PARENT 엔드포인트 보류
- [ ] `PROJECT_MASTER_PLAN.md` §11/§12에 이 트래커 링크 + "MVP 재정의" 반영

**Phase M1 · 도메인 데이터 확장 (하차지·사진)**
- [ ] `student/entity/Student.java` — `photoUrl`·`dropoffAddress`·`dropoffLat`·`dropoffLng` 필드 + Builder + `updateDropoff()`/`updatePhoto()`
- [ ] student `command` 서비스 + DTO — 하차지/사진 등록·변경 API
- [ ] 버스별 학생 명단 조회(query) 응답에 `photoUrl`·`name`·`dropoff` 포함(승하차 화면용)

**Phase M2 · 실시간 버스 위치 push (기사/선탑자 폰 = 버스 위치)**
- [ ] `LocationPing`/`LocationView`에 busId 컨텍스트 + `reportSelf`를 버스 위치 push로 사용(Mock 소스는 데모 유지)
- [ ] `LocationUpdatedEvent`(AFTER_COMMIT→Kafka) → projection → STOMP `/topic/bus/{busId}` 브로드캐스트(관리자 웹 구독)
- [ ] `LocationRepository` 구현을 **Redis projection**으로 교체(기존 `RedisConfig` 재사용), in-memory는 테스트/폴백
- [ ] end-to-end 검증: 드라이버 GPS push → 관리자 실시간 수신 / 권한 밖 버스 구독 거부

**Phase M3 · routing 도메인 + 네이버 지도 (헤드라인)**
- [ ] `routing/` 신규 도메인(CQRS 표준 레이아웃) 골격
- [ ] 포트 `MapRouteClient`(geocoding 주소→좌표, directions 경유지→polyline/거리/ETA) + `RouteEngine`(assign+sequence)
- [ ] 어댑터 `infrastructure/NaverGeocodingClient`·`NaverDirectionsClient`(WebClient) + `application.yml` `routing.provider: naver`
- [ ] `RouteEngine` impl — sweep(정원제약 배치) + nearest-neighbor + 2-opt(Haversine)
- [ ] 엔티티 `RoutePlan`(status DRAFT/RECOMMENDED/APPROVED/PUBLISHED, version, busId, direction 등원/하원, polyline, totalDistanceM, totalDurationS) + `RoutePlanStop`(seq, studentId, lat/lng, etaSeconds)
- [ ] 생성 API — 정원+하차지/승차지 → 자동배치+순서최적화 → 네이버 실도로 → `RoutePlan(DRAFT)`
- [ ] 검증: 정원 초과 배정 없음 / 순서 2-opt 개선 / 네이버 polyline·ETA 채워짐 / 경유지 상한(~15) 초과 시 구간 분할

**Phase M4 · 당일 변경 + 승인 워크플로 배포**
- [ ] `attendance` — `AttendanceException` 서비스/컨트롤러(결석 ABSENT, 당일 하차지 변경)
- [ ] 당일 변경 → 이벤트 → 해당 버스 국소 재계산 → `RoutePlan(RECOMMENDED)` + 관리자 알림(`SCHEDULE_RESULT` 소비)
- [ ] 관리자 승인/거부 API → `APPROVED`/`PUBLISHED`
- [ ] 배포 — 드라이버 그룹이 `PUBLISHED` plan pull(또는 STOMP push)로 수신
- [ ] 검증: 결석 반영 → 추천 → 승인 → 드라이버가 갱신 노선 확인

---

## 4. 노선 최적화 알고리즘 검토 (요청한 검토 결과)

**문제 정의:** 단일 depot(학원)·정원 제약 차량경로문제(CVRP) = 학교버스 라우팅(SBRP). 단일 학원·버스 수 대·학생 수십 명 규모 → 무거운 최적솔버 불필요.

**채택 파이프라인 (cluster-first, route-second):**
1. **Geocoding** — 하차지 주소 → 좌표(네이버). 결과는 `Student`에 캐시(호출 최소화).
2. **자동배치(assign)** — **Sweep 알고리즘**(depot 기준 방위각 정렬 후 `seatCapacity`까지 채워 버스 배정). 방사형 배치에 강하고 설명이 쉽다.
3. **순서 최적화(sequence)** — 버스 내 방문 순서 = open-TSP. **nearest-neighbor 생성 + 2-opt 개선**, 거리는 **Haversine(직선)** → API 호출 0, 즉시 계산.
4. **실도로 경로(네이버)** — 확정 순서의 경유지로 **네이버 Directions(다중 경유지)** 1회 호출 → 실제 도로 polyline + 총거리 + 정류장별 ETA.
5. **당일 재계산(replan)** — 결석/하차지변경은 **해당 버스만 국소 재최적화**(2·3·4 재실행) → 관리자 추천 → 승인 → 배포.

**왜 Heuristic인가(vs OR-Tools):** N² 거리행렬을 네이버로 뽑으면 호출량 폭발 → Haversine로 배치·순서를 정하고 **네이버는 최종 순서 1개 경로에만** 호출해 비용 억제. OR-Tools는 품질↑지만 네이티브 의존성·모델링 비용이 MVP엔 과함. `RouteEngine` 포트 뒤에 두면 콜러 수정 없이 교체 가능(`reference.md` §15 Routing = Port/MSA 후보와 일치).

**등·하원 양방향:** 등원=`boardingStop`(승차) 순서 최적화, 하원=`Student.dropoff` 좌표 순서 최적화. `RoutePlan.direction`으로 두 인스턴스를 구분해 각각 생성·승인·배포.

---

## 5. ⚠ 리스크 / 전제

- **네이버 전용(폴백 없음)** → 로컬에서 실제 경로 계산하려면 **NCP 키(`NAVER_DIRECTIONS_KEY_ID`/`NAVER_DIRECTIONS_KEY`) env 필수.** 키 없는 개발/테스트는 `MapRouteClient`/`RouteEngine` 포트를 목(mock)으로 대체해 배치·순서만 검증(네이버 polyline은 스킵).
- **네이버 Directions 경유지 상한(~15)** → 한 버스 정류장이 상한 초과 시 **구간 분할 호출** 필요.
- **프론트(Flutter 앱 / 관리자 웹)는 백엔드 범위 밖** — 백엔드는 역할별 API 계약만 제공.
- **DB 스키마** — 현재 `ddl-auto: update`라 필드 추가는 자동 반영되나, 운영 전 Flyway 전환 시 마이그레이션 스크립트로 승격 필요.

---

## 6. 참고 (재사용 자산)

- 이벤트 백본: `TransactionalDomainEventRelay`·`KafkaEventPublisher`·`DomainEvent`(`global/event`) — routing/attendance 이벤트도 동일 방식.
- 알림: `NotificationType.SCHEDULE_RESULT`(예약) → 추천안 알림.
- WebClient: `build.gradle` webflux(예약) → 네이버 어댑터.
- Redis: `RedisConfig` 빈 → location projection 저장.
- 포트 패턴 레퍼런스: `LocationSource`/`LocationRepository`(Mock/실 교체) → `RouteEngine`/`MapRouteClient` 동일 패턴.