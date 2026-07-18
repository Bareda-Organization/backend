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

### 11.4 아키텍처 리팩터 로드맵 — Kafka + CQRS + 실시간 push (진행 중)

> 배경: `reference.md`가 기존 코드/컨벤션과 3곳(단순 CRUD 인터페이스·패키지 구조·모듈 간 직접호출)에서 충돌해, **전면 채택 + 기존 12개 모듈 재편**을 결정. 다음 신규 기능(location 실시간 push)을 새 아키텍처의 첫 실적용 사례로 삼는다. 상세 체크리스트는 §12.3.
>
> ⚠️ **가드레일**: 한 번에 컴파일 안 되는 거대 변경 금지 — 모듈 단위로 재편하고 **각 Phase 경계에서 `./gradlew build`(테스트 포함) green + 커밋** 후 다음으로. cross-module 이벤트 전환(직접호출→Kafka)은 발신·수신 양쪽 모듈이 모두 재편된 뒤 **한 커밋에서만** 스위치(그 전까지 기존 직접 호출 유지 → 항상 빌드 가능).

| Phase | 내용 | 상태 |
|---|---|---|
| 0. 컨벤션 단일화 | `reference.md`(Claude 참조용, 정리) + `CODE_CONVENTIONS.html`(사람용 렌더) 역할분리 확정, `CLAUDE.md`/이 문서 §11.3 갱신 | ✅ 완료 |
| 1. 공용 기반 | Kafka(KRaft)+Redis 인프라, `DomainEvent`/`DomainEventPublisher`(Port)+`KafkaEventPublisher`(impl), DB↔Kafka 이중쓰기 정합성(`ApplicationEventPublisher`→`@TransactionalEventListener(AFTER_COMMIT)`→Kafka) | ✅ 완료 |
| 2. 기존 12개 모듈 재편 | CQRS 분리 + 인터페이스 정리 + 직접호출→이벤트. 순서(잎 먼저): `notification` → `student·tenant·user·bus·route` → `rideevent·sos·location` → `auth` | ⬜ 예정 |
| 3. location 실시간 push (플래그십) | `LocationUpdatedEvent`→Redis projection→WebSocket user destination push(`convertAndSendToUser`), SUBSCRIBE 인가(계층별 조회 권한을 push 구독에도 강제), 알림도 WebSocket으로 병행 push | ⬜ 예정 |

- **왜 지금 Kafka인가**: 단일 모놀리식 앱에는 엄밀히 과설계지만, MSA 전환 대비(§17 reference.md)라는 목적 하에 사용자가 트레이드오프를 인지하고 선택.
- **후속(이번 범위 밖)**: `attendance`·`schedule`·`routing` 상세 구현 계획은 이 리팩터와 독립 — §12.2 백로그에 유지, 이번 Phase 0-3 완료 후 재검토.

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
| `location` (포트·Mock·GPS·스케줄러) | — | ✅ | ✅ | ✅ | ✅ 완료 |
| `user` (User·UserTenantRole) | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `tenant` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `student` (Student·StudentGuardian) | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `attendance` | ✅ | ✅ | ⬜ | ⬜ | 🟡 부분 |
| `notification` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `sos` | ✅ | ✅ | ✅ | ✅ | ✅ 완료 |
| `schedule` (ScheduleChangeRequest) | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ 예정 |
| `routing` (OSRM·네이버 ETA) | — | — | ⬜ | ⬜ | ⬜ 예정 |

진행률: **완료 11 / 부분 1 / 예정 2** (총 14 모듈)

