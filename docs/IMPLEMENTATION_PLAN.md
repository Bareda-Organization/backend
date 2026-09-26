# 바래다 (BARAEDA) — 구현 계획 · 진행 추적

방향 전환 이후의 구현을 **순서와 상태**로 관리하는 문서. 무엇을 만드는가(사양)와 어떻게 만드는가(설계)는 `docs/` 7종이 정본이며, 이 문서는 그 정본을 **어떤 순서로 · 어디까지** 옮겼는지만 기록.

| 항목 | 내용 |
|---|---|
| 문서 버전 | v1.0 |
| 작성일 | 2026-08-24 |
| 기준 | FEATURE_SPEC v1.0 · PRD v1.0 · USER_FLOWS v1.0 · API_SPEC v1.0 · ARCHITECTURE v1.0 · ERD v1.0 · TECH_DECISIONS v1.0 (2026-08-24) |
| 성격 | **구현 추적의 단일 창구.** 사양·설계의 복제 부재, 규칙 ID 참조만 |
| 대상 코드 | `backend/` (Spring Boot 4.1.0 · Java 25) — **`frontend/` (Flutter) 는 2026-08-25 사용자 확정으로 착수 대상 밖** (Phase F1~F4 절 · §8) |

**자매 문서** — [FEATURE_SPEC.md](./FEATURE_SPEC.md) · [PRD.md](./PRD.md) · [USER_FLOWS.md](./USER_FLOWS.md) · [API_SPEC.md](./API_SPEC.md) · [ARCHITECTURE.md](./ARCHITECTURE.md) · [ERD.md](./ERD.md) · [TECH_DECISIONS.md](./TECH_DECISIONS.md)

---

## 0. 문서 성격 · 사용법

### 0.1 이 문서가 담는 것 · 담지 않는 것

| 담는 것 | 담지 않는 것 |
|---|---|
| Phase 분할과 선행 관계 · 완료 조건 · 산출물 · **기능 단위 TDD 사이클**(§4.6) | 정책 규칙의 내용 (→ `FEATURE_SPEC`) · 채택 이유 (→ `PRD` · `TECH_DECISIONS`) |
| 현 코드의 처분 판정 · 스키마·시드·Swagger 운영 방침 | 엔드포인트 계약 (→ `API_SPEC`) · 테이블 정의 (→ `ERD`) |
| 진행 상태 표 | 모듈 경계 · 파이프라인 (→ `ARCHITECTURE`) |

**이 문서가 추적하는 것은 백엔드뿐.** 2026-08-25 사용자 확정으로 프론트엔드는 착수 대상 밖이며, Phase F1~F4 와 §1.4 프론트엔드 처분은 **다시 범위에 들어올 때를 위한 보존분**이라 진행 상태를 갱신하지 않는다. 사양(`FEATURE_SPEC` 등)의 프론트 요구는 그대로 유효 — **만들 것이 사라진 것이 아니라 지금 만들지 않는 것**.

**정책 내용의 복제 금지.** 30분·±10분·회차당 1회 같은 값을 이 문서에 다시 적으면 사양이 바뀔 때 두 곳이 갈림. 값이 필요하면 `C-04` · `RTE-02` · `NFR-02` 같은 **규칙 ID 로 참조**한다. 이 문서에 값이 등장하는 경우는 **시드 설계**뿐이며, 그 값도 사양에서 유도된 것임을 매번 ID 로 표기.

### 0.2 상태 표기

| 표기 | 뜻 |
|---|---|
| ⬜ 미착수 | 코드 부재 |
| 🟡 진행 | 착수했으나 완료 조건 미통과 |
| ✅ 완료 | **완료 조건 전항 통과** |
| ➖ 범위 밖 | 이번 단계에서 미구현. 두 종류 — ① 사양이 제외한 기능 (`FEATURE_SPEC §7` · `PRD §11`) ② **사양에 남아 있으나 착수 대상 밖** (프론트엔드 F1~F4, 2026-08-25 확정) |

**완료 판정의 기준은 "코드가 있다"가 아니라 "완료 조건을 통과했다".** 그리고 완료 조건을 통과했다는 말은 그 Phase 가 담당하는 **기능 ID 전부가 §4.6 의 TDD 사이클을 마쳤다**는 뜻이다. 각 Phase 의 완료 조건은 Swagger 에서 밟을 수 있는 흐름 또는 통과해야 하는 테스트로 서술돼 있고, 그 항목을 하나라도 못 채우면 🟡 로 남긴다. 컨트롤러가 200을 반환한다는 사실만으로 ✅ 를 붙이면 다음 Phase 가 성립하지 않은 선행 위에 얹힘.

### 0.3 세션 재개 시 읽는 순서

**§8 진행 추적 표**(지금 어느 Phase 인지) → **해당 Phase 절**(범위·선행·완료 조건·산출물) → 그 Phase 가 참조하는 사양 절 → **§4.6 TDD 사이클**(작업의 최소 단위) → **§7 횡단 규칙** → `CLAUDE.md`(빌드·실행 규약, Flyway 정책, 패키지 규약 `src.backend`).

작업이 끝나면 **§8 표를 갱신**하고, 완료 조건 중 미통과 항목이 남으면 그 사실을 표의 비고에 남긴다.

---

## 1. 현 코드 처분 방침

### 1.1 판정의 전제

`ARCHITECTURE §0` 서두가 "현재 코드는 이 문서와 상당 부분 어긋나는 것이 정상"이라고 규정. 방향 전환의 폭이 **도메인 모델 자체**에 걸쳐 있어(공용 정류장 → 승하차지 · `User`↔`Tenant` N:M → 단일 소속 · 이메일 로그인 → form 가입 + 승인 게이트) **도메인 계층은 대부분 재작성 대상**.

⚠ **살릴 것을 과대평가하지 않는다.** 옛 엔티티에 컬럼을 붙여 새 사양을 흉내 내면 `ERD` 의 CHECK 제약·UNIQUE 가 성립하지 않은 채 남고, 그 어긋남은 런타임 결함으로만 드러남. **구조가 다르면 재작성**이 기준.

#### 옛 Ruling 색인 (원문 유실)

아래 40개(31 + Phase 1 규약 문서 인용 9, F3 조율자 추가)는 `IMPLEMENTATION_PLAN` 본문에 판정 원문이 남아 있지 않은 Ruling 번호다. 코드 자바독·`API_SPEC` 이 그 번호를 인용하고 있어 존재는 확인되나, 판정을 내린 문맥 전체는 유실됐다. **인용 문장에서 읽히는 요지만 보존한다** — 원문 복원이 아니라 참조용 색인이다.

| Ruling | 인용처(파일·절) | 인용 문장에서 읽히는 요지 1줄 |
|---|---|---|
| 13 | `audit/entity/AuditLog` · `audit/dto/LoginHistoryItemResponse` · `audit/query/LoginHistoryQueryService` | `AuditLog` 엔티티는 결과 성공/실패·차단 이벤트 여부를 저장하지 않는다 — 조회 시점에 `action` 값에서 다시 계산 |
| 26 | `account/entity/VerificationCode` | `purpose` 값은 `login_id`·`password` 뿐, 재료 문서의 `recover_id`/`recover_password` 는 낡은 값 |
| 29 | `p1-entity-conventions.md §2` | 엔티티의 Lombok 은 두 개(`@Getter`·`@NoArgsConstructor(PROTECTED)`)만 |
| 30 | `p1-entity-conventions.md §1` | FK 는 연관 객체가 아니라 `Long` 원시 필드로 둔다 |
| 31 | `p1-entity-conventions.md §3` | 엔티티 생성은 정적 팩토리로만(공개 생성자·빌더 부재) |
| 32 | `global/common/converter/LowerCaseEnumConverter` | `@Enumerated(EnumType.STRING)` 은 대문자를 그대로 내보내 소문자 CHECK 제약을 위반하므로 공용 변환기가 필요 |
| 33 | `p1-entity-conventions.md §4` | `jsonb` 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)` + `Map<String,Object>`, 전용 record 승격은 소유 Phase 담당 |
| 34 | `location/entity/RunPosition` | 위도·경도는 CHECK 경계(±90/±180) 위반을 막기 위해 `double` 이 아닌 `BigDecimal` 로 받는다 |
| 36 | `global/config/OpenApiConfig` | OpenAPI 태그 목록·순서·설명은 이 클래스 한 곳에서만 관리, 컨트롤러는 `@Tag` 로 소속만 선언 |
| 38 | `manager/entity/Assignment` | 배치 엔티티의 소유 모듈은 `run` 이 아니라 `manager` |
| 39 | `exception/entity/NoShowDecision` | "2개 이상 모듈 공유" 만 `global/common/enums` 로 올린다는 기준 — `no_show_case`·`no_show_contact_attempt` 는 둘 다 `exception` 모듈 소속이라 해당 없음 |
| 50 | `p1-entity-conventions.md §1` | Task 1 보고서의 `@MapsId` 패턴 후보 서술은 규약 확정 전 제안 — 규약(FK `Long`)이 이긴다 |
| 55 | `notification/entity/NotificationLog` · `exception/entity/EmergencyAlert` · `boarding/entity/RiderStatusHistory` · `account/entity/SignupRequest` | 역할·상태류 컬럼(`recipient_role`·`raised_by_role`·상태 이력·가입 역할)은 CHECK 가 없어 스키마가 값을 보장하지 않는다 — 잘못된 값은 다시 읽을 때 `Enum.valueOf` 에서 터진다 |
| 56 | `p1-entity-conventions.md §4.2` | 값이 같아도 엔티티가 다르면 enum 을 분리한다 |
| 57 | `p1-entity-conventions.md §4.3` | `BaseTimeEntity` 가 `created_at`(updatable=false)·`updated_at` 컬럼명을 선언 — 하위 엔티티에서 재선언 금지 |
| 59 | `p1-entity-conventions.md §7.1` | enum 상수 단위 주석 기준 |
| 62 | `notification/entity/NotificationSetting` · `request/entity/BoardingIntent` · `exception/entity/NoShowCase` · `manager/entity/Manager` | `created_at`·`updated_at` 을 한쪽만 갖는 테이블은 `BaseTimeEntity` 를 상속하지 않고 평범한 필드로 두어 `Clock` 에서 얻은 시각을 팩토리 파라미터로 받는다 |
| 67 | `p1-entity-conventions.md §4` | `inet` 컬럼은 `String` + `@JdbcTypeCode(SqlTypes.INET)` — plain `String` 은 validate 실패, `SqlTypes.OTHER` 는 INSERT 시 `bytea` 바인딩 실패(2026-08-25 정정) |
| 71 | `global/security/SecurityConfig`(160행) | 재작성된 권한 부여표(`RolePermissions.HIERARCHY`)를 Phase 2 Task 1 이 자신의 소유가 아니라는 이유로 `SecurityConfig` 반환문에 배선하지 않고 남겨 뒀다 |
| 76 | `global/security/authz/RolePermissions` · `global/security/SecurityConfig`(161행) | Phase 2 Task 3 가 `SecurityConfig.roleHierarchy()` 에서 부여표를 실제로 처음 소비해 배선 — 없으면 모든 인가 애너테이션이 403 |
| 80 | `student/controller/StudentLinkCodeController` | 링크코드 발급 경로가 `/me/students/...` 계열이 아니라 `/me/link-code` 인 것은 사양 문자열 그대로이며 허용목록 키(HTTP메서드+경로)를 흔들지 않으려 임의로 다듬지 않는다 |
| 89 | `global/config/LocalFlywayCleanStrategy` | Gradle `test` 태스크에서만 `clean()` 을 건너뛰고 `migrate()` 만 하게 하는 프로퍼티 — 여러 `@SpringBootTest` 컨텍스트가 한 JVM 에서 로컬 Postgres 를 공유할 때의 처리 |
| 99 | `notification/entity/DeviceToken` | `revokeByTokenHash` 의 "이미 처리된 행은 다시 처리하지 않는다" 는 멱등 처리 축 |
| 102 | `global/config/ApiPathPrefixConfig` · `global/security/SecurityConfig`(72행) | API 버전 접두사는 `ApiPathPrefixConfig` 가 `@RestController` 핸들러 등록 시점에 배선하며, `server.servlet.context-path` 는 쓰지 않는다 |
| 103 | `global/security/PublicEndpoints` | 인증 없이 여는 API 경로(bare path, 접두사 없음) 목록은 이 클래스가 관리 |
| 142 | `account/repository/AccountRepository` · `academy/query/AdminAcademyQueryService` | `user_count` 등 계정 수 집계는 상태 조건을 걸어야 한다 — 걸지 않으면 승인 대기·거절 계정까지 합산돼 `staff_count` 와 집계 기준이 갈린다 |
| 145 | `notification/controller/NotificationSettingController` · `student/controller/WeeklyAddressController` · `student/controller/GuardianChildController` | 대기 중(pending) 계정은 승인된 계정 전용 기능에 접근할 이유가 사양에 없어 게이트 기본값(허용 목록 밖 `403`)을 따른다 |
| 153 | `API_SPEC §5.10` | 스케줄·회차 CRUD 의 `[조정 중]` 을 2026-08-26 해제 — 보류 사유가 실은 동승자 자동 배정(`§5.14`)에만 해당했다 |
| 160 | `student/photo/spec/PhotoStorage` · `student/dto/StudentRegisterRequest` · `student/dto/StudentUpdateRequest` | 요청의 `photo`(멀티파트 파일)와 응답·컬럼의 `photo_url`(서버가 만든 주소)은 다른 값 — 클라이언트 문자열을 그대로 쓰는 경로는 없다 |
| 161 | `student/access/GuardianChildAccess`(50행) | 자녀 연결 시점에 `guardian` 을 만들지 않는다 — 계정 생성(가입 승인, `AUTH-11`)이 먼저 있어야 한다 |
| 162 | `manager/entity/WorkHours` | 형태가 깨진 근무시간 행은 예외로 드러내고 "근무 시간 없음" 으로 삼키지 않는다 — 시드에서 실제로 겪은 형태 |
| 163 | `routing/repository/RouteRepository` · `student/repository/StudentRepository` · `schedule/repository/ScheduleRepository` · `run/repository/RunRepository` · `student/access/GuardianChildAccess`(62행) | `{id}` 로 자원을 지목하는 조회는 학원 조건을 쿼리에 고정해 남의 학원 자원이면 `403` 이 아니라 `404` 로 답한다 |
| 164 | `API_SPEC §5.12` | 같은 학원에 같은 호차 중복 등록·수정은 `409 DUPLICATE_BUS_NO`(2026-08-26 신설) |
| ~~170~~ | `API_SPEC §8.5` | ~~만료되지 않은 연결 요청이 없으면 `404 LINK_REQUEST_NOT_FOUND`~~ — **Ruling 324(2026-09-22)로 대체.** 연결 요청 단계(§3.2) 자체가 없어져 이 에러 코드도 함께 폐지 |
| 171 | `student/dto/StudentSummaryResponse` · `student/dto/StudentWithdrawalResponse` · `student/dto/StudentDetailResponse` | 응답 DTO 의 `studentId` 등 `*_id` 필드는 문자열 타입 |
| 172 | `student/command/StudentCommandService`(37행·85행) | `guardian_student` 연결을 만드는 경로는 자녀 연결(`P-02`)만 소유 — 퇴원 처리는 연결을 해제만 함께 한다 |
| 173 | `student/command/ChildLinkCommandService` | 중복 연결은 선검사든 DB 거부든 같은 `409 ALREADY_LINKED` 로 통일(Ruling 164 의 요구를 이 자리로 확장) |
| 179 | `student/repository/StopMergeLookupImpl` · `student/repository/StopMergeLookup` · `student/repository/StopRepository` | 근접 병합 잠금 범위는 좌표·격자 단위가 아니라 **학원 하나** — 격자 키 UNIQUE 방식은 기각됐다 |
| 180 | `API_SPEC §5.9` | 노선 편성 CRUD 의 `[조정 중]` 을 2026-08-29 부분 해제 — 남은 미확정은 최적화 트리거·가중치(오픈 이슈 G) |
| 185 | `API_SPEC §10` | 오픈 이슈 표는 파생본이라 정본이 닫혀도 자동으로 안 따라온다 — 해제된 행 3개가 실제로 표에 남아 있었다 |

### 1.2 살림 — 빌드 · 인프라 · `global`

| 대상 | 판정 | 근거 |
|---|---|---|
| `backend/build.gradle` | **살림** | `ARCHITECTURE §2.1` 이 현 스택 승계를 명시. Boot 4 신형 아티팩트명(`-webmvc` · `-aspectj` · `-webclient`)이 이미 반영. `bootRun` 의 `.env` 주입 블록도 유지 — 지도 API 키가 미주입이면 노선 계산이 전부 실패 |
| `application.yml` 프로파일 골격 | **부분 살림** | `local`·`prod`·`demo` 3분할, actuator 노출 2개 한정, micrometer 히스토그램, CORS·WS origin 분리는 유지. **`app.location.mock` · `app.sos` · `app.drivesession` · `app.connection` 블록은 옛 도메인 이름이라 폐기** |
| `global/error/` (`BusinessException`·`ErrorCode`·`GlobalExceptionHandler`) | **골격 살림 · 값 교체** | 예외→HTTP 매핑 구조는 유지. `ErrorCode` 상수는 `API_SPEC §8` 사전으로 전면 교체 |
| `global/response/ApiResponse` | **살림 · 형식 대조** | 공통 봉투 개념은 유지하되 `API_SPEC §1.1` 형식과 대조 후 확정 |
| `global/security/` (`JwtTokenProvider`·`JwtAuthenticationFilter`·`SecurityConfig`·`StompAuthChannelInterceptor`) | **골격 살림** | JWT 발급·검증·필터 체인·STOMP 인증 채널 인터셉터는 구조가 그대로 필요 (`C-14` · `ARCHITECTURE §10.2`) |
| `global/security/AuthUser` | **재작성** | 멤버십을 훑는 N:M 전제. 새 모델은 `account.role` + `account.academy_id` 단일 소속 (`ARCHITECTURE §5.3`) |
| `global/security/authz/` (`Permissions`·`RolePermissions` + 메타 애너테이션 21개) | **패턴 살림 · 내용 교체** | 권한 상수 경유 · `RoleHierarchyImpl` 을 부여표로 전용하는 방식은 `FEATURE_SPEC §6.2` 가 요구하는 것과 동일. 상수 **31종**·역할 6종으로 재작성하고 애너테이션 이름은 옛 기능명(`CanRecordRideEvent` 등)이라 폐기 |
| `global/event/` (`DomainEvent`·`DomainEventPublisher`·`TransactionalDomainEventRelay`) | **살림 · 보강** | `AFTER_COMMIT` 발행 지점은 유지. `TECH_DECISIONS §7` 의 아웃박스를 얹어야 함 |
| `global/config/` (`JpaAuditingConfig`·`RedisConfig`·`WebClientConfig`·`WebSocketConfig`) | **살림** | 설정 골격이 새 사양과 충돌 부재 |
| `global/config/OpenApiConfig` | **재작성** | 시드 설명·계정표가 옛 데이터 기준. §3 의 `SeedFixtures` 참조 방식으로 승격 |
| `global/common/BaseTimeEntity` | **살림 · 타입 교체** | 생성·수정 시각 감사 필드. 현재 `LocalDateTime` 인데 `ERD §2` 가 전 시각 컬럼을 `timestamptz` 로 규정 — **`OffsetDateTime` 으로 교체**(`TECH_DECISIONS §6.1`). 이 클래스를 39개 엔티티가 상속하므로 Phase 1 착수 전에 바꿈 |
| `global/common/ApprovalStatus` | **폐기** | 옛 도메인 enum. 새 상태값은 `API_SPEC §9.2` · `§9.6` |
| `global/tenant/TenantGuard` | **패턴 살림 · 재작성** | "역할만으로 부족하다"는 판단은 `ARCHITECTURE §5.1` ③층과 동일. 다만 N:M 멤버십 전제라 코드는 재작성. **✅ 2026-08-25 Phase 2 Task 5 에서 이행** — `global/security/access/AcademyScope` 로 대체하고 원본은 삭제. 호출처 0건이었고 분기 4개 중 3개는 보존, 1개(academyId null 판정)는 `AuthUser` 컴팩트 생성자로 이동 |
| `observability/` (aspect · metrics · listener) | **살림 · 구독 대상 교체** | `@Scheduled` 를 AOP 로 감싸 계측하는 방식은 `ARCHITECTURE §13.3` 의 "확정 배치 도래→완료 지연" 지표가 그대로 요구. 리스너의 구독 이벤트만 교체 |
| `docker-compose.yml` · `docker-compose.prod.yml` · `infra/` | **살림** | postgres 영속 볼륨 부재 구성 · nginx TLS·프록시 · Prometheus/Grafana 스택이 `ARCHITECTURE §13` 과 일치 |
| `docs/infra/DEPLOYMENT.md` | **살림 · 갱신 대기** | 배포 절차는 유효. 시드 계정표는 §3 확정 후 갱신 |

### 1.3 걷어냄 — 옛 제품 도메인

`backend/src/main/java/src/backend/` 의 도메인 디렉터리 15개(`global`·`observability` 제외)가 대상.

| 모듈 | 판정 | 근거 |
|---|---|---|
| `tenant/` · `user/` (`Tenant`·`User`·`UserTenantRole`·`Role`) | **폐기 · 재작성** | `User`↔`Tenant` N:M 연결 테이블 모델. 새 사양은 계정 1개가 학원 1곳에 속하며 `account.role`·`account.academy_id` 두 컬럼으로 표현 (`ARCHITECTURE §5.3` · `ERD` `account` CHECK). 역할 값도 `ATTENDANT` → `escort` 등 6종 전면 교체 (`API_SPEC §9.1`) |
| `auth/` | **폐기 · 재작성** | 이메일+비밀번호 즉시 로그인 전제. 새 사양은 form 가입 → 학원 검색·선택 → 승인으로 활성화이고 `pending` 상태에도 토큰을 발급하되 API 2개만 허용 (`C-01` · `API_SPEC §1.4`). 승인 주체가 역할별로 갈림(관계자 / 메인 관리자)도 부재 |
| `route/` (`Route`·`Stop`) | **폐기 · 재작성** | `stop.route_id` + `seq` 로 **공용 정류장**을 노선에 매단 구조. `C-12` 는 공용 정류장 개념 자체가 부재이며, 승하차지는 학생 요일별 주소 좌표에서 생성돼 **학원이 소유하는 마스터**(`ERD` `stop.academy_id`)이고 노선은 `run_stop` 이 참조 |
| `routing/` (`RoutePlan`·`RoutePlanStop` · engine · infrastructure) | **폐기 · 재작성** | 배차 시뮬레이션 모델. 새 사양은 `confirmed_route` + `route_version` + `run_stop` + `waypoint` 4테이블과 출발 30분 전 도래 배치가 축 (`RTE-02` · `ARCHITECTURE §8`·`§9`). **외부 지도 API 어댑터의 호출·구간 분할 골격은 참고 가치** — ARCHITECTURE §8.2 |
| `schedule/` (`ScheduleChangeRequest`·`LocationChangeRequest`·`LocationChangeDecision`) | **폐기 · 재작성** | 요청 모델에 3구간 판정·회차당 1회 한도·자동 거절이 부재 (`C-04` · `ERD` `boarding_intent.change_used_count` CHECK) |
| `drivesession/` (`DriveSession`·`DriveSessionStatus`) | **폐기 · 재작성** | 별도 운행 종료 조작을 전제. `C-15` 는 종료 버튼 부재이며 최종 도착 처리가 종료를 겸하고, 하원은 잔류 탑승자가 0명이 되는 시점에 서버가 자동 전이. 상태값도 `Run.status` 4종과 상이 |
| `rideevent/` (`RideEvent`·`RideType`·`RideSource`) | **폐기 · 재작성** | 이벤트 적재형. 새 사양은 `run_rider.status` 5종을 현재 상태로 두고 변경 이력을 `rider_status_history` 에 별도 적재하며 `client_key` UNIQUE 로 멱등 (`C-02` · `API_SPEC §1.7`) |
| `attendance/` (`AttendanceException`·`AttendanceType`) | **폐기 · 재작성** | `absent`(학부모 사전 OFF · 알림 부재)와 `no_show`(현장 처리 · 즉시 알림 + 에스컬레이션)의 구분이 부재 (`C-02`) |
| `location/` | **폐기 · 축소 재작성** | Mock GPS 시뮬레이터 · 학생 단말 좌표 소스 · 연결 유실 판정이 섞여 있음. 새 사양의 좌표 원천은 **매니저 앱 1종**이고 `moving` 상태에서만 송신 (`ARCHITECTURE §10.1`) |
| `notification/` (`NotificationThresholds` 포함) | **폐기 · 재작성** | 아웃박스 부재. 임계값이 코드 상수인데 미승차 대기는 **학원별 설정**이어야 함 (`EXC-01` · `TECH_DECISIONS §12.2` 가 이 지점을 명시적으로 지적) |
| `sos/` | **폐기 · 재작성** | 취지는 `EXC-04` 와 같으나 수신 대상(관계자 + 메인 관리자 동시) · 설정 항목 부재(`C-17`) · 발신 1분 이내 취소 · 팝업 병행이 부재 |
| `bus/` | **폐기 · 재작성** | 새 사양에 가장 가까운 모듈이나 `tenant_id`→`academy_id`, 정원 계산식이 CHECK 제약으로 강제(`BUS-04` · `ERD §5.2`), 호차 UNIQUE 키가 상이 |
| `student/` | **폐기 · 재작성** | 학생↔버스·정류장 직접 참조 구조. 새 사양은 `weekly_address`(요일 × 방향 UNIQUE)가 노선 산출의 기준이고 학생은 버스를 직접 참조하지 않음 (`P-05` · `C-16`) |
| `operations/` | **폐기 · 재작성** | 관제 조회 범위가 `MON-01~07` · `O-05~07` 과 상이. 특히 ETA 가 관제 전용이라는 경계(`C-08`)가 부재 |
| `db/migration/V1__init_schema.sql` (17테이블) | **폐기** | §2 |
| `db/migration-local/V2__seed_data.sql` | **폐기** | §2 · §3 |

### 1.4 프론트엔드 처분 ➖ **범위 밖 · 저장소에서 삭제**

**2026-08-25 확정으로 착수 대상 밖 · 2026-09-04 Ruling 255 로 영구 범위 밖 · 2026-09-09 저장소에서 삭제.** `frontend/` 는 디스크·git 양쪽에 부재하며 프론트 배포 워크플로(`.github/workflows/deploy-web.yml`) · `docker-compose.yml` 의 `frontend` 서비스 · `infra/proxy/nginx.conf` 의 `/` 라우팅도 함께 제거. **이전 `main` 트리에 245파일이 그대로 보존**되므로 재개 시 `git show <이전 main 해시>:frontend/...` 로 꺼낸다.

아래 표는 그 보존분을 **재개 시점에 다시 대조할 판정 기록**. 판정 시점이 2026-08-24 이므로 그대로 실행하지 않는다 (Phase F1~F4 절).

| 대상 | 판정 | 근거 |
|---|---|---|
| `frontend/lib/features/` 9종 (`auth`·`bus`·`drivesession`·`location`·`notification`·`rideevent`·`routing`·`student`·`tenant`) | **폐기 · 재작성** | 화면·모델이 옛 도메인에 1:1. 제품 구성도 기사 앱 2화면 + 관리자 4화면에서 **제품 4종**으로 바뀜 (`ARCHITECTURE §4.1`) |
| `frontend/lib/core/api` (dio interceptor · 401 자동 refresh) · `core/storage` (secure storage) | **살림** | `ARCHITECTURE §2.2` 가 같은 구조를 지정. secure storage 는 **앱 빌드 전용** — 웹 빌드(F3·F4)는 HttpOnly 쿠키 경로 (`C-14` · `API_SPEC §1.2.1`) |
| `frontend/lib/core/ws` (STOMP) | **살림 · 채널 교체** | 순수 STOMP 클라이언트 골격 유지, 채널 4종은 `API_SPEC §7` 로 교체 |
| `frontend/lib/core/ui` · `app/theme` | **부분 살림** | 색 토큰은 `C-09` 의 그린·앰버·레드·스톤 4색에 맞춰 재정의 |
| `frontend/lib/app/router` (`go_router`) | **골격 살림 · 재작성** | `redirect` 한 곳에서 계정 상태·역할 분기를 처리하는 구조는 유지 (`ARCHITECTURE §5.2`·`§5.3`). 분기 대상이 상태 4종 × 역할 6종으로 바뀜 |
| `frontend/Dockerfile` · `nginx.conf` | **살림** | 빌드·배포 구성 |

### 1.5 버리는 것이 아까운 것

폐기 대상이나 **패턴 자체는 새 코드에 이식**할 것.

| 자산 | 무엇이 아까운가 | 이식 지점 |
|---|---|---|
| `ControllerAuthorizationConventionTest` | 컨트롤러 핸들러에 인가 애너테이션이 누락된 상태를 테스트로 잡음 — 새 엔드포인트가 인가 없이 열리는 사고의 상시 방어 | Phase 2 |
| `RolePermissions.HIERARCHY` 방식 | `RoleHierarchyImpl` 을 역할→권한 부여표로 전용해 JWT 토큰 포맷을 건드리지 않고 권한 확장. 우변에 `ROLE_` 을 금지하는 규칙까지 문서화돼 있음 | Phase 2 |
| `observability/aspect` | 계측 호출을 도메인 메서드 밖에 둠 — 도메인이 관측을 모르는 방향 유지 | Phase 7 (배치 지연 지표) |
| `DeploymentConfigGuardTest` | `prod` 프로파일이 위험한 기본값을 상속하지 않는지 검사. **Flyway `clean` 안전장치 검사를 여기에 추가** (§3.2) | Phase 1 |
| `TransactionalDomainEventRelay` | 트랜잭션 커밋 이후 발행 지점 | Phase 4 |
| `build.gradle` 의 `bootRun` `.env` 주입 | `./gradlew bootRun` 이 `.env` 를 읽지 않아 지도 API 키가 빈 값으로 남는 문제의 해결책 | 유지 |
| `docker-compose.yml` postgres 영속 볼륨 부재 구성 | 컨테이너 제거 후 재기동만으로 시드 상태 복귀 | 유지 (§3.2 와 이중 안전망) |
| Swagger `description` 에 시드 계정표를 싣는 방식 | Swagger UI 최상단에서 로그인 계정을 즉시 확인 가능 | Phase 1 — `SeedFixtures` 참조로 승격 |

---

## 2. DB · Flyway 방침

### 2.1 확정 사항

**개발 중 DB 데이터는 전부 삭제돼도 무방하며 Flyway 마이그레이션도 포함** (사용자 확정, 2026-08-24).

| 항목 | 방침 |
|---|---|
| 기존 마이그레이션 | **통째로 폐기.** `db/migration/V1__init_schema.sql`(17테이블) · `db/migration-local/V2__seed_data.sql` 을 삭제하고 `ERD` 기준으로 재작성 |
| 새 스키마 | **`V1__init_schema.sql` 하나로 시작.** 버전을 쌓지 않음 |
| 스키마 변경 | **기존 파일을 직접 수정 + 로컬 DB 재구성.** `docker compose down` → `docker compose up -d postgres redis`. 버전 번호를 늘리지 않음 |
| Hibernate | **`ddl-auto: validate` 유지.** 스키마의 소유는 Flyway 이고 Hibernate 는 엔티티↔스키마 일치 검증만 |
| 시드 | `db/migration-local/` 에 두고 `local`·`demo` 프로파일에서만 `spring.flyway.locations` 에 추가 (`prod` 미포함) |

### 2.2 첫 배포 시점에 원칙이 뒤집힘

⚠ **위 방침은 "아직 어떤 영속 환경에도 적용되지 않은 마이그레이션"에만 성립.** `demo`·`prod` 에 한 번이라도 적용된 뒤에는 반대가 됨.

| 시점 | 원칙 |
|---|---|
| 첫 배포 이전 (현재) | 기존 파일 직접 수정 · 로컬 재구성 · 버전 미증가 |
| **첫 배포 이후** | **기존 파일 수정 금지 · `V{n}` 추가만 허용.** 운영 DB 는 볼륨이 있어 재구성으로 되돌리기 불가이고 체크섬 불일치는 곧 기동 불가 |

**첫 배포 시점에 `CLAUDE.md` 의 Flyway 항목과 `ARCHITECTURE §12` 를 함께 갱신**한다. 갱신을 빠뜨리면 다음 세션이 기존 파일을 고쳐 운영 DB 를 기동 불가 상태로 만든다.

기존 파일을 고친 뒤 재구성 없이 앱만 다시 띄우면 `FlywayValidateException` 이 발생. 이는 **코드 결함이 아니라 재구성 누락 신호**.

### 2.3 테이블 수 — **39개**

구현 기준은 `ERD §3` 의 테이블 항목 **39개**. 2026-08-24 신설 3개(`verification_code`(AUTH-08) · `emergency_alert`(EXC-04) · `device_token`(NTF-12))가 포함된 값이며, 이전 판의 36/38 불일치는 해소됨.

`V1__init_schema.sql` 은 이 39개를 한 파일에 담고 UNIQUE·CHECK·인덱스를 함께 정의한다 (`ERD §5`).

---

## 3. 개발용 Mock 데이터 · Swagger

### 3.1 만족해야 하는 네 가지

사용자 확정 사항.

1. **전 API 에 Swagger 적용**
2. **Mock 데이터로 Swagger UI 에서 바로 테스트 가능**
3. **Swagger 예시 데이터와 Mock 데이터가 일치**
4. **개발 중 서버 재시작마다 Mock 데이터 초기화**

셋째가 가장 어려운 조건. 손으로 적은 `@Schema(example = "...")` 는 시드가 바뀌어도 컴파일을 통과하고 실행 중에도 오류가 부재해 어긋남이 드러나지 않음. "일치한다"를 선언이 아니라 **어긋나면 실패하는 테스트**로 만들어야 성립.

### 3.2 초기화 — `local` 프로파일 기동 시 Flyway `clean` → `migrate`

**구성**

1. `local` 프로파일에서만 등록되는 `FlywayMigrationStrategy` 빈이 `flyway.clean()` 후 `flyway.migrate()` 를 수행
2. Flyway 10+ 는 `clean` 이 기본 차단이므로 `local` 블록에서만 `spring.flyway.clean-disabled: false`
3. 시드는 `db/migration-local/` 에 두고 `local`·`demo` 프로파일에서만 `locations` 에 추가

> Boot 4 는 자동설정 패키지가 모듈별로 재편돼 `FlywayMigrationStrategy` 의 패키지가 Boot 3 과 다를 가능성이 존재. Phase 1 착수 시 실제 좌표를 확인할 것.

**`prod`·`demo` 에서 절대 불가하도록 하는 안전장치 5겹**

⚠ **5겹이 전부 대등하지 않음.** 2026-08-25 조사 결과 실제 성격은 **런타임 인터록 4겹 + 빌드 시점 회귀 게이트 1개**. ①(`@Profile("local")`)과 ②(다중 프로파일 재확인)는 순차 보완 관계이고, ③(접속 URL 검사)과 ④(`clean-disabled`, Flyway 엔진 레벨)는 서로 다른 축이라 진짜 독립. **⑤(`DeploymentConfigGuardTest`)는 런타임에 아무것도 막지 못하며 CI 가 이 테스트를 실제로 실행할 때만 유효** — CI 를 건너뛴 배포에서는 ⑤가 부재한 것과 같음. 겹 수를 세어 안심하지 않는다.

운영 DB 를 지우는 사고는 되돌리기 불가. 한 겹으로 두지 않는다.

| # | 장치 | 막는 것 |
|---|---|---|
| 1 | 전략 빈에 `@Profile("local")` | 프로파일이 다르면 빈 자체가 미등록 |
| 2 | 빈 내부에서 `Environment` 활성 프로파일을 재확인해 `prod`·`demo` 가 섞이면 `IllegalStateException` 으로 **기동 실패** | `--spring.profiles.active=local,prod` 처럼 병기한 실행 |
| 3 | 데이터소스 URL 이 `localhost`·`127.0.0.1` 이 아니면 `clean` 거부 | `local` 프로파일로 원격 DB 를 가리킨 사고 |
| 4 | 공통·`prod`·`demo` 블록에 `spring.flyway.clean-disabled: true` 명시 | 1~3 이 모두 뚫려도 Flyway 자체가 거부 |
| 5 | `DeploymentConfigGuardTest` 에 케이스 추가 — `prod`·`demo` 프로파일로 컨텍스트를 띄워 `clean-disabled=true` 이고 전략 빈이 부재함을 검증 | 설정 회귀. 사람의 기억에 의존하지 않음 |

**부작용 하나** — devtools 자동 재시작도 컨텍스트를 다시 띄우므로 `clean` 이 함께 돈다. 클래스를 한 줄 고칠 때마다 데이터가 초기화됨. 이는 확정 사항 4번이 요구한 동작 그대로이며, Swagger 로 만든 데이터를 유지하려면 devtools 를 끄거나 `local` 이 아닌 별도 프로파일로 띄우는 선택지를 남긴다.

### 3.3 Swagger 예시 ↔ 시드 일치 보장

**단일 원천 — `SeedFixtures`**

시드가 적재하는 식별자·코드·아이디를 `SeedFixtures` 한 클래스의 `public static final String` 상수로 둔다. 애너테이션 값은 **컴파일 타임 상수식만** 허용하므로 `static final String` 리터럴이어야 하고, 메서드 호출이나 연산 결과는 사용 불가.

⚠ **시각 값은 상수 대상 밖.** 시드의 출발 시각은 `now()` 기준 상대값이라(§3.4) 기동마다 달라짐. Swagger 의 시각 예시는 **형식만 보여주는 값**으로 두고 대조 테스트에서 제외한다. 이 경계를 명시하지 않으면 대조 테스트가 매번 실패.

**세 겹의 일치 강제**

| 겹 | 방식 | 어긋나면 |
|---|---|---|
| **① 값 실재 대조** | `SeedFixturesContractTest` — 시드가 적재된 DB 를 조회해 `SeedFixtures` 의 모든 상수가 실재하는 레코드를 가리키는지 검증 | 시드 SQL 을 고치고 상수를 안 고치면 테스트 실패 |
| **② 문서 예시 대조** | `SwaggerExampleSeedContractTest` — `/v3/api-docs` 를 받아 예시값을 전부 수집하고, 식별자 성격 필드(`login_id` · `academy_code` · `run_id` · `student_id` 등 대조 대상 목록)의 예시가 ①의 상수 사전에 속하는지 검증 | 리터럴을 직접 적으면 상수 사전에 없어 실패 |
| **③ 소스 규약** (보조) | 컨트롤러·DTO 소스에서 `example = "` 리터럴 사용 지점을 찾아 허용 목록 밖이면 실패 | 실수를 소스 단계에서 가려냄 |

**②가 핵심.** 상수는 컴파일 시점에 인라인되므로 런타임에는 `@Schema(example = SeedFixtures.PARENT_LOGIN_ID)` 와 `@Schema(example = "parent01")` 를 구분 불가. 따라서 **참조했는지**가 아니라 **값이 시드에 실재하는지**를 검사해야 한다. ③은 그 구분을 소스 수준에서 보강하는 보조 장치이며 단독으로는 불충분.

**시드 SQL 에서 상수를 생성하는 방안은 미채택** — Gradle 태스크로 `V2__seed_data.sql` 을 파싱해 `SeedFixtures.java` 를 생성하면 원천이 하나로 줄지만, 파서가 SQL 문법 변화에 취약하고 생성 코드가 IDE 자동완성·리팩터링과 어긋남. **상수를 사람이 쓰고 ①로 묶는 편**이 같은 보장을 더 적은 기계장치로 달성.

**전 API Swagger 적용의 강제** — `SwaggerCoverageTest`: 모든 요청 매핑 핸들러가 OpenAPI 문서에 존재하고 `summary` 와 최소 1개 응답 예시를 보유하는지 검증. 이 테스트가 없으면 새 컨트롤러가 추가될 때마다 문서에서 누락되고 그 사실이 관측 불가.

### 3.4 시드가 담을 것

**기준** — Swagger UI 에서 `USER_FLOWS` 의 흐름을 끝까지 밟을 수 있어야 함. 아래는 사양에서 유도한 최소 구성.

| 범주 | 내용 | 근거 |
|---|---|---|
| **학원** | 3곳 — A(주 시나리오) · B(격리 검증) · C(`inactive`, 비활성화 후 로그인 유지 검증) | `ARCHITECTURE §6.1` · `O-01` |
| **계정 — 역할 6종** | `system_admin` 1 · `staff` A·B 각 1 · `parent` · `student` · `driver` · `escort` 를 A 에 복수, B 에 각 1 | `API_SPEC §9.1` |
| **계정 — 상태 4종** | `active` 다수 · `pending` (parent 1 + staff 1) · `rejected` (student 1, 거절 사유 보유) · `blocked` (driver 1, 실패 횟수 상한) | `API_SPEC §9.2` · `C-11` |
| **학생 · 보호자** | A 학원 5명. 그중 2명이 같은 보호자에 연결(다자녀 UI 노출 조건) · 학생 계정 미연결 1명(`AUTH-11` 미연결 상태 확인) | `P-02` · `ATT-03` |
| **요일별 주소** | 학생당 요일 7 × 방향 2 = 14행. UNIQUE 조합 준수 | `P-05` · `C-16` · `ERD §5.1` |
| **자녀 연결** | `link_request` 1건(대기) · `link_code` 1건(유효) | `P-02` · `S-05` |
| **차량** | A 2대 · B 1대. 정원 계산식 CHECK 충족, 한 대는 잔여석 1로 두어 정원 초과 차단 검증 | `BUS-04` · `ERD §5.2` |
| **매니저** | A: 기사 2 · 동승자 2, B: 각 1. `work_hours` 보유 | `MGR-01~06` |
| **스케줄** | A 버스별 등원·하원. **적재 시점 요일에 맞춰 생성** | `SCH-01` |
| **회차 — 오늘 날짜** | R1 `idle` · R2 `confirmed` · R3 `moving` · R4 `finished` · R5(B학원) `confirmed` | `API_SPEC §9.3` |
| **3구간 배치** | R1 출발까지 여유 충분(①구간) · R2 출발 임박(②구간) · R3 이미 출발(③구간). **`now()` 기준 상대 시각으로 계산** | `C-04` · `API_SPEC §1.6` |
| **확정 노선** | R2·R3 에 `confirmed_route` + `route_version`(v1) + `run_stop` + `run_rider`. R2 는 v2 까지 두어 버전 대조 검증 | `RTE-02` · `ARCHITECTURE §8.5` |
| **탑승 상태 5종** | `waiting` · `boarded` · `alighted` · `absent` · `no_show` 각 최소 1행 (R2·R3 에 분산) | `C-02` |
| **탑승 의사** | `boarding_intent` — 변경 한도 미소진 1행 · 소진 1행 | `C-04` · `ERD §5.2` |
| **변경 요청** | `pending` · `approved` · `rejected` · `auto_rejected` 각 1 | `API_SPEC §9.6` |
| **미승차** | `no_show_case` 1건 + `no_show_contact` 2행 | `EXC-01` |
| **비상 알림** | `emergency_alert` 1건 — 관계자 미확인 상태(배지 검증) | `EXC-04` · `A-16` · `O-07` |
| **경유 지점** | `waypoint` 1건 + 대응 `run_stop` | `RTE-10` · `A-15` |
| **알림** | 종류별 다수 + `push_state='pending'` 1건(워커 회수 검증) + 읽음·미읽음 각각 | `API_SPEC §9.7` · `ARCHITECTURE §11` |
| **위치** | R3 에 `run_position` 몇 행 | `LOC-01` |
| **감사 로그** | L3 조회 · 로그인 · 차단 각 1행 | `SYS-01·02` |
| **학원별 설정** | `academy_setting` 3행 — 미승차 대기 값을 학원별로 다르게 두어 코드 상수가 아님을 검증 | `EXC-01` · `TECH_DECISIONS §12.2` |

**비밀번호** — 현행 규약을 그대로 따름. Flyway placeholder `seedPasswordHash` 로 주입하고 `local` 기본값은 평문 `password` 의 해시, `demo` 는 `SEED_PASSWORD_HASH` 환경변수 주입이며 기본값 부재.

⚠ **시드가 지켜야 하는 CHECK 제약 3가지** — 시드 SQL 이 가장 자주 걸리는 지점.
- `run.confirm_at = depart_time - interval '30 minutes'` — 상대 시각으로 계산할 때 두 값을 함께 계산할 것
- `bus.student_capacity = capacity - driver_count - escort_count`
- `run_stop` 의 `stop_id` / `waypoint_id` 배타 조건

---

## 4. 테스트 전략

### 4.1 원칙 — 테스트는 Phase 산출물

**각 Phase 의 산출물에는 코드와 함께 그 Phase 를 지키는 테스트가 들어간다.** 테스트가 부재한 Phase 는 ✅ 로 표시하지 않는다.

**작업의 단위는 Phase 가 아니라 기능 ID 1개이며, 그 단위를 §4.6 의 TDD 사이클로 처리한다.** Phase 는 사이클의 묶음이고, Phase 완료는 그 Phase 가 담당하는 기능 ID 전부가 사이클을 통과했다는 뜻.

근거는 이 시스템의 성격에 있음. 결함이 나는 지점이 **시각 · 동시성 · 인가 · 개인정보 노출** 넷에 몰려 있고, 넷 다 **실행해 보면 정상으로 보이는** 종류. 배치가 25분 전에 돌아도 화면은 멀쩡하고, 두 스레드가 같은 회차를 확정해도 응답은 200 이며, 매니저 앱 응답에 보호자 번호 원본이 실려도 화면은 마스킹된 값을 보여줌. **수동 확인으로는 판별 불가**한 것이 대부분이라 테스트가 유일한 검출 수단.

### 4.2 종류 10종

| 종류 | 대상 | 도구 |
|---|---|---|
| **단위** | 상태 전이표 **전수**(허용·불허 조합 전부) · 3구간 판정 **경계값**(30분 전 ±1초) · 정원 계산(`BUS-04`) · ②구간 한도 소진·복구 | JUnit |
| **시간 의존** | `Clock.fixed` 로 고정 — **두 시계 분리**(배치가 25분 전에 돌아도 판정은 출발−30분) · 운행 시작 창 · 미승차 대기 · ②구간 마감(출발 시각 도달 **또는** 운행 시작 중 먼저) | JUnit + `Clock` |
| **동시성** | **조건부 UPDATE 멱등** — 두 스레드가 같은 회차를 동시에 확정 시도할 때 갱신 행 수가 1/0 으로 갈림 · 오프라인 큐 `client_key` 중복 전송 | Testcontainers |
| **통합** | 저장소 · 트랜잭션 경계 · 제약 위반 예외(`DataIntegrityViolationException` 기반 멱등, `TECH_DECISIONS §9.2`) | Testcontainers (PostgreSQL · Redis) |
| **인가** | **역할 6종 × 권한 카탈로그 전수** · 계정 상태 게이트(`pending` 2개 · `rejected` 3개) · **학원 격리** · 보호자↔자녀 · 매니저↔회차 | `spring-security-test` |
| **개인정보** | **역할별 응답 DTO 에 L2 마스킹 적용 · L3 필드 미포함** (`FEATURE_SPEC §6.3`). 매니저 앱 응답에 보호자 번호 **원본이 실리지 않는지** | MockMvc + 직렬화 결과 대조 |
| **아웃박스** | 커밋 후 즉시 발송이 실패해도 워커가 `pending` 을 회수 · `dedup_key` 로 중복 차단 | Testcontainers + Awaitility |
| **스케줄러** | 도래분 폴링이 실제로 대상 회차를 집는지 · 실패 시 `idle` 복귀 후 다음 틱 재시도 | Awaitility |
| **계약** | **Swagger 예시 ↔ 시드 대조** — 예시값을 그대로 넣었을 때 실제 조회가 성립하는지 (§3.3) | 통합 테스트 |
| **회귀 방지** | 컨벤션 — 모든 매핑 메서드에 인가가 부착됐는지 · 엔티티가 응답에 직접 직렬화되지 않는지 | ArchUnit 또는 리플렉션 테스트 |

**개인정보 테스트가 직렬화 결과를 보는 이유** — 응답 DTO 를 만들었다는 사실은 필드가 빠졌다는 보장이 부재. 상속·`@JsonUnwrapped`·중첩 DTO 로 원본이 딸려 나가는 경우가 있어 **직렬화된 JSON 문자열에 원본 값이 등장하는지**를 직접 대조해야 함.

### 4.3 H2 미사용

`TECH_DECISIONS §10` 이 명시. H2 는 PostgreSQL 방언 · **부분 인덱스** · **제약 위반 예외 코드**를 재현 불가하고, `TECH_DECISIONS §9.2` 의 멱등이 **예외 처리에 의존**하므로 실제 PostgreSQL 이 필요. `student(account_id)` 같은 partial UNIQUE 와 `run.confirm_at = depart_time - interval '30 minutes'` 같은 CHECK 도 H2 에서 검증 불가 (`ERD §5.1`·`§5.2`).

**컨테이너 기동 비용** — 테스트 클래스마다 띄우면 전체 실행 시간이 선형으로 증가. 컨테이너 1벌을 공유하고 테스트 간 격리는 트랜잭션 롤백으로 처리하되, **동시성 테스트는 두 스레드가 각자 트랜잭션을 열어야 하므로 롤백 격리 대상 밖** — 해당 테스트만 명시적 정리로 처리.

### 4.4 테스트 데이터 — 개발용 시드와 분리한다

**판정: 계약 테스트만 시드를 쓰고 나머지는 전용 픽스처 빌더로 만든다.**

| 구분 | 데이터 원천 | 대상 |
|---|---|---|
| **계약 테스트** | 개발용 시드 (`db/migration-local`) | `SeedFixturesContractTest` · `SwaggerExampleSeedContractTest` — **시드 자체가 검증 대상**이라 공유가 필연 |
| **그 외 전부** | 테스트 전용 픽스처 빌더 | 단위 · 시간 의존 · 동시성 · 통합 · 인가 · 개인정보 · 아웃박스 · 스케줄러 |

**근거** — 두 데이터의 목적이 다름. 시드는 **Swagger 에서 흐름을 끝까지 밟기 위한 데이터**이고 테스트 픽스처는 **한 규칙을 고립시키기 위한 데이터**. 한 벌로 묶으면 두 방향의 압력이 동시에 걸림.

| 묶었을 때 생기는 일 | 정도 |
|---|---|
| 시드에 계정을 하나 더하면 목록 조회 건수를 단언한 테스트가 전부 실패 | 시드는 §3.4 대로 계속 늘어날 예정이라 상시 발생 |
| 특정 규칙을 고립시키려고 시드에 데이터를 더하면 Swagger 실습용 시드가 비대해져 첫 화면에서 무엇을 눌러야 할지 판별 불가 | 시드의 목적 자체가 훼손 |
| 시드가 상대 시각 기반(§3.4)이라 시간 의존 테스트가 `Clock.fixed` 와 어긋남 | 시간 의존 테스트를 시드 위에 세우는 것이 불가 |

**픽스처가 두 벌이 되는 비용은 데이터에만 발생하고 스키마·제약에는 미발생** — 스키마의 원천은 Flyway 한 벌이라 갈리지 않고, 픽스처 빌더가 만든 행도 같은 CHECK·UNIQUE 를 통과해야 함. 두 벌이 되는 것은 "어떤 값을 넣을지"뿐.

**학원 격리 테스트도 시드를 쓰지 않는다** — 시드에 A·B 두 학원이 이미 존재해 그대로 쓰고 싶어지는 지점이나, 격리 테스트는 "다른 학원의 자원이 **정확히 몇 건** 있는가"를 단언하는 성격이라 시드 변경에 가장 취약. 빌더로 학원 2곳을 만드는 비용이 낮음.

### 4.6 기능 단위 TDD 사이클 — 작업의 최소 단위

**기능 ID 1개 = 사이클 1회.** 기능 ID 의 정의처는 [FEATURE_SPEC §4](./FEATURE_SPEC.md) 의 인덱스 102개이며, 이 문서가 기능을 새로 만들지 않는다.

사이클을 마치지 않은 기능은 **구현했다고 보지 않는다.** 컨트롤러가 200 을 반환하는 사실은 사이클의 어느 단계도 통과시키지 못한다.

#### 4.6.1 다섯 단계

| # | 단계 | 하는 것 | 산출물 | 다음으로 넘어가는 조건 |
|:-:|---|---|---|---|
| ① | **목표** | 기능 ID 가 무엇을 보장해야 하는지를 **검증 가능한 문장**으로 옮김. 사양 절·규칙 ID·경계값을 여기서 확정 | 테스트 이름 목록 | 문장마다 "무엇이 깨지면 이 문장이 거짓이 되는가" 에 답할 수 있음 |
| ② | **RED** | ①의 문장 하나를 테스트 코드로 작성하고 **실행해 실패를 확인** | 실패하는 테스트 1개 | **실패 메시지를 눈으로 확인.** 실패 원인이 오타·컴파일 오류가 아니라 **기능 부재** |
| ③ | **GREEN** | 그 테스트를 통과시키는 **최소** 구현 | 통과하는 테스트 + 구현 | 대상 테스트 통과 + **기존 테스트 전부 통과** |
| ④ | **REFACTOR** | `CODE_CONVENTIONS.md §19`·`§20` 기준으로 정리 — 설명 주석 · SRP · 크기 기준 · 매직 넘버 · 중복 | 정리된 구현 | 테스트가 계속 통과. **동작을 추가하지 않음** |
| ⑤ | **검증** | 기능 ID 의 완료 조건을 사양과 대조하고 Phase 절의 "남길 테스트" 에 등재 | 갱신된 Phase 절 · §8 표 | 사양의 해당 절과 어긋난 항목 0건 |

②~④ 를 문장 하나마다 반복하고, ①의 문장을 전부 소진하면 ⑤ 로 간다.

#### 4.6.2 ②를 건너뛰면 무엇을 잃는가

**실패를 보지 않은 테스트는 무엇을 검출하는지 알 수 없다.** 구현 뒤에 쓴 테스트는 즉시 통과하는데, 그 통과가 뜻하는 것은 두 가지 중 하나이고 **둘을 구분할 수단이 부재**하다 — 규칙을 실제로 검사하거나, 아무것도 검사하지 않거나.

이 시스템에서 특히 위험한 이유는 §4.1 의 결함 4종(시각 · 동시성 · 인가 · 개인정보 노출)이 **전부 "통과하는 빈 테스트"와 구분되지 않는 형태**이기 때문이다.

| 빈 테스트가 통과하는 예 | 실제로는 |
|---|---|
| 배치가 30분 전에 돈다고 단언했으나 `Clock` 을 고정하지 않아 실행 시각을 그대로 읽음 | 25분 전에 돌아도 통과 |
| 두 스레드 동시 확정을 단언했으나 두 번째 스레드가 첫 스레드 완료 뒤에 시작 | 중복 확정을 검출 불가 |
| 마스킹을 단언했으나 DTO 가 아니라 화면 렌더 결과를 대조 | 응답 JSON 에 원본이 실려도 통과 |

②에서 **먼저 실패시켜 보면 셋 다 그 자리에서 드러난다** — 고정하지 않은 시계는 실패하지 않고, 순차 실행은 실패하지 않고, 잘못된 대조 대상은 실패하지 않는다. "실패해야 하는데 실패하지 않는 것" 이 ②가 잡는 유일한 대상이다.

#### 4.6.3 목표 문장 쓰는 법

**테스트 이름이 곧 ①의 산출물**이다 (`CODE_CONVENTIONS.md §20.3` 8번). 형식은 "언제 · 무엇이 · 어떻게 된다".

```
확정_30분_전이_도래하면_idle_회차가_confirmed_로_전이한다
확정_시각_1초_전에는_idle_로_남는다
같은_회차에_두_스레드가_동시_확정을_시도하면_갱신_행_수가_1과_0_으로_갈린다
매니저_앱_명단_응답_JSON_에_보호자_번호_원본이_등장하지_않는다
```

- **"정상 동작한다" · "잘 처리된다" 같은 이름을 쓰지 않는다** — 무엇이 깨지면 거짓이 되는지 판별 불가.
- **경계값은 양쪽을 각각 쓴다.** "30분 전에 전이" 하나만 있으면 항상 전이하는 구현도 통과.
- 이름에 "그리고" 가 들어가면 문장이 둘이므로 사이클도 둘로 가른다.

#### 4.6.4 사이클을 적용하지 않는 것

| 대상 | 이유 | 대신 하는 것 |
|---|---|---|
| Phase 0 걷어내기 | 삭제에는 보장할 동작이 부재 | 빌드·기동 성공을 완료 조건으로 |
| Flyway 마이그레이션 SQL · `application.yml` | 선언이라 RED 를 만들 대상이 부재 | 스키마 대조 테스트(Phase 1) · `DeploymentConfigGuardTest` |
| 엔티티 필드 매핑(Phase 1) | 매핑 자체는 `ddl-auto: validate` 가 검사 | 상태 전이 메서드가 붙는 시점부터 사이클 적용 |

**위 3가지 외에는 예외가 부재.** "단순 CRUD 라 생략" 은 예외에 해당하지 않는다 — 학원 격리(`§7` 규칙 7)가 걸리는 순간 단순 CRUD 가 아니게 되고, 그 조건 누락은 실행해 보면 정상으로 보인다.

#### 4.6.5 사이클을 벗어난 상태의 신호

| 신호 | 실제 상태 | 조치 |
|---|---|---|
| 테스트가 처음부터 통과 | ②를 건너뜀 | 구현을 되돌리고 ②부터 |
| 실패 메시지가 `NullPointerException` · 컴파일 오류 | 기능 부재가 아니라 작성 실수로 실패 | 테스트를 고쳐 **의도한 단언에서** 실패시킴 |
| ③에서 테스트를 고침 | 목표를 구현에 맞춤 | 구현을 고침. 목표가 틀렸다면 ①로 복귀 |
| ③에서 테스트에 없는 분기·옵션을 추가 | 사양 밖 기능 | 삭제. 필요하면 별도 기능 ID 로 등재 |
| 목 객체의 호출 횟수만 단언 | 구현을 검사하고 동작을 미검사 | 실제 코드를 통과시키는 단언으로 교체 |

#### 4.6.6 사이클 1회 예시 — `RTE-02` 확정 배치

① **목표** — "출발 30분 전이 도래한 `idle` 회차가 `confirmed` 로 전이" · "1초 전에는 `idle` 유지" · "이미 `confirmed` 인 회차는 갱신 행 수 0"

② **RED**

```java
/** 확정 시각이 도래하면 idle 회차가 confirmed 로 전이한다. */
@Test
void 확정_시각이_도래하면_idle_회차가_confirmed_로_전이한다() {
    Run run = fixtures.idleRun(departAt(BASE_TIME));
    Clock at = Clock.fixed(run.confirmAt().toInstant(UTC), UTC);   // 판정 시각

    int updated = confirmBatch.runWith(at);

    assertThat(updated).isEqualTo(1);
    assertThat(repository.findById(run.id()).status()).isEqualTo(CONFIRMED);
}
```

실행 → `confirmBatch` 부재로 컴파일 실패 → 최소 골격만 만들어 다시 실행 → `updated` 가 0 이라 실패. **이 실패를 확인한 뒤**에 ③으로 간다.

③ **GREEN** — 조건부 UPDATE 한 줄. 폴링 주기·재시도·지표는 이 단계에서 넣지 않는다(각각 별도 문장).

④ **REFACTOR** — 판정 시각 계산을 도메인으로 내리고 설명 주석을 붙임. 정책 값은 `RTE-02` 로 참조하고 코드에 30 을 적지 않음.

⑤ **검증** — `ARCHITECTURE §9.2` 두 시계 분리와 대조. "실행 시각" 을 쓴 곳이 없는지 확인 후 Phase 7 절에 등재.

---

### 4.6.1 태스크 보고서 — **4항만** 쓴다

**독자는 사용자가 아니라 게이트 리뷰어와 다음 세션이다.** 사용자는 읽지 않으므로 분량은 그대로 낭비다. 전문 규칙은 전역 `~/.claude/rules/parallel-agents-git.md §11`.

| # | 항목 |
|:-:|---|
| 1 | **판단 근거 — 고른 길과 *버린 길*, 그 이유** (코드·diff·커밋 어디에도 안 남는 유일한 정보) |
| 2 | **우려 · 확신 없는 지점 · 지시와 다르게 판단한 것** |
| 3 | **실측 3줄** — 커밋 해시 · **실패 클래스 이름**(개수 아님) · 테스트 수 |
| 4 | **심은 변형 목록**(규칙 23 음성 대조를 했다면 무엇을 심었는지만. 결과 표는 리뷰 판정문이 정본) |

**쓰지 않는다** — "무엇을 했는가" 서술(`git diff`·커밋 메시지가 이미 함) · 음성 대조 결과 표(리뷰가 독립 재현) · 지시서·판정문 재기술(경로로 가리킨다).

⚠ **1·2 를 맨 앞에 둔다.** Phase 2 에서 "무엇을 했는가" 를 길게 쓰다 한도로 끊겨 **`REQUIRES_NEW` 를 버린 근거가 영구 유실**됐다. 다음 라운드가 복원을 시도했으나 "그 세션의 판단 과정을 관측할 수단이 부재" 로 포기했다.

---

### 4.7 Phase 단위 반복 — 목표를 잡고 통과까지 되풀이한다

§4.6 이 **기능 하나**의 사이클이라면, 이 절은 **Phase 하나**의 사이클이다. 둘은 층이 다르고 둘 다 적용된다.

| 층 | 단위 | 목표의 형태 | 완료 |
|---|---|---|---|
| §4.6 | 기능 ID 1개 | 검증 가능한 문장 (테스트 이름) | 문장 전부가 GREEN |
| **§4.7** | **Phase 1개** | **그 Phase 절의 완료 조건 전항** | **완료 조건 전항 통과** |

#### 4.7.1 절차

1. **착수 전 목표 확정** — Phase 절의 완료 조건을 **실행 가능한 명령 또는 단언**으로 옮긴다. 옮길 수 없는 조건이 있으면 그것부터 고친다
2. **각 조건을 테스트·실행으로 확인** — §4.6 사이클을 기능 ID 마다 돌린다
3. **미통과 항목이 남으면 수정하고 다시 전항을 확인한다** — 통과했던 항목이 되돌아갔을 수 있다
4. **전항 통과 시에만 §8 표를 ✅ 로 바꾼다.** 부분 통과는 🟡 이고, 미통과 항목을 비고에 적는다

#### 4.7.2 반복이 수렴하지 않을 때

무한 반복을 막는 것은 횟수 제한이 아니라 **원인 가설을 바꾸는 것**이다.

| 신호 | 실제 상태 | 조치 |
|---|---|---|
| 같은 실패가 3회 반복 | 가설이 틀렸는데 같은 수정을 변형만 해서 재시도 중 | 가설을 버리고 실패 지점을 처음부터 좁힌다 |
| 테스트를 고쳐 통과시킴 | 목표를 구현에 맞춤 | 구현을 고친다. 목표가 틀렸다면 ①로 복귀 |
| 단언을 약화시켜 통과 | 검증력을 버려 통과를 산 것 | 되돌린다 |
| 실패를 "환경 문제" 로 분류 | 근거 없이 분류하면 코드 결함을 덮음 | **로그의 실제 예외를 근거로** 가른다. 인프라 미기동·포트 점유처럼 특정 가능한 원인이 있을 때만 |
| 목표에 없던 기능 추가 | 범위 이탈 | 삭제. 필요하면 별도 기능 ID 로 등재 |

#### 4.7.3 완료 선언에 붙일 것

**목표마다 실행한 명령과 실제 출력.** 남기지 못한 항목은 통과로 세지 않는다. "통과했을 것" 은 근거가 아니다.

로그 파일을 만들었으면 **경로를 보고에 적고 지우지 않는다** — 다음 세션이 재감사할 수 있어야 그 조건이 실제로 닫힌다.

---

### 4.5 Phase × 테스트 종류

각 Phase 절의 **남길 테스트** 행이 정본이며 아래는 요약.

| Phase | 단위 | 시간 | 동시성 | 통합 | 인가 | 개인정보 | 아웃박스 | 스케줄러 | 계약 | 회귀 |
|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| 1 | | | | ● | | | | | **●** | ● |
| 2 | ● | | | | **●** | | | | | ● |
| 3 | | | | ● | ● | | | | | |
| 4 | | | | ● | | | **●** | ● | | |
| 5 | ● | | | ● | ● | | | | | |
| 6 | ● | | | ● | | | | | | |
| 7 | | **●** | **●** | ● | | | | **●** | | |
| 8 | **●** | ● | ● | | | | | ● | | ● |
| 9 | **●** | ● | ● | ● | ● | **●** | | | | |
| 10 | | | | ● | ● | ● | | ● | | |
| 11 | | ● | | ● | ● | | ● | ● | | |
| 12 | | | | ● | | ● | | ● | | |
| 13 | | | | ● | ● | ● | | | | |
| 14 | | | | ● | ● | | | ● | | |

---

## 5. 부하 테스트

### 5.1 목적 — 무너지는 지점을 재는 것

**일반적인 성능 측정이 아니다.** 초당 요청 수를 늘려 보는 것으로는 이 시스템의 한계가 드러나지 않음 — 부하가 REST 조회에 있지 않고 **정해진 시각에 도래하는 배치**와 **끊이지 않는 좌표 흐름**에 있기 때문. 재는 대상은 처리량이 아니라 **어느 조건에서 사양이 정한 시각 약속을 못 지키기 시작하는가**.

### 5.2 시나리오 4개

근거는 `ARCHITECTURE §9.4` · `§13.1` · `TECH_DECISIONS §14`.

| # | 시나리오 | 재는 것 | 무너지는 지점 |
|:-:|---|---|---|
| **1** | **동시 도래 폭주** — 학원 N곳 × 호차 M대가 **같은 출발 시각**을 써서 한 틱에 수십 건이 동시 도래 | **도래→확정 완료 지연** · **미확정 회차 수** | 회차당 계산이 외부 지도 API 를 포함해 수백ms~수초라, 처리 시간이 폴링 주기를 넘겨 **밀림이 누적되는 지점**. `ARCHITECTURE R1` 이 지목한 **가장 먼저 한계에 닿는 곳** |
| **2** | **위치 수신 처리량** — 운행 중 회차 수 × 송신 주기로 좌표가 지속 유입 | 수신 처리 지연 · Redis 갱신 지연 · WS 팬아웃 지연 | 관제 화면의 승하차 반영 목표(`NFR-02`)를 못 지키기 시작하는 회차 수 |
| **3** | **관제 팬아웃** — 메인 관리자가 전 학원을 구독한 상태에서 위치·상태 이벤트가 동시 발생 | WS 발행 지연 · 동시 연결 수 | 인메모리 세션을 쓰는 **인스턴스 1개의 세션 한계** (`ARCHITECTURE §9.5`) |
| **4** | **온디맨드 계산 경합** — ②구간 승인 미리보기·경유 지점 지정이 **확정 배치와 같은 시각에** 몰림 | 승인 화면 응답 시간 · 지도 API 레이트리밋 초과 건수 | 배치용 워커와 사용자 대기 경로가 **같은 외부 API 를 다투는 지점** (`ARCHITECTURE §8.3`·`R3`) |

**시나리오 4가 현실적인 이유** — ②구간은 정의상 출발 30분 안쪽이고 확정 배치는 출발 30분 전에 도래. 두 경로가 시간축에서 **붙어 있음**. 배치용 정책(긴 타임아웃·재시도)을 온디맨드에 그대로 쓰면 관리자가 승인 화면에서 대기하게 되는데, 그 상황이 실제로 발생하는지를 확인하는 것이 이 시나리오.

### 5.3 목표값 — 사양에서 가져온다

**임의의 숫자를 만들지 않는다.** 판정 기준은 전부 사양이 이미 정한 값.

| 판정 | 근거 |
|---|---|
| 승하차 상태 → 학부모·관계자 반영 시간 | `NFR-02` (`FEATURE_SPEC §2.1` 승하차 상태 반영) |
| 위치 송신 주기 | `NFR-03` (`FEATURE_SPEC §2.1` 위치 송신 주기) |
| 확정 완료 마감 | 회차 출발 **30분 전**까지 (`C-03` · `RTE-02`) — 도래→완료 지연이 이 창을 잠식하면 실패 |
| 미확정 회차 수 | `TECH_DECISIONS §13.4` 의 즉시 알럿 조건 — **0이 아니면 이미 사고** |
| 승인 화면 응답 | 사양에 수치 부재. **측정만 하고 임계는 부여하지 않음** — 값을 지어내지 않고 관측 결과를 기록해 추후 사양화 대상으로 등재 |

**부하 시험의 통과 기준은 "빠른가"가 아니라 "약속을 지키는가".** 시나리오 1에서 도래→완료 지연이 늘어도 확정이 출발 30분 전 안에 끝나면 통과이고, 미확정 회차가 1건이라도 남으면 실패.

### 5.4 도구 — k6

**채택: k6.** Gatling · JMeter 대비 근거 셋.

| 근거 | 내용 |
|---|---|
| **WebSocket 이 내장** | 시나리오 2·3 이 WS 팬아웃 측정이라 결정적. JMeter 는 플러그인이 필요하고 플러그인 버전이 본체와 별도 관리 대상 |
| **임계 판정이 스크립트 안에 있음** | k6 의 `thresholds` 로 §5.3 의 판정을 스크립트에 적어 두면 CI 가 통과·실패를 그대로 반환. 결과 해석을 사람이 매번 하지 않아도 됨 |
| **부하 생성기가 측정 대상과 자원을 다투지 않음** | Gatling 은 JVM 이라 같은 머신에서 돌리면 GC·힙이 백엔드와 섞여 **무엇을 재는지 불분명**해짐. EC2 1대 구성이라 이 위험이 실재 |

**STOMP 는 어느 도구를 써도 손으로 다뤄야 함** — 백엔드가 SockJS 미사용 순수 STOMP 이므로(`ARCHITECTURE §2.2`) 프레임(`CONNECT`·`SUBSCRIBE`·`MESSAGE`)을 직접 조립. 이 비용은 도구 선택과 무관하므로 선택 근거에 미포함.

**시나리오 1은 k6 로 재지 않는다.** HTTP 부하가 아니라 **DB 에 회차 N건을 심어 놓고 배치 틱을 관측**하는 성격. 구성은 준비 SQL(회차 적재) + `Clock` 을 시험용으로 전진시키거나 `confirm_at` 을 과거로 심는 방식 + **Prometheus 지표 관측**(`TECH_DECISIONS §13.1` 의 도래→완료 지연 · 미확정 회차 수). 부하 도구의 표현력이 필요하지 않은 대신 **관측 지표가 이미 있어야** 시험이 성립 — Phase 7 의 완료 조건에 지표 노출이 들어 있는 이유.

### 5.5 외부 지도 API 스텁

**실 API 를 때리지 않는다.** 레이트리밋에 걸려 시험이 중단되고 호출 비용이 발생.

| 항목 | 방침 |
|---|---|
| 구성 | `MapRouteClient` 포트의 스텁 구현을 **부하 시험 전용 프로파일**에서 주입 (`ARCHITECTURE §3.2` 가 이미 이 포트를 분리 대상으로 지정) |
| **지연 주입 필수** | 0ms 로 응답하는 스텁은 시나리오 1·4 를 무의미하게 만듦 — 계산 시간의 지배 요인이 외부 호출이기 때문. **실 API 의 응답 시간 분포를 한 번 측정해 그 값을 스텁에 주입** |
| 레이트리밋 재현 | 동시 호출 상한을 넘기면 스텁이 429 를 반환하도록 구성 — 시나리오 4의 "레이트리밋 초과 건수"가 이 응답으로 측정됨 |
| 실패 주입 | 타임아웃·5xx 를 일정 비율로 섞어 폴백 경로(직선거리 근사)가 부하 중에도 동작하는지 확인 |

⚠ **스텁의 지연 분포가 실 API 와 다르면 결과는 그 차이만큼 무의미.** 스텁 값의 출처(언제 무엇을 측정한 값인지)를 시험 결과와 함께 기록.

### 5.6 실행 시점 · 결과의 소비처

**배치 Phase 와 실시간 Phase 가 끝난 뒤.** 그 전에는 잴 대상 자체가 부재.

| 라운드 | 시점 | 시나리오 | 전제 |
|---|---|---|---|
| **L1** | Phase 10 완료 후 | 1 · 2 | 확정 배치(Phase 7)와 위치·WS(Phase 10)가 동작 + 지표 노출 |
| **L2** | Phase 13 완료 후 | 3 · 4 | 관제 구독(Phase 13)과 ②구간 승인 미리보기(Phase 8)가 동작 |

**결과는 인스턴스 증설 판단의 입력** (`ARCHITECTURE §9.5`). 연결 방식은 아래.

1. 시나리오 1의 **도래→완료 지연**이 임계를 넘으면 → 먼저 **워커 수**를 늘림 (배치 크기 상한이 아니라)
2. 워커를 늘려도 밀림이 누적되면 → **인스턴스 증설**
3. 증설을 결정하는 순간 **세 가지가 동시에 필요** — 다른 스케줄러의 분산 락(ShedLock, `TECH_DECISIONS §3.2` 로 이미 준비) · WebSocket 팬아웃의 외부 브로커 전환 · 최신 좌표의 Redis 공유(이미 성립)
4. 시나리오 3의 **동시 연결 수 한계**가 먼저 닿으면 순서가 뒤집힘 — 배치는 여유가 있는데 WS 세션이 포화하는 경우이며, 이때는 워커 증설이 아니라 곧바로 팬아웃 전환이 대상

**결과를 문서로 남긴다** — 측정값·스텁 지연 분포의 출처·판정. 다음 라운드가 이전 결과와 비교 가능해야 증설 신호를 읽을 수 있음.

#### L1·L2 결과 (2026-09-05, F3 L — `report-f3-l.md`, 원본 `backend/load/results/`)

전제 — 지도 API 는 `StubMapRouteClient` 에 **가정값** 주입(300~1200ms · `max_concurrent` 4, 출처 `docs/backend/LOAD_TESTING.md §5` — 실 API 분포는 키 미보유로 측정 불가). 단일 인스턴스 · DB `schoolbus_load` · 포트 18080.

| 시나리오 | N | 측정값 | §5.3 판정 |
|---|:-:|---|---|
| 1 동시 도래 | 10 / 50 / 200 | drain 10.7s / 29.7s / 114.8s · avg lag 10.2s / 28.4s / 67.6s · 미확정 0 / 0 / 0 | **통과**(30분 창 안, 미확정 0). 무너지는 지점 **N=200 까지 미관측** |
| 2 위치 수신 | 20 VU | POST 실패 0 · avg 21.9ms · p95 88.2ms | 임계 통과. ⚠ WS 메아리 수신 20/기대 180 — k6 스크립트 `lastSentAt` 게이트 의심(미수정, 관측만) |
| 3 관제 팬아웃 | 200 세션 | 연결 실패 0 · 방송 latency avg 16.3ms · p95 34ms | 임계 없음 — N=200 까지 미관측 |
| 4 온디맨드 경합 | 20 | avg 38.0ms · p95 42.1ms · 실패 0/20 | 임계 없음. ⚠ 시나리오 1 용 지연값으로 측정(온디맨드용 1~6s·실패율 0.1 아님) · 격벽 거부 Δ6 은 시나리오 1 기여분과 구분 불가(caller 구분 지표 부재) |

**증설 신호 판독** — N=200 에서도 미확정 0 이고 lag 가 창(30분) 대비 3.8% 라 워커·인스턴스 증설 신호 **없음**. 다음 라운드 비교 기준은 이 표. 남은 관측 공백 3건은 **F5 S2(2026-09-05) 로 해소** — 아래 추가 표.

**F5 S2 추가 실측 (2026-09-05, `report-f5-s2.md`, `docs/backend/LOAD_TESTING.md §5.1`)** — 시나리오 2 의 "수신 20/기대 180" 은 k6 스크립트 결함 2건(`position` 을 싣지 않는 매니저 채널 구독 + 기사 계정이 academy 채널 인가 거부)이었고, 올리는 쪽(기사 REST)과 받는 쪽(STAFF WS)을 분리하니 **echo 160/160 = 1.0**. 온디맨드 대역(1~6s · 실패율 0.1 · max_concurrent 4)으로 시나리오 1(N=10)+4(N=20) 동시 실행: 승인 미리보기 p95 **4.56s** · `throttled{caller=on_demand}` Δ16 · `throttled{caller=batch}` Δ6(온디맨드 대역을 같이 맞은 값 — 정상 대역 기준선 아님) · 타임아웃→폴백 1 · `MapRouteUnavailableException` 0. `throttled_total` 에 `caller` 태그 신설(`CallerPolicy` 1:1).

---

## 6. 구현 순서 (Phase)

### 6.1 순서를 정한 기준

`PRD §7.1` 의 P0/P1/P2 를 기준으로 하되 **선행 조건이 있는 것은 우선순위가 낮아도 앞**에 온다.

| 원칙 | 적용 |
|---|---|
| 스키마·시드·Swagger 가 가장 먼저 | 이후 전 Phase 가 여기에 얹힘 |
| 인증·계정 상태 게이트·RBAC 가 그다음 | 모든 API 가 3층 인가를 통과해야 함 (`ARCHITECTURE §5.1`) |
| 학원이 계정보다 앞 | 가입 시 학원을 선택하므로 학원 레코드가 선행 (`AUTH-02`) |
| 알림 아웃박스를 이른 시점에 | 가입 승인·변경 승인·승하차가 모두 알림을 발행. 뒤로 미루면 각 Phase 가 발행 지점 없이 완성됨 |
| 기초 데이터 → 노선 → 배치 → 운행 → 예외 → 관제 | 데이터 의존 방향 |
| 시간 기반 배치는 별도 Phase | 두 시계 분리 · 조건부 UPDATE 멱등 · 동시 도래 제어가 한 덩어리 (`ARCHITECTURE §9`) |
| 프론트는 백엔드 API 가 선행 | `ARCHITECTURE §4` |

`PRD §7.1` 이 P0~P2 어디에도 배치하지 않은 6건(도메인 STU·MGR·BUS, 기능 RTE-01·RTE-07·ATT-03)은 **온보딩과 배차 파이프라인의 선행 조건**이므로 실행 순서상 Phase 5·6·8 에 포함. 우선순위 문언의 공백이 실행을 막지는 않으며, 배치 확정은 §7 에 등재.

---

### Phase 0 — 걷어내기 · 골격 세우기

| 항목 | 내용 |
|---|---|
| **범위** | §1.3 의 도메인 15개 삭제 · §1.2 의 `global`·`observability` 선별 존치 · `ARCHITECTURE §3.1` 의 새 모듈 16개 골격 생성 · `TECH_DECISIONS §11` 의 의존성 추가 |
| **선행** | 부재 |
| **산출물** | 빈 모듈 디렉터리 16개(`academy`·`account`·`student`·`manager`·`bus`·`schedule`·`routing`·`request`·`run`·`boarding`·`exception`·`location`·`notification`·`monitoring`·`audit`·`global`) · 갱신된 `build.gradle` · 정리된 `application.yml` |

**추가 의존성** — ShedLock(spring · provider-jdbc-template) · Resilience4j(spring-boot3) · `spring-boot-starter-cache` · Testcontainers(postgresql · junit-jupiter · `spring-boot-testcontainers`).

**완료 조건**
- `./gradlew build` 성공 — 옛 도메인 참조가 하나도 남지 않음
- `docker compose up -d postgres redis` 후 `./gradlew bootRun` 기동 성공
- `http://localhost:8080/swagger-ui/index.html` 열림 (엔드포인트 0개 상태)
- §1.5 의 존치 테스트 4종이 통과 또는 새 구조에 맞춰 이관 완료

---

### Phase 1 — 스키마 · 엔티티 매핑 · 시드 · Swagger 골격

| 항목 | 내용 |
|---|---|
| **범위** | `V1__init_schema.sql` 재작성(**39테이블** · UNIQUE · CHECK · 인덱스) · 전 테이블 JPA 엔티티 매핑 · `db/migration-local` 시드 재작성 · `SeedFixtures` · Flyway `clean` 전략과 안전장치 5겹 · `OpenApiConfig` 재작성 |
| **선행** | Phase 0 |
| **참조** | `ERD §3`·`§4`·`§5` · `ARCHITECTURE §12` · §2 · §3 |
| **산출물** | 마이그레이션 2파일 · 엔티티 **39** · `SeedFixtures` · 대조 테스트 3종 |

⚠ **`ddl-auto: validate` 는 컬럼·타입·이름만 검사하고 CHECK 제약·partial UNIQUE·FK 지연 설정을 전혀 보지 않는다.** 즉 **Phase 1 통과가 정원 계산식·확정 시각식·`run_stop` 배타 조건 같은 불변식의 보장을 뜻하지 않는다.** 그 불변식들은 각 도메인 Phase 가 `§4.6` 사이클로 테스트를 붙여야 검증된다.

⚠ **시드의 PK 를 시퀀스 자동 증가에 맡기지 않고 SQL 에서 명시 고정한다.** `SeedFixtures` 상수가 PK 를 담는데, 시드 앞쪽에 행을 하나 끼워 넣는 것만으로 뒤쪽 상수가 전부 어긋나기 때문. 사양에 정해진 바가 없어 여기서 확정한다.

**엔티티는 필드 매핑과 연관관계까지만.** 상태 전이 메서드·정책 판정은 각 도메인 Phase 에서 붙인다. 지금 전부 매핑해 두는 이유는 `ddl-auto: validate` 가 **매핑된 엔티티만** 검사하기 때문 — 일부만 매핑하면 나머지 테이블의 어긋남이 한참 뒤에 드러남.

**완료 조건**
- `docker compose down` → `up -d postgres redis` → 기동 시 **39** 테이블 생성 + 시드 적재
- `ddl-auto: validate` 통과 (엔티티 **39**개 전부 매핑된 상태)
- 재기동 시 `clean` → `migrate` 로 시드가 초기 상태로 복귀
- `DeploymentConfigGuardTest` — `prod`·`demo` 프로파일에서 `clean-disabled=true` 이고 전략 빈 부재
- `SeedFixturesContractTest` 통과
- Swagger UI 최상단에 `SeedFixtures` 기반 계정표가 렌더링

---

### Phase 2 — 인증 · 계정 상태 게이트 · RBAC · 학원 격리 · 본인 프로필 · 푸시 단말

| 항목 | 내용 |
|---|---|
| **범위** | `AUTH-01~05` · `AUTH-07~09` · `C-01` · `C-11` · `C-14` · `ARCHITECTURE §5` 3층 전체 · `§6` 학원 격리 |
| **선행** | Phase 1 |
| **기능 ID** | AUTH-01 · 02 · 03 · 04 · 05 · 07 · 08 · 09 (P0) |
| **참조** | `API_SPEC §1.4`·`§1.5`·`§2` · `FEATURE_SPEC §6.2` · `TECH_DECISIONS §2` |
| **산출물** | `account` 모듈 · 권한 상수 **31종** · 역할↔권한 부여표 · 계정 상태 필터 · 저장소 격리 강제 · **refresh 쿠키 조립기 1개** |

**세 층을 각각 다른 지점에 둔다.** ① 계정 상태 게이트는 필터·인터셉터 **한 곳**에 허용 목록 방식으로 두고 기본 차단. ② 역할 권한은 권한 상수 경유 메타 애너테이션. ③ 자원 소속 검증은 저장소 계층.

**refresh 토큰의 전달 수단이 클라이언트별로 갈린다** (`API_SPEC §1.2.1` · `TECH_DECISIONS §2.4`). 앱은 응답 본문, 웹은 `HttpOnly` 쿠키다. **갈림은 컨트롤러 한 층에만 두고** 서비스·저장소는 클라이언트 종류를 모른다 — 발급·적재·무효화 규칙이 전송 수단과 무관하기 때문. 쿠키 조립은 **한 곳(쿠키 조립기)에 모은다**. 엔드포인트마다 손으로 `ResponseCookie` 를 만들면 `Secure` 한 개가 빠진 경로가 생기고, 그 누락은 로컬에서 재현되지 않는다.

**완료 조건**
- Swagger 에서 흐름 완주: 학원 검색 → 가입 → `pending` 상태 조회 → (승인은 Phase 3) → 로그인 → 토큰 재발급 → 로그아웃
- `pending` 토큰으로 허용 **5개**(`GET /auth/signup-status` · `POST /auth/logout` · `GET /me` · `POST /me/devices` · `DELETE /me/devices/{token}`) 외 API 호출 시 전부 `403 AUTH_PENDING`
- `rejected` 토큰은 **6개**까지 허용(`pending` 의 5개 + `POST /auth/signup/reapply`), 거부 시 `403 AUTH_REJECTED`
- `blocked` 계정 로그인 시 `403 AUTH_ACCOUNT_BLOCKED`
- 로그인 실패 누적이 상한에 도달하면 계정 단위 차단, 성공 시 카운터 초기화
- A학원 `staff` 토큰으로 B학원 자원 조회 시 `403 ACADEMY_SCOPE_VIOLATION` — **목록 조회에서도 성립**
- `X-Client-Type: web` 로그인 응답 — 본문에 `refresh_token` 부재 + `Set-Cookie` 에 `HttpOnly`·`Secure`·`SameSite=Strict`·`Path=/api/v1/auth` 4속성 전부 존재
- 쿠키만 담은 `POST /auth/refresh` 가 access 재발급 성공, 본문·쿠키 모두 부재 시 `401 TOKEN_EXPIRED`
- 웹 로그아웃 응답에 `Max-Age=0` 쿠키 삭제 지시 존재
- `ControllerAuthorizationConventionTest` — 인가 애너테이션 누락 핸들러 0건
- 역할↔권한 부여표 테스트 — `FEATURE_SPEC §6.1` 매트릭스와 1:1 대조

---

### Phase 3 — 학원 · 관계자 승인 · 메인 관리자 콘솔 기초

| 항목 | 내용 |
|---|---|
| **범위** | `ACAD-01~06` · `AUTH-06` · `AUTH-10` · `AUTH-11` · `O-01` · `O-02` · `O-03` |
| **선행** | Phase 2 |
| **기능 ID** | ACAD-01~06 · AUTH-06·10·11 (P0) |
| **참조** | `API_SPEC §6` · `USER_FLOWS UF-O-01`·`UF-O-03`·`UF-O-04`·`UF-M-01` |
| **산출물** | `academy` 모듈 · 가입 승인 처리(관계자용 · 메인 관리자용) · 차단 해제 |

**승인 주체가 둘로 갈림** — 학부모·학생·매니저는 관계자가, 관계자는 메인 관리자가 승인. 학원당 관계자 1명은 `academy_staff(academy_id)` UNIQUE 가 강제하고 초과 시 `409 STAFF_QUOTA_EXCEEDED`.

**계정↔레코드 연결이 승인의 일부** (`AUTH-11`) — 연결 없이 승인하면 `active` 이면서 데이터 접근이 불가한 계정이 생김.

**완료 조건**
- Swagger 완주: 메인 관리자 로그인 → 학원 등록 → 관계자 가입 요청 승인 → 관계자 로그인 → 학부모 가입 요청 승인·거절
- 같은 학원에 두 번째 관계자 승인 시 `409`
- 거절된 계정이 재신청으로 `pending` 복귀
- 학원 비활성화 후에도 기존 사용자 로그인 유지, 신규 가입만 차단
- 차단 계정 해제 후 로그인 성공 + 처리자·일시 이력 적재

---

### Phase 4 — 알림 아웃박스 골격

| 항목 | 내용 |
|---|---|
| **범위** | `ARCHITECTURE §11` 파이프라인 · `TECH_DECISIONS §7` 아웃박스 · 가입·승인 계열 알림 |
| **선행** | Phase 3 |
| **기능 ID** | NTF 인프라 · `signup_decided` (P0) |
| **참조** | `API_SPEC §9.7` · `ERD` `notification_log` |
| **산출물** | `notification` 모듈 · 이벤트 구독 · 아웃박스 워커 · 발송 포트 |

**왜 여기인가** — 승하차·운행·승인이 전부 알림을 발행하므로 발행 지점을 뒤로 미루면 각 Phase 가 "이벤트를 발행하지 않는 상태"로 완료 판정을 받게 됨. 개별 알림 종류는 각 기능 Phase 에서 더한다.

**지켜야 하는 것 3가지** — 레코드 INSERT 는 상태 변경과 **같은 트랜잭션**, 발송은 **트랜잭션 밖**, 중복은 `dedup_key` UNIQUE 로 DB 가 차단. `notification` 을 다른 모듈이 직접 호출하지 않고 이벤트 구독으로만 동작.

**완료 조건**
- 가입 승인·거절 시 `notification_log` 행이 생기고 `push_state` 가 `sent` 로 전이
- 발송을 강제 실패시켜도 상태 변경 트랜잭션이 롤백되지 않음
- 시드의 `push_state='pending'` 행을 워커가 회수해 재발송
- 같은 `dedup_key` 를 즉시 발송과 워커가 동시에 집어도 한쪽만 성공

---

### Phase 5 — 기초 데이터: 학생 · 보호자 · 주소 · 차량 · 인력 · 스케줄

⚠ **Phase 2 에서 이월 (2026-08-25, Ruling 117)** — **학부모 → `GuardianStudent` 연결 확인 축소**. Phase 2 Task 5 가 학원 격리와 메인 관리자 예외는 완성했으나 이것은 미착수. 사유는 **배치할 곳 부재** — `p2-controller-conventions §1` 이 `access/` 를 "판정이 2곳 이상에서 필요할 때만" 두라고 규정하는데 당시 소비자가 0곳이었고, `global/` 에 두면 `global` → `student` 역방향 의존이 생긴다. **학생 조회 엔드포인트를 만드는 이 Phase 가 첫 소비자**이므로 여기서 함께 만든다. 완료 조건에 "연결 부재 자녀 조회가 `403 FORBIDDEN`" 을 포함할 것.

| 항목 | 내용 |
|---|---|
| **범위** | `STU-01~08` · `P-02` · `P-05` · `S-05` · `BUS-01~04` · `MGR-01~06` · `SCH-01~03` |
| **선행** | Phase 3 (Phase 4 는 알림 발행에만 필요) |
| **기능 ID** | STU-01~08 · BUS-01~04 · MGR-01~06 · SCH-01~03 · ATT-03 |
| **참조** | `API_SPEC §5.11`~`§5.13` · `§3.2`~`§3.4` · `§3.7` · `USER_FLOWS UF-M-06`·`UF-P-01`·`UF-P-03` |
| **산출물** | `student`·`bus`·`manager`·`schedule` 모듈 · 승하차지 마스터 생성 · 일일 회차 생성 배치 |

**승하차지 마스터의 소유는 `student` 모듈** — 생성 계기가 주소 검증(`STU-05`)이며 근접 좌표 병합도 **학생 등록·주소 수정 시점**에 수행. 노선 계산은 이미 묶인 승하차지를 입력으로 받음 (`ARCHITECTURE §3.3`·`§8.2`).

**일일 회차 생성**(`SCH-02`)은 하루 1회 배치이며 `run(bus_id, service_date, direction, depart_time)` UNIQUE 가 중복 실행을 방어.

**주소 검증은 `GeocodingClient` 포트 + 결정론적 스텁으로 판정한다** (2026-08-26 Ruling 149) — 완료 조건이 재는 것은 좌표 정확도가 아니라 "검증 → 승하차지 매칭" 파이프라인이고, 실 API 어댑터는 Resilience4j 보호(`§7` 규칙 11)와 함께 **Phase 6** 이 만든다. 스텁은 **결정론적**이어야 하고 `422 ADDRESS_VERIFICATION_FAILED` 를 재현할 실패 입력을 계약에 둔다.

**완료 조건** (2026-08-26 정정 — Ruling 152·154·155. 정정 근거는 `progress.md`)
- Swagger 완주: 관계자 로그인 → 학생 등록 → 차량 등록 → 매니저 등록 → 스케줄 등록 → **학부모** 로그인 → 자녀 연결 → 요일별 주소 설정(주소 검증 → 승하차지 매칭) → 회차 생성 배치 실행 → 오늘 회차 조회 → 매니저 배치
  - ⚠ **주소는 관계자가 넣지 않는다**(`API_SPEC §5.11`·`A-10`, 2026-08-24 확정) — 검증·매칭이 일어나는 자리는 학부모의 요일별 주소(`§3.7`)다
  - ⚠ **배치는 회차 생성 뒤여야 한다** — `assignment.run_id` 가 FK NN 이라 회차 없이 배치 행을 만들 수 없다
- 정원 계산 정합 (`BUS-04`) — `student_capacity` 가 요청값이 아니라 서버 계산값이고 CHECK 가 계산식을 강제. **배정 초과 차단(`409 CAPACITY_EXCEEDED`)은 배정 경로가 생기는 Phase 7·8 소유**(Ruling 155)
- 근무 시간이 겹치는 매니저 중복 배치 시 **경고 반환** (`MGR-06`) — 저장은 되고 응답 `warnings[]` 에 충돌이 실린다. **차단 부재**가 정본 3곳(`API_SPEC §5.14`·`USER_FLOWS`·`PRD`)의 문면 (Ruling 152)
- 배치된 매니저 삭제 시 차단 (`MGR-04`)
- 학부모 자녀 연결: 연결 요청 → 학생 앱 코드 생성 → 코드 입력 → `guardian_student` 생성. 같은 자녀 재연결 시 `409 ALREADY_LINKED`
- 요일 × 방향 조합 중복 저장 시 UNIQUE 위반
- 회차 생성 배치를 두 번 돌려도 회차가 늘지 않음

---

### Phase 6 — 노선 계산 파이프라인 · 고정 노선

| 항목 | 내용 |
|---|---|
| **범위** | `RTE-01` · `RTE-09` · `ARCHITECTURE §8.1`~`§8.3` 계산 5단계 · 외부 지도 API 어댑터 |
| **선행** | Phase 5 |
| **기능 ID** | RTE-01 · RTE-09 · A-08 |
| **참조** | `ARCHITECTURE §8` · `TECH_DECISIONS §8` · `PRD §5` |
| **산출물** | `routing` 모듈의 계산 파이프라인 · 전략 포트 3종(`RouteEngine`·`MapRouteClient`·**`AttendantAssigner`**) · Resilience4j 보호 |

**계산 5단계** — ① 좌표 해석 ② 순서 최적화(전략 포트) ③ 도로 경로(외부 API, 경유지 상한 초과 시 구간 분할) ④ ETA 산출 ⑤ 동승자 자동 배정. **⑤가 계산 뒤에 오는 이유는 소요 시간 확정 후라야 근무 시간 충돌을 판정 가능**하기 때문.

**타임아웃·재시도·동시성 제한은 호출자가 주입** — 같은 계산 코드를 배치와 온디맨드가 공유하되 실행 정책이 다름 (`ARCHITECTURE §8.3`).

~~⚠ **막히는 지점** — 최적화 알고리즘 기준(거리·시간·정원 가중치)이 보류 (`PRD §10.1 G`). 전략 포트로 격리했으나 품질 회귀 판정 수단이 부재.~~

✅ **2026-08-29 정정(Ruling 178)** — **판정 수단은 부재하지 않는다.** `ARCHITECTURE:595 R5` 가 `TECH_DECISIONS §8.5` 를 지목하고, 거기에 계산 스냅샷 4항과 고정 데이터셋 3종 회귀 시험이 규정돼 있다. 보류로 남는 것은 **가중치 기준**뿐이며 `PRD §7.1` 이 **P2 후속**(F-01)에 둔다. ⚠ **세 번째 포트 이름은 `AttendantAssigner`** 다 — `ARCHITECTURE §8.2` 가 "학생을 버스에 배정하는 단계가 부재" 를 명시하므로 위 산출물 열의 옛 `BusAssigner` 는 오기였다(Ruling 181).

**완료 조건**
- 고정 노선 편성·조회가 Swagger 에서 동작 (차량 × 요일 × 방향당 1개, UNIQUE 강제)
- 계산 파이프라인 단위 테스트 — 좌표 미확보 학생이 분리되고 나머지가 계산됨
- 외부 지도 API 타임아웃 시 폴백(직선거리 근사)으로 결과가 나오고 **폴백 사실이 결과에 표시**
- 경유지 상한을 넘는 입력에서 구간 분할이 동작
- Resilience4j 서킷 개방 시 온디맨드 호출이 즉시 오류 반환

---

### Phase 7 — 시간 기반 배치: 확정 노선 산출 · 배포 · 버전

| 항목 | 내용 |
|---|---|
| **범위** | `RTE-02` · `ARCHITECTURE §9` 전체 · `§8.5` 배포·버전 · `M-02` |
| **선행** | Phase 6 |
| **기능 ID** | RTE-02 · RTE-08 · RUN-01 (P0) |
| **참조** | `ARCHITECTURE §9` · `TECH_DECISIONS §3`·`§5`·`§6` |
| **산출물** | 도래분 폴링 스케줄러 · 조건부 UPDATE 전이 · 워커 풀 · 배치 지연 지표 · `route_version` 발행 |

**이 시스템의 중심축.** 한 덩어리로 다루는 이유는 아래 넷이 서로 맞물려 있어서.

| 요소 | 내용 |
|---|---|
| 도래분 폴링 | `status='idle' AND confirm_at <= now()` 를 주기 조회, 배치 크기 상한 |
| **두 시계 분리** | 실행 시각(`now()`)은 **어떤 값을 읽을지**, 판정 시각(출발 −30분)은 **무엇을 허용할지**. 분리하지 않으면 배치 지연이 마감을 옮김 |
| **조건부 UPDATE 멱등** | `UPDATE run SET status='confirmed' WHERE id=? AND status='idle'` — 갱신 행 수 0이면 선점됨 |
| 동시 도래 제어 | 워커 풀 크기를 지도 API 레이트리밋에 맞춤 · 회차 단위 `try/catch` 격리 · 실패 시 `idle` 복귀 |

**`Clock` 빈 주입이 여기서 필수** — 두 시계를 테스트로 고정하려면 시각을 주입 가능해야 함 (`TECH_DECISIONS §6`).

**완료 조건**
- 시드의 R1(`idle`)이 시각 도달 시 `confirmed` 로 전이하고 `confirmed_route` + `route_version`(v1) + `run_stop` + `run_rider` 가 생성
- 같은 회차에 배치를 두 번 실행해도 확정이 한 번만 발생 (조건부 UPDATE 검증)
- `Clock` 을 25분 전으로 고정해 배치를 돌려도 **구간 판정은 출발 −30분 기준** — 27분 전 요청이 ①구간으로 처리되지 않음
- 회차 1건이 실패해도 나머지가 확정되고, 실패분이 `idle` 로 복귀해 다음 틱에 재시도
- 확정 시 기사·동승자에 `route_changed` 알림 발행
- **배치 도래→완료 지연 지표**가 `/actuator/prometheus` 에 노출

**⚠ 선행 Phase 가 등재한 것 3건 (착수 전 목표 표에 반드시 옮긴다)**

| # | 항목 | 등재 근거 |
|:-:|---|---|
| 1 | **삭제된 매니저의 계정이 자기 회차·명단(매니저 앱 자료)에 닿는가** — 기사·동승자 축 | Phase 5 T7 리뷰가 Important 로 지적했으나 **Ruling 148 이 로그인 자체는 그대로 200 임을 확정**(`API_SPEC §8.1` 코드 부재·`§5.2` 접근층 위임·`§6.7` 스태프 전용 근거, **Ruling 192 로 재확인**). 남는 것은 로그인 여부가 아니라 **매니저 앱 데이터 접근 축**이고, 그 엔드포인트가 없는 이 Phase 가 아니라 **그것을 만드는 Phase 9 가 소유**한다 |
| 2 | **확정 배치가 `origin`·`destination` 좌표를 어디서 얻는가** | **Ruling 184**(2026-08-29) — Phase 6 은 `optimize` 에서 **호출자가 좌표를 넘기게** 했다(`academy`·`route` 에 좌표 컬럼 부재). ⚠ **확정 배치는 사용자 입력 없이 도므로 그 길이 막힌다.** `academy` 에 좌표 컬럼을 추가할지가 **Phase 7 의 선행 판정**이고, 택하면 `POST /staff/routes/{id}/optimize` 계약이 함께 바뀐다 |
| 3 | **`AcademyScopeRepositoryConventionTest` 가 이름만 보고 통과한다** | Phase 6 T5 리뷰 실측 — JPQL 의 `AND r.academyId` 를 지워도 전건 통과. `AcademyScopeRule` 자바독이 예고한 한계이고 **Phase 7 이 조인을 늘리면 그만큼 넓어진다** |

---

### Phase 8 — 탑승 의사 · 변경 요청 · 3구간 승인 · 관계자 노선 조작

| 항목 | 내용 |
|---|---|
| **범위** | `ATT-01~03` · `REQ-01~05` · `RTE-03·04·06·07·10` · `A-05·A-06·A-07·A-15` · `C-04` · `C-05` |
| **선행** | Phase 7 |
| **기능 ID** | ATT-01·02·03 · REQ-01~05 · RTE-03·04·06·07·10 (P0/P1 혼재) |
| **참조** | `API_SPEC §1.6`·`§3.6`·`§3.8`·`§5.5`~`§5.9`·`§5.15` · `USER_FLOWS UF-P-04`~`UF-P-06`·`UF-M-02`~`UF-M-04`·`UF-M-08` |
| **산출물** | `request` 모듈 · 3구간 판정 정책 객체 · 온디맨드 계산 · 미리보기→배포 경로 |

**3구간 판정은 `domain` 의 정책 객체 하나에 모은다** (`ARCHITECTURE §3.2`). 컨트롤러나 각 command 에 흩어지면 같은 요청이 호출 경로에 따라 다른 구간으로 판정됨.

**`request` 를 별도 모듈로 둔 이유** — 탑승 의사(ATT)와 변경 신청(REQ)이 담는 데이터는 다르나 **3구간 판정·승인 큐·회차당 1회 한도**를 공유. 나누면 그 규칙이 복제됨.

⚠ **온디맨드 계산의 응답 시간** — 승인 대기 **목록**은 요약만 반환하고 전/후 대조는 **상세 조회에서 1건만** 계산 (`ARCHITECTURE §8.4`). 목록에서 항목마다 재최적화를 돌리면 N배 계산이 발생. 계산 결과는 요청 단위로 캐시하고 입력이 바뀌면 무효화 — 관리자가 본 미리보기와 배포되는 노선이 달라지지 않게.

**②구간 자동 거절은 동기 + 폴링 두 경로** — 운행 시작은 `POST /runs/{runId}/start` 트랜잭션 안에서 동기 종결하고, 출발 시각 도달분만 폴링이 담당 (`ARCHITECTURE §9.6`). 폴링만 두면 시작 직후 승인이 통과하는 창이 생김.

**완료 조건**
- ①구간 토글이 승인 없이 즉시 반영되고 재최적화가 발생
- ②구간 요청이 승인 대기로 접수되고 관계자 채널에 `approval_requested` 방송
- ②구간 승인 시 재최적화 → `route_version` +1 배포 → 기사·동승자 푸시
- ②구간 거절 시 기존 노선 유지 + 사유 통지
- **회차당 1회 한도** — 소진 후 재요청 시 `403 CHANGE_LIMIT_REACHED`. 등원 회차와 하원 회차가 한도를 공유하지 않음
- 자동 거절 — 출발 시각 도달과 운행 시작 중 **먼저 오는 시점**에 `auto_rejected` 전이, 기존 노선 유지, **횟수 미소진**
- 서버 처리 실패 시 기존 상태 복구 + 횟수 미소진
- ③구간 미등원 요청이 승인 없이 수용되고 해당 승하차지가 `skipped` — 순번 유지·재최적화 부재
- ③구간 그 외 요청은 `403 CHANGE_WINDOW_CLOSED`
- 강제 추가는 ①구간에서만 성공, ②구간에서 `403`
- 경유 지점 지정 후 `apply=false` 상태에서 확정 노선이 불변임을 확인, `apply=true` 로 배포 시 버전 증가
- 승인 대기 목록 조회에서 노선 계산이 발생하지 않음 (호출 수 검증)

⚠ **착수 전 판정 Ruling 195~199 (2026-08-30)** — 목표 표는 `docs/archive/sdd/IMPLEMENTATION_PLAN/p8-goal-table.md`. 위 완료 조건 12항 중 **4항이 "이 Phase 에 검사 대상이 부재" 한 문면을 담고 있었고** 착수 전에 갈랐다. Phase 7 이 같은 형태로 두 번 틀린 자리다.

| # | 판정 | 이 Phase 몫 | 이월 |
|:-:|---|---|---|
| **195** | `approval_requested` 는 **푸시**(`API_SPEC §9.7`)와 **WebSocket**(`§7`) **두 경로**다. WS 채널 4종은 Phase 10 산출물이고 현재 STOMP 엔드포인트는 `/ws/location` 하나라 **방송을 검사할 대상이 부재** | `notification_log` 적재 + 커밋 후 도메인 이벤트 발행 | **Phase 10** — `/ws/academy/{id}/live` 방송 |
| **196** | ②구간 자동 거절의 두 시점 중 **운행 시작 쪽**은 `POST /runs/{runId}/start` 가 있어야 검사되는데 그 핸들러가 **Phase 9 산출물**이다 (`ARCHITECTURE §9.6`) | 출발 시각 도달 폴링 + `moving` 종결 **도메인 서비스**와 그 단위 검사 | **Phase 9** — start 트랜잭션 안 호출 배선 실측 |
| **197** | **범위 행은 `RTE-07`·`A-07` 을 싣는데 완료 조건 12항에 대응 문면이 부재**하고, 정본 `API_SPEC §5.8`·`§10` 이 여전히 `[조정 중]` 이다 | `§5.7 forced-add` **부분 해제** — 요청·응답을 확정하고 `§10` 표에서 지운다 | **별도 단위** — `§5.8 transfer`. forced-add 가 확정된 뒤라야 추측 없이 정의된다 |
| **198** | ⚠ **정본 안에서 두 문장이 어긋난다.** `C-04 ①`·`API_SPEC §3.6` ①행은 "①구간 즉시 반영 + **재최적화**" 인데, ①구간(출발 30분 전까지) 동안 회차는 `idle` 이고 `confirmed_route` 가 **부재**해 재최적화할 대상이 없다. `ARCHITECTURE §9`(확정 시각이 `run.confirm_at` **컬럼** · `ck_run_confirm_at` CHECK)가 이긴다 | ①구간은 `boarding_intent`·일일 승하차지만 갱신 — **파이프라인 호출 0회**. 반영은 뒤이은 확정 배치가 한다. **예외** — 임시 추가(`API_SPEC §5.10`)로 출발 30분 이내에 만든 회차는 곧바로 확정되므로 **②구간부터 시작** | **정본 개정** — `API_SPEC §3.6` ①행 · `FEATURE_SPEC C-04 ①` 에 각주 |
| **199** | 아래 "Phase 2 에서 이월" note 가 **"회차 조회 경로가 이 Phase 의 산출물" 이라고 적는데 거짓**이다 — 매니저용 회차 조회는 `API_SPEC §4.1` 이고 `§4` 전체가 Phase 9 참조다. 진행 추적 표 Phase 2 행은 이미 **Phase 5·9** 로 적고 있어 **행 쪽이 옳다** | 부재 | **Phase 9** — 매니저 → `Assignment` 범위 축소 |
| **200** | ⚠ **`CHANGE_WINDOW_CLOSED` 의 상태 코드가 정본 안에서 갈렸다** — `API_SPEC §5.6` 에러 목록만 `409` 이고 나머지 **8곳이 전부 `403`**(`§1.6 ③`·`§3.6`·`§3.8`·`§5.7`·`§5.8`·`§5.15`·`§8.3` 사전). T6 이 구현 중 발견해 신고 | **403 으로 통일.** 코드의 정의 자리인 **§8.3 사전이 이기고** 각 절은 사용처다. `ErrorCode` 는 코드 하나에 상태 하나를 싣는 구조라 두 값을 함께 둘 수 없고, 코드를 새로 만드는 것은 "새 상태값을 만들지 않는다"(`CLAUDE.md`)에 걸린다 | **정본 개정** — `§5.6` 그 줄을 `403` 으로 정정(완료) |
| **201** | `API_SPEC §4.16` 원안이 내비 공급자를 **필수 요청 파라미터**로 뒀는데, MVP 가 카카오 단독이라 값이 하나뿐이고 **정작 갈리는 값(경유지 상한)은 서버만 안다** — 앱이 고를 근거가 부재 | **요청에서 `provider` 제거.** `app.navigation.provider` 설정이 활성 공급자를 정하고 **응답의 `provider` 필드가 앱에 알린다**. 자르기를 서버가 하는 이유(기기마다 다르게 잘리면 같은 회차가 폰마다 다른 경로)가 공급자 선택에도 그대로 적용 | **정본 개정** — `§4.16` 요청 표·`§8.4` enum(완료) |
| **202** | **X-01 해소 (2026-08-31 사용자 확정).** 운행 시작 창이 ±3분이라 차량·교통 지연이 3분을 넘기면 **시작 수단 자체가 사라진다.** 예외안 2개는 각각 새 실패 지점을 만든다 — 지연 사유 입력은 기사 단독 우회로 창의 의미를 없애고, 원격 해제는 관계자 미접속이면 운행이 막힌다 | **창을 ±10분으로 넓혀 흡수.** 예외 절차는 두지 않는다. 함께 확정 — **출발 30분 전 `confirmed` 시점부터 노선 열람 가능**하며 수단은 별도 화면이 아니라 **RUN-08 외부 내비 전달** | **정본 개정 6종**(완료) — `FEATURE_SPEC`·`API_SPEC`·`USER_FLOWS`·`PRD`·`TECH_DECISIONS`·`ARCHITECTURE` |
| **203** | **X-03 해소 (2026-08-31 사용자 확정).** 기사에게 승하차 기록 권한을 임시로 주면 `C-06`(동승자 전용)이 **회차마다 갈리는 조건부 규칙**이 되어 인가 판정이 데이터에 의존 | **동승자가 없는 회차는 없다고 가정.** 결근은 운영에서 대체 배치로 흡수. **Phase 9 권한 모델 불변** | **정본 개정** — `FEATURE_SPEC §8`·`PRD §10.2`(완료) |
| **204** | **V 해소 (2026-08-31 실측).** 카카오 SDK `viaList` 는 공식 레퍼런스가 **"경유지 목록(최대: 3개)"** 로 명시. 티맵은 앱 실행 스킴 규격이 **미공개**라 경유지 파라미터의 근거가 부재하고, 상한 1 이면 `remaining` 이 `next` 와 같아져 기능이 성립하지 않음 | **MVP 는 카카오 단독 · 상한 승하차지 4곳**(경유지 3 + 목적지 1). 티맵은 어댑터 자리만 두고 미구현. ⚠ **앱의 손 입력 경유지 수(카카오 5)와 다른 앱이 넘기는 수(3)는 별개 값** — 손 입력 수를 상한으로 잡으면 조용히 잘린다 | **정본 개정** — `§4.16` 상한 표(완료). 상한은 `KakaoNavProvider` **한 곳**에만 (목표 22) |
| **205** | Phase 9 절 본문이 `§4.3 GET /runs/{runId}/route` 를 "`LOC-03`, Phase 10" 으로 적었으나 **응답 필드에 버스 위치가 0개**다 — `stops[]`·`current_stop`·`next_stop`·`skipped_notice` 는 전부 확정 노선 + 미승차 반영. `LOC-03` 태그는 **화면(M-09) 귀속**이지 데이터 출처가 아니다. 없으면 범위에 든 `RUN-03`(운행모드)에 노선 조회 수단이 부재 | **`§4.3` 은 Phase 9 소유.** `next_stop.lat`·`lng`(외부 내비 콜백용) 포함 | **Phase 9** — 목표 표 T1 좌석 |
| **206** | ⚠ **이월 note 안의 사실 서술이 Phase 5 부터 낡아 있었다.** "`Assignment` 엔티티는 있으나 **저장소가 없고**" 라고 적혀 있었으나 `AssignmentRepository` 는 **Phase 5 산출물**(`3b43945`, 119줄)이고 목표 17 에 필요한 파인더를 전부 갖췄다. **이 note 는 Ruling 117 → 199 로 두 번 개정됐는데 매번 *어느 Phase 소유인가* 만 다시 봤고 그 안에 박힌 *사실 주장* 은 한 번도 재확인되지 않았다** | 그 구절을 삭제하고 정정 각주를 남긴다. **저장소를 새로 만들지 않는다** — 만들었으면 같은 뜻의 파인더가 두 벌 남았다 | **정본 개정**(완료). ⚠ **교훈 — 이월 note 를 옮길 때 소유 Phase 만 보지 말고 안에 박힌 "무엇이 없다" 를 그 자리에서 재계수하라**(`phase-goal-loop §6.1`) |
| **207** | ⚠ **목표 8("잔여 탑승자 0명 → 승하차지 `skipped` 전환")이 갈래가 둘인데 목표 표가 T1 한 좌석에만 배정했다.** ①**출발 전 결석 신청**(`request` 모듈) — T1 이 `BoardingIntentCommandService:197` 에서 `runStop.markSkipped()` 로 **구현 완료** ②**운행 중 미승차·하차 처리**(`API_SPEC §4.6`) — T3 의 `RiderStatusUpdateResponse.stopSkipped` 가 **항상 `false` 로 하드코딩**돼 있고, 그것을 계산하려고 미리 만든 `RunRiderRepository.countByRunIdAndStopIdAndStatusNotIn` 은 **호출부가 없는 죽은 코드**다. T3 게이트 리뷰가 발견 | **②를 T3 소유로 배정하고 수정 라운드를 발주한다.** `stop_skipped` 는 `§4.6` 응답 표의 필드이고 `§7.1 rider_changed` 방송 페이로드에도 실린다 — 계산 주체가 없으면 **C-05 가 이 경로에서 영구히 미반영**된다 | **Phase 9** — T3 수정 라운드. ⚠ **교훈 — 완료 조건 하나가 *여러 엔드포인트에 걸치는지* 를 좌석 배정 전에 확인하라.** `403` 누락(2026-08-31)과 **같은 형태**다: 한 조건이 두 곳에서 성립해야 하는데 표가 한 곳만 담았다 |

---

⚠ **Phase 7 에서 이월 (2026-08-30)** — 아래 5건. **착수 전 목표 표에 옮긴다.**

| # | 항목 | 근거 |
|:-:|---|---|
| 1 | **`MANAGER_DOUBLE_BOOKED` 를 겹침 판정으로 올린다** — 지금 JPQL 이 `r.departTime = :departTime` 이라 **출발 시각이 정확히 같을 때만** 중복으로 센다. `PRD:122`·`USER_FLOWS:482` 는 "시간 겹침"·"시간 중복" 이라 **사양의 부분 구현**이다. 등원 회차 직후 하원 회차에 같은 매니저를 붙이는 형태가 실재 가능하다(`schedule` UNIQUE 가 서로 다른 출발 시각을 막지 않는다). 메서드 이름은 이미 `existsOverlappingAssignment` 라 그대로 두면 된다 | **Ruling 193** · `p7-review-t7-verdict.md` |
| 2 | **`Clock` 을 출발 25분 전으로 고정해도 27분 전 요청이 ①구간으로 처리되지 않는다** — Phase 7 목표 3 의 뒷부분. **①/②구간 판정기가 이 Phase 의 산출물**이라 Phase 7 에는 검사 대상 자체가 부재했다(`request/` 에 `entity/` 만 실재). 확정 배치가 저장된 `confirm_at` 을 쓰고 실행 시각으로 재계산하지 않는 것은 Phase 7 이 이미 고정했다 | **Ruling 194** · `p7-review-t2-verdict.md` |
| 3 | **`DailyRoster` 의 중복 배제 범위가 시험으로 고정되지 않았다** — `null` 원소까지 거르도록 넓혀도 전건 통과한다. 다음 사람이 범위를 넓히거나 좁혀도 아무도 모른다 | `p7-review-t7-verdict.md` Minor |
| 4 | **`AcademyScopeRule.conditionClauseContainsAcademyId` 를 직접 겨냥한 시험이 부재** — 지금은 프로덕션 `@Query` 와 결함 주입으로 간접 검증만 된다 | `p7-review-t6-verdict.md` Minor(보류 판정) |
| 5 | **`route_changed` 알림의 "삭제된 매니저 제외" 조건이 도달 불가능하다** — MGR-04 가 배치된 매니저의 삭제를 원천 차단하고 `Manager.linkAccount` 가 계정 재연결을 막아, 그 필터를 지워도 전건 통과한다. 방향은 옳고 해롭지 않으나 **아무도 검사하지 않는 코드**다. ⚠ 보고서가 근거로 든 "`accountId` 재배정 위험" 은 **부정확**하니 함께 정정한다 | `p7-review-t3-verdict.md` Minor |

---

⚠ **Phase 8 에서 이월 (2026-08-31)** — 아래 4건. **착수 전 목표 표에 옮긴다.**

| # | 항목 | 근거 |
|:-:|---|---|
| 1 | **②구간 자동 거절의 운행 시작 쪽 배선** — T6 이 종결 서비스까지 만들었으나 `POST /runs/{runId}/start` 가 이 Phase 산출물이라 **호출 배선과 그 실측이 여기 몫**이다. 완료 조건에 **"운행 시작 직후 도달한 ②구간 승인이 `409 CHANGE_WINDOW_CLOSED` 로 거부된다"** 를 포함할 것 | **Ruling 196** |
| 2 | **매니저 → `Assignment` 범위 축소** — 매니저용 회차 조회(`API_SPEC §4.1`)가 이 Phase 산출물이라 첫 소비자가 여기서 생긴다 | **Ruling 117·199** |
| 3 | ⚠ **동시성 시험 3종이 부하와 결함을 구분하지 못한다** — 전체 실행에서 `TimeoutException` 으로 실패하고 단독 실행은 15초에 통과한다. 두 스레드의 **순서를 강제할 수단이 부재**해 "느린 호스트" 와 "잠금이 깨진 구현" 이 같은 실패로 나타난다. ⚠ **Phase 5·8 에 걸쳐 세 번 관측**됐다 | Phase 8 최종 실측 |
| 4 | **알림 미적재 단언의 구조적 한계** — 알림 발행이 롤백 유발 지점 뒤 **마지막 문장**이라 그 단언이 사실상 "그 지점까지 도달하지 못했다" 만 증명한다. 지금은 실사고로 이어지지 않으나 **알림 발행 뒤에 코드가 추가되면 공백이 된다** | T5 게이트 리뷰 Minor |

⚠ **목표 표를 쓸 때 항목마다 물어라 — "이 값이 흘러가는 갈래가 몇 개이고 그 전부를 검사하는가."** Phase 8 에서 **같은 형태의 사각지대가 다섯 번** 나왔다. 검사되던 쪽은 **저장 결과·호출 횟수·상태 코드**, 안 되던 쪽은 **계산 입력**과 **응답 본문의 계산된 값**이다. `grep -c 'jsonPath("$.data.<필드>' <시험파일>` 로 셀 수 있다.

### Phase 9 — 운행 실행 · 승하차 · 명단

⚠ **Phase 2 에서 이월 (2026-08-25, Ruling 117 · 2026-08-30 Ruling 199 로 이 절로 이동)** — **매니저 → `Assignment` 범위 축소**. Phase 2 Task 5 가 학원 격리는 완성했으나 역할별 추가 축소는 미착수. 사유는 재료 부재 — 판정 대상인 **회차 조회 경로**(`API_SPEC §4.1 GET /manager/runs`)가 **이 Phase 의 산출물**이다. 소비자를 모르는 채 시그니처를 정하면 추측이 된다. **회차 조회를 만들면서 함께 만든다.** ⚠ **2026-08-31 정정 (Ruling 206)** — 이 자리에 원래 **"`Assignment` 엔티티는 있으나 저장소가 없고"** 가 적혀 있었으나 **거짓이다.** `AssignmentRepository` 는 **Phase 5** 산출물(`3b43945`)이고 `findByRunIdAndManagerId`·`findAssignedManagers`·`findManagerRunWindows` 를 이미 갖췄다. T1 좌석이 발주문을 받고 **"만들라고 했는데 이미 있다"** 고 신고해 드러났다. ⚠ 이 note 는 Phase 8 절에 있었고 거기서 "회차 조회가 이 Phase 산출물" 이라고 적었으나 **Phase 8 의 신설 핸들러에 매니저 앱 경로가 0개**라 거짓이었다(Ruling 199).

⚠ **Phase 8 에서 이월 (2026-08-30, Ruling 196)** — **②구간 자동 거절 중 "운행 시작 시 동기 종결" 의 배선.** `ARCHITECTURE §9.6` 은 "운행 시작은 `POST /runs/{runId}/start` **트랜잭션 안에서 동기 종결** — 폴링만 두면 시작 직후 최대 30초 동안 승인이 통과" 라고 못 박는다. Phase 8 이 **폴링 경로와 종결 도메인 서비스까지** 만들었으나 `start` 핸들러가 **이 Phase 에서 처음 생기므로** 배선을 검사할 대상도 여기서 처음 생긴다. **완료 조건에 "운행 시작 직후 도달한 ②구간 승인이 `409 CHANGE_WINDOW_CLOSED` 로 거부된다" 를 포함할 것** — 등재하지 않으면 이 축은 영원히 검사되지 않는다.

✅ **착수 전 사용자 확인 완료 (2026-08-31).** 오픈 이슈 **V** 해소 — **MVP 는 카카오내비 단독**이고 상한은 **승하차지 4곳**(SDK `viaList` 최대 3 + 목적지 1)이다. 티맵은 앱 실행 스킴 규격이 미공개라 근거가 없어 **구현하지 않고 어댑터 자리만 둔다**. 함께 확정된 것 — **A**(등원 자동 하차 알림 **발송**) · **J**(되돌리기는 취소 동작까지만) · **X-01**(운행 시작 창 **±10분**) · **X-03**(동승자 부재 회차는 없다고 가정). 근거는 Ruling 201~205.

⚠ **Phase 7 에서 이월된 필수 항목 (2026-08-30, Ruling 148·192)** — **삭제된 매니저의 계정이 자기 회차·명단(매니저 앱 자료)에 닿는가.** Ruling 148 이 로그인 자체는 그대로 200 임을 확정했고(`API_SPEC §8.1` 코드 부재·`§5.2` 접근층 위임·`§6.7` 스태프 전용 근거), Ruling 192 로 재확인했다. 남는 것은 로그인 여부가 아니라 **매니저 앱이 삭제된 계정에게 회차·명단 데이터를 내주는가**이고, 그 엔드포인트가 이 Phase 에서 처음 생기므로 검사 대상도 이 Phase 에서 처음 생긴다. 완료 조건에 "삭제된 매니저 계정으로 매니저 앱 엔드포인트를 호출하면 거부된다" 를 포함할 것.

| 항목 | 내용 |
|---|---|
| **범위** | `RUN-01~07` · `BRD-01~06` · `RST-01~04` · `C-06` · `C-07` · `C-15` |
| **선행** | Phase 8 |
| **기능 ID** | RUN-01~07 · **RUN-08**(외부 내비 연동, 2026-08-26 신설) · BRD-01~06 · RST-01~04 (P0) |
| **참조** | `API_SPEC §4` · `USER_FLOWS §5`·`§6` · `TECH_DECISIONS §4`·`§5` |
| **산출물** | `run`·`boarding` 모듈 · 상태 전이 메서드 · 멱등 처리 · 운행 알림 |

**등하원 비대칭** (`C-07`) — 등원은 승하차지별 승차 처리 + 종료 시 전원 자동 하차, 하원은 시작 시 전원 자동 승차 + 승하차지별 하차 처리.

**종료 조작이 부재** (`C-15`) — 최종 지점 도착 처리가 종료를 겸함. 등원은 도착 처리 즉시 `finished` + 전원 자동 `alighted`, 하원은 **마지막 탑승자가 `alighted` 되는 순간** 서버가 자동 전이. 미하차 잔류 중에는 `moving` 유지 (`RUN-06`).

**권한이 역할로 갈림** — 승하차 상태 변경은 **동승자만**, 도착 처리·운행 시작은 **기사만** (`C-06` · `FEATURE_SPEC §6.1`).

**멱등** — 오프라인 큐 재전송분은 `client_key` UNIQUE 로 중복 무시하고 최초 결과를 200 으로 반환 (`API_SPEC §1.7`).

**응답 조립에서 필드 등급을 강제** — 매니저 앱 명단은 보호자 번호 마스킹, L3 필드는 권한 보유자만 (`FEATURE_SPEC §6.3` · `NFR-06`).

**외부 내비게이션 앱 연동** (`RUN-08` · `API_SPEC §4.16`, **2026-08-26 사용자 요청으로 신설**) — 확정 노선을 외부 내비로 넘길 **좌표열**을 반환한다. **서버는 딥링크를 만들지 않고** 순서·제외·자르기만 판정하며, 조립은 앱이 한다(근거는 `§4.16`).

**MVP 는 카카오내비 단독** (2026-08-31 사용자 확정) — 상한 **4곳**. 공급자는 **포트 뒤에 둔다**(`§7` 규칙 12 교체 축): `NavProvider` 인터페이스 + `KakaoNavProvider` 구현 1개. 티맵 전환은 구현체 추가 + 설정 변경으로 닫힌다. **어느 공급자인지는 요청이 고르지 않고 서버 설정이 정하며 응답이 알린다**(Ruling 201) — 요청 파라미터로 받으면 공급자를 바꿀 때 앱을 새로 배포해야 하는데, 정작 갈리는 값(상한)은 서버만 아는 것이라 앱이 고를 근거가 없다.

이 Phase 에 둔 이유 — 필요한 것이 **확정 노선(Phase 7)과 도착 포인터(`run_stop.arrived_at`, 이 Phase 의 `RUN-04`)뿐**이고 실시간 위치(Phase 10)를 쓰지 않는다. `§4.3`(`LOC-03`, Phase 10)이 이미 `next_stop.lat`·`lng` 를 "외부 내비 콜백용" 으로 반환하나 그것은 **다음 1개 지점**이라 "해당 노선으로 안내" 를 만족하지 못한다 — 그래서 전용 엔드포인트를 별도로 둔다. **`§4.3` 의 그 필드는 남긴다**(운행 화면이 이미 쓰는 계약이다).

**되돌리기는 취소 동작까지만** (`BRD-05` · 오픈 이슈 **J** 부분 해소, 2026-08-31) — 요청을 받아 상태를 직전 값으로 되돌리고 `rider_status_history` 에 적재하는 데까지 만든다. **허용 범위(언제까지·몇 번까지)와 기발송 알림 정정은 미결**이라 ⚠ **제한을 코드 상수로 추측해 박지 않는다.** 지금 확정된 상태는 "제한을 두지 않는다" 이고, 정책이 정해지면 그때 조인다.

**등원 자동 하차도 알림을 낸다** (오픈 이슈 **A** 해소, 2026-08-31) — 등원 최종 도착 처리로 전원이 자동 `alighted` 될 때 학부모 알림을 발송한다. 알림 과다보다 **도착 사실이 전달되지 않는 쪽**의 손실이 크다는 판정이다.

**동승자가 없는 회차는 없다고 가정한다** (오픈 이슈 **X-03** 해소, 2026-08-31) — 기사에게 기록 권한을 임시로 주는 안은 폐기. 채택했으면 `C-06`(승하차는 동승자 전용)이 회차마다 갈리는 조건부 규칙이 되어 **인가 판정이 데이터에 의존**하게 된다.

**완료 조건**
- 운행 시작이 출발 시각 ±10분 창에서만 성공, 창 밖에서는 거절
- 시작과 동시에 노선 잠금 + `run_started` 알림 3대상 발송
- 동승자 토큰으로 승하차 상태 변경 성공, **기사 토큰으로는 403**
- 기사 토큰으로 도착 처리 성공, **동승자 토큰으로는 403**
- `absent` 학생이 매니저 앱 명단에서 **행 제외**되고 집계에는 "미등원 N명" 유지, 관계자 웹에서는 계속 표시
- `no_show` 처리 시 즉시 학부모 알림 + 관계자 통지 + 케이스 생성
- 잔여 탑승자 0명이 된 승하차지가 `skipped` 로 전환 (순번 유지)
- 등원 최종 도착 처리 → 즉시 `finished` + 전원 `alighted` + **자동 하차분에도 학부모 알림 발송** (`A` 해소)
- 하원 마지막 승하차지 도착 후 미하차 학생이 남으면 `moving` 유지, 마지막 하차 시 `finished` 자동 전이
- 같은 `client_key` 재전송 시 상태가 두 번 바뀌지 않고 200 반환
- **되돌리기 요청이 상태를 직전 값으로 실제로 되돌리고** `rider_status_history` 에 이력 적재 — 응답의 `status` 가 되돌린 값이고, 재조회해도 그 값이다 (`J` 부분 해소)
- 매니저 앱 명단 응답에 보호자 번호가 마스킹, 주소 원문·좌표 미포함
- **(Phase 7 이월, Ruling 148·192)** 삭제된 매니저 계정으로 매니저 앱 엔드포인트(회차·명단 조회)를 호출하면 거부된다 — 로그인 자체는 여전히 200
- **(Phase 8 이월, Ruling 196)** **운행 시작 직후** 도달한 ②구간 승인이 `409 CHANGE_WINDOW_CLOSED` 로 거부된다 — 폴링을 돌리지 않은 상태에서도 거부되어야 한다(`start` 트랜잭션 안에서 동기 종결)
- **(Phase 2 이월, Ruling 117·199)** 매니저 토큰이 **자기에게 배치된 회차만** 조회한다 — 같은 학원의 다른 회차는 `403`
- **(RUN-08)** `GET /runs/{runId}/navigation?scope=next` 가 **다음 미도착 승하차지**의 좌표를 반환하고, `arrived_at` 이 찍힌 지점과 `skipped` 지점은 **결과에 등장하지 않는다**. 응답 `provider` 는 서버 설정이 정한 값(`kakao`)이다
- **(RUN-08)** 남은 승하차지가 0개면 `409 NAV_NO_REMAINING_STOP` — 빈 배열을 200 으로 반환하지 않는다
- **(RUN-08)** `scope=remaining` 에서 남은 지점이 **4곳**을 넘으면 4곳으로 **잘라서 반환하고 `truncated=true`** 이며 `total_remaining_stops` 가 자르기 **전** 개수를 담는다 — 4곳 이하면 `truncated=false`
- **(RUN-08 · X-01)** **`confirmed` 회차(출발 30분 전, 운행 시작 전)에서도 200** 이고 `origin` 에 출발지 좌표가 담긴다 — 기사가 확정 시점부터 노선을 미리 볼 수단이다. `moving` 이면 `origin` 이 비어 있다
- **(RUN-08)** 공급자 상한 **4** 가 `KakaoNavProvider` **한 곳에만** 존재한다 — 조립·자르기 코드에 숫자가 흩어져 있지 않다
- **(RUN-08)** 확정 전 `idle` 회차 요청은 `409 RUN_NOT_CONFIRMED`, 배치되지 않은 회차는 `403`

---

### Phase 10 — 위치 · 실시간 전달 · 근접 알림

⚠ **Phase 2 에서 이월된 필수 항목 (2026-08-25, Ruling 87)** — **계정 상태 게이트가 STOMP 축을 덮지 않는다.** `StompAuthChannelInterceptor` 가 CONNECT 시 상태 판정 없이 `AuthUser` 를 세션에 심고 `SecurityConfig` 가 `/ws/**` 를 `permitAll` 이라, `pending` 토큰으로 **세션 수립·구독이 가능**하다. Phase 2 의 게이트는 `HandlerInterceptor` 라 STOMP 프레임 경로에 부재하다. 지금 위험이 잠재적인 이유는 **구독할 채널과 데이터가 이 Phase 의 산출물**이기 때문이며, **채널을 만드는 이 Phase 가 반드시 닫는다.** 완료 조건에 "`pending` 토큰의 CONNECT·SUBSCRIBE 가 거부된다" 를 포함할 것.

⚠ **Phase 8 에서 이월 (2026-08-30, Ruling 195)** — **`approval_requested` 의 WebSocket 방송.** 이 이름은 정본 두 곳에 있고 **경로가 다르다** — `API_SPEC §9.7` 은 관계자 **푸시 알림**이고 `§7`·`§7.1` 은 `/ws/academy/{id}/live` **채널 방송**이다. Phase 8 이 푸시 적재까지 만들었으나 **채널 4종이 이 Phase 의 산출물**이라 방송은 여기서 처음 검사 가능하다(당시 STOMP 엔드포인트는 `/ws/location` 하나였다). 완료 조건 "채널 4종 방송 이벤트가 `API_SPEC §7.1` 페이로드와 일치" 에 **`approval_requested` 를 명시적으로 포함**할 것 — 목록에 이름이 없으면 빠진 것을 아무도 알아채지 못한다.

⚠ **Phase 2 에서 이월 (2026-08-25, Ruling 121)** — `/topic/tenant/{tenantId}` 경로가 **옛 N:M 멤버십 어휘**를 그대로 쓴다. 코드·스키마·사양은 전부 `academy` 로 정리됐고 이 경로만 남았다. 지금 바꾸지 않은 이유는 **경로가 클라이언트 계약**이라 소비자 없이 바꾸면 검증할 수단이 부재하기 때문이다. **구독 채널을 실제로 만드는 이 Phase 가 클라이언트 계약을 처음 쓰는 시점**이라 그때 함께 정리한다.

⚠ **Phase 9 에서 이월 (2026-08-31)** — 5건 + 리뷰 미부착 2건. ⚠ **아래의 "부재 · 미검증 · 미확인" 은 Phase 9 좌석·리뷰의 자기 신고이며 재계수하지 않았다. 착수 전에 직접 세라**(전역 규칙 `phase-goal-loop.md §6.3` — 낡은 "없음" 은 판정을 틀리게 하는 데 그치지 않고 **이미 있는 것을 새로 만들게** 한다).

| # | 이월 | 확인 방법 |
|:-:|---|---|
| 1 | **동시성 시험 `TimeoutException`** — `Bus`·`RunGeneration`·`ChangeRequestAutoRejection` `*ConcurrencyTest`. Phase 8 이월분이 계속 이월. **부하 의존으로 분류**(단독 재실행 22초 전건 통과 · `too many clients` 0 · postgres 재시작 0 → 0). 근본 원인은 **순서를 정할 수단이 부재** | 전체 실행에서 실패, 단독 실행에서 통과인지 대조 |
| 2 | T4 — `scope` 에 **잘못된 값**이 올 때의 처리 미확인 | `NavigationControllerTest` 의 시험 이름을 세어 잘못된 `scope` 갈래가 실재하는지 확인 |
| 3 | T1 — 두 명단(`§4.2` 매니저 앱 · `§5.4` 관계자 웹)이 **공유하는 헬퍼를 깨뜨렸을 때 양쪽이 동시에 무너지는지** 미검증 | 그 헬퍼에 변형을 심어 두 시험이 함께 실패하는지 실측 |
| 4 | T2 — 목표 2(**시작 시 노선 잠금**)를 명시적으로 검사하는 시험 부재. 기존 커버리지로 방어된다는 것이 좌석 판단(Minor) | 잠금을 푸는 변형을 심어 무엇이 실패하는지 실측 |
| 5 | **T2 수정 라운드 3 에 재리뷰 미부착**(조율자 판단) | 그 결정 자체가 판정 대상 |

⚠ **리뷰를 건너뛴 편집 1건** — `968bc9f`(내비 정차 조회에 학원 조건을 부모 조인으로 부착)는 **조율자 직접 편집**이라 게이트 리뷰가 붙지 않았다(전역 규칙 `parallel-agents-git.md §10`). **다음 리뷰의 판정 대상.** 음성 대조 2건은 이미 실측 — ⓐ되돌리면 `AcademyScopeRepositoryConventionTest` `failures=1` ⓑ조건을 `<>` 로 뒤집으면 행동 시험 6건이 실패하나 **규약 시험은 통과**(텍스트 판정 한계, `.claude/PROJECT_NOTES.md` 등재).

| 항목 | 내용 |
|---|---|
| **범위** | `LOC-01~03` · `NTF-04` · `ARCHITECTURE §10` · WebSocket 채널 4종 |
| **선행** | Phase 9 |
| **기능 ID** | LOC-01·02·03 · NTF-04 (P0/P1) |
| **참조** | `API_SPEC §7` · `§4.12` · `ARCHITECTURE §10` |
| **산출물** | `location` 모듈 · Redis 최신 좌표 · WebSocket 팬아웃 · 근접 판정 스케줄러 |

**"곧 도착합니다"의 트리거는 서버의 위치 판정** — 기사의 도착 처리가 아님 (`NTF-04` · `M-11`). 근접 판정은 **실제 좌표 기반**이며 계획 ETA 근사로 판정하면 지연 운행에서 어긋남.

**채널을 역할별로 가른 이유** — 구독 권한 검증 한 번으로 노출 범위가 결정됨. ⚠ **학부모·학생 채널에 `rider_changed` 를 싣지 않는다** — payload 에 타 학생 이름과 탑승 인원이 들어 있어 `C-08` · `API_SPEC §1.12` 에 어긋남.

**구독 시점에 인가 검증** — 연결만 인증하고 구독 경로를 검사하지 않으면 토큰 보유자가 남의 채널을 구독. `ARCHITECTURE §5.1` 3층을 구독 핸들러에서도 통과시킬 것.

⚠ **착수 전 판정 (2026-08-31, Ruling 207~211)** — 사용자 확정 3건 + 조율자 판정 2건.

| Ruling | 내용 | 정본 반영처 |
|:-:|---|---|
| **207** | **근접 판정 = 다음 미도착 승하차지까지 직선거리 300m 이내 최초 1회.** 정본 6곳이 *"실제 좌표 기반"* 만 적고 **수치가 부재**했다 — 완료 조건을 실행 가능한 형태로 만들 수단이 없었다 | `API_SPEC §4.12` |
| **208** | **신호 유실 판정 = 마지막 수신 후 2분.** 정본이 문자 그대로 `N분` 이었고, 근처의 두 값은 층이 달랐다(관제 경고 2분 · 판정 주기 10초). **관제 경고와 같은 값으로 통일** | `API_SPEC §3.11` |
| **209** | **`API_SPEC §7` 채널 4종은 STOMP 구독 목적지이고 연결 엔드포인트는 `/ws/location` 단일.** 옛 경로 `/topic/tenant/{id}/**` 는 이 표로 대체되어 소멸(Ruling 121 해소) | `API_SPEC §7` |
| **210** | ~~근접 판정 스케줄러의 중복 실행 방지는 조건부 UPDATE 선점으로 한다~~ 🔴 **폐기 — Ruling 212 로 대체.** 정본이 명시적으로 제외한 예외(확정 배치)를 근거로 삼아 **정본이 이름으로 지목한 대상**에 그 예외를 적용한 오판 | — |
| **211** | ⚠ **완료 조건이 범위보다 좁았다** — 범위는 `LOC-01~03` · `NTF-04` 인데 기존 7항 어디에도 **LOC-02 조회 · LOC-03 학부모 노선 · NTF-04 발송**을 검사하는 문면이 부재했다. 아래에서 3항을 신설한다. ⚠ 이것이 `phase-goal-loop §6.2` 가 경고한 형태다 — **총량이 아니라 항목을 대조해야 드러난다** | 이 절 |
| **212** | **Ruling 210 폐기 (2026-08-31 사용자 확정 · 안 "가").** `TECH_DECISIONS §3.2` 는 제목이 *"ShedLock — 지금 넣는다"* 이고 본문이 **근접 알림 스케줄러를 이름으로 지목**한다. 210 이 근거로 든 *"저장소에 락 수단이 부재"* 는 **코드에만 참**이고 정본은 이미 결정 상태였다. ⚠ **알림 중복 발송에는 구멍이 부재** — 조건부 UPDATE + `dedup_key` 로 T3 가 차단하고 결함을 심어 확인까지 마쳤다. 빠진 것은 **인스턴스가 같은 판정을 중복 수행**하는 것을 막는 층이다. ShedLock 은 **새 의존성 + 테이블 + 전 스케줄러 배선**이라 목표 표에 없던 작업이므로 `phase-goal-loop §1`(*목표는 착수 후 늘리지 않는다 — 늘려야 하면 별도 단위로 뺀다*)에 따라 **Phase 11 로 뺀다.** Phase 10 은 현 구현으로 마감 | `TECH_DECISIONS §3.2` 유지 · Phase 11 |

⚠ **이월 항목의 "부재" 주장 재계수 결과 (2026-08-31, 조율자 직접 계수)** — 2건이 낡아 있었다.

| 낡은 주장 | 실측 |
|---|---|
| 산출물 `location` 모듈 | **`RunPosition` 엔티티는 이미 존재**(Phase 1 산출물). 서비스·저장소·컨트롤러만 부재. **엔티티를 새로 만들면 중복이다** |
| 산출물 "Redis 최신 좌표" | **Redis 는 이미 배선**(`RedisConfig` · `spring-boot-starter-data-redis` · yml 3프로파일). 남은 것은 키 설계뿐 |

**완료 조건**
- `moving` 상태에서만 위치 송신이 **수락**되고 (`204`)
- 다른 상태의 위치 송신은 **거절** (`409 RUN_NOT_MOVING`)
- Redis 최신 좌표 **갱신**과 `run_position` 이력 **적재**가 함께 동작
- 채널 4종 방송 이벤트가 `API_SPEC §7.1` 페이로드와 일치 — **`approval_requested` 를 포함**(Ruling 195)
- **학부모·학생 채널 구독 시 `rider_changed` 미수신**
- **관제 채널만 `eta` 포함**
- 권한 밖 채널 구독 시 연결 거부(`4403`)
- **`pending` 토큰의 CONNECT·SUBSCRIBE 가 거부**(Ruling 87 이월 — 계정 상태 게이트가 STOMP 축을 덮는다)
- 신호 유실 **2분** 후 `last_seen_at` 으로 전환 (Ruling 208)
- 승하차 상태 변경이 관계자 채널에 5초 이내 반영 (`NFR-02`)
- **`GET /students/{id}/bus-position` 이 LOC-02 응답을 반환**(Ruling 211 신설 — 미구현 확인)
- **`GET /students/{id}/route` 가 승차지 이전 2개 · 승차지 · 하차지만 반환**(Ruling 211 신설 — `P-08` 표시 범위)
- **근접 300m 진입 시 "곧 도착합니다" 가 1회 발송되고 재진입에 재발송 부재**(Ruling 207·211 신설)
- 전체 테스트 묶음 단독 실행에서 실패 0 (동시성 시험의 부하 의존 실패는 별도 분류)

---

### Phase 11 — 예외 · 비상 알림

| 항목 | 내용 |
|---|---|
| **범위** | `EXC-01~04` · `M-13`~`M-15` · `A-16` · `O-07` · `C-17` · **`A-17` 학원 설정**(미승차 대기 시간 — EXC-01 이 이 값을 읽음) |
| **선행** | Phase 10 |
| **기능 ID** | EXC-01(P0) · **EXC-04(P0)** · EXC-02·03(P1) |
| **참조** | `API_SPEC §4.8`·`§4.13`·`§4.14`·`§5.16`·`§6.11` · `USER_FLOWS UF-E-03`·`UF-X-08` |
| **산출물** | `exception` 모듈 · 미승차 에스컬레이션 스케줄러 · 비상 알림 발신·확인 · **ShedLock 도입**(Ruling 212 — 의존성 · `shedlock` 테이블 · 스케줄러 전건 배선) |

**비상 알림이 P0** (`PRD §7.1`). 승하차와 달리 **역할을 제한하지 않는 유일한 쓰기 경로** — 기사·동승자 둘 다 발신 가능하고 안전 사안이라 제한 부재 (`ARCHITECTURE §3.3`).

**비상 알림은 아웃박스를 거치지 않고 즉시 발송 + 팝업** (`ARCHITECTURE §11`). 발송 실패 시 워커가 회수하되 **재시도 간격을 다른 알림보다 짧게**. 수신 측 on/off 항목 자체가 부재 (`C-17`). **학부모·학생은 수신 대상 밖**.

**미승차 대기 임계값은 학원별 설정** — `academy_setting` 에서 읽고 코드 상수로 두지 않음 (`EXC-01` · `TECH_DECISIONS §12.2`).

**완료 조건**
- `no_show` 처리 후 대기 시간이 경과하고 무응답이면 관계자에 보고 (`no_show_escalated`)
- 학원별로 다른 대기 값이 각각 적용됨 — 코드 상수가 아님을 시드 2학원으로 검증
- 연락 시도 이력이 `no_show_contact` 에 적재
- 기사·동승자 **둘 다** 비상 발신 성공
- 비상 알림이 관계자·메인 관리자에 **동시** 수신되고 알림 설정과 무관하게 발송
- 학부모·학생 계정은 비상 알림 미수신
- 발신 시점 위치·회차·호차·연락처가 자동 첨부
- 발신 1분 이내 취소 성공, 1분 초과 시 거절. 취소 사실도 통지되고 **이력은 존치**
- 관계자 `[확인]` 응답이 발신자 앱에 반영되고 처리 이력 적재
- 관계자 미확인 상태가 메인 관리자 콘솔에 경과 시간과 함께 노출
- **인스턴스 2개가 같은 스케줄러 주기를 돌 때 판정이 1회만 수행됨** — 락 미획득 쪽은 수행 없이 건너뜀 (Ruling 212 · `TECH_DECISIONS §3.2`)
- **락 보유 인스턴스가 죽어도 다음 주기에 다른 인스턴스가 이어받음** — 락이 영구 점유되지 않음
- **`POST /runs/{runId}/reports` 로 보호자 부재·현장 상황 보고 접수** → `201` + 관계자 즉시 통지. `type=guardian_absent` 면 `rider_id` 필수 (`§4.13` · Ruling 213)
- **`GET /staff/reports`·`/{id}` 조회의 `type`·`date`·`run_id` 필터 동작** (`§5.20` · Ruling 213)

⚠ **착수 전 판정 (2026-09-01, Ruling 213)** — **완료 조건이 범위보다 좁았다.** 범위에 `EXC-02`·`EXC-03`·`M-14`(예외 상황 보고)가 있는데 기존 10항 어디에도 **`§4.13` 쓰기·`§5.20` 읽기를 검사하는 문면이 부재**했다. 위 2항을 신설한다. ⚠ **Phase 10 의 Ruling 211 과 같은 형태다** — 총량이 아니라 **범위 열과 완료 조건을 항목 단위로 마주 놓아야** 빈칸이 보인다.

⚠ **착수 전 판정 (2026-09-01, Ruling 214) — 좌석 분할을 테이블 소유권 기준으로 바꾼다.** 옛 분할은 T2 에 비상 발신·취소를, T3 에 확인 응답·관제 노출을 줬는데 **`ack` 는 읽기가 아니라 쓰기**다. 그대로 가면 두 좌석이 **같은 `EmergencyAlert` 엔티티 파일에 상태 전이 메서드를 각각 더하고 `EmergencyAlertRepository` 를 둘이 신설**한다. **Phase 10 의 Critical(Redis 와이어 포맷)이 이 형태였고**, 그때의 해소책인 "계약을 명시하고 조율자가 전파" 로는 이번 경계가 막히지 않는다 — 어긋나는 것이 **값이 아니라 파일**이기 때문이다. ⇒ **한 테이블을 쓰기하는 좌석은 하나로 한다.** T2 가 `emergency_alert` 전량(목표 5~11), T3 이 `exception_report` 전량(목표 15·16), T1 이 `no_show_*`·`academy_setting`, T4 가 `shedlock`. **목표는 16항 그대로이고 배정만 옮겼다** — `phase-goal-loop §1` 은 목표 증감을 막는 규칙이지 작업 분배를 고정하는 규칙이 아니다. 남은 좌석 간 결합 2건(T1 스케줄러의 ShedLock 부착 · T3 의 알림 종류 신설 여부)은 **발주문에 명시하고 조율자가 병합 후 수정 라운드로 닫는다.**

⚠ **진행 중 판정 (2026-09-01, Ruling 215) — `§4.13` 의 관계자 통지에 쓸 알림 종류가 정본에 부재하다.** T3 좌석이 착수 직후 신고했고 조율자가 정본을 직접 열어 확인했다 — `API_SPEC §9.7` 은 **18종**이고 예외 보고에 대응하는 값이 없다. `V1__init_schema.sql:710` 의 `ck_notification_log_type` 이 그 18종을 강제하며, `§7.1` 의 WS 이벤트 8종에도 부재이고 **그 절이 "WebSocket 은 알림 발송 경로가 아님" 을 명시**해 우회로도 없다. **이것은 정본 내부의 어긋남이다** — `§4.13` 이 *"관계자에게 즉시 통지"* 를 요구하는데 `§9.7` 표가 그 값을 빠뜨렸다. ⇒ **기능 요건(`§4.13` · EXC-02·EXC-03 · M-14)이 이기고 `§9.7` 을 고친다.** 값은 **`exception_reported`**(기존 `approval_requested`·`no_show_escalated` 와 같은 명명). ⚠ **보고의 `report_type` 4종을 알림 종류로 쪼개지 않는다** — `§9.7` 은 트리거 하나당 값 하나이고 보고 종류는 페이로드 필드다. **마이그레이션 소유를 나눈다** — ~~`db/migration/` 전체가 T4~~ → **`V4`(shedlock)는 T4 · `V5`(CHECK 재작성)는 T3**. 두 파일은 대상 테이블이 달라 충돌이 부재하다. `docs/API_SPEC.md §9.7` 표 정정도 T3 에 카브아웃했다(다른 좌석이 `docs/` 를 안 건드린다). ⚠ **이 어긋남을 잡아 준 시험이 하나도 없었다** — `NotificationType` 열거값과 DB CHECK 는 서로 독립으로 낡을 수 있고 어긋나면 **그 알림을 처음 발송하는 순간 운영에서 제약 위반으로 터진다.** T3 에 **열거값 전부가 실제 CHECK 를 통과하는지 검사하는 단언**을 요구했다. ⚠ **`handled`·`handled_at`·`student_name` 은 `exception_report` 에 컬럼이 부재하나 마이그레이션 대상이 아니다** — `§4.13` 이 *"MVP 범위는 보고까지, 인계 완료 판정은 2단계(PRD §10 E-05)"* 를 명시하므로 `handled`=`false`·`handled_at`=`null` 고정, `student_name` 은 `run_rider_id → student` 조인으로 조립한다.

⚠ **재계수 결과 (2026-09-01, 조율자 직접 계수) — 이 Phase 의 산출물 상당수가 이미 있다.**

| 층 | 실재 | 위치 |
|---|---|---|
| 테이블 **5종** | `academy_setting` · `no_show_case` · `no_show_contact` · `emergency_alert` · `exception_report` | `V1__init_schema.sql`(Phase 1 산출물) |
| 엔티티 **10개** | `exception/entity/` 9개 + `academy/entity/AcademySetting` | — |
| 저장소 **1개** | 🔴 **`NoShowCaseRepository` 가 이미 있다** | `exception/repository/` |

**부재한 것은 서비스·컨트롤러·DTO 와 나머지 저장소 3개**(`EmergencyAlert`·`NoShowContact`·`ExceptionReport`)**, `AcademySetting` 의 저장소·서비스·컨트롤러.** 신설 마이그레이션은 **`shedlock` 하나뿐이며 번호는 `V4`**(`V1`·`V3` + `migration-local/V2` 가 이미 있어 겹치면 `local` 프로파일 기동이 거부된다). ⚠ **낡은 "없음" 을 믿고 이미 있는 것을 새로 만들 뻔한 전례가 있다**(Ruling 206).

**신설 핸들러 11개 예상 (78 → 89)** — `§4.8` 1 · `§4.13` 1 · `§4.14` 2 · `§5.16` 2 · `§5.20` 2 · `§5.21` 2 · `§6.11` 1. ⚠ **계정 상태 게이트 거부측 목록에도 11줄이 늘어야 한다** — Phase 9·10 에서 연속으로 누락된 자리이고 **개수 단언이 유일한 탐지 수단**이다.

목표 표는 `docs/archive/sdd/IMPLEMENTATION_PLAN/p11-goal-table.md`(완료 조건 **16항**).

#### Phase 10 이월 8건 (2026-09-01 등재)

⚠ **각 항목의 "부재·미검증" 은 좌석·리뷰의 신고이며 재계수하지 않았다.** 착수 시 무엇을 세어 확인할지 함께 적는다.

| # | 항목 | 무엇을 세어 확인하나 |
|:-:|---|---|
| **①** | **ShedLock 도입**(Ruling 212) — 이 Phase 의 산출물이다. 인스턴스가 같은 판정을 **중복 수행**하는 것을 막는 층 | 위 완료 조건 2항. `TECH_DECISIONS §3.2` 가 의존성 좌표까지 명시 |
| **②** | `ProximityNotificationScheduler` **배치 계층 무시험**(R3 지적) — `judgeOne` 판정 로직은 검사하나 **부르는 쪽**(`judgeMovingRuns()` 의 MOVING 필터 · `BATCH_SIZE` · 회차별 예외 격리 `judgeSafely`)이 공백 | `find src/test -name 'ProximityNotificationSchedulerTest*'` 계수 **0** 확인. 본보기는 `RunConfirmationSchedulerTest`(`scheduler.confirmDueRuns()` 직접 호출) |
| **③** | **`speed`·`heading` 조용한 유실**(R1 Minor) — DTO 검증·엔티티 컬럼은 있는데 `RunPosition.onReceive()` 가 값을 세팅하지 않아 **항상 버려진다**. `API_SPEC §4.12` 상 선택 필드라 사양 위반은 아니나 **값이 와도 저장되지 않는 것**이 문제 | `onReceive` 본문에서 두 필드 계수 **0** 확인. ⚠ 고치면 **그 값이 실제로 저장되는지 검사하는 단언을 반드시 함께** 만든다 — 지금 못 잡은 이유가 그 단언 부재다 |
| **④** | **`eta` 값이 항상 `null`** — 목표 6 은 **키의 유무**를 요구해 완료 조건은 충족하나 값은 미계산. 계산 인프라(`§5.18` MON-07)가 **Phase 13 소속**이라 선점하지 않았다 | `PositionBroadcastListener` 의 `ControlPayload` 에서 `eta` 가 상수 `null` 인지 확인. **Phase 13 이 채울 자리** |
| **⑤** | **`RiderNoShowEvent` 경로의 `rider_changed` 방송 여부**(R2 판단 보류) — `RiderChangedBroadcastListener` 자바독이 *"미승차로 잔여 0명 도달 경로는 다루지 않았다"* 고 스스로 적었다. **목표 표 문면에 `no_show` 트리거 언급이 부재**해 범위 판정 불가 | `API_SPEC §7.1` 의 `rider_changed` 트리거 표에 `no_show` 가 있는지 직접 대조. Phase 11 이 미승차 에스컬레이션을 만드는 자리라 **여기서 판정하는 것이 자연스럽다** |
| **⑥** | **`RunCompletionBoundaryTest` 의 `no_show` 로스터가 실제 판정에 미도달**(구현자 자기 신고, 확신 80%) — `RunCompletionService` 가 그 시험에서 대역이라 *"완료 판정이 `no_show` 를 올바르게 제외하는지"* 는 범위 밖 | `RunCompletionService` 자체 시험이 `no_show` 를 제외하는지 검사하는지 확인 |
| **⑦** | **`RedisConfig` 완전 삭제 판단**(구현자 자기 신고) — 지시에 없던 판단이고 *"과하다, `@Deprecated` 만"* 이 나올 수 있다고 스스로 적었다. **게이트 리뷰가 이 커밋에 붙지 않았다** | `grep -rn 'RedisConfig' src/main src/test` 가 **자바독 텍스트만** 나오는지(실제 `import` 0건) 확인 |
| **⑧** | **동시성 시험의 부하 의존**(Phase 8 → 9 → 10 계속 이월) — 전체 실행에서 `TimeoutException` 이 나고 단독 재실행에서 통과한다. **순서를 정할 수단이 부재**한 것이 원인 | 매 Phase 최종 실측에서 실패 클래스가 `*ConcurrencyTest` 뿐인지, 단독 재실행이 전건 통과인지 |

⚠ **Phase 9 이월 ⑤(T2 수정 라운드 3 재리뷰 미부착)는 아직 미해소다** — Phase 10 리뷰 범위 밖이었다. 다음 리뷰의 판정 대상으로 계속 남긴다.


---

### Phase 12 — 알림 설정 · 목록 · 수신 확인 · 로그

| 항목 | 내용 |
|---|---|
| **범위** | `NTF-07~12` · `P-09` · `S-03` · `A-13` |
| **선행** | Phase 11 |
| **기능 ID** | NTF-07~11 (P1) · **NTF-12 푸시 단말 등록 (P0 신설분)** |
| **참조** | `API_SPEC §2.11`·`§3.12`~`§3.14`·`§5.17` · `USER_FLOWS §10` |
| **산출물** | 알림 목록·읽음 처리 · 수신 설정 · 관계자 로그 전수 조회 · **`device_token` 등록·해지** |

⚠ **`NTF-12`(푸시 단말 등록)는 이 Phase 가 아니라 Phase 2(인증) 와 함께 나가야 한다** — `device_token` 이 없으면 **앞선 전 Phase 의 알림이 인앱 목록에만 남고 푸시가 전부 미발송**된다. 범위에 함께 적되 구현 시점은 인증 Phase 로 당길 것.

**off 는 푸시만 차단하고 레코드는 항상 생성** (`ARCHITECTURE §11`). 사용자가 끄는 것은 단말 푸시일 뿐 통지 자체가 아니며, 미수신 주장이 나와도 발송 사실 확인이 가능해야 함.

**지연 알림과 비상 알림은 설정 항목 자체가 부재** (`NTF-07` · `C-17`).

**완료 조건**
- 알림 목록 조회·미읽음 배지·읽음 처리
- 보관 기간 초과분이 목록에서 제외
- 설정 off 상태에서 푸시 미발송이나 `notification_log` 행은 생성
- 지연·비상 알림에 설정 항목이 노출되지 않음
- 관계자 로그 전수 조회 — 시각·호차·대상·종류·확인 여부
- 중요 통지의 수신 확인 추적 + 미확인 배지
- 알림 문구에 **자녀 이름이 포함** (`P-02`)

✅ **착수 전 판정 (2026-09-02) — 목표 표 14항을 `docs/archive/sdd/IMPLEMENTATION_PLAN/p12-goal-table.md` 에 고정했다.**

| # | 판정 | 근거 | 결론 |
|:-:|---|---|---|
| **216** | ⚠ **완료 조건이 범위보다 좁았다.** 범위(`NTF-07~12` · `P-09` · `A-13`)에 있는데 기존 7항 어디에도 문면이 부재한 것 4개 — **읽음 처리의 인가·부재 상태 코드**(`§3.13` 의 `403`·`404 NOTIFICATION_NOT_FOUND`) · **`popup` 노출**(`§3.12` · `NTF-09`) · **설정 대상 밖 항목의 `422`**(`§3.14`) · **`unacked_count` 미확인 배지**(`§5.17` · `NTF-10`) | `API_SPEC §3.12`·`§3.13`·`§3.14`·`§5.17` | **4항 신설 → 최종 12항.** ⚠ **Phase 10 Ruling 211 · Phase 11 Ruling 213 과 같은 형태다** — 총량이 아니라 **범위 열과 완료 조건을 항목 단위로 마주 놓아야** 빈칸이 보인다 |
| **217** | 🔴 **`NTF-12`(푸시 단말 등록·해지)는 이미 완료돼 있다.** 이 절이 *"구현 시점을 인증 Phase 로 당길 것"* 으로 적은 것은 **이미 이행된 상태**다 — `DeviceController`(`POST /me/devices` · `DELETE /me/devices/{token}`) · `DeviceCommandService` · `DeviceControllerTest` 실재. 계정 상태 게이트도 처리됐다(`@AllowedWhenPending` — 대기 계정도 승인 결과 알림을 받을 단말을 등록해야 한다) | 조율자 직접 계수 | **목표에서 제외하고 검증만 한다.** ⚠ **Ruling 206·213 과 같은 형태** — 낡은 "미착수" 서술을 믿으면 **이미 있는 것을 새로 만든다.** 함께 실측한 것 — 테이블 4종·엔티티 6개·저장소 2개·발송 기반 5개·문구 조립기 16개가 전부 실재하고 **부재한 것은 `NotificationSettingRepository` 하나와 조회·명령 서비스·컨트롤러뿐**이다 |
| **218** | 🔴 **`NTF-06`(지연 알림 전송)이 어느 Phase 범위에도 없고 미구현이다.** `API_SPEC §4.9 POST /runs/{runId}/delay` · `M-05`(동승자 전용) · `DELAY_NOTIFY` 권한이 정본에 있는데 **계획의 어느 Phase 범위 열에도 등장하지 않는다.** 코드에는 `NotificationType.DELAY` 와 권한 상수만 있고 컨트롤러·서비스가 부재 | 조율자 직접 계수 · `ERD:1117` | **이 Phase 에 흡수하지 않는다** — 오픈 이슈 **`I`**(지연 알림 중복·누적 규칙)가 미결이고 `ERD:1117` 이 *"규칙 확정 시 전용 테이블 필요"* 로 **스키마 판단을 딸고** 있어 목표로 고정할 수 없다. ⇒ **소유 미배정 항목으로 등재하고 사용자 판정을 받는다.** ⚠ **`I` 가 막는 것은 `NTF-06` 이고 이 Phase 범위(`NTF-07~12`)를 막지 않는다** — 진행 표 비고가 이것을 구별하지 않아 이 Phase 가 막힌 것처럼 읽혔다 |

| **219** | ✅ **오픈 이슈 `J` 잔여 ② 확정 (2026-09-02 사용자 판정) — 이미 나간 알림은 정정하지 않고 새 알림을 발행한다.** 예 — 승차 알림이 나간 뒤 승차를 취소하면 **승차 알림은 그대로 두고 "승차 취소" 알림을 새로 보낸다.** 🔴 **이 판정을 반영하려고 코드를 열어 실제 결함을 찾았다** — `revert` 는 되돌아간 상태(`WAITING`)를 `RiderStatusChangedEvent.status` 에 실어 발행하는데(`BoardingCommandService:181`), `RiderStatusChangedComposer` 가 `"boarded".equals(status) ? 승차 : 하차` 로 **else 를 하차로 떨어뜨린다.** 즉 **승차를 취소하면 학부모에게 "자녀가 버스에서 하차했습니다" 가 간다**(사실과 다른 통지) · 하차를 취소하면 **같은 하차 문구가 한 번 더** 간다 | 사용자 판정 · 조율자 코드 실측(`BoardingCommandService:168~182` · `RiderStatusChangedComposer:24`) | **Phase 12 목표 13·14 로 신설한다.** 착수 전이라 `phase-goal-loop §1`(목표는 **착수 후** 늘리지 않는다)에 저촉되지 않는다. 필요한 것 — ①**되돌리기를 구별할 수 있는 이벤트 정보**(현재 이벤트는 되돌아간 상태만 실어 "무엇이 취소됐는지" 를 알 수 없다. 발행 지점에는 `fromStatus` 가 이미 있다) ②**정정용 알림 종류 신설 + `notification_log.type` CHECK 마이그레이션(`V6`)** ③문구 조립기 ④수신 대상은 기존 승하차 알림과 같다(학부모). ⚠ **`else` 를 하차로 떨어뜨리는 형태 자체가 결함이다** — 상태가 늘어날 때마다 조용히 오답이 된다 |
| **220** | ✅ **`NTF-06`(지연 알림 전송) 처리 확정 (2026-09-02 사용자 판정) — 오픈 이슈로 등재한다.** Ruling 218 이 찾은 "어느 Phase 범위에도 없는 미구현 기능" 을 **새 오픈 이슈 `W`** 로 두고, 오픈 이슈 `I`(지연 알림 중복·누적 규칙)와 함께 해소한다 | 사용자 판정 | **`W` 를 §7.2 오픈 이슈 표에 신설**하고 막는 Phase 를 **미배정**으로 둔다. `I` 가 스키마 판단(`ERD:1117` — 규칙 확정 시 전용 테이블 필요)을 딸고 있어 **`I` 를 먼저 닫아야 `W` 를 Phase 에 배정할 수 있다.** ⚠ **Phase 12 는 `W` 를 기다리지 않는다** — 범위가 `NTF-07~12` 라 서로 막지 않는다 |
| **221** | ⚠ **목표 표의 항수와 번호가 어긋나 있었다 (2026-09-02 조율자 직접 계수).** 머리글이 **15항**인데 표의 행은 **14개**이고 **#12 가 비어** 있었다. 원인 — Ruling 216 이 12항으로 고정할 때 "전체 실측"이 **#12** 였는데, Ruling 219 로 13·14 가 붙으면서 실측을 **#15 로 밀고** 12 자리를 비워 두었다. 머리글 옆 내역(정본 7 + 재계수 신설 4 + Ruling 219 신설 2 + 전체 실측 1)은 **14 로 계산**되어 14 가 옳다. ⚠ **없어진 목표는 부재하다** — 어느 좌석 발주문도 `목표 12`·`목표 15` 를 참조하지 않아 배정 누락은 미발생 | 조율자 직접 계수(`phase-goal-loop §6`) | **14항으로 정정하고 전체 실측을 #12 로 되돌려 1~14 를 연속으로 만들었다.** 좌석이 참조하는 번호(1~11·13·14)는 **불변**이라 발주문 수정은 불필요. `p12-resume.md` 와 이 절의 "12항" 서술도 함께 갱신. ⚠ **Ruling 216 의 신설 4항 중 ④(`unacked_count` 미확인 배지)는 정본 완료 조건 6번째 줄이 "중요 통지의 수신 확인 추적 + **미확인 배지**" 로 이미 담고 있다** — 신설이 아니라 **정본 항목의 세분화**다. 목표 자체는 표에 실려 있어 작업 범위는 불변이고, 분류만 정정한다 |
| **222** | ⚠ **T3 신고 2건 판정 (2026-09-02, 착수 후).** ①`p12-goal-table.md §4` 의 *"`notification_log` 을 쓰기하는 좌석은 T2 하나다. T3 은 읽기 전용이라 **같은 파일을 고치지 않는다**"* 가 **데이터 소유**와 **파일 접촉 금지** 두 가지로 읽혀, T3 이 충돌 회피를 위해 `NotificationLogQueryRepository`(새 파일) 신설을 제안했다. ②`§3.5` 가 요구하는 경계 시험(**실제 읽음 처리를 태운 뒤** T3 조회에서 `acked`·`unacked_count` 가 바뀌는지)을 T3 워크트리에서 **만들 수 없다** — T2 의 쓰기 경로가 그 트리에 물리적으로 부재하다 | 조율자 직접 계수(리포지토리 **47개 전부 엔티티와 1:1** · `Query`·`Read` 접미 리포지토리 **0개**) · Ruling 214 · Phase 9 선례 | **①기존 `NotificationLogRepository` 에 조회 메서드를 추가한다.** Ruling 214 의 기준은 **테이블(데이터) 소유권**이고 그것이 막으려던 사고는 *두 좌석이 같은 엔티티에 상태 전이 메서드를 각각 더해 **쓰기 의미**가 갈리는 것* 이라, 읽기 전용 조회 추가는 해당하지 않는다. 🔴 **선례 부재는 "만들어도 된다" 가 아니라 "만들면 선례 없는 구조가 영구히 남는다" 로 읽는다** — 병합 충돌은 한 번 치르는 비용이고 구조는 남는다. 충돌은 조율자가 양쪽을 다 살려 해소한다. ⚠ **양쪽이 같은 이름을 서로 다른 뜻으로 만드는 것**이 이 자리의 실제 위험이라 T2·T3 **양쪽에** 이름 규칙을 전파했다(전역 규칙 §16). **②경계 시험을 조율자 소유로 이관**하고 `p12-task-t3.md §7` 의 완료 기준 #4 에서 뺐다 — **병합 후 통합 검사에서 만든다**(Phase 9 `Phase9CrossSeatWiringTest` 선례). 🔴 **T2 의 예상 시그니처를 미리 전파하지 않는다** — Phase 11 에서 중계한 메서드 이름이 그 뒤 바뀐 전례(전역 규칙 §16). ⚠ **T3 이 손으로 `UPDATE`·리플렉션으로 대체하지 않기로 한 판단은 옳다** — Phase 10 Critical 이 정확히 그 형태였다. ⚠ **이관은 누락이 아니다. 목표 11 은 유지되고 소유만 조율자로 옮겼다** — `phase-goal-loop §6.2`(옮기다 뒷항을 잃는 사고)의 재발을 막으려고 여기에 등재한다 |
| **223** | ⚠ **T4 완료 보고에서 판단 2건 (2026-09-02).** ①T4 가 신설한 알림 종류 `BOARDING_CANCELED`·`ALIGHTING_CANCELED` 가 **어느 설정 토글에 속하는가** — T4 는 *"`ALIGHTING_CANCELED` 는 `alighting` 토글"* 로 넘겼다. ②`revert()` 는 **`NO_SHOW` 로부터도 호출 가능**한데(조회에서 빠지는 것은 `ABSENT` 뿐) T4 는 목표 13·14 문면이 승차·하차 취소로 한정된다고 읽어 정정 알림을 내지 않았다(자기 신고, 확신 90%) | 조율자 직접 계수 — `API_SPEC §3.14` · `NotificationSetting` 엔티티 필드 · `BoardingCommandService#revert` · `BoardingNotificationListener#appendRiderNoShow` | **①T4 의 결론을 정정한다. 두 종류 모두 `boarding` 토글이다.** 🔴 **`alighting` 토글은 실재하지 않는다** — `§3.14` 가 **`boarding` = 등하원(승차·하차·운행 시작) 알림**으로 승차·하차를 이미 한 항목에 묶었고, 엔티티의 boolean 필드는 `arrive`·`boarding`·`noShow` **3개뿐**이다. T1 에 전파했고 *"대응이 없는 종류는 설정 대상 밖으로 명시적으로 갈라라"* 를 함께 보냈다(`else` 를 한쪽으로 떨어뜨리는 형태가 이번에 고친 결함 그 자체다). **②T4 의 범위 판단은 옳다. 다만 그 뒤에 같은 형태의 실제 결함이 남는다** — `appendRiderNoShow` 가 **보호자에게 "미승차" 알림을 이미 보낸** 뒤라, **미승차를 되돌리면 정정 알림이 없어 오통지가 그대로 남는다.** 목표 문면 밖이라 이번 Phase 에서 고치지 않고 **이월 항목으로 등재**한다. ⚠ **자기 신고가 아니었으면 드러나지 않았을 자리다** — 좌석 보고서의 *"확신 90%"* 항목을 범위 판정이 아니라 **결함 신고로 먼저 읽으라**는 `phase-goal-loop §6.2` 4번이 또 값을 했다. **T4 실측 독립 재확인** — 결과 XML 20개 직접 계수 **tests=97 · failures=0 · errors=0** 일치 · 커밋 2건 `insertions` 171·89 · 트리 빈 결과 · postgres 재시작 **0** · 공유 `schoolbus` 는 **V1~V5 그대로**(V6 미적용) · 기존 시험 변경은 생성자 인자 **2줄**뿐이고 검사 조건 약화 부재 |
| **224** | 🔴 **발주 미소비 8회차 (2026-09-02) — T3 이 Ruling 222 를 받지 못한 채 반대로 구현했다.** 조율자가 T3 의 질문 2건에 판정해 보냈으나(`NotificationLogQueryRepository` 신설 금지 · 경계 시험 이관) 소비되지 않았고, T3 은 **새 리포지토리 파일을 만들어 커밋까지 마쳤다**(`38719c2`). 함께 관측 — **T1 은 미커밋 상태로 유휴 전환**(` M NotificationSetting.java`) | 조율자 실측 — `ls .../notification/repository/` 에 `NotificationLogQueryRepository.java` 실재 · T3 보고서 2항의 *"답을 받지 못해 판단대로 계속 진행했다"* · T1 워크트리 `git status --porcelain` 한 줄 | **탐지 경로가 7회차와 같다 — 보고서가 "답을 못 받았다" 를 적어 준 덕분에 1분 만에 드러났다.** 전역 규칙 §8 의 *"보고서에서 답을 못 받았다·지시가 없었다·대기 중이다 를 찾으면 즉시 계수하라"* 가 두 회차 연속으로 값을 했다. ⇒ **재지시로 합치게 하고, 완료 기준을 구현과 무관한 값으로 줬다**(파일 부재 · 시험 7/7·4/4 · 트리 빈 결과 — 전역 규칙 §8 7번). ⚠ **T1 건은 §8 4.1(트리가 더러우면 심은 변형 잔존인지 먼저 가른다)로 처리** — `git diff` 를 열어 **Ruling 223 을 받고 한 정당한 작업**임을 확인하고 커밋을 재지시했다. 🔴 **그 작업은 요청보다 낫다** — 조율자는 *"대응 없는 종류는 설정 대상 밖으로 명시적으로 갈라라"* 만 요구했는데 T1 은 **`default` 를 없앤 `switch` 식**으로 바꿔 **새 열거값이 생기면 컴파일이 멈추게** 했다. 사람이 기억해야 막히던 것을 컴파일러가 막는다. ⇒ **T4 의 2종을 병합하면 이 `switch` 가 의도대로 컴파일 오류를 내고, `boarding` 계열 `case` 에 넣는 것은 조율자 몫**이다. ⚠ **덧붙임 — 사후 주장 "이미 커밋돼 있었다" 가 또 나왔고 또 틀렸다(3회차).** T1 이 *"이전 턴에서 커밋한 뒤였고 보고 시점에 어긋남이 있었던 것 같다"* 고 보고했으나 **`reflog` 가 갈랐다** — `dc61437` 은 **23:37:35** 생성이고, 조율자 실측은 T1 의 유휴 알림(**23:34:03**) 직후여서 **그 시점 tip 이 `0760e56`** 이었다(실측 출력에 `dc61437` 부재). 커밋이 실측보다 **약 3분 뒤**다. 전역 규칙 §8 6번 그대로이고 판별 수단도 같다 — `git reflog show <브랜치> --date=format:...` 는 **그 ref 가 언제 그 값이 됐는지**를 직접 준다. ⇒ **미완을 판정한 시점의 실측(tip 해시)을 응답에 남겨 두는 습관이 두 번째로 값을 했다.** ⚠ 좌석을 탓할 일은 아니다 — 세 번 다 **악의가 아니라 자기 이력을 잘못 기억한 형태**였고, 그래서 **말이 아니라 ref 이력으로 가른다** |
| **225** | 🔴 **T2 가 목표 6 에서 사양 미준수를 신고했고, 조율자 재계수로 규모가 2배였다 (2026-09-02).** T2 는 *"자녀 이름을 본문에 안 넣는 조립기가 **대략 8개 이상**"* 으로 보고했다. 함께 신고한 것 — 그 조립기들이 근거로 든 **`FEATURE_SPEC §6.3` 이 오인용**이다(이름은 **L1 기본**이고 L3 는 사진·주소 원문·좌표·특이사항·보호자 연락처 원본). 부가 판단 3건 — ID 필드 타입 · `unread_count` 기준 · 14일 경계 방향 | 조율자 직접 계수 — `grep -rl studentName .../notification/domain/impl/` **0건**(총 16개) · §6.3 을 인용한 것 **5개** · `NotificationComposer.compose(S subject)` 입력이 이벤트이고 이벤트에 `studentName` 부재 · `ATT-03`·`P-02` 가 *"모든 문구에 자녀 이름 포함"* 을 2곳에서 명시 | **①T2 의 §6.3 판정은 옳다** — 이름은 L1 이므로 L3 를 근거로 든 생략은 사양 미준수다. **다만 규모는 8개가 아니라 조립기 16개 전부**이고, 🔴 **조립기는 이름을 넣을 수단 자체가 없다**(입력이 이벤트라 `studentId` 만 실린다) ⇒ 고치려면 **이벤트 계약 변경 또는 조회 추가**가 필요해 Phase 2~11 산출물 전반을 건드린다. **목표 표 §3.4 의 "이 Phase 는 목록 응답이 그 값을 싣는지 검사하는 자리이고 조립기를 고치지 마라" 는 옳았다** ⇒ **목표 6 은 Phase 12 범위에서 통과**로 판정하고, **문구 미준수는 이월 항목으로 신설**한다. **②ID 타입 편차는 T2 가 만든 것이 아니다** — 사양은 `run_id`·`student_id`·`notification_id` 를 `string` 으로 적는데 코드는 Phase 7 이후 `Long runId` **15건**·`Long busId` 9건·`Long stopId` 8건을 써 왔고 `studentId` 는 `Long` 6 · `String` 6 으로 갈려 있다. **이미 있던 문서·코드 어긋남**이라 T2 판단을 그대로 두고 **문서 정합 항목으로 별도 등재**한다. ⚠ **T2 의 근거 서술은 실제보다 강했다** — *"기존 관례가 Long"* 이라고 적었으나 `studentId` 는 6:6 으로 갈려 있다. 결론은 유지하되 **근거는 정정한다.** **③`unread_count` 를 필터와 무관한 전체 기준으로, 14일 경계를 포함으로** 둔 두 판단은 **사양 미명시 구간의 서비스 판단**으로 승인한다 — 둘 다 자바독에 근거가 남아 있고, 특히 `unread_count` 는 **실제 읽음 처리 엔드포인트를 거친 뒤에만 검증**하도록 시험을 짜 배지 계산 자체를 증명했다(시드 UPDATE 로 깎으면 증명되지 않는다) |
| **226** | 🔴 **`§3.5` 가 남긴 물음의 답 — "중요 통지" 가 어느 알림 종류인지 정본에 부재하다 (2026-09-02).** `NTF-10`(수신 확인 추적 + `unacked_count` 미확인 배지)·`A-13`·`§3.13` 이 전부 **"중요 통지"** 를 수식어로만 쓰고 **`NotificationType` 21종 중 무엇이 해당하는지 목록이 없다.** T3 이 오픈 이슈 표까지 훑어 신고했고 조율자가 재확인했다 | 조율자 직접 계수 — `docs/` 전체 **4회 언급 전부 수식어**(`API_SPEC:678` · `FEATURE_SPEC:686` NTF-10 정의 · `FEATURE_SPEC:714` A-13 · `USER_FLOWS:496`) · 미해결 목록에도 부재 | **T3 의 처리를 승인한다 — `unacked_count` 는 전 종류를 동일하게 센다.** 근거 없이 일부만 빼는 것이 곧 임의 판단이고, T3 은 그 근거와 함께 *"정본이 분류를 명시하면 이 조건을 좁힌다"* 를 자바독에 남겨 **좁힐 지점을 코드에 표시**했다. 🔴 **이 판정은 Ruling 227 로 뒤집혔다 — 목록은 실재했다**(`USER_FLOWS:623`). T3 의 "정본 어디에도 없다" 가 불완전했고 조율자가 그것을 검증 없이 승인했다. ⇒ 사양 공백이 아니라 **T3 이 못 찾은 것**이다. ⚠ **지금 상태의 실질 효과는 배지가 실제 의도보다 크게 잡히는 것**이고, 반대 방향(빠뜨림)이 아니라 **과다 계수**라 놓치는 사고는 생기지 않는다. **함께 등재 — T3 이 메서드를 `searchForStaffLog`·`countUnackedForStaffLog` 로 지어** 조율자가 지시한 "양쪽이 같은 이름을 다른 뜻으로 만드는 위험" 을 막았고, 개명으로 **파생 쿼리 문법이 깨져 `@Query`+`@Param` 으로 전환**했다 — 🔴 **이 저장소는 `build.gradle` 에 `-parameters` 가 부재해 `@Param` 이 필수**다(반복해서 걸리는 함정, `PROJECT_NOTES` 로 옮길 것). **T3 은 파일 위치가 바뀐 뒤 결함 심기 6건을 전부 재실행**해 같은 실패 개수를 재현했다 — 위치 변경이라 생략해도 됐는데 다시 돌린 것은 옳은 판단이다 |
| **227** | 🔴 **좌석 경계에서 실제 결함이 났다 — `acked` 를 쓰는 쪽과 세는 쪽의 대상 집합이 다르다 (2026-09-02, 조율자 코드 실측).** **T2** 는 `markRead` 에서 **`IMPORTANT_TYPES = {DELAY, NO_SHOW, ROUTE_CHANGED}` 3종만** `acked=true` 로 바꾼다(`NotificationReadCommandService:46·69`). **T3** 의 배지는 `SELECT COUNT(n) ... WHERE n.academyId = :academyId AND n.acked = false` 로 **전 종류를 센다**(`NotificationLogRepository:169`). ⇒ **`BOARDING`·`ALIGHTING`·`ARRIVE` 등은 영원히 `acked=false` 라 미확인 배지가 0 이 되지 않고 발송할 때마다 단조 증가한다.** 하루 수백 건이 쌓이는 서비스라 배지는 며칠 만에 의미를 잃는다 | 조율자 직접 코드 대조 — T2 `NotificationReadCommandService:44~69` · T3 `NotificationLogRepository:155~170` · `USER_FLOWS:623`·`624` | **①`unacked_count` 를 같은 3종으로 좁힌다. 조율자가 병합 시 처리한다** — T2 의 `IMPORTANT_TYPES` 를 공유 상수로 올리고 T3 의 쿼리에 종류 조건을 더한다. 🔴 **②Ruling 226 을 정정한다** — T3 은 *"어느 종류가 중요 통지인지 정본 어디에도 없다"* 로 보고했고 조율자가 그대로 승인했으나, **`USER_FLOWS:623` 에 `중요 알림(지연 · 미승차 · 노선 변경)` 이 실재**한다. T2 가 찾아냈다. ⚠ **조율자 잘못이다 — 좌석의 "부재" 주장을 `grep` 으로 재현하지 않고 승인했다.** `phase-goal-loop §6.3`(낡은 "없음" 은 판정을 틀리게 하는 데 그치지 않고 없는 것을 새로 만들게 한다)과 같은 형태이며, 이번엔 **"없다" 를 근거로 조건을 넓게 두는** 방향으로 났다. **③이 결함은 어느 좌석의 시험으로도 잡히지 않는다** — 각자 자기 쪽만 검사했고 둘 다 통과다. **목표 표 `§3.5` 가 미리 지목한 바로 그 자리**이고(*"Phase 10 의 Critical 이 정확히 이 형태"*), **경계 시험을 조율자가 만들기로 한 것(Ruling 222)이 유일한 탐지 수단**이다. **④정본 내부 어긋남 1건을 함께 등재** — `FEATURE_SPEC:685`(NTF-09)는 *"비상 알림은 팝업 병행이 확정, **그 외 종류별 팝업 여부는 정본에 미기재**"* 인데 `USER_FLOWS:623` 은 **지연·미승차·노선 변경을 팝업 대상으로 명시**한다. 목표 5(`popup`)와 맞닿으므로 사용자 판정 대상 |
| **228** | ✅ **병합 완료 및 병합이 드러낸 것 (2026-09-03).** 좌석 4개를 `p12-t4 → p12-t1 → p12-t2 → p12-t3` 순으로 병합했다(분기점 `99c81b7`, 되돌릴 지점 `p12-premerge-backup`). 충돌 **6건** — `AccountStatusGateEndpoints` 2 · `AuthFlowIntegrationTest` 2 · `ApiValues` 1 · `NotificationLogRepository` 1, **전부 양쪽을 다 살리는 형태** | 조율자 실측 — 게이트 문자열 **84** · `hasSize` **94** · `compileJava compileTestJava` 통과 · `Phase12AckBoundaryTest` 2/2 | **①🔴 T1 의 `switch` 안전장치가 설계대로 작동했다** — 병합 직후 `NotificationSetting.java:94: error: the switch expression does not cover all possible input values`. T4 가 더한 2종을 분류에 안 넣었다는 것을 **컴파일러가 잡았다.** 옛 `if-else` + `return true` 였으면 조용히 "항상 발송" 으로 샜다. Ruling 223 대로 `boarding` 계열 `case` 에 추가. **②조율자 충돌 해소가 만든 문법 오류를 컴파일이 잡았다** — 설명 문자열을 합치며 닫는 괄호를 중복으로 남겼다. 전역 규칙 §10 5번(충돌 해소 병합 뒤 최소한 컴파일)이 값을 했다. **③Ruling 227 결함을 고치고 경계 시험을 신설했다** — `NotificationType.IMPORTANT_FOR_ACK` 한 곳에 집합을 두고 쓰는 쪽·세는 쪽이 함께 참조. `Phase12AckBoundaryTest` 2건. **결함을 되심어 검증** — 배지를 다시 전 종류로 되돌리니 **의도한 1건만 실패**(`확인_대상_밖_종류는_배지에_잡히지_않는다`), 원복 후 트리 빈 결과. **④🔴 T3 의 시험 2건이 결함 쪽 동작을 기대값으로 굳히고 있었다** — 승하차·도착 알림을 심고 `unacked_count` 가 그것을 센다고 단언했다. 그 종류는 읽음 처리를 거쳐도 `acked` 가 남지 않아 **배지가 0 이 될 수 없는 종류**다. ⚠ **`phase-goal-loop §5` 의 *"단언이 결함을 고정하고 있지 않은지 본다 — 테스트를 옳은 동작 쪽으로 고쳤을 때 실패한다면 그 단언은 결함을 굳히고 있다"* 가 그대로 재현됐다.** 확인 추적 대상(지연·미승차)으로 바꿔 배지 단언이 뜻을 갖게 하고, 학원 격리 시험에는 **남의 학원 미확인 건이 배지에 새지 않는** 검사를 함께 남겼다. ⚠ **이 정정은 좌석 시험이 통과했다는 사실이 검증을 뜻하지 않음을 보여 준다** — 두 좌석 모두 전건 통과였고, 한쪽은 잘못된 기대값으로 통과하고 있었다 |
| **229** | 🔴 **판정 회차를 네 번 버리고 다섯 번째에 얻었다 — 전부 환경이었고 그중 하나는 코드 결함으로 오판했다 (2026-09-03).** ①10분 제한 중단 ②**Redis 포트**(앱 기본값 `6379` vs 오버레이 `16379`) → 9건 실패 ③배경 작업 제한 중단 ④**워커 OOM 으로 빌드가 정지**(로그 4시간 무갱신) | 조율자 실측 — `lsof -iTCP:6379` 무결과 · 시험 `system-out` 의 `Unable to connect to Redis` · 로그 끝의 `OutOfMemoryError`+`java.lang.instrument ASSERTION FAILED ... JPLISAgent.c` · 워커 살아있는데 로그 `mtime` 정지 | **①🔴 Redis 건을 "병합이 만든 회귀" 로 오판해 사용자에게 보고했다가 정정했다.** 근거로 삼은 것이 *"단독 재실행에서도 실패한다"* 였는데, **단독 재실행은 부하 의존만 갈라 주고 설정 문제는 못 가른다.** ⚠ **상태 코드(`500`·`503`)만 보면 코드 결함과 구별되지 않는다 — 실패한 시험의 `system-out` 에서 서버측 예외를 읽어야 갈린다.** `PROJECT_NOTES` 에 판별법과 함께 등재. **②워커 힙 `1024m` → `2048m`**(커밋 `b7b155f`). 1024m 은 **763 tests 시절** 실측값이고 1,088건에서 부족해졌다. 🔴 **증상이 실패가 아니라 정지**라 "느리다" 로 오해된다 — `build.gradle` 주석에 판별 근거 2가지를 적었다. ⚠ **주석의 "2048m 은 실사용 610MB 라 더 올릴 근거가 없다" 도 그 시절 값이라 지금은 성립하지 않는다** — `phase-goal-loop §6`(파생본은 낡는다)이 **주석 안의 실측값**에도 적용된다. **테스트가 늘 때마다 이 값을 함께 본다.** **③메모리 부족 회차의 실패는 증거 능력이 부재하다** — 그 회차의 `ProximityNotificationSchedulerTest` 2건은 **깨끗한 회차에서 통과**했고, 클래스 수가 189 → **178 로 줄어든 것**이 "끝까지 못 갔다" 는 신호였다. ⇒ **판정 회차는 새 DB·올바른 포트·충분한 힙 셋을 갖춘 뒤에만 센다.** `shedlock` 처럼 **트랜잭션과 함께 되돌아가지 않는 표**가 죽인 회차의 값을 물고 남는 것도 새 DB 로 끊는다 |

⚠ **오픈 이슈 표가 양방향으로 낡아 있었다**(`phase-goal-loop §6.1` 형태) — **`A`** 는 해소된 것이 미해소로 남아 있었고, **`J` 잔여 ②**(이미 나간 알림의 정정 방식)는 이 Phase 와 맞닿는데 **진행 표에 아예 빠져** 있었다. `J` 잔여는 **정본에 문면이 부재**해 목표로 넣지 않았다 — ⚠ **좌석이 임의로 정정 알림을 만들지 않게 발주문에 명시한다.**

#### Phase 11 이월 3건 (2026-09-02 등재)

| # | 항목 | 이 Phase 에서 확인할 것 |
|:-:|---|---|
| **①** | 🔴 **`§5.16`·`§6.11` 비상 알림 응답 필드가 정본과 어긋난다 — 7건.** `emergency_id`→`id`(이름) · `raised_by`·`position`·`acked_by` 가 **객체여야 하는데 평면**(형태 3건) · `direction`·`contacts` **부재** · `raised_at` 이 `occurred_at`+`received_at` 둘로 갈림. ⚠ **`§6.11` 이 *"응답 — `§5.16` 항목 + 아래"* 라 관리자 콘솔이 이 어긋남을 그대로 상속**한다 | **어느 완료 조건도 이것을 검사하지 않아 전 시험이 초록인 채 남았다.** 목표 문면에 **"응답 형태를 항목 단위로 대조"** 를 넣어야 같은 형태로 다시 빠지지 않는다. 상세 대조표는 `docs/archive/sdd/IMPLEMENTATION_PLAN/report-p11-t2-fix1.md` 맨 끝 |
| **②** | **`no_show_wait_minutes` 에 상한이 정본 어디에도 없다** — `API_SPEC §5.21`·`FEATURE_SPEC:90`·`:355` 확인. 실재 제약은 DDL `CHECK (> 0)` 하나뿐이라 **아주 큰 값을 넣으면 미승차 에스컬레이션이 사실상 발생하지 않는 상태를 사용자가 합법적으로 만든다** | **사양 공백이다.** T1 에게는 *"상한을 발명하지 마라"* 로 지시해 코드에 값을 박지 않았다 — **먼저 정본에 상한을 정하고** 그 다음에 코드가 따라간다 |
| **③** | **규약 시험의 면제 목록은 어느 검사도 받지 않는다.** `SchedulerLockConventionTest` 와 `ControllerAuthorizationConventionTest` **둘 다** 같은 구조다 — 면제 목록에 항목을 넣으면 그 대상은 검사에서 조용히 빠지고, 근거 주석이 틀렸는지 비었는지는 아무도 검사하지 않는다(좌석이 변형으로 실증) | **막는 것** = 새 대상이 면제 없이 방치되는 것 · **못 막는 것** = 면제가 근거 없이 늘어나는 것. 방법 후보 — 면제 항목마다 **근거 주석 존재를 강제**하거나 **면제 개수 단언**을 둔다(계정 상태 게이트가 이미 쓰는 방식) |

⚠ **Phase 9 이월 ⑤(T2 수정 라운드 3 재리뷰 미부착)는 아직 미해소다** — Phase 10·11 리뷰 범위 밖이었다. R1 이 `git merge-base --is-ancestor` 로 좌석 계보 밖임을 확인했다. 계속 남긴다.

---

### Phase 13 — 모니터링 · 관제

| 항목 | 내용 |
|---|---|
| **범위** | `MON-01~07` · `A-03`·`A-04`·`A-14` · `O-05`·`O-06`. **`GET /staff/runs/live`(A-14·MON-07)는 WS 증분 방송 이전의 초기 스냅샷** — 이것이 없으면 화면 진입 시 현재 위치를 그릴 수 없음 |
| **선행** | Phase 12 |
| **기능 ID** | MON-01~07 (P1) |
| **참조** | `API_SPEC §5.3`·`§5.4`·`§6.8`·`§6.9` · `USER_FLOWS UF-M-05`·`UF-O-02` |
| **산출물** | `monitoring` 모듈 · 관계자 대시보드 · 메인 관리자 전 학원 관제 |

**ETA 는 관제 전용** — 학부모·학생 앱 비노출(`C-08`)과 별개이며 메인 관리자 콘솔에만 포함 (`O-05`).

**메인 관리자는 격리 예외이나 조회 범위만** — 학원 등록·관계자 승인·차단 해제 외의 운영 데이터 변경 권한은 부재 (`ARCHITECTURE §6.2`). 경로에 학원 ID 를 명시해 의도를 드러냄.

**관제는 폴링이 아니라 WebSocket** (`ARCHITECTURE §10.3`).

**완료 조건**
- 관계자 대시보드 — 지표 카드 + 회차 표 + 행 클릭 시 호차별 명단
- 실시간 탑승 현황·미승차 에스컬레이션 진행 표시
- 변경분 구분 표시 + 기사·동승자 확인 응답 여부 (`MON-05`)
- 관계자 토큰으로 타 학원 대시보드 조회 시 403
- 메인 관리자 관제 — 전 학원 버스 위치·경로·승하차지별 도착 예정 시각·연락처
- 승하차지별 학생 리스트에 사진·연락처 포함, **관계자 이하 역할에는 미노출**
- 학부모·학생 응답에 ETA 필드 부재

✅ **착수 전 판정 (2026-09-03) — 목표 표 14항을 `docs/archive/sdd/IMPLEMENTATION_PLAN/p13-goal-table.md` 에 고정했다.** 분기점 `b7b155f`. ⚠ `docs/` 가 git 추적 밖이라 이 절의 갱신은 커밋되지 않는다 — 분기점은 코드 HEAD 다.

| # | 판정 | 근거 | 결론 |
|:-:|---|---|---|
| **230** | ⚠ **`§8.1` 의 "13 ← 12 재판정 필요" 를 재판정했다.** 관제는 `run`·`run_rider`·`run_stop`·`run_position`(Redis)·`no_show_case`·`assignment` 의 **읽기 전용 프로젝션**이고 Phase 12 산출물(`notification_log` 조회·설정)을 어디서도 읽지 않는다 | `API_SPEC §5.3`·`§5.18`·`§6.8`·`§6.9` 응답 필드의 출처를 전부 코드에서 추적 | **실질 선행은 9(moving 회차·승하차)·10(위치·WS)·11(미승차 사건)** 이다. 12 가 이미 완료라 진행에는 영향 없음 — 표의 근거만 정정 |
| **231** | ⚠ **완료 조건이 범위보다 좁았다.** 범위(`MON-01~07`·`A-14`·`O-05·06`)와 참조 절에 있는데 기존 7항 어디에도 문면이 부재한 것 6개 — **`§5.18 GET /staff/runs/live` 자체**(범위 열이 *"이것이 없으면 화면 진입 시 현재 위치를 그릴 수 없음"* 으로 명시하고도 완료 조건에 부재) · **위치 미수신 `position=null`+`last_seen_at`** · **`§6.8` 의 `404 ACADEMY_NOT_FOUND`** · **`§6.9` 의 `404 RUN_NOT_FOUND`** · **관제 채널 `position.eta` 채움**(Phase 10 이월 ④가 *"Phase 13 이 채울 자리"* 로 지목) · **Phase 11 이월 ①**(비상 알림 응답 형태 7건 — Phase 12 가 *"다음 Phase 목표 표에 싣는다"* 로 판정) | `API_SPEC` 각 절 · Phase 10·11·12 이월 표 | **6항 신설 → 최종 14항**(정본 7 + 신설 6 + 전체 실측 1). ⚠ **Phase 10 Ruling 211 · 11 Ruling 213 · 12 Ruling 216 에 이어 4회째 같은 형태** — 범위 열과 완료 조건을 항목 단위로 마주 놓아야 빈칸이 보인다 |
| **232** | 🔴 **관제 ETA 의 계산 방식이 정본에 부재하다.** `§6.8` 예시는 `stops[].eta`·`destination_eta`·`est_depart_time` 에 값을 싣는데 "무엇으로 계산하는가" 가 `API_SPEC`·`ERD`·`ARCHITECTURE`·`TECH_DECISIONS` 어디에도 없다. `§5.18 delay_minutes` · `§7.1 position.eta` 도 같다 | 조율자 직접 계수 — `grep -n 'eta\|ETA' docs/*.md` · `RouteEtaSchedule`(노선 계산 ④단계) 실재 · `RunStop.forStop(..., eta)` 가 확정 배치에서 값을 받음 · `PRD:96`·`:158`(주행 중 재계산·재배포 부재) · `ARCHITECTURE §8.3`(계산 소비자는 배치·온디맨드 둘) | **조율자 잠정 판정 — 계획값을 읽는다. 재계산하지 않는다.** `stops[].eta` = `run_stop.eta` 저장값(도착 처리 시 `null`) · `destination_eta` = `depart_time + est_duration_min` · `est_depart_time` = `run.started_at`(`moving` 만 반환하므로 항상 존재) · `position.eta` = 다음 미도착 정차의 `run_stop.eta` · `delay_minutes` = 최근 도착 처리 정차의 `arrived_at − eta`(분, 음수 0; 도착 전이면 `started_at − depart_time`). 근거 — 위치 수신마다(버스 수 × 5~10초) 지도 API 를 부르는 세 번째 계산 소비자는 설계에 없고 `PRD C-05` 가 주행 중 재계산을 배제한다. ✅ **2026-09-03 사용자 확정 — 잠정 판정 그대로.** `est_depart_time` = 실제 출발 시각(`started_at`) · `delay_minutes` = 최근 도착 처리 정차의 `arrived_at − eta` 포함 전항 확정. 좌석 자바독의 *"Ruling 232 잠정"* 은 *"Ruling 232 확정"* 으로 바꾼다 |
| **233** | ⚠ **`§5.3 metrics` 5종 중 정의가 정본에 없는 것 2개 + `§5.18 progress.total` 의 `skipped` 포함 여부.** `metrics.boarded`("승차 완료 인원")·`metrics.unassigned_managers`("배치 대기 매니저 수")가 문면뿐이다 | `grep -n '배치 대기\|unassigned' docs/*.md` — `API_SPEC:1189` 한 곳 | **좌석 판정에 위임하되 조율자 권장을 목표 표 §3.3 에 적었다** — `boarded` = 현재 `status='boarded'` 수(회차 표 `boarded_count` 합과 같아야 카드와 표가 합산 관계를 유지) · `unassigned_managers` = 활성 매니저 중 그날 `assignment` 가 없는 수 · `progress.total` 은 `skipped` 포함(`PRD:158` 순번 유지). 다르게 정하면 근거를 보고서 1항에 |
| **234** | ⚠ **좌석 분할 축 — 이 Phase 는 읽기 전용이라 Ruling 214 의 "테이블 소유권" 이 성립하지 않는다.** 어느 좌석도 테이블에 쓰지 않는다 | 목표 표 §0 재계수 — `monitoring/` 이 `.gitkeep` 뿐이고 신설 마이그레이션 0 | **파일·인가 축으로 3좌석** — T1 관계자(`§5.3`·`§5.18`, `Staff*` 접두, `@CanMonitorAcademy` 신설, **공용 판정 클래스 소유**) · T2 메인 관리자(`§6.8`·`§6.9`, `Admin*` 접두, `PositionBroadcastListener` 의 `eta` 채움) · T3 비상 알림 응답 정합(`exception/`·`admin/AdminEmergency*`). 🔴 **`§5.18` 과 `§6.8` 이 같은 세 판정(Redis 최신 좌표+2분 유실·현재 정차·다음 정차)을 쓴다** — 각자 만들면 판정이 두 벌이 되므로 **T1 이 먼저 만들어 이름을 보고하고 조율자가 T2 에 전파**한다. T2 는 `Admin` 접두 임시본으로 시작해 병합 시 교체. 의도한 겹침은 `AccountStatusGateEndpoints`·`AuthFlowIntegrationTest`(T1·T2) 뿐. `ErrorCode`·`Permissions`·`RolePermissions` 는 필요한 것이 전부 있어 **아무도 건드리지 않는다** |
| **235** | ⚠ **관제 응답의 `position` 시각 필드 이름이 두 절에서 다르다** — `§5.18` 은 `recorded_at`(단말 시각), `§6.8` 은 `received_at`(서버 수신) | `RunPositionRedisValue` 5필드에 둘 다 실재 | **정본 내부 어긋남이 아니다. 각 절 문면대로 내보낸다.** 좌석이 통일하려 들지 않게 발주문에 명시 |
| **236** | 🔴 **T3 착수 전 신고 — `§5.16` 이 요구하는 `position.recorded_at` 의 출처 컬럼이 `emergency_alert` 에 없다 (2026-09-03).** `V1:630-651` 은 `lat`·`lng` 만 갖고, `EmergencyCommandService.attachLocationIfCached` 가 Redis 스냅샷의 `recordedAt` 을 **읽고도 버린다**(`EmergencyAlert.attachLocation(lat, lng)` 에 시각 인자가 없다). T3 은 발주문의 "스키마가 필요하면 보고하고 멈춘다" 대로 코드를 한 줄도 건드리지 않고 멈췄다 | T3 신고 + 조율자 재확인(`ERD:745` 표 · `attachLocationIfCached`) | **정본 내부의 어긋남이고 기능 요건이 이긴다**(Ruling 215 와 같은 형태). ⇒ **`V7` 로 nullable 컬럼 `position_recorded_at timestamptz` 를 추가하고 `attachLocation` 이 `recordedAt` 을 함께 저장한다.** 좌표 미첨부 발신은 `null`. `occurred_at`·`received_at` 을 대신 싣지 않는다(알림 시각이지 측정 시각이 아니다). `ERD §3.4` 표에 행 추가(조율자). ⚠ **"이 Phase 는 스키마를 만들지 않는다" 는 보고 없이 만들지 말라는 뜻**이고, 미루면(안A) 같은 마이그레이션이 다음에 필요하면서 그 사이 응답은 정본 미준수로 남는다. 함께 승인 — ①`raised_at` = `receivedAt` 단일화(`assertWithinCancelWindow` 자바독이 그 결정을 이미 명문화) ②`contacts[]` = 배치 기사·동승자의 `{name, role, phone}`(`API_SPEC §4.14` 처리표) |
| **237** | 🔴 **T2 신고 — T1 의 공용 판정 커밋 `2e04653` 이 만든 `monitoring.query.RunPositionReader` 가 기존 `location.proximity.RunPositionReader` 와 단순명이 같아 Spring 기본 빈 이름이 겹친다 (2026-09-03).** `@SpringBootTest` 컨텍스트가 `ConflictingBeanDefinitionException` 으로 전부 부팅 실패. T2 가 `§6.8` 통합 시험을 처음 돌리며 발견 — 그 전까지 이 조합으로 컨텍스트를 띄운 좌석이 없었다 | T2 신고 + 조율자 실측(T1 워크트리에 두 파일·`@Component` 실재) | **T2 의 수정 `1a9a9d5`(`@Component("monitoringRunPositionReader")`)를 정본 수정으로 채택**하고 T1 에 cherry-pick 을 지시했다. 클래스 개명 대신 빈 이름 명시를 택한 근거 — 주입이 전부 타입 기반이라 영향 0 이고, 세 좌석이 작업 중인 시점에 개명은 `RunLiveStateResolver` 까지 두 좌석의 파일을 함께 바꿔야 한다. ⚠ **좌석이 자기 클래스만 단위 시험하고 컨텍스트를 안 띄우면 이런 결함은 병합 후에야 드러난다** — Phase 12 의 `AcademyScopeSingleJudgmentPointTest`(저장소 전체를 훑는 검사는 좌석 단독 실행에서 의미가 없다)와 같은 형태. 게이트 리뷰에 "`@SpringBootTest` 를 최소 1건 돌렸는가" 를 싣는다 |
| **238** | 🔴 **T1 이 미커밋 상태로 `monitoring/repository/` 에 기존 엔티티(`Assignment`·`Manager`·`NoShowCase`·`RunRider`)의 두 번째 리포지토리 4개를 만들고 있었다 (2026-09-03, 조율자가 워크트리 `git status` 로 발견).** 자바독 근거는 *"쓰기 소유 경계"* — Phase 12 T3 가 `NotificationLogQueryRepository` 를 제안하며 든 것과 **같은 오독**(데이터 소유를 파일 접촉 금지로 읽음). 함께 드러난 것 — **목표 표 §0 의 "`§5.19` 응답의 `ack{driver, escort}` 가 이미 같은 판정을 하고 있을 것이다, 찾아서 재사용하라" 는 조율자의 낡은 추정이었다.** `§5.19 GET /staff/runs/{runId}/route`(관계자용 확정 노선 조회, A-03·A-08·A-15)는 **미구현**이고 `ackedRouteVersionId` 를 읽는 프로덕션 코드는 `Assignment` 엔티티뿐 | 조율자 실측 — `ls monitoring/repository/` 4개 · `grep -rln ackedRouteVersionId src/main` 1개(엔티티) · `run/controller/` 에 `§4.3` 매니저용 `RunRouteController` 만 실재 | **①커밋 전에 정정 전파** — 4개 파일을 지우고 조회 메서드를 기존 `AssignmentRepository`·`ManagerRepository`·`NoShowCaseRepository`·`RunRiderRepository` 에 더한다(Ruling 222 그대로. 이름은 `…ForStaffDashboard` 류로 용도를 드러낸다). T1 이 `ack` 판정식을 `RunAckChangesCommandService` 쓰기 경로에서 거꾸로 읽은 판단은 **옳다.** ②**`§5.19` 는 계획의 어느 Phase 범위 열에도 절 번호로 등장하지 않는 미구현 엔드포인트다** — Ruling 218 의 `NTF-06` 과 같은 형태. **이 Phase 에 흡수하지 않고 오픈 이슈 `X` 로 등재해 사용자 판정을 받는다.** ⚠ **`phase-goal-loop §6.3` 재발** — 목표 표에 "있을 것이다" 를 적으면서 `grep` 으로 세지 않았다. "재사용하라" 는 지시는 **대상의 실재를 조율자가 계수한 뒤에만** 적는다 |
| **239** | ⚠ **T1 완료 보고의 자기 신고 (2026-09-03) — `§5.4 GET /staff/runs/{runId}/roster` 가 타 학원 회차에 `404 RUN_NOT_FOUND` 를 낸다. 정본 `§1.5` 는 타 학원 자원에 `403 ACADEMY_SCOPE_VIOLATION`, 목표 5 문면도 `403`.** 원인 — Phase 9 의 `RosterQueryService` 가 회차를 학원 스코프 조회로 찾아 행 자체가 안 잡히고, `AcademyScope.assertAccessible` 을 거치는 `403` 분기가 없다 | T1 신고 + 조율자 확인(`RosterQueryService`, Phase 9 산출물) | **Phase 13 범위 밖 — 이월로 등재하고 사용자 판정을 받는다.** 두 갈래 — ①**`404` 유지**: 타 학원 자원의 존재 자체를 드러내지 않는다(존재 유출 방지, 보안상 흔한 선택). 정본 `§1.5`·`§5.4` 문면을 고쳐야 한다 ②**`403` 으로 정정**: 정본 문면대로. `findById` + `assertAccessible` 형태(다른 엔드포인트들의 방식)로 바꾼다. ⚠ **같은 저장소 안에서 두 방식이 섞여 있다**는 것이 진짜 문제다 — 어느 쪽이든 **한 규칙**으로 정하고 `AcademyScopeIsolationTest` 류로 전 엔드포인트를 한 번에 검사하는 것이 후속 단위. 목표 5 의 T1 시험은 **실제 동작(`404`)을 단언한 채** 두고, 판정 뒤 그 단언을 바꾼다. 함께 등재 — T1 이 심은 변형 3(학원 조건 제거)을 "소유 밖 파일이라 못 심었다" 고 한 것은 오독(변형은 원복하므로 소유와 무관). R1 이 `RunRepository` 에 직접 심는다 |
| **240** | ✅ **X-08 해소 — 사용자 판정 ② (2026-09-03).** `§5.4 GET /staff/runs/{runId}/roster` 타 학원 회차 응답을 `404 RUN_NOT_FOUND` → **`403 ACADEMY_SCOPE_VIOLATION`** 으로 정정. 조율자 권장 근거 — 회차 id 는 매일 학원마다 생기는 순번이라 `404` 가 감추는 존재 사실의 유출 가치가 미미하고, ①`404` 유지는 이미 `403` 인 다른 엔드포인트·정본 `§1.5` 문면을 되돌려야 한다 | 사용자 판정 + 좌석 `x08-sonnet` 구현 · 조율자 diff 검증 | **커밋 `08e7878`**(3파일 +53/−8) — `RosterQueryService.staffRoster` 를 `findById` + `AcademyScope.assertAccessible` 로(존재 판정과 범위 판정 분리, 다른 엔드포인트와 같은 방식). 시험 — `StaffDashboardControllerTest` 목표 5 를 `403` 으로 교체 · `StaffRosterControllerTest` 에 타 학원 `403`·부재 회차 `404` 2건 신설. 4클래스 28 tests 0 실패. 결함 심기(옛 `findByIdAndAcademyId` 복귀) → 그 2건만 실패, 원복 후 트리 빈 결과. 정본 반영 — `FEATURE_SPEC §8 X-08`·`PRD §10.2`·`USER_FLOWS §12.5` 해소 표시, `API_SPEC §5.4` 에러에 `403` 추가. ⚠ **후속 미배정** — 전 엔드포인트 학원 격리 일괄 검사 시험(저장소 안에 `findByIdAndAcademyId` 방식과 `assertAccessible` 방식이 아직 혼재) |

**착수 시 재계수(2026-09-03 조율자 실측)** — 프로덕션 핸들러 **94** · 컨트롤러 **47** · 게이트 문자열 **84** · `hasSize(94)` · 시험 클래스 파일 **194** · 마이그레이션 `V1`·`V3`~`V6`(다음 `V7`, 이 Phase 미사용). **이미 있어 다시 만들지 않는 것** — `§5.4` 관계자 명단(Phase 9) · WS 관제 채널 구독 인가 + 방송 7종(Phase 10) · `run_stop.eta` 계산(Phase 7 `RouteEtaSchedule`) · 신호 유실 2분 판정(`StudentBusPositionQueryService.STALE_THRESHOLD`) · `@CanMonitorAll`·`MONITOR_ACADEMY`·`MONITOR_ALL` · 확인 응답 근거(`Assignment.ackedRouteVersionId`) · `NoShowCase` · 에러 코드 3종. **부재해서 만드는 것** — `monitoring` 모듈 전체 · 핸들러 4 · `@CanMonitorAcademy` · 관제 채널 `position.eta` 값.

⚠ **Phase 11 이월 ③(규약 시험 면제 목록 무검증)은 Phase 12 가 "조율자 소유 목표 외 과제" 로 적었으나 미착수다** — 2026-09-03 실측 `SchedulerLockConventionTest.EXEMPT` · `ControllerAuthorizationConventionTest.METHOD_LEVEL_EXEMPT`/`EXEMPT_FILES` 에 개수 단언·근거 주석 강제가 **부재**. 이번에도 조율자 소유로 두고 좌석에는 배정하지 않는다(횡단 파일).

**Phase 12 이월 6건은 어느 것도 이 Phase 범위가 아니다** — 목표 표 §8. 이월 ⑥(ID 타입 `string`/`Long`)은 이 Phase 의 신설 응답도 같은 편차를 만들므로 **기존 관례(`Long`)를 따르고 문서 정합은 별도 항목**으로 둔다.

#### Phase 13 이월 9건 (2026-09-03 등재)

| # | 항목 | 다음 Phase 에서 확인할 것 |
|:-:|---|---|
| ~~**①**~~ | **`direction` 반대 방향 픽스처 부재** — `§5.16`·`§6.11` 응답의 `direction` 을 상수 `"to_academy"` 로 심어도 시험이 통과(R3 재판정에서 미해소). 시험 전부 `to_academy` 회차만 만든다 | **해소 — F1 S2 `81597ec`(2026-09-04). 정본에 `to_home` 값 부재 → 반대값 `from_academy` 픽스처, §5.16·§6.11 양쪽** (이전: 시험 강화 소단위 — `to_home` 회차 픽스처 1건) |
| ~~**②**~~ | **WS `emergency_raised` 페이로드 3필드 누락** — `API_SPEC §7.1` 의 `raised_by`·`position`·`rider_count` 가 `EmergencyBroadcastListener` 페이로드에 부재(T3 보고 · R3 대조) | **해소 — F1 S3 `60ef16f`·`8490bbb`. `raised_by{name,role,phone}`·`position{lat,lng}`·`rider_count` 추가, R3 7필드 대조. `rider_count` 는 정본 침묵 → 전체 라이더 수(기존 관례) 유지** (이전: 발주문이 "대조만 하고 보고" 였다. 구현 소단위로 배정) |
| **③** | **`*ConcurrencyTest` `TimeoutException` 부하 의존** — `BusRegistrationConcurrencyTest`·`RunStopProximityClaimConcurrencyTest`. 전체 실행에서만 실패, 단독 통과(Phase 8 부터 계속) | 계속 이월. 전체 실행 판정 시 단독 재실행으로 가른다 |
| **④** | **오픈 이슈 `X` — `§5.19 GET /staff/runs/{runId}/route` 미구현**(A-03·A-08·A-15 의 관계자용 확정 노선 조회). 목표 표가 "재사용하라" 고 적었으나 부재(Ruling 238) | 어느 Phase 소유인지 §9 에서 배정 |
| ~~**⑤**~~ | **`§6.8` `est_duration_min` null 경로 무시험**(T2 보고) — 예상 소요가 없는 회차의 `destination_eta` 처리 | **해소 — F1 S2 `076ad28`. 프로덕션은 이미 옳았고(Ruling 232 자바독) 시험만 추가** (이전: 시험 강화 소단위) |
| ~~**⑥**~~ | **X-08 — `§5.4` 타 학원 회차 `404` vs 정본 `403`**(Ruling 239) | **해소 — 2026-09-03 사용자 판정 ② `403` 정정, 커밋 `08e7878` (Ruling 240).** 후속 **해소 — F3 S4 `dc8efff`(2026-09-05, 메인 `14b0613`)**: `AcademyScopeHttpExhaustiveTest` 가 게이트 목록에서 경로 변수 핸들러를 런타임 추출해 47케이스(F3 S5 후 48) 표 구동 검사. 정본 위반 20건 발견 → Ruling 259 (a) 13절 문면 정정 · (b) 매니저 앱 회차 7건 코드 정정 |
| ~~**⑦**~~ | **`AcademyScopeRepositoryConventionTest` 자바독 "20개" vs 실제 21**(R1 §7) — 단언·리터럴은 21 로 일치, 자바독만 낡음 | **해소 — F1 S2 `0c4d96c`. ERD §6.1/6.2 실측 21·22 로 자바독·메서드명 정정** (이전: 주석 1줄 정정) |
| ~~**⑧**~~ | **`unassigned_managers` 재직 조건 축 회귀 방지력 부재**(R1 재판정 ⚠) — `deletedAt IS NULL` 을 빼도 통과. 시험·픽스처에 소프트 삭제 매니저가 없어서 | **해소 — F1 S2 `efaa80b`+`78f1eb6`. 첫 판은 픽스처가 오늘 회차를 안 만들어 검사 대상 쿼리 미실행 → 수정 라운드로 해소, R2 ⑧ 재심 1건 실패** (이전: 시험 강화 소단위 — 삭제 매니저 픽스처 1건 + `value(1)` 유지) |
| ~~**⑨**~~ | **`AdminMonitoringControllerTest` 위치 유실 경로 무시험**(조율자 경계 시험) — `STALE_THRESHOLD` 2→20분에 관계자·학부모 시험은 실패하는데 메인 관리자 `§6.8` 시험 9건은 전부 통과. 공용 `RunLiveStateResolver` 라 동작은 같으나 회귀 방지력 부재 | **해소 — **Ruling 250 으로 성격이 바뀜** — 시험 부재가 아니라 관리자 응답에 유실 규칙 자체가 없었다(이 표의 "동작은 같다" 는 틀린 서술). F1 S2 `29af43b` 구현 + `API_SPEC §6.8` `last_seen_at` 추가** (이전: 시험 강화 소단위 — `§6.8` 유실 회차 1건) |

**Phase 11 이월 ③(규약 시험 면제 목록 무검증)은 이 Phase 에서 해소** — 조율자 커밋 `fb9f06d`(면제 개수 고정 `hasSize(5)`·`EXEMPT_FILES` 빈 목록 + `containsAll` 실재 검사). 좌석 리뷰 R1 은 분기점이 앞서 미확인으로 남겼고, **병합 후 조율자가 직접 게이밍 변형을 심어 그 시험만 실패(1/9)함을 실증**했다. **Phase 11 이월 ②(X-06 `no_show_wait_minutes` 상한)** 와 **Phase 9 이월 ⑤(T2 수정 라운드 3 재리뷰 미부착)** 는 계속 남긴다 — 이 Phase 는 수정 라운드마다 재판정을 붙였다(R3 1회 · R1 1회, T2 는 주석 1줄이라 diff 확인으로 대체).

---

### Phase 14 — 감사 · 보존 정리 · 운영 게이트

| 항목 | 내용 |
|---|---|
| **범위** | `SYS-01·02` · `O-04` · `ERD §7` 보존 정책 · `TECH_DECISIONS §12`~`§14` |
| **선행** | Phase 13 |
| **기능 ID** | SYS-01·02 (P1) |
| **참조** | `ERD §7` · `TECH_DECISIONS §12.1`·`§13` · `ARCHITECTURE §13.2` |
| **산출물** | `audit` 모듈 · 보존 정리 배치 · 배포 게이트 · 관측 지표 · 런북 |

**L3 필드 조회는 감사 로그에 기록** — 누가·언제·어느 학생의 어떤 등급을 봤는지 (`FEATURE_SPEC §6.3`).

⚠ **정리 배치가 없으면 위치·알림 테이블이 무한 증가.** 위치는 회차당 수백 행이 쌓임 (`ARCHITECTURE §13.2`).

**배포 게이트** — `run.status='moving'` 이 하나라도 있으면 배포 보류. 사람의 기억에 맡기지 않고 파이프라인에 넣음 (`TECH_DECISIONS §12.1`).

**완료 조건**
- L3 필드 조회 시 감사 로그 행이 생성
- 로그인·차단 이력 조회 (메인 관리자만)
- 보존 기간 초과 알림·위치 이력이 정리 배치로 삭제
- `TECH_DECISIONS §13.1` 의 지표가 `/actuator/prometheus` 에 전부 노출
- 배포 스크립트가 `moving` 회차 존재 시 중단
- 런북 문서화 — 실패 모드별 수동 개입 경로

**착수 전 판정 (2026-09-03 조율자, 계획 수립) — 목표 표 `docs/archive/sdd/IMPLEMENTATION_PLAN/p14-goal-table.md`(11항) · 좌석 발주문 `p14-task-t1/t2/t3.md` · 재개 `p14-resume.md`**

| Ruling | 판정 | 근거 | 처리 |
|:-:|---|---|---|
| **241** | ⚠ **완료 조건 재계수 — 정본 6항 + 신설 4항 + 전체 실측 = 11항.** 정본 6항 어디에도 `§6.13` 핸들러 2개(`GET /admin/audit-logs`·`/admin/login-history`)의 응답·쿼리·에러 문면, 로그인 성공·실패·차단의 **기록** 경로, 만료 토큰·코드 정리(`ERD §7.1`), 게이트 개수(98 → 100)가 부재 | `API_SPEC §6.13` · `ERD §7.1` · Phase 10~13 과 같은 형태(범위 열과 완료 조건을 항목 단위로 마주 놓아야 빈칸이 보인다) | 목표 표 §1 |
| **242** | ✅ **L3 감사 단위 = 요청 1건 1행 (2026-09-04 사용자 확정 — 잠정 그대로).** `target_type` 자원 종류 · `detail.student_ids`·`fields`. 서비스 층 기록, 조회 트랜잭션과 분리. 학부모 자녀 목록도 대상 | `SYS-01` 문면("어느 학생의 어떤 등급")은 단위를 안 정했다. 학생마다 1행이면 명단 1회 = 30행. `TECH_DECISIONS §13.2`(값 대신 식별자) | 확정. 좌석 발주문 그대로 |
| **243** | ✅ **보존 정리는 행 단위 DELETE 배치, 파티셔닝은 이번에 안 한다 (2026-09-04 사용자 확정 — 잠정 그대로).** 컷오프 — `notification_log` 14일(정본) · `run_position` **90일**(잠정, **X-09 신설**) · `refresh_token` 만료·폐기 후 30일(잠정) · `link_code`·`link_request` 만료 즉시 · `audit_log` 삭제 부재. 상한으로 나눠 지운다. `V8` 은 인덱스만 | `ERD §7.2` 가 `run_position`·`audit_log` 를 미확정으로 둠. `§7.3` 파티셔닝은 `V1` 테이블 재생성이 필요하고 실사용 전환(R7 법정 검토)과 묶는 것이 맞다. 현재 적재량(dev DB 3행)에서 파티션 이득 부재 | 확정. X-09 = **90일 확정**(법정 검토 시 재조정 여지만 존치). `RetentionPolicy` 자바독에 "2026-09-04 사용자 확정" |
| **244** | 🔴 **강제 확정 콘솔 개입(`TECH_DECISIONS §14.3`)의 API 가 `API_SPEC` 에 부재 → 오픈 이슈 `Y` 신설.** 이 Phase 는 엔드포인트를 만들지 않고 런북에 "수단 없음 — Y 판정 대기" 로 적는다 | `PRD §0` — 설계 문서 3종은 사양이 아니다. 사양 4종에 없는 엔드포인트를 설계 문서만 보고 만들면 정본 밖 API 가 생긴다 | §9 열린 항목에 `Y` 등재 |
| **245** | **배포 게이트 위치 = EC2 의 `infra/scripts/deploy.sh`(이미지 pull 앞), 별도 `deploy-gate.sh` 로 분리해 로컬 실증** | 워크플로는 SSM 으로 `deploy.sh` 를 부른다 — GitHub 러너에서 DB 에 닿을 수 없다. 스크립트 분리는 시험 가능성 때문 | 목표 9 |
| **246** | **좌석 3개 병렬 — 파일 축.** T1 감사(핸들러 2·게이트) · T2 보존 정리(`V8`·리포지토리 삭제 메서드) · T3 지표·게이트·런북(`docs/infra/DEPLOYMENT.md` 는 메인 저장소 직접). 같은 파일을 두 좌석이 고치는 자리 0 | `p14-goal-table.md §4` 겹침 점검 | 발주 |
| **247** | 🔴 **Ruling 242 정정 — L3 감사 대상은 5종이 아니라 4종 (2026-09-04, R1 반려 신고).** `ChildListResponse`(학부모 자녀 목록)는 필드가 `student_id`·`name`·`class_name`·`linked_at` 전부 **L1** 이고 자바독이 *"`photo_url` 이 부재한 것이 사양이다(§1.12 · ERD student)"* 라 명시 — L3 를 싣지 않으므로 `SYS-01` 감사 대상 밖. 조율자가 목표 표를 쓸 때 `grep` 이 자바독 본문의 낱말(`note`)을 필드로 오인했다 | R1 판정문 §2 + 조율자 코드 확인(`ChildListResponse.java:11·31`) | **🔴 → 해소(판정 정정).** 대상 4종 = `StudentDetailResponse`·`StaffRosterItemResponse`·`AdminRunRosterResponse`·`ManagerRosterResponse`. 목표 표 §1 1번 정정. R1 의 ⚠ 3건(`StudentQueryService.detail()`·`AdminRunRosterQueryService.roster()` 감사 호출 전담 시험 부재 · `§6.13` 응답 JSON 키 문자열 무단언)은 **T1 수정 라운드 1 → R1 재판정** |
| **248** | 🔴 **T2 `RetentionCleanupSchedulerTest` 의 2032년 고정 시계가 공유 DB 시드를 지운다 (2026-09-04, 최종 실측 신고).** 실제로 행을 지우는 배치를 미래 고정 시계로 돌려 컷오프가 밀리고 시드 전부(`notification_log` 10 · `run_position` 3 · `link_code`·`link_request` 1)가 삭제 → 같은 DB 에서 뒤에 도는 `NotificationOutboxWorkerTest` 3건이 시드 부재로 실패(새 DB 단독 7/7 통과로 DB 상태 문제임을 가름). R2 는 좌석 범위 시험만 돌려 못 봤다 | 조율자 실측 — `schoolbus_p14final` 행 수 대조 · 새 DB `sb_p14_chk` 에서 정리 → 아웃박스 순 실행 재현 | **조율자 편집 `4933372`** — 고정 시계 제거, 앱 `Clock`(실제 시각) 사용. 심는 행이 전부 `now` 상대라 판정은 여전히 결정적. **⚠ 규칙화 — 실제 삭제를 하는 시험은 고정 시계를 쓰지 않는다**(`p14f-common.md §4`). 이 편집은 리뷰를 건너뛰었으므로 F1 S1 리뷰 대상에 명시 |
| **249** | **이월 소단위 묶음 F1 신설 — Phase 14 이월 ①~⑤ · Phase 13 이월 ①②⑤⑦⑧⑨ · 오픈 이슈 X(`§5.19`)를 좌석 3개(S1 관측·보존 시험 강화 / S2 관제·비상 응답 시험 강화 / S3 구현 2건)로 병렬 배정 (2026-09-04, 사용자 지시 "병렬 가능하면 에이전트 팀").** X 는 정본 `API_SPEC §5.19` 가 응답·권한·에러를 전부 정하고 있고 `RunRouteQueryService`·`Assignment.ackedRouteVersionId` 가 실재해 소단위로 닫을 수 있다(조율자가 경로 확인). W 는 `I` 판정 선행이라 제외 · Y 는 정본 미기재라 제외 | `p14f-goal-table.md`(12항 · §0 재계수 표 · §3 파일 분할 겹침 0) | 분기점 `4933372`. 게이트 90 → **91**(S3 의 `GET /staff/runs/{runId}/route` 1개) · `hasSize(101)`. 리뷰 3좌석 + 조율자 편집 `4933372` 를 S1 리뷰 대상에 명시 |
| **250** | 🔴 **`§6.8` 메인 관리자 관제 응답에 위치 유실 규칙이 빠져 있다 (2026-09-04, F1 S2 BLOCKED 신고).** `FEATURE_SPEC §4.16` "MON-07·**A-14** live 스냅샷 규칙" 은 관리자 관제에도 2분 초과 시 위치를 비우고 마지막 확인 시각만 제공하라고 하는데, `AdminAcademyLiveQueryService.toRun()` 은 `liveState.stale()` 을 쓰지 않고 Redis 미기록일 때만 `position=null`, `AdminAcademyLiveResponse` 에 `last_seen_at` 부재. `API_SPEC §6.8` 도 그 필드가 없다. Phase 13 이월 ⑨ 의 "공용 `RunLiveStateResolver` 라 동작은 같다" 는 **틀린 서술**이었다(공용 클래스는 있으나 관리자 경로가 그 판정을 안 쓴다) | S2 코드 확인 + 조율자 정본 대조(`FEATURE_SPEC` 740행 · `API_SPEC §6.8` · `§5.18`) | **정본 정정 — `API_SPEC §6.8` 에 `last_seen_at`(○) 추가(조율자).** **코드 — F1 S2 목표 9 를 시험 강화에서 구현으로 바꿔 배정**(`monitoring/**` 프로덕션 허용): 유실 회차 `position=null` + `last_seen_at`, 판정은 `RunLiveStateResolver`/`STALE_THRESHOLD` 공용. 변형 `STALE_THRESHOLD` 2→20분에 관리자·관계자·학부모 시험이 **함께** 실패해야 한다 |
| **251** | ⚠ **F1 목표 1 문면("1회 후 1, 2회 후 0")이 구현과 어긋났다 (2026-09-04, S1 신고).** `RetentionCleanupScheduler.cleanUpTable()` 은 한 틱 안에서 `do-while` 로 상한 회차를 이어 돌아 `BATCH_SIZE+1` 행이 1회에 전량 삭제된다. 게다가 **행 수 단언은 상한 제거(`Limit.unlimited()`)를 못 가른다** — S1 이 직접 심어 행 수 시험 6건이 전부 초록임을 실측. 조율자가 `p14-goal-table §3.3` 의 "2회 실행 뒤 0" 을 "1회 후 1" 로 옮겨 적으며 구현을 안 봤다 | S1 보고서 ①③④ · `RetentionCleanupScheduler` 자바독 | **목표 1 정정** — 실제 DB + 저장소 spy 로 "1회 후 0 **그리고** 상한 조회가 `Limit.of(BATCH_SIZE)` 로 ≥2회" 를 검증. 이월 ① 의 본체("통합 경로에서 상한 배선이 살아 있는가")는 유지하고 수치만 구현에 맞춤. **판정 근거를 옮겨 적을 때 구현을 한 번 열어 본다**(`phase-goal-loop §6.3` 형태) |
| **252** | **이월 소단위 묶음 F2 신설 + 판정 4건 (2026-09-04, 사용자 위임 "네가 맞다고 생각한 대로").** 대상 = F1 결과 ①~⑦ + docgraph 깨진 참조 161건. 좌석 3(S1 시험 보강 / S2 코드 정리·자바독 / S3 정본 문서). **a** Phase 14 이월 ③ 은 `2be7c05` 로 이미 해소(낡은 "없음") **b** `rider_count` = 회차 배정 라이더 전원 수(상태 무관) — 비상 시 차에 없는 아이도 찾아야 하므로 전원 수가 안전 쪽, 코드 변경 0 **c** 옛 Ruling 31개는 `docs/` 가 git 밖이라 원문 복원 불가 → 인용 문장 요지로 "옛 Ruling 색인" 절(§1.1 아래)에 색인, 코드 인용 유지 **d** W·Y·L2·법정은 정본 문면이 먼저인 사용자 결정이라 범위 밖 | 분기점 `fa8705d` · 병합 `4a4ecba` · 근거 `f2-goal-table.md §3` |
| **253** | **오픈 이슈 I 해소 — 지연 알림 중복·갱신 규칙 (2026-09-04, 사용자 위임 "남은 작업 전부 진행, 선택은 네 판단" 에 따른 조율자 판정).** 지연 알림은 **갱신** 의미("현재 예상 지연 N분", 합산 아님). 같은 회차에서 직전 발신과 `minutes`·`reason`·`message` 가 전부 같으면 `409 DELAY_DUPLICATE`, 하나라도 다르면 새 알림. 저장은 전용 테이블 `delay_notice`(V9 — `ERD` 각주가 예고한 그대로). **시간 상수를 지어내지 않는다**(§5.3 원칙). 버린 길 — 누적(합산, 수신자 오도) · `notification_log.body` 문자열 비교(`message` 가 자유 문장). 이로써 **W(NTF-06) 를 F3 S1 에 배정** | 정본 반영 `API_SPEC §4.9` · `ERD §3.4 delay_notice` · `FEATURE_SPEC` NTF-06·X-05 · `PRD §10.1` I (F3 S3) · 근거 `f3-goal-table.md §3-a` |
| **254** | **오픈 이슈 Y 해소 — 강제 확정 콘솔 개입 API 정본 문면 (2026-09-04, 같은 위임).** `API_SPEC §6.14 POST /admin/runs/{runId}/force-confirm` 신설(끝에 붙여 기존 번호 불변). 메인 관리자 전용 · `reason` 필수 · 전제 회차 `idle` + `confirm_at` 경과 · 폴백(직선거리) 강제 확정 → `confirmed`, 기존 확정 후속 동일 · 응답 `201` `run_id`·`route_version_id`·`fallback_used`·`confirmed_at` · 에러 `404`·`409 RUN_NOT_IDLE`·`409 RUN_NOT_DUE`·`422` · 감사 `audit_log` `run.force_confirm`(reason·fallback_used). 버린 길 — `confirm_at` 미도래 허용(배치를 앞질러 확정하면 ①구간 토글이 막혀 C-04 와 충돌) · "강제 종료" 확장(`TECH_DECISIONS §14.3` ⚠ 금지). **구현은 F3 S2(파도 2)** | `TECH_DECISIONS §14.3` 표 1행 · 근거 `f3-goal-table.md §3-b` |
| **255** | **프론트엔드(F1~F4)·법정 요건(L-01~09) 영구 범위 밖 (2026-09-04 사용자 결정 "앞으로 계속 고려하지 않는다").** 오픈 이슈 P·Q·H(프론트 의존)와 §7.1 운영 전환 행을 닫는다. 이후 "MVP 완료" 의 정의 = **백엔드 정본(`docs/` 7종)의 기능이 전부 구현·검증된 상태**. F3 목표 16(법정) 삭제 | 근거 사용자 대화 · `f3-goal-table.md §3-d·e` 정정 |
| **259** | 🔴 **X-08 후속 전수 검사에서 정본 위반 20건 발견 (2026-09-05, F3 R4 판정 → 조율자 판정, 사용자 위임).** 두 갈래로 가른다. **(a) 관계자 웹 CRUD·학부모 intent runId 축·비상 확인·예외 보고 13건** — 코드는 학원 조건을 쿼리에 고정해 타 학원 자원을 `404` 로 답한다(Ruling 163 의 의도된 설계 — 존재 비노출). 정본 여러 절(`§5.12`·`§5.15` WAYPOINT 등)이 이미 `404(타 학원)` 을 명시하므로 **침묵한 절의 문면을 "(대상 부재 · 타 학원)" 으로 정정**하고 `§1.5` 통칙에 예외 문장("학원 조건을 쿼리에 고정하는 관계자 웹 자원은 `404` 로 존재를 드러내지 않는다 — Ruling 163")을 추가. `§8.4` 중앙 표에 `REPORT_NOT_FOUND` 행 추가. 코드·시험 불변. **(b) 매니저 앱 회차 7건**(`GET/PATCH/POST /runs/{runId}/…` 6 + `DELETE /runs/{runId}/emergency/{id}`) — `§1.11`·`§4.14` 가 "배치되지 않은 회차 → `403 FORBIDDEN`" 을 **명시**하는데 `ManagerRunAccess.requireAssignedRun` 이 존재 판정을 먼저 해 `404` 를 낸다. F3 S1 의 지연 알림이 따르는 `RunAssignmentAccess`(배치 판정 선행)와 순서가 반대. **코드를 정본에 맞춘다** — 배치 판정 선행으로 통일, 타 학원·미존재 회차 모두 `403 FORBIDDEN`. 시험 기대값 7건 정정 | R4 판정문 `review-f3-r4.md` ①표 · 근거 Ruling 163·240 · 버린 길 — (b) 를 정본 정정으로 닫기(명시 문면을 코드에 맞추는 것은 `phase-goal-loop §3` "테스트를 고쳐 통과" 형태) · (a) 를 코드 정정으로 닫기(13개 서비스 변경, Ruling 163 설계를 뒤집음) |
| **260** | **강제 확정 감사 기록의 `action` 도메인 (2026-09-05, F3 S2 신고 → 조율자 판정, 사용자 위임).** `§6.14` 문면 `action=run.force_confirm` 이 `ERD audit_log.action` CHECK 7종·`AuditAction` enum·`LowerCaseEnumConverter`(점 문자열 생성 불가)와 충돌. **`category=data_access`·`action=update`·`target_type=run`·`target_id=runId` 재사용 + `detail{action:"run.force_confirm", reason, fallback_used, route_version_id}`.** `§6.14` 문면 정정 | 버린 길 — 도메인 확장: V10/V11(F4 선점) 재배정 · ERD CHECK · `§6.12` 감사 조회 action 투영까지 번짐. `update`+`target_type=run` 이면 같은 감사 화면에 나타나고 `TECH_DECISIONS §14.3` 의 "누가·언제·왜·폴백" 은 detail 로 전부 남음 |
| **256** | **RTE-07 `POST /staff/students/{id}/transfer` 정본 문면 확정 · `[조정 중]` 해제 (2026-09-05, F4, 조율자 판정, 사용자 위임).** ①구간 전용 · `from_run_id`·`to_run_id`(같은 학원·날짜·방향) · `stop_id`/`address` 배타 필수 · `run_transfer`(V10) 행 `staged` 저장만, 재최적화 미호출 — 각 회차 확정 배치가 출발 제외·도착 추가를 반영 · `409 STUDENT_NOT_IN_RUN`·`CAPACITY_EXCEEDED`·`TRANSFER_ALREADY_STAGED` | `f4-goal-table.md §3-a`. `§5.7`(Ruling 198)·UF-M-04 가 이미 "대기 저장 → 확정 배치 반영" 을 정함. 버린 길 — 즉시 재최적화(①구간 회차는 `idle` 이라 대상 부재) · 명단 직접 수정(`run_rider` 는 확정 산출물) |
| **257** | **X-06 `no_show_wait_minutes` 상한 = 30분 (2026-09-05, F4, 조율자 판정, 사용자 위임).** 요청 검증 `422` + DB CHECK `> 0 AND <= 30`(V11) 둘 다 | 사양의 유일한 분 단위 상수 "출발 30분 전" — 대기가 확정 창을 넘기면 운행과 겹쳐 의미 상실. 버린 길 — `est_duration_min` 기준 동적 상한(설정 시점에 회차 부재) · 상한 부재 유지 |
| **258** | **X-07 승차·하차 알림은 학부모 전용 유지 (2026-09-05, F4, 조율자 판정, 사용자 위임).** `API_SPEC §9.7` 이 이긴다 — S-03 문면 정정, 코드 변경 0 | `§9.7` 은 수신자를 행마다 명시한 표라 정밀, S-03 은 "P-09 와 동일" 로 복사된 문장. 버린 길 — 학생에게도 발송(정보 가치 없음·알림 과다·`boarding` 토글 의미 확장) |

#### Phase 14 이월 6건 (2026-09-04 등재) — ①~⑤ 는 **이월 소단위 묶음 F1**(`p14f-goal-table.md`) 로 즉시 배정

| # | 항목 | 다음 단위에서 확인할 것 |
|:-:|---|---|
| ~~**①**~~ | **배치 상한 통합 시험 부재** — `RetentionCleanupSchedulerBatchCapTest` 는 Mockito 인자 캡처. 상한을 무시하고 전량 삭제해도 통합 시험은 못 가른다(R2) | **해소 — **Ruling 251 정정** — "1회 후 1" 은 `do-while` 구현과 어긋나고 행 수 단언은 상한 제거를 못 가른다(S1 실측). F1 S1 `3effbec` — `@MockitoSpyBean` 으로 `Limit.of(BATCH_SIZE)` ≥2회 호출 검증, 변형 시 2건 실패** (이전: F1 S1 목표 1 — 상한+1 행 실제 삽입, 1회 후 1 · 2회 후 0) |
| ~~**②**~~ | **`RunUnconfirmedGaugeScheduler` 상태값 사각지대** — 술어 `IDLE`→`CONFIRMED` 변형 생존(R3). 픽스처에 `idle` 회차가 없다 | **해소 — F1 S1 `3ae48e8`. idle 대상 2건으로 우연의 1:1 을 깸, R1 변형 ② 1건 실패** (이전: F1 S1 목표 2) |
| **③** | **`ScheduledTaskMetricsAspect` 재작성 근거 미기록** — Spring Framework 7 `OutcomeTrackingRunnable` 대응(+56/−9)이 T3 보고서에 없고 R3 판정문에만 있다 | **해소 — Ruling 252-a (2026-09-04).** 자바독은 Phase 14 T3 커밋 `2be7c05` 34~39행에 이미 있었다(`OutcomeTrackingRunnable` 대응 근거). 원장의 "없음" 이 낡은 것 — `phase-goal-loop §6.3` 형태. 코드 변경 0 |
| ~~**④**~~ | **WS 발행 타이머 실패 시 미기록 + 카운터 3종 전용 시험 부재** — `WebSocketPublishMetrics`(R3 의 "게이트웨이 타이머") | **해소 — F1 S1 `8e169ff`. `WebSocketPublishMetrics` 자바독 + `DomainEventCounterWiringTest`(실제 스케줄러/워커 경로), R1 3곳 변형 각 1건 실패** (이전: F1 S1 목표 3) |
| ~~**⑤**~~ | **`AuditLogQueryService.academyNamesOf()` `null` academyId NPE** — R1 재판정: 호출부 4곳 전부 `academyId` null 불가 → **프로덕션 도달 불가**, 방어 목적 | **해소 — F1 S1 `62dda03`. `academyNamesOf()` 방어 + `AuditLogQueryServiceTest`(서비스 계층 한정 — 컨트롤러 경로 미검증은 R1 ⚠)** (이전: F1 S1 목표 4(낮은 우선순위, 방어만)) |
| ~~**⑥**~~ | **동시성 시험 `TimeoutException` 부하 의존** — Phase 8 부터 계속. Phase 14 에서 4클래스, 이후 전체 실행마다 2~5클래스가 회차별로 다르게 실패, 단독 재실행은 통과 | **해소 — F5 S3 `a4856ef`(2026-09-05, 메인 `13ad198`).** 원인은 부하가 아니라 **시험 자체의 결함**: 잠금 대기 폴링이 자기 트랜잭션 안에서 `pg_stat_activity` 를 읽는데 PostgreSQL 이 그 뷰를 트랜잭션 동안 캐시(`stats_fetch_consistency=cache`)해 첫 스냅숏을 재사용 → 상대의 잠금 대기를 영원히 0 으로 읽음 → 예산 소진 → 바깥 `Future.get` 이 먼저 만료. 정정 = 폴링마다 `pg_stat_clear_snapshot()`(8클래스). 증거: 실행 중 `pg_stat_activity` 기록 · psql 독립 재현 · Phase 10 의 45초 증액이 45.1s 에서 실패(느림 가설 반증) · 되돌림 재현 3클래스. R1 이 Postgres 에서 독립 재현. **전체 실행 4회 연속 0 실패**(S3 2회 · 조율자 2회, `schoolbus_f5_final`). `TIMEOUT_SECONDS` 불변 |

**Phase 13 이월 ①⑤⑦⑧⑨ 와 ②(WS `emergency_raised` 3필드) · 오픈 이슈 X(`§5.19`)도 F1 에 함께 배정** — Ruling 249 참조.

#### 이월 소단위 묶음 F1 결과 (2026-09-04 등재, Ruling 249) — 남은 이월 7건

병합 S2 `963c750` → S1 `03fa179` → S3 `fa8705d`(메인). 리뷰 R1·R2·R3 전부 승인(🔴 0). 판정문 `review-p14f-r1/r2/r3.md`.

| # | 항목 | 다음 단위에서 확인할 것 |
|:-:|---|---|
| **①** | **`position` 스냅샷 회귀 방지력 부재**(R3 ⑩c) — `EmergencyCommandService` 의 position 을 null 상수로 심어도 통과 | **해소 — F2 S1 `f9410c1`.** `EmergencyRaisedBroadcastIntegrationTest` 에 Redis 캐시 좌표 심고 방송 `position.lat/lng` 값 단언, R1 변형(`null/null`·`ZERO/ZERO`) 각 1건 실패 |
| **②** | **`RunRouteQueryService.buildFromVersion` 공용 실증 편측**(R3 ⑪e) — stops `.skip(1)` 에 §5.19 시험만 실패, §4.3 매니저 시험은 통과 | **해소 — F2 S1 `e404427`.** `RunRouteControllerTest` 정상 조회 시험(`stops[0].stop_id`·`length()`), `.skip(1)` 변형에 단독 실패(R1 단독 실행 확인) |
| **③** | **`direction` 소문자 변환 중복**(R2 ⚠) — `EmergencyStaffQueryService`·`AdminEmergencyQueryService` 가 각자 계산, 공용 헬퍼 없음(F1 이전부터) | **해소 — F2 S2 `66f84c5`.** `global/common/LowerCaseFormatter` 정적 헬퍼로 두 서비스 공용화(`LowerCaseEnumConverter` 는 JPA 변환기라 부적합 — 보고서 1항). identity 변형에 컨트롤러 4건 실패(R2). ⚠ `lower(null)` 단위 시험 부재 → F2 결과 ② |
| **④** | **목표 4 감사 조회 null 방어 시험이 서비스 계층 한정**(R1 ⚠) | **해소 — F2 S1 `3e7bf94`.** `AuditQueryControllerTest` 에 `academy_id` null 행 → `200` + `academy_name` JSON null(`NON_NULL` 변형으로 부재/null 구별 확인, R1) |
| **⑤** | **`TECH_DECISIONS §13.1` 에 카운터 리터럴 문자열 부재**(R1 ⚠) — 이름 일치를 정본으로 검증 불가 | **해소 — F2 S3 (2026-09-04).** `TECH_DECISIONS §13.1` 에 "지표 이름" 열 신설, 7행에 `schoolbus.*` 병기. 지도 API 행은 코드에 지표 부재라 `—` |
| **⑥** | **`rider_count` 정의 정본 침묵**(R3 ③) — "탑승자 수" 만 있고 상태 필터 없음. 구현은 전체 라이더 수(Phase 11 관례) | **해소 — Ruling 252-b (2026-09-04, F2 S3).** `API_SPEC`(2곳)·`ERD` 를 "발신 시점 회차에 배정된 라이더 전원 수(승하차 상태 무관)" 로 명시. 구현 변경 0 |
| **⑦** | **`AuthFlowIntegrationTest` `.as(...)` 설명 앞머리 리터럴 "96개" 가 낡음**(S3 신고 → R3 재확인: 항목별 합은 101 로 정확, 앞머리 숫자만 F1 이전부터 드리프트) + Phase 14 이월 ③(`ScheduledTaskMetricsAspect` 자바독) + `p14f-task-s1.md` 의 절 번호 오기(문서명 없이 적어 엉뚱한 절을 가리켰다) | **해소 — F2 S2 `42a335e`(101개, 합계 재계수) · `67bc570`(자바독 절 번호 19건) · Phase 14 이월 ③ 은 Ruling 252-a(코드 변경 없음) · `p14f-task-s1.md` 의 배치 상한 절 인용 삭제(S3)**. 잔여 코드 인용 11건은 F2 결과 ① |

⚠ **조율자 실수 2건 기록** — ① 목표 1 문면을 옮겨 적으며 구현(`do-while`)을 안 봤다(Ruling 251) ② 발주문에 `rider_count` 를 "탑승 중" 으로 못 박았으나 정본은 침묵 — **발주문의 "판정 고정" 은 정본 문면을 인용할 때만 쓴다.** 좌석의 결함 심기는 하네스 분류기가 "호출을 리터럴로 바꾸는" 편집을 막았고 "주석 처리 + 대체 줄" 은 통과 — 발주문 §3 에 형식을 명시한다.

#### 이월 소단위 묶음 F2 결과 (2026-09-04 등재, Ruling 252) — 남은 이월 5건

병합 S1 `ac0fb52` → S2 `4a4ecba`(메인). 리뷰 R1·R2 승인(🔴 0 · ⚠ 3), S3(문서)는 조율자가 `f2-docs-before` diff 전부 대조. 판정문 `review-f2-r1/r2.md`. 발주문·판정 `f2-goal-table.md`(§3 Ruling 252).

| # | 항목 | 다음 단위에서 확인할 것 |
|:-:|---|---|
| **①** | **docgraph 미색인 문서 인용 11건** — 코드 자바독이 `docs/backend/CODE_CONVENTIONS.md` 의 SRP·크기 기준 절(8건, 코드 컨벤션 — gitignore 라 워크트리에 없어 S2 가 "부재" 로 오신고) · `p1-entity-conventions.md §4.4`(3건)을 인용. 인용 자체는 유효 | docgraph 가 두 문서를 색인하게 `config.json` `docs_globs` 확장(도구 세션) — 코드는 손대지 않음 |
| **②** | **`LowerCaseFormatter.lower(null)` 회귀 방지력 부재**(R2 ⚠) — null 무방어 변형이 0 실패로 생존 | 단위 시험 1건(`null → null`) |
| **③** | **`LinkedChildLookup` 의 "연결 없음 → 403, 퇴원 → 404" 순서 규칙 1차 출처가 정본에 없음**(S2 신고 → R2 ⚠). 자바독은 `API_SPEC §3.7` 로 정정 | `API_SPEC §3.7` 에 순서 규칙 한 줄(문서 소단위) |
| **④** | **관계자 경유 지점 해제(웨이포인트 DELETE) 엔드포인트 정본 공백**(S3 신고) — 코드에 실재, `API_SPEC §5.15` 에 정식 헤더·표 행 부재 | `§5.15` 에 행 추가(헤더 신설 여부는 사용자 결정) |
| **⑤** | **`RedisTestContainerBase.REDIS` 정적 공유 근거 미명시**(R1 ⚠) — 같은 JVM 포크 안에서 클래스 간 컨테이너 공유, 실위험은 `runId` 단조 증가로 0 | 베이스 클래스 자바독에 근거 1줄 |

**docgraph 잔여 깨진 참조는 도구 한계 4종이 전부** — RefState 문서명 전이 · 엔드포인트 정의를 표/헤더로만 인식 · ID 정규식이 흐름 ID 안의 예외 ID·해시 이름·ACME 챌린지 이름을 잡음 · 위 ① 미색인. 정본 쪽 오기는 S3 가 7건 전부 정정(문서 출처 48건 잔여 = 전부 오탐, `report-f2-s3.md`). Phase 14 이월 ⑥(동시성 `TimeoutException`)은 계속 이월.

---

#### 이월 소단위 묶음 F3 결과 (2026-09-05 등재, Ruling 253·254·259·260) — 남은 이월 = F4 로 이관

병합 S1 `eacf545` → S4 `14b0613` → S2 `7d25605` → S5 `4da0076` → L `1a6cef8`(메인). 리뷰 R1·R4·R2 전부 재판정 후 승인. S3(문서)는 조율자가 `f3-docs-before` diff 대조. 발주·판정 `f3-goal-table.md`(§3), 재개 기록 `f3-resume.md`. **목표 15항 전부 통과** — 최종 전체 실행(`4da0076`, DB `schoolbus_f3_final`) **217클래스 1238테스트 실패 2**(`AssignmentConcurrencyTest`·`RunConfirmationConcurrencyTest` `TimeoutException` — 1차 실행과 다른 클래스라 부하 의존 확정, Phase 14 이월 ⑥ 계속) · 게이트 **93/103** · docgraph 코드 출처 `missing_section` 0.

| 닫은 것 | 근거 |
|---|---|
| §9 **W**(지연 알림) · **Y**(강제 확정) · **I**(중복 규칙) · X-08 후속(격리 전수) · F2 결과 ①~⑤ | 위 §9 행 · Ruling 253·254·259·260 |
| 부하 L1·L2 | `§5.6` 결과 표 |

**합류 결함 4건(S5)** — S1·S2·S4 가 각자 워크트리에서 초록이었는데 병합 후 규약·전수 시험(`ControllerAuthorizationConventionTest`·`RolePermissionsTest`·`AcademyScopeRepositoryConventionTest`·`AcademyScopeHttpExhaustiveTest`)이 새 항목을 셌다. ⇒ **좌석 실행 명령에 `global.security.*` 를 항상 포함**(F4 발주문 반영).

| # | F4 로 이관 | 출처 |
|:-:|---|---|
| ① | 지연 알림 관계자·학생 채널 적재 독립 시험 · 공백 `message` 정규화 시험 | R1 재판정 ⚠ |
| ② | `RUN_NOT_IDLE` 의 `moving`·`finished`·`canceled` 직접 시험 · 배치 경로 `forceFallback=false` 기본값 회귀 시험 | R2 ⚠a·b |
| ③ | `RunAssignmentAccess` 자바독의 "호출부가 학원으로 좁힌 runId" 전제 정정 | R4 재판정 ⚠ |
| ④ | 부하 관측 공백 3건(WS 메아리 · 온디맨드 지연 대역 · 격벽 caller 지표) | L ② |

---


#### 이월 소단위 묶음 F4 결과 (2026-09-05 등재, Ruling 256·257·258) — 백엔드 정본 공백 5건 종결

병합 S2 `f745f4d`·S4 `80f7b92` → `84749f3` → S1 `291d73c`·`9a451a3` → **`17cd9ea`**(메인). 리뷰 R1(S1, 재판정 승인)·R2(S2+S4 합동, 승인). S3(문서)는 조율자가 `f4-docs-before` diff 대조. 발주·판정 `f4-goal-table.md`(§3), 진행 기록 `f3-resume.md` 끝 절. **완료 조건 10항 전부 통과** — 최종 전체 실행(`17cd9ea`, DB `schoolbus_f4_final`) **218클래스 1261테스트 실패 2**(`AssignmentConcurrencyTest`·`ChildLinkConcurrencyTest` `TimeoutException`, 부하 의존 — Phase 14 이월 ⑥ 계속) · 게이트 **94/104** · 권한 상수 **33종** · ERD 테이블 **43** · docgraph 코드 출처 `missing_section` 0.

| 닫은 것 | 근거 |
|---|---|
| RTE-07 `POST /staff/students/{id}/transfer`(`§5.8` `[조정 중]` 해제 · V10 `run_transfer` · 확정 배치 양쪽 반영) | Ruling 256 · S3·S1 |
| X-06 상한 30분(도메인 검증 422 + V11 CHECK — R2 가 원시 UPDATE 로 CHECK 작동 확증) | Ruling 257 · S3·S2 |
| X-07 학부모 전용 유지(S-03 문면 정정, 코드 0) · N `run_started` 문구 승격 · `§5.14` 낡은 문장 · 🔸 0건 · `§10 [조정 중]` 표 비움 | Ruling 258 · S3 · §9 행 |
| F3 이월 ①②③(시험 보강 4건 · `RunAssignmentAccess` 자바독) | S4·S2 |

**합류 결함 재발 2건(S1 라운드 2)** — `global.security.*` 를 넣고도 `EnumCheckConstraintParityTest`·`SchemaContractTest`(`global.common.enums.*`·`testsupport.db.*`)가 새 테이블을 셌다. ⇒ 마이그레이션을 만드는 좌석은 그 두 패키지를 예외 없이 실행 범위에(전역 규칙 `parallel-agents-git.md §18`).

**"MVP 완료" 판정(Ruling 255 기준 = 백엔드 정본 기능 전부 구현·검증)** — 정본 `docs/` 7종에 미구현·미판정으로 남은 기능 ID·오픈 이슈 **0**(§9 열린 항목은 프론트·법정·외부 환경 제약뿐). 아래 F5 후보는 기능이 아니라 **회귀 방지력·관측 공백**이다.

| # | F5 후보(미착수) | 출처 |
|:-:|---|---|
| ① | 확정 배치 반영 순서(강제 추가 → 제외 → 추가)를 고정하는 시험 | F4 R1 ⚠ |
| ② | 부하 관측 공백 — k6 시나리오 2 WS 메아리 게이트 · 온디맨드 지연 대역(1~6s·실패율 0.1) 재측정 · 격벽 거부 caller 구분 지표 | F3 L ② |
| ③ | 동시성 `TimeoutException` 5클래스(부하 의존, 단독 재실행 통과) | Phase 14 이월 ⑥ |

---

#### 이월 소단위 묶음 F5 결과 (2026-09-05 등재, Ruling 261) — 회귀 방지·관측 공백 3건 종결

병합 S1 `dd5594a` · S2 `d3ba47b` · S3 `7fefecd` → **`13ad198`**(메인). 리뷰 R1 합동 승인(🔴 0 — S3 원인을 리뷰어가 Postgres 에서 독립 재현). 발주·판정 `f5-goal-table.md`(§3 Ruling 261), 진행 기록 `f3-resume.md` 끝 절. **완료 조건 6항 전부 통과** — 최종 전체 실행(`13ad198`, DB `schoolbus_f5_final`) **2회 연속 219클래스 1264테스트 실패 0·오류 0**(S3 검증 2회 포함 4회 연속). 게이트 94/104 불변 · 정본 문면 변경 없음.

| 닫은 것 | 근거 |
|---|---|
| 확정 배치 반영 순서(강제 추가 병합 → 제외 → 추가) 고정 시험 — 강제 추가로만 올린 학생의 이동 케이스 | F4 R1 ⚠ → S1 |
| 부하 관측 공백 3건(k6 시나리오 2 결함 2 · 온디맨드 대역 · caller 태그) | F3 L ② → S2 · `§5.6` 추가 표 |
| Phase 14 이월 ⑥ 동시성 `TimeoutException` — 부하 의존이 아니라 시험 결함(`pg_stat_activity` 트랜잭션 캐시) | S3 · 위 ⑥ 행 |

**남은 것** — k6 시나리오 2 의 0.8 비율은 threshold 문법 한계로 자동 강제되지 않음(summary 를 밖에서 나눠야 함). F6 후보였던 미지연 스케줄러 2개는 아래 F6 결과 절에서 종결.

---

#### 이월 소단위 묶음 F6 결과 (2026-09-06 등재) — 테스트 설정 미등재 스케줄러 2개 종결

조율자 단독 수정(좌석 발주 없음 — 설정 2줄 + 규약 시험 1건이라 검증 가능성이 시험으로 고정됨, `parallel-agents-git.md §10` 에 따라 다음 리뷰 발주가 있으면 판정 대상에 명시). 커밋 `docs` 절 아래 해시. **완료 조건 3항 전부 통과.**

| # | 완료 조건 | 실측 |
|:-:|---|---|
| 1 | `build.gradle` test 블록이 `RunUnconfirmedGaugeScheduler`·`NoShowEscalationScheduler` 의 `initial-delay-ms` 를 하루로 미룬다 | 속성 이름 `grep -c` 각 1 |
| 2 | 규약 시험이 "모든 `@Scheduled` 가 테스트 JVM 에서 미뤄져 있다" 를 고정 — cron 형은 `'-'`, fixedDelay 형은 `initialDelayString` 속성 ≥ 하루를 `System.getProperty` 로 확인 | `SchedulerLockConventionTest` 5/5. 되돌림 재현: 속성 한 줄 삭제 → 새 시험 1건만 실패, 메시지가 `NoShowEscalationScheduler#escalateDueNoShowCases` 를 지목 → 원복 후 포셀린 빈 결과 |
| 3 | 전체 실행 무실패 유지 | `schoolbus_f6_final` · **219클래스 1265테스트 실패 0·오류 0**(1264 + 새 시험 1) |

**판단 근거** — 빈을 없애거나 프로파일로 끄는 길은 버렸다. 기존 주석대로 없애면 `@Scheduled` 배선이 어느 테스트에도 걸리지 않아 애너테이션을 지워도 초록이 된다. 규약 시험은 `build.gradle` 을 텍스트로 읽지 않고 **테스트 JVM 이 실제로 받은 시스템 속성**을 읽는다 — 등재 여부를 전달 경로 끝에서 세므로 Gradle 설정이 어떤 형태로 바뀌어도 성립한다.

⚠ **환경 함정(조율자 실측)** — 전체 실행 1회차를 `localhost:5432` 로 돌려 800건 실패(전부 `password authentication failed`). 5432 는 다른 프로젝트의 도커 Postgres 였다. School-Bus 는 **15432(Postgres)·16379(Redis)·29092(Kafka)** — `f5-common.md §4` 의 명령 형태를 그대로 쓴다.

**남은 것** — 정본 기준 미구현 기능·오픈 이슈 0(Ruling 255). 잠재 위험 후보도 이 절로 0.

---

#### 이월 소단위 묶음 S 결과 (2026-09-09 등재) — Swagger 문서화 · Kafka 제거 · 정본 공백 2건 종결

브랜치 `verify/swagger-api-coverage`. 최종 전체 실행 **218클래스 1,284테스트 실패 0·오류 0**
(DB `schoolbus_final2`, 삭제 완료). 진행 기록 `report-s1.md`·`report-s2.md`.

| 닫은 것 | 근거 |
|---|---|
| **Swagger 가 API 전체를 담는지 검증** — `OpenApiCoverageTest` 6항(누락 0 · summary 104/104 · 사양 태그 5종만 · 공통 실패 응답 · 코드 이름 실재 · 에러 표 키 실재) | 착수 시점 실측 — summary **0/104** · springdoc 자동 태그 **55종** · 에러 응답 **0건** |
| **엔드포인트 설명·태그** — `ApiTags` 신설, 컨트롤러 55개 `@Tag`, 핸들러 104개 `@Operation`. 문구는 `API_SPEC` 각 절에서 기계 추출 | `OpenApiConfig` 주석이 이미 "컨트롤러는 `@Tag` 로 소속만 선언한다" 고 적었으나 한 번도 이행되지 않았다 |
| **실패 응답 문서화** — 공통(§1.11) 401·403·422 는 `CommonErrorResponsesCustomizer` 가 전 경로에, 고유 142건은 `EndpointErrorResponses` 표 | 애너테이션 불가 — 이 저장소의 성공 봉투가 이미 `ApiResponse` 라 이름이 충돌한다 |
| **`GET /students/{id}/runs`**(§3.5 · P-04 · S-01) · **`GET /runs/{runId}/emergencies`**(§4.15) | ⚠ **정본에 있는데 핸들러가 부재하던 것** — 에러 표 키 대조 시험이 드러냈다. Ruling 255 의 "미구현 0" 판정이 이 둘만큼 어긋나 있었다 |
| **Kafka 제거** — 프로덕션 이벤트 중 `DomainEvent` 구현체 0 · 프로덕션 `@KafkaListener` 0 이라 실제로 흐르는 메시지가 0이었다 | 되돌릴 자리는 `TECH_DECISIONS §7.4` — `NotificationDispatchListener` 자리에 프로듀서, 컨슈머가 같은 `dispatch(id)` 호출 |
| **`local` 전용 초기화 API** `POST /dev/reset` | 기동 시 초기화에 위임 + `@Profile("local")` + `app.dev-tools.reset.enabled`(테스트에서 `false`) |
| **`§1.11` 에 `403`/`404` 판정 규칙 표** 신설 | 지시서가 §4.15 에 404 를 요구했으나 §4.9 가 Ruling 259(b) 로 이미 403 으로 정정한 뒤였다 — 좌석 신고로 드러났다 |

**병합·전체 실행이 드러낸 결함 4건** — ①게이트 핸들러 개수 104→106 · 거부 목록 등재 누락(좌석 각자는 통과, 전역 규칙 `parallel-agents §18` 형태이며 **발주문 실행 범위에 `AuthFlowIntegrationTest` 를 넣지 않은 것이 원인**) ②`StudentRunsControllerTest` 가 시드 회차 상태에 의존 ③그 복원이 커밋되자 `RunConfirmationSchedulerTest` 가 49/50 으로 실패(배치는 `confirm_at` 오름차순 50건) ④`§4.15` 에 발생 불가능한 404 기재.

**남은 것** — S1 이 `404 STUDENT_NOT_FOUND` 를 실제 요청으로 시험하지 못했다(연결은 있는데 학생이 삭제된 데이터가 시드에 부재). 필요하면 그 픽스처를 추가한다.

---

### Phase F1~F4 — 프론트엔드 ➖ **범위 밖**

**2026-08-25 사용자 확정으로 착수 대상에서 제외.** 순서상 뒤라서가 아니라 **앞으로의 작업 범위를 백엔드로 한정한다는 결정** 때문이며, 백엔드 Phase 14 가 끝나도 자동으로 착수되지 않는다. **2026-09-04 사용자 재확정(Ruling 255) — 프론트엔드와 법정 요건은 앞으로 계속 고려하지 않는다.** 아래 "다시 범위에 들어올 때" 표는 기록으로만 존치. 프론트 전용 에이전트 2개(`ui-implementer` · `design-system-auditor`)도 같은 결정으로 삭제됨. 이 절과 §1.4 프론트엔드 처분은 **삭제하지 않고 보존** — 사양의 프론트 요구는 그대로 유효하고, 다시 범위에 들어올 때 이 내용을 그대로 쓴다. 상태 갱신 대상은 백엔드 Phase 0~14 뿐이며 F1~F4 는 `➖` 로 고정.

**다시 범위에 들어올 때 먼저 확인할 것 4가지**

| # | 확인 대상 | 왜 |
|:-:|---|---|
| 1 | **선행 백엔드 Phase 완료 여부** — F1: 9·10·11 / F2: 8·10·12 / F3: 8·13 / F4: 3·13 | 선행이 🟡 면 화면이 호출할 API 가 부재. §8 표에서 확인 |
| 2 | **지도 SDK 선정** (오픈 이슈 P · `PRD §10.1`) | F1~F4 전부의 지도 화면을 막음. `MapSurface` 플레이스홀더로 골격은 가능하나 `LOC-04` 착수 불가 |
| 3 | **강제 노선의 지도 표현** (오픈 이슈 Q) | F3 ②구간 승인 화면의 강제 추가 승하차지 표기가 미확정 |
| 4 | **`frontend/` 현 코드의 처분 판정** (§1.4) | 판정 시점이 2026-08-24 라 재개 시점의 코드와 어긋날 가능성이 존재. 재개 전에 다시 대조 |

`frontend/docs/` 3종(`FLUTTER_CODE_CONVENTIONS.md` · `DESIGN_SYSTEM.md` · `FRONTEND_SETUP.md`)은 재개 시점의 규칙 원본으로 존치하되 **범위 밖 기간 동안 갱신 대상이 아님.**

**보존된 Phase 정의** — 백엔드 API 가 선행. 제품 4종은 배포 단위가 다르나 코드 공유 범위가 `ARCHITECTURE §4.1` 에 정의돼 있음.

| Phase | 제품 | 선행 | 완료 조건 요약 |
|---|---|---|---|
| **F1** | 매니저 앱 (기사 · 동승자) | Phase 9 · 10 · 11 | 역할 분기 · 운행모드 항상 다크 · **낙관적 UI 부재**(`C-10`) · 오프라인 큐 복구 동기화 · 색·라벨 병기(`C-09`) · **지도 페이지 부재**(동승자) |
| **F2** | 학부모 · 학생 앱 | Phase 8 · 10 · 12 | 자녀 2명 이상일 때만 선택 UI · 탑승 토글 3구간 분기 · 승인 대기 카운트다운 · **ETA·탑승 인원 미표시**(`C-08`) · 요일별 주소 설정 |
| **F3** | 관계자 웹 | Phase 8 · 13 | 가입 승인 · **②구간 승인 화면(기존 vs 재최적화 대조)** · 강제 추가 · 수동 조정 · 경유 지점 · 대시보드 · 비상 알림 팝업 |
| **F4** | 메인 관리자 콘솔 | Phase 3 · 13 | 학원 CRUD · 관계자 가입 승인 · 차단 해제 · **전 학원 관제(ETA 포함)** · 비상 알림 미확인 경과 표시 |

**전 제품 공통 3가지** — `go_router` `redirect` 한 곳에서 계정 상태·역할 분기 · `dio` interceptor 로 JWT 부착 + 401 자동 refresh · 색은 그린·앰버·레드·스톤 4색 고정(`C-09`). **이 3가지가 이 문서의 유일한 프론트 전용 규칙**이며, §7 횡단 규칙에는 프론트 전용 항목이 부재.

---

## 7. 횡단 규칙 (전 Phase 공통)

매 Phase 착수 전·완료 전에 훑는 체크리스트. 근거는 `TECH_DECISIONS` (규칙 19·20 은 `docs/backend/CODE_CONVENTIONS.md`, 규칙 21·22 는 §4.6·§4.7, 규칙 23·24 는 전역 `~/.claude/rules/phase-goal-loop.md`, 규칙 25 는 아래 §7.25). **25개 전부 백엔드 규칙**이고 프론트 전용 항목은 부재 — 프론트 전용 규칙은 Phase F1~F4 절의 "전 제품 공통 3가지"에 있으며 그 절은 현재 ➖ 범위 밖.

| # | 규칙 | 근거 | 어기면 |
|---|---|---|---|
| 1 | **`Clock` 빈 주입** — `LocalDateTime.now()` 직접 호출 부재 | §6 | 3구간·확정 배치·±10분 창을 테스트로 고정 불가 |
| 2 | **상태 전이는 엔티티 메서드** — 허용 전이 상수와 함께 | §4 | 전이 규칙이 서비스마다 복제 |
| 3 | **경합 전이는 `@Modifying` 조건부 UPDATE** — 변경 감지 미사용 | §5 | 인스턴스 증설 시 같은 회차가 두 번 확정 |
| 4 | **데이터 접근 전 구간 JPA** — 무거운 조회는 DTO 프로젝션 + fetch join | §9 | 접근 수단이 갈려 격리 강제 지점이 늘어남 |
| 5 | **알림은 아웃박스** — `notification_log` `push_state` | §7 | 커밋 직후 앱이 죽으면 알림이 영구 유실 |
| 6 | **권한은 상수 카탈로그 경유** — 역할 문자열을 컨트롤러에 미기재 | §2.3 | 역할이 하나 늘 때 전수 수정 필요 |
| 7 | **학원 격리는 저장소 계층에서 강제** | `ARCHITECTURE §6.1` | 목록 조회에서 조건 하나가 빠져도 동작해 테스트를 통과 |
| 8 | **응답은 역할별 DTO** — 엔티티 직렬화 부재 | §2.4 | 필드가 하나 늘 때마다 전 역할에 자동 노출 |
| 9 | **전 엔드포인트 Swagger + 시드 기반 예시** | §3 | "Try it out" 이 401·404 로 끝남 |
| 10 | **정책 상수는 코드 상수, 학원별 임계값은 DB** | TECH_DECISIONS §12.2 | yml 로 빼면 운영에서 사양 값이 조용히 변경 |
| 11 | **외부 지도 API 호출은 Resilience4j 로 보호** | §8 | 외부 장애가 계산 경로 전체를 정지 |
| 12 | **교체 축은 `spec`/`impl` 로 분리** — `RouteEngine` · `AttendantAssigner` · `MapRouteClient` · `GeocodingClient` · `PlaceSearchClient` · `NavProvider` · `PhotoStorage` · `ApprovalPreviewCache` · `PushSender` · `NotificationComposer`(`PositionSource` 는 부재 — 위치 원천이 매니저 앱 1종이라 교체 축이 성립하지 않음, BR-146 · BR-161). 호출부는 `spec` 만 의존하고 구현체 선택은 설정 한 곳 | `ARCHITECTURE §3.2.1` | 교체가 전수 수정이 됨 |
| 13 | **엔티티는 정적 팩토리로만 생성** — `@Builder`·`@Setter` 부착 부재. DTO 는 `record` | `ARCHITECTURE §3.2.3` | 빌더가 상태 전이 불변식을 우회 |
| 14 | **엔티티를 컨트롤러 밖으로 내보내지 않음** — 요청·응답 전용 DTO, 응답은 **역할별로 분리**, 공통 응답 봉투 적용 | `ARCHITECTURE §3.2.2` · `API_SPEC §1.10` | 필드가 하나 늘 때마다 전 역할에 자동 노출 |
| 15 | **테스트는 Testcontainers** — H2 미사용 | §10 | PostgreSQL CHECK·partial UNIQUE·`jsonb` 를 검증 불가 |
| 16 | **모듈 역방향 참조 금지** — 하위 모듈이 `routing` 을 미참조 | `ARCHITECTURE §3.3` | 주소 수정 트랜잭션 안에 외부 지도 API 호출이 포함 |
| 17 | **`notification` 을 직접 호출하지 않음** — 이벤트 구독으로만 | `ARCHITECTURE §3.3` | 승하차 트랜잭션이 푸시 실패로 롤백 |
| 18 | **정책 값을 문서에 복제하지 않음** — 규칙 ID 참조 | §0.1 | 사양 변경 시 두 곳이 갈림 |
| 19 | **클래스 · public 메서드 · enum · 이벤트 · 포트에 설명 주석** — 기본 한 문장, 둘째 문장은 다른 질문에 답할 때만. 시그니처를 되풀이하는 주석은 부재 | `CODE_CONVENTIONS.md §19` | 6개월 뒤 그 코드가 왜 있는지 판단할 근거가 부재 |
| 20 | **SRP · 클린 코드** — 클래스가 바뀌는 이유 1개 · 메서드 20줄 · 중첩 2단 · 파라미터 4개 · 매직 넘버 부재 · 조기 반환 | `CODE_CONVENTIONS.md §20` | 정책이 하나 바뀔 때 고칠 파일이 여러 개로 증가 |
| 21 | **기능 ID 1개 = TDD 사이클 1회** — 목표 → RED(실패 확인) → GREEN → REFACTOR → 검증. 실패를 보지 않은 테스트는 산출물로 미인정 | §4.6 | 통과하는 빈 테스트와 실제 검사를 구분할 수단이 부재 |
| 22 | **Phase 1개 = 목표 → 통과까지 반복** — 완료 조건을 실행 가능한 형태로 먼저 고정하고, 전항 통과 전까지 ✅ 미부여. 부분 통과는 🟡 | §4.7 | 성립하지 않은 선행 위에 다음 Phase 가 얹힘 |
| 23 | **음성 대조로 단언을 실측** — 새 단언마다 그것이 잡으려는 결함을 프로덕션에 심어 **그 단언만** 실패하는지 확인. 원복 후 `git status --porcelain` 이 빈 것까지 | `phase-goal-loop §5` | 초록이 "결함 부재" 가 아니라 **"테스트 전체 묶음이 결함을 못 봄"** 과 구분되지 않음 |
| 24 | **개수·경로 문자열은 정본에서 직접 셈** — 계획서·브리프·목표 표의 수치는 인용이지 사실이 아님. 판정 직전 재계수, 표는 **행 수 ≠ 항목 수** 를 의심 | `phase-goal-loop §6` | 파생본이 완료 판정 기준이라 낡은 값 하나가 곧 오판 |
| 25 | **전체 실행은 병합 후에만** — 작업 중인 갈래는 *자기 모듈 + 추가한 항목 종류에 걸리는 검사*만 돌린다. 새 컨트롤러·테이블·권한·마이그레이션·게이트 경로를 **만들 때만** 규약·계약 검사를 범위에 추가 | 아래 §7.25 | 같은 코드에 같은 검사를 N번 돌려 **정보 0인 실행**에 회차당 20분 이상을 쓴다 |

---

### 7.25 규칙 25 의 근거 — 전체 실행 횟수 실측 (2026-09-19)

`R17`~`R19` 세 회차를 세어 보니 **백엔드 전체 실행 12회(약 54분) · 웹 전체 실행 16회(약 19분)** 였고,
그중 **결함을 잡은 것은 1회**(R19 병합 후 `zoom` 고정값 3건)뿐이었다.

**왜 이렇게 늘었나 — 세 겹이 곱해졌다.**

| 겹 | 내용 |
|---|---|
| ① | 조율자가 **목표 표마다** "전체 실행 실패 0" 을 넣었다 → **갈래 수만큼** |
| ② | 후속 작업이 **또** 전체를 돌렸다 → R17 +1 · R18 +2 |
| ③ | 병합 후 **2회 연속** 규칙이 백엔드·웹 각각 → 회차당 4회 |

⚠ **근거로 삼은 규칙을 과하게 적용한 것이다.** `parallel-agents §18` 이 요구한 것은
*"갈래가 **추가하는 항목의 종류**로 실행 범위를 정한다 — 패키지 이름이 아니라 시험 종류로"* 이고,
같은 문서가 *"조율자의 병합 후 전체 실행은 **마지막 그물이지 첫 그물이 아니다**"* 라고 명시했다.
조율자가 이를 *"전부 돌리면 확실하다"* 로 단순화했다.

**판정** — 같은 코드에 같은 검사를 여러 번 돌리는 것은 안전을 더하지 않는다. 값이 생기는 경우는 둘뿐이다:
**①코드가 달라졌을 때**(병합 후) **②상태가 달라졌을 때**(마르는 자원을 잡는 반복 실행).
갈래별 전체 실행 8회는 **둘 다 아니었고** 실제로 0건을 잡았다.

| 유지 | 축소 |
|---|---|
| **병합 후 전체 단독 실행 2회 연속** — 유일하게 값을 한 자리 | 갈래별 전체 실행 → **소유 모듈 + 항목 종류별 검사** |
| | 후속 작업 → **건드린 범위만** |


## 8. 진행 추적 표

**이 표가 이 문서의 핵심이며 세션마다 갱신 대상.** Phase 를 마치면 상태와 비고를 함께 고친다.

| 구분 | 개수 | 갱신 대상 |
|---|:-:|---|
| **백엔드 Phase (0~14)** | **15개** | 대상 — 상태·비고를 세션마다 갱신 |
| **범위 밖 Phase (F1~F4)** | **4개** | ➖ 고정. 2026-08-25 사용자 확정으로 착수 대상 밖이라 상태를 갱신하지 않음 |

| Phase | 이름 | 선행 | 우선순위 | 상태 | 비고 |
|:-:|---|:-:|:-:|:-:|---|
| **0** | 걷어내기 · 골격 세우기 | — | — | **✅** | 완료 조건 4항 전부 실증(2026-08-25). 시각 축 정정(`Clock` 빈 · `OffsetDateTime`)을 Phase 1 선행으로 함께 수행 |
| **1** | 스키마 · 엔티티 매핑 · 시드 · Swagger 골격 | 0 | — | **✅** | 완료 조건 6항 전부 실증(2026-08-25). 테이블 **39** · 엔티티 **39** · 테스트 전체 묶음 32클래스 177테스트 실패 0. 최종 리뷰 이월 5건(T2 Minor 3 · T3 파싱 하한 가드 · T5 Minor 2) + 조율자 직접 편집 2건(`V1:183` guardian FK · `ERD §4.1` 행 분리) |
| **2** | 인증 · 계정 상태 게이트 · RBAC · 학원 격리 | 1 | P0 | **✅** | 완료 조건 **11항 전부 실증**(2026-08-26, 근거 `p2-completion-evidence.md`). 테스트 전체 묶음 **55클래스 298테스트 실패 0**. 태스크 6개 + 수정 라운드 5회 + 게이트 리뷰 5회. **음성 대조 19건으로 검증 구멍 6건 발견·해소.** 이월 — ①조건 2·3 의 거부측 목록을 Phase 3 이 엔드포인트를 늘릴 때 함께 확대(Ruling 133) ②로그인 시도 빈도 제한 → Phase 14(Ruling 127·137) ③학부모·매니저 축소 → Phase 5·9(Ruling 117) ④STOMP 축 계정 상태 게이트 · `/topic/tenant/{id}` 경로 어휘 → Phase 10(Ruling 87·121) |
| **3** | 학원 · 관계자 승인 · 메인 관리자 콘솔 기초 | 2 | P0 | **✅** | 완료 조건 **7항 전부 실증**(2026-08-26, 근거 `p3-completion-evidence.md`). 테스트 전체 묶음 **70클래스 414테스트 실패 0**. 엔드포인트 **12개** 신설(누적 프로덕션 핸들러 **24**) · `ErrorCode` 15→**25종**. 태스크 4개 + 수정 라운드 3회 + 게이트 리뷰 2회. **음성 대조로 실제 결함 5건 발견**(구현자 보고·테스트 수 증가·초록 빌드 중 어느 것도 잡지 못함). ⚠ **차단 결함 1건 정정** — `uk_academy_staff_academy` 전체 UNIQUE 라 관계자 교체가 영구 불가였음(Ruling 139, partial UNIQUE). **사양 개정 2건** — `AUTH_STAFF_INACTIVE`(퇴사자 재로그인 차단, Ruling 143) · `SIGNUP_TARGET_BLOCKED`(승인 대상 차단, Ruling 147). 오픈 이슈 **R·S·T 해소**. 이월 — ①학부모→`GuardianStudent` 연결 확인 → Phase 5(Ruling 117) ②`role='staff'` 한정이 Phase 5·9 에서 조용히 틀리게 될 축(Ruling 148) ③테스트 픽스처 raw SQL 3곳을 실제 등록 경로로 → Phase 5 ④`countAnnotatedMethods` 완전 수식명 미계수 → 별도 단위(Ruling 146) |
| **4** | 알림 아웃박스 골격 | 3 | P0 | **✅** | 완료 조건 **5항 전부 실증**(2026-08-26, 근거 `p4-review-t1-fix1-verdict.md`). 커밋 `c433aec` 회수. 태스크 1개 + 수정 라운드 1 + 게이트 리뷰 2회. **엔드포인트 0개** — 알림 조회·설정·읽음(NTF-07~11)은 Phase 12 소유. **리뷰가 잡은 검증 공백 3건** — ①`dedup_key` 의 **시계 출처**를 아무 단언도 지키지 않았음(소비 시점 시계로 바꿔도 초록 — 재배달마다 키가 갈려 적재 dedup 이 무력해지는 축) ②`MIN_RETRY_INTERVAL=0` 방지가 경합 단언 하나에 의존해 **3회 중 1회만 실패**(결정적 단언을 옆에 세워 3/3 으로 고정) ③규칙 12 가 이름까지 명시한 `NotificationComposer` 미구현(리뷰어가 임시 두 번째 구현체로 제네릭 판별까지 실측). 이월 — ①`push_state='skipped'`(NTF-07 설정 off) → Phase 12 ②경합 단언의 비결정성 자체는 잔존(결정적 단언이 옆에 있음) ③`notification_log` 픽스처가 FK 부재에 기댐. ~~④단독 전체 실행 미실시~~ **해소(2026-08-27)** — T5 회수 후 통합 트리에서 **신규 DB · 동시 실행 부재**로 단독 1회 실행. **95클래스 586테스트 실패 0 · 오류 0**, `notification` 11클래스 전건 통과 |
| **5** | 기초 데이터 — 학생 · 보호자 · 주소 · 차량 · 인력 · 스케줄 | 3 | P0(유도) | **✅** | 완료 조건 **12항 전부 실증**(2026-08-27). 태스크 **7개** + 수정 라운드 **5회** + 게이트 리뷰 **5회**. 회수 — T1 `482dfed` · T2 `319dc63` · T3 `844ea62` · T5 `e670a3f` · T6 `d860e35` · T4 `8cd3f5a` · T7 `002f696`. **최종 단독 실측(신규 DB · 동시 실행 부재) 106클래스 634테스트 실패 0 · 오류 0 · 건너뜀 2**(자격증명 부재로 실 지오코딩 시험) · 프로덕션 핸들러 **50**(직접 셈). **리뷰가 잡은 실 결함 3건** — ①고정 `Clock` × 시드 `CURRENT_DATE` 결합으로 **시드 넣은 날에만 통과하던 시험**(Ruling 176) ②`resilience4j` 애스펙트 순서 오류로 **재시도가 한 번도 걸리지 않음**(`max-attempts:3` 인데 실제 호출 1회) ③옛 사진 파일 삭제 시점(`afterCommit`)을 지키는 단언 부재. **간헐 실패 1건 원인 규명** — 프로덕션 결함이 아니라 동시성 시험의 순서 강제 부재(T7 이 단언을 건드리지 않고 강화). 이월 — ①매니저 삭제 후 연결 계정 로그인 제한 → **Phase 7 목표 표에 등재 필요** ②`manager(account_id)` partial UK 가 **재입사 영구 차단**(Ruling 139 와 같은 형태, 별도 단위) ③`stop` 좌표 UNIQUE 부재로 동시 저장 시 승하차지 중복 가능 → **Phase 6 착수 전 판정** ④`photo_url` **서빙 경로 부재**(오픈 이슈 W 와 함께 판정) ⑤네이버 4xx 도 `503` 으로 뭉개짐 ⑥규칙 10 에 **환경 인프라값 범주가 부재** |
| **6** | 노선 계산 파이프라인 · 고정 노선 | 5 | P0(유도) | **✅** | 완료 조건 **11항 + 5-a 전부 실증**(2026-08-30). **오픈 이슈 G 는 막지 않음**(Ruling 178). 판정 **Ruling 178~188**. 태스크 **8개** + 게이트 리뷰 **14회**. 회수 — T2 `c93be59` · T3 `f943177` · T1 `80e32e1` · T5 `b437c13` · T4 `c63f996` · T6 `e295983` · T7 `f834c36` · T8 `f189a1b`. **최종 단독 실측(신규 DB `p6final2` · 좌석 0 · postgres 크래시 전후 0/0) 126클래스 763테스트 실패 0 · 오류 0 · 건너뜀 3**(자격증명 부재로 실 지오코딩·도로경로 시험) · 프로덕션 핸들러 **56**(직접 셈). **목표 11 실증이 실 결함 1건을 잡았다** — `@Bulkhead` 거절이 폴백 리플렉션 경합(`FallbackMethod` 의 `Method` 캐시 × `setAccessible` 토글)으로 `UndeclaredThrowableException` 이 되어 **목표 5-a 가 실제로는 미충족**이었고, ⚠ **그 시험은 단독 실행에서 통과했다**(격벽이 포화되지 않으면 폴백 경로 자체를 안 밟음) — T8 이 거절 호출을 상한+500 으로 올려 결정화하고 리뷰가 단독 3회 실패를 확인. **조율자 오기 2건을 아래 사람이 정본으로 잡았다** — ①`RejectReason` 을 2값으로 고정(Ruling 186 으로 3값 정정, `API_SPEC:1540`·Ruling 165 ④ 가 이미 확정한 구분) ②T8 브리프의 503 단언 요구(목표 표 5-a 에 그 요구가 부재). 이월 — ①매니저 삭제 후 로그인 제한 ②`AcademyScopeRepositoryConventionTest` 가 이름만 보고 통과 ③명단 `studentId` 중복 배제 ④수동 배치 점→구간 재판정(Ruling 165 ②) ⑤`StopRepository` 중복 메서드 2개 ⑥`BusinessException` message 미전달 |
| **7** | 시간 기반 배치 — 확정 노선 산출 · 배포 · 버전 | 6 | P0 | **✅** | 완료 조건 **12항 전부 실증**(2026-08-30). 착수 시 13항이었으나 **목표 9 가 드리프트로 판명돼 범위 밖 재분류**(Ruling 192). 태스크 **7개** + 게이트 리뷰 **4회** + 수정 라운드 **2회**. 회수 — T1 `1324620` · T6 `2b47f01` · T7 `ce85cde` · T5 `20c5ca1` · T2 `68c5396` · T3 `e009097` · T4 `cd1172b`. **최종 단독 실측(신규 DB · 동시 실행 0) 136클래스 793테스트 실패 0 · 건너뜀 3** · 프로덕션 핸들러 **56**(직접 계수, 늘지 않음) · postgres 크래시 계수 전후 **1 → 1**. 판정 **Ruling 190~194**. **목표 표 드리프트 2건을 아래 사람이 잡았다** — ①목표 9 가 잠긴 결정(`ManagerDeletionLoginTest`, 로그인 200 유지)과 정반대(Ruling 192) ②목표 3 후반이 Phase 8 소유 판정기를 전제(Ruling 194). **리뷰가 잡은 실 결함 2건** — ①선점에 진 스레드도 지연 지표에 표본을 남김(증설 판단이 가장 필요한 시점에 가장 부정확해짐) ②규약 검사가 `@Query` 조건 삭제를 못 잡음. ⚠ **커넥션 고갈로 25건이 코드 결함처럼 실패**(`too many clients`) — 쉬는 컨텍스트가 풀을 붙든 것이 원인, `minimum-idle=1` 로 해소(`a245c32`). 이월 **5건**은 Phase 8 절에 등재 |
| **8** | 탑승 의사 · 변경 요청 · 3구간 승인 · 관계자 노선 조작 | 7 | P0/P1 | **✅** | 완료 조건 **18항**(정본 12 + Phase 7 이월 5 + 전체 실측 1) 판정 완료(2026-08-31). 태스크 **8개** + 게이트 리뷰 **13회** + 수정 라운드 **7회** + 통합 수정 **1회**. 회수 — T1 `455aa70` · T8 `e17415a` · T6 `0f908e3` · T3 `3d1b6bf` · T4 `19775cd` · T2 `35e649a` · T5 `3686fc5` · T7 `00d24e9` · 통합수정 `9374103`. **최종 단독 실측(새 컨테이너 · 신규 DB · 동시 실행 0) 150클래스 891테스트 실패 1 · 오류 0 · 건너뜀 3** · 프로덕션 핸들러 **65**(직접 계수, 56 → +9) · 테이블 **40**(`run_forced_addition` 신설) · postgres 재시작 계수 전후 **1 → 1**. ⚠ **실패 1건은 `AssignmentConcurrencyTest` 의 `TimeoutException`** — 부하 의존으로 분류(**단독 3클래스 재실행 15초 전건 통과** · `too many clients` 0 · 크래시 0). 그 취약점(순서 강제 수단 부재)은 **Phase 9 이월**. **착수 전 판정 Ruling 195~200** 으로 완료 조건 4항을 갈랐다 — WS 방송 → Phase 10 · 운행 시작 배선 → Phase 9 · `§5.8 transfer` → 별도 단위 · **①구간은 재최적화를 호출하지 않는다(정본 모순 개정)** · `CHANGE_WINDOW_CLOSED` **403 통일**(`§5.6` 한 줄만 어긋나 있었다). ⚠ **같은 형태의 사각지대가 다섯 번** — 하나의 입력이 두 갈래로 흐르는데 한쪽만 검사된다(저장 결과·호출 횟수·상태 코드는 보는데 **계산 입력**과 **응답 본문의 계산된 값**은 안 본다). 전부 **리뷰가 결함을 직접 심어서만** 드러났고 다섯 건 다 그 자리에서 메웠다. **리뷰가 잡은 실 결함** — ①`preview_token` 이 문자열만 비교하고 지문을 미대조(공격 시나리오 재현: 낡은 토큰 승인으로 **지워진 정류장이 되살아남**) ②롤백 시험이 **무엇을 심어도 통과**하던 무력 상태 ③`ApprovalQueryService` 가 학원 식별자를 지역변수로 옮겨 담아 격리 우회 가능. ⚠ **병합이 드러낸 결함 9건**(개별 브랜치는 전부 통과) — ①이벤트 생성자 **인자 순서 뒤바뀜**(둘 다 `Long` 이라 컴파일됨) → 수신자 조회 0건 ②T2·T3 가 **같은 이벤트를 각자 리스너로 중복 구독** → 관계자 알림 2건. 나머지는 핸들러 56→65·테이블 39→40 갱신과 규약 오탐 1건. ⚠ **조율자 직접 편집 5건**(충돌 해소 4 + 심은 변형 원복 1)이 리뷰를 건너뛰었다 — **그중 하나는 컴파일 불가 상태로 병합**됐고 T5 가 자기 분기에서 먼저 고쳤다. 이후 병합마다 `compileJava compileTestJava` 를 넣었다. 이월 **4건**은 Phase 9 절에 등재 |
| **9** | 운행 실행 · 승하차 · 명단 | 8 | P0 | **✅** | **착수 전 확인 완료(2026-08-31)** — 사용자가 미결 5건을 닫았다: **A** 등원 자동 하차도 알림 발송 · **J** 되돌리기는 취소 동작까지만(허용 범위·알림 정정은 Phase 12) · **X-01** 운행 시작 창 **±3분 → ±10분** + `confirmed` 시점부터 노선 열람 · **X-03** 동승자 부재 회차는 없다고 가정 · **V** **MVP 카카오내비 단독 · 상한 승하차지 4곳**(티맵은 스킴 규격 미공개라 미구현, 어댑터 자리만). 판정은 **Ruling 201~205**. 완료 조건 **21항** + 전체 실측 1 = **22항**, 목표 표는 `docs/archive/sdd/IMPLEMENTATION_PLAN/p9-goal-table.md`(표 행 23 — 하원 자동 승차를 항목으로 세움). 분기점 **`d430f4c`** · 실측 핸들러 **65** · 컨트롤러 25 · 테스트 클래스 149 · 테이블 40. 신설 핸들러 **10개 예상**(65 → 75). 좌석 **4개 병렬** — T1 조회·명단 / T2 운행 시작·종료 / T3 승하차·되돌리기 / T4 내비. 이월 4건 포함(Ruling 117·148·192·196·199) | **병합·통합 검사 완료(2026-08-31)** — 좌석 4개 회수 `p9-t1 9799802` · `p9-t2 9206199` · `p9-t3 83c0bfc` · `p9-t4 6b4b249`, 충돌 해소 5건 전건 검증(`RunCompletionService` 53줄 · `ErrorCode` 중복 0 · `CanReadRoute` 는 §4.16 포함한 T4 판). **통합 검사 좌석** `73bf9ed` 회수 · 병합 `54fdbd3` — 좌석 경계 배선 3건(`Phase9CrossSeatWiringTest`). 게이트 리뷰 판정 **PASS with concerns**(`p9-int-review-verdict.md`, 리뷰어가 변형 **7개 직접 심어 재현**). ⚠ **리뷰가 구현자 근거를 정정** — 구현자는 쓰는 쪽에 변형을 심어 "완전 고립 불가" 로 신고했으나, **읽는 쪽**(`NavigationQueryService:111` 의 SKIPPED 필터 · `RunRouteQueryService:86` 의 `current_stop` 계산)에 심으면 **그 시험만 실패**. 즉 새 시험의 존재 근거는 쓰는 쪽이 아니라 읽는 쪽. ⚠ **`NavigationControllerTest` 에 미경유 시험이 있는데도 필터를 지우면 미검출** — `scope=next` 라 순번상 앞선 미도착 지점이 먼저 걸려 필터에 미도달. **진짜 사각지대**. **전체 단독 실측(신규 DB `schoolbus_p9final` · 동시 실행 0)** — 162클래스 **946테스트** 실패 **2** · 오류 0 · 건너뜀 3 · 2분 49초. 핸들러 **75**(직접 계수, 65 → +10) · 테스트 클래스 **160** · postgres 재시작 계수 전 **0**. 목표 21(미배치 `403`)·22(상한 격리, `implements NavProvider` 1개) 실재 확인. ⚠ **규약 시험이 실제 지적 1건을 잡았다** — `NavRunStopRepository#findAllByRouteVersionIdOrderBySeqAsc` 가 학원 조건도 `@AcademyScopeExempt` 도 미보유. 상류는 2겹으로 막혀 있었으나(`findByIdAndAcademyId` → `assertAssigned` → 그 회차에서 파생된 `versionId`) **횡단 규칙 7 은 개별 조회마다 조건을 요구**한다. **사용자 확정(2026-08-31) — 예외 표시가 아니라 질의 보강**. `RouteVersion` → `Run` 이중 부모 조인으로 학원 조건 부착(`968bc9f`), 선례는 `RunStopRepository#findAllByRouteVersionIdAndAcademyIdOrderBySeq`. 예외 표시안을 버린 근거 — 그 애너테이션은 **좁힐 수단 자체가 부재한** 조회(로그인·재발급)용이고 여기는 부모 조인이 존재하며, Phase 8 의 `ApprovalQueryService` 가 상류 검증을 근거로 하류를 안 좁혔다가 격리 우회가 가능했던 전례가 같은 방향이다. **조율자 직접 편집이라 게이트 리뷰를 건너뛰었다 — 다음 리뷰의 판정 대상**(전역 규칙 §10). 음성 대조 2건 실측 — ⓐ수정을 되돌리면 규약 시험 `failures=1` ⇒ **고정됨** ⓑ조건을 `<>` 로 뒤집으면 `NavigationControllerTest` 5건·`Phase9CrossSeatWiringTest` 1건 실패하나 **규약 시험은 통과** ⇒ ⚠ **규약 시험은 텍스트 판정이라 조건 *무력화* 는 못 잡는다**(행동 시험이 그 몫). **최종 단독 실측(신규 DB `schoolbus_p9final2`)** — 162클래스 **946테스트** 실패 **3** · 오류 0 · 건너뜀 3 · 4분 20초. 실패 3건은 전부 `*ConcurrencyTest` 의 `TimeoutException` ⇒ **부하 의존**(동시성 12클래스 18시험 단독 재실행 22초 전건 통과 · `too many clients` 0 · postgres 재시작 0 → 0) ⇒ Phase 8 이월분이 계속 이월. **이월 5건** — ①동시성 `TimeoutException`(순서를 정할 수단 부재) ②T4 `scope` 에 잘못된 값이 올 때 처리 미확인 ③T1 두 명단이 공유하는 헬퍼를 깨뜨렸을 때 양쪽 동시 붕괴 미검증 ④T2 목표 2(시작 시 노선 잠금) 명시 시험 부재(기존 커버리지로 방어, Minor) ⑤T2 수정 라운드 3 에 재리뷰 미부착(조율자 판단 — 다음 세션의 판정 대상).
| **10** | 위치 · 실시간 전달 · 근접 알림 | 9 | P0/P1 | **✅** | 완료 조건 **21항 + 전체 실측 1 = 22항** 판정 완료(2026-09-01). 목표 표는 `docs/archive/sdd/IMPLEMENTATION_PLAN/p10-goal-table.md`. 착수 전 판정 **Ruling 207~211**, 진행 중 **Ruling 212**. 좌석 **4개 병렬**(T1 위치 수신·저장 / T2 STOMP 인가·방송 / T3 근접 알림 / T4 학부모 조회) + 게이트 리뷰 **4회** + 수정 라운드 **3회**. 회수 — T1 `ab99495` · T2 `ab2be8a` · T3 `394aeed` · T4 `f8577ec` · T4 재수정 · T2 수정 · T1 수정 `c96b59f`. **최종 단독 실측(좌석 0 · 동시 실행 0)** — **984테스트** 실패 **2** · 오류 **0** · 건너뜀 3 · 5분 9초. 실패 2건은 `BusRegistrationConcurrencyTest`·`RunGenerationConcurrencyTest` 의 `TimeoutException` ⇒ **부하 의존**(단독 재실행 12초 3건 전건 통과 · postgres 재시작 계수 **0**) ⇒ Phase 8 이월분이 계속 이월. 프로덕션 핸들러 **78**(직접 계수, 75 → +3) · 컨트롤러 **37** · 테스트 클래스 **180** · 방송 리스너 **6**(5 → +1) · 테이블 40(불변). ⚠ **핸들러가 3개만 는 것은 누락이 아니다** — STOMP 구독 채널은 HTTP 핸들러가 아니라 계수에 안 잡히고 근접 알림은 스케줄러라 요청 진입점이 부재. `AccountStatusGateEndpoints` 자바독에 그 근거를 남겼다. **Ruling 212 — Ruling 210 폐기**(사용자 확정, 안 "가"). 210 은 정본이 명시적으로 제외한 예외(확정 배치)를 근거로 삼아 **정본이 이름으로 지목한 대상**(근접 알림 스케줄러)에 그 예외를 적용한 오판이었다. T3 좌석이 신고했고 확인 결과 신고가 맞았다 — `phase-goal-loop §6.1` 이 경고한 형태. **알림 중복 발송에는 구멍이 부재**(조건부 UPDATE + `dedup_key`, R3 가 결함 주입으로 독립 재현)하고 빠진 것은 **중복 수행 방지**라 **ShedLock 은 Phase 11 로 분리**. ⚠ **리뷰가 잡은 실 결함 2건 — 둘 다 시험으로는 절대 드러나지 않는 형태다.** ①**목표 6(위치 방송) 통째로 미구현**(R2, BLOCKING) — `API_SPEC §7.1` 이 `position` 을 8종 이벤트 중 **첫 번째**로 명시하는데 방송 리스너가 부재했다. **없는 기능에는 실패할 시험도 없다** — 사양과 코드를 항목 단위로 마주 놓아야만 드러난다. ②🔴 **T1↔T3·T4 Redis 와이어 포맷 파손**(R1, Critical) — 쓰는 쪽은 타입 태그를 심는 직렬화기, 읽는 쪽 둘은 평문 파서였다. 운영에서 **기사가 위치를 보내는 순간부터 근접 알림이 영구히 미발생**(예외를 삼켜 조용한 실패)하고 **학부모 위치 조회가 매번 `500`** 이 났는데 **전 시험이 초록**이었다. 원인은 **세 좌석의 시험이 전부 각자 손으로 만든 평문 JSON 을 심어 자기 쪽만 검사**했고 **실제 쓰기 → 실제 읽기를 잇는 시험이 하나도 없었던 것**. ⚠ **T1 이 계약을 어긴 것이 아니다** — 목표 3 의 Redis 값 계약이 **필드 이름 4종만** 명시하고 **와이어 포맷을 어디에도 적지 않아** 세 좌석이 각자 합리적으로 골랐다. 해소는 쓰는 쪽을 평문으로 통일 + **경계를 잇는 시험 252줄** 신설(`RunPositionCrossConsumerIntegrationTest`) + 계약을 양쪽 자바독에 명시. `RedisConfig` 는 프로덕션 소비자가 0 이 되어 삭제. ⚠ **R2 가 심은 변형 2건이 살아남았다**(구현은 맞는데 지키는 시험이 부재) — `revert` 경로의 이벤트 발행 삭제 · 하차 집계 대상 오염. 수정 라운드에서 메우고 같은 변형이 이제 잡히는 것을 확인. ⚠ **병합이 드러낸 것 3종** — ①**핸들러 3개가 계정 상태 게이트 목록에서 누락**(75 → 78). **Phase 9 와 똑같은 형태** — 좌석들이 그 파일을 안 건드려 충돌이 안 났고 그래서 조용히 빠졌다. **개수 단언이 유일한 탐지 수단**이었다 ②**규약 시험 2건이 좌석 단독 실행에서는 안 보였다**(인가 애너테이션 부재 · 알림 모듈 격리) — 저장소 전체를 훑는 검사는 병합 후에야 의미가 생긴다 ③Redis 시험 베이스가 **세 벌**(좌석마다 자작)이라 T1 판으로 일원화. ⚠ **시드의 "오늘" 행이 DB 생성일에 굳는 함정**을 처음 밟았다 — 하루만 지나면 통합 시험이 `RUN_NOT_FOUND` 로 죽는다. `DROP DATABASE` 재생성은 해결이 아니라 **내일 돌아오는 우회**이고 그 사이 다른 수정이 원인을 가린다(실제로 좌석이 시간대 수정과 DB 재생성을 한 회차에 함께 넣어 어느 쪽이 효과를 냈는지 갈리지 않았다). 해소는 시험의 `Clock` 을 **시드에서 읽은 날짜**로 고정(`Clock.fixed` 가 아니라 `Clock.offset` — 얼리면 목표 9 의 2분 신선도 검사가 무너진다). ⚠ **조율자 직접 편집 8건이 리뷰를 건너뛰었다** — `3ba6af9`(T4 미커밋 12파일 대행 커밋) · `4e5d595`(Ruling 212) · `0d9ef83`·`4ff8057`(Redis 일원화·게이트 등재) · `0d1cef8`(규약 2건) · `3b333f6`(시드 수정 되돌림) · `718c174`·`b7e4c01`(함정 등재). **전부 리뷰 발주문에 판정 대상으로 실었고 R4 가 4건을 승인**했다(게이트 3줄 중 조율자가 1줄만 검증하고 남긴 2줄을 R4 가 각각 독립 반증). 이월 **7건**은 Phase 11 절에 등재 |
| **11** | 예외 · 비상 알림 | 10 | P0 | **✅** | 완료 조건 **16항 + 전체 실측 1 = 17항** 판정 완료(2026-09-02). 목표 표는 `docs/archive/sdd/IMPLEMENTATION_PLAN/p11-goal-table.md`. 착수 전 판정 **Ruling 213~215**. 좌석 **4개 병렬**(T1 미승차·학원설정 / T2 비상 알림 전량 / T3 예외 보고 / T4 ShedLock) + 게이트 리뷰 **3회** + 수정 라운드 **4회** + 마무리 **2회**. 회수 — T4 `55fa708` · T1 `acae3f0` · T2 `04d89ca` · T3 `5207e08` · 락 부착 `ea2a531` · 규약 해소 `27cf7bd`. **최종 단독 실측(좌석 0 · 동시 실행 0 · 새 결과 디렉터리)** — **189클래스 1045테스트 실패 3 · 오류 0 · 건너뜀 3 · 8분 53초.** 실패 3건은 `BusRegistrationConcurrencyTest`·`RouteRegistrationConcurrencyTest`·`RunGenerationConcurrencyTest` 의 `TimeoutException` ⇒ **부하 의존**(3클래스 단독 재실행 **11초 4테스트 실패 0** · postgres 재시작 계수 **0** · 연결 6) ⇒ Phase 8 이월분이 계속 이월. 프로덕션 핸들러 **89**(78 → +11, 게이트 개수 단언이 실증) · 컨트롤러 **42**(37 → +5) · 테이블 **41**(`shedlock` 신설) · 알림 리스너 **12**(11 → +1) · `@SchedulerLock` **5**. ⚠ **착수 시점에 이미 있던 것을 새로 만들지 않았다** — 테이블 5종·엔티티 10개·저장소 1개가 `V1`·Phase 1 산출물로 실재해 재계수로 확인했고(Ruling 213), 신설 마이그레이션은 `V4`(shedlock, T4)·`V5`(`exception_reported`, T3) **둘뿐**이다. **Ruling 214 — 좌석 분할을 테이블 소유권 기준으로 바꿨다.** 옛 분할은 `ack` 를 읽기로 오해해 T2·T3 가 **같은 `EmergencyAlert` 파일을 둘 다 쓰기**하게 만들었다(Phase 10 Critical 과 같은 형태). 어긋나는 것이 **값이 아니라 파일**이라 계약 전파로는 막히지 않으므로 **한 테이블을 쓰기하는 좌석을 하나로** 몰았다 — 목표 16항 그대로이고 배정만 옮겼다. **결과적으로 병합 충돌이 3파일(횡단 관심사)로만 났다.** **Ruling 215 — `§4.13` 관계자 통지에 쓸 알림 종류가 정본에 부재**(`§9.7` 18종에 대응값 없음 · `§7.1` 은 WS 가 발송 경로가 아님을 명시해 우회로도 부재). **정본 내부의 어긋남이고 기능 요건이 이긴다** ⇒ `exception_reported` 신설. T3 좌석이 신고해 드러났다. ⚠ **리뷰가 잡은 실 결함 1건 — 시험이 0건이라 아무 데서도 안 잡혔다.** 🔴 목표 11 의 `AdminEmergencyItemResponse.elapsedSecondsSinceRaised` 가 전역 `SNAKE_CASE` 로 `elapsed_seconds_since_raised` 로 나가는데 `API_SPEC §6.11` 은 **`elapsed_since_raised`** 를 요구했다(R2, Critical). `GET /admin/emergencies` 를 호출하는 시험이 diff 에 **0건**이라 필드명이 틀려도 실패할 시험이 부재했다 — **Ruling 214 로 뒤늦게 옮겨 온 목표**(10·11)에서 나왔고, 리뷰 발주문이 *"누락되기 쉬운 자리"* 로 미리 지목한 그 자리다. 해소는 필드명 정정 + **JSON 키 문자열을 직접 검사하는** 시험 4개 + 실시간 반영 시험 3개 신설. ⚠ **자바 필드명만 보는 시험은 이 어긋남을 못 잡는다** — 이름과 키 사이에 변환이 끼어 있다. ⚠ **리뷰가 지적한 시험 부재 2건**(T1 목표 3·4 무시험 · T2 목표 10·11 무시험)은 **코드는 맞는데 지키는 시험이 부재**한 형태였다. 목표 4 의 `422` 는 **완료 조건의 절반**인데 한 번도 실행되지 않았다 — `phase-goal-loop §6.2`(한 줄에 조건이 여럿) 형태의 재발. 수정 라운드에서 시험 **22개** 신설. ⚠ **조율자가 게이트 리뷰 판정 1건을 뒤집었다** — R4 가 `lockAtMostFor` 상한 미검증을 *"구조적으로 검사 불가"* 로 보고 인지만 하고 승인했으나, **30초 폴링에 `PT24H` 가 박히면 인스턴스 하나가 죽는 순간 근접 알림이 24시간 멈춰 목표 13(영구 점유 부재)을 실제로 위반**한다. 수정 라운드에서 **락을 쥐고 있는 동안 행을 읽는** 방식으로 검사 가능함이 실증됐다(`PT30S`→`PT24H` 변형이 새 시험 1건만 실패). ⚠ **해제 시 `lock_until` 이 현재 시각으로 되돌아가 사후 조회로는 구별되지 않는다.** **T1 도 같은 형태의 자리에서 "검사 불가로 넘길 근거가 없다" 고 판단해 시험을 더했다** — 두 좌석 모두 구조적 불가로 넘길 수 있었는데 실제로는 둘 다 검사 가능했다. ⚠ **병합이 드러낸 규약 위반 5건**(개별 좌석은 전부 통과) — **저장소 전체를 훑는 검사는 좌석 단독 실행에서 의미가 생기지 않아** 병합 후에야 드러난다. **Phase 9·10 에 이은 3회차이고 같은 검사 2종이 또 걸렸다.** ①`EmergencyAlertRepository#findByClientKey` 학원 조건 부재 ②`EmergencyStaffQueryService` 전건 조회 2곳 ③🔴 **`EmergencyNotificationListener` 가 `exception/` 에 있어 모듈 경계 위반**(알림 리스너 12개 중 이것만 반대쪽. **직접 호출이면 발송 실패가 비상 신고를 롤백시킨다**) ④⑤`shedlock` 의 timestamptz·테이블 집합. ⚠ **④는 스키마를 규약에 맞추면 안 되는 자리였다** — 라이브러리가 `timezone('utc', CURRENT_TIMESTAMP)` 로 무시간대 값을 내므로 컬럼을 `timestamptz` 로 바꾸면 **세션 타임존으로 재해석돼 KST 편차 버그가 프로덕션에 새로 생긴다.** 좌석이 라이브러리 소스를 직접 열어 판정했다. **같은 누락의 재발을 막으려고 `SchedulerLockConventionTest` 를 신설**했다(`@Scheduled` ⇒ `@SchedulerLock`, 리플렉션 기반 — 텍스트 스캔은 자바독의 `{@code @Scheduled}` 인용을 세어 **조율자가 실제로 개수를 6으로 틀린 전례**가 있다. 실제 5). ⚠ **조율자 직접 편집 7건이 리뷰를 건너뛰었다** — `dfd3a65`·`032ad2f`·`24843ed`(Ruling·노트) · `9026d26`(T3 미커밋 3건 대행 커밋) · 충돌 해소 2회(T2·T3 병합) · `e773922`·`1f200a5`(함정 등재). ⚠ **조율자 오판 3건** — ①`grep -c` 로 게이트 문자열을 세어(`-c` 는 **일치한 줄 수**다) T2 를 "5개 중 4개만 등재" 로 오판 ②배경 실행 알림의 `exit code 0` 을 믿을 뻔했다(실제 gradle `EXIT=1`·`BUILD FAILED` — **복합 명령의 마지막 원소 코드**였다) ③**한글 이름 시험 헬퍼(`알림_행수(...)`)를 영문 `grep` 으로 놓쳐 "커버리지 부재" 를 발주문에 실었다** — 좌석이 결함을 심어 전제를 뒤집었다(`PROJECT_NOTES` 등재). 이월 **3건**은 Phase 12 절에 등재 |
| **12** | 알림 설정 · 목록 · 수신 확인 · 로그 | 11 | P1 | **✅** | 완료 조건 **14항**(정본 7 + 재계수 신설 4 + Ruling 219 신설 2 + 전체 실측 1) 판정 완료(2026-09-03). 목표 표 `docs/archive/sdd/IMPLEMENTATION_PLAN/p12-goal-table.md`. 착수 전 판정 **Ruling 216~221**, 진행 중 판정 **222~229**. 분기점 `99c81b7` · 되돌릴 지점 `p12-premerge-backup`. 좌석 **4개 병렬**(T1 알림 설정 / T2 목록·읽음 / T3 관계자 로그 / T4 되돌리기 정정 알림). 회수 — T4 `93e9c92` · T1 `dc61437` · T2 `bf09b7f` · T3 `9907de6`. **최종 단독 실측(좌석 0 · 새 DB `schoolbus_p12final` · Redis 16379 · 워커 힙 2048m)** — **195클래스 1088테스트 실패 4 · 오류 0 · 건너뜀 3 · 6분 28초.** 실패 4건은 `BusRegistration`·`RouteRegistration`·`RunGeneration`·`RunStopProximityClaim` 동시성 시험의 `TimeoutException` ⇒ **부하 의존**(4클래스 단독 재실행 **5테스트 실패 0**) ⇒ Phase 8 이월분이 계속 이월. postgres 재시작 **0**. 프로덕션 핸들러 **94**(89 → +5, 게이트 개수 단언이 실증) · 컨트롤러 **47**(42 → +5) · 테이블 42. 🔴 **병합이 실제 결함 2건을 드러냈고 둘 다 어느 좌석 시험으로도 안 잡혔다.** ①**미확인 배지가 확인 불가능한 종류까지 셌다**(Ruling 227) — 쓰는 쪽은 중요 통지 3종만 `acked` 를 남기는데 세는 쪽은 전 종류를 세, 승하차·도착 알림이 영원히 `acked=false` 라 **배지가 0 이 되지 않고 단조 증가**. 집합을 `NotificationType.IMPORTANT_FOR_ACK` 한 곳으로 모으고 `Phase12AckBoundaryTest` 2건 신설(결함 재주입 시 의도한 1건만 실패). ②**읽음 처리가 학원 대조를 직접 견줘 `AcademyScope` 판정 지점을 우회**했다 — `AcademyScopeSingleJudgmentPointTest` 가 병합 후에야 잡았다(**Phase 9·10·11 에 이은 4회차**, 저장소 전체를 훑는 검사는 좌석 단독 실행에서 의미가 생기지 않는다). ⚠ **T3 시험 2건이 결함 쪽 동작을 기대값으로 굳히고 있었다** — 승하차·도착을 심고 배지가 그것을 센다고 단언했다. `phase-goal-loop §5` 의 *"옳은 동작 쪽으로 고쳤을 때 실패하는 단언은 결함을 굳히는 것"* 이 그대로 재현. **양쪽 좌석 모두 전건 통과였다.** ⚠ **T1 이 요청보다 나은 처리를 했다** — `isEnabledFor` 를 **`default` 없는 `switch`** 로 바꿔 열거값 추가를 **컴파일 오류로 강제**한다. 병합 직후 실제로 발화해 T4 의 2종 분류를 막았다(Ruling 223). **이월 6건** — ①동시성 `TimeoutException` ②**문구 조립기 16개 전부가 자녀 이름을 안 넣는다**(`ATT-03` 위반, 근거로 든 `§6.3 L3` 는 오인용 — 이름은 **L1**. 조립기 입력이 이벤트라 **넣을 수단 자체가 부재**해 이벤트 계약 변경이 필요, Ruling 225) ③**미승차 되돌리기 정정 알림 부재**(목표 13·14 와 같은 형태, Ruling 223) ④**"중요 통지" 열거값 분류가 정본에 부재**(Ruling 226·227) ⑤`FEATURE_SPEC` NTF-09 와 `USER_FLOWS:623` 의 **팝업 대상 서술이 상충** ⑥**ID 타입 문서·코드 어긋남**(사양 `string` vs 코드 `Long runId` 15건, Phase 7 이후 누적) |
| **13** | 모니터링 · 관제 | 12 | P1 | **✅** | 완료 조건 **14항** 판정 완료(2026-09-03 — 목표 1~7 R1 + 재판정 · 5·8~12 R2 · 13 R3 + 재판정 · 14 전체 실측). **착수 2026-09-03.** 완료 조건 **14항**(정본 7 + 재계수 신설 6 + 전체 실측 1). 목표 표 `docs/archive/sdd/IMPLEMENTATION_PLAN/p13-goal-table.md`. 착수 전 판정 **Ruling 230~235**, 착수 후 **236**(T3 신고 — `emergency_alert.position_recorded_at` `V7` 신설) · **237**(T2 신고 — `RunPositionReader` 빈 이름 충돌, `1a9a9d5` 채택) · **238**(T1 두 번째 리포지토리 4개 — 정정 미소비로 커밋됨, 수정 라운드 1 + `§5.19` 미구현 발견 → 오픈 이슈 `X`) · **239**(`§5.4` 타 학원 회차 `404` vs 정본 `403` — 이월·사용자 판정). 분기점 `b7b155f`. 좌석 **3개 병렬**(T1 관계자 대시보드·live / T2 메인 관리자 관제·관제 ETA / T3 비상 알림 응답 정합 = Phase 11 이월 ①). **Ruling 232(ETA 계획값·`est_depart_time`·`delay_minutes` 정의)는 2026-09-03 사용자 확정.** 실질 선행 9·10·11(Ruling 230). **최종 단독 실측(좌석 0 · 새 DB `schoolbus_p13final` · Redis 16379)** — **199클래스 1105테스트** 실패 **3** · 오류 0 · 건너뜀 3 · 15분 33초(문서 좌석 3개가 같은 시각에 돌아 Phase 12 의 6분 28초보다 길다). 실패 3건 전부 **부하·환경 의존** — `BusRegistrationConcurrencyTest`·`RunStopProximityClaimConcurrencyTest` `TimeoutException`(Phase 8 이월 계속) · `StudentEntitySchemaValidationTest` Testcontainers `ContainerLaunchException`(환경) — **단독 재실행 3클래스 9/9 통과**(1분 16초). 회수 — T3 `6998410` · T1 `194e6fe`(충돌 0) · T2 `bc99df7`(충돌 2파일 양쪽 살림, `AssignmentRepository` 자동 병합). 게이트 문자열 **88** · `hasSize(98)` · 컴파일 통과. 리뷰 3회 + 재판정 2회(R3 · R1) + 수정 라운드 4회(T3 1 · T2 1 · T1 2). 조율자 편집 `fb9f06d`(Phase 11 이월 ③ 해소 — 병합 후 게이밍 변형 `GhostController.java#vanished` 로 그 시험만 실패 실증). 조율자 경계 시험 — `STALE_THRESHOLD` 2→20분에 관계자 관제·학부모 시험 동시 실패(공유 상수 실증). **이월 9건** → Phase 13 절 끝 |
| **14** | 감사 · 보존 정리 · 운영 게이트 | 13 | P1 | **✅** | 완료 조건 **11항** 판정 완료(2026-09-04 — 목표 1~5 R1 + 재판정 · 6·7 R2 · 8·9·10 R3 · 11 전체 실측). **계획 수립 2026-09-03 · 착수·완료 2026-09-04.** 목표 표 `docs/archive/sdd/IMPLEMENTATION_PLAN/p14-goal-table.md`. 착수 전 판정 **Ruling 241~246**, 착수 후 **247**(R1 반려 신고 — `ChildListResponse` 는 L1 뿐, 감사 대상 4종으로 정정) · **248**(최종 실측 신고 — T2 시험의 2032년 고정 시계가 공유 DB 시드 삭제, 조율자 편집 `4933372`) · **249**(이월 소단위 묶음 F1 신설). 사용자 확정 2건(242 L3 감사 단위 · 243/X-09 90일) 잠정값 그대로. 분기점 `08e7878`. 좌석 **3개 병렬**(T1 감사 / T2 보존 정리·`V8` / T3 지표·배포 게이트·런북). **최종 단독 실측(좌석 0 · 새 DB `schoolbus_p14final2` · Redis 16379 · `--rerun`)** — **209클래스 1140테스트** 실패 **3** · 오류 0 · 건너뜀 3 · 5분 1초. 실패 3건 전부 **부하 의존** `TimeoutException`(`BusRegistration`·`ChangeRequestAutoRejection`·`RouteRegistration` ConcurrencyTest) — **단독 재실행 3/3 통과**. 1차 실측(`0a1f19c`)의 `NotificationOutboxWorkerTest` 3건은 Ruling 248 로 해소. 회수 — T2 `289f42a` · T3 `ccaf74f` · T1 `0a1f19c`(충돌 0, 파일 겹침 0). 게이트 문자열 **90** · `hasSize(100)` · 컴파일 통과. 리뷰 3회 + 재판정 1회(R1) + 수정 라운드 1회(T1). 조율자 경계 시험 — 병합 트리·새 DB 에서 `retention.*`+`audit.*`+지표 노출 2클래스 31건 통과, `V1`~`V8` 적용·보존 인덱스 6개 실재. **이월 6건** → Phase 14 절 끝(①~⑤ 는 F1 로 즉시 배정) **이월 소단위 묶음 F1(Ruling 249, 2026-09-04 같은 날 완료)** — 좌석 3개 병렬(S1 관측·보존 시험 강화 / S2 관제·비상 응답 시험 강화 + Ruling 250 `§6.8` 유실 규칙 구현 / S3 `emergency_raised` 7필드 + `§5.19` 관계자 노선 조회 = 오픈 이슈 X 해소). 리뷰 3회 전부 승인(🔴 0) + 수정 라운드 2회(S2 목표 8 · S1 목표 1/Ruling 251). 병합 S2 `963c750` → S1 `03fa179` → S3 **`fa8705d`**(충돌 0). 게이트 **91** · `hasSize(101)`. **최종 단독 실측(새 DB `schoolbus_p14f_final` · `--rerun`) — 213클래스 1159테스트 실패 3 · 오류 0 · 건너뜀 3 · 5분 24초, 실패 3건 전부 동시성 `TimeoutException`(`BusRegistration`·`RunStopProximityClaim`·`ChangeRequestAutoRejection`), 단독 3/3 통과.** 닫은 이월 — Phase 13 ①②⑤⑦⑧⑨ · Phase 14 ①②④⑤ · X. 남은 이월 7건 → Phase 14 절 끝 "F1 결과" 표 **이월 소단위 묶음 F2(Ruling 252, 2026-09-04 같은 날 완료)** — 좌석 3개 병렬(S1 시험 보강 3건 / S2 `LowerCaseFormatter` 공용화 + 자바독 절 번호 19건 / S3 `TECH_DECISIONS §13.1` 지표 이름 · `rider_count` 정의 · 옛 Ruling 색인 31행 · 정본 참조 7건). 리뷰 R1·R2 승인(🔴 0 · ⚠ 3). 병합 S1 `ac0fb52` → S2 **`4a4ecba`**(충돌 0, 31파일 +156/−56). 게이트 **91** · `hasSize(101)` 불변. **최종 단독 실측(새 DB `schoolbus_f2_final` · `--rerun`) — 213클래스 1161테스트 실패 3 · 오류 0 · 건너뜀 3 · 6분 8초, 실패 3건 전부 동시성 `TimeoutException`(`BusRegistration`·`RunStopProximityClaim`·`RunGeneration`), 단독 3/3 통과(14초).** 닫은 이월 — F1 결과 ①~⑦ · Phase 14 ③. docgraph 161 → 코드 11 + 문서 48(전부 도구 한계·오탐). 남은 이월 5건 → "F2 결과" 표 |
| **F1** | 매니저 앱 | 9·10·11 | P0 | **➖** | **2026-08-25 확정으로 범위 밖.** 재개 시 오픈 이슈 P |
| **F2** | 학부모 · 학생 앱 | 8·10·12 | P0 | **➖** | **2026-08-25 확정으로 범위 밖.** 재개 시 오픈 이슈 P |
| **F3** | 관계자 웹 | 8·13 | P0/P1 | **➖** | **2026-08-25 확정으로 범위 밖.** 재개 시 오픈 이슈 P·Q |
| **F4** | 메인 관리자 콘솔 | 3·13 | P1 | **➖** | **2026-08-25 확정으로 범위 밖.** 재개 시 오픈 이슈 P |

### 8.1 병렬 가능 구간 — Phase 진입 시 판정표 (2026-08-25 신설)

위 표의 **선행 열은 "읽는 순서" 로 적힌 곳이 섞여 있다.** 진짜 의존과 완화 가능한 것을 갈라 둔다. **Phase 에 진입할 때 이 표를 먼저 보고, "재판정" 인 항목은 그 Phase 절의 완료 조건을 읽어 확정한다.**

| 구간 | 계획상 선행 | 판정 | 근거 |
|---|:-:|:-:|---|
| **4 ‖ 5** | 각각 3 | **확정 — 병렬 가능** | Phase 5 절이 이미 명시 — "선행 Phase 3 (**Phase 4 는 알림 발행에만 필요**)". 두 Phase 를 동시에 띄운다 |
| 5 → 6 → 7 → 8 → 9 | 직렬 | **진짜 의존** | 데이터가 앞 단계에서 만들어져야 뒷 단계가 존재. 노선 계산 → 확정 배치 → 탑승 의사·3구간 → 명단 → 승하차. **쪼개지 않는다** |
| 10 ← 9 | 9 | **진짜 의존** | 위치는 운행 중 회차(`status='moving'`)가 있어야 찍힌다 |
| **11 ← 10** | 10 | **완화 가능 — 부분** | `EXC-01`(미승차)·`EXC-02·03`(예외 보고)은 **9(운행 실행)만 필요**하고 위치를 쓰지 않는다. `EXC-04`(비상 알림)는 `emergency_alert.lat`/`lng` 가 **nullable** 이나 `ERD` 가 "미전달 시 **최신 수신 좌표로 대체**" 로 규정해 그 폴백만 `run_position`(10)을 요구. **⇒ 11 을 둘로 갈라 EXC-01~03 을 10 과 병렬로 돌릴 수 있다** |
| **12 ← 11** | 11 | **완화 가능** | ⚠ `NTF-12`(푸시 단말)는 **Phase 12 절이 이미 "Phase 2 와 함께 나가야 한다" 고 명시**했고 실제로 Phase 2 에서 구현 중(`API_SPEC §2.11`). `NTF-07`(설정 on/off)은 `notification_setting`+account 만 필요. `NTF-08~11`(목록·읽음·배지)은 `notification_log` **조회**라 **아웃박스(4) 이후 구현 가능**. ⇒ **12 는 4 이후 착수 가능**. 단 완료 판정은 알림이 실제로 쌓인 상태가 필요하므로 **구현 시점과 판정 시점을 가른다** |
| **13 ← 12** | 12 | **재판정 완료 — 실질 선행은 9·10·11** (Ruling 230, 2026-09-03) | 관제는 타 모듈 테이블의 **읽기 전용 프로젝션**이고 테이블은 Phase 1 에 전부 존재. 응답 필드 출처를 코드에서 전부 추적한 결과 **Phase 12 산출물(`notification_log`·`notification_setting`)을 어디서도 읽지 않는다** — 필요한 것은 9(moving 회차·승하차)·10(위치 Redis·WS)·11(`no_show_case`)이다. 12 가 이미 완료라 진행에는 영향 없음 |
| **14 ← 13** | 13 | **재판정 필요 — 앞당길 이유가 있다** | `SYS-02`(접속 이력)는 **Phase 2 인증 계층만 필요**하고, 보존 정리는 Phase 1 테이블 위에서 돈다. ⚠ **더 중요한 것 — 감사 기제(AOP·인터셉터)를 늦게 붙이면 앞선 전 Phase 의 엔드포인트에 소급 적용해야 하고, 일찍 붙이면 이후 Phase 가 자동으로 기록된다.** 병렬 여부가 아니라 **순서 자체를 앞당길 후보** |

**판정 원칙 셋**

1. **데이터 파이프라인은 쪼개지 않는다** — 5→9 는 앞 단계가 뒷 단계의 입력을 만든다
2. **읽기 전용 프로젝션과 횡단 기제(감사·알림 조회)는 선행이 약하다** — 테이블이 있으면 구현·테스트가 되고, 완료 판정만 데이터를 요구한다. **구현 시점과 판정 시점을 가르면 병렬 폭이 넓어진다**
3. **같은 모듈 안에서 층(컨트롤러/서비스/저장소)을 나눠 병렬로 돌리지 않는다** — 인터페이스가 확정되기 전에 양쪽이 가정을 세우고 어긋나면 둘 다 재작업. Phase 2 에서 `AuthUser` 를 두 태스크가 각자 고쳐 조율자가 손으로 합친 전례가 실재(Ruling 78)

**Phase 내 태스크 병렬은 일의 성격을 따른다** — **데이터 모델은 넓게 쪼개지고 기능은 깊게 쌓인다.** Phase 1(엔티티 39개)은 폭 4까지 갔고, Phase 2(인증 모듈)는 층 구조라 폭 2다. 기능 Phase 에서 폭을 넓히려면 **아래층을 앞 태스크가 완성**해야 한다(Phase 2 에서 T3 이 저장소를 완성해 T4‖T5 를 연 것이 그 예).

---

**F1~F4 의 `➖` 는 성격이 다르다** — 아래 목록은 **사양이 만들지 않기로 정한 기능**이고, F1~F4 는 **사양에 남아 있으나 지금 착수하지 않는 것**. 우선순위 열의 P0/P1 을 남겨 둔 이유가 이것이며, 재개 시 Phase F1~F4 절의 확인 4가지를 먼저 밟는다.

**범위 밖 (➖) — 사양이 제외한 기능** — `FEATURE_SPEC §7` 의 제외·보류 항목과 `PRD §11` 의 2~3단계 항목. 일괄 승하차 · 매니저 앱 지도 페이지 · 학부모→기사 직접 연락 · 관계자 권한 등급 · 태그 승하차 · 법정 안전운행기록 자동 생성 · 다학원 통합 · 통계 대시보드 · 알림톡 연동.

---

## 8.2 🔧 백엔드 정합 라운드 (`BE-R1`) — **독립 태스크** (2026-09-12 사용자 확정, Ruling 273)

**사용자 지시** — *"일단 M2까지 끝내고 백엔드 보류는 새로운 태스크로 분리, 그리고 백엔드 수정하고 그 다음 F4 진행."*

**실행 순서가 고정됐다.**

| 순 | 단위 | 조건 |
|:-:|---|---|
| 1 | **프론트 F3 종결** | `M2` 게이트 리뷰 회수 + 전체 단독 실행 |
| 2 | **`BE-R1` 백엔드 정합 라운드** ← 이 절 | F3 종결 후 착수 |
| 3 | **프론트 F4** (실시간 · 지도) | `BE-R1` 종결 후 |

- ⚠ **`BE-R1` 은 F3 의 이월이 아니라 독립 태스크다.** 프론트 이월(`docs/frontend/IMPLEMENTATION_PLAN §5.3`)과 섞지 않는다 —
  저쪽은 프론트가 고치고 이쪽은 백엔드가 고친다
- ⚠ **F4 를 `BE-R1` 앞에 두지 않는 이유** — 6번(비상 발신 응답)이 고쳐지지 않으면 **매니저 앱의 비상 발신이
  실제로 동작하지 않는다.** 그 위에 실시간(F4)을 얹으면 안 되는 기능 위에 얹는 것이 된다
- **착수 전에 할 일** — 이 표의 항목마다 **"무엇이 통과하면 끝인가"** 를 실행 명령·응답 코드로 적은 목표 표를 먼저 고정한다
  (`phase-goal-loop.md §1`). 지금 이 표는 *무엇을 고치나* 까지만 적혀 있다
- ⚠ **아래 「고칠 항목」표의 2·3·6·7 은 서버를 정본에 맞추는 변경이라 프런트 검사를 반드시 깨뜨린다** — 서버 수정과
  그 검사 수정을 **같은 커밋**에 넣는다(`parallel-agents-git.md §18`). R2 에서 `B1` 이 같은 형태를 이미 겪었다(이월 10).
  **목표 표 번호로는 1·2·3·5 이고 같은 4건이다** — 두 표의 번호 체계가 다르므로 인용할 때 어느 표인지 적는다

### 목표 표 — **무엇이 통과하면 끝인가** (착수 전 고정, 2026-09-12)

전항 통과가 완료 조건이다. **검증은 실행 명령·응답으로 적는다** — "고쳤다" 는 완료 근거가 아니다.

| # | 완료 조건 | 검증 |
|:-:|---|---|
| 1 | **매니저 앱에서 비상을 발신하면 취소 버튼이 뜬다** | 앱으로 실제 발신(가짜 응답 아님). 응답에 `emergency_id`(**문자열**) · `raised_at` · `cancelable_until` · `notified` 4개가 있고 클라이언트가 예외 없이 파싱 |
| 2 | 비상 목록 응답 최상위 키가 **`items`** — DTO **2개** | `curl /admin/emergencies` · `curl /staff/emergencies` 둘 다 `.data.items` 가 배열. **`emergencies` 키가 0건** |
| 3 | `EmergencyType` 이 **소문자**로 내려간다 | `curl` 응답의 `type` 이 `vehicle_fault` 형태. 프런트의 대소문자 흡수 코드를 **지우고도** 화면이 동작 |
| 4 | `POST /staff/runs/{runId}/waypoints` 가 **구조화된 코드**를 낸다 | 확정 노선 없는 회차로 호출 → `500` 이 아닌 `4xx` + `§8` 에 등재된 코드. ⚠ **`ROUTE_NOT_CONFIGURED_FOR_RUN` 과 합치지 않는다**(원인이 다르다 — 저쪽은 "고정 노선 부재", 이쪽은 "확정 노선 미산출") |
| 5 | `DELETE /runs/{runId}/emergency/{id}` 가 **`204`** | `curl -i` 의 상태줄이 `204`, 본문 길이 0 |
| 6 | **Directions 15 로 전환** | `app.routing.map.max-waypoints` 가 **17**, 엔드포인트가 `map-direction-15` 계열. 정차지 10개 노선의 외부 호출 횟수가 **전환 전보다 준다**(로그로 계수). ⚠ **이 전환은 일일 호출 할당량을 60,000 → 3,000 으로 줄인다** — 아래 할당량 판정 참조 |
| 10 | **시드에 "자정을 한 번 넘겨도 `confirmed` 인 회차" 가 있다** | `frontend/apps/manager-app/.../real_backend_run_flow_test.dart` 를 **자정 직후에 돌려도 통과**. 지금은 `00:05` 에 `confirmed` 회차가 0건이라 2건이 실패한다. ⚠ **보증 범위는 자정 1회다** — 아래 경계 기록 참조 |
| 7 | `API_SPEC §4.14` 문서 정정 | 에러 목록에서 `404 RUN_NOT_FOUND` 제거 — `§4.15` 와 같은 규칙으로 `403` 하나. **코드 변경 부재**(코드는 이미 옳다) |
| 8 | **백엔드 전체 + 프론트 4종 단독 실행 전항 통과** | ✅ **통과 (2026-09-13 06:45, 메인 `5658ecac`)** — 아래 실측 표 |
| 9 | **목표 1·2·3·5 의 서버 수정과 그 프런트 검사 수정이 같은 커밋** (= 「고칠 항목」표 6·2·3·7) | `git show --stat` 으로 양쪽이 함께 있는지. 4건 각각 |

⚠ **9번이 이 라운드의 함정이다.** 서버를 정본에 맞추는 순간 프런트 검사가 빨간불이 되는데, 갈라서 하면
고치는 사람이 **자기가 깨뜨린 줄 안다.** R2 에서 `B1` 이 같은 형태를 이미 겪었다(이월 10).

### ✅ 목표 8 실측 — **병합 후 단독 실행** (2026-09-13 06:45 · 메인 `5658ecac` · 동시 실행 좌석 0)

| 대상 | 검사 | 실패 | 건너뜀 | 근거 |
|---|:-:|:-:|:-:|---|
| **백엔드** | **1,300** | **0** | 3 | 결과 XML 221개 직접 집계. `:test` 가 `UP-TO-DATE`·`FROM-CACHE` 아님을 확인. 건너뜀 3건은 `NaverGeocodingClientLiveTest`(2) · `NaverDirectionsClientLiveTest`(1) — **자격증명 부재, 환경 문제** |
| `academy-web` | 90 (40파일) | 0 | 0 | `vitest --run` |
| `manager-app` | 67 | 0 | **0** | `hidden:false` 만 계수. **실서버 계약 검사가 실제로 돌았다** |
| `parent-app` | 53 | 0 | ⚠ **6** | 아래 참조 — **코드 결함이 아니라 검사가 박힌 포트를 본다** |
| `baraeda_core` | 17 | 0 | 0 | |
| `baraeda_ui` | 71 | 0 | 0 | |
| **프론트 합계** | **298** | **0** | 6 | |

**전제 조건으로 시드 서버를 병합 코드로 재기동했다** — `ber1-seed` 가 `V2__seed_data.sql` 을 고쳐
체크섬이 바뀌었으므로 `schoolbus` DB 를 재생성하고 Flyway 11개를 새로 적용했다(**코드 결함이 아니라
재구성 신호** — `CLAUDE.md` Flyway 항목). R7(`service_date` 내일 · `confirmed`)이 들어간 것을 직접 확인.
⚠ **이 재기동 전까지 8080 은 수정 전 코드로 돌고 있었다** — `FIX-EM` 좌석의 사고가 그것을 드러냈다.

⚠ **`parent-app` 건너뜀 6건은 `BE-R1` 이 만든 것이 아니고 고칠 대상도 여기가 아니다.**
`real_backend_p1_test.dart:77`·`real_backend_p2_test.dart:80` 이 **`localhost:8082` 를 박아 두고 있어**
`--dart-define` 을 옳게 줘도 안 읽는다. 그 포트는 F3 좌석 것이라 지금 존재하지 않는다.
⇒ **이 6건은 어떤 회차에서도 돈 적이 없다. 커버리지처럼 보이지만 0이다.**
`FE-R2` 이월 11번으로 돌렸다(`docs/frontend/IMPLEMENTATION_PLAN.md §5.3` — **조율자가 한 번 닫았다가 다시 연 항목**).

✅ **2026-09-13 정정 (사용자 확정)** — 9번의 대상이 ~~`2·3·5`~~ → **`1·2·3·5`** 다. 같은 목록이 세 곳에 적혀
있었고 셋이 서로 달랐다(도입부 `2·3·6·7`(고칠 항목 번호) · 목표 9 `2·3·5` · 고칠 항목 표 꼬리 `2·3`).
어느 번호 체계로 읽어도 **목표 1(비상 발신 응답 4필드)이 9번의 대상에서 빠져 있었고**, 그것이 이 라운드에서
프런트가 실제로 파싱하는 **최우선 항목**이다. 두 표의 번호가 **둘 다 `2·3` 으로 시작**해 앞 두 항목이 우연히
같은 것을 가리키는 탓에 어긋남이 드러나지 않았다 — **번호를 인용할 때 어느 표인지 함께 적는다.**

### ✅ 할당량 판정 — **Directions 15 를 쓰고 일일 3,000 호출을 설계 제약으로 받는다** (2026-09-13 사용자 확정)

**사용자가 제공한 사실** — 이 프로젝트의 일일 호출 할당량은 **Directions 5 = 60,000 · Directions 15 = 3,000** 이다.

**조율자 실 API 실측** (6회 호출, 서울→부산, `option=trafast`)

| 경로 | 경유지 0 | 경유지 15 | 경유지 16 |
|---|---|---|---|
| `map-direction-15/v1/driving` | `200` | `200` · 390,994m | `200` · 374,020m |
| `map-direction/v1/driving` (옛) | `200` · 378,918m | **`200` · 390,994m** | **`200` · 374,020m** |

⚠ **옛 경로도 경유지 16개를 받으며 결과가 동일하다.** 경유지 0개와 거리가 다르므로(378,918 → 374,020)
경유지를 실제로 반영한 결과다 — **Directions 5 는 문서가 말하는 5개 상한을 강제하지 않는다.**

**정차지 10개 노선(12개 지점) 기준 일일 처리 용량**

| 구성 | 노선당 호출 | 일일 할당량 | 일일 처리 노선 |
|---|:-:|:-:|:-:|
| 전환 전 — 옛 경로 + 상한 7 | 2 | 60,000 | 30,000 |
| **채택 — `map-direction-15` + 상한 17** | **1** | **3,000** | **3,000** |
| 기각 — 옛 경로 + 상한만 17 | 1 | 60,000 | 60,000 |

**기각한 길과 이유** — 세 번째가 용량이 20배 크지만 **문서에 없는 동작에 의존**한다. 네이버가
Directions 5 에 5개 상한을 실제로 걸기 시작하면 **경유지가 조용히 버려지고**, 증상이 "경로가 이상하다"
로만 보여 발견이 가장 늦다. 이 저장소는 같은 형태(경유지 구분자 문제로 경유지 있는 요청만 조용히
근사값이 되던 상태)를 이미 한 번 겪었다 — `NaverDirectionsGateway` 의 `drivingUri` 자바독에 그 전말이 있다.

⇒ **문서가 보장하는 상한 안에서 동작하는 쪽(Directions 15)을 받고, 일일 3,000 호출을 제약으로 명시한다.**

⚠ **그래서 용량 설계가 필요해졌다 — 일일 회차 수가 3,000 을 넘으면 이 구성은 막힌다.**
확정 배치(회차당 1회) 외에 ②구간 승인 미리보기·경유 지점 지정도 같은 할당량을 쓴다
(`ARCHITECTURE §8.3` 의 계산 소비자 둘). **실제 일일 호출 수를 관측할 수단이 아직 없다.**

### ⚠ 할당량 판정에 딸려 나온 후속 2건 — **`BE-R1` 범위 밖. 별도 단위로 등재한다**

할당량이 1/20 이 되면서 **할당량 초과 경로를 탈 확률도 그만큼 커졌다.** 그 경로의 현재 동작을 조율자가 실측했다.

| # | 증상 | 근거 | 왜 지금 문제가 되나 |
|:-:|---|---|---|
| 1 | **할당량 초과(429)를 3번 재시도한다** | `application.yml` `resilience4j.retry.instances.mapRoute` 의 `ignore-exceptions` 가 `CallNotPermittedException`·`BulkheadFullException` 둘뿐이다. HTTP 오류는 걸러지지 않는다 | 할당량이 떨어진 순간 **한 요청이 3회를 태운다.** 가장 나쁜 시점에 소모가 3배가 된다 |
| 2 | **사용자 문구가 일일 할당량 소진을 설명하지 못한다** | `ErrorCode.MAP_ROUTE_UNAVAILABLE` = `503` + *"경로 조회 서비스에 연결할 수 없습니다. **잠시 후 다시 시도해 주세요**"*. `NaverDirectionsGateway.unavailable()` 이 타임아웃·5xx·서킷 개방·4xx 를 **한 예외로 모은다** | 일일 할당량이 소진된 것이면 **오늘 안에는 다시 시도해도 안 된다.** 문구가 틀린 기대를 만든다 |

⇒ **`BE-R1` 에서 고치지 않는다** — 목표 표에 없고, 재시도 정책과 에러 문구는 포트 계약에 닿는 판단이다.
`FE-R2` 뒤 또는 별도 단위로 등재한다. ⚠ **고칠 때 ①의 `ignore-exceptions` 에 무엇을 적을지가 판단의 본체다** —
`WebClientResponseException` 전체를 넣으면 일시적 5xx 재시도까지 사라진다.

### ⚖ Ruling 274 — **게이트가 찾은 실 결함 1건. 수정 라운드로 돌린다** (2026-09-13 04:25)

`EM` 게이트가 목표 1 을 *"부분 통과 — 좁은 범위만 확인, 화면 전체는 미통과"* 로 판정했다.
**조율자가 독립 실측으로 확인했다.**

| 위치 | 타입 | 실측 |
|---|:-:|---|
| `POST /runs/{runId}/emergency` 응답 (`EmergencyRaiseResponse.emergencyId`) | `String` | 이번 라운드에서 정본에 맞게 고쳐졌다 |
| `GET /runs/{runId}/emergencies` 응답 (`RunEmergencyItemResponse.emergencyId`) | ⚠ **`Long`** | `exception/dto/RunEmergencyItemResponse.java:14` — **고쳐지지 않고 남았다** |
| 앱 파싱 (`emergency_raise_result.dart:16` · **`emergency_item.dart:22`**) | 둘 다 `as String` | Jackson 이 `Long` 을 원시 JSON 숫자로 내보내 **캐스팅이 던진다** |

⇒ **비상 목록이 비어 있지 않은 순간 매니저 앱 비상 화면이 죽는다.** 게이트가 직접 재현했다.

**정본이 문자열을 요구하는 근거 2개** — ①`API_SPEC.md:1727` `§4.14` 응답 표의 `emergency_id` 행이
**`string`** ②`API_SPEC.md:1114` `§4.15` 가 *"발신 응답을 놓친 경우의 `emergency_id` **재취득 경로**도
겸함 — 취소(§4.14 `DELETE`)에 필요"* 라 적는다. **같은 값을 같은 용도로 쓰라는 뜻이라 타입이 갈릴 수 없다.**

⇒ **수정 라운드 `FIX-EM` 발주**(워크트리 `wt/ber1-fix-em` · 포트 8095 · DB `schoolbus_fixem` ·
지시서 `.claude/ber1/FIX-em.md`). **목표 1 은 이 수정이 끝나야 통과다.**

#### ⚠ 이 결함이 드러난 경위가 규칙 하나를 다시 증명했다

**목표 1 이 "응답에 필드 4개가 있다" 로만 적혀 있었다면 아무도 못 봤다.** 필드는 4개 다 있었고
발신 경로는 실제로 잘 동작했다. **"앱에서 취소 버튼이 뜬다" 라는 동작 서술이, 같은 화면의 다른
경로(목록 조회)까지 게이트의 시야에 넣었다.**
⇒ **완료 조건은 동작으로 적는다**(`phase-goal-loop.md §1`). 이 라운드에서 두 번째로 값을 했다.

⚠ **같은 계열의 공백이 남아 있다** — 발신·목록·취소 세 경로가 **한 값으로 이어지는지** 검사하는
것이 지금도 없다. `FIX-EM` 완료 조건 4번으로 넣었다.

### ⚖ Ruling 275 — **식별자 타입이 저장소 전체에서 정본과 어긋난다** (2026-09-13 05:45)

⚠ **이 라운드에서 가장 넓은 발견이다. `BE-R1` 범위 밖 — 별도 단위로 등재한다.**

`FIX-EM` 좌석이 *"같은 결함이 `EmergencyStaffItemResponse` 에도 있다"* 고 신고했고,
**조율자가 범위를 재 보니 두 건이 아니라 계통적 어긋남이었다.**

### ✅ 조사 완료 (2026-09-13 12:30) — 산출물 `.claude/ruling275/survey.md` (174줄)

⚠ **조율자가 등재한 "69건" 은 틀렸다. 실제 위반은 15건이다.**

| 세어 본 것 | 조율자 최초값 | 조사 재검증 |
|---|:-:|:-:|
| 절별 표가 식별자를 **`string`** 으로 못박은 곳 | 20곳 | ✅ **20곳** (일치) |
| 같은 표에서 **`number`** 로 적은 곳 | 0곳 | ✅ **0곳** (일치) |
| 응답 DTO 의 `Long ...Id` | ⚠ **69건** | ❌ **재현 불가** — 줄 수 **67** · 일치 수 **77** · 레코드 필드 파싱 **82**. 셋 다 69 가 아니고 **셋 다 위반 건수도 아니다**(사양이 타입을 안 적은 내부 id 까지 센 값) |
| **실제 위반** | — | **15건** (사양이 타입을 명시한 응답 필드 22개 중. **7개는 이미 맞다**) |

#### ⚠ 근본 원인은 드리프트가 아니라 **두 관례의 공존**이다

조사가 찾은 패턴 — **학부모·매니저 앱이 보는 응답(`§2`~`§4`대)은 대체로 `String`**,
**관계자·메인 관리자·알림 계열(`§5`·`§6`대)은 대체로 `Long`.**
무작위 어긋남이 아니라 **두 갈래 내부 관례가 각자 굳은 것**이다.

⚠ **그래서 같은 필드가 DTO 마다 갈린다 — 이것이 핵심 위험이다.**

| 필드 | `String` | `Long` | 비고 |
|---|:-:|:-:|---|
| `run_id` | **1** | **7** | ⚠ **`emergency_id` 보다 넓게 갈라져 있다** |
| `emergency_id` | 2 | 4 | `BE-R1` 이 앱 쪽 2개만 고쳤다 |
| `student_id` | 2 | 3 | 앱은 `String` · 알림·관리자는 `Long` |
| `account_id` | 1 | 1 | 로그인 응답 ↔ 관리자 콘솔 |

⇒ **전체가 틀린 것보다 나쁘다** — 한쪽을 보고 "맞다" 고 판단하게 된다. 실제로 `BE-R1` 에서 그랬다.

#### ⚠ 살아 있는 크래시 1건 — **흡수되지 않았다**

`parent-app/lib/core/runs/domain/run_intent_result.dart:43` 이
**`json['change_request_id'] as String?`** 로 직접 캐스트하는데 서버
(`BoardingIntentToggleResponse:20`)는 **`Long`** 을 낸다. **형제 파일
(`change_request.dart:45`·`:99`)은 `asIdString` 으로 흡수하는데 이 파일만 안 한다.**
주석조차 없어 **알고도 둔 것이 아니라 놓친 것**으로 보인다.
⇒ **`FE-R2` `P` 좌석에 후속으로 배정했다**(도달 가능 여부 확인 → 수정 → 검사 → 전수 계수).

#### 프런트 흡수 색인 — **이미 알려졌지만 묻힌 것들**

- **중앙 흡수 헬퍼가 실재한다** — `parent-app/lib/core/common/json_id.dart` 의 `asIdString()`.
  주석이 *"서버 쪽 사양 위반의 임시 흡수, 클라이언트 버그 수정이 아니다"* 라고 **명시**한다
- 정확한 흡수 주석 **3건**(`emergency_id`·`notification_id`·`approval_id`·`run_id`)
- ⚠ **낡은 주석 1건** — `admin/types/index.ts` 가 *"실제 응답은 `emergencies` 다"* 라고 적는데
  **`BE-R1` 이 `items` 로 이미 고쳤다.** 형제 파일은 정정됐는데 이 주석만 남았다
- ⚠ **대조를 아예 안 한 자리** — `signupRequests.ts` 2개는 `request_id: number` 를 흡수하면서
  **불일치를 언급조차 안 한다.** "발견하고 기록 안 함" 이 아니라 **애초에 대조를 안 한 형태**

#### 남은 판단 — **사용자 몫이다**

| 읽기 | 대상 | 확인된 위반 |
|:-:|---|:-:|
| **A** | 절별 표가 타입을 명시한 응답 필드만 | **15** |
| **B** | A + 표에 타입이 없는 식별자 전부 | **≥17** (`link_request_id`·`report_id` 추가 확정 · **8건 미확인**) |

**`§1.1` 의 "식별자 | 서버 발급 문자열" 이 응답 본문 전체에 걸리는지**가 갈림선이고
**정본 개정이라 조율자가 정할 사안이 아니다.** 조사 좌석도 판정하지 않았다.

⚠ **커버리지 공백 — 조사가 전수가 아니다.** 시각·날짜·좌표·enum·불리언은 **표본만** 봤고
(표본에서는 위반 0), **필수·선택(`●`/`○`)은 미완**이다. 쪼개기 제안은 **7묶음**(`survey.md §4`).

---

### 최초 등재값 (2026-09-13 05:45) — ⚠ **위 조사가 반증했다. 기록으로만 남긴다**

| 세어 본 것 | 값 |
|---|:-:|
| 절별 응답 필드 표가 식별자를 **`string`** 으로 못박은 곳 | **20곳** |
| 같은 표에서 **`number`** 로 적은 곳 | ⚠ **0곳** |
| 응답 DTO 의 **`Long ...Id`** 필드 | ⚠ ~~**69건**~~ → **재현 불가. §위 참조** |

**정본은 한 방향으로만 말한다.** `§1.1` 의 *"식별자 | 서버 발급 문자열"* 은 문면에 경로 파라미터가
함께 적혀 범위를 단독으로는 못 박지만, **절별 표 20곳에 반례가 0곳이라 읽는 방법이 하나뿐이다.**

⚠ **프런트가 이 어긋남을 *보고하지 않고 흡수했다*** — 이것이 가장 나쁜 부분이다.
`academy-web/src/features/emergency/types/index.ts:22` 에 주석이 이렇게 적혀 있다.

> `emergency_id` — **§5.16 표는 string 이라 적었지만 실측은 숫자였다**(`notification_id` 와
> 같은 성격의 사양-실제 불일치, §2).

**어긋남을 알아챈 기록이 코드에 남아 있는데 아무 일도 일어나지 않았다.** 프런트는 사양이 아니라
**서버의 결함에 타입을 맞췄고**(`emergency_id: number`), 그래서 **양쪽이 합의한 상태로 굳었다.**
`notification_id` 도 같다 — 정본 2곳(`:698`·`:1752`)이 `string` 인데 서버는
`StaffNotificationItemResponse`·`NotificationItemResponse` 둘 다 `Long` 이다.

#### 왜 지금까지 안 터졌나 — 그리고 왜 곧 터지나

- **웹(TypeScript)은 런타임 캐스팅이 없어** 숫자를 받아도 조용히 동작한다. 그래서 **증상이 없다**
- **앱(Dart)은 `as String` 이 실제로 던진다.** `BE-R1` 이 앱을 정본에 맞추기 시작하자마자
  **목표 1 에서 바로 크래시가 났다**(Ruling 274). ⇒ **앱이 정본을 따를수록 더 많이 깨진다**
- ⇒ **프런트를 정본대로 고치는 작업(`FE-R2`·F4)이 진행될수록 이 어긋남이 하나씩 터진다**

#### 고칠 때의 판단 본체 — **일괄 변환하지 마라**

1. **`§1.1` 의 범위를 먼저 확정한다** — 응답 본문 전체인가, 표에 적힌 20곳뿐인가.
   **사용자 판정 대상이다**(정본 개정이라 조율자가 정할 사안이 아니다)
2. **69건을 한 번에 바꾸면 프런트 69곳이 동시에 깨진다.** 목표 9(서버+프런트 같은 커밋)가
   이 규모에서 성립하려면 **엔드포인트 단위로 쪼개야 한다**
3. ⚠ **`EmergencyRaiseResponse`·`RunEmergencyItemResponse` 2건만 `String` 이라 지금은 저장소가
   섞여 있다.** 어느 쪽으로 가든 **한 규칙으로** 끝내야 한다 — 섞인 상태가 가장 나쁘다
4. **웹의 "흡수 주석" 을 먼저 찾아 목록으로 만든다** — 그것이 이미 발견된 어긋남의 색인이다

### ⚖ Ruling 276 — ⚠⚠ **실제 도로 경로가 한 번도 쓰인 적이 없다** (2026-09-13 13:05 실측)

**증상** — `NaverDirectionsGateway` 가 응답에서 **`route.trafast`** 를 읽는데 네이버는
**`route.traoptimal`** 을 준다. 파싱이 `null` 로 떨어져 **직선거리 근사 폴백**이 값을 채운다.
**응답 코드는 `200` 이고 예외도 없다 — 그래서 아무 신호가 없다.**

| 실측 | 값 |
|---|---|
| 직접 호출 `map-direction-15/v1/driving` | **`HTTP 200`** · `route` 안의 키 = **`['traoptimal']`** · **`trafast` 부재** |
| 옛 경로 `map-direction/v1/driving` | **같다** — `['traoptimal']`. ⇒ **`BE-R1` 의 전환이 만든 결함이 아니다** |
| 코드 | `NaverDirectionsGateway:188-192` — `response.route().trafast()` |
| 도입 시점 | **`b5083a84`(2026-08-29) 어댑터 최초 도입부터** `trafast` 였다(`git log -S` 로 확인) |

⇒ **어댑터가 생긴 날부터 지금까지 모든 실 호출이 조용히 폴백으로 떨어졌다.**
**노선 거리·소요시간이 도로가 아니라 직선으로 계산돼 왔다.**

⚠ **코드 주석이 틀렸다** — *"`option` 을 지정하지 않았을 때의 기본 탐색 결과 묶음이 `trafast` 다"*
(`:199`). **실측은 `traoptimal` 이다.** 이 주석이 **검증 없이 쓰인 전제**였고 아무도 세지 않았다.

#### 왜 여태 안 드러났나 — 세 겹이 겹쳤다

1. **폴백이 조용하다** — 주석이 *"직선거리 근사가 된다 — 서킷 개방과 달리 `ON_DEMAND` 라도 오류가 아니다"*
   라고 **설계 의도로 적어 뒀다.** 예외도 로그 경고도 없다
2. **유일한 탐지 수단이 항상 꺼져 있었다** — `NaverDirectionsClientLiveTest` 가
   `System.getenv()` 로 자격증명을 보는데, **`backend/.env` 는 docker-compose 용이라 Gradle 시험
   JVM 에 전달되지 않는다.** 키는 **처음부터 있었는데** 닿지 않았다
3. ⚠ **조율자가 후속 6번을 "자격증명 부재" 로 잘못 적었다** — 실제 원인은 **"자격증명이 시험에
   전달되지 않음"** 이다. **키가 없다고 믿어 그 시험을 닫을 생각을 안 했다**

#### 어떻게 드러났나 — **사용자가 "키는 백엔드 것 그대로 쓰면 되지 않나" 라고 물어서**

조율자는 `ls .env*` 결과를 보고 **"키 부재"** 로 판단했었다. ⚠ **그 명령은 zsh 글롭 실패로
전체가 중단된 것**이고, 조율자가 **출력 없음을 부재로 읽었다**(`parallel-agents-git.md §15` 와 같은 형태).
사용자가 *"백엔드에는 `.env` 로 넣었던 걸로 기억한다"* 고 정정해 재확인하니 **값이 그대로 있었다.**

⇒ **자격증명을 시험에 넣어 돌리자 1분 만에 잡혔다.**

```bash
cd backend && set -a && . ./.env && set +a && ./gradlew test --rerun --tests '*Live*'
# NaverGeocodingClientLiveTest   tests=2 skipped=0 failures=0   ← 주소변환은 정상
# NaverDirectionsClientLiveTest  tests=1 skipped=0 failures=1   ← [실 응답을 못 읽어 폴백으로 떨어졌다]
```

#### 고칠 때 판단할 것 — **한 줄 바꾸기가 아니다**

| 갈래 | 뜻 |
|---|---|
| **A. `traoptimal` 을 파싱한다** | 네이버 기본값(실시간+요금 반영 **최적**)을 받는다. **주석의 원래 의도("기본 탐색 결과")와 일치** |
| **B. `option=trafast` 를 요청하고 `trafast` 를 파싱한다** | **최속** 경로. 통학버스에 최속이 맞는지는 **제품 판단** |

⚠ **어느 쪽이든 `NaverDirectionsClientLiveTest` 가 통과해야 끝이다** — 이제 그 시험이 실제로 돈다.
⚠ **자격증명을 시험 JVM 에 전달하는 방법도 함께 정한다** — 안 하면 **다시 건너뛰는 상태로 돌아간다.**

### ⚠ 구현 라운드가 드러낸 후속 4건 — **`BE-R1` 범위 밖. 별도 단위로 등재한다** (2026-09-13 03:40)

위 2건과 별개다. 저 둘은 조율자 실측이고 아래 셋은 **구현 좌석의 자기 신고**에서 나왔다.
셋 다 공통 형태 — **검사가 없어서 결함이 조용히 통과하는 자리**다.

| # | 증상 | 근거 | 왜 지금 문제가 되나 |
|:-:|---|---|---|
| 3 | **`Raw*` 매핑 계층에 단위 시험이 하나도 없다** — 관계자 웹 `admin/api/emergencies.ts` 는 서버 응답을 그대로 쓰지 않고 `Raw` 접두어 어댑터로 한 번 변환한다. 그 매핑 함수를 입력→출력으로 검증하는 시험이 부재하며, 완전히 모킹된 컴포넌트 시험을 통해 간접 실행될 뿐이다 | `EM` 보고서 2항 → ⚠ **`EM` 게이트가 변형 `M9` 로 실측 확증** | **`BE-R1` 이 바로 그 응답 필드를 바꿨다**(봉투 `items` · `EmergencyType` 소문자). 다음에 또 바뀌면 이 계층의 결함이 조용히 통과한다 |
| 4 | **웹 라벨 매핑 경로가 검사 사각지대** — `EmergencyAlertsPage.test.tsx`·`EmergencyList.test.tsx` 둘 다 `TYPE_LABEL`/`toTypeLabel` 렌더 갈래를 실행하지 않고 오류·실패 갈래만 본다 | `EM` 보고서 2항 → ⚠ **`EM` 게이트가 변형 `M8` 로 실측 확증** | 목표 3 이 그 함수에서 방어적 `.toLowerCase()` 정규화를 걷어냈는데, **그 변경을 직접 검증하는 시험이 부재하다.** 백엔드가 소문자 직렬화를 검증하므로 계약 위반은 잡히지만 프런트 매핑 자체는 안 잡힌다 |

⚠ **3·4 는 더 이상 자기 신고가 아니다 — 게이트가 결함을 심어 "아무도 안 잡는다" 를 직접 보였다**(2026-09-13).

| 변형 | 심은 것 | 결과 |
|:-:|---|---|
| **M9** | `emergencies.ts` 의 `toEmergencyItem` 에서 **`raisedAt: raw.raised_at` → `raisedAt: raw.canceled_at`** | ⚠ **`vitest` 40파일 90건이 전부 통과했다.** 전용 `emergencies.test.ts` 가 **부재**하고, `EmergencyList.test.tsx` 는 **그 어댑터보다 위 계층에서 모킹**해 `toEmergencyItem` 자체를 우회한다 |
| **M8** | `TYPE_LABEL`/`toTypeLabel` 의 라벨 문자열 하나를 다른 값으로 치환 | **두 컴포넌트 시험 모두 못 잡았다** |

⇒ **`M9` 의 뜻이 3번의 서술보다 무겁다** — "응답 필드가 바뀌면 못 잡는다" 가 아니라
**"발신 시각 자리에 취소 시각이 찍혀도 전 검사가 초록이다".** 비상 화면에서 **언제 일어난 일인지가
틀리게 표시되는데 아무 신호가 없다.** 고칠 때 **어댑터 입력→출력 단위 시험을 먼저** 만든다.

⚠ **이 두 변형은 "살아남은 변형은 그 자체가 결함 보고" 라는 규칙이 실제로 값을 한 사례다**
(`phase-goal-loop.md §5`). 예상과 달리 통과한 것을 넘기지 않고 적게 한 것이 근거를 만들었다.
| 5 | **새 에러 코드의 `API_SPEC §8` 등재를 강제하는 검사가 부재** | 조율자 계수 — `ErrorCodeCatalogTest` 는 **전수가 아니다**(`ErrorCode.values()` 순회 **0건**, 손으로 고른 12종만 검사. 상수는 71개). `OpenApiCoverageTest` 는 **한 방향뿐**(문서의 코드가 enum 에 있는지만 본다) | **새 enum 코드를 미문서화로 두어도 두 검사 모두 통과한다.** 에러 사전이 코드에서 조용히 멀어진다 |
| 6 | ~~**실 지도 API 를 자동으로 지키는 회귀 시험이 없다**~~ | `DIR` 게이트 §5 (2026-09-13) | ⚠ **2026-09-13 해소 — 그리고 그 시험이 돌자마자 실 결함을 잡았다. 아래 Ruling 276** |

⚠ **6번을 "환경 문제라 괜찮다" 로 읽지 마라.** 자격증명 부재로 건너뛰는 분류 자체는 타당하다.
**분류가 타당하다는 것과 그 시험이 돌았다는 것은 별개이고, 건너뛴 시험은 아무것도 검증하지 않는다**
(`phase-goal-loop.md §5.2 4.1`). ⇒ 고칠 때의 판단 본체는 **"실 API 를 무엇으로 대신할 것인가"** 다 —
CI 에 자격증명을 넣을지, 계약을 고정한 녹화 응답으로 대신할지.

⚠ **5번은 앞선 라운드의 지시서 오기를 정정하면서 드러났다** — `COMMON.md`·`GATE-COMMON.md` 가
`ErrorCodeCatalogTest` 를 "전수 시험" 으로 서술했고, `WP` 좌석이 직접 읽고 신고했다. 둘 다 고쳤다.

⚠ **판정되지 않은 채 남은 것 1건** — `emergency_id` 가 발신(`raise`) 응답에서는 **문자열**인데
(`Long.toString()`), 취소 대상 식별에 쓰이는 경로 변수 `{id}` 는 여전히 **`Long`** 이다(`EM` 보고서 2항).
**목표 1 은 "취소 버튼이 뜬다" 까지만 검사하고 버튼을 눌렀을 때 성공하는지는 어느 목표도 검사하지
않는다** — 불일치가 실재하면 그 갈래가 깨져 있을 수 있다. **`EM` 게이트 리뷰의 판정 대상으로 넘겼다.**

⚠ **1번은 "응답 필드가 있다" 가 아니라 "앱에서 취소 버튼이 뜬다" 로 적었다** — 필드만 채우고 형식이 어긋나면
파싱이 또 죽는다. **동작으로 적어야 그 사고가 걸린다.**

✅ **닫힌 항목** — `§6.14` 강제 확정 성공 응답이 `201` 인지는 **A1 게이트가 백엔드 `@ResponseStatus(HttpStatus.CREATED)`
소스로 확인**했다. 정본과 일치하므로 고칠 것이 없다.

### ⚠ 추가 항목 — **검사가 시각에 따라 스스로 빨간불이 된다** (2026-09-13 00:05 실측)

`real_backend_run_flow_test.dart` 2건이 **코드 변경 없이** 실패로 바뀌었다. 같은 검사가 `22:00` 에는 통과했다.

| 시각 | 결과 |
|---|---|
| 2026-09-12 22:00 | 65 / 65 통과 |
| 2026-09-13 00:05 | **63 통과 · 2 실패** (`Expected: non-empty · Actual: []`) |

**원인** — 시드가 회차를 **하루 중 특정 시각**에 두고, 확정은 *"출발 30분 전"* 에 일어난다. 자정 직후에는
**`confirmed` 상태인 회차가 하나도 없어** 목록이 비고, 그것을 전제로 한 검사가 깨진다.
**DB 를 새로 만들어 Flyway 로 다시 시드해도 같다** — 오래된 데이터 문제가 아니라 **시각 자체가 조건**이다.

⚠ **이것은 환경 문제가 아니라 검사 설계 결함이다.** 인프라는 정상이고 코드도 정상인데 **밤에는 무조건 빨갛다.**
`B1` 이 이미 한 번 손댔으나(고정 `run_id` → 매번 찾도록) **시드에 그런 회차가 없는 시간대**까지는 못 막았다.

**고칠 방향(BE-R1 에서 판정)** — ①시드에 *"언제나 `confirmed`"* 인 회차를 하나 둔다 ②검사가 Clock 을 주입받아
시각을 고정한다 ③창 밖이면 **명시적 사유와 함께** 건너뛴다. **③은 마지막 수단이다** — 건너뜀은 통과가 아니다(`phase-goal-loop §5.2 4.1`).

#### ✅ 해소 — `SEED` 좌석이 ①을 골랐다 (2026-09-13, 커밋 `8beb6c33`)

`migration-local/V2__seed_data.sql` 에 **회차 R7**(`service_date = CURRENT_DATE + 1` · `confirmed` ·
`confirmed_route`·`route_version`·`run_stop`·`run_rider`·`assignment` 전 연쇄)을 추가. 마이그레이션은
만들지 않았다 — 기존 테이블·컬럼에 행만 더하므로 `SchemaContractTest`·`EnumCheckConstraintParityTest` 가
걸리지 않는다(좌석 판단, 조율자 확인).

**②를 버린 근거** — 이 결함은 검사 코드가 아니라 **시드의 시각 설계**가 원인이다. Clock 을 주입해
시각을 고정하면 검사는 통과하지만 **실제 서버가 자정을 넘겼을 때 겪는 상황은 그대로 남는다** —
검사만 속이고 결함은 유지된다.

⚠ **보증 범위의 경계 — 자정 1회다. 이 한계를 알고 받았다.**
`CURRENT_DATE` 는 **시드 적용 시각에 한 번 평가**되므로 R7 은 "시드 적용일 + 1일" 에 고정된다.

| 재기동 없이 지난 자정 | R7 의 위치 | 결과 |
|:-:|---|---|
| 0회 (시드 당일) | 내일 | R1~R6 이 오늘 — 통과 |
| **1회** | **오늘** | **R7 이 오늘 — 통과** (원래 사고가 이 경우였다) |
| 2회 이상 | 어제 | **다시 실패** |

**그 이상을 보증할 수단은 정적 SQL 에 부재하다** — `CURRENT_DATE` 가 계속 따라오지 않는다.
다만 `§3.2` 가 **local 프로파일 기동마다 Flyway `clean` → `migrate`** 를 돌리므로, 서버를 다시 띄우면
시드가 갱신된다. 즉 남는 실패 조건은 **"개발 서버를 이틀 넘게 재기동 없이 띄워 둔 상태"** 다.
그 경계는 `V2__seed_data.sql` 의 R7 주석에도 적혀 있다(*"그 이상 재기동 없이 지나간 날짜는 이 시드가
보증하지 않는다"*). ⚠ **다시 같은 실패를 보면 새 결함으로 진단하지 말고 며칠째 띄워 둔 서버인지 먼저 세라.**

#### ⚠ 같은 좌석이 원인을 못 밝힌 관측 1건 — 별도 단위로 남긴다

`./gradlew test` 직후 **R1·R6 의 상태가 `idle` → `confirmed` 로 바뀐 것**이 관측됐다.
`build.gradle` 의 `app.flyway-clean.suppressed=true` 로 **Flyway 재시드가 원인이 아님은 배제**됐으나,
테스트 부수효과인지 **살아 있던 dev 서버의 `RunConfirmationScheduler` 가 실제 시간 경과로
`confirm_at` 문턱을 넘긴 것**인지 가리지 못했다. 뒤쪽이면 **정상 동작**이다(테스트 프로파일은
`app.run.confirmation.initial-delay-ms: 86400000` 으로 스케줄러를 사실상 끈다 — 조율자 확인).
좌석은 검증 단계마다 서버를 재기동해 우회했다. **판정 수단** — 그 시점에 dev 서버가 떠 있었는지와
해당 회차의 `confirm_at` 이 지났는지를 함께 본다.

### 고칠 항목 (F3 R2 에서 드러난 것)

| # | 항목 | 무엇을 고치나 | 근거 |
|:-:|---|---|---|
| 1 | **Directions 를 5 → 15 로 전환** | `NaverDirectionsGateway` 의 `DRIVING_PATH`(`/map-direction/v1/driving`)와 `app.routing.map.max-waypoints: 7` | 사용자가 콘솔에서 Direction 15 를 이미 켰다. 지금은 경유지 5개 상한이라 **정차지 6개 넘는 노선을 3~4번 나눠 호출**한다. 15 로 올리면 한 번에 17개 지점을 처리해 **호출 수가 약 1/2.5** 로 준다. ⚠ **~~요금·지연 둘 다 이득~~ 은 틀렸다** — Directions 15 의 일일 할당량이 Directions 5 의 **1/20**(3,000 ↔ 60,000)이라 호출 수가 절반이 되어도 **일일 처리 가능 노선은 30,000 → 3,000 으로 줄어든다**(2026-09-13 실측·사용자 확정, 아래 할당량 판정). ⚠ **엔드포인트 경로가 다르므로**(`map-direction-15` 계열) 경로와 상한을 **함께** 바꿔야 하고, 정확한 경로는 착수 시 네이버 문서로 확인한다 |
| 2 | **비상 목록 응답 봉투 — DTO 2개** | `AdminEmergencyListResponse`(`§6.11`)·**`EmergencyStaffListResponse`(`§5.16`)** 의 최상위 키 `emergencies` → **`items`**(`API_SPEC §1.8`·`§5.16` 이 `items[]`·`unacked_count` 를 명시) | A1 게이트 리뷰가 라이브 호출로 재확인. ⚠ **조율자 전제가 틀렸다** — 발주문에 *"B1 이 `§4.1`·`§4.2` 를 같은 이유로 고쳤으니 같은 판정이 걸린다"* 고 적었으나, B1 이 고친 것은 `GET /manager/runs`·`GET /runs/{runId}/roster` 라 **이 엔드포인트 쌍과 무관한 다른 자리**다. 근거는 전례가 아니라 **정본 문면** 이다. ⚠ **범위도 조율자가 안 것보다 넓다** — `/admin` 하나가 아니라 `/staff` 쪽 DTO 에도 같은 결함이 있다. 같은 모듈의 `RunEmergencyListResponse` 는 `items` 를 쓰므로 **모듈 관례가 아니라 이 두 DTO 만의 개별 결함**이다. 프런트는 실제 응답 키에 맞춰 정확히 구현돼 있어 **서버를 고치면 프런트 타입도 같은 커밋에서 뒤집는다** |
| 3 | **`EmergencyType` JSON 직렬화** | 대문자(`VEHICLE_FAULT`) → 정본의 소문자(`vehicle_fault`) | DB 저장은 `LowerCaseEnumConverter` 로 소문자인데 **JSON 직렬화만 enum 이름을 그대로 쓴다.** A1 이 실제 호출로 확인. 지금은 프런트가 대소문자 무관 조회로 흡수 중이라 **서버를 고치면 그 흡수 코드도 함께 정리**한다 |
| 4 | **`§6.14` 강제 확정 성공 응답 코드** | 정본은 `201`. 실제 응답이 무엇인지 **미확인** | 이전 보고가 `200` 으로 적고 있었다. 성공 경로가 시드 제약으로 막혀 재현되지 않았는데, **B1 이 ①구간 회차를 시드에 추가해 병합했으므로 지금은 열렸을 수 있다.** A1 게이트 리뷰에 확인을 지시했다 — 그 결과를 보고 이 항목의 존폐를 정한다 |

| 5 | **`POST /staff/runs/{runId}/waypoints` 가 날것 `500` 을 낸다** | `WaypointCommandService.routeContextOf()` 가 `ConfirmedRoute` 부재 시 `IllegalStateException` 을 던지는데 `BusinessException` 으로 감싸지지 않아 그대로 샌다. **구조화된 코드로 바꾸고 `§8` 에 등재** | W2 갈래가 코드로 원인을 짚었으나 *"결함인지 측정 시점의 우연인지"* 를 못 갈랐고, **게이트 리뷰가 `idle` 회차에서 재현해 실제 결함으로 확정**했다(타이밍 우연 아님). ⚠ **B1 이 신설한 `ROUTE_NOT_CONFIGURED_FOR_RUN`(422) 과 같은 코드로 묶을지 판정이 필요하다** — 저쪽은 *"확정 시점에 대응하는 고정 노선이 없음"* 이고 이쪽은 *"확정 노선(`ConfirmedRoute`)이 아직 산출되지 않음"* 이라 **원인이 다르다.** 합치면 화면이 두 상황을 구별하지 못한다 |

| **6 ⚠최우선** | **비상 발신 응답이 정본과 다르다 — 기능이 실제로 동작하지 않는다** | `EmergencyRaiseResponse` 가 `(Long emergencyId, OffsetDateTime receivedAt)` 둘뿐이다. 정본 `§4.14` 는 **`emergency_id`(문자열) · `raised_at` · `cancelable_until` · `notified`** 4개를 요구한다 | M2 가 실제 호출로 발견, 조율자가 DTO 원문으로 재확인. ⚠ **이 묶음의 다른 항목과 등급이 다르다 — "개선" 이 아니라 "지금 안 되는 것" 이다.** 매니저 앱 클라이언트가 정본대로 파싱하므로 **발신 직후 예외로 죽는다.** 위젯 검사는 가짜 응답으로 돌아 못 잡고 **실 호출에서만 드러났다.** ⚠ **클라이언트를 실물에 맞추면 안 된다** — `cancelable_until` 이 없으면 취소 기능 자체가 사라지고, 단말에서 `raised_at + 1분` 으로 계산하면 시계 오차 문제가 돌아온다(그 판단은 이미 내려져 있다). **B1 이 `§4.1`·`§4.2` 에서 고친 것과 같은 계열**(식별자 문자열화)인데 이 DTO 만 남았다 |
| 7 | `DELETE /runs/{runId}/emergency/{id}` 응답이 `200` + 바디 | 정본 `§4.14` 는 `204`(본문 없음) | M2 실측. 현재 클라이언트가 바디를 안 읽어 **영향은 없다.** 6번을 고칠 때 함께 본다 |
| 8 | **`API_SPEC §4.14` 문서가 낡았다** | 에러 목록이 `404 RUN_NOT_FOUND` 를 `403 FORBIDDEN` 과 따로 적고 있으나, `§4.15` 는 같은 사안으로 이미 정정됐다(Ruling 259(b)) | M2 가 실제 호출로 확인 — **없는 회차로 발신하면 `403` 이 온다.** 즉 **코드가 옳고 문서만 낡았다.** 문서만 고치면 된다(코드 변경 부재) |

⚠ **2·3·6·7 은 "서버를 정본에 맞추는 변경" 이라 프런트 검사를 반드시 깨뜨린다**(이 표의 번호 · 목표 표로는 1·2·3·5). `phase-goal-loop.md §5` 와
`parallel-agents-git.md §18` 대로 **서버 수정과 그 검사 수정을 같은 커밋**에 넣는다. 갈라서 하면 고치는 사람이
자기가 깨뜨린 줄 안다 — R2 에서 B1 이 같은 형태를 이미 겪었다(이월 10).

### ⚖ Ruling 277 — **비상 확인 이벤트가 사양과 두 가지로 어긋난다** (2026-09-13 실측)

**F4-A 1단계 게이트 리뷰가 프론트 시험의 이상을 신고했고, 조율자가 정본 대조로 갈랐다 — 틀린 쪽은 백엔드다.**

`API_SPEC §7.1`(2122행)이 `emergency_acked` 를 이렇게 정한다.

> `emergency_id` · **`acked_by_name`** · `acked_at`. **매니저 채널 전용**

**어긋남 2건** — 둘 다 `global/websocket/EmergencyBroadcastListener.java` 에 있다.

| # | 사양 | 코드 | 결과 |
|:-:|---|---|---|
| 1 | `acked_by_name` (**이름**, 문자열) | `AckedPayload(Long emergencyId, **Long ackedBy**, …)` → SNAKE_CASE 로 **`acked_by`** | 매니저 앱이 **"학원이 확인했습니다" 에 누구인지 표시할 수 없다.** 프론트 파싱은 `acked_by_name` 을 읽으므로 **`null` 캐스팅으로 죽는다**(`Ruling 275` 와 같은 형태 — 이 저장소에서 앱이 두 번 죽은 그 형태다) |
| 2 | **매니저 채널 전용** | `broadcastAcked()` 가 `sendToStaffAndAdmin()` 까지 호출 — **학원·관리자 채널에도 보낸다** | `§7` 채널 표(2100·2101행)의 학원 7종·관리자 6종에 `emergency_acked` 가 **없다.** 관계자 웹이 사양에 없는 이벤트를 받는다 |

**왜 여태 안 드러났나** — 백엔드 시험은 자기가 만든 레코드를 자기가 검사하고, 프런트에는
이 이벤트를 받는 소비자가 **F4-A 2단계 전까지 존재하지 않았다.** 양쪽이 각자 초록이었다.

⚠ **같은 파일의 `RaisedPayload` 에는 `API_SPEC §7.1` 7필드를 인용한 자바독이 붙어 있는데
`AckedPayload` 에는 없다.** 근거 주석이 없는 쪽이 어긋났다.

**판정 — 백엔드를 고친다.** 사양이 이긴다(`CLAUDE.md` — 사실이 어긋나면 `docs/` 가 기준).
①`ackedBy` 로 사용자 이름을 조회해 `acked_by_name` 으로 싣는다 ②매니저 채널에만 보낸다.
**프론트는 이미 사양대로 돼 있으므로 고치지 않는다.**

⇒ **F4-A 2단계(`P`·`M`·`W`)의 선행이다** — `M` 은 이 이벤트를 소비하고 `W` 는 받지 않아야 한다.

---

### ⚖ Ruling 278 — **심은 결함이 살아남았다: `emergency_acked` 배선을 세는 시험이 0건** (2026-09-13)

**`Ruling 277` 게이트 리뷰가 결함을 직접 심어 찾아냈다. 기능 결함이 아니라 *검사 공백* 이다.**

판정 좌석이 심은 변형 4건 중 **3번이 아무것도 깨뜨리지 않았다.**

| 심은 것 | 결과 |
|---|---|
| 이름 대신 id 문자열 | 해당 시험 1건만 실패 ✅ |
| 학원·관리자 채널 복원 | 해당 시험 1건만 실패 ✅ |
| **발행 지점의 이름 조회를 항상 `null` 로** | ⚠ **`exception`+`websocket` 70건 전부 통과 — 아무것도 안 잡음** |
| 목적지를 `managerRun`→`academyLive` 로 교체 | 2건 실패 ✅ |

**원인 — `Ruling 277` 이 원인으로 지목한 바로 그 구조가 그대로 남았다.**
`EmergencyBroadcastListenerTest` 는 **손으로 만든 이벤트로 리스너만 단독 시험**한다.
`EmergencyCommandService.ack()` → 이벤트 발행 → 리스너 → WS 페이로드로 이어지는
**실제 배선을 검증하는 시험이 0건**이다(실측 — `grep -rln 'EmergencyBroadcastListener\|WebSocketBroadcastGateway'
`backend/src/test/java/src/backend/exception/` = **0**).

⚠ **`Ruling 277` 이 "왜 여태 안 드러났나" 로 적은 문장이 *"백엔드 시험은 자기가 만든 레코드를
자기가 검사한다"* 였다. 그 수정이 그 설계를 그대로 남겼다** — 즉 **같은 형태의 다음 결함도
같은 이유로 안 잡힌다.**

**본보기가 이미 있다** — `EmergencyRaisedBroadcastIntegrationTest` 는 `emergency_raised` 를
**HTTP 요청 → 커밋 → 실제 게이트웨이 캡처**까지 검증한다. `emergency_acked` 에만 짝이 없다.

**판정 — 후속으로 통합 시험을 만든다.** 병합은 막지 않는다(기능은 사양대로 동작하고
완료 조건 3항이 실측 통과했다). 함께 처리할 것 — **죽은 필드 `EmergencyAckedEvent.ackedBy`
제거**(호출부 0건 실측, Minor).

⇒ **`F4-A` 2단계와 파일이 겹치지 않으므로 병렬로 돈다.**

---

### 📌 이월 — **시험용 계정 생성기의 로그인 id 가 중복될 수 있다** (2026-09-13, `Ruling 278` 게이트가 남김)

**증상** — 같은 격리 DB 에서 통합 시험을 **초기화 없이 두 번 돌리면** 로그인 id 가 충돌해
시험이 실패한다. 코드 결함처럼 보이지만 원인은 시험 데이터 생성기다.

**원인** — 계정을 만드는 시험용 함수가 **이름(`name`)에는 `nanoTime` 을 섞는데
`login_id` 에는 안 섞는다**(`"직원" + SEQUENCE.incrementAndGet()` 뿐). **같은 픽스처 안의 비대칭**이다.
트랜잭션을 끄고(`@Transactional(propagation = NOT_SUPPORTED)`) 실제로 커밋하는 통합 시험에서만
드러난다 — 롤백하는 시험은 흔적이 안 남아 부딪히지 않는다.

⚠ **계수가 갈렸다 — 고치는 라운드가 직접 세라.** 게이트는 **17파일**, 조율자 재계수는 검색어에
따라 **8~29** 가 나왔다(`staffAccount(` 문자열 · `EmergencyFixtures` 참조 · 정의처가 서로 다른 값을 준다).
**무엇을 세는지부터 정하고 세라**(`phase-goal-loop §6` — `grep | wc -l` 은 줄 수이지 항목 수가 아니다).

**왜 지금 안 고쳤나** — `Ruling 278` 범위 밖이고, 고치면 그 픽스처를 쓰는 시험 전체를 다시
돌려야 한다. **F4 가 끝난 뒤 단독으로 처리한다.**

---

### 📌 조율자 실수 기록 — **발주문에 적은 시험 패키지 이름이 실재하지 않았다** (2026-09-13)

`Ruling 278` 발주문에 실행 범위를 `access`·`authz`·`geocoding`·`config` 로 적었다.
**넷 다 최상위 패키지가 아니라 중첩 경로였다** — `src/backend/student/access` ·
`src/backend/global/security/authz` · `src/backend/student/geocoding` · `src/backend/global/config`.

**결과** — 구현 좌석은 그 넷을 **못 찾아 좁게** 재서 **331**, 게이트는 실제 패키지를 찾아 **338**.
두 값이 어긋나 게이트가 원인을 추적하는 데 시간을 썼다. **둘 다 자기 범위에서는 맞는 값이었다.**

⚠ **`parallel-agents-git.md §18 3.1` 이 이미 요구하는 것을 조율자가 안 했다** —
*"발주 전 `grep -m1 '^package' <그 시험 파일>` 로 선언을 읽고 패턴을 만든다."*
**짧은 이름으로 적으면 좌석이 그 이름의 최상위 패키지를 찾는다.**

⇒ **앞으로 실행 범위는 짧은 이름이 아니라 `grep` 으로 읽은 패키지 선언 전체를 적는다.**

---

### 📌 이월 — **실서버 시험이 주소를 안 받으면 조용히 조율자 서버로 간다** (2026-09-13, 같은 날 3회 발생)

**증상** — 좌석이 *"단위 시험만 돌렸다"* 고 믿는 상태에서 조율자 시드 DB 에 행이 늘어난다.
2026-09-13 하루에 **3회** 났고 누계 **16건**(8 + 2 + 6)이 시드 DB 에 쌓였다가 정리됐다.

**확정된 기제** (게이트 좌석이 끝까지 추적)

```
flutter test --exclude-tags integration   ← 좌석 의도: 실서버 시험 제외
  ↓ 그 앱에 dart_test.yaml 도 tags: 선언도 부재 ⇒ 이 플래그는 아무것도 안 거른다
test/integration/ 의 실서버 시험이 함께 실행
  ↓ --dart-define=API_BASE_URL 미지정
ApiConstants.baseUrl 기본값 http://localhost:8080/api/v1 = 조율자 시드 서버로 실제 발신
```

⚠ **세 번 다 지시서에 "기본값이 8080 이다, 매 실행에 인자를 붙여라" 가 적혀 있었다.**
그래도 났다 — **좌석은 그 인자가 필요한 실행을 돌리고 있다는 자각 자체가 없었다.**
⇒ **경고 문구를 더 넣는 대책은 효과가 없다. 구조로 막아야 한다.**

**고칠 것 (다음 라운드)**

| # | 대상 | 내용 |
|:-:|---|---|
| 1 | `parent-app` · `manager-app` 의 `test/integration/*` | ⚠ **주소가 명시되지 않으면 실패하게 한다.** 지금은 기본값으로 조용히 붙는다. **빠뜨림이 오염이 아니라 실패로 드러나야 한다** — 이것이 본체 |
| 2 | 두 앱의 `dart_test.yaml` | **태그를 선언해 `--exclude-tags integration` 이 실제로 동작하게 한다.** 지금은 그 플래그가 거짓말을 한다 |
| 3 | `academy-web` | 실백엔드 시험을 새로 만들 때 같은 함정을 들이지 않는다(`F4-A` `W` 수정 회차에서 만드는 중) |

**정본 시드 건수 — 발주문에 적어 둘 값**: `V2__seed_data.sql` 의 `emergency_alert` `INSERT` 는
**1건**(id=1 · run 3 · `vehicle_fault`). 좌석도 조율자도 이 값과 대조하면 잔여가 즉시 드러난다.

---

### ⚖ `Ruling 280` — **`F5` 에서 배포를 뺀다. 연결 검증까지만** (2026-09-14 사용자 결정)

> *"배포는 일단 제외하고 F5 에서는 연결 검증까지만 진행"*

**바뀐 것** — `docs/frontend/IMPLEMENTATION_PLAN.md §5` 의 F5 행
(*"실제 API 연결 검증 · Docker 배포"* → **연결 검증까지만**).

**F5 에 남는 것 — 화면 단위 완료 조건 4개**(같은 문서 `§6`)

| # | 내용 |
|:-:|---|
| 1 | **정본 대조** — 화면이 덮는 기능 ID·유저플로우 항목을 1:1 로 확인 |
| 2 | **실제 API 호출** — 백엔드를 띄우고 그 화면이 쓰는 엔드포인트를 **전부 실제로** 호출. Mock 으로 대체한 항목은 목록으로 보고 |
| 3 | **예외 경로** — `USER_FLOWS §12`(서버 실패·통신 두절·권한 차단)와 `API_SPEC §8` 에러 코드 중 그 화면에 걸리는 것을 실제로 재현 |
| 4 | **컨벤션** — `docs/frontend/CONVENTIONS_REACT.md` 위반 0 (React 한정) |

**F5 에서 빠지는 것** — Docker 배포 구성·`deploy-web.yml` 재작성·실 배포.

**왜 지금 빼도 되는가** — 배포는 **AWS 실물 자원과 GitHub 비밀값 3개가 부재**해 어차피 막혀
있고(`school-bus-deploy-plan-pending`), **연결 검증은 그것과 무관하게 지금 할 수 있다.**
⇒ 막힌 것을 기다리느라 할 수 있는 것을 미루지 않는다.

⚠ **배포를 "안 한다" 가 아니라 "이 Phase 에서 빼둔다" 이다.** 되살릴 때 필요한 것은
위 비밀값 3개 + AWS 자원이며 코드·절차서는 이미 완성돼 있다.

---

### ⚖ `Ruling 279` — **위치 송신 주기 `5~10초` → `2초`** (2026-09-14 사용자 결정)

> *"위치 전송 주기를 앞당기고 보간을 2초 단위로 하면 괜찮을 것 같아"*

**바뀐 것** — 사양 문면 **12곳**(`FEATURE_SPEC` 3 · `API_SPEC` 5 · `ERD` 3 · `PRD` 3 ·
`ARCHITECTURE` 2 · `TECH_DECISIONS` 1 · `USER_FLOWS` 1 — 중복 포함 계수) · 코드 1곳
(`manager-app` `drive_mode_screen.dart:59` 의 박힌 `Duration(seconds: 8)` → 상수로 빼고 2초).

⚠ **코드의 실제 값은 `8초` 였다** — 사양은 `5~10초` 라 적었으나 구현은 그 범위의 한쪽 끝이었다.
따라서 **주기 변경 배수는 4배**(8→2)이지 2.5~5배가 아니다.

⚠ **`Ruling 232` 기록(1243행)의 `5~10초` 는 고치지 않았다** — 그것은 2026-09-03 당시의 판정
근거를 적은 **과거 기록**이고, 고치면 그때의 판단 맥락이 사라진다. **그 판정의 결론은 2초에서
더 강해진다**(주기가 짧을수록 지도 API 재호출 비용이 커지므로 "재계산하지 않는다" 가 더 옳다).

⚠ **HTML 렌더 2개(`API_SPEC.html`·`OPERATIONS_PLAN.html`)는 갱신하지 않았다** —
`CLAUDE.md` 의 *"사람용 HTML 은 사용자가 명시적으로 요청할 때만 수정"* 규칙 때문이다.
**지금 그 둘은 낡았다.** 필요해지면 사용자가 요청한다.

#### ⚠ 이 결정이 무는 것 — **둘은 안전하고 하나는 재측정이 필요하다**

| 갈래 | 2026-09-09 실측 | 8→2초 적용 | 판정 |
|---|---|---|:-:|
| **위치 수신 처리량** | 포화 **250 req/s**(분산)·190(동시) · 목표 20 req/s | 목표가 **50 req/s** 로 | ✅ 여유 3.8~5배 |
| **디스크** | `run_position` 219B/행 · 90일 보존 **2.13GB** · 5년 총 6.1~9.3GB · 디스크 50GB | 회차당 540행 → **1,350행**(운행 45분). 90일 보존 **약 5.3GB** · 5년 총 **9~15GB** | ✅ 50GB 안 |
| ⚠ **WS 팬아웃** | 목표 규모 **약 40,000건/s 가 통과선 그 자체(여유 부재)** · 개선 후 60,000 통과 · **80,000 붕괴** | 산술적으로 **160,000건/s** | ❌ **재측정 필요** |

⚠ **다만 그 40,000 은 최악값이다** — 측정 당시 **전 세션을 관제 채널 하나에 몰았다.**
실제로는 학부모가 **자기 자녀 회차 토픽만** 구독하므로 훨씬 낮을 가능성이 크고,
**그 재측정은 이미 "다음에 할 것" 으로 등재돼 있다**(`backend/report/2026-09-09-부하-한계-측정.md`).

**붕괴 서명을 미리 적어 둔다** — STOMP 아웃바운드 큐 폭증(최대 520만) → 힙 3.8GB →
Hikari 획득 30초 타임아웃 → **위치 수신 HTTP 자체가 실패.**
⚠ **지도가 느려지는 것이 아니라 위치가 아예 안 들어온다.** 증상으로 오인하지 마라.

⇒ **그래서 주기를 상수로 뺐다. 되돌리는 데 숫자 하나면 된다.**
**운영 기본값 확정은 팬아웃 재측정 뒤로 미룬다** — 아래 이월.

### 📌 이월 — **2초 주기의 WS 팬아웃을 실제 채널 분포로 재측정한다** (2026-09-14, `Ruling 279` 가 남김)

**막는 것** — 운영 배포. **막지 않는 것** — `F4-B` 완료(개발·데모 규모에서는 문제가 부재).
**⚖ 소유 확정 (2026-09-14 사용자 결정) — 배포 시점으로 미룬다.**
*"배포로 미뤄줘"*. **`F5`(연결 검증)에 넣지 않는다** — 운영 배포만 막는 항목이고
배포 자체가 AWS 자원·GitHub 비밀값 부재로 대기 중이라(`Ruling 280`), 그 둘을 같은 시점에 묶는다.
⚠ **배포를 다시 열 때 이 항목을 선행 조건으로 읽어라** — 안 재고 올리면 붕괴 서명이
"지도가 느려짐" 이 아니라 **"위치가 아예 안 들어옴"** 으로 나타난다.

**할 일** — 전 세션을 관제 채널에 몰지 말고 **학부모가 자기 회차 토픽만 구독하는 실제 분포**로
방송량을 다시 잰다. 그 값이 60,000건/s 아래면 2초를 그대로 두고, 넘으면 주기를 되돌리거나
아웃바운드 스레드를 다시 조정한다(`app.ws.outbound.core-pool-size`).

---

### 📌 이월 — **베이스를 상속하지 않은 시험이 공유 운영 Redis(`16379`)로 간다** (2026-09-14, `f4b-redis` 가 찾음)

⚠ **`parallel-agents-git.md §13.2`(Flutter 가 주소를 안 받으면 조율자 서버로 감)와 같은 형태다.**
**인자를 빠뜨리면 격리가 꺼지는 것이 아니라 공유 자원으로 넘어간다.**

`application.yml:44` 기본값이 `localhost:16379` 이고 `build.gradle` 에 **시험용 재정의가 부재**하다.
⇒ `RedisTestContainerBase` 를 상속하지 않은 `@SpringBootTest` **141개**(152−11)가 전부 **공유
컨테이너를 가리키는 연결 팩터리**를 들고 있고, **그중 4개가 실제로 붙는다**(단독 실행 + 연결 증분 실측).

| 클래스 | 무엇이 붙나 |
|---|---|
| `StaffEmergencyControllerTest` · `AdminEmergencyControllerTest` | `EmergencyCommandService.attachLocationIfCached` 의 `GET run:*:position` |
| `ActuatorHealthTest` · `PrometheusEndpointTest` | `/actuator/health` 의 **Redis 상태 점검**(`INFO`) |

⚠ **뒤 둘이 비상 신고와 무관한데도 붙는다** — **"이 시험은 Redis 와 상관없다" 는 직관이 판정
근거가 될 수 없다**는 증거다.

**시험이 거짓 전제를 자바독에 명시하고 있다** — *"이 클래스는 `RedisTestContainerBase` 를
상속하지 않는다 — **위치 캐시가 항상 비어 있으므로** 정상 경로를 그대로 검증한다"*.
**"상속하지 않음" 은 "Redis 를 안 씀" 이 아니라 "공유 운영 Redis 로 감" 이다.**
지금 통과하는 이유는 **`DBSIZE` 가 우연히 0** 이기 때문이고, 누군가 `bootRun` 으로 앱을 띄워
`run:<id>:position` 이 남으면 **"모두 null" 단언이 남의 데이터를 읽고 깨진다.**

**실측 — 전체 배치 1회당 공유 컨테이너 연결 `+10`** (`HELLO 3` 8회 · `GET run:*:position` 28건 ·
클라이언트 `Lettuce(spring-data-redis_v4.1.0)` · 주소 `192.168.65.1` = 시험 JVM).
⚠ **조율자가 "38시간 314건뿐이니 배치가 공유 컨테이너를 안 쓴다" 로 오독했다** — **적게 쓰는 것이지
안 쓰는 것이 아니다.** 314건은 그동안 배치가 약 30회 돈 결과다.

**처방(좌석 권고)** — 시험 JVM 의 `spring.data.redis.port` 기본값을 **죽은 포트**로 두어,
베이스를 상속하지 않은 채 Redis 에 닿으면 **실패로 드러나게** 한다.
⚠ **위 4개를 함께 처리해야 하므로 별도 라운드가 필요하다.** 금지 문구로는 안 막힌다(`parallel-agents §13` 이 3회 증명).

**소유** — 미정. **`F4-B` 완료를 막지는 않는다**(지금 초록이다).

---

### ✅ 해소 — **전체 실행에서만 Redis 연결이 끊긴다** (2026-09-14, `f4b-redis` · 병합 `357da6aa`)

**근본 원인** — `@Testcontainers`·`@Container` 는 `static` 필드라도 컨테이너 수명을 **시험 클래스
단위**로 관리한다(클래스 끝 `stop()` → 다음 클래스 `start()`, 매핑 포트 변경). 그런데
`@DynamicPropertySource` 가 준 포트는 `LettuceConnectionFactory` 빈 생성 시점에 **한 번만** 읽혀
Spring 컨텍스트에 굳고, 그 컨텍스트를 **설정이 같은 다음 클래스가 캐시에서 재사용**한다.
⇒ 두 번째 클래스가 **이미 파기된 포트**로 접속해 `SocketException: Connection reset`.

**실측 증거** — 1번 클래스 컨테이너 포트 `55079` = 컨텍스트 포트 `55079` 통과 ·
2번 클래스 컨테이너 포트 `55100` ≠ 컨텍스트 포트 `55079` 실패.

**수정** — 컨테이너를 **JVM 당 하나**로 고정(정적 초기화에서 `start()`, 정지 부재. 회수는 Ryuk).

| 단계 | N | 실패 |
|---|:-:|:-:|
| 고치기 전 (최소 재현 묶음 2클래스) | 5 | **4** |
| 고친 뒤 | 5 | **0** |
| **되돌려 심은 뒤** | 3 | **3** |
| 다시 원복 후 | 2 | **0** |

**조율자 독립 확인 (병합 후 단독 실행, 전용 DB `schoolbus_redisdiag` · `--rerun`)**
— **클래스 222 · 검사 1302 · 실패 0 · 오류 0 · 건너뜀 0** (`TEST-*.xml` 합산, 6분 1초).
`:test` 가 `UP-TO-DATE`·`FROM-CACHE` 가 **아님**을 로그에서 확인.
⚠ 좌석 워크트리에서 나던 **건너뜀 3건은 `backend/.env` 미복제** 때문이었고 **메인에서는 0** 이다.

#### ⚠ 이 수정이 바꾼 것 — 다음 사람이 알아야 한다

전에는 `@Container` 때문에 **클래스마다 빈 Redis** 였다(우발적 격리). 지금은 **11개 클래스가
키를 공유**한다. **빈 Redis 를 전제하는 시험**(`keys *` 전수 조회 · 키 부재 단언)을 새로 만들면
깨진다. 현재 그런 시험은 부재.

#### ⚠ 같은 형태가 왜 다른 두 곳에서는 안 났나 — **조율자 직접 계수 (2026-09-14)**

`@Container` 를 쓰는 기반은 **3개**다. 결함이 나려면 **조건 둘이 동시에** 필요하다 —
①컨테이너 주소가 Spring 컨텍스트에 굳고 ②**여러 클래스가 그 컨텍스트를 나눠 쓴다.**

| 기반 | `@DynamicPropertySource` 선언 위치 | 왜 안 났나 |
|---|---|---|
| `RedisTestContainerBase` | **공유 부모에 1개** — 하위 11개가 상속 | ⇒ 같은 컨텍스트 캐시 키 · **두 조건 충족 → 결함** |
| `MigratedPostgresTestBase` | 부모 **0개**, 하위 10개가 **각자 선언** | 선언 위치가 달라 **캐시 키가 갈린다** — 재사용 부재 |
| `BaseTimeEntityAuditingTest` | `@ServiceConnection` · **상속 클래스 0개** | 클래스가 하나뿐이라 ②가 부재 |

⚠ **`BaseTimeEntityAuditingTest` 는 잠재 위험이다** — 이름이 `Base...` 라 상속용으로 보이는데
실제 상속은 0건이고, **같은 설정의 클래스를 하나만 더 만들면 같은 결함이 난다.**

⚠ **`@Container` 를 `grep` 으로 세면 고친 파일이 여전히 걸린다** — 새 자바독이
*"그 두 애너테이션을 쓰지 않는다"* 를 적고 있어서다. **주석을 걷어내고 세라**
(`phase-goal-loop.md §5.1` 과 같은 형태).

**보고서** — `.claude/f4b/report-REDIS.md`

---

### ~~📌 이월~~ — **전체 실행에서만 Redis 연결이 끊긴다** (2026-09-13, 독립 관측 3회) — ✅ **위에서 해소**

**증상** — `EmergencyRaisedBroadcastIntegrationTest` 가 **전체 배치 실행에서만** 실패한다.
단독 재실행은 **항상** 통과한다.

```
org.springframework.data.redis.RedisSystemException: Redis exception
Caused by: io.lettuce.core.RedisException: java.net.SocketException: Connection reset
```

**관측 이력 — 서로 다른 세 주체가 같은 클래스에서 같은 증상을 봤다**

| 관측자 | 회차 |
|---|---|
| `Ruling 278` 구현 좌석 | 전체 3회 중 **2회** 실패 · 단독 2회 통과 |
| `Ruling 278` 게이트 좌석 | 전체 실행 중 **2회** 재현 · 단독 통과 |
| 조율자(F4-A 목표 10) | 전체 1회 실패 · 단독 통과 |

**환경 문제로 분류한 근거** (추측이 아니라 관측)

| 근거 | 값 |
|---|---|
| 예외 종류 | **단언 실패가 아니라 소켓 연결 끊김** |
| Redis 컨테이너 | `running` · `RestartCount=0` · `PING` → `PONG` |
| 단독 재실행 | **3주체 모두 통과** |
| 대상 클래스 | 항상 같음 — Redis 를 쓰는 통합 시험 |

⚠ **다만 근본 원인은 미확인이다.** 연결 풀 크기인지 타임아웃인지 전체 배치 말미의 자원 경합인지
갈리지 않았다. ⇒ **"환경" 으로 닫지 말고 다음 라운드에서 재본다.**

**볼 곳** — ①Lettuce 연결 풀·타임아웃 설정 ②전체 실행 중 Redis 연결 수 추이
③이 시험이 `RedisTestContainerBase` 를 상속하는데 컨테이너 수명이 배치 전체와 어떻게 맞물리는가

⚠ **이 저장소의 Redis 는 이름공간으로 가를 수단이 부재하다**(`parallel-agents-git.md §0`) —
좌석을 여럿 돌리면 같은 Redis 를 공유한다. **그것이 원인이면 병렬 라운드마다 재발한다.**

**소유 — `F4-B` 0단계와 병렬로 도는 별도 좌석** (2026-09-13 사용자 확정)

| 물음 | 답 |
|---|---|
| 왜 F4-B 목표에 안 넣나 | F4-B 8항은 **전부 지도**다. 이것은 백엔드 인프라라 성질이 다르다 |
| 왜 미루면 안 되나 | ⚠ **F4-B 목표 8(전체 단독 실행 · 실패 0)에서 반드시 다시 걸린다.** 착수는 못 막고 **완료를 막는다** |
| 왜 병렬인가 | F4-B 0단계는 **관문이라 결과를 기다려야 한다.** 그 대기 시간에 돈다. 파일도 안 겹친다(Flutter 지도 패키지 ↔ 백엔드 Redis 설정) |
| 동시 실행이 안전한가 | **이번엔 안전하다** — F4-B 0단계는 Redis 를 쓰지 않는다. ⚠ **다른 Redis 작업과는 동시에 돌리지 마라** |

⚠ **이 좌석에는 전용 DB·전용 포트를 주되 Redis 는 공유다.** 조사 중에는 **다른 좌석이 Redis 시험을
돌리지 않게** 조율자가 순서를 잡는다.

---

## 8.3 🔧 `BE-R2` — `Ruling 282`(로그인 실패 차단 무효) + 시험 격리 (2026-09-14 계획 · **착수 전**)

**F5 가 실서버 호출로 드러낸 보안 결함 1건과, 사용자가 지시한 시험 격리 요건을 한 라운드로 묶는다.**
프론트 갈래(`FE-R3`)는 `docs/frontend/IMPLEMENTATION_PLAN.md §5.6` 에 따로 있다 — **서로 파일이 겹치지 않는다.**

### 왜 지금인가 — 실측 근거

| 것 | 실측(2026-09-14) |
|---|---|
| 증상 | 틀린 비밀번호 **6회 연속**에도 `remaining_attempts` 가 **여섯 번 다 `4`** · 계정 미차단 |
| DB | `account.failed_attempts` **0** · `audit_log` 의 `login_fail` **0건**(계약 시험이 수백 번 실패시킨 DB 에서도) |
| 무너지는 것 | ①`C-11`(실패 5회 차단)이 **비밀번호 대입에 상한을 주지 못한다** ②`SYS-01`·`SYS-02` 로그인 실패 감사가 **통째로 빈다** |

### ⚠ 원인은 두 겹이다 — 프로덕션 하나, 시험 하나

**① 프로덕션** — `LoginCommandService.login()` 이 `@Transactional` 인데 실패 경로에서
`BusinessException`(런타임)을 **던진다.** 같은 트랜잭션의 `account.recordLoginFailure()` 더티 체킹
갱신과 `auditLogRepository.save(login_fail)` 이 **롤백으로 함께 사라진다.**

**② 시험** — `AuthControllerTest` 가 **클래스에 `@Transactional` 을 달고 있다**(`:57`).
그래서 MockMvc 요청 5건이 **시험의 트랜잭션·영속성 컨텍스트 하나를 공유**하고, 롤백되지 않은
**메모리 위의 엔티티**를 그대로 읽어 단언이 통과한다. **실제 요청은 요청마다 트랜잭션이 갈리므로
그 갱신이 남지 않는다** — 즉 **시험 구조가 결함을 정확히 가리는 형태**다.

⇒ **프로덕션만 고치면 시험은 여전히 아무것도 보증하지 않는다. 둘 다 고친다.**

### ⚠ 수단은 이미 저장소에 있다 — 새로 만들지 마라

`AuditRecorder`(`audit/service/`)가 **`Propagation.REQUIRES_NEW` 로 감사를 본 트랜잭션과 분리**하고
있고, 자바독이 그 판단 근거를 `Ruling 242` 로 남겨 뒀다. 그 자바독은 심지어
`AccountUnblockCommandService`(같은 트랜잭션에 묶는 쪽)와 **왜 다른지까지** 적어 두었는데,
**"던지는 경로" 는 그 대조에서 빠져 있었다.** ⇒ **적용 범위의 비대칭**이지 해법의 부재가 아니다.

### 목표 표 — 전항 통과가 완료 조건

| # | 완료 조건 | 검증 (이 명령·단언이 통과해야 끝) |
|:-:|---|---|
| 1 | **로그인 실패가 실제로 누적된다** | 실행 중인 서버에 틀린 비밀번호를 5회 보내면 `remaining_attempts` 가 **4·3·2·1** 로 줄고 5회차가 **403 `AUTH_ACCOUNT_BLOCKED`**. ⚠ **시험이 아니라 `curl` 로도 재현되어야 한다** — 이 결함이 시험에서만 통과했던 것이 문제의 절반이다 |
| 2 | **`failed_attempts` 가 DB 에 남는다** | 위 5회 뒤 `select failed_attempts, status from account where login_id=…` 가 **`5`·`blocked`** |
| 3 | **`login_fail` 감사 로그가 남는다** | 실패 1회마다 `audit_log` 에 1행. 차단된 회차에는 `login_fail` + **`block` 2행**(정본이 별행으로 규정) |
| 4 | **미등록 아이디도 감사 로그가 남는다** | `LoginCommandService:102` 의 `forLoginFail(null, null, …)` 도 같은 롤백에 걸린다 — **함께 고친다** |
| 5 | **시험이 이 결함을 잡는다** | `AuthControllerTest` 의 상한 시험이 **프로덕션 수정을 되돌리면 실패**해야 한다. ⚠ 지금은 되돌려도 통과한다 — 클래스 `@Transactional` 때문이다. **그 구조를 어떻게 바꿀지는 네가 정하고 판단 근거를 적어라** |
| 6 | **같은 형태를 전수로 셌다** | "쓰고 나서 던지는" 자리를 계수해 목록으로 보고. 최소 대상 — `AccountUnblockCommandService` · `RunForceConfirmCommandService` · `AuditRecorder` 호출부 전체. ⚠ **고칠 것과 안 고칠 것을 근거와 함께 가른다**(같은 트랜잭션이 옳은 자리가 실재한다 — `Ruling 242` 자바독) |
| 7 | ⚠ **계정 열거를 되살리지 마라** | `Account.REMAINING_AFTER_FIRST_FAILURE` 가 존재·미존재 응답을 같게 만드는 장치다(리뷰 라운드 2 I-1). 수정 후에도 **미등록 아이디의 첫 응답이 존재 계정의 첫 응답과 같은 값**이어야 한다 |
| 8 | **전체 실행 실패 0 · 건너뜀 0** | `./gradlew test --rerun -PtestDbUrl=…` · 결과 XML 에서 계수. 착수 전 **1,302**(222클래스) |

### 시험 격리 — 사용자 지시 (2026-09-14)

> *"테스트 시 하나 하고 데이터베이스 초기화 하거나 별도의 데이터베이스 또는 레디스로 진행하여 같은 문제 안 생기게 해 줘"*

**F5 에서 세 좌석이 "마르는 자원" 에 걸렸다** — 소모되는 정류장 · 흘러간 시각 · 해제된 차단 계정.
**첫 실행만 초록**이었고 `phase-goal-loop.md §5.4` 로 규칙화했다. 이 라운드가 그 구조를 갖춘다.

| # | 완료 조건 | 검증 |
|:-:|---|---|
| 9 | **백엔드 시험이 전용 DB 에서 돈다 — 인자를 잊어도** | 지금 `-PtestDbUrl` 을 **빠뜨리면 공용 `schoolbus` 로 간다.** 기본값을 전용 DB 로 돌리거나, 인자 부재 시 **실패**하게 한다. ⚠ **어느 쪽이든 "잊으면 조용히 남의 DB 를 밟는" 경로가 없어야 한다**(F5 목표 0 과 같은 형태) |
| 10 | **Redis 격리를 전 시험으로 넓힌다** | 📌 F4 이월 2번 — **베이스 미상속 시험 141개가 공유 Redis(`16379`)를 가리킨다**(실제로 붙는 것 4개). `build.gradle:103` 에 **전용 컨테이너를 띄우는 수단이 이미 있다** — 상속 범위를 넓히거나 DB index·키 접두사로 가른다. **수단은 네가 정하고 버린 길을 적어라** |
| 11 | ⚠ **같은 명령을 연달아 네 번 — 네 번 다 실패 0 · 건너뜀 0** | 사이에 `psql`·초기화 등 **어떤 손질도 없이.** 네 회차 수치를 전부 적는다. **이것이 9·10 의 진짜 완료 조건이다** |
| 12 | **표준 실행 명령을 한 곳에 적는다** | `.claude/PROJECT_NOTES.md` 의 `test-runner` 절. 백엔드·웹·앱 3갈래의 **"이 명령을 그대로 쓰면 격리된다"** 를 적는다 — 다음 세션이 인자를 지어내지 않게 |

### 좌석 `BE-C` — **계약 정합 3건** (F5 가 클라이언트만 고친 것들의 뿌리)

**F5 는 앱·웹에서 크래시를 멈추게 했지만 셋 다 원인은 클라이언트가 아니었다.**
정본과 서버가 어긋나 있고, **클라이언트가 서버 쪽으로 맞춰 준 상태**다. 그대로 두면
**다음 소비자가 같은 자리에서 또 죽는다** — 정본을 읽고 만들 것이기 때문이다.

| # | 어긋남 | 정본 | 서버 실측 | F5 가 한 것 |
|:-:|---|---|---|---|
| A | `§4.5` 응답 `next_stop` 의 **내부 필드가 정본에 부재** | 필드명 규정 없음(`remaining[]` 에만 `stop_name` 이 있다) | `stop_name` 을 보낸다 | 앱이 `name` 을 기대해 **모든 도착 처리가 크래시** → 앱을 `stop_name` 으로 고침 |
| B | `§4.2` 명단의 `photo_url` 이 **필수(`●`)** | *"육안 확인용 — 태그 미사용"* · **필수** | ⚠ **시드 학생 6명 전원 `null`** · DB 컬럼도 `nullable` | 앱을 널 허용으로 고침 — **사진 없는 학생이 있으면 화면이 죽던 것** |
| C | `§5.5` 상세의 `route_preview`·`est_*`·`preview_token` 이 **필수(`●`)** | 6필드 전부 필수 | **결정된 건은 전부 `null`** — 백엔드 시험 `StaffApprovalControllerTest#결정된_건의_상세_조회는_재최적화를_실행하지_않는다` 가 **의도로 못 박아 뒀다**(`Ruling 265` 계열) | 웹을 널 허용으로 고침 |

⚠ **B·C 는 방향이 다르다.** C 는 **서버 쪽에 명시된 의도와 근거**가 있으니 **정본이 진다**(조건부 필수로 개정).
B 는 **어느 쪽이 옳은지 정해진 적이 없다** — 정본은 필수라 하고 현실은 전원 부재다.
A 는 **정본에 문장 자체가 없다** — 서버가 이미 정한 값을 정본이 받아 적으면 된다.

**⇒ `phase-goal-loop.md §6` 그대로 — 어느 쪽이 이기는지 근거로 판정하고, 정본이 틀렸으면 정본을 고친다.**
**코드를 조용히 정본에 맞추지 마라.**

| # | 완료 조건 | 검증 |
|:-:|---|---|
| 13 | **A — `§4.5` 응답에 `next_stop` 내부 필드를 적는다** | 서버 응답을 `curl` 로 받아 **실제 키 이름 그대로** 정본에 등재. 앱 모델과 3자 대조(정본·서버·앱)가 일치 |
| 14 | **B — `photo_url` 필수 여부를 판정한다** | 두 길 중 하나를 **근거와 함께** 고른다. ⓐ정본을 `○` 로 내리고 **부재 시 화면 표현**(이니셜 등)을 규정 ⓑ서버가 기본 이미지를 채워 필수를 지킨다. ⚠ **시드 6명 전원이 `null` 이라는 실측을 판정문에 적어라** — 예외가 아니라 기본 상태다 |
| 15 | **C — `§5.5` 6필드를 조건부 필수로 개정한다** | *"대기(`pending`) 건만 채워지고 결정된 건은 `null`"* 을 정본 문면에 넣는다. **백엔드 시험이 그 의도를 이미 고정하고 있으므로 서버는 고치지 않는다** |
| 16 | ⚠ **같은 형태를 전수로 센다** | *"정본이 필수(`●`)라 적었는데 서버가 `null` 을 보내는 필드"* 를 **응답 DTO 전수로** 계수한다. ⚠ **`grep` 으로 세지 마라** — 레코드 필드·중첩 선언을 가르려면 파일을 읽어야 한다(`phase-goal-loop.md §6.0`, 같은 계수를 세 겹으로 틀린 전례) |
| 17 | **정본을 고쳤으면 그 절을 인용한 코드 주석이 낡지 않았는지 본다** | `python3 ~/.claude/tools/docgraph/build.py <repo>` 후 깨진 참조 확인 |

⚠ **`BE-C` 는 `docs/` 를 고치는 유일한 좌석이다** — 다른 좌석은 정본을 **읽기만** 한다.
⚠ **`BE-C` 는 `backend/src/main` 을 고치지 않는다**(목표 14 에서 ⓑ를 고르면 그때 조율자에게 보고).

### 좌석 배정 — **3좌석 병렬**(파일이 겹치지 않는다)

| 좌석 | 대상 | 목표 | 전용 자원 |
|:-:|---|---|---|
| `BE-A` | `account`·`audit` 도메인 + 그 시험 | 1~8 | 포트 `8150` · DB `schoolbus_ber2_a` |
| `BE-B` | `build.gradle` · 시험 기반 클래스 · Redis 격리 | 9~12 | 포트 `8151` · DB `schoolbus_ber2_b` |
| `BE-C` | **`docs/API_SPEC.md` 만** — 계약 정합 3건 | 13~17 | `backend/src` 읽기 전용 · 포트 **`8155`** · DB `schoolbus_ber2_c` |

⚠ **`BE-C` 의 포트는 2026-09-14 에 `8152` → `8155` 로 옮겼다** — `FE-R3` 의 `P3` 가 같은 `8152` 를 쓰고 있어
두 갈래를 동시에 돌리면 `BE-C` 의 `curl` 조회가 `P3` 서버로 간다. 실패가 아니라 **조용한 오염**이라
판정이 나중에 뒤집힌다(`parallel-agents-git.md §13.2` 와 같은 형태).

⚠ **`BE-B` 가 `build.gradle` 을 고치는 동안 `BE-A` 가 그 파일을 건드리면 병합에서 충돌한다** —
`BE-A` 발주문에 **"`build.gradle` 을 고치지 마라. 필요하면 보고하라"** 를 넣는다.
⚠ **`BE-B` 의 변경은 `BE-A` 의 판정 명령을 바꾼다** — 병합 순서를 **`BE-A` → `BE-B`** 로 두고,
병합 후 전체 실행은 `BE-B` 의 새 명령으로 한 번 더 돌린다.

### ✅ `BE-B` 종결 — 시험 DB·Redis 격리 + 조율자 판정 2건 (2026-09-14)

**판정문** — `.claude/ber2/report-BE-B.md`(좌석이 파일을 못 써 조율자가 받아 적음 · 3회 분할).
**커밋** — `9dc43753`(브랜치 `ber2-b`, 분기점 `2ea66814`). 목표 12 산출물은 git 밖 문서 2종.

**고른 길** — ①DB 는 **인자 부재 시 실패**(경고 아님) ②Redis 는 **자동 격리**(인자 불요).
⚠ **이 비대칭에 근거가 붙어 있다** — DB 격리는 **좌석마다 다른 이름**이 필요해 사람이 그 이름을
대야 하지만, Redis 는 **어느 시험이 오든 전용 컨테이너 하나**면 충분하다. **댈 이름이 없으니
실패시킬 이유도 없고, 자동 주입이 유일하게 "잊어도 안전한" 형태다.**

#### 조율자 독립 실측

**① 목표 9 — 인자를 빼고 직접 돌렸다**

| 명령 | 결과 |
|---|---|
| `./gradlew test` · `./gradlew build` | **BUILD FAILED** + 안내 문구 ✅ |
| `./gradlew compileJava` · `help` | **BUILD SUCCESSFUL** ✅ |

⚠ **`compileJava`·`bootRun` 을 안 막은 것이 맞다** — 병합 직후 컴파일 확인이 이 저장소의 필수
절차라(`parallel-agents-git.md §10.5`) 그것까지 막혔으면 조율자가 매번 걸린다.

**② 목표 10 — 좌석이 "미확인" 으로 남긴 것을 조율자가 실증했다**

좌석은 *"격리가 실제로 격리됨을 보이는 음성 대조를 이번 회차에 재수행하지 않았다 — 앞 회차
요약을 근거로 삼았다"* 며 **미확인으로 남겼다. 옳은 처신이다**(요약은 파생본이고 파생본은 낡는다).

**조율자가 베이스를 상속하지 않는 경로로 실행하며 컨테이너를 직접 관측했다.**

```
docker ps  (시험 실행 중)
  redis:7                     0.0.0.0:59723->6379/tcp   ← 전용 · 임의 매핑 포트
  testcontainers/ryuk:0.14.0  0.0.0.0:59721->8080/tcp   ← 회수 사이드카
  school-bus-redis-1          0.0.0.0:16379->6379/tcp   ← 공유(건드리지 않음)
```

**전용 컨테이너가 공유 Redis 와 별개로 뜬다.** ✅ **목표 10 통과.**

⚠ **딸려 오는 성질 하나** — 커스터마이저가 **모든** Spring 시험 컨텍스트에 붙으므로, Redis 를
전혀 안 쓰는 시험도 그 컨테이너를 띄운다(정적 초기화에서 시작). **JVM 당 하나라 낭비는 아니지만,
이제 Spring 시험 전부가 Docker 를 요구한다** — Postgres 때문에 어차피 요구하던 것이라 실질 변화는 부재.

#### ⚖ Ruling 286 — 목표 11 은 **조건부 통과. 병합 후 단독 실행으로 확정한다**

⚠ **좌석이 스스로 판정을 조율자에게 넘겼다** — *"라운드 3 이 4번째 시도에서야 통과했다.
수동 개입은 없었으나 '각 회차 첫 시도가 무결함' 이라는 엄격한 해석으로는 미충족이다.
임의로 '4회 전부 통과' 로 단언하지 않는다."* **이 처신이 판정의 질을 지켰다.**

**조율자 판정 — 이 조항의 목적은 "마르는 자원" 을 잡는 것이다**(`phase-goal-loop.md §5.4`).
그 기준으로 보면 아래가 갈린다.

| 마르는 자원이라면 | 실제 관측 |
|---|---|
| **같은 시험**이 N회차부터 **단조적으로** 실패한다 | **실패 클래스가 시도마다 전부 달랐다**(19건·18건·27건, 겹침 부재) |
| 회차가 갈수록 나빠진다 | **라운드 4 가 첫 시도에 통과**했다 |
| 원인이 상태 소비다 | 원인이 **`SQLSTATE 53300`**(연결 상한) · 성공 직전 실측 **44/100** |

⇒ **마르는 자원이 아니다. 목적은 충족됐다.**
**다만 "통과" 를 지금 선언하지 않는다** — 세 갈래 좌석이 동시에 도는 중의 실패 목록은
**증거 능력이 부재**하다(`parallel-agents-git.md §9.5`). **전부 멈춘 뒤의 단독 전체 실행이
유일하게 결정적인 측정**이고, 조율자는 그것을 어차피 돌린다. **그 결과로 확정한다.**

##### ⚖ Ruling 286 갱신 — 좌석 단위로는 **여섯 회차 무결함**이 확정됐다

좌석이 **1·2회차의 코드 상태를 확정할 수 없다**(조율자가 돌린 회차라 시각을 못 본다)고 신고하고,
**다시 돌리는 대신 5·6회차를 새로 더해** `3→4→5→6` 을 확정된 코드 상태 아래 4연속으로 만들었다.
*"이미 끝낸 것은 다시 돌리지 마라"* 와 *"네 변경 이후여야 한다"* 를 **둘 다 지키는 방법**이었다.

**조율자가 `reflog` 로 1·2회차도 확정했다** — 좌석이 볼 수 없던 값이다.

```
9dc43753  ber2-b@{13:54:37}: commit  (목표 9·10)
2ea66814  ber2-b@{13:28:26}: branch: Created from 2ea66814
조율자가 센 회차 — 결과 파일 최종 기록 14:06:56 · 집계 14:10:12 · 도는 워커 0건
```

⇒ **그 실행은 커밋보다 12분 뒤에 끝났다. `9dc43753` 이후가 확정**이고 집계값도 일치한다
(222클래스 · 1,302 · 실패 0 · 건너뜀 0). ⇒ **1~6회차 전부 확정 코드 상태 아래 무결함.**

⚠ **`reflog` 가 처음부터 답을 갖고 있었다 — 그래도 좌석의 "미확인" 이 옳다.**
**볼 수 없는 값을 추측해 "이후일 것" 이라 적었으면 판정 근거가 무너졌다.**
`parallel-agents-git.md §8` 이 `reflog` 를 세 번이나 판별 수단으로 기록한 이유가 여기서도 같다 —
**그 ref 가 언제 그 값이 됐는지를 직접 주므로 좌석도 조율자도 같은 값을 본다.**

⇒ **남은 것은 병합 후 단독 실행 하나뿐이다.** 좌석 단위 판정은 끝났다.

#### 📌 `BE-B` 가 남긴 이월 2건

| # | 항목 | 소유 |
|:-:|---|---|
| 1 | **`max_connections=100` 대 동시 좌석 수** — 근본 해소는 `maxParallelForks`·Hikari 풀 크기 조정인데 **공유 파일이라 좌석 단독 판단으로 바꾸지 않았다**(옳은 자제). 지금은 `PROJECT_NOTES.md` 에 재시도 안내로 대체 | 다음 라운드 · 조율자 판단 |
| 2 | `deploy-backend.yml` 이 실제 CI 에서 도는지 **미검증** — 로컬에서 문법·인자 전달만 확인 | 배포 시점(`Ruling 280` 으로 지금은 범위 밖) |

⚠ **`§8.3` 의 "인자를 잊으면" 사고가 이제 구조로 막혔다** — `parallel-agents-git.md §13.2` 가
*"경고를 한 줄 더 넣는 대책은 효과가 없다. 세 번 증명됐다"* 고 적은 그 자리다.
**백엔드 갈래는 이 라운드로 그 구조를 갖췄고, 웹 갈래는 `W3` 가 같은 회차에 갖췄다.**

---

### ✅ `BE-A` 종결 — `Ruling 282` 수정 + 조율자 독립 재현 (2026-09-14)

**판정문** — `.claude/ber2/report-BE-A.md`(좌석이 파일을 못 써 조율자가 받아 적음).
**커밋** — `acb64e9c` · `0ba310f6` · `49592ead` (브랜치 `ber2-a`, 분기점 `2ea66814`).

**수정 방식** — `@Transactional(noRollbackFor = BusinessException.class)`.
트랜잭션은 하나로 두되 **그 예외만 롤백 대상에서 뺐다.** 실패 응답을 던지는 일과 그 실패를
기록하는 일은 **둘 다 커밋돼야 하는 서로 다른 일**이라는 판단이고, 세 대안을 근거와 함께 버렸다.

#### 조율자 독립 재현 — 좌석 보고를 받지 않고 직접 쟀다

**① 동작 확인** — `ber2-a` 브랜치를 `8150` 에 띄워 처음 결함을 잡았던 방법 그대로 다시 쟀다.

| 착수 전 | 수정 후 |
|---|---|
| 6회 다 `remaining_attempts: 4` | **`4·3·2·1` → 5회차 `403 AUTH_ACCOUNT_BLOCKED`** |
| `failed_attempts=0` · `active` | **`failed_attempts=5` · `blocked`** |
| `login_fail` 0건 | **`login_fail` 5행 + `block` 1행**(시드 `block` 1행과 별개) |

**② 심은 변형을 조율자가 직접 재현** — 일회용 작업 폴더(`verify-282`)와 전용 DB 를 따로 만들어
`noRollbackFor` 를 지우고 그 시험만 돌렸다. **좌석 보고와 정확히 일치했다.**

```
검사 22 · 실패 3 (나머지 19개는 통과)
❌ 로그인_실패가_상한에_도달하면_계정이_blocked_로_전이한다  expected:<3> but was:<4>
❌ 실패_몇_회_후_성공하면_failed_attempts_가_0_이_된다        expected: 2 but was: 0
❌ 미등록_login_id_의_실패도_감사_행이_남는다                 expected: 1 but was: 0
```

⚠ **`§5` 의 두 조건을 둘 다 만족한다** — 결함에 직결된 것만 실패했고(22 중 3),
**기존 단언 19개는 통과**했다. 원복 후 트리 빈 결과 확인 · 작업 폴더·DB 삭제 완료.

#### ⚖ Ruling 285 — 목표 7 을 **조율자 실측으로 닫는다**

좌석이 **"미확인"** 으로 남겼다(*"롤백 시점만 바꿀 뿐 로직을 안 건드린다는 설계상 근거는 있으나
직접 재확인은 안 했다"*). **옳은 처신이다 — 기억으로 통과를 채우지 않았다.**

**조율자가 잰 값으로 닫는다.**

```
미등록 아이디 nonexistent_user_xyz → {"remaining_attempts":4}
존재 계정 staffA 첫 실패          → {"remaining_attempts":4}
```

**두 응답이 같다.** `Account.REMAINING_AFTER_FIRST_FAILURE` 가 살아 있고 계정 존재 여부가
응답으로 새지 않는다. ✅ **통과.**

⚠ **근거가 둘이라 더 강하다** — 좌석이 뒤이어 신고한 대로 **이 라운드 이전부터 있던 시험**
`AuthControllerTest#미등록_login_id_의_실패_응답이_존재하는_계정의_첫_실패와_같다`(`:311`)가
같은 것을 자동으로 검사하고 있었다(생성 커밋 `6387960e` — 조율자 확인). 그 시험은 **에러 본문의
키 집합과 값을 둘 다 대조**한다. ⇒ **자동 검사 + 실서버 실측 두 갈래가 같은 결론을 낸다.**

#### ⚖ 정정 — `AdminBlockedAccountControllerTest` 실패의 원인 귀속

좌석이 판정문 2항에 *"1회차의 그 실패는 **내** curl 검증이 만든 오염"* 이라고 적었으나,
**뒤이어 스스로 정정했다** — 조율자의 확인 curl(KST 14:15~14:16)과 좌석 1회차 종료 시각
(UTC 05:16:00 = KST 14:16:00)이 **겹친다.** 즉 **조율자 쪽이 원인이거나 최소한 함께 작용했다.**

**어느 쪽인지 특정하지 못한다. 특정할 필요도 없다** — 두 경우 다 **코드 결함이 아니라 오염**이고,
4회차(실패 0)가 그 뒤에 돌았다. **판정문의 그 인과 서술만 부정확했다.**

⚠ **좌석을 탓할 일이 아니다** — 조율자가 **도는 중인 좌석의 DB 에 실제 요청을 보낸 것**이
원인 제공이다. 다음 라운드에 같은 확인을 하려면 **좌석 DB 가 아니라 별도 DB 를 따로 만들어라**
(이번 `verify-282` 처럼).

#### 📌 조율자가 찾은 부수 효과 1건 — 고치지 않고 기록만 한다

⚠ **결함이 고쳐지자 `CorsCredentialsTest` 가 회차마다 감사 행을 하나 남긴다.**
그 시험(`:54`)은 CORS 헤더를 보려고 **존재하지 않는 계정으로 로그인을 시도**하는데,
`@Transactional` 도 정리 코드도 없다. **전에는 그 행이 롤백으로 사라졌다 — 그것이 바로 결함이었다.**

- **실측** — `schoolbus_ber2_a` 에 `actor_login_id='존재하지않는계정'` 행이 **3건** 누적(회차 3회분)
- **해롭지 않다** — `audit_log` 를 세는 시험이 **전부 좁은 필터**를 쓴다
  (`actor_login_id` · `target_id` · `id`). 넓게 세는 시험이 0건이라 깨질 단언이 부재하다
- **운영에서도 문제가 아니다** — 미등록 아이디의 실패를 기록하는 것은 **목표 4 가 요구한 동작**
  (`SYS-01`·`SYS-02`)이고, 보존 정리 스케줄러가 이미 있다
- ⇒ **시험 DB 안에서만 자라고 라운드 종료 시 삭제된다. 조치 불요.**

#### ⚠ 연결 상한 초과 — 병합 후 전체 실행에 그대로 걸린다

좌석의 4회 중 **3회가 `FATAL: sorry, too many clients already`** 로 실패했다(123·14·20건).
**실패 클래스가 매회 달랐고 겹치지 않았으며**, 좌석이 고친 4파일 관련 시험은 **한 번도 등장하지 않았다.**
⇒ **환경 문제 분류가 맞다**(`~/.claude/rules/parallel-agents-git.md §0` 의 "DB 커넥션 총량" 그대로 —
DB 를 갈라도 커넥션 수는 서버 전체 공유이고, 컨텍스트 캐시가 JVM 당 수십 개를 쥔다).

⚠ **좌석이 스스로 "다시 돌리면 통과한다를 결함 부재의 증명으로 삼는 것은 방법론적으로 약하다" 고
적었다. 맞는 말이다.** ⇒ **조율자의 병합 후 전체 실행은 반드시 단독으로 한다** —
다른 좌석이 전부 멈춘 뒤에. 동시 실행 중의 실패 목록은 증거 능력이 부재하다.

---

### ✅ `BE-C` 종결 — 계약 정합 3건 + 조율자 판정 (2026-09-14)

**판정문** — `.claude/ber2/report-BE-C.md`(좌석이 파일을 못 써 조율자가 받아 적음 · 3회 분할 전송).
**고친 파일은 `docs/API_SPEC.md` 하나**이고 `docs/` 는 git 추적 밖이라 커밋이 부재하다.

**맡긴 3건보다 많이 나왔다** — 사양이 `●`(항상 값이 있다)라 적었는데 서버가 `null` 을 보내는 필드가
**13개 · 6개 절**. 배정 밖이던 `§4.1`(`est_duration_min`)·`§6.9`(`photo_url`·`student_phone`)가 신규 발견이다.

**조율자 독립 실측** — 좌석 주장을 그대로 받지 않고 직접 셌다.

| 좌석 주장 | 조율자 실측 | 판정 |
|---|---|:-:|
| 시드 학생 6명 전원 `photo_url` 부재 | `select count(*), count(photo_url) from student` → `6\|0` | ✅ |
| 시드 회차 7건 전부 `est_duration_min` 부재 | `7\|0` | ✅ |
| `§5.5` 의 *"고유 에러 부재"* 가 거짓 | `GET /staff/approvals/{id}` 에 없는 id(9999) → **`404 APPROVAL_NOT_FOUND`** | ✅ |
| `StudentRow` 에 사진 자리 부재 | 생성자 파라미터 8개에 `photoUrl` 부재(`student_row.dart:34~55`) | ✅ |
| `●` 행 213개 = 필드 이름 255개 | — | 미검증(계수 방식은 타당) |

#### ⚖ Ruling 283 — `◐`(조건부 필수) 표기 신설을 승인한다

좌석이 *"`CLAUDE.md` 의 새 상태값 금지에 걸리는지 확신 100%가 아니다"* 로 신고했다.
**걸리지 않는다.** 그 금지의 대상은 **도메인 기능 ID 와 상태머신 값**(회차 `idle`·`confirmed` 등)이고,
`◐` 는 **응답 표의 표기 규약**이라 도메인 어휘가 늘지 않는다.
`○`(없을 수 있다)로 내리면 *"`pending` 건에서는 반드시 있다"* 를 적을 자리가 사라져 **계약이 약해진다** —
좌석의 논거가 맞다. **조건을 함께 적지 않은 `◐` 는 무효**라는 제약도 `§1.13` 에 함께 실렸다.

#### ⚖ Ruling 284 — 지시 범위를 넘은 편집 3건을 유지한다

좌석이 `§4.2` 만 지시받고 `§6.9`·`§4.1` 도 고쳤다고 자진 신고했다.
**유지한다** — `phase-goal-loop §6.4`("이월을 닫을 때 그 항목이 지목한 파일이 아니라 **같은 형태 전체**를
세라")가 요구하는 것이 정확히 이 행동이다. `§4.2` 만 고치고 `§6.9` 를 두면 **관제 화면을 만드는 쪽이
같은 자리에서 다시 죽는다.** 되돌릴 지점(`:813`·`:2098`·`:2099`)도 판정문에 적혀 있다.

#### 📌 `BE-C` 가 남긴 이월 5건

| # | 항목 | 왜 이 라운드 밖인가 | 소유 |
|:-:|---|---|---|
| 1 | ⚠⚠ **사진 육안 대조가 현재 구현에서 성립하지 않는다** — `baraeda_ui` 의 `StudentRow` 에 **사진을 받을 자리가 부재**해, `photo_url` 이 채워져도 클라이언트가 표시하지 못한다. 명단 모델은 값을 받아 **그대로 버린다**(`roster_response.dart:90`·`:109`). `C-06`·`M-03`·`BRD-01`·`RST-02` 가 요구하는 *"사진 + 명단 육안 대조"* 가 **동작하지 않는 상태** | 공유 패키지 수정 + 표시 설계 판단. `P3`·`W3` 둘 다 공유 패키지를 건드리기 전에 보고하게 돼 있어 라운드 중 끼워 넣을 수 없다 | **프론트 다음 라운드 · 최우선** |
| 2 | `§3.12 sent_at` 이 `●` 인데 `null` 이 나간다 — ⚠ **해법이 이미 있고 한쪽에만 적용돼 있다.** 같은 컬럼을 읽는 `§5.17` 은 `COALESCE(sent_at, created_at)` 으로 `●` 를 지킨다 | 서버 수정이라 `BE-C` 경계 밖 | 백엔드 |
| 3 | `§4.3 next_stop.lat`·`lng` 가 `●` 인데 `null` 이 나간다 — 필수로 적힌 **이유가 외부 내비게이션 콜백용**이라, 사양을 내리는 것보다 **서버가 좌표 없는 항목을 안 내보내는 편**이 맞을 수 있다 | 어느 쪽이 이기는지가 새 판정 | 백엔드 |
| 4 | `§5.5` 의 *"고유 에러 부재"* 가 거짓(조율자 재현 완료). 코드가 더 내는 것 — `409 RUN_NOT_CONFIRMED` · `422 ROUTE_NOT_CONFIGURED_FOR_RUN` · `422 ACADEMY_COORDINATES_MISSING`. ⚠ **같은 문구가 `§1.11` 을 인용하며 여러 절에 반복된다** — 전수로 세야 한다 | `§8` 에러 사전 작업과 겹칠 수 있다 | `API_SPEC` 다음 라운드 |
| 5 | `ApprovalQueryService.java:218`·`:293` 의 *"정본 공백"* 주석 2건이 낡았다. `:293` 은 **`BE-C` 개정 이전부터** 낡아 있었다(`Ruling 265` 가 이미 메웠다) | `backend/src/main` 이라 `BE-C` 경계 밖 | 백엔드 |

**조율자가 심은 변형(데이터 탐침) 원복을 직접 확인했다** — `waypoint.removed_at` 빈 값 ·
`run_stop` id 5~8 의 `arrived_at` 전부 빈 값 · 고쳤다고 적은 줄 4개(`:813`·`:849`·`:2098`·`:2099`)가
전부 제자리. 표기 총계 — `●` 211 · `○` 80 · `◐` 4(6필드가 4행에 담긴다 — **행 ≠ 항목**의 실례).

⚠ **`schoolbus_ber2_c` 에 소모된 정차지가 1건 남았다**(목표 13 의 도착 처리). 손대지 않은
`schoolbus` 는 0건이다. **되돌리는 수단이 부재해 좌석이 반복 대상에서 뺐고 그 사실을 적었다** —
`§3.2`. 이 DB 는 라운드 종료 시 삭제하므로 조치가 불요하나, **마르는 자원의 실례로 남겨 둔다.**

⚠ **좌석이 스스로 적은 한계를 그대로 싣는다** — 목표 16 의 계수는 **하한이다.** `null` 이 지역 변수·
삼항식·`Optional.orElse(null)` 을 거치는 경로는 리터럴 위치 대조로 안 잡힌다
(`photo_url` 이 정확히 그 경로였고 방법 ①이 못 잡았다). `§1.13` 에 명시돼 있다.

⚠ **`§5.5` 의 `pending` 건 응답은 측정되지 않았다** — 시드에 `pending` 승인 건이 0개라
코드 경로를 읽어 판정했다. **실행 측정이 부재한 유일한 항목이다.**

## 8.4 ✅ `R4` 병합 — 백엔드 갈래(`r4-null`) 종결 (2026-09-17)

`BE-C` 이월 2·3번(사양이 `●` 인데 서버가 `null` 을 보내는 필드)을 닫은 갈래를 병합했다.
병합 커밋 `ea12d070`. **판정문은 못 받았고 판단 근거는 커밋 메시지가 정본이다**
(`git log 6dfb051a..ea12d070`).

| 커밋 | 대상 | 판정 |
|---|---|---|
| `1140dead` | `§3.12 sent_at` | **정본을 안 고치고 서버를 고쳤다** — `§5.17` 이 이미 `COALESCE(sent_at, created_at)` 으로 `●` 를 지키고 있었다. **해법이 부재한 것이 아니라 적용 범위가 한쪽에만 걸려 있던 것** (`parallel-agents-git.md §4.2.2` 형태) |
| `5086ec82` | `§4.3 next_stop.lat`·`lng` | **정본이 이기고 서버를 고쳤다** — `next_stop` 이 `●` 인 이유가 외부 내비게이션 콜백용이라, 사양을 내리는 대신 좌표 없는 항목(제거된 강제 경유지 `RTE-10`)을 후보에서 제외. ⚠ `stops[]` 전체 목록은 좌표 없는 행을 그대로 보존(`§1.13`) |

⚠ **함께 드러난 것** — 기존 `§3.12` 시험들이 `.exists()` 만 확인해 **`null` 값도 통과하는 약한
검사**였다. 그래서 이 결함이 오래 남았다.

### 병합 후 단독 전체 실행 — **1,305 · 실패 0 · 오류 0 · 건너뜀 0**

전용 DB `schoolbus_verify` 를 새로 만들어 `--rerun` 으로 돌렸다(`3m 46s`, 222클래스).
**1,303 + 신규 2건 = 1,305 로 정합.** 판정은 `build/test-results/test/TEST-*.xml` 을 직접 집계했다.

⚠ **조율자가 한 번 오판했다가 되잡았다** — 실행 중에 XML 222개를 세어 *"1,303 통과"* 로 읽었는데,
**파일 기록 시각이 09:41(측정 시점 12:03)로 이전 세션의 잔여**였다. `--rerun` 이 결과 파일을
미리 지우지 않으므로 **개수만으로는 이번 회차인지 갈리지 않는다.**
⇒ **결과 XML 을 집계할 때 `mtime` 범위를 함께 찍어라**(`phase-goal-loop.md §5.2` 에 딸린 함정).

⚠ **`§8.3` `BE-C` 이월 5건 중 2·3 이 이 병합으로 닫혔다.** 남은 것은 1(사진 — `r4-photo` 가
닫음) 제외하고 **4(`§5.5` 에러 사전 전수)·5(`ApprovalQueryService` 낡은 주석 2건)** 둘이다.

## 9. 열린 항목 — 어느 Phase 를 막는가

### 7.1 개발 전 결론이 필요한 것

| ID | 항목 | 막는 대상 | 성격 |
|---|---|---|---|
| ~~L-06~~ | 만 14세 미만 법정대리인 동의 | **해소 — 착수를 막지 않음** | 서비스 대상이 **만 14세 이상** (2026-08-25 확정) |
| ~~L-07~~ | 위치정보사업 등록·신고 | **해소 — 착수를 막지 않음** | 현 단계는 **테스트 용도**. 대외 서비스 개시 전에 결론 (2026-08-25 확정) |
| ~~⚠ 운영 전환~~ | 법정 요건 L-01~09 전체 | **범위 밖 확정 — 2026-09-04 사용자 결정 "프론트엔드·법정 내용은 앞으로 계속 고려하지 않는다"(Ruling 255)** | 대상 연령이 내려가거나 실사용자를 받으면 위 두 건의 전제가 뒤집힘 (`ARCHITECTURE R7`) |

두 건은 `PRD §7.1` 이 P0 에 배치. **법률 검토 결과에 따라 Phase 5·10 의 범위가 바뀔 가능성이 존재**하므로 그 전에 결론이 필요.

### 7.2 Phase 를 막는 미결정

⚠ **이 표는 파생본이고 실제로 낡아 있었다** — 2026-08-26 실측에서 **L·K·M·R·S·T 6건이 이미 해소**된 상태로 확인됐다(L·K·M·R·T 는 `PRD §10.1.1` "2026-08-24 해소(사용자 확정)", S 는 Phase 3). **미해결로 적힌 항목을 보면 정본에서 "정말 미해결인가" 부터 확인하라** — 해소된 것을 미해소로 믿으면 이미 있는 사용자 결정과 어긋나는 판정을 새로 만들게 된다(Ruling 157 이 그 사고다).

| ID | 항목 | 막는 Phase | 우회 가능 여부 |
|---|---|:-:|---|
| ~~L~~ | 주소 검증 수단 (지오코딩 API) | **해소 — 2026-08-24 사용자 확정** | ⚠ **이 표가 낡아 있었다.** 정본 4곳(`FEATURE_SPEC C-18` · `PRD §10.1.1` · `ARCHITECTURE:160`·`:596`)이 **네이버 API 확정**으로 적는다. Phase 5 가 `GeocodingClient` 포트 + **네이버 실 어댑터**(Resilience4j 보호) + 테스트용 결정론적 스텁을 만든다 (**Ruling 157** 이 149 를 폐기·개정) |
| ~~K~~ | 매니저 근무시간 자료형 | **해소 — 2026-08-24 사용자 확정** | `PRD §10.1.1` 이 **"K 폐기 — 구조화된 시간 범위로 다루고 자유 텍스트를 두지 않음"** 으로 닫았고 `ERD manager.work_hours` 가 같은 문면이다. jsonb 안의 형태(요일 키 × 구간 배열 × `HH:mm`)를 **Ruling 150** 이 고정 |
| ~~R~~ | 학원 코드 형식 규칙 | **해소** | `PRD §10.1.1` 이 **자동 생성**으로 닫았고(2026-08-24), 생성 규칙은 Phase 3 **Ruling 140** 이 고정 |
| ~~M~~ | 학생 주소 수정 반영 시점 (즉시 / 익일) | **해소 — 2026-08-24 사용자 확정** | `PRD §10.1.1` — **"학부모가 일일 승하차지를 등록하는 시점에 검증·등록, 관계자 경유 부재"**(P-06 · STU-05). 확정된 회차로의 소급 부재는 Phase 7·8 소유 (**Ruling 151**) |
| **G** | 노선 최적화 알고리즘 기준 | ~~6~~ **막지 않음** | ⚠ **이 행이 낡아 있었다**(Ruling 178, 2026-08-29). `ARCHITECTURE:595 R5` 가 **판정 수단을 `TECH_DECISIONS §8.5` 로 이미 지목**한다 — 계산 스냅샷 4항(§8.5.1) + 고정 데이터셋 **3종** × 지표 **3종** 회귀 시험(§8.5.2, **지도 API 미호출**). 미확정으로 남는 것은 **가중치 기준**뿐이고 `PRD §7.1` 이 그것을 **P2 후속**(F-01)에 둔다. Phase 6 은 목표 6·7 로 착수 가능 |
| ~~**P**~~ | 지도 SDK 선정 | **범위 밖 확정(2026-09-04 사용자, Ruling 255 — 프론트 영구 제외)** | `MapSurface` 플레이스홀더로 화면 골격은 가능. `LOC-04` 착수 불가. **백엔드 Phase 를 막지 않음** — 프론트 재개 시 결론 필요 |
| ~~**Q**~~ | 강제 노선의 지도 표현 | **범위 밖 확정(2026-09-04 사용자, Ruling 255 — 프론트 영구 제외)** | 강제 추가 승하차지의 지도 표기 확정 필요. **백엔드 Phase 를 막지 않음** |
| ~~A~~ | 등원 회차 자동 하차 알림 발송 여부 | **해소 — 2026-08-31 사용자 확정** | **발송한다.** 등원 최종 도착 처리로 전원이 자동 `alighted` 될 때 학부모 알림을 낸다. 알림 과다 우려보다 **도착 사실이 전달되지 않는 쪽**이 크다고 판정. Phase 9 완료 조건에 등재 |
| ~~**I**~~ | 지연 알림 중복·누적 규칙 | ~~12~~ | **해소 — 2026-09-05 조율자 판정(사용자 위임, Ruling 253).** 갱신 의미("현재 예상 지연 N분", 합산 아님) · 직전 발신과 `minutes`·`reason`·`message` 전부 같으면 `409 DELAY_DUPLICATE` · 전용 테이블 `delay_notice`(V9). F3 S1 `dd98645` 구현, R1 재판정 승인 |
| **J** | 승하차 되돌리기 정책 | **9 부분 해소 · 잔여는 12** | **2026-08-31 사용자 확정** — Phase 9 는 **되돌리기 요청을 받아 상태를 되돌리고 `rider_status_history` 에 적재하는 것까지만** 만든다. **잔여 미결 2건은 정책이 정해지면 추가 구현** — ①허용 범위(언제까지·몇 번까지·회차 종료 후에도 가능한가) ②이미 나간 알림의 정정 방식(Phase 12). ⚠ **범위를 코드 상수로 추측해 박지 않는다** — 지금은 제한을 두지 않는 것이 확정 상태다 |
| ~~S~~ | 학원 완전 삭제 조건 | **해소 — Phase 3** | `API_SPEC §6.3` "물리 삭제 부재 — soft delete 만". 비활성화만 구현 |
| ~~T~~ | 관계자 계정 전달 수단 | **해소 — 전제 소멸** | `PRD §10.1.1` — 관계자도 직접 form 가입하고 메인 관리자가 승인. 초기 비밀번호 발급 개념 자체가 부재. Phase 3 에서 확인 완료 |
| ~~**N**~~ | "운행 시작" 알림 on/off 귀속 | ~~12~~ | **해소 — 2026-09-05 F4 S3.** 문구를 코드(`RunStartedComposer`: 제목 "운행 시작 안내" · 본문 "배정된 회차의 운행이 시작되었습니다.")에서 `API_SPEC §9.7` `run_started` 행으로 승격. 귀속은 등하원 알림 토글(기존 정리) |
| ~~V~~ | 외부 내비 앱의 경유지 개수 상한 | **해소 — 2026-08-31 실측 · 사용자 확정 (Ruling 204)** | **카카오내비 = 승하차지 4곳**(SDK `viaList` 최대 3 + 목적지 1, 공식 레퍼런스 명시). **티맵 = 근거 부재**(앱 실행 스킴 규격 미공개)라 **MVP 에서 미구현**. ⇒ **MVP 는 카카오 단독**이며 `scope=remaining` 도 완료 판정 대상이다. 상한은 코드 상수(규칙 10)이고 `API_SPEC §4.16` 에 근거와 함께 기재 |
| **H** | 승하차지 상세 관리 | ➖ | 보류 — 범위 밖 |
| ~~**W**~~ | **`NTF-06` 지연 알림 전송** (2026-09-02 신설, Ruling 218·220) | ~~미배정~~ | **해소 — F3 S1(2026-09-05, 메인 `eacf545`).** `POST /runs/{runId}/delay` · `DelayNotificationController/CommandService` · `DelayNotice`(V9) · 수신 범위(관계자 전원 + 현재 승하차지 이후, 탑승·`absent` 제외) · NTF-07 항상 발송 · 게이트 92/102. 회차 없음·타 학원·미배치 → `403 FORBIDDEN`(§1.11, Ruling 259(b) — `§4.9` 문면 정정). R1 재판정 승인(⚠ 2 는 F4 S4 시험 보강) |
| ~~**X**~~ | **`§5.19 GET /staff/runs/{runId}/route` 관계자용 확정 노선 조회** (2026-09-03 신설, Ruling 238) | **해소 — F1 S3 `25be27c`~`bf25ac0` (2026-09-04)** | `API_SPEC §5.19`(RTE-02 · A-03·A-08·A-15, 응답 = `§4.3` + `route_version`·`published_at`·`ack{driver, escort}`)가 정본에 실재하는데 **계획의 어느 Phase 범위 열에도 절 번호로 없다.** 코드에는 `§4.3` 매니저용 `RunRouteController` 만 있다. Phase 13 T1 이 `ack` 판정을 재사용하려다 부재를 발견. **Phase 13 은 `X` 를 기다리지 않는다** — 대시보드의 `ack_driver`·`ack_escort` 는 `Assignment.ackedRouteVersionId` 로 직접 판정한다 |
| ~~**Y**~~ | **강제 확정 콘솔 개입 API** (2026-09-03 신설, Ruling 244) | ~~미배정~~ | **해소 — 정본 `API_SPEC §6.14`(F3 S3, Ruling 254) + 구현 F3 S2(2026-09-05, 메인 `7d25605`).** `POST /admin/runs/{runId}/force-confirm` · `SYSTEM_ADMIN`·`Permissions.RUN_FORCE_CONFIRM`(33종) · `RUN_NOT_IDLE`·`RUN_NOT_DUE` · 폴백 강제 `confirmOne(id, true)` 오버로드(배치 경로 동일) · 감사 `audit_log` action `update`+`detail.action=run.force_confirm`(Ruling 260) · 게이트 93/103. R2 재판정 승인 |
| ~~**X-09**~~ | **`run_position` 보유 기간** (2026-09-03 신설, Ruling 243) | **14** | **해소 — 2026-09-04 사용자 확정 90일.** `ERD §7.2` 의 "미확정" 을 90일로 갱신 대상. 실사용 전환 시 L-06~08 검토에서 재조정 여지만 존치 |

### 7.3 미해결 운영 규칙 (`FEATURE_SPEC §8`)

| ID | 문제 | 막는 Phase | 진행 가능 여부 |
|---|---|:-:|---|
| ~~X-01~~ | 운행 시작 창 예외 | **해소 — 2026-08-31 (Ruling 202)** | **±3분 → ±10분.** 예외 절차(지연 사유 입력·원격 해제)는 두 안 모두 폐기 — 창을 넓혀 흡수. 함께 확정된 것 — **출발 30분 전 `confirmed` 시점부터 노선 열람 가능**해야 하며 수단은 RUN-08 |
| **X-02** | ②구간 승인 무응답 | **8** | 현재 규칙(자동 거절 + 기존 노선 유지 + 횟수 미소진)이 확정 서술이라 **구현 가능**. 재검토 여지만 존치 |
| ~~X-03~~ | 동승자 당일 결근 | **해소 — 2026-08-31 (Ruling 203)** | **동승자가 없는 회차는 없다고 가정.** 기사 임시 권한 안은 폐기 — 채택했으면 `C-06`(승하차는 동승자 전용)이 회차마다 갈리는 조건부 규칙이 된다. **Phase 9 권한 모델 불변** |

### 7.4 문서 자체의 미해결

| 항목 | 내용 | 처리 |
|---|---|---|
| **문서 그래프 색인의 깨진 참조 ✅ 0건** (2026-09-20 재색인 기준 — 수치는 돌릴 때마다 다시 센다, `python3 ~/.claude/tools/docgraph/build.py <repo>`) | 도구 `~/.claude/tools/docgraph/`(규칙 `~/.claude/rules/docgraph.md`). **208 → 121 → 0.** 갈래별 처리 — `ruling_not_in_docs` 60건은 코드 주석에만 있던 판정 23개라 **§7.5 로 등재** · `undefined_endpoint` 22건은 `POST /dev/reset` 미등재(→ `API_SPEC §11`)와 경로 변수를 감춘 축약 5곳 · `missing_section` 은 **한 줄에 다른 문서명이 먼저 나와 뒤의 맨 절 번호가 그리로 읽힌 것**이 전부였다 | ⚠ **0건은 유지 대상이지 발주 조건이 아니다** — 새 글을 쓰면 다시 는다. **절을 인용할 때 문서명을 함께 적으면 애초에 안 생긴다.** ⚠ **이 칸에 판정 번호·절 번호를 예시로 적지 마라** — 도구가 이 줄을 그 참조의 기록처로 대신 잡는다(`build._ruling_fallback_records`). 2026-09-20 에 옛 예시를 걷어내고서야 가려져 있던 20건이 드러났다. 남은 **정보성 12건**은 프론트 라운드 판정이 `docs/frontend/IMPLEMENTATION_PLAN.md` 에 기록된 것으로 설계대로다 |
| **테이블 수 선언 불일치** ✅ 해소 | 2026-08-25 실측 정정 — `ERD §3` 의 `####` 항목이 **39**(그룹별 7·7·13·12). `ERD §1` 소계표(7·6·13·10)와 이 문서 Phase 1 완료 조건의 38 표기를 39 로 맞춤. `FEATURE_SPEC §3.2` 는 이미 39 로 일치 | 구현 기준 **39**. 세 문서 전부 일치 |
| **`PRD §7.1` 미배치 6건** | 도메인 STU·MGR·BUS 와 기능 RTE-01·RTE-07·ATT-03 이 P0~P2 어디에도 미기재 | 실행 순서상 Phase 5·6·8 에 포함. 우선순위 배치 확정은 별건 |
| ~~**🔸 표기 항목의 존치 여부**~~ | 구 기획에서 흡수한 항목의 존치가 미확정 (`FEATURE_SPEC §0`) | **해소 — 2026-09-05 F4 조율자 계수.** `grep -c '🔸' docs/FEATURE_SPEC.md docs/PRD.md docs/USER_FLOWS.md docs/API_SPEC.md` 전부 **0** — 미확정 표기가 남아 있지 않다 |
| ~~**`API_SPEC §10` [조정 중] 항목**~~ | 배차·노선 최적화 정책 확정 전까지 경로·권한·목적만 예약 | **해소 — 2026-09-05 F4 S3(Ruling 256).** `§5.8 transfer` 를 `§5.7` 형식으로 확정해 `§10` 표에서 제거, 표는 "현재 `[조정 중]` 항목 부재" 한 줄. 구현 F4 S1(메인 `5292777`). 이전 경위: 2026-08-30 Ruling 197 로 `§5.7 forced-add` 는 Phase 8 이 확정, `§5.8` 은 별도 단위로 남겼던 것 |

### 7.5 코드 주석에만 있던 판정 23건 — 정본 등재 (2026-09-20)

**왜 여기 있나.** 판정 원장이 계획서에 자리 잡은 것은 `Ruling 117` 무렵부터이고, 그 이전 회차의 판정은
**근거가 코드 주석에만 남았다.** 도구가 이것을 `ruling_not_in_docs` **60건**(서로 다른 판정 23개)으로
세고 있었다(§7.4). 내용을 그 주석에서 복원해 아래에 옮긴다 — **주석이 정본이 되는 상태를 끝내는 것이
이 표의 목적**이며, 각 행의 "근거 위치" 가 전문(全文)이 있는 자리다.

⚠ **이 표는 사후 복원분이다.** 판정 당시의 논의 기록은 부재하고 **채택된 결론과 그 근거만** 남았다.
뒤집으려면 새 Ruling 을 발행한다 — 이 행을 고쳐 쓰지 않는다.

| 판정 | 내용 | 근거 위치 |
|---|---|---|
| **Ruling 13** | 감사 로그의 `result`(성공·실패) · `block_event`(차단 이벤트 여부)를 **엔티티가 저장하지 않고 조회 시점에 `action` 값에서 다시 계산.** 저장된 `block_event` 컬럼은 "이 시도가 차단을 유발했는가" 라 응답이 요구하는 "이 행이 차단 이벤트인가"(해제 포함)와 대상이 다름 | `AuditLog` · `LoginHistoryItemResponse` · `LoginHistoryQueryService` (ERD §3.4) |
| **Ruling 26** | 복구 인증 코드의 `purpose` 값은 `login_id` · `password`. 기획 재료 문서의 `recover_id`·`recover_password` 는 **낡은 값으로 폐기** | `VerificationCode` (ERD §3.2 · API_SPEC §2.9) |
| **Ruling 36** | Swagger 태그의 목록·순서·설명을 `OpenApiConfig` **한 곳에서만** 관리하고 컨트롤러는 `@Tag(name=…)` 로 소속만 선언 | `OpenApiConfig` |
| **Ruling 38** | `Assignment`(회차별 매니저 배치)의 소유 모듈은 `run` 이 아니라 **`manager`** | `manager/entity/Assignment` (ERD §3.3) |
| **Ruling 39** | enum 을 `global/common/enums` 로 올리는 기준은 **모듈 2개 이상이 공유.** 한 모듈 안의 두 테이블이 공유하는 값 도메인은 그 모듈에 둔다 | `exception/entity/NoShowDecision` |
| **Ruling 71** | 태스크는 **자기 소유가 아닌 자리표시자를 고치지 않는다.** Phase 2 Task 1 이 부여표(`RolePermissions.HIERARCHY`)를 재작성하면서 `SecurityConfig` 의 배선 한 줄은 소유 밖이라 그대로 둠 | `SecurityConfig#roleHierarchy` |
| **Ruling 76** | 그 배선을 **부여표를 처음 소비하는 태스크**(Phase 2 Task 3)가 한다. 배선 이전에는 `hasAuthority(…)` 기반 애너테이션이 전부 거부로 떨어짐 — **부여표가 빈 것과 배선이 빠진 것은 증상이 같다** | `SecurityConfig#roleHierarchy` · `authz/RolePermissions` (FEATURE_SPEC §6.2) |
| **Ruling 80** | 경로 문자열은 **사양 원문 그대로.** `@PublicEndpoint` 허용 목록과 계정 상태 게이트 대조가 "HTTP 메서드 + 경로" 를 키로 삼으므로 임의로 다듬지 않는다(예 — `/me/link-code` 를 `/me/students/…` 계열로 고치지 않음) | `StudentLinkCodeController` (API_SPEC §3.3) |
| **Ruling 89** | Gradle `test` 태스크만 심는 프로퍼티 `app.flyway-clean.suppressed` 가 참이면 **`clean()` 을 건너뛰고 `migrate()` 만.** 여러 `@SpringBootTest` 컨텍스트가 한 JVM 에서 같은 로컬 Postgres 를 공유할 때 한쪽의 `clean()` 이 다른 쪽이 검증 중인 스키마를 삭제 | `LocalFlywayCleanStrategy` · `FlywayCleanStrategyGuardTest` |
| **Ruling 99** | 로그아웃의 무효화 범위는 **그 단말의 refresh 토큰 하나**(계정 전량이 아님). 재발급은 옛 토큰을 그 자리에서 무효화. 이미 처리된 행은 다시 처리하지 않음 — 두 번째 호출이 최초 시각을 덮어쓰면 이력이 소멸 | `AuthControllerTest` · `DeviceToken#revokeByTokenHash` (API_SPEC §2.6·§2.11) |
| **Ruling 102** | API 버전 접두사를 **`server.servlet.context-path` 가 아니라 `addPathPrefix`** 로 배선. 컨테이너 전역 설정은 `/actuator`·`/ws`·`/swagger-ui` 까지 물게 됨. 컨트롤러 소스의 `@RequestMapping` 리터럴은 접두사 없는 그대로 유지 | `global/config/ApiPathPrefixConfig` · `ApiPathPrefixConfigTest` |
| **Ruling 103** | 공개 엔드포인트 목록을 **서로 독립인 3원 대조**로 고정 — ①소스의 `@PublicEndpoint` 애너테이션 ②`PublicEndpoints` 상수 ③사양에서 손으로 옮긴 하드코딩 목록. **대조 대상이 같은 곳을 참조하면 둘 다 틀려도 검사가 통과** | `global/security/PublicEndpoints` · `ControllerAuthorizationConventionTest` · `ErrorCodeCatalogTest` · `PageParamsTest` |
| **Ruling 104** | JSON 필드명은 **전역 `spring.jackson.property-naming-strategy: SNAKE_CASE`** 가 변환. 개별 `@JsonProperty` 를 붙이지 않는다. ⚠ **쿼리 파라미터는 Jackson 을 거치지 않아 적용 대상 밖** — `@RequestParam` 에 이름을 손으로 적는다. `@ModelAttribute` DTO 로 묶어 `service_date` 가 안 붙고 목록이 늘 오늘로 고정된 사고가 실재 | `application.yml` · `StaffRunController` · `NavigationController` · `WebSocketEnvelope` · `account/dto/*` |
| **Ruling 142** | `user_count` 가 세는 범위는 **소속이 확정됐고 아직 종료되지 않은 계정.** 포함 — `active`·`blocked`(차단은 소속의 일시 정지이지 종료가 아님). 제외 — `pending`(소속 미확정) · `rejected`(소속된 적 부재) · 관계자(`staff_count` 가 따로 셈) | `AdminAcademyQueryService` · `AccountRepository` (API_SPEC §6.1) |
| **Ruling 145** | 계정 상태 게이트의 기본값은 **차단**이다 — `@AllowedWhenPending`·`@AllowedWhenRejected` 허용 목록과 `@PublicEndpoint` 밖의 모든 핸들러는 `pending`·`rejected` 계정에 `403`. **승인된 계정 전용 기능에는 게이트 애너테이션을 붙이지 않는 것이 사양** | `testsupport/gate/AccountStatusGateEndpoints` · `GuardianChildController` · `WeeklyAddressController` · `NotificationSettingController` (API_SPEC §1.4) |
| **Ruling 160** | 요청의 `photo`(멀티파트 **파일 파트**)와 응답·컬럼의 `photo_url`(서버가 저장 후 만든 **주소**)은 **다른 값이라 이름을 가른다.** 등록·수정 요청 DTO 에 사진 필드를 두지 않는 것이 사양 — 두면 클라이언트가 외부 URL 을 그대로 보내 `PhotoStorage` 를 우회 | `StudentRegisterRequest` · `StudentUpdateRequest` · `photo/spec/PhotoStorage` (API_SPEC §5.11) |
| **Ruling 161** | `guardian` 레코드를 **접근 판정 지점이 만들지 않는다.** 보호자·계정 연결을 만드는 것은 가입 승인(AUTH-11) 하나 — 없는 계정에 보호자를 지어 주면 승인을 거치지 않은 사람이 자녀 연결 화면에 진입 | `student/access/GuardianChildAccess` (API_SPEC §1.5) |
| **Ruling 162** | 근무시간 `jsonb` 의 **형태가 어긋난 행은 예외로 드러낸다.** "근무 시간 없음"(`WORK_HOURS_NOT_SET`)으로 삼키지 않는 이유는 등록 시 비워 둔 정상 상태와 고쳐야 할 데이터 결함이 같은 응답이 되기 때문 — 시드에서 실제로 겪은 형태 | `manager/entity/WorkHours` (ERD `manager.work_hours`) |
| **Ruling 171** | 응답의 `*_id` 는 **JSON 문자열.** 근거는 표기 통일이 아니라 **정밀도** — JavaScript `number` 는 2^53 을 넘으면 값을 잃고, 그때는 요청이 실패하는 것이 아니라 **다른 학생을 가리킨다.** ⚠ 검사 함정 — MockMvc `jsonPath(…).value(String.valueOf(…))` 와 JsonPath `?(@.x == '4')` 는 타입을 강제 변환해 숫자 `4` 를 통과시킨다. **값이 아니라 타입을 본다** | `StudentDetailResponse` · `StudentSummaryResponse` · `StudentWithdrawalResponse` (API_SPEC §3.1·§2.10·§5.11) |
| **Ruling 172** | 퇴원(STU-04)이 **보호자 연결까지 같은 트랜잭션에서 해제**(`guardian_student.unlinked_at`). 학생 쪽만 지우면 접근 판정(`unlinked_at IS NULL`)을 지나 **퇴원한 자녀가 옛 보호자 목록에 잔존.** 연결을 **만드는** 경로는 자녀 연결(P-02) 소유이고 여기서 열지 않는다 | `StudentCommandService#퇴원` · `StudentWithdrawalUnlinkTest` (ERD §7.1 · UF-P-01) |
| **Ruling 173** | 자녀 연결의 중복은 **선검사에서든 DB 제약 거부에서든 같은 `409 ALREADY_LINKED`.** 선검사만으로는 부족 — 두 트랜잭션이 서로의 미커밋 INSERT 를 못 보고 둘 다 통과한 뒤 `uk_guardian_student` 가 하나를 거부하며, 옮기지 않으면 `500` 이 나가 "서버 고장" 과 "이미 연결됨" 이 구별되지 않는다(`Ruling 164` 의 요구를 이 자리에 적용) | `ChildLinkCommandService` · `ChildLinkConcurrencyTest` |
| **Ruling 179** | 승하차지 근접 병합(STU-05)의 잠금 범위는 **학원 하나**(`pg_advisory_xact_lock`). 좌표·격자 키는 **기각** — 임계 반경 안인데 다른 칸에 놓인 두 점이 서로 다른 잠금을 잡아 중복이 생긴다. 대가는 매칭 성공 경로까지 학원 단위로 직렬화되는 것이고, 받아들이는 근거는 승하차지 생성이 **저빈도 연산**이라는 것. 잠금 없는 후보 조회를 밖에 두지 않기 위해 잠금·조회를 `StopMergeLookup` 한 메서드로 묶는다 | `StopMergeLookup` · `StopMergeLookupImpl` · `StopRepository` · `StopMergeLockScopeTest` |
| **Ruling 205** | 매니저 앱 실시간 노선(`GET /runs/{runId}/route`)은 **확정 노선에 미승차(③구간) 반영 결과만 얹은 표시용 뷰.** 미경유(skipped)는 **표시만** 하고 재최적화·ETA 재계산 부재(C-05 "주행 판단은 기사"). `next_stop` 은 결번을 건너뛴 다음 실제 정차지이고 `skipped_notice` 가 그 사유를 동반 | `RunRouteController` · `RunRouteQueryService` · `RunRouteResponse` · `AssignmentRepository` (API_SPEC §4.3) |

---

## 10. 갱신 규칙

| 시점 | 갱신 대상 |
|---|---|
| Phase 완료 시 | §8 표의 상태 · 비고 |
| 완료 조건 일부 미통과로 넘어갈 때 | §8 비고에 미통과 항목을 명시 (🟡 유지) |
| 열린 항목이 해소될 때 | §9 에서 제거하고 해당 Phase 의 제약 서술을 정정 |
| 사양이 바뀔 때 | 이 문서는 **규칙 ID 만 참조**하므로 대개 갱신 부재. Phase 범위가 바뀌면 §6 갱신 |
| **첫 배포 시점** | §2.2 · `CLAUDE.md` Flyway 항목 · `ARCHITECTURE §12` 를 **함께** 갱신 |

---

## 8.5 ⚖ `R5` 목표 표 — 이월 정리 라운드 (2026-09-17 계획 · **착수 전** · 메인 `d1061802`)

### 착수 전 재계수 — **이월 13건 중 4건이 이미 닫혀 있음**

`R4` 재개 기록(`.claude/r4/RESUME.md §4`)이 나열한 이월을 `phase-goal-loop §6.3` 대로 항목마다
직접 계수. **낡은 "미해소" 4건 확인** — 그대로 발주했으면 이미 고쳐진 것을 다시 고칠 상태.

| 이월 | 문면 | 실측 | 판정 |
|:-:|---|---|:-:|
| 프론트 3 | `§3.10`·`§3.11` 미구현 | `route_detail_screen.dart`·`bus_position_api.dart` 실재 · `R4` 가 이미 그 화면을 수정 | **닫힘** |
| 프론트 4 | `USER_FLOWS` 결손 **5건** | `A-12` → `UF-M-06` · `A-16` → `UF-X-08` 보완(2026-09-14) · `A-17` → `UF-M-09` 신설 | **3건 닫힘 · 2건 잔존** |
| 프론트 6 | 두 앱 도우미가 `DioException` 을 통째로 삼킴 | 두 파일 다 `if (e.type != DioExceptionType.connectionError) rethrow` 보유 | **닫힘** |
| 프론트 7 | 학부모 앱 `§2.1~2.4` 실서버 시험 부재 | `real_backend_signup_test.dart` 실재 · `§2.1~§2.4` 4건 전부 서술 | **닫힘** |

⚠ **`FE-R3` 가 닫았는데 이월 표가 안 따라온 것** — `phase-goal-loop §6.1` 이 말하는
**"해소된 것이 미해소로 남아 있는 쪽이 더 위험"** 의 실례. 닫은 쪽이 파생본을 미갱신.

### 갈래 3개 — 파일 무중복 · 병렬

| 갈래 | 대상 파일 | 전용 DB · 포트 | 모델 |
|---|---|---|:-:|
| **`r5-route`** | `backend/.../student/query/StudentRouteQueryService.java` + 그 시험 · `frontend/apps/parent-app/lib/features/route/**` + 그 시험 | `schoolbus_r5_route` · 서버 `:8181`(DB `schoolbus_r5run`) | sonnet |
| **`r5-bedoc`** | `docs/API_SPEC.md` · `backend/.../request/query/ApprovalQueryService.java` | `schoolbus_r5_bedoc` | sonnet |
| **`r5-fedoc`** | `docs/USER_FLOWS.md` · `docs/FEATURE_SPEC.md` | 부재 (문서 전용) | sonnet |

⚠ **`docs/API_SPEC.md` 는 `r5-bedoc` 단독 소유.** `r5-route` 가 사양 개정이 필요하다고 판단하면
고치지 말고 `BLOCKED` 로 보고.

### ⚖ Ruling 288 — **`§3.10` 표시 범위의 "하차지" 는 승차지와 별개 항목** (착수 전 조율자 판정)

`R4` 가 미판정으로 남긴 것. ⓐ서버 결손 · ⓑ`승하차지` 가 한 단어 두 갈래 중 **ⓐ 채택.**

**근거 — 정본 두 곳이 같은 문면이고 둘 다 세 항목을 나열.**
- `API_SPEC:720` *"표시 범위 — 승차지 이전 2개 · 승차지 · 하차지만 (P-08)"*
- `FEATURE_SPEC:658` *"승하차지 요약 — **승차지 이전 2개 · 승차지 · 하차지만** 표시"*

⚠ **`FEATURE_SPEC:658` 이 결정적** — 같은 문장이 `승하차지` 를 **묶음 명칭**으로 먼저 쓰고,
그 내역을 `승차지`·`하차지` 로 **갈라서** 나열. 한 단어라는 해석과 양립 불가.

**⇒ 서버(`StudentRouteQueryService.window()`)가 `myIndex + 1` 까지만 잘라 하차지를 미반환하는 것이
결손.** 정본을 안 고치고 서버를 고침 — `R4` 의 `§3.12`·`§4.3` 판정과 같은 방향.

⚠ **방향별 해석은 갈래가 판정** — 등원은 본인 승차지가 `my_stop_id` 이고 하차지가 학원,
하원은 그 반대. `run` 의 방향을 읽어 양쪽을 다뤄야 하며 **양쪽 다 시험 대상**.

### 목표 표 — 무엇이 통과하면 끝인가

| # | 갈래 | 완료 조건 | 검사 수단 |
|:-:|---|---|---|
| 1 | `r5-route` | `GET /students/{id}/route` 응답 `stops[]` 에 **하차지 포함** — 등원·하원 양방향 | 방향별 시험 각 1건 이상. 서버의 하차지 추가 로직을 지우면 **그 시험만** 실패 |
| 2 | `r5-route` | 앱 `RouteDetail.visibleStops` 가 **서버 응답을 다시 자르지 않음** | 서버가 4개를 주면 4개를 전부 그리는 시험. `route_detail.dart` 의 창 좁히기 제거 |
| 3 | `r5-route` | 시각 비교 **1초 허용 오차** 판정 — 정확 비교 전환 또는 오차 유지 근거 | 판정문에 근거 기재. 코드를 바꿨으면 그 시험 통과 |
| 4 | `r5-route` | `stops[]` 좌표 필터 미적용이 `§1.13` 과 정합인지 판정 | 판정문에 정본 인용 + 조치 |
| 5 | `r5-route` | 백엔드 전체 실행 **실패 0 · 건너뜀 0** | `./gradlew test -PtestDbUrl=...schoolbus_r5_route --rerun` · 결과 XML `mtime` 병기 |
| 6 | `r5-route` | 학부모 앱 전체 실행 **실패 0 · 건너뜀 0** | 자기 워크트리 서버 `:8181` 기동 후 `--dart-define=API_BASE_URL=http://localhost:8181/api/v1` |
| 7 | `r5-bedoc` | `docs/API_SPEC.md` 의 *"고유 에러 부재"* **16곳 전수**를 코드와 대조해 거짓인 곳 정정 | 절 번호별 판정을 판정문에 표로. `grep -c '고유 에러 부재' docs/API_SPEC.md` 값 전후 병기 |
| 8 | `r5-bedoc` | `ApprovalQueryService.java` 의 *"정본 공백"* 주석 2건 소멸 | `grep -c '정본 공백' backend/src/main/java/src/backend/request/query/ApprovalQueryService.java` → **0** |
| 9 | `r5-bedoc` | 백엔드 전체 실행 **실패 0 · 건너뜀 0** | `./gradlew test -PtestDbUrl=...schoolbus_r5_bedoc --rerun` |
| 10 | `r5-fedoc` | `USER_FLOWS` 결손 2건 해소 — `§6.14` force-confirm · `§6.6` member-accounts | `grep -c 'force-confirm' docs/USER_FLOWS.md` → **0 아님** · `grep -c 'member-accounts' docs/USER_FLOWS.md` → **0 아님** |
| 11 | `r5-fedoc` | `§6.14` force-confirm **기능 ID 판정** — 신규 ID 등재 또는 "ID 부재로 정의" 명문화 | `FEATURE_SPEC` 개정 + 판정 근거. ⚠ 계획서의 `O-06` 은 관제에 이미 쓴 코드라 **재사용 금지** |
| 12 | 조율자 | 병합 후 **단독 전체 실행** 6종 실패 0 · 건너뜀 0 | `R4` 와 같은 절차(`.claude/r4/RESUME.md §1`) · 결과 XML `mtime` 범위 병기 |

### 이 라운드 범위 밖 — 조율자 판정으로 닫음

| 항목 | 판정 |
|---|---|
| `max_connections=100` 대 동시 작업 수 | **이월 유지** — 공유 설정 변경이라 갈래 작업으로 분리 불가. 갈래 수를 3으로 묶어 이번 라운드는 미도달 |
| `deploy-backend.yml` CI 미검증 | **범위 밖 유지** — `Ruling 280`(배포 제외)이 여전히 유효 |
| `CAPACITY_EXCEEDED`·`PREVIEW_STALE` 재현 불가 | **이월 유지** — 외부 주소 검증 API 도달 불가 · 시드에 대기 건 부재. 환경 사유 |

### ✅ `r5-fedoc` 회수 (2026-09-17) — 목표 10·11 완료

보고서 `/Users/mskim/Desktop/PJ/School-Bus/.claude/r5/report-r5-fedoc.md`
(⚠ 갈래의 파일 쓰기가 harness 에 거부돼 **조율자가 받아 적음** — 작성 주체를 파일 머리에 명시).
**git 커밋 부재** — `docs/` 가 추적 밖이라 회수는 파일 내용으로 확인.

| 목표 | 조치 | 조율자 실측 |
|:-:|---|---|
| 10 | `USER_FLOWS` 에 **`UF-O-07`(회차 강제 확정) 신설** | `grep -c 'force-confirm' docs/USER_FLOWS.md` **0 → 1** |
| 10 | `member-accounts` — **신설하지 않음**(갈래의 의도적 편차) | ⚖ Ruling 289 로 승인 · 아래 |
| 11 | `FEATURE_SPEC` 에 **ID 부재를 명문화**(ⓑ 채택) | `grep -c 'O-06' docs/FEATURE_SPEC.md` **6 → 6**(새 `O-06` 참조 미생성) |

#### ⚖ Ruling 289 — **갈래의 편차가 옳고 조율자 발주문이 틀렸다**

발주문 완료 조건 `grep -c 'member-accounts' docs/USER_FLOWS.md` → `0` 이 아닐 것 은 **성립 불가**.

```bash
grep -c 'member-accounts' docs/API_SPEC.md   # → 0
```

⚠ **`member-accounts` 는 정본 어디에도 실재한 적이 없는 이름.** 조율자가 `F5` 이월 표 문면
(`docs/frontend/IMPLEMENTATION_PLAN.md §5.5` 4번)을 확인 없이 옮겨 적음. 실제 경로는
`/admin/staff-accounts`(`API_SPEC:2014`·`:2022`)이고 그 흐름은 `UF-O-06`(`USER_FLOWS:652`)이
2026-09-14 에 이미 메움.

⇒ **"미완" 이 아니라 "착수 전 이미 닫혀 있던 것".** 갈래가 문자열을 억지로 심지 않고
**기능 기준으로 판정해 신고한 것이 옳은 처신**이며, 그 자진 신고가 **유일한 탐지 수단**.
전역 규칙 등재 — `~/.claude/rules/phase-goal-loop.md §6.5`.

⇒ **`§8.5` 착수 전 재계수 표 정정** — "이월 13건 중 **4건** 닫힘" → **5건 닫힘**.

#### ⚖ Ruling 290 — **`§6.14` force-confirm 은 기능 ID 를 갖지 않는다** (ⓑ 채택)

**근거(갈래 계수)** — `FEATURE_SPEC §4.10` RUN 도메인 `RUN-01~08` 전부 배정 ·
`§6.4` 매핑 표 `O-01~O-07` 전부 다른 도메인에 배정. **빈 자리 부재.**
이 동작은 권한 `RUN_FORCE_CONFIRM` + API `§6.14` 만으로 이미 완전히 정의.

**ⓐ(신규 ID 등재)를 버린 근거** — `CLAUDE.md` 의 *"새 기능 ID·상태값을 만들지 않는다"* 는
**사양에 없는 개념을 지어내지 말라**는 뜻이지 이미 정의된 동작을 ID 로 다시 쪼개라는 뜻이 아님.

⚠ **이 저장소의 첫 사례** — 갈래가 초안에서 `EXCEPTION_REPORT_READ` 를 선례로 들었다가
**`A-18` 을 실제로 보유함을 재확인해 스스로 정정.** 선례 부재를 명시.

#### 📌 `r5-fedoc` 이 남긴 정본 공백 1건

`force-confirm` 흐름 중 **"어느 화면·신호가 그 회차를 강제 확정 대상으로 표시하는가"** 가 미정의.
`TECH_DECISIONS §14.2` 는 *"연속 실패 시 관계자에 경보"* 까지만 적고 **경보 채널·화면이 부재**.
`UF-O-07` 안에 **"(정본 공백)"** 으로 표시만 하고 미작성 — 지어내지 않은 것이 옳은 처신.

### ✅ `R5` 병합 + 단독 전체 실행 — 6종 전부 실패 0 · 건너뜀 0 (2026-09-17)

`r5-bedoc`·`r5-route` 두 갈래를 `--no-ff` 로 병합. **파일 무중복이라 충돌 0건.**
병합 커밋 `d8941ee7`(bedoc) · `4c16a876`(route). `r5-fedoc` 은 `docs/` 전용이라 커밋 부재.

**병합 직후 `compileJava compileTestJava` 통과 확인** — 충돌이 없어도 돌린다
(`parallel-agents-git.md §10.5` — 병합이 성공해도 코드가 성립한다는 뜻은 아니다).
Dart 생성 코드 재생성은 불요(`route_detail.dart` 는 수동 파싱 · 공유 패키지 미변경).

| 대상 | 검사 | 착수 전 | 대사 |
|---|:-:|:-:|---|
| 백엔드 | **1,306**(222클래스) | 1,305 | `r5-route` 신규 1(하원 방향) · **연속 4회** |
| 관계자 웹 | 248(69파일) | 248 | 변동 부재 |
| 학부모 앱 | **117** | ~~116~~ 117 | ⚠ `Ruling 293` · **연속 4회** |
| 매니저 앱 | 118 | 118 | 변동 부재 |
| `baraeda_core` | 48 | 48 | ⚠ 아래 |
| `baraeda_ui` | 77 | 77 | 변동 부재 |

프론트 5종은 전용 서버 `:8181`(DB `schoolbus_r5run`)에 붙여 측정. 백엔드는 전용 DB `schoolbus_r5verify`.

**백엔드 연속 4회 — 회차마다 결과 파일 기록 시각이 달라 4회 실행을 확인**
(`13:55:18` · `13:58:36` · `14:02:16` · `14:05:36`, 전 회차 `222클래스 1306 · 0 · 0 · 0`).
⚠ **`R4` 가 밟은 "이전 세션 잔여 XML 을 이번 결과로 오독" 함정을 `mtime` 병기로 회피.**

**학부모 앱 연속 4회** — 4회 전부 `visible 117 · 실패 0 · 건너뜀 0`(hidden 49).

### ⚖ Ruling 293 — 학부모 앱 기준값 `116` → **`117`**

`--dart-define=FIXTURE_DB` 없이 잰 값이 `116` 이었다. 그 인자를 채우면
`real_backend_p5_test.dart` 의 검사 1건이 **건너뜀에서 실행으로** 바뀌어 `117` 이다.
`r5-route` 가 *"건너뜀 1건을 환경 문제로 분류하지 않고 그 파일의 스킵 사유를 직접 읽어"* 찾아냈다.

⚠ **`.claude/r4/RESUME.md §1` 의 `116` 도 같은 이유로 낡았다** — 그 회차는 건너뜀 수를
함께 적지 않아 갈리지 않았다. `§5.2 4.1`("건너뜀도 통과가 아니다")을 적용했으면 그때 드러났다.

### ⚠ 조율자가 이 회차에 밟은 함정 2가지

**① 합성 항목을 검사로 함께 세어 `166` 으로 읽었다.** Dart 리포터의 `hidden` 은 `testStart`
메타데이터가 아니라 **`testDone` 이벤트의 속성**이다. 잘못된 경로로 판정해 전부 visible 로 셌다.
실제는 **`117 visible + 49 hidden`**. ⇒ **`§5.2 4.2` 가 경고한 바로 그 형태**이며,
갈래 보고값(`117 + 49`)과 어긋나는 것을 보고 되잡았다.

**② `.env` 를 워크트리에 안 넣고 발주했다** — 아래 별도 항목.

### 📌 `R5` 가 남긴 이월 3건

| # | 것 | 소유 |
|:-:|---|---|
| 1 | ⚠ **`baraeda_core` 의 차단 계정 검사가 마르는 자원에 기댄다** — *"blocked 계정은 로그인 자체가 실패한다"*(`real_backend_auth_test.dart`)가 **앞서 돈 검사가 차단을 해제하면 실패**한다. 재차단 수단이 부재. **학부모 앱은 `ensureParentSeedIsSafeForTiming()` 으로 조건부 `POST /dev/reset` 을 넣어 뒀는데 `baraeda_core` 에는 그 장치가 없다** — `parallel-agents-git.md §4.2.2` 형태(해법이 한쪽에만 적용) | 프론트 |
| 2 | `API_SPEC §6.11`(`GET /admin/emergencies`)의 쿼리 파라미터 `status`·`academy_id` 를 **컨트롤러가 하나도 받지 않는다** — `Ruling 292` | 백엔드·웹 합동 |
| 3 | `max_connections=100` 대 동시 작업 수 · `deploy-backend.yml` CI 미검증 · `CAPACITY_EXCEEDED`·`PREVIEW_STALE` 재현 불가 | 기존 이월 유지 |

### ⚠ 조율자 절차 위반 2건 — 원인은 하나 (`.claude/PROJECT_NOTES.md` 미독)

| 위반 | 근거 | 결과 |
|---|---|---|
| 에이전트 `name` 미기입 | `PROJECT_NOTES.md:187` 형식 `p{Phase}-t{Task}-{역할}-{에이전트}-{모델}` | **복구 불가** — 실행 중 개명 불가 |
| 병렬 발주 전 절전 억제 미실시 | `:410` — 이 머신은 유휴 **1분**에 잠들고 그때 갈래가 죽는다(전례 4회) | 라운드 중간에 `caffeinate -i -m -s` 로 복구 |

⚠ **`.env` 미투입도 같은 뿌리다**(`parallel-agents §12` — 워크트리에는 git 이 무시하는 파일이 안 따라온다).
자격증명 부재로 실 API 검사 3건이 **건너뜀**이 되어 완료 조건 "건너뜀 0" 이 **달성 불가**였다.
사용자 지적으로 드러났고, **기억에 "에이전트 띄울 세션은 `PROJECT_NOTES.md` 부터" 가 이미 있었는데
두 세션 연속 같은 이유로 어겼다.**

---

## 8.6 ⚖ `R6` 목표 표 — 잔여 이월 정리 (2026-09-17 계획 · **착수 전** · 메인 `e575ed38`)

### 착수 전 재계수 — **3건 열림 · 2건은 열 수 없음**

| 이월 | 실측 | 판정 |
|---|---|:-:|
| `baraeda_core` 마르는 자원 | `grep -rc 'dev/reset' frontend/packages/baraeda_core/test/` → **0건**. 학부모 앱은 `test/support/real_backend_target.dart` 에 보유 | **열림** |
| `§6.11` 쿼리 파라미터 | `AdminEmergencyController:35` `public ... list()` — **인자 0개**. 정본 `API_SPEC:2129` 는 `status`·`academy_id` 명시 | **열림** |
| `max_connections=100` | `show max_connections` → **100**. `docker-compose.yml` 에 `command:` 재정의 부재 | **열림 · 조율자 소유** |
| `deploy-backend.yml` CI | `Ruling 280`(배포 제외)이 유효 | 범위 밖 |
| `CAPACITY_EXCEEDED`·`PREVIEW_STALE` | 외부 주소 검증 API 도달 불가 · 시드에 대기 건 부재 | 환경 |

### ⚖ Ruling 294 — **`§6.11` 필터는 서버 결손이다. 정본을 안 고치고 서버를 고친다**

`Ruling 292`(별개 단위로 이월)를 여기서 닫는다. **세 근거가 전부 같은 쪽을 가리킨다.**

| 근거 | 내용 |
|---|---|
| 정본 | `API_SPEC:2129` — *"요청 (쿼리) `status` · `academy_id`(선택)"* |
| ⚠ **호출자** | `frontend/apps/academy-web/src/features/admin/api/emergencies.ts:75` 가 **실제로 그 두 값을 실어 보낸다**(`query: { status, academy_id: academyId }`) |
| 선례 | `§5.16`(`GET /staff/emergencies`)은 같은 계열인데 **`status`·`date` 를 받는다** |

⚠ **이것은 문서 불일치가 아니라 동작하지 않는 기능이다** — 관리자가 목록 화면에서 필터를 고르면
값이 서버까지 가는데 **서버가 통째로 무시해 전체 목록이 그대로 나온다.** 화면은 정상으로 보인다.

### 갈래 2개 — 파일 무중복 · 병렬

| 갈래 | 대상 | 전용 자원 | 모델 |
|---|---|---|:-:|
| **`r6-t1-impl-gp-sonnet`** | `frontend/packages/baraeda_core/test/**` | 서버 `:8182`(DB `schoolbus_r6run`) | sonnet |
| **`r6-t2-impl-gp-sonnet`** | `backend/.../admin/**`(비상 조회) + 그 시험 | DB `schoolbus_r6_t2` | sonnet |

### 목표 표

| # | 갈래 | 완료 조건 | 검사 수단 |
|:-:|---|---|---|
| 1 | `t1` | `baraeda_core` 실서버 검사가 **소모한 상태를 스스로 되돌린다** | 같은 명령을 **연달아 4회** 돌려 4회 다 `48 · 실패 0 · 건너뜀 0`. **사이에 어떤 손질도 부재** |
| 2 | `t1` | 되돌리는 수단은 **새로 만들지 말고 학부모 앱 것을 가져다 쓴다** | `parent-app/test/support/real_backend_target.dart` 의 조건부 `POST /dev/reset` 이 본보기. **왜 그 형태인지 판정문에** |
| 3 | `t2` | `GET /admin/emergencies` 가 `status`·`academy_id` 를 **실제로 거른다** | 필터별 검사. **거르는 코드를 지우면 그 검사만 실패**(심어서 확인) |
| 4 | `t2` | `status` 허용값이 `§5.16` 과 어긋나지 않는다 | `§5.16` 은 `open`·`acked`·`canceled`(기본 `open`). **`§6.11` 에 기본값 서술이 부재** — 판정하고 근거를 적는다 |
| 5 | `t2` | 백엔드 전체 **연속 4회** 실패 0 · 건너뜀 0 | `./gradlew test -PtestDbUrl=...schoolbus_r6_t2 --rerun` · 결과 XML `mtime` 병기 |
| 6 | 조율자 | `max_connections` 상향 | 갈래가 **전부 멈춘 뒤** 처리 — postgres 재기동이 필요해 동시 진행 불가 |
| 7 | 조율자 | 병합 후 단독 전체 실행 6종 실패 0 · 건너뜀 0 | 백엔드·학부모 앱은 **연속 4회** |

⚠ **목표 6 을 갈래에 주지 않는 이유** — `docker-compose.yml` 을 고치면 **postgres 컨테이너를
재기동**해야 하고, 그러면 **도는 갈래의 검사가 전부 죽는다**(`parallel-agents-git.md §0` 공유 자원).

### 📌 `R6` 후속으로 예약 — `R7` 후보 (2026-09-17 사용자 지시 "끝나면 해")

`R6` 갈래 2개가 멈춘 뒤 착수. **동시에 하지 않는 이유는 `§8.6` 목표 6 과 같다** — 아래 ①이
`bootRun` 서버와 시드 상태를 쓰므로 도는 갈래와 자원이 겹친다.

| # | 것 | 근거 |
|:-:|---|---|
| 1 | **`CAPACITY_EXCEEDED` 재현 검사** — `§5.7` 강제 추가로 정원 초과 | 차단 사유였던 *"주소 검증 API 도달 불가"* 가 **무효**(`R5` 에서 네이버 지오코딩 실 API 검사 2건 통과). `bootRun` 은 `.env` 를 읽어 실 지오코딩이 동작 |
| 2 | **`PREVIEW_STALE` 재현 검사** — `§3.8` 로 대기 건 생성 → 미리보기 토큰 → 입력 변경 → `§5.6` 승인 | 차단 사유였던 *"시드에 대기 건 부재"* 가 **무효**(`V2__seed_data.sql:374` 에 `pending` 1건 실재) |
| 3 | `max_connections` 상향 (`§8.6` 목표 6) | postgres 재기동 필요 |

⚠ **①②는 "환경 차단" 으로 세 세션 이월됐으나 근거가 낡아 있었다.** 전말은
`docs/frontend/IMPLEMENTATION_PLAN.md §5.5` 이월 8번 아래. **차단 사유도 파생본이라 낡는다** —
`phase-goal-loop §3`(근거 없는 환경 분류가 코드 결함을 덮는다)의 실례.

### ✅ `R6` 병합 + 단독 전체 실행 — 6종 전부 실패 0 · 건너뜀 0 (2026-09-17)

병합 커밋 `2ced97c8`(t2) · `121acdd4`(t1). **파일 무중복이라 충돌 0건.** 컴파일 통과 확인.

| 대상 | 검사 | 착수 전 | 대사 |
|---|:-:|:-:|---|
| 백엔드 | **1,310**(222클래스) | 1,306 | `t2` 신규 4 · **연속 4회**(`15:25` · `15:28` · `15:32` · `15:35`) |
| 관계자 웹 | 248(69파일) | 248 | 변동 부재 |
| 학부모 앱 | 117 | 117 | **연속 4회** |
| 매니저 앱 | 118 | 118 | 변동 부재 |
| `baraeda_core` | 48 | 48 | **연속 4회 + 아래 ⭐** |
| `baraeda_ui` | 77 | 77 | 변동 부재 |

#### ⭐ `t1` 수정의 결정적 검증 — **실제 오염원 바로 뒤에서 작동**

연속 4회보다 강한 증거를 얻었다. **매니저 앱 전체 실행 직후** 조율자가 차단 계정 상태를 직접 쟀다.

```
POST /auth/login {driverBlocked}  →  성공 (차단이 실제로 풀려 있음)
baraeda_core 실행              →  visible=48 failed=0 skipped=0
```

⇒ **오염이 이론이 아니라 실제로 일어나고**(매니저 앱이 풀었다), **자가 복구가 그것을 감지해
되돌렸다.** `Ruling 295` 의 진단(크로스 패키지 상태 오염)이 실측으로 확인된 것이다.

### ⚖⚖ Ruling 297 재확인 — **`§5.16` 에 같은 결함. `R7` 최우선**

`t2` 가 `§6.11` 을 고치다 드러낸 것. 조율자 실측 —
`StaffEmergencyController:44` 가 `list(@AuthenticationPrincipal AuthUser requester)` 뿐이고,
`academy-web/.../emergency/api/index.ts:74` 는 `query: { status, date }` 를 **실제로 보낸다.**

⚠ **관리자용(`§6.11`)보다 위험하다** — `USER_FLOWS:444` 가 이 경로를
*"접속이 끊겼다 돌아온 관계자가 **밀린 알림을 따라잡는 경로**"* 로 규정한다.
**비상 알림은 응답 지연이 곧 안전 문제다.**

### ⚠ 조율자 발주문 오류 2건 — 둘 다 갈래가 반박해 바로잡혔다

| 오류 | 실제 | 등재 |
|---|---|---|
| *"앞서 돈 검사가 차단을 푼다"* | **매니저 앱**(다른 제품)이 푼다 — 패키지 안에서 순서를 고쳐도 못 막는다 | `Ruling 295` |
| *"`§5.16` 은 이미 필터를 받는다(선례 실재)"* | **코드에 부재.** 정본 문면만 읽고 코드를 안 봤다 | `Ruling 296` |

⚠ **두 번째는 `phase-goal-loop §6.3` 4번이 경고한 형태** — *"사양에 있다 ≠ 코드에 있다.
'재사용하라' 를 적는 순간 그 대상을 `grep`·`ls` 로 세어 **파일 경로를 함께 적어라**."*
경로를 적었으면 그 자리에서 드러났다.

**두 갈래 다 지시를 맹종하지 않고 신고한 뒤 옳게 처리했다** — 발주문의
*"확신 없는 지점을 적어라"* 가 이 라운드에서도 유일한 탐지 수단이었다.

---

## 8.7 ⚖ `R7` 목표 표 — `§5.16` 필터 + 미재현 오류 2종 (2026-09-17 계획 · **착수 전** · 메인 `d0fbe4c9`)

### ✅ 목표 6(`max_connections`)은 착수 전에 조율자가 닫았다

`docker-compose.yml` 에 `command: ["postgres","-c","max_connections=300"]` 추가 → 커밋 `d0fbe4c9`.
**갈래를 띄우기 전에 한 이유** — postgres 재기동이 필요해 도는 갈래가 있으면 전부 죽는다.

⚠ **영속 볼륨이 부재한 컨테이너라 재생성 전에 `pg_dump` 로 백업했다**(`schoolbus` 171KB ·
`schoolbus_load` 3.4MB → `/tmp/r7-dbbackup/`). 재생성 후 실측 — 두 DB 생존 ·
`academy` 3행 · `schoolbus_load` 44테이블 · `show max_connections` = **300**.

### 갈래 2개 — 파일 무중복 · 병렬

| 갈래 | 대상 | 전용 자원 | 모델 |
|---|---|---|:-:|
| **`r7-t1-impl-gp-sonnet`** | `backend/.../exception/**`(관계자 비상 조회) + 그 시험 | DB `schoolbus_r7_t1` | sonnet |
| **`r7-t2-impl-gp-sonnet`** | `frontend/apps/academy-web/src/features/**/realBackend.test.ts` | 서버 `:8183`(DB `schoolbus_r7run`) | sonnet |

### 목표 표

| # | 갈래 | 완료 조건 | 검사 수단 |
|:-:|---|---|---|
| 1 | `t1` | `GET /staff/emergencies` 가 `status`·`date` 를 **실제로 거른다**(`Ruling 297`) | 필터별 검사. **거르는 조건을 한 칸 넓히면 그 검사만 실패**(심어서 확인) |
| 2 | `t1` | ⚠ **`R6` 가 방금 만든 같은 로직을 두 벌로 만들지 않는다** | `AdminEmergencyQueryService`(커밋 `dd29f050`)의 상태 파생·우선순위·`422` 검증을 **뽑아 공유**하거나, 못 하면 **왜 못 하는지 판정문에** |
| 3 | `t1` | 백엔드 전체 **연속 4회** 실패 0 · 건너뜀 0 | `-PtestDbUrl=...schoolbus_r7_t1 --rerun` · 결과 XML `mtime` 병기 |
| 4 | `t2` | **`CAPACITY_EXCEEDED` 를 실제 응답으로 재현** | `§5.7` 강제 추가로 정원 초과 → `409`. ⚠ 차단 사유였던 *"주소 검증 API 도달 불가"* 는 **무효**(아래) |
| 5 | `t2` | **`PREVIEW_STALE` 를 실제 응답으로 재현** | `§3.8` 로 대기 건 생성 → 미리보기 토큰 → 입력 변경 → `§5.6` 승인 → `409` |
| 6 | `t2` | 관계자 웹 전체 **연속 4회** 실패 0 · 건너뜀 0 | 새로 만든 상태를 **스스로 되돌린다**(`R6 t1` 형태) |
| 7 | 조율자 | 병합 후 단독 전체 실행 6종 실패 0 · 건너뜀 0 | 백엔드·학부모 앱·`baraeda_core` 는 **연속 4회** |

### ⚠ 목표 4·5 의 차단 사유가 무효인 근거 (2026-09-17 재계수)

세 세션 동안 *"환경 차단"* 으로 이월됐으나 **두 근거 다 반증됐다.**

| 원 근거 | 실측 |
|---|---|
| *"강제 추가에 주소 검증(네이버 지오코딩) 통과가 필요한데 도달 불가"* | **`R5` 회차에서 네이버 지오코딩 실 API 검사 2건이 통과.** 도달 불가가 아니라 `backend/.env` 가 시험 JVM 에 미전달돼 꺼져 있던 것. ⚠ **`bootRun` 은 `.env` 를 읽으므로**(`build.gradle:316`) 실서버는 실 지오코딩으로 돈다 |
| *"시드에 `pending` 승인 건 부재(pending 0 · approved 1)"* | **`V2__seed_data.sql:374` 에 `pending` 1건 실재.** 당시 값은 그 갈래 DB 의 **소모된 상태**였다. 게다가 `POST /students/{id}/change-requests`(`§3.8`)로 **정상 API 흐름으로 만들 수 있다** |

⚠ **차단 사유도 파생본이라 낡는다** — `phase-goal-loop §3`(근거 없는 환경 분류가 코드 결함을 덮는다).
사용자가 *"외부 주소 검증 API에 도달이 안 돼 이게 뭐야?"* 로 물어서 드러났다.

### ✅ `R7` 병합 + 단독 전체 실행 — 6종 전부 실패 0 · 건너뜀 0 (2026-09-17)

병합 커밋 `ce1115be`(t1) · `e54a9ec0`(t2). **파일 무중복이라 충돌 0건.** 컴파일 통과 확인.

| 대상 | 검사 | 착수 전 | 대사 |
|---|:-:|:-:|---|
| 백엔드 | **1,315**(222클래스) | 1,310 | `t1` 신규 5 · **연속 4회**(`16:50`·`16:53`·`16:57`·`17:00`) |
| 관계자 웹 | **249**(69파일) | 248 | `t2` 신규 2 · 삭제 1 · **연속 4회** |
| 학부모 앱 | 117 | 117 | **연속 4회** |
| 매니저 앱 | 118 | 118 | 변동 부재 |
| `baraeda_core` | 48 | 48 | **연속 4회** |
| `baraeda_ui` | 77 | 77 | 변동 부재 |

### ⭐ `t1` 목표 2 가 **실증으로** 달성됐다 (`Ruling 298`)

*"`R6` 가 만든 로직을 두 벌로 만들지 마라"* 를 갈래가 **음성 대조로 증명**했다.

| 변형 위치 | 실패한 시험 클래스 |
|---|---|
| **공유 파일**(`EmergencyStatusFilter`) 2회 | `StaffEmergencyControllerTest` + `AdminEmergencyControllerTest` **양쪽** |
| staff 전용 파일 1회 | staff 3건만 — **admin 무영향** |

⚠ **`import` 가 있다는 정적 대조로는 같은 말을 할 수 없다.** 공유 파일 한 곳을 깨뜨렸을 때
두 클래스가 함께 실패하는 것이 *"한 벌"* 의 유일한 반박 불가 증거다.
`AdminEmergencyQueryService` 가 **47줄 줄어든 것**이 같은 사실의 다른 표현이다.

### ⚖⚖ Ruling 299 — `PREVIEW_STALE` 은 **시드 구조상 재현 불가**. 조율자 분석도 틀렸다

세 세션이 각각 다른 차단 사유를 적었고 **세 번째에야 진짜 벽이 특정**됐다.

| 세션 | 주장 | 실제 |
|---|---|---|
| `F5` | *"시드에 `pending` 건이 부재"* | **틀림** — `V2__seed_data.sql:374` 에 실재 |
| **`R7` 조율자 발주문** | *"`pending` 이 실재하니 재현 가능"* | **틀림** — 그 건이 다음 관문에서 막힌다 |
| `r7-t2` 갈래 | *"`route` 1행이 방향까지 갈라 4중 일치가 0건"* | **맞음**(조율자 재확인) |

**실측** — `route` 는 `(academy 1, bus 1, 오늘 요일, **`to_academy`**)` **1행뿐**이고,
`pending` CR#1 이 붙은 `run 2` 는 **`from_academy`** 다. `ApprovalQueryService.detail()` 이
`(academy, bus, weekday, direction)` 4중 일치 `route` 를 요구하므로 **`preview_token` 단계에
도달하기 전에 `422 ROUTE_NOT_CONFIGURED_FOR_RUN` 으로 끝난다.**

⚠ **조율자 오류의 형태 — 존재를 확인하고 도달 가능을 확인하지 않았다.** 대기 건이 **있는지**만
세고, 그 건이 **미리보기까지 갈 수 있는지**는 세지 않았다. 오늘 반복한 것과 같은 계열이다
(`phase-goal-loop §6` — 한 층을 세고 다음 층을 안 센다).

**⇒ 이월** — 해법은 `V2__seed_data.sql` 에 `from_academy` route 행 추가이고,
**갈래 소유 범위 밖이라 손대지 않은 것이 옳다.**

### ⚖ Ruling 300 — **실패하는 검사는 `skip` 이 아니라 삭제한다**

`COMMON §6` 의 완료 조건이 **"건너뜀 0"** 이라 `skip` 으로 남기면 그 조건을 영구히 만족할 수 없다.
더 중요한 것 — 이 저장소에서 *"건너뛴 검사가 몇 주간 한 번도 안 돌았는데 종료 코드가 `0` 이라
아무도 몰랐던"* 사고가 실재한다(`phase-goal-loop §5.2 4.1`).
**근거를 코드 주석으로 남기고 지우는 편이 낫다.**

### 📌 `R7` 이 남긴 이월 3건

| # | 것 | 소유 |
|:-:|---|---|
| 1 | `PREVIEW_STALE` 재현 — `V2__seed_data.sql` 에 `from_academy` route 행 추가 필요 | 백엔드 시드 |
| 2 | 관계자 웹 실서버 검사의 **직렬 실행 여부 미점검**(Vitest 기본은 병렬) — 상태를 남기는 파일이 1개뿐이라 위험은 낮다는 갈래 판단 | 프론트 |
| 3 | `deploy-backend.yml` CI 미검증 | 배포 시점(`Ruling 280`) |

---

## 8.8 ⚖ `R8` 목표 표 — 잔여 이월 2건 (2026-09-17 계획 · **착수 전** · 메인 `e54a9ec0`)

**사용자 지시(2026-09-17) — "배포 제외하고 진행".** `R7` 이월 3건 중 배포 항목을 빼고 둘을 닫는다.

### ⚠ 착수 전 조율자 판정 — **`V2__seed_data.sql` 을 고치지 않는다**

`Ruling 299` 의 해법이 *"시드에 `from_academy` route 행 추가"* 인데, **그 파일을 직접 고치면
보존 DB 2개가 기동 불가가 된다.**

| 실측 | 값 |
|---|---|
| `application-load.yml:17` | `locations: classpath:db/migration,classpath:db/migration-local` — **`load` 프로파일도 `migration-local` 을 읽는다** |
| `schoolbus` · `schoolbus_load` 의 `flyway_schema_history` | **둘 다 `V2 seed data` 적용 완료(`success=t`)** |

⇒ `V2` 를 고치면 체크섬이 바뀌어 **두 DB 다 `FlywayValidateException` 으로 기동 실패**한다.
`schoolbus_load` 는 부하 측정 자료라 재구성으로 되돌릴 수 없다.

**⇒ `migration-local/` 에 새 버전 파일을 쌓는다.** 공통 마이그레이션이 `V11` 까지 있으므로
**`V12` 이상**을 쓴다(갈래가 직접 세어 확인). `CLAUDE.md` 의 *"개발 단계에서는 새 버전으로 쌓지
않아도 된다"* 는 **허용이지 요구가 아니며**, 보존 대상이 실재하는 지금은 쌓는 쪽이 맞다.

### 갈래 1개 — 두 이월이 같은 앱을 건드려 나누지 않는다

| 갈래 | 대상 | 전용 자원 | 모델 |
|---|---|---|:-:|
| **`r8-t1-impl-gp-sonnet`** | `backend/src/main/resources/db/migration-local/**` · `frontend/apps/academy-web/**` | DB `schoolbus_r8_t1` · 서버 `:8184`(DB `schoolbus_r8run`) | sonnet |

### 목표 표

| # | 완료 조건 | 검사 수단 |
|:-:|---|---|
| 1 | **`PREVIEW_STALE` 를 실제 응답으로 재현**(`Ruling 299` 가 특정한 벽을 넘는다) | `§5.5` 상세에서 `preview_token` 을 받고 → 입력 변경 → `§5.6` 승인 → **`409`** |
| 2 | 시드 보강은 **새 버전 파일**로 한다. `V2__seed_data.sql` **무수정** | `git diff --name-only` 에 `V2__seed_data.sql` **부재** |
| 3 | ⚠ **보존 DB 2개가 여전히 기동한다** | `schoolbus`·`schoolbus_load` 에 그 마이그레이션이 적용된 뒤 `flyway_schema_history` 의 `success=t` · 앱 기동 성공 |
| 4 | 관계자 웹 검사의 **직렬 실행 여부를 계수로 확인**하고 필요하면 직렬화 | `vitest.config.*` 의 동시성 설정을 **읽어서** 판정. *"위험이 낮다"* 가 아니라 **설정이 무엇인지** 적는다 |
| 5 | 관계자 웹 **연속 4회** 실패 0 · 건너뜀 0 | 사이에 어떤 손질도 부재 |
| 6 | 백엔드 **연속 4회** 실패 0 · 건너뜀 0 | 시드가 바뀌므로 반드시 돌린다 |
| 7 | 조율자 — 병합 후 단독 전체 실행 6종 실패 0 · 건너뜀 0 | 백엔드·웹·학부모 앱·`baraeda_core` 는 연속 4회 |

⚠ **목표 3 이 이 라운드에서 가장 위험한 항목이다** — 되돌릴 수 없는 자원을 건드린다.

### ✅ `R8` 병합 + 단독 전체 실행 — 6종 전부 실패 0 · 건너뜀 0 (2026-09-17)

병합 커밋 `<merge>`. 갈래 1개(`r8-t1`)라 충돌 부재. 컴파일 통과.
**서버 기동 로그에서 `Successfully applied 12 migrations ... v12` 확인.**

| 대상 | 검사 | 착수 전 | 대사 |
|---|:-:|:-:|---|
| 백엔드 | **1,315**(222클래스) | 1,315 | 변동 부재 · **연속 4회**(`19:17`·`19:20`·`19:24`·`19:29`) |
| 관계자 웹 | **251**(69파일) | 249 | 신규 2 · **연속 4회** |
| 학부모 앱 | 117 | 117 | **연속 4회** |
| 매니저 앱 | 118 | 118 | 변동 부재 |
| `baraeda_core` | 48 | 48 | **연속 4회** |
| `baraeda_ui` | 77 | 77 | 변동 부재 |

### ⭐ 닫은 이월 2건

| 이월 | 결과 |
|---|---|
| `PREVIEW_STALE` 재현 | **세 세션 만에 닫힘.** 시드 공백을 `V12__add_from_academy_route_for_pending_preview.sql` 로 메우고 실제 `409` 를 받는 검사 신설 |
| 웹 검사 직렬 실행 | **이미 돼 있었다** — `vitest.config.ts:20` `fileParallelism: false`(커밋 `d76a697e`, 앞선 라운드) |

⚠ **두 번째가 교훈이다** — `r7-t2` 가 *"상태를 남기는 파일이 1개뿐이라 위험이 낮다"* 로 넘긴
항목인데, **설정 파일을 한 번 열었으면 그 자리에서 끝났을 일**이었다.
`R8` 발주문이 *"'위험이 낮다' 가 아니라 '설정이 무엇이다' 를 값으로 적어라"* 로 못 박아 드러났다.

### ⚖ Ruling 301 — `--rerun` 은 실재하는 Gradle 옵션이다 (갈래 신고 정정)

갈래가 *"존재하지 않는 플래그를 썼다가 무시됐다"* 고 적었으나 **조율자 실측으로 반증.**

```
./gradlew help --task test → "--rerun  Causes the task to be re-run even if up-to-date."
./gradlew test --bogusflag → "Unknown command-line option '--bogusflag'."
```

⚠ **Gradle 은 모르는 옵션을 조용히 무시하지 않는다.** 4회 전부 유효하다.
**이 정정이 중요한 이유** — 조율자 검증 스크립트가 `R5`~`R8` 내내 `--rerun` 을 썼다.
오기를 두면 다음 세션이 **오늘 판정 전체를 의심하거나 스크립트를 바꾼다.**

### ⚖ Ruling 302 — `schoolbus_load` 에 V12 적용은 안전하다 (조율자가 대신 확인)

갈래가 *"확신 없으면 미확인으로 남기라"* 는 지시대로 **건드리지 않은 것이 옳다.**
되돌릴 수 없는 자원이라 조율자가 **롤백되는 트랜잭션 안에서 실제 삽입**으로 확인했다.

⚠ **위험 요소** — `V1__init_schema.sql:337`
`uk_route_bus_weekday_direction UNIQUE (bus_id, weekday, direction)` 에 **`academy_id` 가 부재**해
다른 학원 행과도 부딪힐 수 있다. 실측 결과 `bus_id=1` 행이 `(wed, to_academy)` 하나뿐이라 무충돌.

### 📌 `R8` 이 남긴 이월 2건

| # | 것 | 소유 |
|:-:|---|---|
| 1 | ⚠ **`ApprovalPreviewResolver` 의 인메모리 `previewCache` 가 `POST /dev/reset` 에 안 지워진다** — `stale = cached.isPresent()` 라 **리셋 직후 첫 조회가 `stale=true` 로 잘못 분류**된다. **검사 환경만의 문제가 아니다** — 운영에서 DB 를 초기화하거나 외부에서 데이터를 바꾸면 *"낡지 않았는데 낡았다"* 가 나와 사용자에게 *"재조회 후 재시도"* 안내가 뜬다. 갈래가 범위 밖이라 신고만 했다 | 백엔드 |
| 2 | `deploy-backend.yml` CI 미검증 | 배포 시점(`Ruling 280` · 2026-09-17 사용자 지시로 이번 회차 제외) |

---

## 8.9 ⚖ `R9` 목표 표 — 미리보기 캐시가 초기화에 안 지워진다 (2026-09-17 계획 · **착수 전** · 메인 `9a4fc255`)

**사용자 지시 "진행"(2026-09-17).** `R8` 이 남긴 이월 1건을 닫는다(배포 건은 지시로 제외).

### ⚠ 착수 전 재계수 — **`r8-t1` 신고보다 범위가 좁다**

갈래가 *"실제 운영에서도 잘못 표시될 가능성"* 으로 보고했으나 조율자가 코드를 직접 읽어 좁혔다.

| 실측 | 값 |
|---|---|
| `DevResetService.java:32`·`:33` | **`@Profile("local")` + `@ConditionalOnProperty(app.dev-tools.reset.enabled)`** — **개발 프로파일 전용** |
| `ApprovalPreviewCache` 인터페이스 | `find` · `put` · `evict(approvalId)` — **`clear()` 가 부재** |
| `DevResetService.reset()` | Flyway 재적재 + Redis 위치 키 삭제 — **인메모리 캐시는 미포함** |
| `evict` 호출부 | 3곳 실재(`ChangeRequestDecisionService:201`·`:221` · `ChangeRequestAutoRejectionPersistence:68`) — **결정 시 지우는 배선은 정상** |

⇒ **운영 영향은 제한적이다**(그 경로가 `local` 에만 뜬다). **실제 피해는 개발·검사 환경의 비결정성**이고,
`r8-t1` 이 그 때문에 `previewStale` 값을 단언하지 못하고 우회했다.

⚠ **그래도 고칠 값이 있다** — 검사가 단언하지 못하는 값은 **아무것도 지키지 못한다.**
`stale` 은 *"낡은 미리보기로 승인하는 것"* 을 막는 신호이고, 그 신호를 검사가 고정하지 못하는 상태다.

### 갈래 1개

| 갈래 | 대상 | 전용 자원 | 모델 |
|---|---|---|:-:|
| **`r9-t1-impl-gp-sonnet`** | `backend/.../request/preview/**` · `backend/.../global/dev/**` + 그 시험 · `frontend/apps/academy-web/src/features/approval/**` | DB `schoolbus_r9_t1` · 서버 `:8185`(DB `schoolbus_r9run`) | sonnet |

### 목표 표

| # | 완료 조건 | 검사 수단 |
|:-:|---|---|
| 1 | `POST /dev/reset` 이 **미리보기 캐시도 지운다** | 리셋 직후 첫 조회가 `preview_stale=false`. **지우는 호출을 빼면 그 검사만 실패**(심어서 확인) |
| 2 | 캐시를 비우는 수단이 **인터페이스에 선언**된다 | `ApprovalPreviewCache` 에 `clear()` 추가 · 구현체 반영. ⚠ **`evict(Long)` 은 결정 시 지우는 용도라 그대로 둔다** |
| 3 | ⚠ **`@Profile("local")` 경계를 깨뜨리지 않는다** | `DevResetService` 의 조건 두 겹은 `DevResetController` 와 **같아야 한다**(주석이 그 이유를 적어 뒀다 — 안 맞추면 `load` 프로파일 기동이 막힌 전례). **`load` 프로파일 기동을 실제로 확인** |
| 4 | `r8-t1` 이 포기한 **`previewStale` 단언을 되살린다** | `frontend/.../approval/api/realBackend.test.ts` 가 리셋 후 첫 조회의 `previewStale` 을 **값으로 단언** |
| 5 | 백엔드 **연속 4회** 실패 0 · 건너뜀 0 | `-PtestDbUrl=...schoolbus_r9_t1 --rerun` · XML `mtime` 병기 |
| 6 | 관계자 웹 **연속 4회** 실패 0 · 건너뜀 0 | 서버 `:8185` |
| 7 | 조율자 — 병합 후 단독 전체 실행 6종 실패 0 · 건너뜀 0 | 백엔드·웹·학부모 앱·`baraeda_core` 는 연속 4회 |

⚠ **목표 3 이 이 라운드의 함정이다** — 이 저장소에서 **조건을 한쪽에만 달아 `load` 프로파일 기동이
막힌 사고**가 실재한다(2026-09-09, `DevResetService` 주석에 기록).

### ✅ `R9` 병합 + 단독 전체 실행 — 6종 전부 실패 0 · 건너뜀 0 (2026-09-17)

갈래 1개라 충돌 부재. 컴파일 통과.

| 대상 | 검사 | 착수 전 | 대사 |
|---|:-:|:-:|---|
| 백엔드 | **1,316**(223클래스) | 1,315(222) | 신규 `DevResetServiceTest` 1클래스·1시험 · **연속 4회**(`20:55`·`20:58`·`21:02`·`21:05`) |
| 관계자 웹 | 251(69파일) | 251 | **연속 4회** |
| 학부모 앱 | 117 | 117 | **연속 4회** |
| 매니저 앱 | 118 | 118 | 변동 부재 |
| `baraeda_core` | 48 | 48 | **연속 4회** |
| `baraeda_ui` | 77 | 77 | 변동 부재 |

### ⭐ Ruling 303 — **"심었는데 통과했다" 를 넘기지 않은 것이 이 라운드의 성과**

갈래가 결함을 심고 **재시작 직후 1회 실행**했더니 `6/6` 전부 통과했다. **거기서 멈추지 않았다.**

> 재시작하면 인메모리 캐시가 항상 비어 있어 `clear()` 유무와 무관하게 첫 조회는 캐시 미스로
> 시작한다 — **결함이 있어도 재시작 직후 1회 실행으로는 절대 안 걸리는 형태**다.

**재현 경로를 스스로 설계했다** — `run.depart_time` 이 `now()` 상대값이라 리셋마다 지문이 달라지는
것을 이용해 **같은 서버에 재시작 없이 두 번째 실행**으로 재현. 2회차에서 **그 단언 1건만** 실패.

⚠ **`phase-goal-loop §5` 가 요구하는 것이 정확히 이 행동이다.** 넘겼으면
**"이 검사는 그 결함을 못 잡는다" 는 사실을 모른 채 초록으로 끝났을 것이다.**

### ⚠ 보고 누락 1건 — 목표 3(`load` 프로파일 기동)

발주문이 완료 조건으로 박았는데 **보고서·유휴 요약 둘 다에 언급이 부재**했다. 조율자가 직접 쟀다.

```
./gradlew bootRun --args='--spring.profiles.active=load --server.port=8186 ...'
→ ✅ Started BackendApplication in 11.908 seconds
```

**통과다** — `DevResetService` 안에 캐시 비우기를 넣어 `@Profile("local")` 경계가 유지됐다.
공용 서비스로 뺐으면 **주입 대상 부재로 기동이 막혔을 것**이다(2026-09-09 실제 사고).

⇒ **결과는 통과이나 누락은 기록한다** — 완료 조건을 보고서에 적지 않으면
**조율자가 재지 않는 한 판정할 수단이 부재**하다.

## 8.10 📌 오늘(2026-09-17) 이월 정리 5라운드 총정리

`R5`→`R6`→`R7`→`R8`→`R9`. **다섯 라운드 다 충돌 0건 병합.**

### 고친 실제 결함 — 전부 "화면은 정상인데 동작 안 함" 형태

| # | 것 | 라운드 |
|:-:|---|:-:|
| 1 | **학부모 노선 화면에 하차지가 아예 안 나왔다**(서버가 자르고 앱이 또 잘랐다) | `R5` |
| 2 | **비상 알림 필터가 관리자·관계자 양쪽 다 죽어 있었다**(웹은 값을 보내는데 서버가 무시) | `R6`·`R7` |
| 3 | `§5.5` 가 "고유 에러 부재" 로 적었으나 코드가 4종을 던짐 | `R5` |
| 4 | `baraeda_core` 검사가 **첫 실행만 초록**(매니저 앱이 계정 차단을 푼다) | `R6` |
| 5 | `PREVIEW_STALE` 이 **시드 공백으로 세 세션간 재현 불가**였다 | `R8` |
| 6 | **미리보기 캐시가 초기화에 안 지워져** 검사가 값을 단언하지 못했다 | `R9` |

### ⚠ 조율자가 반복한 오류 — **한 층을 세고 다음 층을 안 센다**

**네 번** 났고 **네 번 다 갈래가 잡아냈다.** 전역 규칙 `phase-goal-loop.md §6.5` 신설.

| 센 것 | 안 센 것 |
|---|---|
| 이월의 절 번호 | **그 옆 이름**(정본에 실재한 적 없는 이름) |
| `grep -c` 계수 변화 | **한 줄이 두 항목을 담아** 계수가 안 변하는 경우 |
| 사양에 필터가 있음 | **코드에 있는가**(없었다) |
| 시드에 대기 건이 있음 | **그 건이 미리보기까지 가는가**(못 갔다) |

### 남은 이월 1건

`deploy-backend.yml` 이 실제 CI 에서 도는지 미검증 — ⚠ **에이전트로 진행 불가.**
막는 것은 코드가 아니라 **AWS 실물 자원 + GitHub Secret 3종**(`AWS_DEPLOY_ROLE_ARN` ·
`DEPLOY_BUCKET` · `EC2_INSTANCE_ID`)이고 사용자 계정 권한이 필요하다.
워크플로는 `workflow_dispatch`(수동 실행 전용)로 돼 있으며 그 근거가 파일 머리에 적혀 있다.
절차는 `docs/infra/DEPLOYMENT.md §2.13`.

---

## 8.11 ⚖ `R10` 결과 — 지도 잔여 + 사양 정합 (2026-09-17 **완료** · 메인 `0c2966df`)

분기점 `93c0c014` 에서 갈래 3개 병렬. **이 라운드의 산출물 대부분이 "만들지 않기로 한 판정" 이다.**

| 갈래 | 목표 | 결과 |
|---|---|---|
| `r10-t1` 매니저 앱 | 버스 마커 보간 | **해당 없음 — 좌표원이 부재.** 검사 7건만 추가 (`0c2966df`) |
| `r10-t2` 관계자 웹 | 지도 인증 실패 원인 구분 | **구분 불가 — SDK 가 신호를 주지 않는다.** 코드 변경 부재 |
| `r10-t2` 관계자 웹 | 오늘 운행 버스 마커 | **구현** — 기존 `getRunsLive()` 재사용 (`b4a34159`) |
| `r10-t3` 백엔드 | `current_stop` 사양 정합 | **사양 3곳 정정**(`Ruling 304`). 코드 변경 부재 |
| `r10-t3` 백엔드 | 관제 화면 `401` | **정상 동작으로 판정** — 전역 부트스트랩 `POST /auth/refresh` |

### 검사 수 — 병합 후 단독 전체 실행 (서버 `:8190` · DB `schoolbus_r10_final`)

| 대상 | 기준 | 실측 | 비고 |
|---|---:|---:|---|
| 백엔드 | 1,316 | **1,316**(223클래스) | 결과 XML 계수 · mtime 22:31:11 |
| 관계자 웹 | 251 | **254**(69파일) | `r10-t2` +3 |
| 학부모 앱 | 117 | **117** | ⚠ 아래 인자 항목 |
| 매니저 앱 | 118 | **125** | `r10-t1` +7 |
| `baraeda_core` | 48 | **48** | |
| `baraeda_ui` | 77 | **77** | |

**6종 전부 실패 0 · 건너뜀 0.**

### ⭐ Ruling 304 — `current_stop_name` 문면 정정 (사양 3곳)

`§4.3`(929)은 `Ruling 281` 로 이미 정정됐으나 **같은 형태가 3곳에 남아 있었다** —
`§3.11`(737) *"현재 위치명"* · `§5.18`(1883) 설명 부재 · `§7.1`(2229) *"현재 위치명"*.

**코드가 옳고 사양이 낡았다.** 갈래가 3개 클래스를 각각 열어 계산부를 확인했고
조율자도 `PositionBroadcastListener.currentStopNameOf:124` 를 직접 읽어 재확인했다 —

```java
ordered.stream().filter(s -> s.getArrivedAt() != null)
       .max(Comparator.comparingInt(RunStop::getSeq))
```

즉 **도착 기록이 있는 정차 중 `seq` 최댓값**(= 마지막으로 도착한 곳)이고 실시간 좌표가 아니다.
세 클래스가 **의도적으로 각자 재구현**한 것이며(소유 패키지 밖 의존 회피) 자바독이 상호 인용한다.

⚠ **`Ruling 281` 은 이 원장에 등재되지 않았다** — 갈래가 그 선례를 따라 304 도 안 남기려다
확인을 요청했고, 조율자가 **관례 불일치**로 판정해 여기 등재한다.

### ⚠ 조율자 오류 1건 — 전체 실행에서 드러났다

학부모 앱 첫 실행이 `+116 ~1`(**건너뜀 1**)이었다. 원인은 코드가 아니라
**조율자가 `--dart-define=FIXTURE_DB` 를 빠뜨린 것**이다. 그 검사(학생 소프트 삭제 → `404`)는
정상 API 로 재현할 수 없어 SQL 로 픽스처를 심는데, DB 이름을 인자로 받는다.
인자를 주니 통과 — **재시도는 그 파일 하나만** 했다(`phase-goal-loop §2.1`).

⇒ **`API_BASE_URL` 과 `FIXTURE_DB` 는 짝이다.** 학부모 앱 전체 실행에는 둘 다 준다.
`FIXTURE_DB` 를 안 주면 **던지지 않고 그 검사 1건만 건너뛰므로 종료 코드가 `0` 이다** —
건너뜀 수를 안 세면 영영 안 드러난다(`phase-goal-loop §5.2 4.1`).

### ⚠ 조율자 준비 누락 1건 — `r10-t1` 이 한동안 막혔다

`*.freezed.dart`·`*.g.dart` 는 `.gitignore` 대상(`frontend/**`)이라 **`git worktree add` 가
복제하지 않는다.** `backend/.env` 는 복사해 넣었으면서 생성 파일을 빠뜨렸고, 그 결과
매니저 앱 검사가 **기존 것까지 전부 컴파일 실패**했다.

⇒ **Dart 를 쓰는 갈래의 워크트리에는 생성 파일도 함께 복사한다**(`parallel-agents-git.md §12`).
현재 대상은 2개 — `baraeda_core/lib/error/failure.freezed.dart` ·
`manager-app/lib/features/offline_queue/data/offline_queue_database.g.dart`.

⚠ **갈래가 "대신 돌려 달라" 고 넘기지 않고 보고하고 멈춘 것이 옳았다** — 그 덕에 원인이
조율자 쪽이라는 것이 드러났다. 갈래가 우회했으면 **증상만 사라지고 다음 라운드에 재발**한다.

### 이월

| 항목 | 사유 |
|---|---|
| 오늘 운행 화면의 **승하차지 마커** | `§5.19` 웹 클라이언트 부재. 이번 목표는 "버스 마커" 라 범위 밖 판정이 옳았으나, 버스 마커 1개만으로는 노선 맥락 부재 — 필요 여부를 사용자에게 확인 |
| `deploy-backend.yml` CI 미검증 | ⚠ **에이전트로 진행 불가** — AWS 실물 자원 + GitHub Secret 3종 + 사용자 계정 권한 |
| iOS 지도 래퍼의 폐기 예정 API | 지금은 동작. 네이버가 제거하면 iOS 만 깨진다 — 외부 일정 의존 |

---

## 8.12 ⚖ `R11` 목표 표 — 연동 검사 공백 + 불필요 코드 삭제 (2026-09-17 계획 · **착수 전** · 메인 `0c2966df`)

**착수 근거** — `R10` 종결 후 전체 확인에서 나온 것. 검사는 6종 전부 통과했으나
**쓰기 경로에 실서버 계약 검사가 없다**(18건). 조사 기록은 `.claude/verify/seam-gap.md`(공백) ·
`.claude/verify/spec-drift.md`(사양 어긋남, 4건은 정정 완료).

⚠ **조율자가 그 조사 보고 1건을 실측으로 뒤집었다** — 갈래는 `POST /auth/logout`·`POST /auth/refresh`
웹 검사가 **부재**라고 보고했으나 `auth/api/realBackend.test.ts:238,252` 에 **실재**한다.
갈래가 *"검사가 그 함수를 import 하는가"* 로만 세어, **함수를 안 거치고 직접 요청하는 검사**를 못 봤다.
⇒ **공백은 19건이 아니라 18건.** 계수 방법이 한 층만 봤을 때의 전형이다.

### 갈래 3개 — 파일 무중복 · 병렬

| 갈래 | 대상 파일 | 전용 자원 |
|---|---|---|
| **`r11-t1`** 웹 쓰기 경로 계약 검사 16건 | `frontend/apps/academy-web/**` | 서버 `:8191` · DB `schoolbus_r11_t1` |
| **`r11-t2`** 모바일 계약 검사 2건 | `frontend/apps/parent-app/**` · `frontend/packages/baraeda_core/**` | 서버 `:8192` · DB `schoolbus_r11_t2` |
| **`r11-t3`** 불필요 백엔드 코드 삭제 | `backend/src/**` | DB `schoolbus_r11_t3` |

⚠ **`baraeda_core` 는 이번 라운드에 `r11-t2` 단독 소유**다. 다른 둘은 읽기만 한다.
⚠ **`frontend/apps/manager-app/**` 은 아무도 안 건드린다.**

### 목표 표 — 무엇이 통과하면 끝인가

| # | 갈래 | 완료 조건 | 검사 수단 |
|:-:|---|---|---|
| 1 | `t1` | **웹 쓰기 경로 16건에 실서버 계약 검사가 생긴다** | 각 `realBackend.test.ts` 가 그 경로를 **실제로 호출**. 판정은 import 목록이 아니라 **경로 문자열이 검사 본문에 있는가**(위 ⚠ 참조) |
| 2 | `t2` | **모바일 2건**(`POST /auth/logout` · `PATCH /students/{id}/weekly-address`)에 계약 검사가 생긴다 | 읽기(`getWeeklyAddress`)는 이미 있다 — **쓰기만** 채운다 |
| 3 | `t1`·`t2` | ⚠ **되돌릴 수 없는 상태를 소비하지 않는다** | **같은 명령 연속 4회**, 사이에 손질 없이 전부 실패 0·건너뜀 0. 초기화는 **검사 안**에 넣는다(`POST /dev/reset` 실재) |
| 4 | `t3` | **불필요한 백엔드 코드가 삭제된다** | 삭제 후 `./gradlew test` 전항 통과 + **삭제한 것마다 "왜 불필요한가" 근거** |
| 5 | `t3` | ⚠ **미완성 화면용 엔드포인트 3건을 지우지 않는다** | `GET /staff/runs/{runId}/route` · `GET /runs/{runId}/navigation` · `POST /staff/students/{id}/transfer` — **죽은 코드가 아니라 미구현 화면**이다(`seam-gap.md §B`) |
| 6 | `t3` | ⚠ **`POST /dev/reset` 을 지우지 않는다** | 의도된 개발 도구이며 **목표 3의 초기화가 이것에 의존**한다 |
| 7 | 전 갈래 | 각자 소유 묶음 **연속 4회** 실패 0 · 건너뜀 0 | 사이에 어떤 손질도 부재 |
| 8 | 조율자 | 병합 후 단독 전체 실행 **6종** 실패 0 · 건너뜀 0 | 기준 — 백엔드 1,316 · 웹 254 · 학부모 117 · 매니저 125 · `baraeda_core` 48 · `baraeda_ui` 77 |

⚠ **목표 4·5 가 이 라운드의 위험 지점이다** — Spring 은 컨트롤러·리스너·스케줄러를
**프레임워크가 호출**하므로 **호출 그래프상 전부 "죽은 코드" 로 보인다.** 그래프 도구 출력을
그대로 믿고 지우면 앱이 죽는다. `tokensave_dead_code` 는 이 저장소에서 **1,821건**을 보고했고
`tokensave_unused_imports` 는 **애너테이션 사용을 세지 않아** `@SpringBootApplication` 까지
"안 쓰는 import" 로 잡았다(5,936건) — **둘 다 판정 근거로 쓸 수 없다.**

---

## 8.13 ⚖ `R11` 결과 — 연동 검사 공백 + 불필요 코드 삭제 (2026-09-18 **완료** · 메인 `1421d567`)

| 갈래 | 결과 |
|---|---|
| `r11-t1` 웹 | **쓰기 경로 계약 검사 12건 추가 + 결함 1건 수정.** 남긴 4건은 사유를 코드 주석에 기록 |
| `r11-t2` 모바일 | **계약 검사 2건 추가** — `POST /auth/logout` · `PATCH /students/{id}/weekly-address` 쓰기 |
| `r11-t3` 삭제 | **1건.** 저장소가 이미 깨끗해 더 지울 것이 부재 |

### 검사 수 — 병합 후 단독 전체 실행 (서버 `:8190` · DB `schoolbus_r11_final`)

| 대상 | 기준 | 실측 | 증감 |
|---|---:|---:|---|
| 백엔드 | 1,316 | **1,316**(223클래스) | — |
| 관계자 웹 | 254 | **261**(69파일) | **+7** |
| 학부모 앱 | 117 | **118** | **+1** |
| 매니저 앱 | 125 | **125** | — |
| `baraeda_core` | 48 | **49** | **+1** |
| `baraeda_ui` | 77 | **77** | — |

**6종 전부 실패 0 · 건너뜀 0.**

### ⭐ 최대 수확 — **학생 등록·수정이 실서버에 대고는 동작한 적이 없었다**

`httpClient.ts` 가 multipart 의 JSON 파트를 **개별 필드로** 보냈는데 백엔드는
`@RequestPart("data")` 로 **하나로 묶인 JSON 파트**를 요구한다
(`StaffStudentController:102,113` — 조율자 직접 확인).

**왜 여태 안 드러났나** — 프런트 단위 검사는 **가짜 응답**을 쓰고, 백엔드 검사는 **자기 형식대로**
요청을 만든다. 양쪽 다 초록인데 **둘을 이어 보는 검사가 없었다.** 그 자리는
`student/api/realBackend.test.ts` 가 *"쓰기 경로는 넘어간다"* 고 **스스로 적어 둔** 곳이었다.

⇒ **가짜 응답만으로 검사하면 원리적으로 못 잡는 형태다.** 프론트 `F2` 의 응답 봉투 사고와 같은 계열.

### ⚠ 같은 계수 질문에 셋이 각각 다르게 세어 **셋 다 틀렸다**

*"이 엔드포인트가 실서버 검사로 덮였는가"* 하나에 세 주체가 세 방법을 썼고 전부 오답을 냈다.

| 누가 | 센 방법 | 놓친 것 |
|---|---|---|
| 조사 갈래 | 검사가 그 **함수를 import 하는가** | **원 요청을 직접 보내는** 검사(`auth` 2건을 공백으로 오보) |
| **조율자** | 검사 본문에 **경로 문자열**이 있는가 | **함수를 불러** 검사하는 정상 경우(t1 작업을 `0/16` 으로 오판) |
| `r11-t1` | 그 기능의 **검사 파일이 있는가** | 파일은 있어도 **그 항목은 미덮임**(*"이미 다뤄져 있었다"* 로 오보 — 실제 분기점 계수 **0/16**) |

⇒ **덮임 판정은 "함수명 또는 경로, 둘 중 하나라도" 로 센다.** 한 층만 세면 방향만 다를 뿐 똑같이 틀린다.

### ⚠ 조율자 준비 누락 — **같은 계열 3연속**

`pubspec.lock` 이 `.gitignore` 대상이라 워크트리에 복제되지 않았고, 워크트리가 전이 의존을
새로 해석해 **위젯 검사 10건이 컴파일 실패**했다. 갈래는 *"환경 문제"* 로 옳게 분류했으나
원인을 못 찾았고, **조율자가 메인에서 같은 명령을 돌려 통과하는 것을 보고서야 갈렸다**
(lock 복사 + `flutter pub get` 후 워크트리도 118 통과).

`R10` 의 생성 파일 누락과 같은 뿌리다. **전역 규칙 `parallel-agents-git.md §12.1` 에 등재했고,
목록을 기억하지 말고 뽑는 명령을 실측해 함께 실었다.**

### 이월

| 항목 | 사유 |
|---|---|
| 웹 계약 검사 **4건** | 경유지 2건(시드에 대상 회차 부재) · 비상 확인(되돌릴 API 부재 — **상태 분기 수법을 쓰면 가능할 수 있다**) · 보고서 상세(목록과 변환 공유) |
| `@Entity` 미사용 필드 **약 18건** | Lombok `@Getter` 때문에 선언 계수로는 판정 불가 — **getter 사용처를 세야** 한다. ⚠ 지우려면 마이그레이션이 딸리므로 **첫 배포 전에** 해야 한다 |
| `try/finally` 부재(웹 왕복 검사) | 중간 실패 시 고아 행이 남는다. 지금은 `globalSetup` 의 `POST /dev/reset` 이 덮고 있다 |
| `deploy-backend.yml` CI 미검증 | ⚠ 에이전트로 진행 불가 — AWS 자원 + 사용자 계정 권한 |

---

## 8.14 ⚖ `R12` 목표 표 — 잔여 이월 (2026-09-18 계획 · **착수 전** · 메인 `1421d567`)

**착수 근거** — `R11` 이 남긴 이월 중 **배포 CI 를 제외한 전부**(2026-09-18 사용자 지시).
배포는 AWS 실물 자원 + 사용자 계정 권한이 필요해 에이전트로 진행 불가라 범위 밖이다.

| 갈래 | 대상 파일 | 전용 자원 |
|---|---|---|
| **`r12-t1`** 웹 계약 검사 잔여 + 정리 구조 | `frontend/apps/academy-web/**` | 서버 `:8193` · DB `schoolbus_r12_t1` |
| **`r12-t2`** `@Entity` 미사용 필드 | `backend/**` | DB `schoolbus_r12_t2` |

### 목표 표

| # | 갈래 | 완료 조건 | 검사 수단 |
|:-:|---|---|---|
| 1 | `t1` | **비상 확인(`ackEmergency`) 계약 검사가 생긴다** | 되돌릴 API 가 부재하므로 **상태로 분기**한다 — `r11-t1` 이 `patchRunAssignment`·`decideSignupRequest` 에 쓴 수법을 그대로 따른다 |
| 2 | `t1` | **경유지 2건**(`addRunWaypoint`·`removeRunWaypoint`) 판정 | 막는 것이 *"시드에 대상 회차 부재"* 다. ⚠ **시드(`V2__seed_data.sql`)는 `t2` 소유다 — 고치지 말고 보고하라.** 검사 안에서 API 로 재료를 만들 수 있으면 만든다 |
| 3 | `t1` | **보고서 상세(`getReportDetail`) 판정** | 목록과 같은 변환을 공유한다는 기존 근거가 여전히 유효한지 확인. **유효하면 "불요" 가 정답**이고 억지로 만들지 않는다 |
| 4 | `t1` | **왕복 검사의 `try/finally` 부재를 해소한다** | 중간 실패 시 고아 행이 남는 구조. ⚠ **해소 여부를 검사로 고정하라** — 안 그러면 다음 사람이 되돌려도 아무도 모른다 |
| 5 | `t2` | **`@Entity` 미사용 필드가 판정된다** | ⚠ **"약 18건" 은 heuristic 추정치다(`R11` `r11-t3` 보고). 인용이니 직접 세라.** 판정은 **getter 사용처**로 한다 — 선언 계수는 Lombok `@Getter` 때문에 증거가 안 된다 |
| 6 | `t2` | **지운 것마다 근거가 남는다** | 컬럼을 지우면 **마이그레이션이 딸린다.** ⚠ 배포 전이라 `V1` 직접 수정이 허용되지만(`CLAUDE.md`), **첫 배포 후에는 금지로 뒤집힌다** — 지금이 마지막 기회다 |
| 7 | `t2` | ⚠ **raw SQL·JPQL 참조를 함께 센다** | getter 만 세면 **`@Query`·네이티브 SQL·시드(`V2`)·`jdbcTemplate` 이 쓰는 컬럼을 놓친다.** 하나라도 걸리면 지우지 않는다 |
| 8 | 전 갈래 | 각자 소유 묶음 **연속 4회** 실패 0 · 건너뜀 0 | 사이에 어떤 손질도 부재 |
| 9 | 조율자 | 병합 후 단독 전체 실행 **6종** 실패 0 · 건너뜀 0 | 기준 — 백엔드 1,316 · 웹 261 · 학부모 118 · 매니저 125 · `baraeda_core` 49 · `baraeda_ui` 77 |
| 10 | 조율자 | ⚠ **`V1` 이 바뀌면 보존 DB `schoolbus` 를 재구성한다** | 체크섬 불일치로 **기동 실패**한다. 코드 결함이 아니라 재구성 누락 신호다(`CLAUDE.md`) |

⚠ **`t2` 가 이 라운드의 위험 지점이다** — DB 컬럼 삭제는 되돌리기 어렵고, **getter 한 층만 세면
놓친다.** 조율자가 병합 전에 삭제 내역을 전부 읽는다. **애매하면 지우지 않는 쪽이 항상 옳다.**

---

## 8.15 ⚖ `R12` 결과 — 잔여 이월 (2026-09-18 **완료** · 메인 `ea288291`)

| 갈래 | 결과 |
|---|---|
| `r12-t1` 웹 | **계약 검사 2건 추가**(`ackEmergency`·매니저) + **왕복 검사 5파일에 `try/finally` 정리 보장.** 경유지 2건·보고서 상세는 **"만들지 않는다"** 판정 |
| `r12-t2` 필드 판정 | 후보 **17건 전수 검증 → 삭제 0건** 권고. 14건은 JPQL 실사용, 3건은 정본 설계 컬럼 |
| `r12-t3` 삭제 실행 | 사용자 결정에 따라 **컬럼 3건 삭제** — 엔티티·`V1`·`V2` 시드·`docs/ERD.md` 를 한 벌로 |

### 검사 수 — 병합 후 단독 전체 실행

| 대상 | 기준 | 실측 | 증감 |
|---|---:|---:|---|
| 백엔드 | 1,316 | **1,316**(223클래스) | — |
| 관계자 웹 | 261 | **263**(69파일) | **+2** |
| 학부모 앱 · 매니저 앱 | 118 · 125 | **118** · **125** | — |
| `baraeda_core` · `baraeda_ui` | 49 · 77 | **49** · **77** | — |

**6종 전부 실패 0 · 건너뜀 0.**

### ⭐ `r12-t3` 의 발견 — **스키마 쪽은 고정되지 않는다**

음성 대조를 **엔티티/스키마 두 방향으로 나눠** 6사이클 돌린 결과 —

| 되살리는 방향 | 결과 |
|---|---|
| **엔티티에 필드 재삽입** | 컨텍스트를 띄우는 사실상 전 검사가 `Schema validation: missing column` 으로 실패 ✅ |
| **스키마에만 `ALTER TABLE ADD COLUMN`** | ⚠ **아무것도 안 빨개진다** (3건 모두) |

**원인은 단일하다** — `ddl-auto: validate` 는 *"엔티티가 요구하는 컬럼이 스키마에 없는"* 방향만
검사하고 반대 방향은 검사하지 않는다. 그리고 `SchemaContractTest` 는 **테이블 집합(43개)만**
이름 대조하고 **컬럼 목록은 어느 테이블에 대해서도 들고 있지 않다**(조율자 재확인 —
`information_schema.columns` 를 쓰는 유일한 검사는 **시각 타입 검사**이지 목록 대조가 아니다).

⇒ **한 방향만 봤으면 "고정됐다" 로 끝났을 것이다.** 두 방향으로 나눈 설계가 이 비대칭을 드러냈다.

### ⚠ 같은 계수 함정을 **네 번째**로 밟았다 — 이번엔 조율자

| 회차 | 누가 | 센 방법 | 놓친 것 |
|:-:|---|---|---|
| 1 | 조사 갈래(`R11`) | import 만 | 원 요청을 직접 보내는 검사 |
| 2 | 조율자(`R11`) | 경로 문자열만 | 함수를 불러 검사하는 경우 |
| 3 | 구현 갈래(`R11`) | 파일 존재 | 파일은 있어도 그 항목은 미덮임 |
| 4 | **조율자(`R12`)** | **주석 미제거** | 주석에만 있는 이름을 본문으로 오인 |

⇒ **덮임 판정은 ①주석을 걷어낸 본문에서 ②함수명 또는 경로 중 하나라도** 로 센다.

### 조율자 후속 조치

- **보존 DB `schoolbus` 재구성 완료** — `V1` 이 바뀌어 체크섬이 어긋나므로 필수다.
  재구성 후 실측 — 마이그레이션 **12건** 적용 · 학생 **6** · 비상 신고 **1**(정본 시드와 일치) ·
  **지운 컬럼 3개가 `information_schema` 에 0건**
- ⚠ **출력 압축이 DB 목록의 줄을 접어 보존 DB 가 "사라진 것" 처럼 보였다.** `count(*) filter` 로
  **숫자를 세어** 실재를 확인했다 — **목록 출력으로 부재를 판정하지 마라**

### 이월

| 항목 | 사유 |
|---|---|
| ⭐ **스키마 고아 컬럼을 잡는 검사 부재** | `SchemaContractTest` 가 테이블 이름 집합을 대조하듯 **테이블별 컬럼 집합도 대조**하면 막힌다. ⚠ 이번 3건에 국한된 문제가 아니라 **구조적 빈틈** |
| **알림 배선 누락** | `NotificationLog` 의 `studentId`·`studentName`·`busNo` 를 **값으로 채우는 코드가 부재**. 생성 경로가 `NotificationOutbox:69 → forOutbox(...)` 하나뿐인데 그 인자에 없다. **사용자가 별도 라운드로 배정(2026-09-18)** |
| 경유지 계약 검사 2건 | 재료(`confirmed` 회차)를 만들 권한이 웹 검사 계정에 **원천적으로 부재** — 강제 확정이 메인관리자 전용. **시드 변경이 선행돼야 한다** |
| `deploy-backend.yml` CI 미검증 | ⚠ 에이전트로 진행 불가 — AWS 자원 + 사용자 계정 권한. **2026-09-18 사용자가 범위 밖으로 확정** |

## 8.16 ⚖ `R13` 목표 표 — 알림 배선 (2026-09-19 계획 · **착수 전** · 메인 `1568ee9f`)

**문제** — `notification_log` 의 `student_id`·`student_name`·`bus_no` 를 **값으로 채우는 코드가
프로덕션에 부재**. 생성 경로는 `NotificationOutbox.append` → `NotificationLog.forOutbox(...)`
하나뿐이고(`graft grep forOutbox` — 프로덕션 1곳 · 시험 5곳), 그 인자 9개에 세 값이 없다.
컬럼(`V1`) · 응답 DTO(`NotificationItemResponse` · `StaffNotificationItemResponse`) ·
사양(`API_SPEC §3.12`·`§5.17`) · 시드는 전부 준비돼 있고 **값 넣는 쪽만** 결락.
⇒ 시드 알림은 자녀 이름이 보이고 **앱이 만든 알림은 전부 빈 값**.

### 판정 기준은 정본에서 나온다 — 21종을 임의로 분류하지 않는다

`ERD.md` `notification_log` 절이 정의처다.

| 컬럼 | 정본 정의 |
|---|---|
| `student_id` | 대상 자녀 |
| `student_name` | 자녀 이름 스냅샷. **알림 문구에 필수 포함되는 값** |
| `bus_no` | 호차 |

⇒ **문구(`title`·`body`)를 만드는 Composer 가 자녀 이름을 넣으면 그 종류는 `student_*` 를 채운다.
호차를 넣으면 `bus_no` 를 채운다.** 이것이 유일한 판정 기준이며(ATT-03 이 근거 —
"모든 문구에 자녀 이름 포함"), `API_SPEC §9.7` 수신자 열은 결과를 **검증**하는 데 쓴다.

### 조율자 실측 (2026-09-19) — ⚠ 인용이다. 직접 세고 어긋나면 보고하라

| 항목 | 값 | 센 방법 |
|---|---:|---|
| 알림 종류 | 21 | `NotificationType` enum 상수 |
| 적재 호출 지점 | 20 | `grep -rn "notificationOutbox.append(" backend/src/main/java` |
| 리스너 파일 | 13 | 같은 grep 의 `-l` |
| 리스너가 받는 이벤트 | 17 | `@EventListener` 메서드 인자 |
| 그중 `studentId` 보유 | 10 | 이벤트 record 선언 파싱 |
| 그중 `busNo` 보유 | 2 | `EmergencyRaisedEvent` · `EmergencyCanceledEvent` |

### 목표 — 전항 통과가 완료 조건

| # | 완료 조건 | 실행 명령 · 검사 조건 |
|:-:|---|---|
| 1 | `NotificationDraft` 가 `studentId`·`studentName`·`busNo` 를 갖는다 | 컴파일 + `grep -c 'studentId\|studentName\|busNo' NotificationDraft.java` = 3 이상 |
| 2 | `forOutbox` 가 그 셋을 받아 엔티티에 저장한다 | 엔티티 왕복 검사 — 저장 후 재조회로 세 값 일치 |
| 3 | **문구에 자녀 이름이 들어가는 모든 종류**에서 적재 행의 `student_name` 이 그 이름과 일치 | 종류별 통합 검사. **대상 종류를 Composer 전수로 세어 보고서에 적는다** |
| 4 | **문구에 호차가 들어가는 모든 종류**에서 `bus_no` 가 채워진다 | 같음(최소 `emergency`·`emergency_canceled`) |
| 5 | 채우지 **않는** 종류는 정본 근거와 함께 보고서에 나열 | 21종 전부가 3·4·5 중 하나에 배정됐는지 계수 |
| 6 | `GET /notifications` 응답에 `student_id`·`student_name` 이 실제로 내려간다 | 컨트롤러 검사 — 적재→조회 왕복, `null` 아님 |
| 7 | 음성 대조 — 배선을 되돌리면 **그 검사만** 실패 | 값 전달을 `null` 로 바꿔 심고 실패 확인 → 원복 → `git status --porcelain` 빈 결과 |
| 8 | 백엔드 전체 **단독** 실행 | 실패 **0** · 건너뜀 **0** · 검사 수 **≥ 1,316** (결과 XML 로 계수) |

**범위 밖** — 스키마 변경 부재(컬럼이 이미 있다) ⇒ 마이그레이션·보존 DB 재구성 불필요.
프론트엔드 표시 변경은 이 라운드 범위 밖.

## 8.17 ⚖ `R13-T2` 목표 표 — 스키마 고아 컬럼 검사 (2026-09-19 계획 · **착수 전** · 메인 `e83312d7`)

**R12 가 등재한 구조적 빈틈**(`§8.15` 이월 1행). 컬럼 삭제 음성 대조를 두 방향으로 나눠 돌린 결과 —

| 되살리는 방향 | 결과 |
|---|---|
| 엔티티에 필드 재삽입 | `Schema validation: missing column` 으로 실패 ✅ |
| **스키마에만 `ALTER TABLE ADD COLUMN`** | ⚠ **아무것도 안 빨개진다**(3건 모두) |

**원인은 단일** — `ddl-auto: validate` 는 *"엔티티가 요구하는 컬럼이 스키마에 없는"* 한 방향만 보고,
`SchemaContractTest` 는 **테이블 집합(43개)만** 대조하며 **컬럼 목록은 어느 테이블에 대해서도 부재**하다
(`information_schema.columns` 를 쓰는 유일한 검사는 시각 타입 검사이지 목록 대조가 아니다).

### 목표 — 전항 통과가 완료 조건

| # | 완료 조건 | 실행 명령 · 검사 조건 |
|:-:|---|---|
| 1 | 테이블별 **컬럼 집합 대조**가 존재한다 — 엔티티 매핑 ↔ `information_schema.columns` | `testsupport/db/` 안에 위치. 기존 테이블 집합 대조와 같은 방식 |
| 2 | **스키마에만 있는 컬럼**(엔티티 미매핑)이 있으면 **실패**한다 | 허용 예외는 **명시적 목록 + 사유 주석**으로만. 목록은 최소로 |
| 3 | ⭐ **음성 대조 — 임의 테이블에 `ALTER TABLE ADD COLUMN` 으로 컬럼을 심으면 이 검사가 실패한다** | R12 가 못 잡은 **바로 그 형태**. 이 항이 이 라운드의 본체다 |
| 4 | 현재 스키마에서 통과한다 | ⚠ **고아 컬럼이 실재하면 허용 목록에 넣지 말고 보고하라** — 지울지는 별도 판단(R12 §6.4 의 "성급한 닫힘") |
| 5 | 백엔드 전체 **단독** 실행 | 실패 **0** · 건너뜀 **0** (결과 XML 로 계수) |

**범위 밖** — 고아 컬럼을 발견해도 **삭제하지 않는다**(보고까지). 스키마·마이그레이션 변경 부재.

## 8.18 ⚖ `R13` 결과 — 알림 배선 + 스키마 고아 컬럼 검사 (2026-09-19 **완료**)

| 갈래 | 결과 |
|---|---|
| `r13-t1` 알림 배선 | 21종 배정 — **`student_*` 12 · `bus_no` 3 · `null` 6** (합 21). 적재 지점 20곳 전부 배선 |
| `r13-t2` 스키마 검사 | `SchemaContractTest` 에 **엔티티 매핑 ↔ `information_schema.columns` 컬럼 집합 대조** 추가. **고아 컬럼 0건 · 허용 목록 미생성** |

### 검사 수 — 병합 후 단독 전체 실행 (DB `sb_r13_final`)

| 대상 | 기준 | 실측 |
|---|---:|---:|
| 백엔드 | 1,316 | **1,319**(223클래스) |

**실패 0 · 오류 0 · 건너뜀 0 · `UP-TO-DATE`/`FROM-CACHE` 0** (결과 XML 직접 계수).

### ⭐ 가장 값진 것 — **조율자가 준 판정 기준이 틀렸고 갈래가 잡았다**

조율자가 `ERD.md` 문면(`student_name` = *"알림 문구에 필수 포함되는 값"*)에서
**"Composer 가 자녀 이름을 넣으면 채운다"** 를 판정 기준으로 발주했다.
**그 기준대로면 21종 전부 "안 채움" 이 된다** — 조율자 독립 계수 실측:
**Composer 18개 중 본문에 자녀 이름을 넣는 것 0건**(주석 제거 후 계수).
이미 `Ruling 225` 가 *"문구 조립기 16개 전부가 자녀 이름을 안 넣는다"* 로 기록해 둔 사실이다.

⇒ 갈래가 기준을 **"이벤트가 단일 학생·차량을 가리키면 채운다"** 로 바꿔 적용하고
**확신도 70% 로 자진 신고**했다. 옳은 처신이고, 신고가 없었으면 조율자는 기준이 틀린 줄 몰랐다.

⚠ **조율자 오류의 형태** — `ERD` 문면은 `student_name` 의 **용도**(ATT-03 이행 시 쓰일 값)를
서술한 것이지 **채우는 조건**이 아니었다. 컬럼 정의(`student_id` = *"대상 자녀"*)가 조건이다.
**정본 문장을 조건문으로 읽기 전에 그 조건이 현재 코드에서 참이 되는지 세어 봤어야 했다**
(`phase-goal-loop §5.1` 의 "검사 명령을 먼저 돌려 봐라" 와 같은 형태).

### ⚠ 조율자 오류 2건 — 둘 다 절차 누락

| # | 오류 | 탐지 |
|:-:|---|---|
| 1 | **목표 표를 커밋하지 않고 워크트리 생성** — `git worktree` 는 추적 파일만 체크아웃하므로 `§8.16` 이 갈래 트리에 부재했다 | 갈래가 *"완료 조건 정본이 부재"* 로 **코드를 안 건드리고 `BLOCKED`** — `parallel-agents §12` 그대로 |
| 2 | ⭐ **Orca 조율 루프를 통째로 누락** — Skill `orchestration` 을 안 읽고 CLI 를 기억으로 다뤘다. 발주는 맞았고 **기다리기·정산이 전부 빠졌다** | 워커 2개가 `worker_done` 을 정상 발신했는데 조율자가 못 받고 **영구 대기**. **사용자 지적이 유일한 탐지 수단** |

오류 2 의 재발 방지 — **조율 작업의 첫 단계는 Skill `orchestration` 호출**(2026-09-19 사용자 상시 지시).
절차를 `CLAUDE.md` 에 복제하지 않는다 — 가이드가 CLI 에서 나오는 이유가 바이너리와 어긋나지 않게 하기 위해서다.

### 이월

| 항목 | 사유 |
|---|---|
| **ATT-03 미이행 — 문구에 자녀 이름 부재** | `Ruling 225` 그대로. Composer 18개 전부. 입력이 이벤트라 **넣을 수단 자체가 부재**해 이벤트 계약 변경이 선행돼야 한다. **이번 배선이 그 선행 조건을 만든 것**(스냅샷 컬럼이 이제 채워진다) |
| **`arrive` 의 학생 본인 수신 미구현** | `API_SPEC §9.7` 은 수신자를 "학부모·학생" 으로 적는데 `RunApproachingStopNotificationListener` 는 **첫 보호자에게만** 보낸다. R13 이전부터 있던 별도 결함 |
| `bus_no` 확장 보류 | `run_started`·`delay` 관계자 다리는 `Run` 을 한 번 더 거쳐야 해 보류. `route_changed` 는 `busId` 1단이라 포함했다 |
| 경유지 계약 검사 2건 · 배포 CI | R12 에서 이월된 그대로 (후자는 범위 밖 확정) |

## 8.19 ⚖ `R14` 목표 표 — 알림 문구 정합 + 되돌리기 제한 + 계약 검사 (2026-09-19 계획 · **착수 전**)

**범위 판정 — A3(미승차 되돌리기 정정 알림)은 뺐다.** 새 알림 종류(`NotificationType` 값)가 필요한데
정본이 **미정**으로 남겨 둔 자리다(`Ruling 223 ②`). `CLAUDE.md` 가 *"새 기능 ID·상태값을 만들지 않는다"* 를
못박으므로 **사용자 결정 전에는 착수 불가**. C 계열로 되돌린다.

### 갈래 3개 — 파일 무중복 · 병렬

| 갈래 | 범위 | 건드리는 곳 |
|---|---|---|
| `r14-t1` | **A1 알림 문구에 자녀 이름(ATT-03)** + **A2 `arrive` 학생 본인 수신** + **B5 `bus_no` 확장** | `notification/**` · 알림 이벤트 record |
| `r14-t2` | **X-04 되돌리기 제한**(Ruling 305) | `boarding/**` |
| `r14-t3` | **B4 경유지 계약 검사 2건** + 선행 시드 변경 | 웹 계약 검사 · `db/migration-local/V2__seed_data.sql` |

⚠ **T1 이 전 리스너를 건드리므로 A2·B5 를 다른 갈래로 뗄 수 없다** — 이벤트가 이름을 나르게 하려면
리스너 13개를 전부 지나야 한다.

### 목표 — 전항 통과가 완료 조건

| # | 갈래 | 완료 조건 | 검사 조건 |
|:-:|:-:|---|---|
| 1 | T1 | **자녀 이름이 문구에 들어가는 알림은 `title`·`body` 에 그 이름이 실재한다** | 대상 종류를 `API_SPEC §9.7`·ATT-03 으로 가려 **표로 적고**, 종류마다 문구를 단언 |
| 2 | T1 | 이름을 **안 넣는** 종류는 정본 근거와 함께 나열 | 21종 전부가 1·2 중 하나에 배정 — 합이 21 |
| 3 | T1 | `student_name` 컬럼 값과 문구 속 이름이 **같다** | R13 배선이 만든 값을 재사용(두 벌로 갈리지 않게) |
| 4 | T1 | **`arrive` 알림을 학생 본인도 받는다**(`§9.7` 수신자 "학부모·학생") | 현재는 첫 보호자만 — 학생 수신분 적재 단언 |
| 5 | T1 | `bus_no` 가 `run_started`·`delay` 의 **관계자 알림**에도 채워진다 | `Run`→`Bus` 조회 1단 추가 |
| ~~6~~ | T2 | ⚠ **폐기 — `§8.20` 목표 6a·6b·6c 가 대체한다(Ruling 307).** 여기 적힌 *'뒤 순번 도착'* 파생 규칙은 **마지막 승하차지에 뒤 순번이 없어** 영원히 되돌릴 수 있는 구멍이 있었다 | `§8.20` 을 보라 |
| 7 | T2 | **횟수 제한은 두지 않는다** | 같은 학생을 연달아 3회 되돌려도 전부 성공하는 단언 |
| 8 | T3 | 경유지 계약 검사 2건이 **실제로 돈다** | **건너뜀 0** — 시드에 `confirmed` 회차를 넣어 재료를 만든다 |
| 9 | 전체 | 음성 대조 — 각 갈래가 심은 변형에 **그 검사만** 실패 | 변형 1회 = 원복 1회 = `git status --porcelain` 확인 1회 |
| 10 | 전체 | 병합 후 **단독 전체 실행** | 백엔드 실패 0 · 건너뜀 0 · 검사 수 **≥ 1,319** · 웹/앱 4종 실패 0 |

**범위 밖** — A3 미승차 되돌리기 정정 알림(사양 미정) · X-02(사용자 검토 대기) · 배포(보류).

## 8.20 ⚖ 사용자 확정 3건 (2026-09-19) — `Ruling 307`·`308` · R14 범위 재조정

### Ruling 307 — 승하차지 **출발** 상태를 도입한다

지금 승하차지별로 추적되는 것은 **근접(300m · 위치 기반 자동)** 과 **도착(`arrived_at` · 기사 수동 버튼)** 둘뿐이고
**출발은 부재**하다(`departed`·`left_at` 스키마 0건).

| 항목 | 확정 |
|---|---|
| 판정 | 도착 처리(`arrived_at IS NOT NULL`)된 승하차지에서 버스가 **100m** 밖으로 벗어난 **최초 시점** |
| 저장 | `run_stop.departed_at` (신설) |
| 재사용 | `ProximityJudge`(거리 계산) · `RunPositionReader`(위치) · 주기 스케줄러 — **새로 만들 부품 부재** |
| 상수 | **100m**. 근접 300m 와 **다른 값**이다 — 도심에서 300m 이탈은 너무 늦다는 판단 |

⚠ **초안의 파생 규칙 *"뒤 순번 승하차지가 도착하면 떠난 것"* 은 폐기한다** — **마지막 승하차지에 뒤 순번이
없어** 영원히 되돌릴 수 있는 구멍이 있었다(사용자 지적으로 발견).

### Ruling 308 — 승하차 알림을 **출발 시점**에 확정 결과로 1회 발송

`NTF-01`·`NTF-02`·`BRD-04` 가 정한 **즉시 발송**을 바꾼다. 근거·세부는 `FEATURE_SPEC §4.15` 머리 단락.

- **묶는 것은 발송 시각뿐** — 내용은 수신자별·학생별 개별 발송(한 알림에 남의 자녀를 담지 않는다)
- **정정 알림 부재** — `Ruling 219`(취소 알림 추가 발행)를 **대체**. `Ruling 223 ②`(미승차 되돌리기 정정 부재)도 **소멸**
- ⚠ **폴백 — 신호 유실·기사의 도착 미처리로 출발 판정이 안 되면 다음 승하차지 도착 시 강제 발송**

**하나의 개념이 두 규칙을 설명한다** — `Ruling 305`(되돌리기 경계)와 `Ruling 308`(발송 시점)이 **같은 선**이다.

### R14 범위 재조정

| 갈래 | 조치 |
|---|---|
| `r14-t1` 알림 문구 | **계속.** 문구에 이름을 넣는 일은 발송 시점과 무관하다 |
| `r14-t2` | **확장 재발주** — 목표 6 을 *"`departed_at` 도입(Ruling 307) + 그 위에 되돌리기 제한"* 으로 바꾼다 |
| `r14-t3` 경유지 계약 검사 | **계속.** 무관 |
| **R15** | **알림 발송 시점 이동(Ruling 308)** — T1 과 같은 파일을 건드려 분리한다 |

### 목표 표 증분 (T2 교체분)

| # | 완료 조건 | 검사 조건 |
|:-:|---|---|
| 6a | `run_stop.departed_at` 이 **도착 후 100m 이탈 시점**에 기록된다 | 좌표를 주입해 100m 안/밖을 각각 검사. **최초 1회만** 기록(`claimProximityNotice` 와 같은 조건부 UPDATE 형태) |
| 6b | **출발한 승하차지의 되돌리기는 거부된다** — `409 STOP_ALREADY_DEPARTED` | `departed_at IS NOT NULL` 이 유일한 판정. **뒤 순번 참조 부재** |
| 6c | **마지막 승하차지에도 걸린다** | 파생 규칙이 못 잡던 자리 — 단독 검사로 못박는다 |
| 7 | 횟수 제한 부재 | 같은 학생을 연달아 3회 되돌려도 전부 성공 |

## 8.21 ⚖ 사용자 확정 (2026-09-19) — `Ruling 309`·`310` · 지도 화면 개편 + 실제 도로 경로

### Ruling 309 — 도로 경로 좌표를 **저장해서** 화면에 내려준다 (A 방안)

**증상** — 지도에 노선을 그리면 **지형을 뚫는 직선**으로 나온다(사용자 실측).

**원인 — 이미 받고 있는 좌표를 버린다.** `NaverDirectionsGateway` 가 응답에서
`route.traoptimal[].summary.distance/duration` **만** 꺼내고, 같은 응답에 실려 오는
**`route.traoptimal[].path`(도로를 따라가는 좌표 배열)를 파싱하지 않는다.**
⇒ 화면이 가진 것은 승하차지 좌표뿐이라 이으면 직선이 된다.

| 검토안 | 판정 |
|---|---|
| **A. 백엔드가 `path` 를 파싱·저장하고 API 로 제공** | ✅ **채택** — 좌표가 이미 응답에 오므로 **추가 요금 0**, 화면이 그리는 경로가 **실제 확정 경로와 같음이 보장** |
| B. 프론트가 네이버에 직접 요청 | ❌ **불가** — Directions 는 서버용 REST API. 브라우저 호출은 **키 노출 + CORS**. 웹 지도 JS SDK v3 에 경로 탐색 기능 부재 |
| C. 조회 전용 엔드포인트(저장 부재) | △ 차선 — 같은 구간을 다시 사면서 **재계산 시점 차이로 다른 답**이 나올 수 있다 |

- ⚠ **`fallbackUsed=true`(지도 API 장애)면 좌표가 부재해 직선이 된다 — 화면에 "근사 경로" 를 표시한다.**
  표시하지 않으면 사용자가 그 직선을 실제 경로로 믿는다(`TECH_DECISIONS §8` 과 같은 근거)
- 📌 **함께 발견 — 구간별 거리·시간도 근사값이다.** `StraightLineLegs.distribute` 가 네이버의 **총합**을
  **직선거리 비율로** 구간에 배분한다(정상 응답일 때조차). ETA 정확도에 영향 — **별도 이월**

### Ruling 310 — 지도 화면 개편 (관계자 웹 · 3개 화면 전부)

| 항목 | 확정 |
|---|---|
| 대상 | **지도가 나오는 3개 전부** — `DashboardPage`(운행 관리) · `TodayRunPage`(오늘 운행) · `MonitoringPage`(관제) |
| 배치 | **지도를 화면 상단에 가득**, 그 **우측에 버스별 상태 목록** |
| 상태 표기 | `idle`(대기) · `confirmed`(확정) · `moving`(운행 중) · `finished`(종료) — **운행종료도 목록에 남긴다** |
| 클릭 | 버스를 고르면 **그 노선이 지도에 표시** — Ruling 309 의 실제 도로 경로 |

### 실행 순서 — **R14 를 먼저 닫고 R15 로 간다** (2026-09-19 사용자 지시)

Ruling 309 가 **스키마·포트 계약을 건드리는데 `r14-t2` 가 같은 파일(`V1`)을 고치는 중**이라 충돌한다.
⇒ **R14(T1·T2·T3) 병합·검증을 마친 뒤 R15 를 착수**한다. 사용자 지시 — *"충돌 날 것 같으면 마무리하고 진행"*.

**R15 범위** — ①`path` 파싱·저장·API(Ruling 309) ②지도 화면 개편 3종(Ruling 310) ③알림 발송 시점 이동(Ruling 308).

## 8.22 ⚖ `R14` 결과 — 알림 문구 + 출발 상태 + 계약 검사 (2026-09-19 **완료**)

| 갈래 | 결과 |
|---|---|
| `r14-t1` | **ATT-03 이행** — 토글 6종 알림 문구에 자녀 이름 삽입 + **`arrive` 학생 본인 수신** + `bus_no` 확장(`run_started`·`delay` 관계자 다리) |
| `r14-t2` | **`run_stop.departed_at` 신설**(도착 후 **100m** 이탈, Ruling 307) + 되돌리기 제한(`409 STOP_ALREADY_DEPARTED`) + 횟수 제한 부재 유지 |
| `r14-t3` | 경유지 계약 검사 2건 **실행**(건너뜀 0) + 선행 시드 `R8` + **프런트 응답 매핑 정정** |

### 검사 수 — 병합 후 단독 전체 실행 (DB `sb_r14_final`)

| 대상 | 기준 | 실측 |
|---|---:|---:|
| 백엔드 | 1,319 | **1,334**(228클래스) |
| 관계자 웹 | 263 | **265** |

**실패 0 · 오류 0 · 건너뜀 0 · `UP-TO-DATE`/`FROM-CACHE` 0.**

### ⭐ `r14-t3` — **이월 항목의 전제 자체가 틀렸다**

R12 가 남긴 이월은 *"시드 변경이 선행돼야 한다"* 였다. **재료 부재는 사실이었으나 그 뒤에 진짜 결함이
있었다** — `route_preview` 응답 모양과 프런트 타입이 어긋나 있었고 `removed` 필드가 **아예 누락**이었다.
갈래가 서버에 직접 요청을 보내 응답을 실측하고서야 드러났다.

⇒ **발주문의 *"인용이지 사실이 아니다. 직접 재현해서 확인하라"* 한 줄이 유일한 탐지 수단이었다**
(`phase-goal-loop §6.3` 의 "이월 note 안의 사실 주장을 재계수하라" 가 그대로 재현).

### ⚠ 조율자 오류 2건

| # | 오류 | 탐지 |
|:-:|---|---|
| 1 | **판정 기준을 파생 규칙으로 줬다** — *"뒤 순번 승하차지가 도착하면 떠난 것"*. **마지막 승하차지에 뒤 순번이 없어** 영원히 되돌릴 수 있는 구멍 | **사용자 지적** |
| 2 | ⭐ **끝난 워커에 `terminal send` 로 후속 지시를 보냈다** — 글자만 입력창에 들어가고 **제출되지 않았다.** 워커는 빈 프롬프트에서 대기했고 조율자는 "수정 중" 으로 오인 | **사용자 질문**("T3 동작하고 있어?") |

오류 2 의 재발 방지 — **후속 작업은 새 Dispatch 로 준다**(`worker-start --spec … --terminal <핸들>`).
⚠ **`liveness: live` 는 일하는 중이라는 뜻이 아니다** — 프롬프트 대기도 `live` 다. **진행은 산출물 변화로 본다.**

### ⚠ 병합 후에만 드러난 것 1건

`r14-t3` 의 시드 `R8` 이 `student_id=1` 을 태워 **`StudentRunsControllerTest` 2건이 실패**했다
(회차 4건 → 5건). 갈래는 *"`V2` 는 local·demo 전용이라 백엔드 시험에 영향 부재"* 로 판단해 전체 실행을
생략했는데, **그 시험은 `SeedFixtures` 로 시드를 직접 읽는다.** `parallel-agents §18`(전체를 세는 시험)의 시드 판이다.
⇒ 같은 갈래에 후속 Dispatch 로 돌려 정정(출발 시각 순서 `3·2·8·1·6` 을 조율자가 DB 에서 실측해 전달).

### 이월

| 항목 | 사유 |
|---|---|
| **`change_decided` 에 자녀 이름 미포함** | `r14-t1` 이 정본의 "토글" 문구를 좁게 읽어 제외(확신 70%). 다자녀 가정이 같은 시각에 두 자녀 결과를 받으면 구분 불가 — **확장 여지** |
| `claimDeparture` 동시성 시험 부재 | 근접 판정(`claimProximityNotice`)에는 전용 시험이 있는데 출발 판정에는 부재 |
| 출발 판정 대상 선정 | *"도착·미출발 중 `seq` 최솟값 1건"* — 신호 유실로 과거 정차지가 쌓이는 경우 미검증 |
| **구간별 거리·시간이 근사값** | `StraightLineLegs.distribute` 가 총합을 직선거리 비율로 배분(Ruling 309 부록) |
| `mapRoute` 외부 API 간헐 503 | 코드 결함 부재 — 시험 전용 지연 재시도로 대응 |

**다음 — R15**: 도로 경로 저장·표시(Ruling 309) · 지도 화면 개편 3종(Ruling 310) · 알림 발송 시점 이동(Ruling 308).

## 8.23 ⚖ `R15` 목표 표 — 도로 경로 + 지도 개편 + 알림 발송 시점 (2026-09-19 착수)

**근거** — `Ruling 309`(§8.21) · `Ruling 310`(§8.21) · `Ruling 308`(§8.20). 셋을 한 라운드로 묶은
이유는 §8.21 "R15 범위" 에 적혀 있다.

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
| 6 | 전체 실행 실패 0 · 건너뜀 0 | 기준 **265건**(§8.22) — 인용이다. 직접 세라 |

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
| 10 | 전체 실행 실패 0 · 오류 0 · **건너뜀 0** | 기준 **1,334건**(§8.22) — 인용이다. 직접 세라 |

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

`r15-t2` 는 **198건**(실패 0·건너뜀 0)을 보고하고 *"§8.22 의 265 는 `realBackend` 를 포함한 수치로 추정"*
이라고 적었다. 조율자가 확인한 결과 **맞다**:

- §8.22 계열 기록이 일관되게 **"69파일"** 로 적혀 있고, T2 워크트리 실측은 **일반 56파일 + `realBackend` 13파일 = 69**
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

| 대상 | 기준(§8.22) | 실측 |
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

## 8.36 ⚖ `R22` 결과 — 버스 마커 식별 · 출발도착 표시 · 대기 회차 예정 경로 (2026-09-20 **완료**)

**메인 `bfea43ad`** · 커밋 2개(`4a69657b` · `bfea43ad`) · 관계자 웹 **366**(실서버 계약 포함) /
백엔드 **20**(변경 범위) · 실패 0 · **건너뜀 0**.
전부 **사용자가 화면을 직접 보고 지적한 것** — 3건 + 진행 중 추가 지시 2건.

### 목표 판정

| 지적 | 판정 | 실제 원인 · 처리 |
|---|:-:|---|
| 버스마다 다른 아이콘 | ✅ | 번호·등하원 구별은 이미 존재. 번호가 **핀 아래 라벨**이라 축소 시 겹쳐 읽힘 ⇒ 번호를 핀 안으로 |
| 출발지·목적지 미표시 | ✅ | `§5.19` 응답에 **그 필드가 부재**. `roadPath` 양 끝을 사용 (등원 = 승차지→학원, 하원 = 학원→하차지, `Ruling 190`) |
| 대기 회차가 "확정되지 않은 회차입니다" | ✅ | **시드 시간대 결함** — 아래 관측 1 |
| (추가) 전체 관제에서 노선 조회 `403` | ✅ | `Ruling 323` |
| (추가) 버스마다 다른 색 | ✅ | 상태 4색·정차지·학생·출발도착 색을 피한 파랑~청록 **6색**. 목록 순서가 아니라 **번호 문자열**로 결정 — 순서로 정하면 회차 증감 시, 또 화면마다 같은 버스가 다른 색 |

### ⭐ `Ruling 323` — 전체 관제의 노선 조회를 메인 관리자에게 연다 (2026-09-20 사용자 승인)

**판정** — `§5.19 GET /staff/runs/{runId}/route` 의 권한을 **학원 관계자 + 메인 관리자** 로 확장.
`@CanMonitorRunRoute` 를 신설해 `MONITOR_ACADEMY` · `MONITOR_ALL` 중 **하나면 통과**.

**경위** — 그 엔드포인트의 권한이 `MONITOR_ACADEMY` 하나뿐이고 그 권한은 `Role.STAFF` 전용이다
(`RolePermissions` 가 역할 간 상속을 금지). 메인 관리자는 `MONITOR_ALL` 을 따로 보유해,
**전체 관제 화면(`§6.8`)에서 버스를 눌러 노선을 그리는 기능이 `403 FORBIDDEN` 으로 통째 차단** 상태.

⚠ **`@CanMonitorAcademy` 자체를 넓히지 않는다** — 대시보드(`§5.3`)·실시간 회차(`§5.18`)도 그
애너테이션을 쓰는데 두 조회는 `requester.academyId()` 로 범위를 좁힌다. `academyId` 가 `null` 인
메인 관리자가 들어가면 **오류 없이 빈 결과**가 조용히 반환. 학원 격리는 그대로 `AcademyScope` 가 판정.

**정본 반영** — `API_SPEC §5.19` 권한 표기 갱신 완료.
**동반 수정** — 같은 형태의 결함 1건. `ackOf` 가 요청자의 `academyId` 로 확인 현황을 조회해
메인 관리자는 늘 "둘 다 미확인". 접근 판정이 끝난 뒤이므로 **회차가 속한 학원**으로 조회하도록 교체.

### 관측

| 관측 | 내용 |
|---|---|
| ⚠⚠ **날짜와 요일을 다른 시계에서 뽑아 기능이 통째로 정지** | `service_date` 는 KST 인데 `extract(dow from now())` 는 UTC. 2026-09-19 에 **날짜만 KST 로 고치고 요일을 잔존**시킨 자리. 한국시간 09:00 이전에는 요일이 하루 앞서서 `plannedRouteOf` 의 **(학원·버스·요일·방향) 4중 일치**가 고정 노선 조회 실패 → `409`. ⚠ **토요일 작업에서는 미발현** — UTC 도 같은 토요일. `V2` 7곳 · `V12` 1곳을 `AT TIME ZONE 'Asia/Seoul'` 로 통일 |
| ⚠⚠ **마커를 만들어도 카메라가 미이동이면 안 만든 것과 동일** | 단위 검사 전부 초록인데 "도착" 이 지도 아래로 잘림. `NaverMapSurface` 의 포커스 신호가 **선택 버스 마커 하나뿐**이라, 실시간 위치가 없는 회차(대기·확정·종료)는 선택해도 카메라가 제자리. 노선 id 도 신호에 포함하고, 노선이 있으면 고정 배율(15) 대신 `fitBounds` 로 전환 — 고정 15 는 시드 정차지 4곳(대각선 426m) 기준이라 8km 노선에서 한쪽 끝이 화면 밖 |
| 카메라는 여전히 **"선택할 때만"** 이동 | `R21-A` 지시 유지 — 위치 갱신이 사용자가 옮긴 지도를 덮지 않게. 선택 없이 여러 대를 한 화면에 담는 처리는 미도입 |

### 이월

| # | 항목 |
|:-:|---|
| 1 | 7대 초과 시 **버스 색 중복** — 그때는 칩 안의 번호가 구별 수단 |

---

## 8.37 ⚖ `R23` 결과 — 데모 선단 3대 · 로컬 운행 시뮬레이터 · 지도 마커 선택 (2026-09-20 **완료**)

**메인 `e79a1653`** · 관계자 웹 **371**(실서버 계약 포함) / 백엔드 **29**(확정·시작·위치·노선) · 실패 0.
사용자 요청 4건 — **관리자 화면에서 데모가 보이게 한다.**

### 남은 실물

| 무엇 | 어디 |
|---|---|
| 데모 선단 3대(3·4·5호차) · 버스마다 승하차지 10곳 · 학생 10명 · 등원 노선 1개 · 회차 1건 | `backend/src/main/resources/db/migration-local/V13__demo_fleet.sql` |
| 로컬 운행 시뮬레이터 (**`local` 프로파일 전용**) | `backend/src/main/java/src/backend/demo/DemoRunSimulator.java` |
| 고른 버스만 남기는 규칙 (세 화면 공유) | `frontend/apps/academy-web/src/features/map/visibleMarkers.ts` |

**데모 기동** — `docker compose -f docker-compose.yml -f docker-compose.app.yml up -d` 후
**1분** 안에 버스가 이동 (확정 폴링 30초 + 시뮬레이터 시작 지연 15초). 정지는 `app.demo.enabled=false`.

### 관측

| 관측 | 내용 |
|---|---|
| ⚠⚠ **확정 노선의 도로 경로는 시드에 박을 수 없다** | 승하차지 10곳을 지나는 실제 경로는 수작업 생성 불가. 회차를 `idle` 로 두고 `confirm_at` 만 과거로 두면 **30초 확정 폴링이 기동 직후 네이버에서 수신**(실측 1191·554·589 좌표). **시드가 못 하는 일을 배치에 맡기는 형태** |
| ⚠⚠ **시뮬레이터의 대상을 안 좁히면 옆으로 샌다** | 초기 구현이 시작 창에 든 확정 회차를 **전부** 출발시켜, **계약 검사가 "확정 상태" 로 쓰는 1호차 하원 회차(`run 2`)까지 운행 중으로 전이**. 데모를 한 번 띄운 것만으로 검사가 깨지는 상태. `app.demo.bus-ids` 로 범위 축소. **위치 송신은 미축소** — 상태를 안 바꾸므로 유출 부재 |
| 기존 버스·노선·회차는 **미변경** | 계약 검사가 값으로 붙들고 있어 데모 선단을 **새로** 생성 |

---

## 8.38 ⚖ `R24` 결과 — 운행 상세에서 승하차지별 학생 (2026-09-20 **완료**)

**메인 `552fb8aa`** · 고친 화면의 검사 **21**건 통과 · 타입·린트 0건.

운행 상세에서 승하차지를 누르면 **그 자리 학생만** 표시. 다시 누르면 해제.

- **코드를 일부러 망가뜨려 확인** — 승하차지 필터 제거 · 다시 누르기 해제 제거 · 마커 강조 제거, **3건 모두 검출**
- **눈 확인** — 4호차(승하차지 10곳)에서 1번 승하차지 선택 시 그 자리 학생 1명만 표시

⚠ **명단(`§5.4`)에 `stop_id` 가 부재** — 승하차지는 이름 문자열뿐.
지도 마커 → 노선 응답(`§5.19`)의 정차지 이름 → 명단 `stopName` 순으로 연결.
**같은 이름의 승하차지가 둘이면 구별 불가** (그 시점에 계약 변경 필요).

---

## 8.39 ⚖ `R25` 결과 — 승하차지 선택 강조 · 고른 버스를 정중앙 100m (2026-09-20 **완료**)

**메인 `47631a3d`** · 고친 범위 **168**건 통과 · 타입 0건.

### 관측

| 관측 | 내용 |
|---|---|
| ⚠⚠ **네이버 SDK 의 마커 클릭 이벤트가 이 아이콘 형태에서 미호출** | `naver.maps.Event.addListener(marker, "click", …)` 가 실제 마우스·합성 이벤트 **전부** 무반응 (`icon.content` 만 주고 `size` 를 생략한 HTML 아이콘). 마커 HTML 에 `data-marker-id` 를 부착하고 **지도 컨테이너에서 갈무리 단계로 수신**. ⚠ **SDK 쪽으로 되돌리지 말 것** |
| ⚠⚠ **HTML 아이콘은 `size`·`anchor` 가 없으면 좌상단이 좌표에 부착** | 모든 마커가 아이콘 절반만큼 이동해 렌더 — 정중앙에 놓은 버스가 **실측 38px** 이탈. 버스 칩은 번호 길이로 폭이 가변이라 `size` 사전 지정 불가 ⇒ **CSS `translate(-50%,-50%)`**. 클릭 판정도 동반 이동 |
| ⚠ **아이콘 재생성 분기가 버스에만 부착** | `R21-A` 의 잔재. 승하차지를 선택해 `selected` 를 켜도 지도에 변화 부재. 종류를 안 가리되 **내용이 달라졌을 때만** `setIcon` (버스 위치가 2초마다 도착해 매번 재생성하면 깜빡임) |
| **세 화면 검사가 `zoom: 15` 를 값으로 박아 둔 것은 관문** | 축척은 코드만 보고 결정 불가라 "눈으로 확인했는가" 를 묻는 자리. `SELECTED_BUS_MAP_ZOOM`(=16, 축척 바 100m)을 도입해 다음엔 같은 자리에서 미발생 |

- **코드를 일부러 망가뜨려 확인** — 아이콘 갱신을 버스로 되돌리기 · 노선 맞춤 유지 · 가운데 맞춤 제거, **3건 모두 검출**

---

## 8.40 ⚖ `R26` 결과 — 앱 2종 시각 표시 · 알림 태그 · M-06 자동 동기화 (2026-09-21 **완료**)

**메인 `568e9e24`** · 학부모 앱 **141** / 매니저 앱 **143**(백엔드 실기동 · 계약 검사 포함) ·
실패 **0** · 건너뜀 **0**. [`8.39`](#839--r25-결과--승하차지-선택-강조--고른-버스를-정중앙-100m-2026-09-20-완료) 다음 회차.

### 목표 판정

| # | 완료 조건 | 판정 | 근거 |
|:-:|---|:-:|---|
| 1 | `M-06` "복구 시 **자동** 동기화" 이월을 닫는다 | ✅ | 재생 호출부 3곳(수동 버튼 · 다음 쓰기 직전 · 30초 타이머) |
| 2 | 학부모 앱 **알림 수신**을 눈으로 확인 | ✅ | 시뮬레이터에서 4건 표시 — **그 자리에서 결함 1건** |
| 3 | 학부모 앱 **지도에 실제 버스**를 눈으로 확인 | ✅ | `scripts/demo-bus.sh 3` 로 2호차 이동 · 마커 · "가장 가까운 승하차지" 표시 |
| 4 | 코드를 일부러 망가뜨려 새 검사가 무는지 | ✅ | 11종 전부 검출(아래) |

### ⭐⭐ 눈으로만 나온 결함 2건 — **검사는 둘 다 초록이었다**

[`8.39`](#839--r25-결과--승하차지-선택-강조--고른-버스를-정중앙-100m-2026-09-20-완료) 의
*"출처가 눈이면 판정도 눈"* 이 **7회차 연속** 재현.

| 결함 | 증상 · 원인 |
|---|---|
| ⚠⚠ **모든 시각이 9시간 이르게** | 19:40 출발 회차가 "10:40 출발", 지도의 위치 시각이 한국시간 20:03 에 "11:03:23 기준". **`DateTime.parse` 는 오프셋이 붙은 문자열을 언제나 UTC `DateTime` 으로 돌려주는데**(`…Z` 든 `…+09:00` 이든) 화면이 `hour`·`minute` 과 `DateFormat` 으로 그 값을 그대로 벽시계로 읽었다. 앱 2종 **12곳** · 관계자 웹은 `toLocaleTimeString` 이라 무관 |
| ⚠⚠ **알림 태그가 전부 초록 "승차 완료"** | 학부모 앱이 `NotificationCard` 에 태그를 **한 번도 넘기지 않아** 기본값(`BaraedaStatus.boarded`)이 붙었다. 하차·지연·노선 변경까지 "승차 완료" 였고, **`no_show`(미승차)는 사고인데 초록 '승차 완료'** 로 보였다 — `FEATURE_SPEC C-02`(반드시 구분) · C-09(레드) 위반 |

- **왜 검사가 못 봤나 — 시험 데이터가 결함을 가렸다.** 시각 쪽은 시험이 `DateTime(2026,9,12,8)`
  (로컬)을 넣어 변환 여부가 드러나지 않았고, 태그 쪽은 **그 위젯에 검사가 0건**이었다.
  [`8.39`](#839--r25-결과--승하차지-선택-강조--고른-버스를-정중앙-100m-2026-09-20-완료) 의
  *"맨 숫자 `busNo: '1'`"* 과 같은 형태다
- **기댓값을 `DateTime.utc(...).toLocal()` 로 만든 검사**를 3곳에 뒀다(학부모 회차 카드 ·
  매니저 홈 · 지도 위치 타일). ⚠ **표준시가 UTC 인 기계에서는 무해하게 통과한다** —
  시험기의 표준시를 코드에서 바꿀 수단이 부재하다. 규칙은 `CONVENTIONS_FLUTTER §9` 에 등재
- 모르는 알림 종류는 **중립(스톤 '안내')** 으로 떨어뜨린다 — `§9.7` 은 앞으로도 늘고,
  기본값이 초록이면 새 종류마다 같은 오표시가 조용히 생긴다

### `M-06` — 재생을 부르는 곳이 셋이 됐다

| 부르는 곳 | 맡는 상황 |
|---|---|
| 큐 화면의 재시도 버튼 | 사용자가 직접 |
| `sendOrQueue` (쓰기 한 건이 나가기 **직전**) | 운행 중 — 복구의 가장 이른 신호가 "쓰기 한 건이 성공" |
| `OfflineQueueAutoSync` 30초 타이머(앱 트리 최상단) | 더 이상 처리할 것이 없는 시점(운행 종료 후) |

- ⚠ **순서가 이 구현의 핵심이다** — 새 요청을 큐보다 먼저 보내면 오프라인에서 쌓인 옛 처리가
  복구 후의 새 처리를 **덮어쓴다**(오프라인 '탑승' → 복구 후 '되돌리기' → 재생이 다시 '탑승')
- ⚠ **두절이 이어지면 재생은 첫 실패에서 멈춘다** — 남은 행마다 타임아웃을 되풀이할 이유가 없고,
  중간 건만 성공하면 **큐 안의 순서가 뒤집힌다**
- ⚠ **로그인 전에는 자동 재생하지 않는다** — 토큰이 없어 401 이 오고, 재생 규칙상 4xx 는 큐에서
  **영구 제거**라 쌓아 둔 승하차 처리가 통째로 사라진다
- **스모크 시험에 "앱 트리에 실제로 걸려 있는가" 단언**을 더했다 — 위젯을 만들어 두고 아무 데도
  끼우지 않으면 [`8.39`](#839--r25-결과--승하차지-선택-강조--고른-버스를-정중앙-100m-2026-09-20-완료)
  직전 회차의 "갈 길이 없는 화면" 과 같은 구멍이 된다

### 관측 — 시뮬레이터를 조작하는 수단

| 수단 | 결과 |
|---|---|
| `orca computer click --app Simulator --window-id <id> --x --y` | **된다.** 창 좌표 = `28 + 논리좌표 × 1.079`(iPhone 17 Pro · 456×972 창). ⚠ 창이 앞에 없으면 `no on-screen window` — `--restore-window` 를 붙인다 |
| `orca computer scroll` · `drag` | **스크롤이 안 된다** — 합성 휠 이벤트가 시뮬레이터에 닿지 않고, 드래그는 끝점 탭으로 처리된다 |
| `osascript` 로 **아래 화살표 키 반복** | ✅ **스크롤은 이 방법으로 한다.** Flutter 스크롤 뷰가 하드웨어 키보드 입력을 받는다 |
| `xcrun simctl terminate` + `launch` | 화면 이동 상태를 초기로 되돌릴 때 |

⚠ **클릭이 한동안 먹지 않는 구간이 있었다**(같은 좌표가 앞서는 들었다) — 이 회차에서는
원인을 특정하지 못했다. **클릭마다 스크린샷으로 상태를 확인**하고 좌표를 다시 계산한다.

### 이월 — ✅ 같은 날 해소

| # | 항목 | 처리 |
|:-:|---|---|
| 1 | 로컬 시드의 `boarding` 알림 본문이 *"곧 탑승합니다"* — `§9.7` 은 `boarding` 을 **탑승 완료** 로 정의 | ✅ 닫음. ⚠ **한 행이 아니라 10행 전부였다**(아래) |

#### 시드 알림 10행을 **실제 제조기 문구**로 맞췄다 (`V2__seed_data.sql`)

이월은 행 하나를 지목했으나 같은 형태를 전수로 세니 **10행 중 8행**이 어긋나 있었다
(`phase-goal-loop §6.4` — *"이월이 지목한 파일이 아니라 같은 형태 전체를 세라"*).

| 어긋남 | 고친 값(근거 = `notification/domain/impl/*Composer`) |
|---|---|
| `boarding` 이 *"곧 탑승합니다"* | `승하차 안내` / `… 학생이 버스에 탑승했습니다.` — `§9.7` 의 `boarding` 은 **탑승 완료** |
| `no_show` 가 ***"노쇼 안내"*** | `미승차 안내` / `… 학생이 아직 버스에 탑승하지 않았습니다. 확인해 주세요.` — 사양 용어는 **미승차**(C-02) |
| `arrive` 가 ***"정류장"*** + 자녀 이름 부재 | `곧 도착합니다` / `… 학생이 탄 버스가 승하차지 근처에 도착했습니다.` — **공용 정류장 개념 부재**(C-12) · 자녀 이름은 ATT-03 |
| `signup_decided` 가 *"가입 심사가 진행 중입니다"* + **승인 대기 계정** 수신 | `가입 거절 안내` / `가입이 거절되었습니다. 사유: 재학증명서 미제출`, 수신자를 **거절된 계정(11)** 으로 — `decided` 는 **결정된** 것 |
| `route_changed` 를 **학부모**가 수신 | 수신자를 **기사(계정 13)** 로 · `student_id` 제거 — `§9.7` 수신자는 **기사·동승자** |
| `emergency`·`delay`·`run_started`·`alighting` 제목·본문이 제조기와 불일치 | 각 `*Composer` 의 상수 그대로. `run_started` 본문은 `§9.7` 이 문면을 못박고 있다 |

- ⚠ **보존한 것** — `push_state`(pending 1 · failed 1 · sent 8) · 행 id · 재시도 횟수.
  `NotificationOutboxWorkerTest` 가 *"시드의 유일한 미발송 행 = 4번"* · *"포기 행 = 9번"* ·
  *"발송이 끝난 행 8건"* 을 값으로 붙들고 있다
- 학부모 앱 단위 시험의 시험 데이터도 같은 문구로 정정 — **시험 데이터가 틀린 문구를 고정하면
  그 문구가 정답처럼 굳는다**(이 회차 본문의 `busNo: '1'` 과 같은 형태)
- 검증 — 백엔드 알림 검사 **127**(실패·건너뜀 0) · 학부모 **141** / 매니저 **143** / 관계자 웹 알림 **5**,
  **전부 새 시드로 재구성한 DB 에서**

---

## 8.41 ⚖ `R27` 결과 — 관계자 웹 5건 (2026-09-22 **완료** · 오케스트레이터 3갈래)

**메인 `a4a24cce`** · 백엔드 **1,374** / 관계자 웹 **392**(실서버 계약 포함) / 학부모 앱 **141** /
매니저 앱 **143** · 실패 **0** · 건너뜀 **0**. 사용자 지시 5건 — **전부 화면을 보고 지적한 것**.

### 목표 판정

| # | 지시 | 판정 | 처리 |
|:-:|---|:-:|---|
| 1 | 금일 운행 상세 "현재 위치" 카드에 도착 예정 시각 | ✅ | 표의 도착 컬럼과 같은 `estArrivalTime` — 두 자리가 어긋날 수 없다 |
| 2 | 명단을 승하차지별로 묶어 접고 펴기 · 길면 스크롤 | ✅ | 공용 `RosterTable` 에 선택 인자 `groupBy`(안 주면 예전과 같은 평평한 표) · 스크롤 상자 + 머리줄 고정 |
| 3 | 가입 승인은 승인만 · 자녀 연결은 학생 코드 → 학부모 입력 | ✅ | **`Ruling 324`** — 사양 변경. 아래 |
| 4 | 학생 관리에서 학부모 연결 여부 | ✅ | `§5.11` 응답에 `guardian_count` 신설 · 표에 "연결 N명 / 미연결" |
| 5 | 고정 노선 편성 화면에 경로·정차지, 수정 후 갱신 | ✅ | **`GET /staff/routes/{id}/path` 신설**(`§5.9`) · 지도에 도로 경로 + 순번 붙은 정차지 마커 |

### ⭐ `Ruling 324` — 가입 승인과 자녀 연결을 갈랐다 (2026-09-22 사용자 확정)

> *"가입 승인 절차는 학생·학부모가 요청하고 학원은 간단하게 승인만 진행. 이후 학부모-학생 연결은
> 각자의 앱에서 학생이 일회성 연결코드를 생성하고 학부모가 입력해서 연결하는 방식으로 진행."*

| 무엇 | 전 | 후 |
|---|---|---|
| 학부모 가입 수락(`§5.2`) | `link.student_ids` **필수**(학원이 자녀를 지정) | **승인만** — 연결은 앱이 맡는다 |
| 연결 코드 생성(`§3.3`) | **대기 중인 연결 요청이 선행 조건** | 학생이 **언제든** 생성 |
| 연결 요청(`§3.2`) | 학부모 → 학생 요청이 흐름의 시작 | **폐지** — 테이블·엔티티·에러코드·알림종류까지 제거 |

- **학생·기사·동승자 수락은 그대로 연결이 필수다** — 계정↔학원 명부 레코드 연결은 학원만 할 수 있다(AUTH-11)
- **학부모 계정이 자녀 0명으로 `active` 가 되는 상태가 정상이 됐다**
- 실서버 한 바퀴 확인 — 학생이 요청 없이 코드 생성 → 다른 학부모가 입력해 연결(`student_id=4`) →
  **같은 코드 재사용은 `LINK_CODE_INVALID`**(1회성 유지)

### ⚠⚠ 갈래끼리는 안 보이는 것이 병합에서만 드러났다 — 7건 실패

각 워크트리에서는 **셋 다 초록**이었다. 병합 트리 전체 실행에서 7건이 깨졌고 **5건은 R27 이전부터**였다.

| 갈래 | 무엇 |
|---|---|
| **R27 이 만든 2건** | 프로덕션 핸들러 수 — C 가 `link-requests` 를 빼 105 로 적었는데 **B 가 새 엔드포인트를 더해 106**. 학원 격리 전수 검사·계정 상태 게이트 목록에도 그 경로가 빠져 있었다 |
| **이전부터 5건** | 차량 시험 3건이 3·4·5호차로 등록 → **R23 데모 선단(V13)과 `409 DUPLICATE_BUS`** · `DemoRunSimulator#tick` 이 분산 락 면제 목록에도, 테스트 지연 등재에도 부재 |

- **5건은 R23 이후 전체 실행이 한 번도 없었다는 뜻이다** — 병합 전 커밋(`1d76fa3b`)에서 같은 검사를
  돌려 갈랐다. `parallel-agents §18`(전체를 세는 시험)·`§0.3`(병합 후에만 드러나는 것)이 그대로 재현
- 노선 목록 페이징 시험도 같은 형태였다(시드 편성 수 2를 상수로 박음 → V13 이 5로) — **총계·마지막
  쪽을 실측 계수에서 유도**하도록 고쳤다. 시드가 늘 때마다 깨지는 자리를 없앤다

### 관측

| 관측 | 내용 |
|---|---|
| **갈래가 스스로 신고한 생략이 값을 했다** | B 가 *"정차지 마커에 순번 배지를 생략했다 — 조율자 의도와 다를 수 있다"* 를 보고서 2항에 적었다. 실제로 지도를 보니 정차지 2곳이 **한 점으로 겹쳐** 목록과 대응시킬 수단이 없었다 ⇒ 조율자가 순번 칩을 마저 붙였다 |
| **자바독 안에 떠돌던 충돌 마커** | `EndpointErrorResponses.java` 에 짝 없는 `<<<<<<< HEAD` 한 줄. 주석 안이라 컴파일·검사 어디에도 안 걸렸다(C 가 발견해 신고) |
| 병합 충돌 **0건** | 세 갈래가 `docs/API_SPEC.md` 를 함께 고쳤지만 절이 멀어 텍스트 병합이 그대로 됐다 |

---

## 8.42 ⚖ `R28` 결과 — 고정 노선 편성: 주소로 검색해 지도에서 지점을 잡는다 (2026-09-22 **완료**)

**메인 `d9a99c1d`** · 백엔드 **1,383** / 관계자 웹 **401**(실서버 계약 포함) · 실패 **0** · 건너뜀 **0**.

지금까지 정차지를 더하려면 **`stop_id` 를 직접 적어야** 했다(개발자용 입력). 사용자 지시로
**도로명 주소 검색 → 지도에서 위치 확인 → 필요시 수정 → 반영** 네 단계로 바꿨다.

### 왜 단계를 갈랐나

**지오코딩이 돌려주는 점과 버스가 실제로 서는 자리가 다르다.** 주소는 대개 건물 중심점으로
풀리는데, 버스는 그 블록 모퉁이나 도로가에 선다. 검색 즉시 승하차지를 만들면 **그 차이를 메울
기회 없이** 잘못 찍힌 지점이 남고, 다음 편성에서 다시 후보로 뜬다.

| 단계 | 호출 | 성질 |
|---|---|---|
| ① 검색 | `GET /staff/stops/search?address=` | **조회 전용** — 승하차지를 만들지 않는다 |
| ② 확인 | (호출 없음) | 임시 핀을 **그 지점 배율(zoom 18)** 로 확대해 보여준다 |
| ③ 수정 | (호출 없음) | 지도를 눌러 핀을 옮긴다. **검색 위치에서 몇 m 옮겼는지** 표시 |
| ④ 반영 | `POST /staff/routes/{id}/stops` | 이때 비로소 승하차지가 생기고 노선 끝에 붙는다 |

- **중복 방지** — 50m 안에 기존 승하차지가 있으면 이름·거리를 알린다. 반영해도 새로 만들지 않고
  **그 승하차지에 붙는다**(근접 병합 STU-05 재사용). ⚠ 거리는 **옮긴 지점 기준으로 다시 잰다** —
  서버가 준 값은 검색 지점 기준이라, 중복을 피하려고 옮긴 뒤에도 경고가 남는다
- **좌표를 서버가 다시 지오코딩하지 않는다** — 그러면 ③에서 관계자가 옮긴 지점이 사라진다

### ⚠ 화면을 보고서야 드러난 것 2건 — 검사는 둘 다 초록이었다

| 결함 | 원인 |
|---|---|
| **지도가 검색 지점으로 확대되지 않는다** | `NaverMapSurface` 가 노선 전체를 담는 배율(3km)을 화면이 넘긴 카메라보다 우선했다(R25 가 "고른 버스" 에만 예외를 뒀다). 3km 축척에서는 블록 모퉁이를 찍을 수 없어 **기능이 성립하지 않는다** ⇒ `fitToContent` 를 두어 화면이 좌표를 못박을 수 있게 |
| **옮겨도 "이미 있다" 거리가 그대로** | 서버 `distance_m` 은 검색 지점 기준. 중복을 피하려고 옮긴 뒤에도 같은 경고가 남아 판단을 흐린다 |

### ⚠ Ruling 179 의 한 자리 예외 — 잠금 없는 근접 조회

`StopRepository.findNearby`(잠금 없음)를 새로 뒀다. Ruling 179 는 *"잠금 없는 후보 조회를 여기 두면
호출부가 잠금을 빠뜨려도 컴파일된다"* 며 그것을 금지했는데, **검색은 아무것도 만들지 않아 "조회 →
판정 → 생성" 임계 구역 자체가 없다.** 그 경로에 학원 잠금을 걸면 화면 조회가 그 학원의 주소
등록·강제 추가를 기다리게 된다.

⇒ **쓰기 경로가 실수로 그쪽을 부르지 않도록 `StopMatcherUsesLockingLookupTest` 가 호출 대상을
소스에서 고정한다** — 동시 요청에서만 드러나는 결함이라 평소 검사로는 못 잡는다.

### 실측

- 실 지오코더로 확인 — `서울특별시 중구 세종대로 110` → 서울시청 좌표 · **36m 거리의 시드 승하차지를
  경고**로 표시
- 화면 확인 — 검색(30m 축척) → 지도 클릭으로 **약 60m 이동** 표시 → 표시명 수정 → 반영 후 목록 3번에 등재
- 코드를 일부러 망가뜨려 4종 전부 검출(검색이 즉시 반영 · 지도 클릭 무시 · 중복 안내 제거 · 근접 병합 해제)

---

## 8.43 ⚖ `R29` 결과 — 고정 노선 편성 화면 다듬기 6건 (2026-09-22 **완료**)

**메인 `61040855`** · 백엔드 **1,385** / 관계자 웹 **409** · 실패 **0** · 건너뜀 **0**.
전부 **사용자가 화면을 보고 지적한 것**.

| # | 지시 | 처리 |
|:-:|---|---|
| 1 | 지도 세로 크기 통일 | `MAP_SURFACE_HEIGHT_PX`(480) **한 곳**으로. 화면마다 480·320 으로 갈려 있었다 |
| 2 | 승하차지 순서를 드래그로 | HTML5 드래그만 사용(라이브러리 미추가). **위·아래 버튼은 유지** — 키보드만 쓰는 사용자는 끌 수 없다 |
| 3 | 목록을 좌측에 모으고 넘치면 스크롤 | 최대 너비 560px · `max-height: min(48vh, 520px)` + 세로 스크롤 |
| 4 | "정차지 ID 로 추가" 제거 | 주소 검색(`R28`)이 대체 |
| 5 | 경유지에 **설 자리** 지정 | `§5.15` 요청에 선택 필드 `seq`. 비우면 맨 뒤(이전 동작) · 범위 밖은 `422` |
| 6 | 미리보기를 **전·후 두 지도**로 | 좌 = 기존 경로(confirmed 색) · 우 = 변경된 경로(route 색). 좁으면 위아래로 쌓인다 |

### 관측

| 관측 | 내용 |
|---|---|
| **`FixedStop.seq` 는 원래부터 "관리자가 지정한 최종 순번"이었다** | 엔진이 그 자리를 비워 두도록 설계돼 있었는데(ARCHITECTURE §8.2) **호출부가 늘 `정차지 수 + 1` 을 박아 넣어** 그 자리가 닫혀 있었다. 5번은 기능을 새로 만든 것이 아니라 **이미 있던 자리를 연 것** |
| **지도 높이 통일은 상수 하나로는 안 지켜진다** | styled 파일이 숫자를 다시 박으면 아무 검사도 안 깨졌다 ⇒ `mapSurfaceSize.test.ts` 가 `.styled.ts` 전체를 훑어 `320|480px` 리터럴을 금지한다 |
| ⚠ **styled 파일은 지도 배럴이 아니라 상수 파일을 직접 가져온다** | 화면 시험이 `@/features/map` 을 통째로 목으로 바꾸는 관례가 있어(jsdom 에 SDK 부재), 배럴로 가져오면 목마다 상수를 다시 내보내야 하고 하나만 빠져도 시험이 죽는다 |
| **미리보기 예상 시각이 ISO 문자열 그대로** | 화면을 보고서야 나왔다(`2026-09-22T07:24:12.642407Z`). `R21-B` 가 금일 운행 상세에서 고친 것과 **같은 형태가 다른 화면에 남아 있던 것** |

- 코드를 일부러 망가뜨려 **5종 전부 검출**(드롭 무시 · 높이 리터럴 복귀 · `seq` 누락 · 변경 후 경로 제거 · 백엔드가 지정 순번 무시)
- 화면 확인 — 목록이 좌측 560px 안에 모이고 "정차지 ID 로 추가" 가 사라진 것 · 전후 지도가 **서로 다른 경로**(빨강·파랑)로 그려지고 정차 2→3곳 · 소요 30→49분(+19분)이 함께 뜨는 것

---

## 8.44 ⚖ `R30` 결과 — 데모 규모 확장 · 정차지 핀 · 고정 노선 편성 화면 개편 (2026-09-23 **완료**)

**메인 `07ae5911`** · 사용자 지시 3묶음 — 데모 데이터 3건 · 정차지 아이콘 · 편성 화면 11건.

### 데모 데이터 · 정차지 아이콘

| 무엇 | 처리 |
|---|---|
| 학원 10곳 · 버스 30대 · 버스당 학생 20명 · 노선당 승하차지 15곳 | `db/migration-demo/V14__demo_scale.sql` — **local 실행에서만** 적재(테스트 DB 는 `build.gradle` 이 뺀다. 넣었더니 전 학원을 세는 시험 4건이 데모 행을 셌다) |
| 가입·구간 변경 승인 대기가 학원마다 여러 건 | 가입 6건(관계자 5 · 메인 관리자 1) · 구간 변경 6건(하원 회차, **기동 후 약 29분이면 자동 거절**) |
| ⚠ **운영 결함** — 동시 도래 60건 중 53건이 직선 근사로 확정 | 확정 워커 풀 8 > 지도 격벽 4(대기 0). 풀 기본값을 격벽 설정에서 끌어온다 → 0건. `ARCHITECTURE §9.4` 가 이미 "풀 = 레이트리밋" 이었다 |
| 정차지 마커 → 끝이 좌표를 가리키는 물방울 핀 + 순번 | 관계자 웹 · 매니저 앱 같은 윤곽(22×29). 학부모 앱은 정차지를 그리는 화면이 부재 |
| ⚠ 매니저 앱 노선 지도에 **아무도 못 들어갔다** | 버튼이 명단 화면(동승자만 진입)에 있고 기사에게만 보였다 — 운행 모드에 버튼을 열었다 |

### 편성 화면 11건 — `Ruling 325`

| # | 지시 | 처리 |
|:-:|---|---|
| 1 | 정차지 목록은 좌측 특정 공간에만 | 왼쪽 380px 칸, 지도 높이 안에서 스크롤 |
| 2 | 주소를 다 치지 않아도 비슷한 주소 | **`GET /staff/stops/suggest` 신설** — 장소 검색(NAVER API HUB 지역 검색, 최대 5건) + 주소 검색(지오코딩) 후보, 후보마다 50m 안 기존 승하차지. ⚠ 도로명만(`목동서로`)은 둘 다 0건 |
| 3 | 마커를 마우스로 끌어 자리 지정 | 지도 영역에서 직접 옮긴다(SDK 마커 이벤트가 이 아이콘 형태에서 안 불린다 — R23). 잡은 자리만큼 빼서 핀 끝이 튀지 않는다. 지도 클릭으로 옮기던 방식은 제거 |
| 4 | 출발·도착 위경도 입력 제거 | **`POST /staff/routes/{id}/optimize` 기준점 선택화** — 둘 다 비우면 Ruling 190 규칙 |
| 5·6 | 경유지 추가가 아닌 승하차지 CRUD · 회차 선택 제거 | 회차 경유 지점(`A-15`) 칸을 이 화면에서 뺐다(백엔드 API 는 유지). 승하차지 추가·수정·삭제로 대체 |
| 7 | 별도 저장 버튼 | **`PUT /staff/routes/{id}/stops` 신설** — 추가·수정·삭제·순서를 한 트랜잭션으로 |
| 8 | 시점·종점 표기 | 다른 지도와 같은 출발·도착 표시. 정차지 쪽 끝은 저장 전 목록에서 가져온다 |
| 9·10 | 좌표 입력 삭제 · 주소 검색은 CRUD 에만 | 좌표 입력칸 0개 · 검색은 추가·수정 양식 안에만 |
| 11 | 지도 제외 디자인 개선 | 목록 칸(번호 원 · 변경 배지 · 줄 단위 버튼) · 저장 줄(변경 건수 · 되돌리기) · 빈 상태 |

### ⭐ `Ruling 325` — 고정 노선 편성의 저장·최적화·검색 계약 (2026-09-23 사용자 지시)

| 무엇 | 전 | 후 |
|---|---|---|
| 승하차지 반영 | 추가는 `POST .../stops` 즉시 · 순서는 `PATCH` 따로 | **`PUT .../stops` 한 번** — 고치기 전에 전부 검증해 하나라도 거부되면 아무것도 안 바뀐다 |
| 승하차지 수정 | 수단 부재 | 같은 요청 안에서 `stop_id` + 새 이름·좌표 — **승하차지 자체를 옮긴다**(노선별 사본을 만들면 학생 주소가 옛 행을 가리켜 확정 배치가 그 학생을 못 태운다). 다른 노선에도 함께 반영된다 |
| 목록에서 뺀 승하차지 | — | 노선에서만 빠지고 행은 남는다(학생 주소가 가리킨다) |
| 최적화 기준점 | **필수**(Ruling 184 — 학원에 좌표가 없던 시절) | **선택** — 둘 다 비우면 확정 배치와 같은 규칙(Ruling 190), 하나만 주면 `422`, 학원 좌표가 없으면 `422 ACADEMY_COORDINATES_MISSING`. 규칙이 한 줄로 공개돼 있어 요청·편성 상태만으로 기준점을 재현할 수 있으므로 Ruling 184 가 막은 "보이지 않는 정책" 이 아니다 |
| 주소 자동완성 | — | `GET /staff/stops/suggest?query=` — 0건은 빈 목록(검색의 `422` 와 다르다), 공급자 장애는 `503` |
| 웹의 회차 경유 지점(`A-15`) | 편성 화면에서 회차를 골라 지정 | **웹 화면에서 제거** — 편성 화면은 한 노선으로 들어와 작업하는 곳이다. API(`§5.15`)·백엔드 검사는 그대로 |

### 관측

| 관측 | 내용 |
|---|---|
| **테스트가 다른 규칙에 가려져 결함을 못 잡았다** | "저장 안 한 변경이 있으면 최적화를 막는다" 를 정차지를 **지워서** 만들었더니 "2곳 미만이면 막는다" 가 먼저 막아, 조건을 없앤 코드도 통과했다(일부러 심어 확인). 순서만 바꿔 변경을 만들도록 고쳤다 |
| **셸 인자가 한 덩어리로 넘어가 401 로 보였다** | zsh 는 변수 안의 공백으로 인자를 나누지 않는다 — `--dart-define` 두 개가 URL 에 붙어 `/api/v1%20--dart-define=...` 로 요청됐다. 프록시 접근 로그의 경로로 찾았다 |
| **장소 검색이 운영에서 전부 실패했는데 시험은 초록이었다** | NAVER API HUB 가 JSON 을 **`text/plain`** 으로 보내고, 앱의 JSON 설정(snake_case)이 `roadAddress` 를 비웠다. 시험이 `WebClient.create()`·`application/json` 으로 돌아 둘 다 가려졌다 — 앱의 `WebClient` 빈과 실측 머리로 바꿔 재현한 뒤 고쳤다. 실패 로그에 원인 예외를 남기지 않아 원인을 찾는 데 한 바퀴를 더 돌았다 |
| ⚠ 검색 API 는 **NAVER API HUB 로 이관**됐다 | 개발자센터(`openapi.naver.com` · `X-Naver-Client-Id`)는 2026-07-31 신규 발급 중단. HUB 는 `naverapihub.apigw.ntruss.com` · NCP 헤더(`x-ncp-apigw-api-key-id`) — 옛 헤더는 `401`(실측). 키는 `.env` 의 `NAVER_SEARCH_CLIENT_ID/SECRET` |

### 후속 2건 (같은 날)

| 지시 | 처리 |
|---|---|
| 지도 세로를 화면 절반 이상, 모든 지도 | 높이 상수를 CSS 길이 `max(480px, 60vh)` 로 — 관제·운행 관리·금일 운행·구간 변경 승인·편성 지도와 옆 목록이 함께 따른다 |
| 최적화에서 특정 순서·시점·종점 고정 | `POST .../optimize` 에 `fixed_stop_ids` — 줄마다 자물쇠. 고정은 저장 대상이 아니라 최적화 조건이라 변경 건수에 안 센다(화면을 떠나면 풀린다). 첫 줄(등원)·마지막 줄(하원)에 시점·종점 표시 — 학원 쪽 끝은 늘 고정 |
| 로그아웃 · 페이지별 뒤로가기(관계자 웹) | 두 셸의 머리에 둘 다. 뒤로는 **이 앱 안에서 지나온 화면**으로만(첫 화면에서 로그인 화면으로 나가지 않게), 상세에 바로 들어왔으면 목록으로. ⚠ 로그아웃 요청이 실패하면 토큰만 지워지고 화면은 로그인된 채 남던 결함을 함께 고쳤다. 저장 안 한 편집이 있으면 뒤로·사이드바·로그아웃이 한 번 묻는다(`beforeunload` 는 앱 안 이동에 안 불린다). 앱 2종의 로그아웃은 미착수(매니저 앱 부재 · 학부모 앱은 승인 대기 화면에만) |

### ⭐ `Ruling 326` — 학생 관리: 좌석·호차·승하차지 제거, 보호자 연락처는 관계자가 고친다 (2026-09-23 사용자 지시)

| 무엇 | 전 | 후 |
|---|---|---|
| 좌석(`seat_no`) | 등록·수정 칸 · 컬럼 | **제거** — 저장만 하고 어디서도 읽지 않았다 |
| 목록 호차·승하차지(`bus_no` · `stop_name`) | 응답 필드 · 표 열 | **제거** — 한 번도 채워진 적 없이 늘 `null`. 학생 승하차지는 요일·등하원마다 달라 학생 하나에 한 값이 성립하지 않고, 노선은 학부모가 등록한 요일별 주소로 자동 편성된다 |
| 보호자 연락처 | 계정 연락처(`account.phone`) 조회, 관계자 수정 불가 | **`guardian.phone`**(학원 관리 연락처) — 상세에 보호자 전부, `PATCH guardians[]` 로 수정. 목록·매니저 명단·관리자 명단이 모두 이 값을 읽는다 |
| 계정 연락처 | — | **관계자가 고칠 수 없다** — 복구 번호를 바꾸면 계정을 가로챌 수 있다. 가입 시점에 두 값은 같다(2026-09-23 604명 전원 일치) |

- 운행 리포트 데모 10건(목동 학원 · 어제 하원 3회차 + 오늘) · 앱 2종 로그아웃(학부모 설정 · 매니저 홈)도 같은 날

---

## 8.45 ⚖ 백엔드 전체 검사 — 사양 판정 9건 `Ruling 327`~`335` (2026-09-25)

**2026-09-25 백엔드 전체 검사**(Orca 작업 창 11개 + 재판정 3개, 기준 커밋 `c8c3c6ec`)의 고유 지적 165건 중, 사양과 코드 중 어느 쪽을 고칠지 결정이 필요한 9건을 판정. 사용자 지시 — *"정할 9건은 PRD, API 명세서 등을 기준으로 적절하게 정해줘"*.
**추적 원장은 `backend/report/2026-09-25-백엔드-전체-검사.md`**(git 추적 밖 · `BR-NNN` 상태 칸). 이 절은 판정만 기록하고 수정 진행은 원장 상태 칸이 추적.

| Ruling | 원장 | 판정 | 근거 문서 |
|---|---|---|---|
| 327 | BR-002 | **코드를 사양에 맞춤** — 등원 종료 = 학원 도착 처리 | C-15 · RUN-05 · `API_SPEC §4.5`·`§4.10` · `UF-D-04` 네 곳 일치 |
| 328 | BR-004 | **차단 해제 = 차단 직전 상태로 복귀** — 사양(`UF-O-03`·`§6.12`) 개정 | `FEATURE_SPEC §3.6` · C-11 · `ARCHITECTURE §5.1` |
| 329 | BR-005 | **SMS 연동 전까지 전화번호 복구 경로 차단 + 관계자 경유 초기화 신설** | `PRD` F-05(문자 연동 = 2단계) · C-11 "또는 관리자 경유" · `§6.7` 선례 |
| 330 | BR-018 | **코드를 사양에 맞춤** — 확정 배치에 동승자 자동 배정 연결 | `PRD §5` 인력 배차 · MGR-05 · `ARCHITECTURE §8.2` ⑤ |
| 331 | BR-040 | **실제 푸시 발송 구현(FCM)** + 로그아웃 기기 해지 수단 보강 | `PRD` 목표 "5초 이내 푸시" · NTF-12(P0) · `§2.11` |
| 332 | BR-054 | **응답의 모든 식별자는 JSON 문자열** — `Ruling 275` 미결 해소, `Ruling 171` 유지 | `§1.1` · 절별 표 20곳 문자열 · 0곳 숫자 |
| 333 | BR-060 | **L3 감사 = 조회 · 수정 · 삭제** — `FEATURE_SPEC §6.3` 문면을 SYS-01 에 맞춤 | SYS-01 · `API_SPEC §6.13` `action` 3종 · `AuditLog` 정의 |
| 334 | BR-068 · BR-006 | **③구간 미등원 → 기존 `route_changed` 알림 + `rider_changed` 방송**, ③은 `waiting` 학생에만 | `§3.6` ③ · RUN-07 · `§7.1` |
| 335 | BR-111 | **C-08 우선** — 학생 채널 `run_started`·`run_ended` 에서 인원수 제외 | C-08 · `§1.12` · `IMPLEMENTATION_PLAN:1041` 선례 |

### ⭐ `Ruling 327` — 등원 회차의 최종 지점은 학원이다 (BR-002)

| 무엇 | 전(코드) | 후 |
|---|---|---|
| 등원 확정 노선의 정차 목록 | 학생 승하차지 · 경유 지점만 — 학원 항목 부재 | **마지막 순번에 도착지(학원) 항목 1개** — `run_stop.destination = true`(`stop_id`·`waypoint_id` 둘 다 NULL). 좌표·이름은 학원 |
| 마지막 승차지 도착 처리 | `is_final=true` → 전원 하차 · 운행 종료 | **일반 도착**(포인터 전진). 그 승차지 학생의 승차 처리가 계속 가능 |
| 학원 항목 도착 처리 | 수단 부재 | `§4.5` 로 처리 → `is_final=true` → 즉시 `finished` + 전원 자동 `alighted` (C-07 · C-15) |
| `§4.2 stops[]` · `§4.3 stops[]` | 학원 부재 | 마지막 항목에 `is_destination: true`(명단은 `students[]` 빈 배열 · 노선은 `student_count` 0). ⚠ **매니저 앱의 도착 버튼은 `§4.2` 명단에서 나온다**(`drive_mode_providers.dart` `nextUnarrivedStop`) — `§4.3` 에만 넣으면 등원 운행이 끝나지 않는다(2026-09-25 프론트 영향 조사에서 정정) |
| 하원 회차 | — | **변경 부재** — 학원은 출발지라 항목을 두지 않고 최종 지점은 마지막 하차지 |

- `{stopId}` 는 `run_stop.id`(`ERD` `run_stop.id` 설명과 같음) — 경유 지점 도착 처리 불가(BR 원장 `W01-08`)와 함께 정합
- 근거 — 사양 네 곳이 같은 문장(학원 도착 처리 = 종료). 사양을 코드에 맞추면 **학원 도착 전 하차 알림 · 마지막 승차지 학생 승차 불가 · 학원까지 구간 위치 추적 중단**이 사양으로 굳음

### ⭐ `Ruling 328` — 차단 해제는 로그인 차단만 푼다 (BR-004)

| 무엇 | 전 | 후 |
|---|---|---|
| 해제 후 상태 | 무조건 `active` | **차단 직전 상태**(`active`·`pending`·`rejected`) |
| 보관 | — | `account.status_before_block` — `blocked` 전이 시 직전 값 저장, 해제 시 복원 후 NULL |
| `§6.12` 응답 `account_status` | `active` 고정 | 복원된 상태 |
| `§6.10` 목록 | 역할·직전 상태 부재 | `role` · `status_before_block` 추가 — 메인 관리자가 승인 대기 계정의 해제를 구별 |

- 근거 — 차단 사유는 로그인 실패 5회(C-11)이고 가입 승인과 무관. `§3.6` 이 `pending`·`rejected` 의 접근 범위를 따로 정하며, 무조건 `active` 면 **관계자 역할 계정이 가입 승인 없이 학원 전체 개인정보 권한**을 얻음. `UF-O-03` 의 "blocked → active" 문면이 원인이라 함께 정정

### ⭐ `Ruling 329` — 비밀번호·아이디 복구: SMS 연동 전까지 관계자 경유 (BR-005)

| 무엇 | 전 | 후 |
|---|---|---|
| `POST /auth/recover` | 코드를 저장만 하고 발송 부재 · 코드를 맞히면 임시 비밀번호·아이디를 응답 본문으로 반환 | **SMS 발송 수단이 설정되기 전에는 `503 RECOVERY_UNAVAILABLE`** — 코드 발급·대조·초기화 전부 미수행 |
| 관리자 경유 복구(학부모·학생·매니저) | 경로 부재 | **`POST /staff/accounts/{accountId}/password-reset` 신설**(AUTH-08) — 같은 학원의 학부모·학생·매니저 계정만. 응답에 `login_id` · 임시 비밀번호 1회. refresh 토큰 전량 무효화 · 감사 기록 |
| 관리자 경유 복구(관계자) | `§6.7` | 변경 부재 |
| SMS 연동 후 재개 조건 | — | 임시 비밀번호·아이디는 **SMS 로만** 전달(응답 본문 금지) · 번호당 발급 60초 1회·하루 5회 · 대조 횟수는 조건부 UPDATE 로 누적 |

- 근거 — `PRD` 가 문자 연동(F-05)을 **2단계**로 두어 지금 단계에 SMS 채널이 존재하지 않음. 발송 없는 전화번호 인증은 정상 사용자에겐 불능이고 공격자에겐 대입 경로. C-11 이 이미 "전화번호 인증 **또는 관리자 경유**" 로 두 경로를 규정하고, 관계자 계정은 `§6.7` 이 같은 형태(임시 비밀번호 1회 반환)를 운영 중
- 차단(`blocked`) 계정의 초기화는 차단을 풀지 않음 — 해제는 메인 관리자(C-11)

### ⭐ `Ruling 330` — 동승자 자동 배정을 확정 배치에 연결한다 (BR-018)

- 확정 배치(RTE-08)가 ④ ETA 산출 뒤 **동승자 자리가 빈 회차에만** `AttendantAssigner` 호출. 이미 수동 배치(`§5.14`)가 있으면 건드리지 않음
- 후보 — 그 학원의 삭제되지 않은 `escort` 매니저. 근무 시간·중복 배치 충돌이 없는 매니저를 고름(MGR-06)
- 후보가 없으면 **빈 채로 확정**(확정을 실패시키지 않음) — 관계자 화면의 미배치 표시로 드러남
- 배정되면 그 매니저에게 `assignment_changed` 알림(`§9.7` "당일 배치 변경 → 해당 매니저")
- `API_SPEC §5.14` 의 "구현 완료" 문면을 사실에 맞게 정정
- 근거 — `PRD §5` 인력 배차 흐름 · MGR-05 · `ARCHITECTURE §8.2` ⑤ 가 모두 자동 배정을 요구. 문서를 코드에 맞추면 동승자 없는 회차가 출발(승하차 기록 주체 부재, C-06)

### ⭐ `Ruling 331` — 푸시를 실제로 발송한다 (BR-040)

| 무엇 | 후 |
|---|---|
| 채널 | **FCM HTTP v1 한 채널** — android · ios(Firebase 가 APNs 로 중계) · web. `PushSender` 구현체 추가(`ARCHITECTURE §3.2.1` 교체 자리) |
| 대상 | 수신 계정의 **유효한 `device_token` 전부**(`§2.11` 다기기) |
| 무효 토큰 | FCM 이 `UNREGISTERED`·`INVALID_ARGUMENT` 를 주면 그 행을 해지(`§2.11` 무효 토큰) |
| 설정 가드 | `prod` 에서 발송 구현체가 로그 전용이면 **기동 실패** — 조용히 미발송으로 운영되는 상태 차단 |
| 자격 증명 | Firebase 서비스 계정 키 — SSM(`TECH_DECISIONS` 시크릿 규칙) |
| 로그아웃 해지 | `§2.7` 요청에 **`device_id`(선택) 추가** — 있으면 그 기기의 토큰 해지 |
| 앱 | 학부모·매니저 앱과 관계자 웹이 **실제 FCM 토큰**을 등록(현재 UUID 등록 — 2차 확인 V3) |

- 근거 — `PRD` 핵심 목표 "승하차 사실을 5초 이내 학부모에게 푸시" · NTF-12 가 P0. 문서를 줄이면 제품의 핵심 가치가 성립하지 않음
- ⚠ **Firebase 프로젝트 생성 · 서비스 계정 키 발급은 사용자 작업** — 키가 없으면 로컬은 로그 전용 구현체로 동작

### ⭐ `Ruling 332` — 응답 본문의 식별자는 전부 JSON 문자열 (BR-054)

- `API_SPEC §1.1` "식별자 — 서버 발급 문자열" 은 **경로 파라미터와 응답 본문 전체**에 걸림 — `Ruling 275` 가 사용자 몫으로 남긴 "응답 본문 전체에 걸리는가" 를 **예** 로 판정. `Ruling 171`(2^53 초과 시 JavaScript 정밀도 손실) 유지
- 요청 쪽은 **문자열·숫자 둘 다 수용**(`§5.14` 처럼 사양이 `integer` 로 적은 요청 필드 포함) — 클라이언트를 한 번에 바꾸지 않아도 요청이 깨지지 않음
- 적용은 **서버 일괄 + 관계자 웹 타입 변경을 한 단위**로. Dart 앱은 `asIdString` 흡수가 이미 존재 — 흡수 주석을 "사양 준수" 로 정리
- 근거 — 절별 표 20곳이 문자열 · 숫자 0곳. 지금은 같은 필드(`run_id` 등)가 응답마다 타입이 갈려(Ruling 275 조사) **전부 틀린 것보다 나쁜 상태**

### ⭐ `Ruling 333` — L3 감사는 조회·수정·삭제 (BR-060)

| 무엇 | 후 |
|---|---|
| 대상 행위 | 관계자·메인 관리자가 L3 필드(학생 사진 · 주소 원문·좌표 · 특이사항 · 보호자 연락처 원본)를 **조회·수정**하거나 학생을 **퇴원(삭제)** 처리 |
| `action` | `read` · `update` · `delete`(`API_SPEC §6.13` 3종 그대로) |
| 목록 응답 | L3 를 싣는 목록(관계자 학생 목록의 보호자 연락처 원본 등)은 **실린 학생마다 `read` 1행** |
| 대상 밖 | 학부모·학생의 **본인 범위** 조작(`§6.3` "학부모는 본인 자녀에 한해") |
| 문서 | `FEATURE_SPEC §6.3` "조회 시 감사 로그" → "조회·수정 시 감사 로그" |

- 근거 — 세 곳 중 두 곳(SYS-01 "조회·수정 이력" · `API_SPEC §6.13` `action` 3종 · `AuditLog` 정의)이 수정 감사를 요구하고 `§6.3` 만 "조회 시". 수정 감사를 빼면 Ruling 326 의 보호자 연락처 수정(계정 탈취 위험을 이유로 계정 연락처와 분리한 값)이 흔적 없이 바뀜

### ⭐ `Ruling 334` — ③구간 미등원의 전달과 적용 범위 (BR-068 · BR-006)

| 무엇 | 전 | 후 |
|---|---|---|
| 적용 대상 | 명단의 그 학생 상태 무관 → `absent` 로 덮어씀 | **`waiting` 학생에게만.** `boarded`·`alighted`·`no_show` 면 `403 CHANGE_WINDOW_CLOSED`(이미 처리된 학생은 앱에서 바꾸지 않음 — 동승자·관계자 경로) |
| 기사·동승자 전달 | 부재 | ① WS `rider_changed`(매니저·관제 채널, `status=absent` · `stop_skipped`) ② **기존 `route_changed` 알림**(배치 기사·동승자) — 새 알림 종류를 만들지 않음 |

- 근거 — `§3.6` ③ 이 "기사·동승자 푸시" 를 요구하는데 `§9.7`·`§7.1` 에 경로가 없어 사양 내부가 어긋나 있었음(2차 확인 V2). ③ 미등원은 해당 승하차지를 **정차하지 않고 지나감**으로 바꾸므로 "확정 후 노선 변경"(RUN-07, 수신자 기사·동승자)에 해당
- `waiting` 한정 근거 — `§3.6` ③ 의 대상은 "미등원"(아직 안 탄 학생). 탄 학생을 `absent` 로 덮으면 하원 회차가 아이를 태운 채 종료 가능(BR-006, 치명)

### ⭐ `Ruling 335` — 학생 채널에서 인원수를 뺀다 (BR-111)

- `/ws/students/{id}/run` 의 `run_started`·`run_ended` payload 에서 `auto_boarded_count`·`auto_alighted_count` 제외. 매니저·관제·메인 관리자 채널은 유지
- 근거 — C-08(학부모·학생 앱 "탑승 인원 미표시")은 기반 문서의 공통 규칙이고 `§1.12` 도 같은 문장. `§7.1` 이 채널 구분 없이 인원수를 적은 것이 어긋남. `position` 이 이미 채널별 payload(ETA 제외)를 쓰는 선례

### 프론트 영향 · 적용 순서 (2026-09-25 조사 — 관계자 웹 · 학부모 앱 · 매니저 앱 · 공유 패키지 2종)

**배포 전이라 사용자 기기의 옛 앱 버전은 부재** — 그래도 저장소 안에서 서버와 프론트가 어긋나면 시험·데모가 깨지므로 아래 순서를 지킨다.

| Ruling | 프론트 영향 | 서버만 바꾸면 | 적용 순서 |
|---|---|---|---|
| 327 | 매니저 앱은 `§4.2` 명단의 `stop_id` 를 도착 처리에 그대로 돌려보냄(비교·가공 부재) — **앱 수정 없이 학원 버튼이 생김** | `§4.2` 에 학원 항목이 없으면 **등원 운행이 영원히 종료되지 않음** | 서버 한 단위: 명단 · 노선 · 도착 처리 · `stop_arrived` 의 `stop_id` 를 **함께** `run_stop.id` 로. 앱은 "도착지" 표기만 선택 보완 |
| 332 | 관계자 웹 식별자 숫자 선언 77곳 · `Number()` 변환 9곳. 관제·금일 운행 지도가 `Number(markerId) === run.runId` 로 비교 | **지도에서 버스·정차지를 눌러도 선택되지 않음**(오류 없이 조용히) | **서버 + 웹을 한 단위로.** 앱 2종은 이미 문자열 + `asIdString` 33곳 흡수 — 영향 부재 |
| 335 | 공유 패키지 `baraeda_core/lib/websocket/ws_payloads.dart:123·143` 이 인원수를 `as int`(필수)로 변환 · 학부모 앱 실시간 지도가 "자동 탑승 처리 N명" 표시 | **학부모 앱 실시간 지도가 이벤트 수신 시 예외** | **앱 먼저**(패키지 nullable + 학부모 앱 표시 제거) → 서버 |
| 329 | 학부모 앱 `account_recovery_screen` · 관계자 웹 `recover` 가 `/auth/recover` 호출 | 일반 오류 문구만 표시(멈춤 부재) | 서버와 함께 두 화면에 "학원(관계자)에 문의" 안내 + 관계자 웹에 초기화 버튼(`§5.22` 진입점 — 없으면 새 API 를 쓸 곳이 부재) |
| 334 | 학부모 앱이 `CHANGE_WINDOW_CLOSED` 를 "운행 중에는 이 변경을 되돌릴 수 없습니다" 로 표시 | 이미 탄 학생의 미탑승 시도에 뜻이 맞지 않는 문구 | 서버와 함께 문구 분기. 매니저 앱은 `rider_changed` 를 받으면 명단을 다시 불러올 뿐이라 영향 부재 |
| 328 | 관계자 웹 해제 창은 응답을 읽지 않음 | 영향 부재 | 목록에 역할·차단 직전 상태 열 추가(선택) |
| 330 · 331 · 333 | 알림 종류는 웹이 이미 알고 앱은 문자열로 받음 · 앱은 UUID 를 단말 토큰으로 등록 중(FCM 이 거부 → 서버가 행 정리, 기능 손상 부재) · 감사는 서버 내부 | 영향 부재 | 푸시가 실제로 도착하려면 앱 2종·웹의 FCM 토큰 등록이 별도로 필요 |

**판정 밖 수정 중 계약이 바뀌는 것** — ① 401 본문(`TOKEN_EXPIRED`): 관계자 웹은 코드가 `TOKEN_EXPIRED` 일 때만 재발급하는데 지금 401 에 본문이 없어 **웹 자동 재발급이 동작하지 않는 상태** — 고치면 정상화, 앱은 상태 코드만 봐서 영향 부재 ② 삭제 `200`→`204`: 웹이 `204` 를 이미 처리 ③ `500`→`422`: 오류 문구만 정확해짐 ④ `§5.4` 응답 모양(`items` vs 배열): 웹이 배열에 맞춰져 있어 **코드가 아니라 문서를 고친다**


---

## 8.46 ⚖ 백엔드 전체 검사 1차 수정 결과 · 수정 중 사양 판정 `Ruling 336`~`343` (2026-09-25)

**1차 수정 완료** — 원장 `backend/report/2026-09-25-백엔드-전체-검사.md` 의 동작 변경 항목 134건을 8갈래(`fx-a`~`fx-h`, Orca Run `run_56f08ba4dda1`)로 수정해 main 에 병합(`981c3c63` G · `68ca788d` E · `b0004c32` H · `7a991bb0` F · `0aaa4bbf` B · `adf6b9b7` A · `8717550f` C · `6dab9f26` D).
결과 — 수정 129 · 부분 수정 2 · 보류 2(BR-139 · BR-154) · 잘못 짚음 1(BR-108). 갈래별 판단 근거·재현 시험은 `backend/report/review-2026-09-25/FIX-*.md`.

**검증** — 병합 main 에서 전체 시험 **282 클래스 · 1,612건 · 실패 0 · 오류 0 · 건너뜀 0**(검사 전 1,409건 대비 +203, 결과 XML 집계). 관계자 웹 `tsc` 0건 · vitest 351 통과(77 건너뜀 = 실서버 연동) · Flutter 단위 시험 baraeda_core 41 · baraeda_ui 83 · 학부모 앱 106 · 매니저 앱 115 전부 통과.

**병합 중 조율자가 고친 것(리뷰 대상, 병합 커밋 메시지에 전말)** — 갈래끼리 맞물린 결함 7건: 퇴원생 제외가 공용 명단 부품에서 빠짐(A×B) · removed 행이 학생 회차 일괄 조회에 섞임(A×B) · 알림 발송 실행기가 STOMP 송신 실행기를 대체(C) · 학원 식별자 지역 변수 옮겨 담기(C) · 위치 유실 지표가 첫 500건만 셈(D) · 로그아웃이 notification 저장소를 직접 import(D) · 종료 보류 시험이 등원 회차에 보류를 켬(E×H).

| Ruling | 원장 | 판정 | 근거 |
|---|---|---|---|
| 336 | BR-029 | ②구간 `riding=true` 는 `403 CHANGE_WINDOW_CLOSED`, 지금 의사와 같은 값은 한도·요청 없이 `200 applied`(무변경). ①에서 끈 학생도 그 회차의 대상(`404` 아님) | PRD "30분 이내: 추가 불가 — 취소만 승인 경로" |
| 337 | BR-110 | `absent` 알림은 관계자 통지가 없던 두 경우(① 변경 신청 cancel · ② cancel 승인)만. ① 토글 OFF 는 `intent_changed` 로 충족 | PRD "오늘 안 타요" — 관계자 통지 1회 |
| 338 | BR-052 | 오늘 운행 중(`moving`) 회차가 서는 승하차지의 **좌표** 수정은 `403 CHANGE_WINDOW_CLOSED`. 이름·그 밖 회차는 허용 | ARCHITECTURE §8.5 운행 시작과 동시에 노선 잠금 |
| 339 | BR-022 · BR-023 | 끝나지 않은 배치가 있으면 역할 변경도 `409 MANAGER_ASSIGNED` — 삭제 차단과 같은 조회("배치 중" = 취소·종료 아님 + 운행 중이거나 오늘 이후) | MGR-04 "배치 해제 후" |
| 340 | BR-042 | 취소는 `idle`·`confirmed` 만. 취소된 회차의 시작·강제 추가·이동은 **새 코드 `409 RUN_CANCELED`**(행이 실재해 `404` 는 틀린 사실). 매니저 목록에서 제외 | §5.10 `canceled_at` 이 관계자 화면에 보임 |
| 341 | BR-016 | 출발 이동 학생의 행 = `status absent` + `change removed` — §4.2·§5.4 명단에는 보이고(빨강) `absent_n`·알림·학생 채널에서는 제외 | FEATURE_SPEC §3.5 "당일 삭제된 탑승자 — 빨강 하이라이트" |
| 342 | BR-047 | 확정 연속 실패는 §5.10·§6.8 응답의 `consecutive_failures` 로 노출. 경보 채널은 계속 공백 | UF-O-07 이 비운 것은 채널 — 식별 재료는 DB 에 실재 |
| 343 | BR-116 | 정원 축소 시 `PATCH /staff/buses/{id}` 응답 `warnings[]` `CAPACITY_BELOW_ASSIGNED` — 경고이고 차단 아님 | §5.14 경고 형태와 같게 |

**남은 것** — 2차(모듈을 가로지르는 정리 26건 · 문서 5건 · 새 발견 BR-171) · 보류 BR-139·BR-154(사양 판정 필요) · **W12 BR-166~170 은 사용자 지시로 계획만**(`backend/report/2026-09-25-스레드-풀-Redis-수정-계획.md`, 착수 대기) · 프론트 후속(`RUN_CANCELED` 문구 · 정원 경고 표시 · 연속 실패 표시 · 경유 지점 `preview_token` · WebSocket `TOKEN_EXPIRED` 재연결 확인).

## 8.47 ⚖ 백엔드 전체 검사 — 보류 3건 판정 `Ruling 344`~`346` (2026-09-25)

1차 수정에서 사양 결정이 필요해 멈춘 3건(`backend/report/review-2026-09-25/FIX-E.md §1` · `FIX-H.md §1`). 사용자 위임(*"PRD·API 명세서 기준으로 적절하게"*)으로 판정.

| Ruling | 원장 | 판정 | 근거 |
|---|---|---|---|
| 344 | BR-154 | `§4.11` 요청의 `change_ids[]` 를 **삭제**. 변경 확인은 **현재 노선 버전 단위 전건** — 본문 없이 호출하면 그 회차의 현재 버전을 확인한 것으로 기록 | 변경 항목 id 를 내주는 응답·저장하는 칸이 사양 어디에도 부재(`grep change_id docs/` → §4.11 1건). 확인 기록은 `assignment.acked_route_version_id`(ERD) 버전 단위이고 표시값도 전부 참/거짓(§4.1 `ack_required` · §5.3 `ack_driver`·`ack_escort`). M-04 "변경 목록 확인 **버튼** 응답" 도 버튼 하나. 매니저 앱도 이 필드를 보내지 않음(`roster_screen.dart` `ackChanges(runId:)`) |
| 345 | BR-031 1번 갈래 | 승하차 처리(`§4.6`)는 FEATURE_SPEC §3.3 전이 3개만 받는다 — `waiting→boarded` · `waiting→no_show` · `boarded→alighted`. 그 밖(같은 상태 재요청 포함)은 **새 코드 `409 RIDER_TRANSITION_NOT_ALLOWED`**. 표 밖으로 가려면 되돌리기(§4.7)가 먼저 — 하원 자동 승차(C-07) 뒤 미승차는 [되돌리기](`boarded→waiting`) → [미승차](`waiting→no_show`) 로 표 안이다. `client_key` 재전송은 전이 검사보다 먼저 판정돼 `200` 재생 유지 | §3.3 이 `no_show` 를 "종결" 로 적음. 매니저 앱은 `waiting` 행에 [탑승]·[미승차], `boarded` 행에 [하차]·[되돌리기]만 낸다(`roster_screen.dart` 상태별 버튼) — 정상 조작은 표 안. 오프라인 큐는 쌓인 요청을 새 요청보다 먼저 순서대로 흘려(`offline_queue_repository_impl.dart`) 순서 역전으로 표 밖 전이가 생기지 않음. `422` 가 아니라 `409` 인 것은 요청 형식이 아니라 탑승자의 현재 상태가 막기 때문(`ErrorCode` 관례 · Ruling 340 선례) |
| 346 | BR-139 | 시드(`V2` · `V14`)의 날짜식 **유지** — `service_date` 는 적용 시각의 한국 날짜, `depart_time` 은 적용 시각 기준 상대값. 한국시간 20시 이후·03시 이전에 적용하면 일부 회차의 운행일과 출발 시각의 한국 날짜가 갈린다는 한계를 시드 머리 주석에 적는다 | 원문 수정안(운행일을 출발 시각에서 유도)은 R1·R6·R8 이 공유하는 노선 1행의 요일과 어긋나 확정 배치의 노선 조회(`RunConfirmationService`)가 실패 — 저녁에 만든 시험 DB 를 깨는 쪽이 지금 결함(자정 뒤 오래 켜 둔 로컬 서버의 매니저 목록)보다 해가 큼. 요일별 노선 행 추가는 시드 행 수 계약 변경 대비 이득이 작음(로컬은 `down`+`up` 으로 매번 다시 깖) |

**프론트 후속에 추가** — 매니저 앱 `RIDER_TRANSITION_NOT_ALLOWED` 문구(같은 학생을 두 번 누르거나 화면이 낡았을 때 보임).

## 8.48 ⚖ 백엔드 전체 검사 2차 정리 — 범위 판정 `Ruling 347`~`348` (2026-09-26)

2차 2단계 착수 전 조율자 판정. 사용자 위임(2026-09-26 *"결정 사항이 있다면 적절히 선택해줘"*).

| Ruling | 원장 | 판정 | 근거 |
|---|---|---|---|
| 347 | BR-098 · BR-099 | BR-099 의 **Redis 좌표 리더 3벌**(`location/proximity/RunPositionReader` · `student/query/RunPositionCache` · `monitoring/query/RunPositionReader` 와 각 `RunPositionSnapshot`, 키 `run:%d:position`)과 BR-098(Redis 포트 부재)은 2차에서 손대지 않고 **W12 에서 처리**. BR-099 의 나머지(요일 변환 · 제약명 판별 · 현재 정차지 판정)는 2차 2단계 | W12 의 BR-167(Redis 장애 시 DB 최신 행 대체)이 좌표 읽기 경로를 새로 짓는다 — 2차에서 3벌을 먼저 합치면 W12 가 같은 코드를 한 번 더 옮긴다. 나머지 3종은 Redis 와 무관 |
| 348 | BR-113 | `§3.1`·`§3.3`·`§7` 규칙 9(**전 엔드포인트 Swagger + 시드 기반 예시**)를 **유지하고 예시를 채운다** — 예시를 포기하도록 사양을 고치지 않는다. 우선순위는 요청(로그인 본문 · 경로·쿼리 식별자 · 요청 DTO 식별자) → 응답. `§3.3` 의 "최소 1개 응답 예시" 는 **응답 스키마에 예시가 달린 필드가 하나 이상**인 것으로 판정 — 엔드포인트마다 `@ApiResponse` 본문을 손으로 쓰지 않는다 | 규칙 9 가 막으려는 사고("Try it out 이 401·404 로 끝남")는 요청 쪽 예시로 풀린다. 응답 본문 예시를 111개 엔드포인트마다 손으로 쓰면 DTO 필드와 두 벌이 돼 낡는다 — 필드 `@Schema(example)` 는 springdoc 가 응답 예시로 조립한다 |

## 8.49 ⚖ W12 스레드 · 커넥션 풀 · Redis — 착수 전 판정 `Ruling 349`~`351` (2026-09-26)

계획서 `backend/report/2026-09-25-스레드-풀-Redis-수정-계획.md §5` 의 결정 3건. 사용자가 착수를 허락했고(*"이후 W12 진행"*) 결정은 위임(2026-09-26 *"결정 사항이 있다면 적절히 선택해줘"*) — 계획서 권장안 그대로.

| Ruling | 원장 | 판정 | 근거 |
|---|---|---|---|
| 349 | BR-170 | STOMP 송신이 넘칠 때 **버스 위치(`position`) 방송은 옛 값을 버린다.** 승하차·비상 이벤트는 버리지 않는다. 버린 건수는 `schoolbus.ws.outbound.dropped{event}` 로 노출 | 위치는 2~5초 뒤 새 값이 덮어쓰는 데이터라 버려도 다음 값이 온다 — 학부모 화면은 과부하 순간 몇 초 멈춘 뒤 최신 값으로 이동. 버리지 않으면 큐가 무한히 늘어 **힙 고갈 → 프로세스 종료**(부하 측정 `2026-09-09-부하-한계-측정.md`)로 끝나고 그때는 모든 이벤트가 사라진다 |
| 350 | BR-169 | 지도 API 격벽을 **배치 3칸(`mapRouteBatch`) · 화면 1칸(`mapRouteOnDemand`)** 으로 분리. 합계 4 = 공급자 동시성 상한 유지. 확정 워커 수 = 배치 칸 수 | 출발 30분 전 확정(07:30 등)이 몰리는 시각에 배치가 우선이어야 한다 — 지금은 화면 조회 1건이 칸을 차지하면 회차 1건이 직선거리 근사로 확정된다. 화면은 칸이 없으면 지금처럼 직선 근사(표시용이라 재조회로 회복). 대안(배치에만 대기 시간)은 변경이 작지만 화면 요청이 배치를 계속 밀어낼 수 있다 |
| 351 | BR-168 | 운영(`prod`)·데모(`demo`) 프로파일에 **Hikari 20 · STOMP 송신 스레드 32** 를 **잠정값**으로 넣는다. 설정 주석에 "측정 기계(10코어) 기준 — 운영 권장 사양(4 vCPU) 재측정 전까지" 와 `max_connections`(기본 100) 여유 계산을 적는다 | 기본값(10·4)은 측정 근거가 없고, 측정값은 적어도 이 부하 형태에서 검증됐다. 운영 사양 재측정은 배포 계획에 편입 |

## 8.50 ⚖ W12 완료 판정 — 부하 확인 결과 해석 `Ruling 352` (2026-09-26)

계획서 `backend/report/2026-09-25-스레드-풀-Redis-수정-계획.md §4` 의 4번(실동작)·5번(부하) 결과(`backend/report/review-2026-09-25/FIX-VERIFY.md`). 사용자 위임(2026-09-26 *"결정 사항이 있다면 적절히 선택해줘"*).

| Ruling | 원장 | 판정 | 근거 |
|---|---|---|---|
| 352 | BR-166~170 | **W12 완료로 판정한다.** §4-4 실동작은 통과. §4-5 부하는 ①(힙이 상한 안에서 멈추고 프로세스 생존)·②(`position` 만 버려짐) 통과, ③(09-09 대비 위치 수신 실패율 개선)은 **학생 채널 구독형(실제 트래픽 형태)으로 판정** — 같은 세션·위치 부하에서 p95 32ms · 실패 0. 관제 채널에 전 세션이 몰리는 최악 회차(p95 45~55s · 실패 4~20%)는 W12 의 결함이 아니라 **별도 조사 대상 2건**으로 넘긴다: ⓐ 확정 배치와 위치 수신이 DB 연결을 다퉈 획득 대기가 30초(Hikari `connectionTimeout`)에 걸림 — 09-09 보고서 §13.2 의 "막히는 자리 미특정" 과 같은 증상, 프로파일링으로 특정 ⓑ 관제 채널 팬아웃(세션 수 × 위치)의 실제 관제 세션 수 기준 재평가. 후속 설정 — 세션 송신 버퍼 64KB·시간 10초 명시 · 운영 컨테이너 메모리 상한 명시(`MaxRAMPercentage=70` 이 호스트 전체 기준이 되지 않게) | 관제 채널은 관계자·메인 관리자만 구독해 실제 세션 수가 학부모 채널보다 두 자릿수 이상 적다(최악 회차는 전 세션을 관제에 몰아 측정). 실패의 직접 원인은 송신 큐가 아니라 DB 연결 획득 대기(30.0~30.02s)로 측정됐고, 이는 W12 가 다룬 Redis·송신 경로 밖이다 |

## 8.51 ⚖ 목표 규모 판정 · 연결 경합의 원인 `Ruling 353` (2026-09-26)

`Ruling 352` 가 넘긴 조사 2건의 결과(`backend/report/review-2026-09-25/FIX-LOAD2.md`).

| Ruling | 원장 | 판정 | 근거 |
|---|---|---|---|
| 353 | BR-166~170 후속 | ① **목표 규모(학원 10 · 버스 100 · 학생 2,000 · 회차 200)는 확정 배치까지 겹친 실제 사용 형태에서 감당한다** — 위치 8,398건 실패 0 · p95 37.6ms · 방송 유실 0 · 확정 200건 전부 도로 경로(직선 근사 0) · 연결 타임아웃 0 · 힙 최대 77%. ② **`Ruling 352` 가 넘긴 "확정 배치 ↔ 위치 수신 DB 연결 경합(30초 대기)" 은 앱 결함이 아니라 측정 환경 결함이다** — macOS Docker Desktop 의 호스트→컨테이너 포트 전달(15432)이 26~29초씩 멈췄다. 그래서 `FIX-VERIFY.md` 의 "관제 채널 몰림 회차 A·B 의 위치 실패 4~20%" 는 **앱의 한계 수치로 쓰지 않는다**(같은 오염). `2026-09-09-부하-한계-측정.md §13.2` 의 "막히는 자리 미특정" 도 같은 원인일 가능성이 높다(같은 증상 — 스레드는 DB 연결 대기, CPU 한산). ③ 앞으로 **포화 지점을 찾는 부하 회차는 앱을 Postgres 와 같은 Docker 네트워크(또는 Linux 호스트)에서 띄운다** — 호스트 앱 → 컨테이너 DB 경로로는 측정하지 않는다 | 네 갈래 증거가 같은 답: 누수 감지(연결 보유 17.5~29.7초의 획득 지점은 평범한 조회) · 스레드 덤프 12개(보유 스레드 전부 Postgres 소켓 읽기, 잠금·송신 대기 0) · JFR(15432 소켓 읽기 5초 이상 61건, 대부분 26~29초, CPU 0.003~0.03) · `pg_stat_activity`(같은 순간 Postgres 전 세션이 `ClientRead` — 이미 답하고 다음 명령을 기다림, 실행 쿼리 0 · 잠금 0). 양쪽이 서로를 기다린 것은 바이트가 중간 전달 경로에 묶였다는 뜻. 회차 직후 Docker Desktop 전체의 포트 전달이 고장났고 재시작으로 복구됐다 |

## 8.52 ⚖ 측정 환경 판정 확정 `Ruling 354` (2026-09-26)

| Ruling | 원장 | 판정 | 근거 |
|---|---|---|---|
| 354 | BR-166~170 후속 | `Ruling 353` ②를 **확정**한다 — 앱 · k6 · Postgres · Redis 를 전부 같은 Docker 네트워크에 두고(호스트 포트 전달 부재) `FIX-VERIFY.md` 의 관제 몰림 A·B 와 같은 조건으로 다시 재니 **위치 수신 실패 4.4~20.5% → 0%, Hikari 대기 최대 30초(타임아웃 상한) → 0.9~13.6초**, 30초 정지 서명 소멸. 이 경로로 드러난 **실제 한계는 관제 채널 WebSocket 팬아웃 지연**(세션 3,000 에서 p95 13.4초 · 4,000 에서 29.8초) — 실제 관제 세션(학원당 관계자 1명 × 10 + 메인 관리자 ≈ 12)의 250~333배 규모라 **조치하지 않는다.** 포화 측정 도구로 `backend/load/compose-inside.yml` · `run_load3.sh` 를 남긴다 | `backend/report/review-2026-09-25/FIX-LOAD3.md` — 앱 컨테이너가 `school-bus-postgres-1:5432`·`school-bus-redis-1:6379` 로 접속(호스트 포트 게시 부재) |

## 8.53 ⚖ 프론트 R31 중 판정 `Ruling 355`~`357` (2026-09-26)

| Ruling | 원장 | 판정 | 근거 |
|---|---|---|---|
| 355 | W12 관찰 2건 · BR-094 잔여 | ① 관제·학부모 버스 위치 조회의 Redis `MGET` 이 `readOnly` 트랜잭션 안에 남는 것(Redis 무응답 시 요청당 최대 500ms 연결 보유)과 ② 위치 수신 리스너의 `catch` 를 좁혀도 시험이 못 잡는 것(변형 M10 생존 — Spring `afterCompletion` 이 예외를 스스로 잡음)은 **조치하지 않는다.** ③ `routing → run`(확정 전이 · 회차 잠금 · `RunRouteConfirmedEvent`)과 `routing → request`(`ApprovalPreviewResolver`)는 `ARCHITECTURE §3.3` 이 허용한 방향 — 다이어그램에 화살표만 추가 | ① 관제 세션은 실제 약 12개(`Ruling 354`)라 500ms × 12 가 연결 풀 20 을 위협하지 않는다. 트랜잭션 밖으로 빼려면 두 서비스의 클래스 단위 `@Transactional(readOnly)` 를 풀어야 해 변경 대비 이득이 없다 ② 204 를 지키는 것은 프레임워크이고, 실제 막아야 할 형태(Redis 쓰기가 트랜잭션 안 + 보호 부재 · M11)는 잡힌다(`FIX-W1.md` 2·4항) ③ `§3.3` 본문 "`routing` 이 확정 전이 — 상태 전이 메서드를 통해서만" · 다이어그램 `routing → … request (읽기)` |
| 356 | `LOC-01` · R31 M-F | 매니저 앱 운행 화면이 떠 있는 동안 **화면 꺼짐 방지**(`wakelock_plus` · 얇은 포트 `WakelockPort`)를 켠다. 켜고 끄는 기준은 화면 생명주기(진입·dispose) — 운행 상태가 아니다. 백그라운드 위치 송신(iOS `Always` · Android 포그라운드 서비스)은 **범위 밖**으로 둔다 | 위치 송신이 앞에 뜬 운행 화면 범위(M-B)라 화면이 꺼지면 송신이 멈출 수 있고, 백그라운드 송신은 권한·스토어 심사 부담이 크다. 운행 종료 뒤 결과 화면이 위에 쌓이는 동안 켜진 채 남는 것은 수용(운행 화면을 떠나는 순간 풀림 — `FIX-MF.md` 2항) |
| 357 | BR-054 · `Ruling 332` 적용 방식 | 서버 응답 식별자 문자열화를 **레코드 타입 변경이 아니라 전역 Jackson 직렬화 모듈**(`global/config/IdentifierJsonConfig` — 필드 이름이 `id` 이거나 `…Id` 로 끝나고 타입이 `Long`/`long` 이면 문자열)로 한다. 레코드의 `Long` 은 그대로 · 요청 역직렬화는 숫자·문자열 둘 다 수용 · 식별자가 아닌 숫자(`capacity` · `*_count` · `consecutive_failures`)는 숫자 그대로 | 66파일 104필드를 `String` 으로 바꾸면 내부 비교·DB 바인딩마다 변환이 생긴다. 이름 규약 판별이라 **새 응답 레코드도 코드 수정 없이 자동으로 문자열**(`IdentifierJsonConfigTest`). 응답에 `List<Long> …Ids` 필드는 부재(요청 DTO·지역 변수뿐 — 조율자 확인). ⚠ **이름이 `Id` 로 끝나지 않는 식별자를 응답에 새로 넣으면 숫자로 샌다** — 새 필드는 `…_id` 이름 규칙을 지킨다(`API_SPEC §1.1`) |
