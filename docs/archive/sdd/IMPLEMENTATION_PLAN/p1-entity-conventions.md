# Phase 1 엔티티 작성 규약 — 39개 공통

이 문서는 **조율자가 확정한 규칙**이다. Task 3~6 이 전부 이것을 읽고 같은 형태로 쓴다.
**그대로 따르고, 어긋나야 할 이유를 발견하면 고치지 말고 보고하라.**

Phase 1 이 39개 엔티티를 **한꺼번에** 매핑하는 이유는 하나다 — `ddl-auto: validate` 가 **매핑된 엔티티만** 검사하기 때문이다. 일부만 매핑하면 나머지 테이블의 어긋남이 한참 뒤에야 드러난다.

---

## 1. FK 는 `Long` 원시 필드 (Ruling 30)

**`@ManyToOne` · `@OneToMany` · `@OneToOne` · `@MapsId` 를 쓰지 않는다. 예외 부재.**

PK = FK 인 1:1 확장 테이블(`academy_setting` · `notification_setting` · `confirmed_route`)도 마찬가지다:

```java
@Id
@Column(name = "academy_id")
private Long academyId;
```

**근거** — ① 횡단 규칙 16(모듈 역방향 참조 금지)이 엔티티 `import` 로 조용히 뚫리는 것을 원천 차단 ② `TECH_DECISIONS §9` 가 프로젝션 지향이라 연관 그래프 순회가 불필요 ③ 순환 FK 쌍(`confirmed_route.current_version_id` ↔ `route_version.confirmed_route_id`)이 JPA 저장 순서와 충돌하지 않음 ④ 여러 에이전트가 컴파일 의존 없이 병렬로 돌 수 있음.

⚠ **Task 1 보고서 §6 의 "`@MapsId` 패턴 후보" 서술을 따르지 마라 (Ruling 50).** 그 보고서는 스키마 담당자가 쓴 것이고 이 규약이 확정되기 전 제안이다. 규약이 이긴다.

⚠ **`ERD §4.2` 의 FK 미설정 6개 테이블**(`run_position` · `notification_log` · `audit_log` · `exception_report` · `rider_status_history` · `emergency_alert`)에 실수로 `@ManyToOne` 을 달면, 존재하지 않는 DB 제약을 Hibernate 가 검증하려다 실패한다. 이 6개는 예외 없이 `Long` 컬럼만.

## 2. Lombok 은 두 개만 (Ruling 29)

