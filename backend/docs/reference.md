# Backend Architecture & Development Convention (v1.0)

> Claude 참조용 컨벤션 소스(토큰 효율을 위해 Markdown 유지). 사람이 브라우저로 볼 땐 같은 원칙을 시각화 포함해 렌더링한 `CODE_CONVENTIONS.html`을 본다 — 두 문서는 원칙은 동일하고 매체만 다르다.

목표: 변경에 강한 구조 · 기능 추가 시 기존 코드 수정 최소화(OCP) · 테스트 용이성 · MSA 전환 용이성 · 높은 응집도·낮은 결합도 · **읽는 사람이 한 번에 이해하는 코드**(§19 설명 주석 · §20 SRP·클린 코드)

## 1. 설계 원칙

- SOLID 원칙 준수
- DIP(의존관계 역전 원칙) 적용
- Event Driven Architecture 우선
- CQRS 적용 가능한 구조 유지
- 구현보다 인터페이스에 의존
- 변경 가능성이 있는 부분만 추상화

## 2. spec / impl 사용 기준

모든 클래스에 인터페이스를 만드는 게 목적이 아니다. 판단 기준은 하나뿐이다 — **"구현이 변경될 가능성이 있는가?"**

**반드시 spec을 만드는 경우**: 외부 시스템 연동 · 전략 패턴 · 여러 구현체가 존재하거나 존재할 가능성이 있는 경우 · Mock 구현이 필요한 경우 · 추후 MSA로 분리될 가능성이 있는 경우

예: `LocationSource`, `NotificationSender`, `EtaService`, `RouteEngine`, `SmsSender`, `PushSender`, `StorageService`, `ImageUploader`

**spec을 만들지 않는 경우**: 단순 CRUD 서비스. 구현이 하나뿐이고 변경 가능성이 거의 없으면 인터페이스를 만들지 않는다.

예: `StudentService`, `BusService`, `TenantService`

## 3. 패키지 구조

모듈당 표준 레이아웃:

```
location/
├── command/
├── query/
├── entity/
├── event/
├── projection/
├── repository/
├── dto/
├── controller/
├── access/         # 선택
└── infrastructure/
```

외부 연동은 `infrastructure` 아래에 둔다. 엔티티 패키지 이름은 `entity/`가 표준이다(현재 코드 기준 — notification·routing 두 모듈만 역사적 이유로 `domain/`을 쓰고 있으며, 2026-08-24 방향 전환으로 도메인 모듈을 재작성하므로 **새 모듈은 예외 없이 `entity/` 를 쓴다**).

`access/`는 **선택** 패키지다. "이 사용자가 이 리소스를 만질 수 있는가" 판정만 담으며, 같은 판정이 2곳 이상에서 필요할 때만 만든다.
`query/`가 아니라 `access/`에 두는 이유: 호출자가 Command 서비스인 경우가 많아 `query/`에 두면 §7이 금지한 "Command가 Query를 호출"이 새로 생긴다.
`global/`이 아니라 판정 대상을 소유한 도메인에 두는 이유: `global`은 모든 모듈이 의존하는 바닥이라 특정 도메인 Repository를 참조하면 의존이 역전된다.
형태 2가지 — 의존 없으면 `public final class` + `static`(`bus/access/BusCrewGuard`), Repository가 필요하면 스프링 빈(`student/access/GuardianAccess`). `global/tenant/TenantGuard`는 도메인 무관이라 `global`에 있는 예외(static).

같은 이유로 **여러 모듈이 공유하는 순수 조회 규칙**도 `query/` 밖에 둔다 — 호출자에 Command 서비스가 섞이는 순간 `query/`는 §7 위반을 만든다. 판정이면 `access/`, 명단·집계 같은 읽기 규칙이면 그 이름의 패키지를 만든다(`attendance/roster/ActiveRosterReader` = "당일 실제 명단" 규칙, 기사 앱·노선 계산·시뮬레이션 3곳이 공유). 표준 레이아웃에 항상 있는 패키지가 아니라 필요한 모듈에만 둔다.