- `user`/`tenant`/`student`: `MemberController`+`MemberServiceImpl`, `TenantController`+`TenantServiceImpl`, `StudentController`+`StudentServiceImpl`(배정·보호자 연결 포함) 모두 구현 완료. 등록·조회 모두 `TenantGuard`로 학원 격리 검사.
- 사용자 계층·권한(2장) 요구사항 — 5계층 `Role` + `UserTenantRole` N:M + `TenantGuard`(학원관리자 자기 학원만/플랫폼관리자 지정 학원) + `RideEventQueryService`의 역할별 조회 스코핑(`getMyRecords`/`getChildrenRecords`/`getRosterRecords`/`getTenantRecords`)까지 구조적으로 완료. PARENT "알림함"은 `notification` 모듈 완료로 함께 해소됨. 남은 건 DRIVER "메모"(기사 운행관리 모듈 몫)뿐 — 권한 구조 자체가 아니라 해당 기능 모듈 미구현 때문.
- `notification`: `NotificationLog`(dedupKey unique) + `NotificationLogRepository` + `NotificationCommandService`(멱등 `notify` + `dedupKey` 공식 헬퍼) + `NotificationQueryService` + `NotificationSender` 포트(`LogNotificationSender` MVP 구현, `LocationSource`와 동일한 추상화 패턴) + `NotificationController`(`/api/notifications/children` PARENT, `/api/notifications` 관리자) 모두 구현. `NotificationThresholds`(10/5/3분) 상수 정의. **트리거는 BOARD_DONE/ALIGHT_DONE, SOS만 연동됨** — Phase 2에서 직접호출(`RideEventCommandService.record()`, `SosCommandService`가 `NotificationCommandService.notify`를 직접 호출)에서 Kafka 도메인 이벤트 경유(`DomainEventNotificationConsumer`의 `@KafkaListener`가 `notify` 호출)로 전환됨 — NO_SHOW(정류장 도착 감지 필요)·APPROACH(ETA 필요)·SCHEDULE_RESULT(schedule 모듈 필요)는 해당 모듈이 만들어질 때 같은 이벤트 발행 패턴을 따르도록 남겨둠.
- `sos`: `SosEvent`(단방향 상태전이 `acknowledge`/`resolve` 도메인 메서드, 잘못된 전이는 `CONFLICT` 409) + `SosEventRepository` + `SosCommandService`(발신/확인/종료) + `SosQueryService`(역할별 4계층 조회) + `SosController` + `SosEscalationScheduler`(`app.sos.escalation-check-ms`, 기본 30초 폴링) 모두 구현. 발신 즉시·에스컬레이션(3분 미확인) 모두 `NotificationCommandService`의 dedupKey 멱등을 그대로 재사용해 "같은 이벤트 중복 알림 없음"을 스케줄러 코드 없이 보장. 실제 서버 기동 후 발신→확인→종료 상태전이, 권한 가드(403)·상태 가드(409), 백데이트 이벤트로 에스컬레이션 1회만 발송됨을 curl로 검증 완료.

### 12.2 다음 작업 백로그 (권장 순서)

- [x] ~~기본정보 등록 API~~ — user·tenant·student 서비스·컨트롤러(+멀티테넌시 격리) 완료.
- [x] ~~notification 모듈~~ — NotificationLog + dedup_key 멱등 + 발송 추상화 + 임계값 상수화 완료. BOARD_DONE/ALIGHT_DONE 트리거 연동. NO_SHOW/APPROACH/SCHEDULE_RESULT 트리거는 각 모듈(routing·schedule) 구현 시 연결.
- [x] ~~sos 모듈~~ — SosEvent(OPEN→ACKNOWLEDGED→RESOLVED) + 3분 에스컬레이션 완료. 발신/에스컬레이션 모두 notification dedup 재사용으로 중복 방지 검증됨.
- [ ] **실시간 채널(WebSocket push)** — §11.4 Phase 3로 확정·상세화됨. 진행 체크리스트는 §12.3 참조.
- [ ] **schedule 모듈** — ScheduleChangeRequest 승인 워크플로(대기/승인/반려 → 명단 자동 갱신). Phase 0-3(§11.4) 완료 후 착수.
- [ ] **attendance 서비스·컨트롤러** — 결석·휴원 승인 시 해당 날짜 명단에서 학생 스킵. Phase 0-3(§11.4) 완료 후 착수.
- [ ] **routing 클라이언트** — OSRM(MVP) → 네이버 Directions, ETA 계산 · 근접 5분 알림 연동. Phase 0-3(§11.4) 완료 후 착수.
- [ ] **기사 운행관리** — 운행 세션/일일 로그(운행 전 체크리스트는 정식 출시 때 복원).
- [ ] **Mock → 실 GPS 전환** — 플래그 토글 + 8장 법적 선행요건(동의 UI·스키마) 충족.

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
- [ ] `auth` — CQRS 분리(필요 범위만)
- [ ] 각 모듈 완료마다 `./gradlew build` green + 커밋

**Phase 3 · location 실시간 push (플래그십)**
- [ ] `LocationServiceImpl.ingest()`에서 저장 후 `LocationUpdatedEvent` 발행(AFTER_COMMIT→Kafka)
- [ ] `InMemoryLocationRepository` → `RedisLocationRepository`(infrastructure, TTL+다중 인스턴스)로 교체, `LocationRepository` Port 덕에 `LocationService` 무수정
- [ ] `WebSocketConfig`에 user destination 활성화(`setUserDestinationPrefix("/user")`), projection consumer가 `convertAndSendToUser(principal, "/queue/location", payload)`로 본인에게만 push
- [ ] `StompAuthChannelInterceptor`에 SUBSCRIBE 인가 추가 — 구독 destination을 4계층 스코프(본인/자녀/담당버스/테넌트)와 대조
- [ ] `NotificationSender` Port에 `WebSocketNotificationSender`(infrastructure) 추가 — `LogNotificationSender`와 병행 등록
- [ ] end-to-end 검증: 자녀 위치 구독→push 도착 / 권한 밖 구독 거부 / BOARD_DONE push / SOS 에스컬레이션 이벤트 경유 동일 동작
- [ ] 검증 후 §12.1 `location` 행에 "실시간 push" 명시, 이 §12.3 체크리스트 전항목 완료 처리
