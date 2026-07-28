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
└── infrastructure/
```

외부 연동은 `infrastructure` 아래에 둔다. 엔티티 패키지 이름은 `entity/`가 표준이다(현재 코드 기준 — notification·routing 두 모듈만 역사적 이유로 `domain/`을 쓰고 있으며, 일괄 rename은 범위 밖으로 확정됨. `PROJECT_MASTER_PLAN.md` §12.3 참조).

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

## 7. CQRS 원칙

- **Command**: 생성 · 수정 · 삭제
- **Query**: 조회 전용

Command는 Query를 호출하지 않는다.

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