`global/security/authz/`는 **인가 어휘**를 모아 둔 곳이다 — permission 문자열 상수(`Permissions`), 역할→permission 부여표(`RolePermissions`), 그리고 컨트롤러가 실제로 붙이는 메타 애너테이션 20개(`@CanManageStudents` 등). **컨트롤러는 역할 문자열을 쓰지 않는다** — `@PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")` 대신 "무엇을 할 수 있는가"로 이름 붙은 애너테이션을 쓰고, 어느 역할이 그 권한을 갖는지는 부여표 한 곳에서만 정한다. 그래야 새 역할 추가가 컨트롤러 14개가 아니라 부여표 1파일로 끝난다(`ControllerAuthorizationConventionTest`가 되돌아가는 것을 막는다).

`access/`와 달리 `global/` 아래인 이유: `access/`는 "이 사용자가 **이 리소스**를 만질 수 있는가"라서 판정 대상 엔티티를 소유한 도메인에 속하지만, `authz/`의 판정 대상은 특정 도메인이 아니라 **인가 어휘 자체**다. 어떤 도메인 Repository에도 의존하지 않고 14개 도메인 컨트롤러가 전부 참조하므로 `global/tenant/TenantGuard`와 같은 근거로 `global`에 둔다.

**부여표 우변에 `ROLE_`을 쓰지 않는다**(`ROLE_A > ROLE_B` 형태의 역할→역할 간선 금지). 넣으면 "이 사람은 ACADEMY_ADMIN인가"에 애너테이션 계층은 예, `AuthUser.hasRole()`을 보는 서비스 계층은 아니오라고 답해 두 계층이 갈린다. 게다가 그 문법을 한 줄이라도 허용하면 다음 사람이 `ROLE_ACADEMY_ADMIN > ROLE_ATTENDANT`를 추가하는 순간 선탑자 전용 권한이 관리자에게 조용히 열린다.

## 4. spec / impl 구조

```
notification/infrastructure/
├── spec/
│   └── NotificationSender
└── impl/
    ├── LogNotificationSender
    └── WebSocketNotificationSender
```

Controller는 spec만 의존한다.

```java
private final NotificationSender notificationSender;
```

## 5. 구현체 교체 원칙

새로운 구현체를 추가할 때 기존 구현을 수정하지 않는다.

예: `MockLocationSource` → `PhoneGpsSource` → `BusGpsSource` → `BeaconLocationSource`로 교체되어도 `LocationService`는 수정하지 않는다.

## 6. Event 기반 개발

서비스 간 직접 호출을 최소화한다.

- 권장: `RideEvent` → Kafka → `Notification`
- 금지: `RideService` → `NotificationService` → `HistoryService` → `StatisticsService` (직접 체이닝 호출)

**예외 — 호출 결과가 동기 응답 계약의 일부일 때만** 모듈 간 직접 호출을 허용한다. 결과가 그대로 응답 필드가 되는 호출은 이벤트로 돌리면 그 필드가 항상 null이 되어 API 의미가 바뀐다(계약 변경). 이 예외를 쓸 땐 두 가지를 지킨다 — ① 호출 지점에 "왜 이벤트가 아닌가"를 주석으로 남긴다, ② 응답에 필요한 값만 동기로 받고 후속 파급(알림·통계 등)은 이벤트로 흘린다.

예: `LocationChangeCommandService` → `RoutingCommandService.republishForBus` — 반환된 planId가 `LocationChangeRequestResponse.appliedPlanId`이고, 기사 알림은 그 안에서 `RoutePlanPublishedEvent`로 나간다.

## 7. CQRS 원칙

- **Command**: 생성 · 수정 · 삭제
- **Query**: 조회 전용

Command는 Query를 호출하지 않는다.

