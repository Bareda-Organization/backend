# 학원 통학버스 통합관리 — 마스터 기획 & MVP 구현 추적

> **이 문서가 프로젝트 단일 소스(SoT)다.** 기획 원본 `학원 통학버스 통합관리 시스템.docx`를 재정리한 **요구사항** + 현재 백엔드 코드 기준 **MVP 구현 계획** + **진행 추적**. 작업 중 구조·목표·남은 일을 여기서 확인하고, 진행되면 여기서 갱신한다. 큰 변경은 `projectInfo.md`가 아니라 이 문서에 반영.

## 문서 지도

| 문서 | 성격 | 언제 |
|---|---|---|
| `학원 통학버스 통합관리 시스템.docx` | 기획 **원본**(불변) | 원문 근거 확인 |
| **`PROJECT_MASTER_PLAN.md`** (이 문서) | 요구사항 + 구현계획 + **진행추적** | 평소 항상 |
| `../../projectInfo.md` | 초기 작업 지시서(프론트 MVP 데모 관점) | 프론트·시나리오 참고 |
| `BACKEND_ARCHITECTURE.html` | 백엔드 서버·인프라 설계 | 서버 구조 팔 때 |
| `CODE_CONVENTIONS.html` | 백엔드 코드 컨벤션 | 새 코드 규칙 |

**읽는 법:** 1~10장 = 최종 요구사항 / 11장 = 현재 코드 기준 MVP 계획 / 12장 = 모듈 상태·백로그(작업 끝날 때마다 갱신).

---

## 1. 서비스 개요 & 목적

노선 편성·승하차·커뮤니케이션·차량/인력·운행 모니터링·법정 기록을 하나로 묶어 통학버스 운영의 **안전성 + 효율성**을 올리는 **멀티 테넌트 플랫폼**(모바일 앱 + 관리자 웹).

- **6대 서비스:** ① 노선표·배차 ② 학생 승하차 ③ 학원·기사·학부모·동승보호자 커뮤니케이션 ④ 차량·운행인력 ⑤ 운행 모니터링·사고 대응 ⑥ 통계·법정 운행기록
- **핵심 가치:** 위치 표시가 아니라 **승하차 기록·명단·배차·예외대응·법정기록이 하나로 연결되는 데이터 흐름**. 위치는 그 위에 얹히는 한 조각.

## 2. 사용자 계층 & 권한

하나의 승하차 기록(`RideEvent`)을 여러 계층이 **각자 권한 범위 안에서만** 조회 — "계층별 조회 권한 분리"가 설계 핵심.

기획서(docx)는 운영 4역할(학원관리자·기사·동승보호자·학부모). 코드(`Role` enum)는 여기에 **학생·플랫폼 관리자**를 더한 **5계층**. 동승보호자는 MVP에서 별도 역할로 분리하지 않고 **기사 앱 승하차 확인 흐름에 흡수**(추후 승격 가능).

| 계층(Role) | 조회 범위 |
|---|---|
| `STUDENT` 학생 | 본인 기록 + 도보 경로 + SOS |
| `PARENT` 학부모 | 자녀·날짜별(캘린더) + 알림함 |
| `DRIVER` 기사 | 담당 정류장·명단·메모 |
| `ACADEMY_ADMIN` 학원관리자 | **소속 학원(tenant)** 전체 이력·통계 |
| `PLATFORM_ADMIN` 플랫폼관리자 | **전 학원** 운영·과금 |

- 학원관리자 ≠ 플랫폼관리자 (권한 범위 완전 별개).
- **멀티테넌시 N:M:** `User`에 `tenant_id`를 직접 박지 않고 **`UserTenantRole(user_id, tenant_id, role)` 연결 테이블**로 표현(학부모가 서로 다른 학원 자녀, 기사가 복수 학원 소속 가능).

## 3. 관리 도메인 데이터

기획서 기본정보 + 운영 데이터를 엔티티로 매핑. 상태는 12장 참조.

| 기획서(docx 3장) | 핵심 항목 | 엔티티 |
|---|---|---|
| 학생정보 | 사진, 정규 등하원 요일, 승하차 위치, 인계 보호자, 혼자 귀가 여부 | `Student` + `StudentGuardian` |
| 정류소정보 | 정확한 좌표, 상세 위치·사진, **지오펜스 반경**, 허용 승하차 시간 | `Stop` |
| 차량정보 | 등록 정원, 실제 학생 탑승 가능 인원, 어린이통학버스 신고·보험 만료 | `Bus`(seat_capacity) |
| 운행인력정보 | 기사·동승보호자, 자격·안전교육 이수·유효기간 | `User`(DRIVER) + `UserTenantRole` |

**전체 엔티티:** `User` · `UserTenantRole` · `Tenant` · `Student` · `StudentGuardian` · `Bus` · `Route`(assign_capacity) · `Stop` · `RideEvent`(허브) · `AttendanceException` · `ScheduleChangeRequest` · `SosEvent` · `NotificationLog` · `AuditLog`.

- **RideEvent가 허브** — Student·Bus·Stop·Route와 연결. 정정 시 원본을 덮어쓰지 않고 새 기록(`source=CORRECTION`, `corrected_by/at`, `original_ref`).
- ⚠ 흔한 오해: "정류소=주소"가 아님. 주소와 별개로 **실제 승하차 지점**(동 앞·출입구, 진행방향, 유턴 여부, 대기 안전장소, 지오펜스)을 따로 관리.

## 4. 노선 · 배차 관리

노선은 최단거리가 아니라 **정원·안전·시간 제약**을 만족하는 "운행 가능한" 경로. 변경은 덮어쓰지 않고 **버전 관리**.

- **관리 단위:** 요일별 / 등원·하원 / 수업시간별 / 차량별 / 학기·방학별 + 적용 시작·종료일.
- **최적화 제약(요약):** 차량 정원, 학생별 승하차 가능 시간, 학원 도착 마감, 교통량, 정차 소요, 아파트 진입·회차, 일방통행·유턴·높이 제한, 어린 학생 최대 도보거리, 학생별 최대 탑승시간, 형제자매 동일 차량, 진행방향 반대편 하차 방지, 위험 횡단 방지.
- **임시 변동 절차:** ① 학부모 신청 → ② 시스템이 정원·노선 영향 자동확인 → ③ 관리자 승인/거절 → ④ 기사·동승보호자 전달·확인 → ⑤ 학부모 최종 안내.
- **정책:** 기사·학부모가 직접 합의한 변경도 **학원 승인을 거쳐야 효력**. 정규 노선은 관리자 최종 승인 후에만 적용. → 코드 `ApprovalStatus`(대기/승인/반려).

## 5. 승하차 관리 & 학생 상태머신

승차·하차를 **하나로 묶지 않고 각각** 기록. 학생 운행 상태 전이:

`탑승예정 → 정류소 대기 → 승차완료 → 운행중 → 하차예정 → 하차완료 → 보호자 인계완료 → 운행완료` (대기·운행 중 **미승차/예외** 분기 → 6장)

- **확인 방식(초기 병행):** 명단 + 사진 육안 + NFC/RFID + QR + 실패 시 수동 + 탑승 인원·좌석 자동 대조. → `RideSource` enum(`QR/NFC/MANUAL/CORRECTION`), `RideType`(`BOARD/ALIGHT`).
- ⚠ 흔한 오해: 태그만으로 확정하면 오확정 위험 → 동승보호자가 **명단·실제 학생 함께 확인**하는 이중 구조. 또 **하차완료 ≠ 보호자 인계완료** — 저학년은 단순 하차 버튼으로 운행 종료 금지.

## 6. 예외상황 · 에스컬레이션

각 예외를 **대기 → 연락 → 관리자 보고 → 후속조치**의 정해진 순서·임계시간으로 처리.

