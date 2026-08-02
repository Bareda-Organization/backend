# Backend Architecture & Development Convention (v1.0)

> Claude 참조용 컨벤션 소스(토큰 효율을 위해 Markdown 유지). 사람이 브라우저로 볼 땐 같은 원칙을 시각화 포함해 렌더링한 `CODE_CONVENTIONS.html`을 본다 — 두 문서는 원칙은 동일하고 매체만 다르다.

목표: 변경에 강한 구조 · 기능 추가 시 기존 코드 수정 최소화(OCP) · 테스트 용이성 · MSA 전환 용이성 · 높은 응집도·낮은 결합도

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

외부 연동은 `infrastructure` 아래에 둔다. 엔티티 패키지 이름은 `entity/`가 표준이다(현재 코드 기준 — notification·routing 두 모듈만 역사적 이유로 `domain/`을 쓰고 있으며, 일괄 rename은 범위 밖으로 확정됨. `PROJECT_MASTER_PLAN.md` §12.3 참조).

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
