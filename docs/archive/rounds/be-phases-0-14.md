# 보관 — 백엔드 Phase 0~14 · 처분 방침 · 열린 항목 원문

**2026-10-01 `docs/IMPLEMENTATION_PLAN.md` 에서 원문 그대로 옮긴 보관 문서(R46-DOCS · 분기점 `bef9d3ce`).** 요약·재작성 없이 줄 단위로 옮겼고, 절 제목의 번호(`## 8.2 …` 등)도 원문 그대로라 옛 절 번호 인용이 이 파일에서 같은 번호로 찾아진다. **읽고 싶을 때만 연다** — 지금의 규칙·진행 표는 본문 ``docs/IMPLEMENTATION_PLAN.md`` 에 있다. 이 파일 안의 "위"·"아래"·"이 문서" 와 절 번호 인용은 옮기기 전 문서 기준이며, 본문에 남은 절(`7` 횡단 규칙 · `8.73` 이후 등)을 가리킬 수 있다.

| 항목 | 내용 |
|---|---|
| 옮긴 절 | 1(현 코드 처분 방침) · 6(Phase 0~14·F1~F4 본문, 이월 소단위 묶음 F1~F6·S 포함) · 8 표의 Phase 행 비고 원문 · 8.1(병렬 가능 구간) · 9(열린 항목 원문, 7.5 코드 주석에만 있던 판정 23건 포함) |
| 기간 | 2026-08-24 ~ 2026-09-09 |
| 줄 수 | 원문 1103줄(아래 머리말·구분선 제외) |

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

⚠ **2026-09-10 사용자 결정으로 프론트엔드는 다시 범위 안이다 — Ruling 255 의 "프론트 영구 범위 밖" 을 뒤집었다.** 옛 `frontend/`(Flutter) 는 삭제한 그대로이고 지금의 `frontend/` 는 새로 만든 것이다(관계자 웹 Next.js · 앱 2종 Flutter). 작업 창구는 [`docs/frontend/IMPLEMENTATION_PLAN.md`](./frontend/IMPLEMENTATION_PLAN.md) 로 분리했고, 아래 Phase F1~F4 의 `➖` 는 옛 Flutter 계획에 대한 기록으로 그대로 둔다.

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


## 6. 구현 순서 (Phase) — Phase 0~14 · F1~F4 본문 (본문 6.1 "순서를 정한 기준" 뒤를 옮김)

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

⚠ **2026-09-10 사용자 결정으로 프론트엔드는 다시 범위 안이다 — Ruling 255 의 "프론트 영구 범위 밖" 을 뒤집었다.** 옛 `frontend/`(Flutter) 는 삭제한 그대로이고 지금의 `frontend/` 는 새로 만든 것이다(관계자 웹 Next.js · 앱 2종 Flutter). 작업 창구는 [`docs/frontend/IMPLEMENTATION_PLAN.md`](./frontend/IMPLEMENTATION_PLAN.md) 로 분리했고, 아래 Phase F1~F4 의 `➖` 는 옛 Flutter 계획에 대한 기록으로 그대로 둔다.

**2026-08-25 사용자 확정으로 착수 대상에서 제외.** 순서상 뒤라서가 아니라 **앞으로의 작업 범위를 백엔드로 한정한다는 결정** 때문이며, 백엔드 Phase 14 가 끝나도 자동으로 착수되지 않는다. **2026-09-04 사용자 재확정(Ruling 255) — 프론트엔드와 법정 요건은 앞으로 계속 고려하지 않는다.** 아래 "다시 범위에 들어올 때" 표는 기록으로만 존치. 프론트 전용 에이전트 2개(`ui-implementer` · `design-system-auditor`)도 같은 결정으로 삭제됨. 이 절과 §1.4 프론트엔드 처분은 **삭제하지 않고 보존** — 사양의 프론트 요구는 그대로 유효하고, 다시 범위에 들어올 때 이 내용을 그대로 쓴다. 상태 갱신 대상은 백엔드 Phase 0~14 뿐이며 F1~F4 는 `➖` 로 고정.

**다시 범위에 들어올 때 먼저 확인할 것 4가지**