Command에 조회가 필요하면 Query 서비스를 부르는 대신 §3의 공유 읽기 계층(`access/`·`roster/`)으로 내린다. 그 규칙을 Query 서비스도 함께 쓰면 "명단이 두 벌"이 되는 것도 막힌다.

**예외 — 계산 결과가 Command의 판정·저장 입력일 때만** Command가 Query 서비스를 직접 호출할 수 있다. 판별 기준 3개를 모두 만족해야 한다: ① 단순 조회가 아니라 외부 API·최적화가 걸린 **무거운 계산**이고 ② 그 결과가 Command의 판정 근거이자 저장 대상이며 ③ 같은 계산을 Query 진입점도 그대로 노출한다. 이때 공유 읽기 계층으로 내리면 계산이 두 벌이 되어 외부 API 호출이 배로 늘고, 화면이 본 값과 저장된 값이 갈라진다. 허용하는 대신 지킬 것 — ⓐ 그 Query 서비스는 아무것도 저장하지 않는다(`@Transactional(readOnly = true)` 유지) ⓑ 인가를 누가 책임지는지 양쪽 javadoc에 적는다(Query 진입점과 내부 호출의 인가 주체가 다르다).

현재 해당하는 곳은 `RoutingCommandService`·`LocationChangeCommandService` → `RoutePlanSimulationService`(시뮬레이션 델타) **2곳뿐이며, 이 예외에 기대는 새 호출을 늘리지 않는다.**

## 8. Projection 원칙

Projection은 읽기 모델만 생성한다. 비즈니스 로직을 작성하지 않는다.

예: `LocationUpdatedEvent` → `CurrentLocationProjection` → Redis

## 9. Repository 규칙

Repository는 JPA 접근만 수행한다. 비즈니스 로직 금지.

- 금지: `repository.updateAndNotify();`
- 권장: `repository.save();`

## 10. DTO 규칙

`Controller` → Request DTO → `Service` → Response DTO. Entity를 직접 반환하지 않는다.

## 11. Controller 규칙

Controller는 요청 검증 · 인증 사용자 확인 · Service 호출만 수행한다. 비즈니스 로직 금지.

## 12. Service 규칙

Service는 비즈니스 규칙만 담당한다. HTTP·Redis·Kafka·JPA를 직접 알지 않는다. 필요한 경우 Port(spec)를 사용한다.

## 13. Infrastructure 계층

외부 기술은 Infrastructure에 위치한다.

예: `KafkaPublisher`, `RedisLocationRepository`, `FcmSender`, `GrpcEtaClient`, `S3Uploader`

비즈니스 계층은 구현을 모른다.

## 14. Event 규칙

Event는 과거형으로 작성한다.

예: `LocationUpdatedEvent`, `RideCompletedEvent`, `StudentBoardedEvent`, `SosTriggeredEvent`

Command·Query를 Event 이름에 사용하지 않는다.

## 15. 변경 가능한 기술은 Port로 추상화한다

| Port | 구현 후보 |
| --- | --- |
| `LocationSource` | Mock, Phone, Bus, Beacon |
| `NotificationSender` | Log, WebSocket (후보: FCM, SMS, Kakao, Email) |
| `MapRouteClient` | OSRM, Naver (후보: Google) — 실도로 경로/거리 조회 |
| `RouteEngine` | Heuristic(sweep+NN+2-opt) (후보: 외부 최적화 엔진) — 방문 순서 결정 |
| `EtaService` | Internal, gRPC, AI ETA |
| `StorageService` | Local, S3, MinIO |

주의: `MapRouteClient`(외부 지도 API로 실도로 경로를 얻는 포트)와 `RouteEngine`(정차 순서를 계산하는 알고리즘 포트)은 서로 다른 포트다 — OSRM/네이버 스위치는 `MapRouteClient` 쪽이다.

## 16. 새로운 기능 추가 원칙

새로운 기능을 추가할 때 기존 코드를 수정하지 않는 것을 목표로 한다.