| 예외 | 흐름(타임라인) |
|---|---|
| 미승차 | 정류소 도착 → **+10분 미확인** → noshow 판정 → 학부모+관리자 알림 |
| 보호자 부재 | 하차위치 도착 → 대기·연락 → 연락 실패 → 관리자 보고 → 재탑승·복귀 |
| SOS | 발신 → 즉시 학부모+관리자 → **+3분 미확인** → 플랫폼관리자 에스컬레이션 |

기타: 태그/단말기 오류(수동·오프라인 처리, 통신복구 후 자동 동기화) · 차량 고장/사고(긴급신고·명단 자동전달·대체차량) · **차내 잔류 방지**(운행종료 전 탑승 0 확인, 승차-하차 자동 대조, 미하차 시 종료 차단).

- ⚠ **법적 근거:** 도로교통법 — 어린이 **하차확인장치** 작동, 좌석안전띠·보호자 동승 **안전운행기록 작성·보관·분기 제출**. 확인·기록은 법정 요건.

## 7. 실시간 위치 · 알림 정책

학부모에게 전체 노선이 아닌 **자녀 관련 제한 위치만** 제공. 알림은 "이벤트 1건 = 발송 1건"이 되도록 **멱등(중복 억제)**.

**알림 파이프라인:** 이벤트 감지(매 틱) → (위치판정은) **연속 N회 관측** 게이트 → `dedup_key` 계산 → 이미 발송? → YES 무시 / NO 발송+`NotificationLog` 기록.
`dedup_key = (유형 + 학생 + 대상일자 + 정류장/단계)`

**임계값(문서·코드 공통 상수):**

| 알림 | 임계 | 대상 | enum(`NotificationType`) |
|---|---|---|---|
| 미승차 판정 | 정류소 도착 +**10분** | 학부모+관리자 | `NO_SHOW` |
| 근접 알림 | 도착 예상 **5분 전** | 학생+학부모 | `APPROACH` |
| SOS 에스컬레이션 | 관리자 미확인 **3분** | 플랫폼관리자 | `SOS` |
| 승차/하차 완료 | 즉시 | 학부모 | `BOARD_DONE`/`ALIGHT_DONE` |
| 시간변경 결과 | 처리 시 | 학부모 | `SCHEDULE_RESULT` |

- ⚠ 흔한 오해: 미승차 매 틱 알림 = 알림 폭탄. 같은 `dedup_key` 재감지돼도 **1회만** 발송. GPS 단발 이상치 오탐 방지 위해 **연속 N회 관측** 조건.

## 8. 개인정보 · 위치정보 · 법

학생 사진·주소·연락처·위치는 민감 개인정보 → 수집목적·보유기간·접근권한을 설계 단계에서 확정.

- **동의:** 학부모 수집·이용 동의 + 만 14세 미만 **법정대리인 동의** + 사진 별도 안내.
- **보호:** 암호화, 계층별 접근권한, 조회·수정 이력, 캡처·다운로드 제한, 퇴사·노선변경 시 권한 즉시 회수.
- **위치정보:** 차량 위치가 학생/기사와 연결되면 개인위치정보일 수 있음 → 위치정보사업 신고 여부 법률 검토. 보유기간 최대 1년.
- ⚠ **실서비스 전환 선행조건:** MVP(Mock)는 실 위치 미수집이라 유예 가능하나, **동의 UI·저장 스키마는 실 GPS 전환 전 반드시 구현**. 미루면 Mock→실 GPS 전환이 법적으로 막힘.

## 9. 핵심 성과지표(KPI)

| 범주 | 지표 |
|---|---|
| 안전 대응 속도 | 미승차→학부모 안내 시간, 보호자 부재→관리자 보고 시간, 사고 평균 해결시간 |
| 운행 품질 | 정시 도착률, 예상 vs 실제 오차, 평균 지연 |
| 효율 | 평균 탑승시간, 평균 탑승률, 노선 최적화 전후 거리 감소율 |
| 정확성 | 승하차 1건당 처리시간, 미하차 자동 탐지율, 공지 확인율 |

## 10. 구축 3단계 로드맵

- **1단계 · MVP (현재):** 기본정보 관리 · 정규 노선·요일일정 · 승하차 수동 · **위치추적(Mock)·ETA·기본알림** · 미승차·보호자 부재 · 관리자 대시보드 · 운행기록.
- **2단계 · 자동화:** 교통량 노선 최적화 · NFC/RFID 태그 · 차량·좌석 자동배정 · 카카오 알림톡·문자 · 법정 안전운행기록 자동.
- **3단계 · 확장:** 다학원·운수업체 통합 · 수요예측 배차 · 차량 유지보수 · 학원 ERP·출결 연동 · 기사 품질 평가.

---

## 11. MVP 구현 계획 (현재 코드 기준)

MVP는 **학생 폰 GPS**로 확정하되, 실제 앱을 아직 구동할 수 없어 위치는 **Mock 좌표 스트림**으로 진행. 저장·조회·API는 실 GPS와 동일하게 만들어 나중에 **소스만 교체**.

### 11.1 위치 소스 추상화 (이미 구현됨)

`LocationSource` 인터페이스를 `MockLocationSource`(활성) / `PhoneGpsSource`(대기)가 구현. 스케줄러가 주기마다 `tick()` 호출. Mock은 정해진 경로를 삼각파로 왕복시켜 "움직이는 점"을 만들고, 실 GPS는 학생 앱이 `POST /api/locations`로 좌표 push. 둘의 유일한 차이는 "누가 좌표를 만드는가"뿐, 저장·조회는 동일.

**실 GPS 전환 = 플래그만 토글** (스케줄러·서비스·저장소·조회 API 그대로):

```yaml
# application.yml
app:
  location:
    tick-ms: 3000          # 위치 소스 tick 주기(ms)
    mock:
      enabled: true        # Mock 시뮬레이터 (실 GPS 전환 시 false)
      step: 0.08           # 매 tick 진행도 증가폭(0~1 삼각파)
    gps:
      enabled: false       # 실 학생폰 GPS push (실 연동 시 true)
```

```java
public interface LocationSource {
    boolean isActive();   // 지금 좌표를 공급하는가
    void tick();          // 스케줄러가 주기마다 호출(pull). push 소스는 no-op
    String label();       // 로그·진단용 이름
}
```

### 11.2 MVP 범위 (권장 시나리오 1·2·5·6·8)

| 영역 | 내용 | 상태 |
|---|---|---|
| 위치추적(Mock) | LocationSource 포트 · Mock 시뮬레이터 · 조회 API | ✅ 완료 |
| 승하차 기록 | RideEvent · 정정 이력(원본 보존) · 계층별 조회 | ✅ 완료 |
| 버스 관리 | Bus · Route · Stop · 배차 · 정원 | ✅ 완료 |
| 알림 | NotificationLog · dedup 멱등 · 임계값(10/5/3분) · 발송 | ⬜ 예정 |
| 기사 운행관리 | 정류장별 승하차 체크 가능 · 운행 세션/로그 미구현 | 🟡 부분 |
| 인증·권한 | JWT · 역할 기반 · 멀티테넌시 가드 | ✅ 완료 |
| 기본정보 등록 | user·tenant·student 서비스·컨트롤러(등록 API) | 🟡 부분 |

**대표 흐름(하원):** ① 학생 앱 위치 전송(Mock) → ② 승차 체크 기록 → 학부모 푸시 → ③ 학부모 앱 지도 실시간 노출 → ④ 근접 5분 전 푸시 → ⑤ 하차 체크 → 기록 저장 → 학부모 푸시 → ⑥ 예정 +10분 미승차면 학부모+관리자 동시 알림.

### 11.3 코드 컨벤션 (Kafka+CQRS 전면 채택으로 갱신, 2026-07-18)