| # | 확인 대상 | 왜 |
|:-:|---|---|
| 1 | **선행 백엔드 Phase 완료 여부** — F1: 9·10·11 / F2: 8·10·12 / F3: 8·13 / F4: 3·13 | 선행이 🟡 면 화면이 호출할 API 가 부재. §8 표에서 확인 |
| 2 | **지도 SDK 선정** (오픈 이슈 P · `PRD §10.1`) | F1~F4 전부의 지도 화면을 막음. `MapSurface` 플레이스홀더로 골격은 가능하나 `LOC-04` 착수 불가 |
| 3 | **강제 노선의 지도 표현** (오픈 이슈 Q) | F3 ②구간 승인 화면의 강제 추가 승하차지 표기가 미확정 |
| 4 | **`frontend/` 현 코드의 처분 판정** (§1.4) | 판정 시점이 2026-08-24 라 재개 시점의 코드와 어긋날 가능성이 존재. 재개 전에 다시 대조 |

~~`frontend/docs/` 3종(`FLUTTER_CODE_CONVENTIONS.md` · `DESIGN_SYSTEM.md` · `FRONTEND_SETUP.md`)은 재개 시점의 규칙 원본으로 존치~~ — **2026-09-10 프론트 재개 · 2026-09-20 문서 통합으로 그 경로는 부재.** 지금의 규칙 원본은 `docs/frontend/CONVENTIONS_REACT.md` · `CONVENTIONS_FLUTTER.md` · `SETUP.md`(2026-09-30 R40 재작성).

**보존된 Phase 정의** — 백엔드 API 가 선행. 제품 4종은 배포 단위가 다르나 코드 공유 범위가 `ARCHITECTURE §4.1` 에 정의돼 있음.

| Phase | 제품 | 선행 | 완료 조건 요약 |
|---|---|---|---|
| **F1** | 매니저 앱 (기사 · 동승자) | Phase 9 · 10 · 11 | 역할 분기 · 운행모드 항상 다크 · **낙관적 UI 부재**(`C-10`) · 오프라인 큐 복구 동기화 · 색·라벨 병기(`C-09`) · **지도 페이지 부재**(동승자) |
| **F2** | 학부모 · 학생 앱 | Phase 8 · 10 · 12 | 자녀 2명 이상일 때만 선택 UI · 탑승 토글 3구간 분기 · 승인 대기 카운트다운 · **ETA·탑승 인원 미표시**(`C-08`) · 요일별 주소 설정 |
| **F3** | 관계자 웹 | Phase 8 · 13 | 가입 승인 · **②구간 승인 화면(기존 vs 재최적화 대조)** · 강제 추가 · 수동 조정 · 경유 지점 · 대시보드 · 비상 알림 팝업 |
| **F4** | 메인 관리자 콘솔 | Phase 3 · 13 | 학원 CRUD · 관계자 가입 승인 · 차단 해제 · **전 학원 관제(ETA 포함)** · 비상 알림 미확인 경과 표시 |

**전 제품 공통 3가지** — `go_router` `redirect` 한 곳에서 계정 상태·역할 분기 · `dio` interceptor 로 JWT 부착 + 401 자동 refresh · 색은 그린·앰버·레드·스톤 4색 고정(`C-09`). **이 3가지가 이 문서의 유일한 프론트 전용 규칙**이며, §7 횡단 규칙에는 프론트 전용 항목이 부재.

## 8. 진행 추적 표 — Phase 0~14 행의 비고 원문 · 8.1 병렬 가능 구간 (본문 표는 요약행만 남김)

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
| ~~**P**~~ | 지도 SDK 선정 | **해소 — 2026-09-10 프론트 재개와 함께 네이버 단일 공급자로 확정**(웹 JavaScript API v3 · Flutter `flutter_naver_map`, `docs/frontend/IMPLEMENTATION_PLAN §8.3`). 옛 문면: 범위 밖 확정(2026-09-04 사용자, Ruling 255 — 프론트 영구 제외) | `MapSurface` 플레이스홀더로 화면 골격은 가능. `LOC-04` 착수 불가. **백엔드 Phase 를 막지 않음** — 프론트 재개 시 결론 필요 |
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