예: AI 분석 추가 시 기존 `LocationUpdatedEvent`는 그대로 두고 새로운 `AiAnalysisProjection`만 추가한다 — Command 수정 없음.

## 17. MSA 전환 원칙

분리 가능성이 있는 기능은 처음부터 Port를 정의한다.

대상: ETA · Routing · Notification · Location Source · Storage · OCR · AI

이후 REST → gRPC → 별도 서비스로 변경되어도 비즈니스 코드는 수정하지 않는다.

## 18. 최종 개발 원칙

1. 변경 가능성이 있는 부분만 인터페이스(spec)로 추상화한다.
2. 서비스 간 통신은 Event(Kafka)를 우선하고, 즉시 응답이 필요한 경우에만 gRPC를 사용한다.
3. CQRS를 적용하는 모듈은 Command, Query, Projection을 명확히 분리한다.
4. 외부 기술(FCM, Redis, Kafka, gRPC, S3 등)은 Infrastructure 계층에 위치시키고 Port를 통해 접근한다.
5. 새로운 기능은 기존 코드를 수정하는 대신 새로운 구현체, Consumer 또는 Projection을 추가하는 방식으로 개발한다.
6. 모든 설계는 Open-Closed Principle(확장에는 열려 있고 변경에는 닫혀 있음)을 목표로 한다.

## 19. 설명 주석 — 클래스 · 메서드 · 기능마다 한 줄

**모든 클래스 · public 메서드 · enum · 이벤트 · 포트 인터페이스에 한 줄 설명을 붙인다.** 분량은 **한 문장**이고, 길어지면 그 자체가 책임이 둘이라는 신호다(§20).

| 대상 | 붙이는 것 | 형식 |
| --- | --- | --- |
| 클래스 · 인터페이스 | **무엇을 담당하는가** + 왜 이 계층에 있는가 | 클래스 선언 위 `/** … */` 한 문장 |
| public 메서드 | **무엇을 보장하는가** — 입력·출력이 아니라 사후 조건 | 메서드 위 `/** … */` 한 문장 |
| 상태 전이 메서드 | 허용 전이와 거절 조건 | `/** … 아니면 {@link BusinessException}. */` |
| enum 상수 | 그 값이 뜻하는 실제 상황 | 상수 오른쪽 줄 주석 또는 `/** … */` |
| 이벤트 | **언제 발행되는가** (커밋 시점 기준) | 레코드 선언 위 한 문장 |
| 포트(`spec`) | 교체 축이 무엇인가 · 구현체 후보 | 인터페이스 위 한 문장 |
| private 메서드 | 이름으로 설명되면 **부재**. 계산 근거가 필요할 때만 | — |

```java
/** 확정 노선 1건의 방문 순서를 정한다. 알고리즘 교체 축이라 spec/impl 로 가른다. */
public interface RouteOptimizer {

    /** 승하차지 목록을 방문 순서대로 재배열한다. 입력 순서에 의존하지 않는다. */
    List<Stop> order(List<Stop> stops, Coordinate origin);
}
```

**시그니처를 한국어로 되풀이하는 주석은 금지한다.** `/** 학생을 저장한다. */ void saveStudent(Student s)` 는 정보량이 0이고, 시그니처가 바뀌면 거짓말로 남는다. 적을 것이 시그니처뿐이면 **주석 대신 이름을 고친다.**

**주석이 사양을 복제하지 않는다** — 30분·±3분 같은 값은 주석에 적지 않고 규칙 ID(`C-04` · `RTE-02`)로 참조한다(`IMPLEMENTATION_PLAN §7` 규칙 18과 같은 이유).

⚠ 흔한 오해 — "주석이 많을수록 친절하다." 반대다. 주석은 코드와 함께 갱신되지 않으므로 **틀린 주석은 없는 주석보다 나쁘다.** 이름으로 말할 수 없는 것(왜 이 예외를 쓰는가 · 왜 이벤트가 아닌가)만 남긴다.