**허용** — `@Getter` · `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
**금지** — `@Setter` · `@Builder` · `@Data` · `@AllArgsConstructor` · `@EqualsAndHashCode` · `@ToString`

빌더는 상태 전이 불변식을 우회한다(`ARCHITECTURE §3.2.3`) — `RunRider.builder().status(ALIGHTED).build()` 가 컴파일되는 순간, 허용되지 않는 전이를 아무 데서나 만들 수 있다.

## 3. 생성은 정적 팩토리로만 (Ruling 31)

`private` 생성자 + `public static` 팩토리. 이름은 "어떻게 만드는가" 가 아니라 **"언제 생기는가"** 를 말한다 — `uponApproval(...)` · `forNewLink(...)` · `forConfirmedRoute(...)`.

**팩토리는 `Long` 식별자를 받고**, 파라미터 순서는 **부모 → 자식 → 부속**으로 전 엔티티 통일한다.

> `ARCHITECTURE §3.2.3` 은 "엔티티 참조를 받아 타입으로 순서를 강제" 하는 대안 1을 권하지만, §1 로 `@ManyToOne` 이 사라진 상태에서 팩토리 파라미터만 엔티티로 받으면 모듈 간 컴파일 import 가 되살아나 횡단 규칙 16 이 뚫린다(예: `boarding` 의 `RunRider` 가 `run`·`student` 를 import). 그래서 §3.2.3 의 대안 2(식별자 + 순서 통일)를 쓴다. 완전한 방어가 아니라는 것은 조율자가 인지하고 있다 — 최종 형태는 각 도메인 Phase 가 팩토리를 이름으로 분화시킬 때 승격하는 것이다.

⚠ **Phase 1 은 필드 매핑까지다.** 상태 전이 메서드(`activate()` · `changeTo()` · `advanceToVersion()`)와 정책 판정은 **각 도메인 Phase 가 붙인다.** 팩토리는 그 엔티티를 만들 수 있는 **최소 1개**만 만들고 도메인별 분화는 미룬다.

## 4. 타입 매핑

| DB 타입 | Java 타입 | 비고 |
|---|---|---|
| `timestamptz` | **`OffsetDateTime`** | `LocalDateTime` **금지** — 오프셋을 버리면 비교 결과만 틀리고 예외는 발생하지 않는다. 이 시스템의 중심축이 시간이다 |
| `date` | `LocalDate` | |
| `time` | `LocalTime` | |
| `numeric(9,6)` (좌표) | **`BigDecimal`** + `@Column(precision = 9, scale = 6)` | `Double`·`double` **금지** — `lat BETWEEN -90 AND 90` CHECK 경계에서 부동소수 반올림이 위반을 만든다 (Ruling 34) |
| `numeric(6,2)` 등 기타 | `BigDecimal` + precision·scale 명시 | |
| `jsonb` | `@JdbcTypeCode(SqlTypes.JSON)` + **`Map<String, Object>`** (ERD 서술이 배열을 함의하면 `List<Map<String, Object>>`) | 대상 3개 — `manager.work_hours` · `route_version.policy_snapshot` · `audit_log.detail`. 전용 record 승격은 소유 Phase 담당 (Ruling 33) |
| `uuid` | `UUID` | |
| `inet` | **`String` + `@JdbcTypeCode(SqlTypes.INET)`** | ⚠ **2026-08-25 정정(Ruling 67).** plain `String` 은 `ddl-auto: validate` 가 VARCHAR 로 기대해 **실패**하고, `SqlTypes.OTHER` 는 validate 는 통과하나 **INSERT 시점에 pgjdbc 가 `bytea` 로 바인딩해 실패**한다. `SqlTypes.INET`(Hibernate 7.4.1) 만이 둘 다 만족. 대상 `audit_log.ip` |
| `boolean` | `boolean`(NN) / `Boolean`(nullable) | |
| `integer` | `int`(NN) / `Integer`(nullable) | |
| `smallint` | `Short` / `short` | |
| CHECK 로 값이 고정된 컬럼 | enum + `@Convert(converter = X.Db.class)` | `@Enumerated` **금지** — `Enum.name()` 이 대문자를 내보내 CHECK 를 위반한다 (Ruling 32) |

**감사 시각** — `created_at` 과 `updated_at` **둘 다** 있는 테이블만 `global/common/BaseTimeEntity` 를 상속한다.
⚠ **테이블마다 ERD 를 확인하라.** `signup_request`(`requested_at` 만) · `refresh_token`(`issued_at`) · `link_code`(`created_at` 만) 처럼 한쪽만 있거나 이름이 다른 테이블이 여럿이다. 억지로 상속시키면 `ddl-auto: validate` 가 없는 컬럼을 찾다 실패한다.

**PK** — 실측 확인됨: 36개 테이블이 **`bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`** 다. 엔티티는 `@GeneratedValue(strategy = GenerationType.IDENTITY)` 를 쓴다.

PK = FK 인 확장 테이블 3개(`academy_setting.academy_id` · `notification_setting.account_id` · `confirmed_route.run_id`)는 **생성 전략을 두지 않는다** — 값을 직접 넣는다.

⚠ **Task 1 보고서 §6 이 `academy_setting` 을 "JPA 에선 `@MapsId` 패턴 후보" 라고 적었는데, 이 규약이 이긴다 (조율자 Ruling 50).** §1 이 `@MapsId` 를 예외 없이 금지한다 — `@MapsId` 는 `@OneToOne`/`@ManyToOne` 을 전제하므로 모듈 간 엔티티 참조를 되살린다. 보고서의 그 문장은 규약 확정 전에 쓰인 제안이고 결정이 아니다.

## 4.1 CHECK 없이 "enum 처럼 보이는" 컬럼 (조율자 확정, Ruling 55)

값 목록이 DB CHECK 로 강제되지 **않는데** 개념상 값 도메인이 정해진 컬럼이 4개 있다. Task 3 이 실측으로 찾아냈다.

| 컬럼 | 쓸 enum | 소유 태스크 |
|---|---|---|
| `signup_request.requested_role` | `Role` | account |
| `emergency_alert.raised_by_role` | `ManagerRole` | exception |
| `notification_log.recipient_role` | `Role` | notification |
| `rider_status_history.from_status`(nullable) · `to_status` | `RiderStatus` | boarding |

**해당 enum 과 컨버터를 그대로 적용한다.** plain `String` 으로 두지 않는다 — 같은 개념에 어휘가 두 벌이 되고, 어느 쪽이 정본인지 판별할 수단이 부재해진다.

**단 필드 주석에 "이 컬럼에는 DB CHECK 가 부재해 스키마가 값을 보장하지 않는다" 를 반드시 남겨라.** 잘못된 값은 INSERT 를 통과하고 **읽을 때** `Enum.valueOf` 에서 터진다 — 쓴 사람이 아니라 읽는 사람이 실패를 본다.

⚠ **V1 에 CHECK 를 추가하지 마라.** `ERD` 가 이 4개에 CHECK 를 규정하지 않았고, 값 집합을 추론해 제약을 새로 만드는 것은 근거 없는 신규 설계다(`CLAUDE.md`). 값 집합 확정은 각 소유 Phase 의 일이다.

## 4.2 값이 같아도 엔티티가 다르면 enum 을 분리한다 (조율자 확정, Ruling 56)

`AcademyStatus`(`active`·`inactive`)와 `StaffStatus`(`active`·`inactive`)는 **값이 완전히 같지만 별도 타입으로 유지**한다.

근거 — 두 상태의 생명주기가 다르다(학원 비활성화는 로그인 유지 + 신규 가입 차단 · 관계자 비활성화는 재직 여부). 하나로 합치면 학원 상태를 받아야 할 자리에 관계자 상태를 넘겨도 컴파일이 통과하고, "왜 별개 컬럼인가" 를 매번 되짚어야 한다. 파일 하나 늘어나는 값보다 타입이 갈라 주는 값이 크다.

**값 목록형이 아닌 CHECK 는 enum 화하지 않는다** — 범위 제약(`BETWEEN`) · 계산식 제약 · 조건부 NOT NULL 은 전부 대상 밖이다.

## 4.3 `BaseTimeEntity` 는 이미 컬럼명이 명시돼 있다

Task 3 이 `@Column(name = "created_at", updatable = false)` · `@Column(name = "updated_at")` 을 붙였다(조율자 승인, Ruling 57). **상속만 하면 컬럼명이 맞으므로 하위 엔티티에서 다시 선언하지 마라.**

## 4.4 감사 필드가 아닌 시각 컬럼은 팩토리 파라미터로 받는다 (조율자 확정, Ruling 62)

`created_at`·`updated_at` **둘 다** 있는 테이블만 `BaseTimeEntity` 를 상속하고, 그 두 컬럼만 Spring Data auditing(`@CreatedDate`·`@LastModifiedDate`)이 채운다.

**그 밖의 시각 컬럼은 전부 평범한 필드**이고 **정적 팩토리 파라미터로 받는다.** `@CreatedDate`·`@LastModifiedDate`·`@EntityListeners` 를 개별 엔티티에 붙이지 마라.

대상 예 — `signup_request.requested_at` · `system_admin.created_at` · `refresh_token.issued_at`/`expires_at`/`revoked_at` · `guardian_student.linked_at`/`unlinked_at` · `link_request.requested_at`/`expires_at` · `verification_code.created_at`/`expires_at`/`consumed_at` · `link_code.created_at`/`expires_at`/`used_at` · `weekly_address.updated_at` · `run.confirmed_at`/`started_at`/`finished_at`/`canceled_at` · `assignment.assigned_at`/`acked_at` · `no_show_case.started_at`/`expires_at`/`resolved_at` · `emergency_alert.occurred_at`/`received_at` 등

**근거 3가지**
1. `JpaAuditingConfig` 의 Javadoc 이 auditing 범위를 **`BaseTimeEntity` 로 명시적으로 한정**한다
2. 이 컬럼들 대다수가 순수 생성 감사가 아니라 **도메인 값**이다 — `expires_at` 은 마감 계산의 입력이고, `requested_at` 은 3구간 판정의 기준이며, `revoked_at`·`consumed_at` 은 상태 전이의 결과다. auditing 은 이런 값을 다룰 수단이 부재하다
3. 한 엔티티 안에서 시각을 채우는 기제가 둘로 갈리면(일부는 auditing, 일부는 파라미터) **어느 필드가 어느 경로인지 매번 되짚어야 한다**

⚠ **팩토리 안에서 `OffsetDateTime.now()` 를 직접 호출하지 마라.** 횡단 규칙 1(`Clock` 빈 주입)이 금지한다 — 시각을 고정할 수 없으면 3구간·확정 배치·±3분 창을 테스트로 잡을 수단이 사라진다. **호출부가 `Clock` 에서 얻어 넘긴다.**

## 5. 패키지 · 파일

- `src.backend.<모듈>.entity.<클래스>` — `reference.md §3` 이 `entity/` 를 표준으로 명시. **`domain/` 을 쓰지 마라**
- **`package-info.java` 를 두지 마라** (`CLAUDE.md`)
- 클래스명은 테이블명의 UpperCamelCase (`run_rider` → `RunRider`)
- `@Table(name = "…")` 로 테이블명을 **명시**한다 (네이밍 전략에 의존하지 않는다)
- `@Column(name = "…")` 도 명시한다 — snake_case ↔ camelCase 변환을 전략에 맡기면, 전략이 바뀌는 순간 39개가 한꺼번에 깨진다

## 6. UNIQUE 제약을 엔티티에 적을 것인가

**적지 않는다.** `ddl-auto: validate` 는 UNIQUE 제약의 존재를 검증 항목으로 보지 않으므로 `@Table(uniqueConstraints=…)` 를 달아도 안 달아도 검증 결과가 같고, partial UNIQUE(`WHERE account_id IS NOT NULL`)는 애초에 JPA 애너테이션으로 표현할 수단이 없다. **제약의 정본은 `V1__init_schema.sql` 하나**이고, 엔티티에 반쪽만 적으면 "여기 적힌 것이 전부" 라는 오해를 만든다.

## 7. 설명 주석 (`reference.md §19`)

클래스 · public 메서드 · enum 에 설명 주석을 단다.

- **기본 한 문장.** 둘째 문장은 **다른 질문**에 답할 때만 붙인다 — 왜 이 형태인가 · 언제 교체되는가 · 무엇을 하지 않는가. 같은 질문을 두 번 답하면 줄인다
- **문장 수를 세어 결함으로 매기지 않는다** (판정 기준은 "두 문장이 같은 질문에 답하는가")
- 시그니처를 되풀이하는 주석은 쓰지 않는다 (`/** 학생 이름을 반환한다. */ getName()`)
- **틀린 주석은 없는 주석보다 나쁘다** — 이 저장소에서 "틀린 주석을 고치라" 는 지시를 이행하면서 정정 문장에 새 오기를 심은 사례가 있다

엔티티 클래스 주석에는 **그 테이블이 왜 존재하는가**를 적는다. `ERD §3.x` 의 "존재 이유" 문단이 그 재료다.

## 7.1 enum 상수 단위 주석 기준 (조율자 확정, Ruling 59)

**값 이름만으로 뜻이 서지 않는 상수에만** 한 줄 주석을 붙인다.

- 붙인다 — `NO_SHOW`(미승차인지 결석인지 이름만으로 미판별) · `AUTO_REJECTED`(누가·언제 거절했는지) · `CONFIRM_BATCH`(어느 배치인지) · `PREV_STOP_WAIT`
- 붙이지 않는다 — `MON`~`SUN` · `MALE`/`FEMALE` · `ANDROID`/`IOS`/`WEB` · `ACTIVE`/`INACTIVE`

`reference.md §19` 는 enum **타입**에 주석을 요구할 뿐 상수 단위를 요구하지 않는다. 기준이 없으면 파일마다 갈리므로 여기서 고정한다. **기존 31개 파일을 이 기준으로 소급 정리하지 마라** — 앞으로 만들 것에만 적용된다.

## 8. 하지 않는 것

- 리포지토리 · 서비스 · 컨트롤러 · DTO
- 상태 전이 메서드 · 정책 판정 · 계산식
- 사양에 없는 필드 · 편의 메서드 · 역정규화
- `V1__init_schema.sql` · `application.yml` 수정 (스키마 결함은 **보고만**)
- `docs/` 아래 사양 문서 수정