`backend/docs/reference.md`(Backend Architecture & Development Convention v1.0)를 전면 채택하기로 확정 — 기존 "service·repository 전부 spec/impl 분리" 규칙을 아래로 대체한다. **Claude 참조용 원본은 `reference.md`(Markdown, 삭제하지 않고 유지), 사람이 보는 렌더링 버전은 `CODE_CONVENTIONS.html`(HTML, 요청 시에만 갱신)** — 여기는 요약만.

- **spec/impl은 "변경 가능성 있는 포트"만.** 외부 연동·전략 패턴·Mock 필요·MSA 분리 후보(`LocationSource`, `NotificationSender`, `EtaService`, `RouteEngine`, `LocationRepository` 등)만 인터페이스. **단순 CRUD 서비스(Bus/Tenant/Student/Member/Route/RideEvent/Sos)는 구현체 하나만** — 인터페이스 제거.
- **CQRS**: `command/`(생성·수정·삭제, 이벤트 발행) / `query/`(조회 전용, command 호출 금지)로 분리. 둘 다 concrete, 컨트롤러가 둘 다 주입.
- **이벤트 우선 통신**: 모듈 간 직접 호출 대신 과거형 이벤트(`RideCompletedEvent`, `StudentBoardedEvent`, `SosTriggeredEvent` 등) → **Kafka** 발행/구독. 즉시 응답 필요한 경우만 예외적으로 직접 호출.
- **infrastructure/**: 외부 기술(Kafka·Redis·gRPC·S3 등) 어댑터는 여기에만. 비즈니스 계층은 구현 기술을 모른다.
- `package-info.java`는 두지 않는다(패키지 레벨 애너테이션 필요 시만 예외). 표준 패키지 레이아웃·판단 기준 상세는 §11.4 및 `CODE_CONVENTIONS.html`.

### 11.4 아키텍처 리팩터 로드맵 — Kafka + CQRS + 실시간 push + 남은 도메인 (진행 중)

> 배경: `reference.md`가 기존 코드/컨벤션과 3곳(단순 CRUD 인터페이스·패키지 구조·모듈 간 직접호출)에서 충돌해, **전면 채택 + 기존 12개 모듈 재편**을 결정. 다음 신규 기능(location 실시간 push)을 새 아키텍처의 첫 실적용 사례로 삼는다. 상세 체크리스트는 §12.3.
>
> ⚠️ **가드레일**: 한 번에 컴파일 안 되는 거대 변경 금지 — 모듈 단위로 재편하고 **각 Phase 경계에서 `./gradlew build`(테스트 포함) green + 커밋** 후 다음으로. cross-module 이벤트 전환(직접호출→Kafka)은 발신·수신 양쪽 모듈이 모두 재편된 뒤 **한 커밋에서만** 스위치(그 전까지 기존 직접 호출 유지 → 항상 빌드 가능).

| Phase | 내용 | 상태 |
|---|---|---|
| 0. 컨벤션 단일화 | `reference.md`(Claude 참조용, 정리) + `CODE_CONVENTIONS.html`(사람용 렌더) 역할분리 확정, `CLAUDE.md`/이 문서 §11.3 갱신 | ✅ 완료 |
| 1. 공용 기반 | Kafka(KRaft)+Redis 인프라, `DomainEvent`/`DomainEventPublisher`(Port)+`KafkaEventPublisher`(impl), DB↔Kafka 이중쓰기 정합성(`ApplicationEventPublisher`→`@TransactionalEventListener(AFTER_COMMIT)`→Kafka) | ✅ 완료 |
| 2. 기존 12개 모듈 재편 | CQRS 분리 + 인터페이스 정리 + 직접호출→이벤트. 순서(잎 먼저): `notification` → `student·tenant·user·bus·route` → `rideevent·sos·location` → `auth` | ✅ 완료 |
| 3. location 실시간 push (플래그십) | `LocationUpdatedEvent`→Redis projection→WebSocket user destination push(`convertAndSendToUser`), SUBSCRIBE 인가(계층별 조회 권한을 push 구독에도 강제), 알림도 WebSocket으로 병행 push | ✅ 완료 (2026-07-19 E2E 검증) |
| 4. attendance 서비스화 | 기존 엔티티·레포 위에 승인 상태전이(`approve`/`reject`) + command/query/controller + 당일 명단 스킵 통합. `sos` 모듈 템플릿 재사용 | ⬜ 예정 |
| 5. schedule 모듈 | greenfield 일정변경 승인 워크플로(`ScheduleChangeRequest`) + `SCHEDULE_RESULT` 알림 최초 연결 | ⬜ 예정 |
| 6. routing 모듈 (자동배치 최적화, 헤드라인) | 학생 하차지 좌표 + WebClient/`MapRouteClient` 포트(geocode·directions) + `RouteEngine`(sweep 정원배치 + NN/2-opt) + `RoutePlan`(DRAFT→RECOMMENDED→APPROVED→PUBLISHED) + 당일변경 replan + `APPROACH`/`NO_SHOW` 알림. 6a~6f 하위단계로 분할 | ⬜ 예정 |
| 7. 기사 운행관리 | 운행 세션/일일 로그(운행 전 체크리스트는 정식 출시 때 복원) | ⬜ 예정 |
| 8. Mock → 실 GPS 전환 | `app.location` 플래그 토글(Phase 3에서 포트 분리 완료 → 소스만 교체) + 8장 법적 선행요건(동의 UI·이력 스키마) | ⬜ 예정 |

- **왜 지금 Kafka인가**: 단일 모놀리식 앱에는 엄밀히 과설계지만, MSA 전환 대비(§17 reference.md)라는 목적 하에 사용자가 트레이드오프를 인지하고 선택.
- **Phase 4~8 확정(2026-07-19)**: 남은 5개 모듈을 이 로드맵으로 흡수. 특히 **routing은 "넓은 범위(자동배치 최적화)"로 확정** — `MVP_RELEASE_TRACKER.md` §4 알고리즘 파이프라인(sweep 배치 + NN/2-opt + 실도로 directions + 당일 replan)을 PROJECT_MASTER_PLAN으로 흡수하며, `Student`에 하차지 좌표 필드 추가(스키마 변경)를 동반한다. 상세 커밋 단위 체크리스트는 §12.3. 개발은 다른 세션에서 이어감(메모리 `school-bus-next-phases` 참조).
- **⚠️ Phase 3 검증 중 발견한 인프라 버그(수정 완료)**: `spring-kafka`의 `JsonSerializer`/`JsonDeserializer`는 아직 클래식 Jackson 2(`com.fasterxml.jackson`)를 쓰는데, 이 프로젝트엔 Boot 4 기본 Jackson 3만 있고 클래식 Jackson 2용 `jackson-datatype-jsr310`이 없어서 **모든 `DomainEvent`의 `occurredAt`(`Instant`) 필드가 Kafka 발행 시 조용히 직렬화 실패**하고 있었다(`TransactionSynchronizationUtils.afterCompletion threw exception` 로그로만 남고 트랜잭션 자체는 커밋됨 — 즉 DB 저장은 되지만 이벤트는 유실). Location뿐 아니라 Phase 2에서 만든 다른 이벤트(`StudentBoardedEvent` 등)도 동일 문제였을 것 — Kafka를 실제로 기동해 본 건 이번이 처음이라 지금까지 발견되지 않았다. `build.gradle`에 `runtimeOnly 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'` 한 줄 추가로 해결(커밋 `e19272a`. 별도로 `RedisConfig`도 Redis 값 직렬화를 Jackson 3 `GenericJacksonJsonRedisSerializer`로 전환하는 컴파일 수정을 커밋 `26fd18c`에서 함께 처리).

---

## 12. 진행 추적 · 백로그

> 작업이 끝날 때마다 아래 상태·체크박스를 갱신한다. (✅ 완료 = 엔티티~컨트롤러 / 🟡 부분 = 엔티티·레포만, 등록 API 미완 / ⬜ 예정 = enum·스텁만)

### 12.1 모듈별 상태

| 모듈 | 엔티티 | 레포 | 서비스 | 컨트롤러 | 상태 |
|---|:-:|:-:|:-:|:-:|---|
| `global` (security·error·response·tenant·config) | — | — | — | — | ✅ 완료 |
| `auth` | — | — | ✅ | ✅ | ✅ 완료 |
| `bus` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `route` (Route·Stop) | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `rideevent` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `location` (포트·Mock·GPS·스케줄러, 실시간 push) | — | ✅ | ✅ | ✅ | ✅ 완료 |
| `user` (User·UserTenantRole) | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `tenant` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `student` (Student·StudentGuardian) | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `attendance` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `notification` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `sos` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `schedule` (ScheduleChangeRequest) | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `routing` (OSRM·네이버 ETA, sweep+NN+2-opt) | ✅ | ✅ | ✅ | ✅ | 🟡 부분(6a~6e) |

진행률: **완료 13 / 부분 1 / 예정 0** (총 14 모듈)

- `user`/`tenant`/`student`: `MemberController`+`MemberServiceImpl`, `TenantController`+`TenantServiceImpl`, `StudentController`+`StudentServiceImpl`(배정·보호자 연결 포함) 모두 구현 완료. 등록·조회 모두 `TenantGuard`로 학원 격리 검사.
- 사용자 계층·권한(2장) 요구사항 — 5계층 `Role` + `UserTenantRole` N:M + `TenantGuard`(학원관리자 자기 학원만/플랫폼관리자 지정 학원) + `RideEventQueryService`의 역할별 조회 스코핑(`getMyRecords`/`getChildrenRecords`/`getRosterRecords`/`getTenantRecords`)까지 구조적으로 완료. PARENT "알림함"은 `notification` 모듈 완료로 함께 해소됨. 남은 건 DRIVER "메모"(기사 운행관리 모듈 몫)뿐 — 권한 구조 자체가 아니라 해당 기능 모듈 미구현 때문.
- `notification`: `NotificationLog`(dedupKey unique) + `NotificationLogRepository` + `NotificationCommandService`(멱등 `notify` + `dedupKey` 공식 헬퍼) + `NotificationQueryService` + `NotificationSender` 포트(`LogNotificationSender` MVP 구현, `LocationSource`와 동일한 추상화 패턴) + `NotificationController`(`/api/notifications/children` PARENT, `/api/notifications` 관리자) 모두 구현. `NotificationThresholds`(10/5/3분) 상수 정의. **트리거는 BOARD_DONE/ALIGHT_DONE/SOS/SCHEDULE_RESULT 연동됨** — Phase 2에서 직접호출에서 Kafka 도메인 이벤트 경유(`DomainEventNotificationConsumer`의 `@KafkaListener`가 `notify` 호출)로 전환됐고, Phase 5에서 `SCHEDULE_RESULT`가 최초로 연결됨. NO_SHOW(정류장 도착 감지 필요)·APPROACH(ETA 필요)는 routing(Phase 6) 구현 시 같은 이벤트 발행 패턴을 따르도록 남겨둠.
- `sos`: `SosEvent`(단방향 상태전이 `acknowledge`/`resolve` 도메인 메서드, 잘못된 전이는 `CONFLICT` 409) + `SosEventRepository` + `SosCommandService`(발신/확인/종료) + `SosQueryService`(역할별 4계층 조회) + `SosController` + `SosEscalationScheduler`(`app.sos.escalation-check-ms`, 기본 30초 폴링) 모두 구현. 발신 즉시·에스컬레이션(3분 미확인) 모두 `NotificationCommandService`의 dedupKey 멱등을 그대로 재사용해 "같은 이벤트 중복 알림 없음"을 스케줄러 코드 없이 보장. 실제 서버 기동 후 발신→확인→종료 상태전이, 권한 가드(403)·상태 가드(409), 백데이트 이벤트로 에스컬레이션 1회만 발송됨을 curl로 검증 완료.
- `location` 실시간 push (2026-07-19 E2E 검증 완료): `parent@school.com`이 `/user/queue/location` 구독 시 자녀(김민준·이서연) GPS가 ~3초 주기로 push됨(`MockLocationSource`→`LocationUpdatedEvent`→Kafka→`LocationPushConsumer`→`convertAndSendToUser`). `admin@school.com`이 타 테넌트(가온에듀) `/topic/tenant/{id}/location` 구독 시 `StompAuthChannelInterceptor`가 즉시 거부(ERROR frame + 연결 종료), 자기 테넌트 구독은 정상 push. `driver@school.com`의 `POST /api/ride-events`(BOARD) → `parent`의 `/user/queue/notifications`로 BOARD_DONE 즉시 push(`WebSocketNotificationSender`). SOS 발신 즉시 push + 3분 미확인 에스컬레이션 push 모두 동일 채널로 도착 확인. 검증 중 Kafka 이벤트 직렬화 버그(위 §11.4 콜아웃)를 발견·수정.
- `attendance` (Phase 4, 2026-07-19 완료, 커밋 `de79104`): `sos`를 템플릿으로 서비스화. `AttendanceException.approve/reject`(PENDING만 허용, 잘못된 전이는 `CONFLICT` 409) + `AttendanceCommandService`(신청 생성은 학부모, 본인 자녀만 가능·`FORBIDDEN` 가드) + `AttendanceQueryService`(자녀별/테넌트별 조회 + `getActiveRoster(busId, date)` 명단 스킵 헬퍼 — `StudentRepository.findByAssignedBusId` 결과에서 해당 날짜 `APPROVED` 신고가 있는 학생을 제외) + `AttendanceController`(`/api/attendance-exceptions`). 승인 시 `AttendanceApprovedEvent` 발행(현재 소비자 없음, Phase 6 routing이 당일 replan 트리거로 구독 예정). `getActiveRoster`는 서비스 간 재사용 헬퍼로만 노출하고 별도 컨트롤러 엔드포인트나 다른 모듈 배선은 하지 않음(rideevent 명단·Phase 6 routing이 향후 직접 호출해 소비). docker compose(postgres/redis/kafka)+`bootRun`으로 curl E2E 검증: 생성→타인 자녀 403→미인증 401→관리자 승인 200(processedBy 기록)→재승인 409→children/tenant 조회 정상, Kafka `attendance-approved` 토픽 발행 확인(직렬화 에러 없음). 전용 테스트 파일은 만들지 않음(`sos` 템플릿 선례를 따름).
- `schedule` (Phase 5, 2026-07-19 완료, 커밋 `94f99c6`): greenfield 신규. `ScheduleChangeRequest`(tenantId/studentId/requestedDate/requestedTime/reason/status/processedBy) + `approve/reject`(attendance와 동일 PENDING 가드, `CONFLICT` 409) + `ScheduleCommandService`(신청은 학부모가 본인 자녀만) + `ScheduleQueryService`(자녀별/테넌트별 조회) + `ScheduleController`(`/api/schedule-change-requests`) — attendance와 동일 구조. **attendance와의 차이**: 승인·반려 **둘 다** `ScheduleResultEvent`(과거형, `status` 필드 포함) 발행 → topic `schedule-result` → `DomainEventNotificationConsumer`에 `onScheduleResult` 리스너 신설 → 그동안 정의만 되고 미사용이던 `NotificationType.SCHEDULE_RESULT`를 최초로 연결. docker compose+`bootRun` curl E2E 검증: 생성→타인 자녀 403→미인증 401→승인 200→재승인 409→반려→children/tenant 조회, `notification_log` 테이블에 SCHEDULE_RESULT 승인/반려 메시지가 정확한 문구로 저장됨을 DB 조회로 직접 확인(Kafka 발행→소비→알림 저장 전체 파이프라인 검증, 직렬화 에러 없음). 전용 테스트 파일은 만들지 않음(선례 따름).
- `routing` (Phase 6a~6e, 2026-07-20 진행, 커밋 `f4b88e5`·`fcfc48f`·`b5def33`·`e852c37`·`3e32178`·`a214fd6`(6a~6d, 2026-07-19)+6e(2026-07-20, 커밋 미생성); **6f는 미완**): 이 코드베이스 최초의 외부 HTTP 연동·최적화 알고리즘·다단계 상태 엔티티. 6a `Tenant.lat/lng`(depot, 마스터플랜에 없던 갭을 이번에 메꿈)+`Student.dropoff*` 좌표 스키마. 6b `spring-boot-starter-webclient`(처음엔 `webflux`로 시도했다가 Boot4 는 webflux/webclient 스타터가 완전히 분리돼 있음을 발견해 교체 — 아래 함정 참조) + `MapRouteClient` 포트 + `OsrmMapRouteClient`(기본값, `router.project-osrm.org` 공개 데모서버 실호출 확인) + `NaverMapRouteClient`(NCP Direction 15 스펙 구현, 실키 없어 미검증). 6c `RouteEngine` 포트 + `HeuristicRouteEngine`(sweep 방위각정렬+nearest-neighbor+2-opt, `GeoMath` Haversine 순수계산, 외부호출 0) + 단위테스트 4개. 6d `RoutePlan`(DRAFT/RECOMMENDED/APPROVED/PUBLISHED 상태전이 도메인메서드는 구현, **승인/배포 컨트롤러 엔드포인트는 6f 몫이라 아직 없음**)+`RoutePlanStop` + `RoutingCommandService.generate()`(`AttendanceQueryService.getActiveRoster` 재사용으로 당일 결석 자동 제외 → `Bus.seatCapacity` 하드 정원검증 → 방향별 좌표해석 → `RouteEngine` → 방향별 waypoint 구성 → `MapRouteClient`(15개 초과시 청킹) → 정차별 ETA 누적) + `RoutingQueryService`/`RoutingController`(`/api/route-plans`, 생성/조회만).
  - **⚠️ 함정 1**: `WebClient.Builder` 자동구성 빈을 기대하고 `spring-boot-starter-webflux`를 썼더니 "No qualifying bean" — Boot 4는 webflux(리액티브 서버)와 webclient(HTTP 클라이언트)가 완전히 분리된 스타터다(jar 내부 클래스 목록으로 직접 확인, `spring-boot-webflux` 모듈엔 서버용 자동구성만 있고 WebClient 관련은 전혀 없음). `spring-boot-starter-webclient`로 교체해 해결.
  - **⚠️ 함정 2**: `RoutePlan.polyline`에 `@Lob`을 붙였더니 Postgres 컬럼이 `text`가 아니라 `oid`(Large Object 참조)로 매핑됨 — 수 KB 짜리 JSON 좌표배열일 뿐인데 대용량 객체로 저장하면 트랜잭션 경계 밖 재조회 시 깨질 수 있는 함정. `@Column(columnDefinition="TEXT")`로 교체.
  - **로컬 개발 DB 재시딩 필요**: 스키마 변경(6a 좌표 필드 추가) 후 기존 세션에서 이미 시드가 들어가 있으면 `ddl-auto: update`가 신규 컬럼만 NULL로 추가하고 기존 행은 채워주지 않는다 — `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` 후 재기동해 `DataInitializer`가 새 좌표까지 포함해 재시딩하도록 함(로컬 전용 조작, 운영 데이터 아님).
  - curl E2E 검증(2026-07-19, 6a~6d): 한빛학원 depot(37.5075,127.0355)+bus3 학생 3명 dropoff 좌표 확인 → `POST /api/route-plans/generate`(DROPOFF) 실제 OSRM 응답으로 `totalDistanceM=3651.3`/`totalDurationS=391.5`/정차 3건(누적 ETA 89·224·392초)/실polyline(2078자) 생성 → 재생성 시 `version` 1→2 증가 확인 → PICKUP 방향(역순+depot후미) 생성 확인 → 하차좌표 없는 버스로 시도 시 학생명 나열한 400 확인 → 상세/목록 조회 정상 → 미인증 401/학부모 403 가드 확인. Kafka·직렬화 에러 없음.
  - **6e(2026-07-20 구현+curl E2E 검증 완료)**: `attendance-approved`·`schedule-result`(APPROVED만) 두 토픽을 `routing/infrastructure/impl/RoutingReplanEventConsumer`가 구독 → `RoutingCommandService.replanForStudent(studentId, eventDate)`가 학생의 배정 버스를 찾아 **그 버스·방향의 "최신" RoutePlan.serviceDate가 이벤트 날짜와 일치할 때만**(= 실제로 영향받는 계획일 때만) `buildPlan(status=RECOMMENDED)`으로 재계산·버전증가 저장 → `RoutePlanRecommendedEvent` 발행 → `notification`의 `DomainEventNotificationConsumer.onRoutePlanRecommended`가 `NotificationType.ROUTE_RECOMMENDED`(신규 enum 상수, 관리자는 테넌트 토픽 브로드캐스트로 수신)로 발송. `generate()`/`replanForStudent()` 공통 로직은 `buildPlan(bus, direction, serviceDate, status)`로 추출(`RoutePlan.builder()`도 `status`를 하드코딩 `DRAFT` 대신 명시 파라미터로 받도록 변경). replan 중 예외(로스터 0명·정원초과·경로 API 오류)는 `RuntimeException`으로 잡아 로그만 남기고 기존 계획은 보존(새 행만 시도, 파괴적 아님).
    - **⚠️ 설계 결정**: `schedule-result`는 `notification` 모듈이 이미 같은 토픽을 구독 중이라, `RoutingReplanEventConsumer`에 **별도 consumer group**(`school-bus-backend-routing`)을 명시하지 않으면 같은 그룹(`school-bus-backend`, 전역 기본값)으로 묶여 두 소비자가 파티션을 나눠 갖는 경쟁 소비가 되어 한쪽만 메시지를 받는다 — Kafka에서 "여러 로직이 같은 토픽을 각자 전부 받는" 팬아웃을 하려면 로직마다 다른 group-id가 필수라는 함정을 6e에서 처음 마주쳐 명시적으로 반영.
    - **⚠️ 알려진 MVP 한계**: `ScheduleChangeRequest` 승인은 `AttendanceQueryService.getActiveRoster`(결석만 반영)에 영향을 주지 않으므로, 일정변경으로 트리거된 replan은 현재 알고리즘 버전에서는 보통 이전 버전과 동일한 정차 순서를 재생산한다(그래도 버전 증가 + RECOMMENDED로 감사이력·관리자 알림은 남는다) — 학생별 시간창을 고려하는 알고리즘은 이번 범위 밖.
    - `ScheduleResultEvent`에 `requestedDate` 필드 추가(기존 필드에 추가, 소비자 쪽 하위호환 문제 없음 — 로컬 개발 단계라 별도 마이그레이션 불필요) — replan 대상 날짜 판단에 필요.
    - **⚠️ 함정 3 (신규 enum 상수 + `ddl-auto: update`)**: `NotificationType`에 `ROUTE_RECOMMENDED`를 추가했더니 실제 발송 시 `notification_log_type_check` Postgres CHECK 제약 위반(`SQLState 23514`)으로 조용히 재시도만 반복(Kafka 컨슈머가 예외를 삼키고 로그만 남김, 앱 자체는 안 죽음). 원인: Hibernate ORM 7이 `@Enumerated(EnumType.STRING)` 컬럼에 대해 **테이블 최초 생성 시점의 enum 값 목록으로 CHECK 제약을 굽고, `ddl-auto: update`는 새 컬럼 추가만 할 뿐 기존 CHECK 제약은 갱신하지 않는다** — 6a의 "신규 컬럼 NULL 미채움" 함정과 같은 계열(스키마 변경 vs `ddl-auto: update`의 한계)이지만 이번엔 컬럼이 아니라 제약이 대상. 로컬은 기존과 동일하게 `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` 후 재기동해 해결(재시딩하면 CHECK 제약도 최신 enum 전체로 재생성됨) — **운영에서는 Flyway 등 마이그레이션 도구로 `ALTER TABLE ... DROP/ADD CONSTRAINT`를 명시적으로 실행해야 하는 항목**으로 남겨둔다(§11.4 로드맵에 Flyway 전환이 예정돼 있음).
    - **✅ curl E2E 검증(2026-07-20, docker compose postgres/redis/kafka + host `bootRun`)**: bus1(3호차, 한빛학원) DROPOFF·PICKUP 각각 `v1 DRAFT`(학생 3명: 김민준·이서연·박도윤) 생성 → 학부모가 김민준 오늘자 결석신고 → 관리자 승인 → **두 방향 모두 `v2 RECOMMENDED`(김민준 제외 2명)로 자동 재계산 + 관리자에게 `ROUTE_RECOMMENDED` 알림 2건(하원/등원) 도착** 확인 → 학부모가 이서연 오늘자 시간변경 신청 → 관리자 승인 → **`v3 RECOMMENDED`(로스터는 알고리즘 미반영으로 동일, 위 "알려진 MVP 한계"대로) + `ROUTE_RECOMMENDED` 알림 2건 추가 도착** 확인, 동시에 기존 `SCHEDULE_RESULT` 알림도 정상 병행 발송(=`schedule-result` 토픽을 `notification`·`routing` 두 소비자가 그룹 분리로 각자 전량 수신하는 팬아웃이 실제로 동작함을 증명) → **반려 케이스**(이서연 재신청 후 관리자 반려) → 새 버전 생성 없음(`v3`가 최대로 유지) 확인 → **날짜 불일치 케이스**(이서연 내일자 결석신고 승인, 최신 계획은 오늘자) → 최신 계획의 `serviceDate`와 이벤트 날짜가 달라 replan 스킵, 버전 변화 없음 확인. 서버 로그에 Kafka·직렬화 에러 없음(macOS Netty DNS 리졸버 경고 1건은 무관한 기존 이슈).
  - **다음 착수점(6f)**: §12.3 Phase 6 하위 체크리스트 6f(관리자 승인/배포 API+기사 조회) 참조. **6f의 APPROACH/NO_SHOW 알림 스케줄러는 사용자 확정으로 Phase 7(기사 운행관리, 운행 세션 시작시각 기록)로 명시 보류** — 도착 예상시각 계산에 필요한 실제 운행 시작시각을 기록하는 모듈이 아직 없어서, 근사치로 대체하지 않기로 함.

### 12.2 다음 작업 백로그 (권장 순서)

- [x] ~~기본정보 등록 API~~ — user·tenant·student 서비스·컨트롤러(+멀티테넌시 격리) 완료.
- [x] ~~notification 모듈~~ — NotificationLog + dedup_key 멱등 + 발송 추상화 + 임계값 상수화 완료. BOARD_DONE/ALIGHT_DONE/SOS/SCHEDULE_RESULT 트리거 연동. NO_SHOW/APPROACH 트리거는 routing(Phase 6) 구현 시 연결.
- [x] ~~sos 모듈~~ — SosEvent(OPEN→ACKNOWLEDGED→RESOLVED) + 3분 에스컬레이션 완료. 발신/에스컬레이션 모두 notification dedup 재사용으로 중복 방지 검증됨.
- [x] ~~실시간 채널(WebSocket push)~~ — §11.4 Phase 3 완료(2026-07-19 E2E 검증). 체크리스트는 §12.3 참조.
> 아래 항목은 §11.4 로드맵 Phase 4~8로 확정·상세화됨(2026-07-19). 커밋 단위 체크리스트는 §12.3.
- [x] ~~attendance 서비스화~~ (Phase 4) — 승인 상태전이 + command/query/controller + 당일 명단 스킵 헬퍼 완료(2026-07-19, 커밋 `de79104`). §12.3 Phase 4.
- [x] ~~schedule 모듈~~ (Phase 5) — ScheduleChangeRequest 승인 워크플로 + `SCHEDULE_RESULT` 알림 최초 연결 완료(2026-07-19, 커밋 `94f99c6`). §12.3 Phase 5.
- [x] ~~routing 모듈 6a~6e~~ (Phase 6 앞부분) — 하차지/depot 좌표 + `MapRouteClient`/`RouteEngine` 포트 + sweep·NN·2-opt + `RoutePlan` 생성 API(6a~6d, 2026-07-19, 커밋 `f4b88e5`~`a214fd6`) + attendance/schedule 승인 이벤트 소비 국소 replan(6e, 2026-07-20, curl E2E 검증 완료) 완료. **6f(승인/배포 API+기사 조회, APPROACH/NO_SHOW 알림)는 미완, 알림은 Phase 7 이후로 보류.** §12.3 Phase 6.
- [ ] **기사 운행관리** (Phase 7) — 운행 세션/일일 로그(운행 전 체크리스트는 정식 출시 때 복원). §12.3 Phase 7.
- [ ] **Mock → 실 GPS 전환** (Phase 8) — 플래그 토글 + 8장 법적 선행요건(동의 UI·스키마). §12.3 Phase 8.

### 12.3 아키텍처 리팩터 체크리스트 — Kafka + CQRS + 실시간 push (§11.4 상세)

> 목표 패키지 레이아웃(모듈당): `controller/ command/ query/ domain/ event/ projection/ repository/ dto/ infrastructure/`. 각 Phase 경계에서 `./gradlew build` green 확인 후 커밋.

**Phase 0 · 컨벤션 단일화**
- [x] git init + baseline commit
- [x] `reference.md`는 삭제하지 않고 읽기 쉽게 정리해 Claude 참조용 컨벤션 소스로 유지 (2026-07-18 결정 — HTML 재로딩 토큰비용 문제로 삭제 계획 철회)
- [x] `CODE_CONVENTIONS.html`의 "reference.md는 삭제되었다" 콜아웃(91번째 줄) 정정 완료 (사용자 명시 요청으로 진행, 2026-07-18)
- [x] `CLAUDE.md` 컨벤션 문구 갱신 (reference.md를 컨벤션 우선 참조 문서로 지정, HTML 수정은 요청 시에만)
- [x] `PROJECT_MASTER_PLAN.md` §11.3/§11.4 갱신 (이 항목)
- [x] Phase 0 변경사항 커밋 (`1b73734`)

**Phase 1 · 공용 기반 (Kafka + Redis + 이벤트 백본)** — ✅ 완료, 커밋 `11f726d`
- [x] `backend/build.gradle`에 `spring-kafka` 추가
- [x] docker-compose에 Kafka(KRaft 모드, Zookeeper 불필요) 서비스 추가
- [x] `application.yml`에 `spring.kafka.*`(producer/consumer JSON) 설정, prod는 `${KAFKA_BOOTSTRAP_SERVERS}`
- [x] `global/config/RedisConfig` 신규 (RedisTemplate/ConnectionFactory)
- [x] `global/event/DomainEvent`(공통 필드: eventId·occurredAt·tenantId) + `DomainEventPublisher`(Port) 신규
- [x] `global/infrastructure/KafkaEventPublisher`(impl, KafkaTemplate 래핑) 신규
- [x] DB↔Kafka 이중쓰기 정합성 패턴 확립: `ApplicationEventPublisher`(인프로세스) → `@TransactionalEventListener(phase=AFTER_COMMIT)` → `KafkaEventPublisher.publish()` (`TransactionalDomainEventRelay`)

**Phase 2 · 기존 12개 모듈 CQRS+이벤트 재편** (순서: 잎 모듈 먼저)
- [x] `notification` — CQRS(`command`/`query`) 분리 + `domain`/`infrastructure` 패키지 재편 완료(엔티티→`domain/`, `NotificationSender` 포트→`infrastructure/{spec,impl}/`, `NotificationService`→`NotificationCommandService`+`NotificationQueryService`). `@KafkaListener` consumer 전환은 아직 아님 — 발신 모듈(rideevent·sos·location) 재편 후 다음 체크리스트 항목에서 한 커밋으로 스위치, 그 전까지는 기존 직접 호출(`notificationCommandService.notify(...)`) 유지
- [x] `student` · `tenant` · `user` · `bus` · `route` — CQRS(`command`/`query`) 분리 완료. reference.md §2 기준 단순 CRUD라 spec/impl 분리 없이 concrete 클래스로 전환(기존 5개 spec 인터페이스+impl 삭제), 각 컨트롤러는 Command+Query 두 서비스 주입으로 변경. `domain/` 패키지 리네임(엔티티→domain)은 이번 범위 밖(blast radius 큼, 별도 검토). 커밋 `dc057a7`
- [x] `rideevent` · `sos` · `location` — CQRS(`command`/`query`) 분리 완료(reference.md §11.3 기준 단순 CRUD류라 concrete 클래스, spec/impl 없음). 직접호출→이벤트 전환도 함께 완료: `StudentBoardedEvent`/`RideCompletedEvent`/`SosTriggeredEvent`/`SosEscalatedEvent`/`StudentConnectionLostEvent` 5종 신규, 발신 3모듈은 `notificationCommandService.notify(...)` 직접 호출 대신 `ApplicationEventPublisher.publishEvent(...)` 발행으로 전환. 알림 모듈에 `DomainEventNotificationConsumer`(`@KafkaListener` 5개)를 추가해 Phase 1 DomainEvent 파이프라인(AFTER_COMMIT→Kafka)의 첫 실사용처가 됨 — 발신·수신 양쪽 재편 후 한 커밋 스위치 원칙대로 진행. 부수적으로 `LocationServiceImpl.reportSelf()`의 `@Transactional(readOnly=true)` 버그(내부에서 쓰기 메서드 `ingest()` 호출)도 `@Transactional`로 수정. 커밋 `6d57e02`
- [x] `auth` — CQRS(`command`/`query`) 분리 완료: `AuthService`(spec/impl) → `AuthCommandService`(signup) + `AuthQueryService`(login/refresh, DB 쓰기 없는 순수 조회+토큰 발급이라 Query로 분류). reference.md §2 기준 실제 대체 구현체가 없어 spec/impl 없이 concrete 클래스로 전환. auth는 repo만 호출하고 다른 모듈 서비스를 직접호출하지 않아 이벤트 전환 대상 없음("필요 범위만"의 의미). `MemberCommandService` Javadoc의 `AuthService.signup` 참조도 `AuthCommandService.signup`으로 갱신. 커밋 `aa46d80`
- [x] 각 모듈 완료마다 `./gradlew build` green + 커밋 — Phase 2 전 모듈(`notification`/`student·tenant·user·bus·route`/`rideevent·sos·location`/`auth`)에서 매번 확인 완료

**Phase 3 · location 실시간 push (플래그십)** — ✅ 완료, 커밋 `035af35`(3a)·`21ef52b`(3b)·`494da3d`(3c)·`1aa1184`(3d)·`4276ec5`(3e)·`26fd18c`+`e19272a`(3f 수정)
- [x] `LocationServiceImpl.ingest()`에서 저장 후 `LocationUpdatedEvent` 발행(AFTER_COMMIT→Kafka)
- [x] `InMemoryLocationRepository` → `RedisLocationRepository`(infrastructure, TTL+다중 인스턴스)로 교체, `LocationRepository` Port 덕에 `LocationService` 무수정
- [x] `WebSocketConfig`에 user destination 활성화(`setUserDestinationPrefix("/user")`), projection consumer가 `convertAndSendToUser(principal, "/queue/location", payload)`로 본인에게만 push
- [x] `StompAuthChannelInterceptor`에 SUBSCRIBE 인가 추가 — 테넌트 브로드캐스트 토픽(`/topic/tenant/{id}/**`)을 관리자 권한과 대조(개인 큐는 user-destination 라우팅 자체가 구조적으로 격리)
- [x] `NotificationSender` Port에 `WebSocketNotificationSender`(infrastructure) 추가 — `LogNotificationSender`와 병행 등록
- [x] end-to-end 검증(2026-07-19, docker compose postgres+redis+kafka + host `bootRun`, STOMP 프로브 스크립트로 수행): 자녀 위치 구독→push 도착 / 타 테넌트 구독 거부(자기 테넌트는 허용) / BOARD_DONE push / SOS 발신 즉시 push + 3분 에스컬레이션 push 모두 확인
- [x] 검증 후 §12.1 `location` 행에 "실시간 push" 명시, 이 §12.3 체크리스트 전항목 완료 처리

**Phase 4 · attendance 서비스화 (결석/휴원 승인 → 명단 스킵)** — ✅ 완료(2026-07-19, 커밋 `de79104`), 템플릿: `sos` 모듈
- [x] `attendance/entity/AttendanceException`에 `approve(Long adminUserId)`/`reject(Long adminUserId)` 상태전이 메서드 추가(`SosEvent` 패턴, PENDING 아닌 상태에서 호출하면 `BusinessException(ErrorCode.CONFLICT, "이미 처리된 신청입니다")`)
- [x] `AttendanceCommandService`(신청 create — 학부모가 본인 자녀만, `StudentGuardianRepository.findByGuardianId`로 검증 + 관리자 approve/reject) + `AttendanceQueryService`(학부모 자녀별/관리자 테넌트별, `TenantGuard.resolveTenantId` 재사용) + `AttendanceController`(`/api/attendance-exceptions`) + 요청/응답 record(`CreateAttendanceExceptionRequest`/`AttendanceExceptionResponse`)
- [x] 당일 명단 스킵 헬퍼 — `AttendanceQueryService.getActiveRoster(busId, date)`가 `StudentRepository.findByAssignedBusId` 파생 명단에서 `AttendanceExceptionRepository.findByStudentIdAndTargetDate`로 승인된 결석 학생을 제외해 반환. 다른 모듈 배선은 하지 않고 헬퍼로만 노출(rideevent 명단·Phase 6 routing이 향후 직접 호출해 소비)
- [x] 승인 시 `AttendanceApprovedEvent` 발행(AFTER_COMMIT → Kafka `attendance-approved` 토픽, 현재 소비자 없음 — Phase 6 replan 트리거로 구독 예정)
- [x] `./gradlew build` green + docker compose(postgres/redis/kafka)+`bootRun` curl E2E 검증(생성/권한가드/승인/중복승인 409/조회/Kafka 발행 확인) + 커밋 `de79104`

**Phase 5 · schedule 모듈 (일정변경 승인 워크플로)** — ✅ 완료(2026-07-19, 커밋 `94f99c6`), greenfield, `SCHEDULE_RESULT` 최초 연결
- [x] `schedule/entity/ScheduleChangeRequest`(tenantId/studentId/requestedDate/requestedTime/reason, status `ApprovalStatus` 재사용, `approve/reject` 상태전이 — attendance와 동일 PENDING 가드, `CONFLICT` 409) + `ScheduleChangeRequestRepository`
- [x] `ScheduleCommandService`(신청은 학부모가 본인 자녀만, `StudentGuardianRepository`로 검증) + `ScheduleQueryService`(자녀별/테넌트별 조회) + `ScheduleController`(`/api/schedule-change-requests`, attendance와 동일 구조)
- [x] 승인/반려 **둘 다** `ScheduleResultEvent`(과거형, `status` 필드 포함) 발행(command에서 `ApplicationEventPublisher.publishEvent`, 직접호출 없음) → topic `schedule-result` → `DomainEventNotificationConsumer`에 `onScheduleResult` `@KafkaListener` 추가 → `NotificationType.SCHEDULE_RESULT`로 `notify`(`dedupKey` 헬퍼, stage=`request:{id}`)
- [x] `./gradlew build` green + docker compose(postgres/redis/kafka)+`bootRun` curl E2E 검증(생성/권한가드/승인/반려/재승인 409/조회 + `notification_log`에 SCHEDULE_RESULT 승인·반려 메시지 저장 확인, Kafka 직렬화 에러 없음) + 커밋 `94f99c6`

**Phase 6 · routing 모듈 (자동배치 최적화, 헤드라인)** — 6a~6f 하위단계, 각 경계 build green + 커밋. **6a~6e 완료(6a~6d 2026-07-19, 6e 2026-07-20), 6f 미완**
- [x] **6a** — `student/entity/Student`에 `dropoffAddress`·`dropoffLat`·`dropoffLng` + `updateDropoff()` + `PATCH /api/students/{id}/dropoff`. 등원 좌표는 기존 `boardingStop`(Stop.lat/lng) 재사용, 하원은 신규 하차지. **추가로 `tenant/entity/Tenant`에 `lat`·`lng`(depot) + `updateLocation()` + `PATCH /api/tenants/{id}/location`도 함께 추가**(마스터플랜에 없던 갭 — sweep 알고리즘엔 학원 기준점이 필수인데 Tenant에 좌표가 전혀 없었음). `ddl-auto: update` 자동 반영. 커밋 `f4b88e5`
- [x] **6b** — `global/config/WebClientConfig`(첫 `@Bean WebClient`, connect 3s/read·write 5s 타임아웃) + `routing/infrastructure` `MapRouteClient` 포트. 어댑터 `OsrmMapRouteClient`(기본값 `routing.provider=osrm`, `router.project-osrm.org` 공개 데모서버 — 키 불필요, curl로 실동작 확인) + `NaverMapRouteClient`(`routing.provider=naver`, NCP Direction 15 스펙 구현, 실키 없어 미검증). `@ConditionalOnProperty`로 단일 활성 빈 선택. `application.yml`에 `routing.max-waypoints: 15` 추가. **geocode API는 만들지 않음** — Student/Tenant 좌표를 관리자가 직접 입력하는 구조라(Stop과 동일 패턴) 주소→좌표 변환이 필요 없음. 커밋 `fcfc48f`+`b5def33`(WebClient.Builder 의존성 수정)
- [x] **6c** — `RouteEngine` 포트 + `HeuristicRouteEngine`: sweep(depot 기준 방위각 정렬로 결정적 초기순서) + nearest-neighbor(그리디 구성) + 2-opt(경계간선만 비교하는 O(1) delta, 개선 없을 때까지 반복). 거리 Haversine(`routing/domain/GeoMath`, 외부 호출 0). 단위테스트 4개(빈입력/단일점/순열검증/지그재그배치 총거리 비교). 커밋 `e852c37`
- [x] **6d** — `routing/entity/RoutePlan`(status DRAFT/RECOMMENDED/APPROVED/PUBLISHED **도메인 메서드**(approve/publish)는 구현, version, busId, direction 등원/하원, serviceDate, polyline(TEXT), totalDistanceM, totalDurationS, approvedBy/publishedBy) + `RoutePlanStop`(seq, studentId, lat/lng, etaSeconds). 생성 API(`POST /api/route-plans/generate`): `AttendanceQueryService.getActiveRoster`로 당일 로스터(결석 자동제외) → `Bus.seatCapacity` 하드 정원검증 → 6c 배치·순서 → 방향별 waypoint 구성(DROPOFF=depot 선두/PICKUP=역순+depot 후미) → 6b directions(경유지 15개 초과 시 경계공유 구간분할 후 병합) → `RoutePlan(DRAFT)`. `RoutingQueryService`+`RoutingController`(상세/목록 조회). **승인/배포 컨트롤러 엔드포인트는 6f 몫이라 아직 없음**(엔티티 도메인 메서드만 완성). 커밋 `3e32178`+`a214fd6`(polyline 컬럼 `@Lob`→`TEXT` 수정)
- [x] **6e** — Phase 4/5 승인 이벤트 소비(`attendance-approved` 전량, `schedule-result`는 APPROVED만) → 해당 학생 배정 버스·방향만(최신 계획의 serviceDate가 이벤트 날짜와 일치할 때만) 국소 replan → `RoutePlan(RECOMMENDED)` 저장 → `RoutePlanRecommendedEvent` 발행 → 관리자 `NotificationType.ROUTE_RECOMMENDED` 알림(신규 enum). `RoutingReplanEventConsumer`는 `schedule-result` 토픽을 `notification` 모듈과 함께 구독하므로 별도 consumer group(`school-bus-backend-routing`) 필수(경쟁소비 방지). 2026-07-20 구현+curl E2E 검증 완료(docker compose postgres/redis/kafka+host `bootRun`), `./gradlew build`(DB 필요한 `contextLoads` 제외) green.
- [ ] **6f** — 관리자 승인/거부 API(`APPROVED`/`PUBLISHED`) + 드라이버 그룹 `PUBLISHED` 수신(pull 또는 STOMP push). `NotificationType.APPROACH`(근접 5분)·`NO_SHOW`(미탑승 10분)는 **Phase 7(기사 운행관리, 운행 세션 시작시각 기록) 이후로 보류 확정(2026-07-19)** — 도착 예상시각 계산에 실제 운행 시작시각이 필요한데 그걸 기록하는 모듈이 아직 없어 근사치로 대체하지 않기로 함
- [x] 6a~6d 검증(2026-07-19): 정원 초과 배정 0(물리 seatCapacity 하드 가드) / 재생성 시 버전 증가(2-opt 재계산 확인) / directions polyline·ETA 채워짐(실 OSRM 응답) / 하차좌표 누락 학생 목록화된 400 / DROPOFF·PICKUP 양방향 정상.
- [x] 6e 검증(2026-07-20): 결석 승인→국소 replan(로스터 감소)+관리자 알림 / 일정변경 승인→국소 replan+관리자 알림(schedule-result 팬아웃 확인) / 반려 시 replan 없음 / 날짜 불일치 시 replan 스킵. **⬜ 남은 검증**: 승인→배포(6f) / 근접·미탑승 push(6f, Phase 7 이후)

**Phase 7 · 기사 운행관리 (운행 세션/일일 로그)**
- [ ] 운행 세션(시작/종료) + 일일 운행 로그 엔티티 + 레포 (운행 전 체크리스트는 정식 출시 때 복원)
- [ ] command/query/controller — 기사(DRIVER/ATTENDANT) 운행 시작·종료·조회, rideevent 명단(사진·이름·하차지) 연계
- [ ] `./gradlew build` green + 커밋

**Phase 8 · Mock → 실 GPS 전환**
- [ ] `app.location.mock.enabled`/`gps.enabled` prod 토글(코드는 Phase 3에서 `MockLocationSource`/`PhoneGpsSource` 포트 분리 완료 → 소스만 교체)
- [ ] 8장 법적 선행요건 — 위치정보 동의 UI 계약·동의 이력 스키마 충족, 백엔드는 동의 이력 저장/검사 슬롯 제공
- [ ] `./gradlew build` green + 커밋