## 20. 가독성 · 유지보수성 — SRP 와 클린 코드

**모든 Phase 의 기본 채점 기준.** 동작이 맞아도 아래를 어기면 리뷰에서 되돌린다.

### 20.1 단일 책임 (SRP)

**클래스 하나가 바뀌는 이유는 하나다.** "그리고"로 이어 설명해야 하면 이미 둘이다.

| 신호 | 조치 |
| --- | --- |
| 클래스 설명(§19)에 "그리고 · 또한"이 필요 | 책임 단위로 분리 |
| 한 서비스가 조회와 상태 변경을 동시에 수행 | 이 문서 §7 CQRS 대로 Command / Query 로 분리 |
| 정책 판정이 서비스 안에 인라인 | `domain` 정책 객체로 추출 (`ARCHITECTURE §3.2`) |
| 컨트롤러가 DTO 조립 이상을 수행 | Service 로 이동 (§11) |
| 같은 판정이 2곳 이상에 복제 | `access/` 또는 공유 읽기 계층으로 승격 (§3) |

### 20.2 크기 기준

넘으면 **위반이 아니라 분리 검토 신호**다. 넘긴 채 두려면 왜 나눌 수 없는지를 §19 주석에 한 줄로 남긴다.

| 대상 | 기준 |
| --- | --- |
| 메서드 본문 | 20줄 |
| 클래스 | 200줄 |
| 메서드 파라미터 | 4개 (넘으면 `record` 파라미터 객체) |
| 중첩 깊이 (`if`·`for`·`try`) | 2단 |
| 응답 DTO 필드 | 10개 (`ARCHITECTURE §3.2.3`) |

### 20.3 작성 규칙

1. **이름이 곧 설명이다.** 축약어를 만들지 않는다 — `calcRtPlnV2` 가 아니라 `calculateRoutePlan`. 불리언은 `is`·`has`·`can` 으로 시작한다.
2. **조기 반환(guard clause)으로 중첩을 없앤다.** 실패·예외 조건을 위에서 걷어내고 정상 경로를 들여쓰기 0단에 둔다.
3. **매직 넘버·문자열 금지.** 정책 값은 코드 상수, 학원별 임계값은 DB (`IMPLEMENTATION_PLAN §7` 규칙 10).
4. **중복은 3번째에 추출한다.** 2번째까지는 우연일 수 있고, 이른 추상화는 서로 다른 두 규칙을 한 메서드에 묶어 한쪽만 바꿀 수 없게 만든다.
5. **한 메서드는 한 추상화 수준만 다룬다.** 정책 판정과 SQL 조립이 같은 메서드에 있으면 읽는 사람이 매번 계층을 오르내린다.
6. **`null` 을 반환하지 않는다.** 부재는 `Optional`, 목록은 빈 컬렉션.
7. **주석으로 코드를 남기지 않는다.** 죽은 코드는 삭제하고 git 이력에 맡긴다.
8. **테스트 이름은 문장으로 쓴다** — `확정_30분_전에_도래하면_confirmed_로_전이한다`. 무엇을 보장하는지 이름만 읽고 알 수 있어야 한다.

### 20.4 유지보수성 — 바꿀 때 손대는 곳의 수

**"이 값이 바뀌면 몇 파일을 고치는가"가 판정 기준이다.** 답이 2 이상이면 설계가 틀렸다.

| 바뀌는 것 | 고쳐야 하는 곳 |
| --- | --- |
| 역할이 하나 늘어남 | `RolePermissions` 부여표 1파일 (§3) |
| 지도 API 공급자 교체 | `MapRouteClient` 구현체 1개 + 설정 1곳 (§15) |
| 알림 채널 추가 | `PushSender` 구현체 1개 (§16) |
| 정책 상수 변경 | 상수 클래스 1곳 (`IMPLEMENTATION_PLAN §7` 규칙 10) |
