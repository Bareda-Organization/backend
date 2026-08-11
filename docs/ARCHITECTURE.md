# 학원 통학버스 통합관리 — 아키텍처

**한 줄 요약:** Flutter 앱 하나(기사·관리자 겸용)가 nginx를 거쳐 Spring Boot 단일 서버에 붙고, 서버는 PostgreSQL(영속) · Redis(최신 좌표) · Kafka(도메인 이벤트) 세 인프라를 나눠 쓰며, 실시간 전달은 STOMP WebSocket과 REST 폴링이 혼재한다.

이 문서는 **시스템 전체가 어떻게 맞물리는지**를 다룬다. 백엔드 한 모듈의 내부 구현·클래스 단위 상세는 다루지 않는다.

| 문서 | 범위 | 관계 |
|---|---|---|
| **ARCHITECTURE.md** (이 문서) | 백엔드 + 프론트엔드 + 인프라를 아우르는 상위 뷰. 경계·데이터 흐름·설계 제약 | — |
| `backend/docs/BACKEND_ARCHITECTURE.html` | **백엔드 한정 심화판**. 모듈 내부 구조·클래스 다이어그램 | 존치. 이 문서와 중복 서술하지 않는다 |
| `backend/docs/reference.md` | 백엔드 코드 컨벤션 원칙(spec/impl 판단기준·CQRS·Event 규칙) | 3장이 이 원칙의 **실제 준수 현황**을 기록한다 |
| `frontend/docs/FLUTTER_CODE_CONVENTIONS.md` | 프론트 코드 컨벤션 | 4장이 이 원칙의 실제 준수 현황을 기록한다 |

자매 문서: [PRODUCT_SPEC.md](./PRODUCT_SPEC.md) · [USER_FLOWS.md](./USER_FLOWS.md) · [API_SPEC.md](./API_SPEC.md)

> **서술 규칙**: 이 문서의 모든 수치·구조는 코드 실측값이며 출처 파일을 괄호로 병기했다. 근거를 찾지 못한 항목은 `※ 미확인:`으로 표시했다. **"설정은 있으나 실제로는 쓰이지 않는" 항목은 그렇게 명시**했다 — 있는 것처럼 읽히면 안 되기 때문이다.

---

## 1. 전체 구성 — 앱(Flutter) · API(Spring) · 인프라

### 1.1 런타임 구성 요소

브라우저·앱이 닿는 입구는 **nginx 하나뿐**이다. 백엔드·프론트엔드 컨테이너는 호스트에 포트를 열지 않는다(`docker-compose.yml:74-77`, `:100-101`).

| 계층 | 구성 요소 | 역할 | 호스트 노출 |
|---|---|---|---|
| 클라이언트 | Flutter 앱 (Web 빌드가 컨테이너에 탑재) | 기사 2화면 + 관리자 4화면 | — |
| 엣지 | nginx `proxy` | `/` → 프론트, `/api`·`/ws`·`/swagger-ui` → 백엔드 | **`80:80`** (유일한 입구) |
| 애플리케이션 | Spring Boot `backend` | REST 64여 개 · STOMP · 스케줄러 4개 · Kafka 프로듀서/컨슈머 | `expose: 8080`만 |
| 영속 | PostgreSQL 16 | 테이블 16개. 스키마는 Flyway 관리 | `5432:5432` (DB 툴용) |
| 캐시 | Redis 7 | **학생 최신 좌표 1건**(TTL 9초) 전용 | `6379:6379` |
| 메시징 | Kafka 3.9 (KRaft) | 도메인 이벤트 15종 릴레이 | `29092:29092` |

인프라 포트를 호스트에 남긴 이유는 "인프라만 컨테이너로 띄우고 백엔드는 IDE에서 `./gradlew bootRun`으로 돌리는 개발 방식"을 위해서다(`docker-compose.yml:11-12`).

### 1.2 요청이 흐르는 세 갈래

같은 앱이 목적에 따라 세 가지 경로를 동시에 쓴다. 이게 이 시스템의 전체 그림이다.

1. **일반 REST** — 앱 → nginx `/api/` → Spring Controller → Service(command/query) → Repository → PostgreSQL → `ApiResponse` 래퍼로 응답
   - 프론트가 실제로 호출하는 REST 엔드포인트는 **22개**, 백엔드가 제공하는 REST는 **68개**(+STOMP 1) → **사용률 약 32%**. 구현은 됐지만 화면이 없는 API가 대부분이다
2. **실시간 push (WebSocket)** — 도메인 이벤트 → Kafka → 컨슈머 → STOMP destination → 앱이 구독
   - 실제로 이 경로를 타는 건 **알림뿐**이다. 프론트의 STOMP 구독 destination은 2개(`/topic/tenant/{id}/notifications`, `/user/queue/notifications`)이고 위치 구독은 코드에 없다(`stomp_gateway_impl.dart`, `notification_repository.dart:23,27`)
3. **폴링** — 관제 화면이 3초마다 `GET /api/locations/buses` 재조회(`bus_monitor_controller.dart:93`)
   - 버스 위치는 이벤트를 발행하지 않아 push 경로가 없기 때문이다(8.2절)

### 1.3 설계 의도 — 왜 이렇게 갈랐는가

- **nginx 단일 입구**: 프론트와 API가 같은 출처(:80)가 되어 브라우저 CORS가 아예 발생하지 않는다(`docker-compose.yml:9`). 프론트 빌드 인자 `API_BASE_URL=""`로 상대경로를 쓰므로 주소가 localhost든 배포 도메인이든 **같은 번들이 그대로 동작**한다(`:92-95`).
- **Redis를 좌표 전용으로 한정**: 위치는 "최신 1건"만 의미가 있고 이력은 필요 없다고 판단한 결과다. 그래서 RDB가 아니라 TTL 있는 Redis에 넣는다(8.1절 6단계).
- **Kafka를 중간에 둔 이유**: 승하차·SOS·미승차 같은 사건이 발생했을 때 발행 모듈이 알림 모듈을 직접 호출하지 않게 하려는 것이다(`reference.md` §6 "서비스 간 직접 호출 최소화"). 실제로 `RideEventCommandService`는 `NotificationService`를 모른다.

---

## 2. 기술 스택 — 실측 버전

### 2.1 백엔드 (`backend/build.gradle`)

| 항목 | 값 | 비고 |
|---|---|---|
| Spring Boot | **4.1.0** (`:3`) | 웹 스타터는 신형 아티팩트명 `spring-boot-starter-webmvc` — 구버전 `-web`이 아니다 |
| Java toolchain | **25** (`:13`) | |
| Gradle group | `src`, 기본 패키지 `src.backend` (`:7`) | 비관례적. 새 클래스는 반드시 이 하위에 둬야 컴포넌트 스캔이 닿는다 |
| DB 드라이버 | `org.postgresql:postgresql` (runtimeOnly) | |
| 스키마 관리 | `spring-boot-starter-flyway` + `flyway-database-postgresql` | `ddl-auto: validate` (10장) |
| 보안 | `spring-boot-starter-security` + jjwt **0.12.6** | |
| 메시징·캐시 | `spring-boot-starter-kafka`(신형 이름), `-data-redis`, `-websocket` | |
| 외부 호출 | `spring-boot-starter-webclient` (reactor-netty) | routing 모듈이 `.block()`으로 동기 호출 |
| API 문서 | springdoc **3.0.3** | 3.x가 Spring Boot 4(Jackson 3) 지원 최초 라인 |
| JSON | **Jackson 3**(Boot 4 기본). Jackson 2는 `jackson-datatype-jsr310`만 runtimeOnly로 잔존 | spring-kafka 직렬화가 아직 Jackson 2를 쓰기 때문 |

⚠ **`spring-boot-starter-actuator`가 없다.** `SecurityConfig.java:61`이 `/actuator/health`를 permitAll 하지만 **해당 엔드포인트는 존재하지 않는다**(grep 무매칭). 헬스체크 URL로 쓸 수 없다.

컨테이너 이미지는 멀티스테이지 — `eclipse-temurin:25-jdk`로 `bootJar` 빌드 후 `eclipse-temurin:25-jre`에 jar만 복사(`backend/Dockerfile`).

### 2.2 프론트엔드 (`frontend/pubspec.yaml`)

| 항목 | 버전 | 채택 이유(주석 실측) |
|---|---|---|
| Dart SDK | `^3.12.0` | Docker 빌드 이미지(`ghcr.io/cirruslabs/flutter:stable`)의 Dart가 3.12.0이라 하한을 낮춰 맞춤 |
| 상태관리 | `flutter_riverpod ^3.4.1` | **코드생성(`riverpod_generator`) 미사용** — Flutter 3.44.8 번들 analyzer와 충돌. provider를 손으로 선언한다 |
| 라우팅 | `go_router ^17.3.0` | `redirect` 훅 한 곳에서 역할 분기·미로그인 차단 |
| HTTP | `dio ^5.11.0` | interceptor로 JWT 부착 + 401 자동 refresh를 한 군데로 모음 |
| 직렬화 | `freezed_annotation ^3.1.0`, `json_annotation ^4.12.0` | `ApiResponse<T>` 제네릭 언랩을 타입 안전하게 |
| 토큰 보관 | `flutter_secure_storage ^10.3.1` | 모바일 Keychain/Keystore, 웹은 WebCrypto |
| 실시간 | `stomp_dart_client ^3.0.1` | 백엔드가 **SockJS 미사용 순수 STOMP**라 그대로 맞음 |
| 지도 | `flutter_map ^8.3.1` + OSM 타일 | `flutter_naver_map`이 **Web 미지원**이라 채택 불가 |

지도는 네이버/구글이 아니라 **OpenStreetMap 타일**이다(`flutter_map_adapter.dart:204`). 단, 서버 측 경로 계산은 네이버 Directions를 기본값으로 쓴다(9장·12장) — 클라이언트 지도와 서버 경로 API의 공급자가 서로 다르다.

### 2.3 인프라 이미지

`postgres:16` · `redis:7` · `apache/kafka:3.9.0`(KRaft, Zookeeper 없음) · `nginx:alpine` (`docker-compose.yml`).

---

## 3. 백엔드 모듈 구조

### 3.1 도메인 모듈 14개 + global 인프라

패키지 최상위는 업무 단위로 갈라져 있다(기술 단위 `controller/`·`service/`로 가르지 않았다 — 기능 하나를 고칠 때 한 폴더만 열면 되게 하려는 의도다).

| 모듈 | 책임 | 대표 엔티티 |
|---|---|---|
| `auth` | 로그인·회원가입·토큰 재발급(JWT 발급 진입점) | (엔티티 없음, User 재사용) |
| `user` | 계정(User)·학원별 역할 멤버십(UserTenantRole)·멤버 관리 | `User`, `UserTenantRole` |
| `tenant` | 학원(테넌트) 등록·조회·위치 갱신 | `Tenant` |
| `student` | 학생 등록·버스/정류장 배정·보호자 연결·하차지 변경 | `Student`, `StudentGuardian` |
| `bus` | 버스 등록·기사/노선 배차 | `Bus` |
| `route` | **정적 노선·정류장 마스터**(레거시 성격, 12장 R6) | `Route`, `Stop` |
| `routing` | **일자별 경로 최적화·배차·배포**, 외부 지도 API 연동 | `RoutePlan`, `RoutePlanStop` |
| `drivesession` | 기사 운행 세션 시작/종료·로스터·APPROACH/NO_SHOW 판정 | `DriveSession` |
| `rideevent` | 승·하차·인계 기록 생성·정정·계층별 조회 | `RideEvent` |
| `location` | 실시간 위치 수집(학생·버스)·저장·WebSocket push·연결끊김 판정 | **엔티티 없음**(RDB 미영속) |
| `attendance` | 결석·휴원 등 출결 예외 신청·승인 | `AttendanceException` |
| `schedule` | 등하원 시간 변경 요청 신청·승인 | `ScheduleChangeRequest` |
| `sos` | 학생 긴급 SOS 발동·확인·에스컬레이션 | `SosEvent` |
| `notification` | 도메인 이벤트 → 알림 로그 변환·발송, 알림 임계값 상수 보관 | `NotificationLog` |
| `global` | 공통 인프라 — 보안·에러·이벤트 포트·Kafka·Redis·WebSocket 설정·`TenantGuard`·push 대상 해석 | **엔티티 없음**(`BaseTimeEntity`는 공통 상위 클래스) |

`location`에 엔티티가 없는 게 이 구조의 특징이다 — 위치는 **RDB에 남기지 않고** Redis/메모리에 최신 1건만 둔다.

### 3.2 계층 규칙 — controller / command·query / repository / entity / dto

모듈 하나의 표준 레이아웃(`reference.md` §3):

| 패키지 | 담는 것 |
|---|---|
| `controller/` | REST(+STOMP) 진입. 검증·인증주체 확인·서비스 호출만 |
| `command/` | 쓰기 서비스 (CQRS의 C) |
| `query/` | 읽기 서비스 (CQRS의 Q) |
| `repository/spec/` | Spring Data 인터페이스 |
| `entity/` (일부 모듈은 `domain/`) | JPA 엔티티. **상태 전이 규칙이 여기 산다** |
| `dto/` | Request/Response record |
| `event/` | 도메인 이벤트 record |
| `infrastructure/` | 외부 기술 어댑터 (필요한 모듈만) |
| `projection/` | 이벤트를 읽기 모델로 투영 (`location`만) |

엔티티 패키지 이름은 `entity/`가 표준이며, `notification`·`routing` 두 모듈만 역사적 이유로 `domain/`을 쓴다(일괄 rename은 범위 밖으로 확정).

각 계층이 지켜야 할 선(`reference.md` §9~§13):

| 계층 | 해도 되는 일 | 하면 안 되는 일 |
|---|---|---|
| Controller | 요청 검증(`@Valid`), `@AuthenticationPrincipal AuthUser` 수령, 서비스 호출 | 비즈니스 로직 |
| Service(command/query) | 비즈니스 규칙, 권한·격리 판단, 이벤트 발행 | HTTP·Redis·Kafka·JPA 구현을 직접 아는 것(Port로 접근) |
| Repository | JPA 접근만 | 비즈니스 로직 |
| Entity | **상태 전이 검증**(예 `SosEvent.acknowledge()`) | — |
| DTO | 요청/응답 매핑 | 엔티티를 그대로 노출하는 것 |

**상태 전이 검증이 서비스가 아니라 엔티티 안에 있다**는 게 이 코드베이스의 일관된 선택이다. 예: `DriveSession.end()`가 IN_PROGRESS가 아니면 `CONFLICT`를 던지고(`DriveSession.java:79-85`), 커맨드 서비스는 권한만 확인한 뒤 이 메서드를 호출한다. 상태 규칙을 여러 서비스가 각자 복사하지 않게 하려는 의도다.

**spec/impl 분리는 "구현이 바뀔 가능성이 있는가" 하나로만 판단한다**(`reference.md` §2). 실제로 분리한 곳은 4군데뿐이다:

| 모듈 | 분리 대상 | 포트 → 구현체 |
|---|---|---|
| `notification` | command·query·infrastructure **3계층 모두** (유일) | `NotificationSender` → `LogNotificationSender`, `WebSocketNotificationSender` |
| `location` | repository만 | `LocationRepository` → `InMemoryLocationRepository`, `RedisLocationRepository`(`@Primary`) |
| `routing` | engine·infrastructure | `RouteEngine` → `HeuristicRouteEngine` / `BusAssigner` → `SweepAssigner` / `MapRouteClient` → `NaverMapRouteClient`, `OsrmMapRouteClient` |
| `location/source` | 위치 소스 | `LocationSource` → `MockLocationSource`, `PhoneGpsSource`, `MockBusLocationSource`, `DriverGpsSource` |

나머지 10개 모듈의 command/query는 **인터페이스 없이 concrete 클래스 하나**다. 코드 주석이 "단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다"라고 이유를 명시한다(예 `bus/command/BusCommandService.java:23`).

> **흔한 오해:** "spec/impl로 나눠야 좋은 설계"가 아니다. 구현이 하나뿐이고 바뀔 이유가 없는데 인터페이스를 만들면 파일만 두 배가 되고 추적만 어려워진다. 이 저장소는 그래서 **의도적으로 대부분을 나누지 않았다.**

단, **repository는 구현체가 없어도 전부 `repository/spec/`에 둔다**(예 `bus/repository/spec/BusRepository.java`). `location`만 예외적으로 `repository/impl/`에 실제 구현 클래스(Redis/InMemory)를 갖는다.

### 3.3 CQRS — 실제 분리 실태

**14개 도메인 모듈 전부가 `command` + `query` 패키지를 갖는다**(`find` 실측). 즉 CQRS 분리는 예외 없이 적용됐다.

- `command` = 생성·수정·삭제, `query` = 조회 전용. **Command는 Query를 호출하지 않는다**(`reference.md` §7).
- 다만 이것은 **"읽기 모델·쓰기 모델 DB를 분리한 CQRS"가 아니다.** 같은 JPA 엔티티·같은 테이블을 두 서비스가 나눠 쓸 뿐이다. 읽기 전용 별도 저장소로 간 것은 위치(Redis) 하나뿐이고, 그것도 `projection/` 패키지(`location/projection/LocationPushConsumer.java`)를 통해 Kafka 이벤트를 소비하는 형태다.
- CLAUDE.md가 언급하는 `service/{spec,impl}` 레이아웃은 **이 저장소의 실제 컨벤션이 아니다** — 실제는 `command`/`query`다. `user/service/spec`·`user/service/impl` 디렉터리가 남아 있지만 **`.java` 파일이 0개인 빈 디렉터리**다(스캐폴딩 잔재).

### 3.4 모듈 간 의존 방향 · 순환 의존

`import src.backend.*` 집계 실측:

| 모듈 | 의존하는 모듈 |
|---|---|
| `tenant` | global |
| `user` | global, tenant |
| `auth` | global, tenant, user |
| `attendance` / `schedule` | global, student |
| `rideevent` | bus, global, student |
| `location` | bus, global, route, student |
| `route` | bus, global, student, tenant |
| `student` | bus, global, route, tenant, user |
| `bus` | global, route, student, tenant, user |
| `sos` | global, notification, student |
| `routing` | attendance, bus, global, schedule, student, tenant |
| `drivesession` | attendance, bus, global, notification, rideevent, routing, student |
| `notification` | drivesession, global, location, rideevent, routing, schedule, sos, student |
| `global` | student, user |

대체로 **아래에서 위로(기반 → 응용)** 흐른다: `tenant → user → student/bus/route → rideevent/drivesession/routing → notification`. `notification`이 가장 많은 모듈을 의존하는 건 모든 이벤트의 종착지이기 때문이다.

**순환 의존 5건 (실측)** — 성격이 세 가지로 갈린다:

| 순환 | 성격 | 근거 |
|---|---|---|
| `bus` ↔ `route` | **구조적** — JPA 연관관계(`Bus.route`)와 조회 서비스가 모듈 경계에서 만남 | `Bus.java:19`, `RouteQueryService.java:8` |
| `bus` ↔ `student` | **구조적** — 동일 | `Student.java:16`, `BusQueryService.java:16-17` |
| `drivesession` ↔ `notification` | **상수 참조 1개 때문** — `NotificationThresholds` 하나를 가져다 쓰느라 생긴 역방향 | `DriveSessionCommandService.java:27` ↔ `DomainEventNotificationConsumer.java:7-8` |
| `sos` ↔ `notification` | **상수 참조 1개 때문** — 동일 | `SosCommandService.java:13` ↔ `DomainEventNotificationConsumer.java:19-20` |
| `global` ↔ `student`·`user` | **공통 인프라가 도메인을 앎** — `PushTargetResolver`가 학생을 조회하고 `AuthUser`가 `Role` enum을 씀 | `global/push/PushTargetResolver.java`, `global/security/AuthUser.java:13` |

주의: `drivesession`↔`notification`·`sos`↔`notification`은 **런타임 결합이 아니다.** 이벤트 흐름 자체는 Kafka로 단방향(발행 모듈 → notification)이고, 역방향은 컴파일 타임 상수 참조뿐이다. 그래도 모듈을 MSA로 떼어내려는 순간 걸림돌이 되므로 12장에 리스크로 남긴다.

---

## 4. 프론트엔드 구조

Flutter 단일 앱이 기사·관리자 두 역할을 모두 담는다. 로그인 후 JWT의 역할에 따라 라우터가 다른 셸로 보낸다(5.3절).

### 4.1 feature-first 레이어

폴더는 **기능 단위**로 먼저 가르고, 그 안을 계층으로 나눈다. **`features/` 이름은 백엔드 모듈명을 그대로 쓴다** — "이 화면이 어느 백엔드 모듈을 부르는지"를 폴더만 보고 알게 하려는 규칙이다(`FLUTTER_CODE_CONVENTIONS.md` §2).

- **`lib/main.dart`, `lib/bootstrap.dart`** — 진입점·전역 초기화·에러 핸들링·`ProviderScope` 구성(포트 override가 여기 모인다)
- **`lib/app/`** — 앱 셸. `router/`(go_router 정의·경로 상수·역할 리다이렉트), `theme/`(색·간격 토큰), `shell/`(기사·관리자 셸). **개별 기능 화면을 두지 않는다**
- **`lib/core/`** — 기술 계층 = 백엔드의 `infrastructure`
  - `api/` — `api_client`(dio 조립) · `api_response`(`{success,data,message}` 언랩) · `api_exception`(status → 앱 예외) · `interceptor/`
  - `ws/` `storage/` `map/` `location/` — 각각 `spec/`(포트) + `impl/`(구현)
  - `ui/`(공용 로딩·에러·빈상태 위젯) · `util/`(외부 의존 없는 순수 함수)
- **`lib/features/<백엔드 모듈명>/`**
  - `data/dto/` + `data/*_repository.dart` — 서버 계약 그대로 + HTTP 호출만
  - `domain/` — 앱이 쓰는 모델 (**필요할 때만**)
  - `application/` — provider·notifier. 상태 규칙
  - `presentation/screen/`, `presentation/widget/` — 화면
- **`lib/shared/domain/`** — 여러 feature가 공유하는 모델(`Role`, `AuthSession` 등)

**백엔드 계층과의 대응**(`FLUTTER_CODE_CONVENTIONS.md` §3):

| 백엔드 | 프론트 | 금지 |
|---|---|---|
| Controller | `presentation/` | 비즈니스 판단, `dio` 직접 호출 |
| Service | `application/` | `dio`·`stomp`·`geolocator` 직접 사용, `BuildContext` 의존 |
| Repository | `data/*_repository.dart` | 비즈니스 로직, 상태 보관 |
| Infrastructure | `core/` | 특정 feature 지식 |

의존은 **`presentation → application → data → core`** 단방향이다. **화면이 `dio`를 import 하면 그 자체로 위반**이다.

**DTO ↔ domain 분리 기준**은 백엔드 spec/impl과 같은 질문("서버 표현이 바뀔 여지가 있는가")을 쓴다. 모든 DTO에 도메인 모델을 짝지어 만들지 않는다.
- 만드는 경우: `RoutePlanResponse.polyline`(JSON **문자열**, `[lng, lat]` 순서 → `List<LatLng>`로 파싱 필요), JWT `memberships: ["1:ACADEMY_ADMIN"]` → `AuthSession`, 여러 API를 합쳐야 완성되는 화면 모델
- 안 만드는 경우: `BusResponse`·`NotificationResponse` 등 이미 화면에 맞는 응답 — DTO를 그대로 쓴다
- 변환은 **repository에서만** 한다(`toDomain()`). application·presentation에서 JSON을 파싱하지 않는다.

⚠ 컨벤션 문서가 스스로 기록한 기존 위반 1건: `features/rideevent/application/driver_roster_controller.dart` → `features/drivesession/application/` 직접 import (§9 C-1). 새 코드가 이걸 선례로 삼지 말라고 명시돼 있다.

### 4.2 포트-어댑터 — 무엇을 왜 spec/impl로 갈랐는가

백엔드 `reference.md` §15와 **같은 기준**을 적용해, 교체 가능성이 있는 기술 **4개만** 포트로 뽑았다.

| 포트 (`core/*/spec/`) | 현재 구현 | 포트로 만든 이유 |
|---|---|---|
| `MapViewAdapter` | `FlutterMapAdapter` (flutter_map + OSM) | `flutter_naver_map`이 **Web 미지원**이라 지금은 OSM. 나중에 네이버/구글로 교체 가능해야 함 |
| `LocationSource` | `MockLocationSource`(기본), `GpsLocationSource`(geolocator) | 기사 화면에서 토글로 전환, Phase 8에서 실 GPS로 넘어가야 함 |
| `StompGateway` | `StompGatewayImpl` | 실시간 동작을 테스트에서 Fake로 대체해야 함 |
| `TokenStorage` | `SecureTokenStorage` | 테스트에서 인메모리 Mock 필요 |

배선은 `bootstrap()` 한 곳에서 `overrideWithValue`로 주입한다(`bootstrap.dart:68`, `:72-83`). override를 빼먹으면 포트 기본 구현이 `UnimplementedError`를 던져 **런타임에 즉시 드러난다**(`map_view_adapter.dart:120-124`) — 조용히 잘못 동작하는 것보다 낫다는 판단이다.

**포트를 만들지 않은 것**: `ApiConfig`, 각 feature의 repository(서버가 하나뿐이라 교체 대상이 아님).

지도 어댑터의 중립 모델은 `GeoPoint`이고(`map_view_adapter.dart:6`), 마커 종류는 5개(`bus`/`stop`/`upcomingStop`/`visitedStop`/`depot`). 카메라 자동 fit 시 **버스 마커는 제외**한다 — 움직이는 마커 때문에 화면이 계속 리셋되는 걸 막으려는 것이다(`flutter_map_adapter.dart:150-155`).

### 4.3 상태관리 — Riverpod provider 계보

`flutter_riverpod` 3.x를 **코드 생성 없이** 손으로 선언한다(2.2절 사유).

| 화면 | 최상위 controller | 무엇을 호출하나 |
|---|---|---|
| 기사 · 오늘의 노선 | `driverRouteControllerProvider` | `GET /api/route-plans/driver/{busId}` |
| └ 위치 전송 패널 | `driverLocationControllerProvider` | 5초 타이머 → `POST /api/locations/bus` |
| 기사 · 승하차 명단 | `driveSessionControllerProvider` + `driverRosterControllerProvider` | `POST/GET/PATCH /api/drive-sessions/*`, `POST/GET /api/ride-events` |
| 관리자 · 관제 지도 | `busMonitorControllerProvider` | 초기 `GET /api/buses` + 3초마다 `GET /api/locations/buses` |
| └ 상세 패널 | `busDirectoryProvider` · `adminBusSessionProvider` · `adminBusRideEventsProvider` · `adminBusRoutePlansProvider` | 4개를 **화면에서 조립** |
| 관리자 · 배차 | `adminDispatchControllerProvider` | `POST /api/route-plans/auto-assign` → `/confirm` |
| 관리자 · 노선 | `adminRoutePlansProvider` + `adminRoutePlanDetailProvider(id)` | `GET /api/route-plans`, `/{id}` |
| 관리자 · 알림 | `notificationFeedControllerProvider` (autoDispose) | `GET /api/notifications` + STOMP 구독 |
| 전역 | `authControllerProvider`, `adminTenantProvider` | 세션·테넌트 선택 |

특징 세 가지:

1. **라이프사이클 연동** — 관제 폴링과 기사 위치 전송은 앱이 백그라운드로 가면 `pause()`, 돌아오면 `resume()`한다(`admin_monitor_screen.dart:70-79`, `driver_location_card.dart:48-58`). 배터리·트래픽 낭비를 막는 목적이다.
2. **정리(dispose)를 명시** — 알림 피드 provider는 dispose 시 구독 취소 + `gateway.disconnect()`를 수행한다(`notification_feed_controller.dart:109-113`).
3. **화면이 조립 지점** — 관제 상세 패널은 서로 다른 feature의 provider 4개를 화면에서 `ref.watch`로 합친다. 컨벤션이 이를 **유일하게 허용한 계층 교차 예외**로 규정한다(조건: `presentation/`일 것, 읽기만 할 것, 상대가 없어도 내 화면은 그려질 것 — `FLUTTER_CODE_CONVENTIONS.md` §9 C-1).

에러 처리 규약: **HTTP status로만 분기한다.** 백엔드 실패 응답에 에러 코드 필드가 없어(12장 R11) `message` 문자열 파싱은 금지돼 있다.

---

## 5. 인증 · 인가

### 5.1 JWT 발급·검증·클레임 구조

발급·검증은 `global/security/JwtTokenProvider.java` 한 클래스가 담당한다(jjwt 0.12.6, HMAC-SHA, 키는 `jwt.secret`의 UTF-8 바이트 — `:40`).

**클레임 구조** (`JwtTokenProvider.java:53-64`):

| 클레임 | 값 |
|---|---|
| `sub` | userId (문자열) |
| `email` | 이메일 |
| `memberships` | **`"tenantId:ROLE"` 문자열 배열**. 플랫폼 관리자는 tenantId가 빈 문자열 → null로 복원 |
| `type` | `"access"` / `"refresh"` |
| `iat` / `exp` | 발급·만료 시각 |

**요청 처리 흐름 (request → 처리 → response)**:

1. 앱이 `Authorization: Bearer <accessToken>` 헤더로 요청(`AuthInterceptor.onRequest`, `auth_interceptor.dart:63-75`)
2. Spring 필터 체인의 `JwtAuthenticationFilter`(`OncePerRequestFilter`)가 헤더를 읽는다. **`Authorization` 헤더만 인식**하고 쿠키·쿼리는 보지 않는다(`:26-27`, `:57-63`)
3. 토큰을 파싱해 **`type`이 `access`인 것만** SecurityContext에 세팅(`:42`). 위조·만료면 **예외를 삼키고 익명으로 진행**한다 — 최종 거부는 `authorizeHttpRequests`가 한다
4. principal은 `AuthUser` record(`userId`, `email`, `memberships`)로 복원된다
5. 컨트롤러 메서드의 `@PreAuthorize`가 역할을 검사 → 통과하면 서비스로, 실패하면 `AccessDeniedException` → 전역 핸들러가 **403**

핵심 설계 결정: **토큰에 멤버십을 실어 필터가 DB 조회 없이 principal을 복원한다**(`AuthUser.java:73-74` 주석). 요청마다 `user_tenant_role`을 조회하지 않아 빠르지만, **역할을 바꿔도 기존 액세스 토큰에는 즉시 반영되지 않는다**(최대 15분 지연). refresh 시점에는 DB에서 멤버십을 다시 읽어 재인코딩하므로 그때 반영된다(`AuthQueryService.java:69-74`).

> **흔한 오해:** JWT는 "서버가 기억하는 세션"이 아니다. 발급한 순간 서버는 그 토큰을 잊는다 — 그래서 **로그아웃해도 토큰 자체는 만료 전까지 유효**하다. 이 저장소에도 로그아웃·토큰 무효화 엔드포인트가 없고, 프론트 로그아웃은 로컬 저장소를 지우는 것이 전부다(`auth_controller.dart:44-47`).

### 5.2 토큰 수명과 리프레시 — 백엔드 설정 ↔ 프론트 인터셉터

| 항목 | 값 | 출처 |
|---|---|---|
| `jwt.access-token-validity-seconds` | **900초 = 15분** | `application.yml:80` |
| `jwt.refresh-token-validity-seconds` | **1209600초 = 14일** | `application.yml:81` |
| `jwt.secret` | `${JWT_SECRET:local-dev-secret-change-me-please-32bytes-minimum-length}` | `application.yml:79` |

프론트 쪽 대응(`core/api/interceptor/auth_interceptor.dart`):

1. 401 수신 → 저장된 refreshToken으로 `POST /api/auth/refresh` **1회** 시도 → 성공하면 원 요청 재시도(`:77-101`)
2. 동시에 여러 401이 터져도 `_inFlightRefresh`로 **재발급을 1회로 합친다**(`:52-54`, `:103-113`) — 토큰 5개를 동시에 발급받아 서로 덮어쓰는 사고를 막는다
3. 재시도는 **인터셉터가 없는 별도 Dio** + `auth_retried` 플래그로 무한 루프를 차단(`:31-36`, `:56-57`)
4. 재발급 실패 → `onSessionExpired()` → `AuthController.state = null` → go_router의 `refreshListenable`이 감지해 `/login`으로(`auth_controller.dart:60-62`, `app_router.dart:26`)
5. 순환 의존 회피를 위해 재발급은 `SessionRefresher` 포트를 통해 `ref.read`로 늦게 해석한다(`api_client.dart:154-169`)

STOMP도 CONNECT 실패 시 토큰을 재발급받아 재시도하며, **재연결마다 저장소에서 토큰을 새로 읽어** 싣는다(`stomp_gateway_impl.dart:165-178`).

앱 시작 시 세션 복원은 저장된 accessToken을 파싱만 하고 **만료 검사를 하지 않는다** — 만료면 첫 요청이 401을 받고 위 흐름이 알아서 처리하기 때문이다(`auth_repository.dart:57-63`).

토큰 보관은 `flutter_secure_storage`(iOS Keychain / Android Keystore / Web은 WebCrypto). 웹에서는 XSS 시 탈취 가능하며, **accessToken 15분 수명이 그 완화 장치**라고 코드 주석이 밝히고 있다(`secure_token_storage.dart:10-11`).

### 5.3 역할(Role) 5종과 접근 정책

`Role` enum: `STUDENT` · `PARENT` · `DRIVER` · `ACADEMY_ADMIN` · `PLATFORM_ADMIN` (`user/entity/Role.java`).
**역할은 User에 직접 박지 않고 `UserTenantRole`(학원별 역할)로 부여한다** — 같은 사람이 학원 A에서는 학부모, 학원 B에서는 관리자일 수 있기 때문이다(6.1절).

**필터 체인 구성** (`global/security/SecurityConfig.java:54-70`):

1. `csrf().disable()` — 세션 쿠키를 안 쓰는 stateless API라 CSRF 대상이 아니다
2. `cors(...)` — `app.cors.allowed-origins` 목록만 허용, `/api/**`에만 적용
3. `sessionCreationPolicy(STATELESS)`
4. `authorizeHttpRequests` — permitAll 경로 외 `anyRequest().authenticated()`
5. `exceptionHandling` — 미인증 시 403이 아니라 **401**(`HttpStatusEntryPoint(UNAUTHORIZED)`)
6. `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`
7. `PasswordEncoder` = `BCryptPasswordEncoder`

**permitAll 경로 5개 패턴**: `/api/auth/**` · `/actuator/health`(2.1절 — 엔드포인트 자체가 없음) · `/ws/**` · `/swagger-ui/**`+`/swagger-ui.html` · `/v3/api-docs/**`.
`/ws/**`를 연 이유는 **HTTP 핸드셰이크에는 토큰을 실을 수 없기 때문**이고, 대신 STOMP CONNECT 프레임에서 `StompAuthChannelInterceptor`가 인증한다(`SecurityConfig.java:31-33`).

**인가는 경로 규칙이 아니라 컨트롤러의 `@PreAuthorize` 메서드 보안이다**(`@EnableMethodSecurity`, `SecurityConfig.java:41`). 실측 **55개소**(클래스 레벨 3 + 메서드 레벨 52):

| 역할 | 대표 접근 범위 |
|---|---|
| `PLATFORM_ADMIN` 단독 | 테넌트 생성·수정·목록 (`TenantController.java:44,51,66`) |
| `ACADEMY_ADMIN` + `PLATFORM_ADMIN` | 모든 관리자 조회·승인·배차. 클래스 레벨 일괄 지정 3곳(`StudentController:36`, `BusController:35`, `MemberController:32`) |
| `DRIVER` | 승하차 기록, 운행 세션, 버스 위치 보고, 기사용 노선 조회 |
| `STUDENT` | 자기 위치 보고·조회, SOS 발동 |
| `PARENT` | 자녀 조회, 출결·스케줄 신청 |

⚠ **`AuthUser.authorities()`는 멤버십의 역할을 `ROLE_*` authority로 변환하면서 "어느 학원에서 얻은 역할인지"를 잃는다**(`AuthUser.java:40-46`). 여러 학원 멤버십이 있으면 **역할 합집합**이 된다. 그래서 "역할 검사"만으로는 격리가 안 되고, 서비스 계층의 `TenantGuard`가 반드시 한 번 더 필요하다(6.3절).

**프론트 측 역할 라우팅** (`app/router/role_redirect.dart:18-60`):

1. 토큰 복원 중이면 splash에 묶어둔다
2. 세션 없음 → `/login`
3. 로그인은 됐으나 MVP 미지원 역할(학생·학부모) → `/login`에 머물며 "준비 중" 배너
4. 로그인 상태로 `/login`·`/`에 있으면 역할 홈으로 — 관리자 → `/admin/monitor`, 기사 → `/driver/route`
5. 경로 접두어 가드 — `/admin*`은 `role.isAdmin`, `/driver*`은 `role == DRIVER`

**정의된 역할 5개 중 화면이 있는 역할은 3개**다(기사 2화면, 학원 관리자·플랫폼 관리자 각 4화면 — 두 관리자는 화면이 완전히 동일하고 tenant 결정 방식만 다르다). 학생·학부모는 0화면이다.

---

## 6. 멀티테넌시 — 이 시스템의 핵심 제약

여러 학원(테넌트)의 데이터가 **같은 테이블에 섞여 저장**되고, 애플리케이션 코드가 `tenant_id`로 갈라 보여준다. 학원마다 DB를 따로 두는 방식이 아니다.

### 6.1 User ↔ Tenant N:M을 UserTenantRole로 푼 이유

`User` 엔티티에는 **`tenant_id`가 없다**(`user/entity/User.java`). 대신 조인 엔티티 `UserTenantRole(user, tenant, role)`이 소속과 역할을 함께 들고 있다.

이유는 코드 주석이 직접 밝힌다 — **"학부모가 여러 학원에 자녀를 둘 수 있으므로 User–Tenant는 N:M"**(`User.java:16-17`). 여기에 두 가지가 더 얹힌다:

- **역할은 학원마다 다를 수 있다.** 같은 사람이 A학원에서는 기사, B학원에서는 학부모일 수 있다. 그래서 role이 User가 아니라 이 조인 테이블에 붙는다.
- **플랫폼 관리자는 소속이 없다.** `UserTenantRole.tenant`가 `@ManyToOne(LAZY)`에 **nullable**이라, 플랫폼 관리자는 `tenant_id = NULL`인 전역 역할 한 줄로 표현된다(`UserTenantRole.java:43-45`). JWT 클레임에서는 `":PLATFORM_ADMIN"`처럼 tenantId가 빈 문자열로 인코딩된다.

unique 제약은 `(user_id, tenant_id, role)`이다 — 같은 학원에서 같은 역할을 두 번 부여할 수 없되, **한 학원에서 여러 역할은 가능**한 구조다.

### 6.2 tenant_id 보유 엔티티 11개 / 미보유 4개

| 구분 | 엔티티 | 형태 |
|---|---|---|
| **FK 연관으로 보유** (4) | `UserTenantRole`(nullable) · `Student` · `Bus` · `Route` | `@ManyToOne Tenant` |
| **평문 `Long` 컬럼으로 보유** (7) | `RideEvent` · `DriveSession` · `AttendanceException` · `ScheduleChangeRequest` · `SosEvent` · `NotificationLog` · `RoutePlan` | FK 제약 없음 |
| **미보유 — 부모 경유 격리** (4) | `StudentGuardian`(Student 경유) · `Stop`(Route 경유) · `RoutePlanStop`(RoutePlan 경유) · `User`(UserTenantRole로 분리) | — |
| 루트 (1) | `Tenant` | 자기 자신이 테넌트 |

두 가지 형태가 공존하는 게 우연이 아니다. **마스터 데이터(학생·버스·노선)는 엔티티 연관 + FK**로, **이벤트성 데이터(승하차·운행·알림·SOS)는 평문 `Long` + FK 없음**으로 통일돼 있다(7.3절에서 이유를 설명).

### 6.3 격리가 실제로 강제되는 지점

**인터셉터·AOP·Hibernate 필터가 아니라, 서비스 계층에서 정적 헬퍼를 명시 호출**하는 방식이다.

`TenantGuard.resolveTenantId(AuthUser admin, Long requested)` (`global/tenant/TenantGuard.java:22-40`):

| 요청자 | `requested` | 결과 |
|---|---|---|
| `PLATFORM_ADMIN` | `null` | `INVALID_INPUT` "tenantId가 필요합니다" → **400** |
| `PLATFORM_ADMIN` | 지정 | 그대로 통과 — **어느 학원이든 조회 가능** |
| `ACADEMY_ADMIN` | `null` | 본인 소속(`primaryTenantId()`)으로 자동 대입. 소속 없으면 **403** |
| `ACADEMY_ADMIN` | 지정 | `belongsToTenant(requested)` 실패 시 **403** |

플랫폼 관리자에게 tenantId를 **강제**하는 게 포인트다. 전 학원 데이터를 한꺼번에 긁는 실수를 막는 안전장치다.

**호출 지점 실측 26개소** (18개 서비스) — attendance 1 · schedule 1 · route 3 · notification 1 · location 2 · student 4 · bus 4 · user 2 · drivesession 1 · rideevent 1 · routing 5 · sos 1.

격리 방식은 실제로 **네 갈래**다:

| 방식 | 적용 대상 | 예 |
|---|---|---|
| ① `TenantGuard` 호출 | 관리자 목록 조회·등록 (26개소, 18개 서비스) | `GET /api/locations`, `GET /api/buses` |
| ② **인라인 재구현** (`isPlatformAdmin() \|\| belongsToTenant(...)`) | 관리자 단건 처리(승인·반려·확인·종료·정정) **5개소** | `AttendanceCommandService.java:80-82`, `ScheduleCommandService.java:85-87`, `SosCommandService.java:87-89`, `TenantQueryService.java:35-37`, `RideEventCommandService.java:116` |
| ③ **본인/담당자 확인** | 기사·학생 엔드포인트 | 버스의 `driver.id == 요청자 userId`(`BusLocationCommandService.java:37-39`), 요청자 userId로 학생 역조회(`LocationCommandService.java:54-57`) |
| ④ **관계 기반** | 학부모 엔드포인트 | `student_guardian` 연결 확인 → 아니면 "자녀가 아닙니다" 403. **tenantId를 클라이언트가 보내는 경로가 아예 없어 위조 여지가 없다** |

**STOMP 구독 격리**는 별도다(`StompAuthChannelInterceptor:66-80`). `/topic/tenant/{tenantId}/**` 구독만 검사하며, `PLATFORM_ADMIN`이거나 (`ACADEMY_ADMIN` && 해당 테넌트 소속)이어야 통과한다. 개인 큐 `/user/queue/**`는 Spring user-destination 라우팅이 본인 세션 배달을 보장하므로 검사하지 않는다.

### 6.4 ⚠ 리스크

이 절의 항목은 12장 R1~R4에 개선 방향과 함께 정리했다. 여기서는 사실만 짚는다.

1. **DB 레벨 격리가 없다.** Hibernate `@Filter`, PostgreSQL RLS, 인터셉터 기반 자동 조건 주입 — 어느 것도 코드에 없다(`grep -rE "@Filter|RowLevelSecurity|@TenantId"` 무매칭). **모든 격리는 개발자가 매번 손으로 `tenantId`를 쿼리 조건에 넘기는 것에 의존한다.** 새 조회 메서드에서 그걸 빠뜨리면 조용히 전 학원 데이터가 새어나간다.
2. **격리 로직이 이원화돼 있다.** `TenantGuard` 26개소 + 같은 조건을 인라인으로 다시 쓴 5개소. 규칙을 고치려면 `TenantGuard` 하나로 끝나지 않고 인라인 다섯 군데를 함께 고쳐야 한다.
3. **격리가 아예 없는 엔드포인트가 1개 있다** — `GET /api/routes/{id}/stops`. `@PreAuthorize`도 없고 `AuthUser`를 파라미터로 받지도 않아(`RouteController.java:53-56`, `RouteQueryService.java:45-50`), **인증만 됐으면 누구나 다른 학원의 정류장 이름·좌표를 읽을 수 있다.** 프론트가 이 엔드포인트를 쓰지 않아 드러나지 않았을 뿐이다.
4. **`POST /api/auth/signup`이 요청 body의 `tenantId`·`role`을 그대로 신뢰한다.** `permitAll`이므로 **누구나 임의 학원의 `ACADEMY_ADMIN`으로 self-signup 할 수 있다**(`AuthCommandService.java:50-62`). 존재 확인만 하고 "가입해도 되는가"는 묻지 않는다.

---

## 7. 데이터 모델 — 엔티티 16개와 관계

`@Entity`가 붙은 클래스는 **16개**이고, `V1__init_schema.sql`의 `create table`도 **16개**다 — **1:1 대응하며 고아 테이블·고아 엔티티가 없다.**

공통 규약:
- 모든 엔티티가 `@Id @GeneratedValue(IDENTITY) Long id` + `@NoArgsConstructor(PROTECTED)` + `@Getter` + 생성용 `@Builder`를 공유한다. **setter가 없다** — 상태 변경은 의미 있는 도메인 메서드(`assignBus()`, `approve()`, `end()`)로만 한다.
- **`BaseTimeEntity`는 엔티티가 아니다.** `@Entity`가 아니라 `@MappedSuperclass` + `@EntityListeners(AuditingEntityListener.class)`가 붙은 **생성·수정 시각 공통 베이스**이며, 테이블로 매핑되지 않는다(`global/common/BaseTimeEntity.java:19-21`). 16개 엔티티 중 **15개가 이걸 상속**한다 — 유일한 예외가 `RoutePlanStop`이고, 스키마에도 그 테이블만 `created_at`/`updated_at`이 없어 코드와 DDL이 일치한다.

### 7.1 관계 목록 (ERD 대용)

**FK 제약이 있는 관계 13건** (`V1__init_schema.sql:227-290`):

| 부모 | 자식 | FK 컬럼 | NULL |
|---|---|---|---|
| Tenant | UserTenantRole | `user_tenant_role.tenant_id` | 허용(플랫폼 관리자) |
| User | UserTenantRole | `user_tenant_role.user_id` | 불가 |
| Tenant | Student | `student.tenant_id` | 불가 |
| Tenant | Bus | `bus.tenant_id` | 불가 |
| Tenant | Route | `route.tenant_id` | 불가 |
| Route | Stop | `stop.route_id` | 불가 |
| Route | Bus | `bus.route_id` | 허용(미배차) |
| User(DRIVER) | Bus | `bus.driver_id` | 허용(미배차) |
| Bus | Student | `student.bus_id` | 허용 |
| Stop | Student | `student.stop_id` | 허용 |
| Student | StudentGuardian | `student_guardian.student_id` | 불가 |
| User(PARENT) | StudentGuardian | `student_guardian.guardian_id` | 불가 |
| RoutePlan | RoutePlanStop | `route_plan_stop.route_plan_id` | 불가 |

**N:M을 조인 엔티티로 푼 관계 2건**:
- `User ↔ Tenant` → `UserTenantRole` (+`role`), unique(user_id, tenant_id, role)
- `Student ↔ User(보호자)` → `StudentGuardian` (+`relation`), unique(student_id, guardian_id)

**FK 없이 애플리케이션 레벨로만 유지되는 참조** — 이벤트성 테이블 전부. 대표 예:

| 테이블 | 평문 참조 컬럼 |
|---|---|
| `ride_event` | tenant_id, student_id, bus_id, stop_id, corrected_by, **original_ref(자기참조)** |
| `drive_session` | tenant_id, bus_id, driver_id, route_plan_id |
| `route_plan` | tenant_id, bus_id, approved_by, published_by |
| `route_plan_stop` | student_id |
| `attendance_exception` / `schedule_change_request` | tenant_id, student_id, processed_by |
| `sos_event` | tenant_id, student_id, acknowledged_by, resolved_by |
| `notification_log` | tenant_id, student_id |
| `student` | **`user_id`** — 학생 로그인 계정 연결. `@ManyToOne`이 아닌 `Long`이라 FK 제약이 없다 |

### 7.2 FK 있는 관계 vs 애플리케이션 레벨 참조

경계선이 명확하다:

| | FK 있음 (13건) | FK 없음 |
|---|---|---|
| 성격 | **마스터 데이터** — 학원·계정·학생·버스·노선·정류장 | **이벤트/트랜잭션 데이터** — 승하차·운행·계획·출결·SOS·알림 |
| 매핑 | `@ManyToOne(LAZY)` 엔티티 연관 | 평문 `Long` 필드 |
| 격리 | 부모 체인을 타고 감 | `tenant_id` 컬럼 직접 보유 |

### 7.3 왜 이벤트성 테이블은 FK를 걸지 않았는가

코드가 이유를 직접 밝힌다 — **"대용량 테이블이라 연관 엔티티 대신 식별자(Long)로 참조하고 tenant_id로 격리한다"**(`RideEvent.java:22`). 정리하면 세 가지 효과가 있다:

1. **쓰기 성능** — FK가 있으면 INSERT마다 부모 존재 확인(참조 무결성 체크)이 붙는다. 승하차 기록처럼 초당 다건이 쌓이는 테이블에서는 이 비용이 누적된다.
2. **N+1과 lazy 로딩 사고 회피** — `@ManyToOne`을 걸면 조회할 때마다 프록시가 따라오고, 목록을 순회하며 `getStudent().getName()`을 부르는 순간 쿼리가 학생 수만큼 나간다. `Long studentId`만 들고 있으면 그런 일이 구조적으로 생기지 않는다.
3. **모듈 분리 여지** — `rideevent`가 `student` 엔티티를 컴파일 타임에 참조하지 않으므로, 나중에 별도 서비스로 떼어낼 때 걸림돌이 적다(`reference.md` §17 MSA 전환 원칙).

대가는 분명하다: **DB가 무결성을 보장하지 않는다.** 존재하지 않는 `student_id`를 가진 `ride_event`가 들어가도 DB는 막지 않고, 학생을 지워도 기록이 고아로 남는다. 이건 트레이드오프이지 실수가 아니다 — 다만 삭제 API가 없어서(현재 `DELETE` 엔드포인트가 **0개**) 아직 문제가 드러나지 않은 상태다.

**정정(correction)도 같은 철학이다.** `RideEvent.correct()`는 원본을 덮어쓰지 않고 `correctedBy`/`correctedAt`/`originalRef`를 채운 **새 행**을 만든다(`RideEventCommandService.java:92-105`). `RoutePlan`도 재계산 시 기존 행을 고치지 않고 `version + 1`인 새 행을 만든다(`RoutePlan.java:31`). **이력을 지우지 않는 것**이 이 도메인의 일관된 원칙이다 — 사고가 났을 때 "누가 언제 무엇을 바꿨는지"가 남아야 하기 때문이다.

### 7.4 상태머신 — 전이 검증이 있는 것 5개

전이 로직은 **전부 엔티티 도메인 메서드 안**에 있고, 커맨드 서비스는 권한·전제만 확인하고 호출한다.

| 엔티티 | 전이 | 위반 시 |
|---|---|---|
| `RoutePlan` | DRAFT·RECOMMENDED → APPROVED → PUBLISHED | `CONFLICT`. **PUBLISHED 이후 전이 없음**(취소·철회 메서드 부재) |
| `DriveSession` | (생성)IN_PROGRESS → COMPLETED | `CONFLICT` "이미 종료된 운행입니다". 추가로 **차내 잔류 학생이 있으면 종료 차단** |
| `SosEvent` | OPEN → ACKNOWLEDGED → RESOLVED | `CONFLICT`. **OPEN에서 RESOLVED로 건너뛸 수 없다** — 누가 대응했는지 반드시 남기려는 의도 |
| `AttendanceException` | PENDING → APPROVED / REJECTED | `CONFLICT` "이미 처리된 신청입니다". 종착 상태(되돌리기 없음) |
| `ScheduleChangeRequest` | PENDING → APPROVED / REJECTED | 동일 |

**전이 검증이 없는 것**: `RideType`(BOARD/ALIGHT/HANDOVER). `RideEventCommandService.record()`는 요청 `type`을 그대로 새 행으로 저장할 뿐, "승차 없이 하차", "이미 하차한 학생을 또 하차" 같은 순서·중복을 검사하지 않는다. 수행하는 검증은 ① 담당 기사 여부 ② 학생과 버스의 테넌트 일치 두 가지뿐이다. 현재는 **기사 앱이 버튼을 하나만 노출해서** 막고 있을 뿐이라, 직접 API를 호출하면 뚫린다(12장 R16).

`RideSource`는 상태머신이 아니라 분류값이며, `record()`는 항상 `MANUAL`, `correct()`는 항상 `CORRECTION`으로 하드코딩된다 — **`QR`/`NFC` 값을 쓰는 코드 경로가 현재 없다.**

---

## 8. 실시간 위치 파이프라인 — Mock → 실 GPS 추상화

⚠ **먼저 알아야 할 것: 이 시스템에는 위치 파이프라인이 두 개 있고 서로 다르게 동작한다.**

| | 학생 위치 | 버스 위치 |
|---|---|---|
| 저장소 | **Redis** (`loc:{studentId}`, TTL 9초) | **InMemory** (ConcurrentHashMap) |
| 이벤트 발행 | O (`LocationUpdatedEvent`) | **X** |
| 클라이언트 전달 | Kafka → STOMP **push** | **없음 → REST 폴링** |
| 관리자 화면 사용 | 프론트에 화면 없음 | 관제 지도가 3초 폴링 |

즉 **push 파이프라인이 완비된 쪽(학생)은 프론트에 화면이 없고, 화면이 있는 쪽(버스)은 push가 없다.**

### 8.1 학생 위치 — 생성 → 저장 → push 전체 경로

Mock 경로(MVP 기본값) 기준. 실 GPS는 1~5단계만 다르고 6단계부터 완전히 같다.

1. **트리거** — `LocationSimulationScheduler.tick()`이 `app.location.tick-ms`(**3초**, `application.yml:54`)마다 실행
2. **활성 소스 선별** — 주입된 `List<LocationSource>` 4개를 순회하며 `isActive()`가 true인 것만 `tick()`. 기본값에서는 `MockLocationSource`와 `MockBusLocationSource` 2개가 활성
3. **이동 계획 로딩** — `MockSimulationPlan.build()`가 `@Transactional(readOnly=true)`로 학생·버스·노선의 lazy 연관을 안전하게 읽어 좌표만 담은 `Leg` record로 변환. 대상은 "배정 버스 + 승차 정류장이 모두 있는 학생". **자기호출로 프록시를 못 타는 문제를 피하려고 별도 빈으로 분리**했다(`MockSimulationPlan.java:17-20`)
4. **좌표 생성** — 학생별 진행도를 `step`(0.08)만큼 올리고 삼각파로 변환해 출발↔도착 좌표를 선형보간
5. **수집 진입** — `LocationCommandService.ingest(tenantId, studentId, lat, lng, MOCK)` (`@Transactional`)
6. **저장** — `locationRepository.save(...)` → `@Primary`인 `RedisLocationRepository`가 키 `loc:{studentId}`에 **TTL 9초**(tick 3초 × 3)로 SET. 값은 Jackson 3 JSON + `@class` 타입 정보. **학생별 최신 1건만 유지되고 이력은 남지 않는다**
7. **이벤트 발행(1단)** — 같은 트랜잭션 안에서 `ApplicationEventPublisher.publishEvent(LocationUpdatedEvent.of(...))`
8. **커밋 후 릴레이(2단)** — `TransactionalDomainEventRelay`가 `@TransactionalEventListener(AFTER_COMMIT)`로 받아 `DomainEventPublisher.publish()` 호출
9. **Kafka 발행** — 클래스명 `LocationUpdatedEvent` → 토픽 **`location-updated`**로 자동 변환, 파티션 키 = `tenantId`. 실패 시 로그만 남기고 **재시도 없음**
10. **Kafka 소비** — `LocationPushConsumer.onLocationUpdated()`(`@KafkaListener`)
11. **push 대상 해석** — `PushTargetResolver.resolve(studentId)`가 학생 본인·보호자 전원·담당 기사·소속 학원 id를 조회해 `PushTargets`로 반환. 학생이 없으면 전체 스킵
12. **DTO 변환** — `LocationView(studentId, studentName, lat, lng, recordedAt, origin)`. `Instant` → 시스템 기본 시간대 `LocalDateTime`
13. **개인 큐 push** — `convertAndSendToUser`로 본인·보호자·담당 기사에게 `/queue/location` → Spring이 `/user/{userId}/queue/location`으로 재작성해 해당 세션에만 배달
14. **테넌트 토픽 브로드캐스트** — `/topic/tenant/{tenantId}/location` → 그 학원 관리자 전원
15. **클라이언트 수신** — 심플 브로커가 `/ws/location`에 연결된 STOMP 세션에 전달. `/topic/tenant/**` 구독은 `StompAuthChannelInterceptor`가 소속 검증

**곁가지 — 연결 끊김 감지**: STOMP `SessionConnectedEvent`/`SessionDisconnectEvent`를 받아 `LocationSessionRegistry`에 기록하고(`LocationSocketEventListener:31,36`), `ConnectionLossScheduler`가 10초마다 스캔해 **끊긴 지 30초**(`app.connection.loss-grace-seconds`) 넘은 학생만 `StudentConnectionLostEvent`로 올린다. 30초 유예를 둔 건 터널·순간 끊김 오탐을 막기 위해서다.

### 8.2 ⚠ 현재 관제는 WebSocket이 아니라 3초 REST 폴링이다

기사 앱 → 서버 → 관리자 화면 경로는 이렇게 끊겨 있다:

1. 기사 앱이 **5초 타이머**로 `LocationSource.read()` → `POST /api/locations/bus` (`driver_location_controller.dart:76`, `:160-184`)
2. `BusLocationCommandService.reportSelf()` — 버스의 `driver.id == 요청자 userId` 검증 후 통과
3. `busLocationRepository.save(...)` → **`InMemoryBusLocationRepository`**(ConcurrentHashMap). Redis 구현이 없어 재시작 시 휘발, 다중 인스턴스 미공유
4. **이벤트를 발행하지 않는다** — `BusLocationCommandService`에 `ApplicationEventPublisher`가 **아예 주입돼 있지 않다**(생성자 실측). 따라서 Kafka·STOMP 경로가 전혀 없다
5. 관리자 화면이 **3초마다** `GET /api/locations/buses` 재조회 (`bus_monitor_controller.dart:93`, `:162-201`)

프론트 코드 주석도 "location 채널은 전부 학생 위치"라고 기술한다. **STOMP는 알림에만 쓰이고, 지도 위 버스는 폴링으로 움직인다.**

부수 효과 두 가지:
- 기사 앱이 5초마다 보내고 관리자가 3초마다 읽으므로, 화면 갱신 주기가 실제 데이터 신선도보다 짧다(같은 좌표를 여러 번 읽는다).
- 서버 응답의 `recordedAt`에 타임존이 없어(12장 R5) 프론트가 **절대 시각 대신 "클라이언트 수신 시각"으로 신선도를 계산**하고 있다(`monitored_bus.dart:42-50`).

### 8.3 Mock / 실 GPS 교체 지점

이 시스템의 핵심 설계 제약은 "MVP는 Mock으로 하되 **데이터 소스만 교체**하면 실 GPS로 넘어갈 수 있어야 한다"였다. 실제 구현은 `LocationSource` 포트 + 설정 플래그 조합이다.

| 대상 | 포트 | Mock 구현 | 실 GPS 구현 | 전환 플래그 |
|---|---|---|---|---|
| 학생 위치 | `LocationSource` | `MockLocationSource` | `PhoneGpsSource`(**no-op** — 좌표는 앱이 `POST /api/locations`로 push) | `app.location.mock.enabled` / `gps.enabled` |
| 버스 위치 | `LocationSource` | `MockBusLocationSource` | `DriverGpsSource`(no-op — `POST /api/locations/bus`) | `app.location.bus-mock.enabled` / `bus-gps.enabled` |
| 알림 발송 | `NotificationSender` | `LogNotificationSender`(로그만) | **없음** — FCM·알림톡 어댑터 미구현 | 구현체 추가 시 자동 fan-out |

**기본값**(`application.yml:56-63`): `mock.enabled=true`, `gps.enabled=false`, `bus-mock.enabled=true`, `bus-gps.enabled=false`.
**`prod` 프로파일은 이미 반대로 작성돼 있다**(`:118-126`) — 즉 **설정 전환은 준비 완료, 실제 단말 연동만 미검증**이다.

실 GPS 소스가 `no-op`인 이유는 방향이 반대이기 때문이다. Mock은 서버가 좌표를 **끌어오지만**(pull), 실 GPS는 단말이 **밀어넣는다**(push). 그래서 `PhoneGpsSource.tick()`은 아무 일도 하지 않고, 대신 REST/STOMP 진입점이 같은 `ingest()`로 수렴한다(8.1절 6단계부터 동일).

프론트 쪽 대응 포트도 같은 구조다 — `LocationSourceFactory` → `MockLocationSource`(기본) / `GpsLocationSource`(geolocator). ⚠ **기사 앱의 기본값이 Mock이다.** 기사가 전송 스위치를 켜면 기본으로 노선 polyline을 따라 왕복하는 가상 좌표가 나간다(약 43km/h). 실 GPS를 쓰려면 매번 수동 전환해야 한다(`driver_location_controller.dart:21`).

또한 서버는 기사 앱의 Mock/GPS 토글과 무관하게 `POST /api/locations/bus`로 들어온 좌표를 **전부 `GPS`로 기록**한다 — 상세 패널의 "출처: 단말/시뮬레이터" 표기가 실제를 반영하지 못한다(`bus_location_dto.dart:29-33`).

---

## 9. 비동기 · 이벤트

### 9.1 도메인 이벤트 발행·구독 — 2단 릴레이

**발행 측은 Kafka를 모른다.** 도메인 서비스는 Spring `ApplicationEventPublisher.publishEvent(domainEvent)`만 호출하고, `TransactionalDomainEventRelay`의 `@TransactionalEventListener(phase = AFTER_COMMIT)`가 **커밋 이후에만** `DomainEventPublisher` 포트로 릴레이한다. 구현체가 `KafkaEventPublisher`다.

단계로 풀면:

1. 도메인 서비스가 `publishEvent(domainEvent)` — **동기, 트랜잭션 경계 안**
2. 트랜잭션 커밋
3. `TransactionalDomainEventRelay`가 `AFTER_COMMIT`으로 수신
4. `DomainEventPublisher`(포트) 호출
5. `KafkaEventPublisher`(구현)가 Kafka로 발행 — **비동기, 프로세스 경계 밖**

이렇게 두 단으로 나눈 이유:
- **롤백된 트랜잭션의 이벤트가 밖으로 나가지 않는다.** "승차 기록 저장은 실패했는데 학부모에게 승차 알림이 갔다"를 구조적으로 막는다.
- **서비스가 Kafka를 몰라도 된다.** 나중에 Kafka를 RabbitMQ로 바꿔도 `DomainEventPublisher` 구현체만 갈면 된다.

⚠ 한계도 코드 주석이 스스로 밝힌다(`TransactionalDomainEventRelay.java:12-13`) — **Outbox 패턴이 아니다.** 커밋 직후 릴레이 직전에 프로세스가 죽으면 이벤트가 유실된다.

**공통 이벤트 계약**: 모든 도메인 이벤트는 `DomainEvent` 인터페이스의 3필드 `eventId(UUID)` / `occurredAt(Instant)` / `tenantId(Long)`를 갖는다. `occurredDate()` 기본 메서드가 알림 dedupKey 조립에 쓰인다.

**발행 지점 15개소 / 15종**:

| 모듈 | 이벤트 | 유도 토픽 |
|---|---|---|
| attendance | `AttendanceApprovedEvent` | `attendance-approved` |
| schedule | `ScheduleResultEvent` (승인·반려 공용) | `schedule-result` |
| location | `LocationUpdatedEvent`, `StudentConnectionLostEvent` | `location-updated`, `student-connection-lost` |
| student | `StudentAssignmentChangedEvent`, `StudentDropoffChangedEvent` | `student-assignment-changed`, `student-dropoff-changed` |
| drivesession | `ApproachEvent`, `NoShowEvent` | `approach`, `no-show` |
| rideevent | `StudentBoardedEvent`, `RideCompletedEvent`, `HandoverCompletedEvent` (`RideType`에 따라 셋 중 하나) | `student-boarded`, `ride-completed`, `handover-completed` |
| routing | `RoutePlanPublishedEvent`, `RoutePlanRecommendedEvent` | `route-plan-published`, `route-plan-recommended` |
| sos | `SosTriggeredEvent`, `SosEscalatedEvent` | `sos-triggered`, `sos-escalated` |

이벤트 이름은 **과거형**이다(`reference.md` §14) — "무슨 일이 일어났다"를 알리는 것이지 "무엇을 하라"고 명령하는 게 아니기 때문이다.

Spring `@EventListener`는 위 릴레이 외에 2개뿐이다 — STOMP `SessionConnectedEvent`/`SessionDisconnectEvent`를 받는 `LocationSocketEventListener`.

### 9.2 Kafka — 실제 동작 여부

**설정만 있는 게 아니라 실제 동작 경로다. 토픽 15개 전부 프로듀서와 컨슈머가 모두 존재한다.**

- **프로듀서는 `KafkaEventPublisher` 하나뿐**(`KafkaTemplate<String,Object>`).
- **토픽명은 이벤트 클래스명에서 자동 유도**된다: `XxxEvent` → `Event` 접미사 제거 → 케밥케이스 소문자. 새 이벤트를 만들면 토픽 상수를 따로 등록할 필요가 없다.
- **파티션 키 = `tenantId`** → 같은 학원의 이벤트는 같은 파티션 = **순서 보장**. 다른 학원끼리는 순서를 보장하지 않으며, 그래도 되는 도메인이다.
- payload는 이벤트 record를 그대로 JSON 직렬화(`JsonSerializer`/`JsonDeserializer`, 신뢰 패키지 `src.backend.*`).

**컨슈머 클래스 3개 / `@KafkaListener` 16개소**:

| 클래스 | consumer group | 구독 토픽 수 |
|---|---|---|
| `notification/.../DomainEventNotificationConsumer` | `school-bus-backend`(기본) | **11개** — 승하차·인계·approach·no-show·SOS 2종·연결끊김·스케줄결과·노선추천·노선배포 |
| `location/projection/LocationPushConsumer` | `school-bus-backend`(기본) | **1개** — `location-updated` |
| `routing/.../RoutingReplanEventConsumer` | **`school-bus-backend-routing`** | **4개** — `attendance-approved`, `schedule-result`, `student-assignment-changed`, `student-dropoff-changed` |

`schedule-result`를 두 컨슈머가 구독하므로 routing 쪽은 **별도 group id를 명시해야 fan-out이 된다** — 같은 그룹이면 둘 중 하나만 받는다(Kafka의 컨슈머 그룹 동작). 코드 주석이 이 이유를 명시한다.

⚠ **설정만 있고 실제로는 없는 것**:
- **`NewTopic` 빈이 없다.** 토픽은 브로커의 `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`(`docker-compose.yml:54`)로 최초 발행 시 자동 생성된다 → **파티션 수·복제 계수를 코드로 통제하지 못한다.**
- **컨슈머 실패 처리가 없다.** `@KafkaListener` 16개소 어디에도 `errorHandler`·DLT(`@RetryableTopic`) 설정이 없다. 소비 중 예외가 나면 기본 재시도 후 로그만 남고 메시지가 사라진다.

### 9.3 Redis 용도

**용도가 딱 하나다 — 학생 최신 좌표 저장소.** `RedisLocationRepository`가 `RedisTemplate`을 쓰는 유일한 지점이다.

- 키 `loc:{studentId}`, TTL = `app.location.tick-ms × 3` = 기본 **9초**
- 값 직렬화는 `RedisConfig`가 Jackson 3 기반 `GenericJacksonJsonRedisSerializer` + `enableUnsafeDefaultTyping()`(타입 정보 `@class` 저장). 커넥션 팩토리는 Boot 자동 구성
- `@Primary`로 등록돼 `InMemoryLocationRepository`를 대체한다

⚠ **설정·주석과 실제가 다른 지점**:
- `build.gradle` 주석은 Redis를 "캐시·실시간(Pub/Sub)"이라 적었지만, **`@Cacheable`·`@EnableCaching`·`RedisMessageListenerContainer`가 전부 무매칭**이다. 캐시·Pub/Sub·세션·분산락 어디에도 쓰지 않는다.
- **알림 중복 억제도 Redis가 아니다** — DB unique 제약(`notification_log.dedup_key`) + 앱 레벨 선체크 2단이다.
- **버스 좌표는 Redis를 안 쓴다** — `InMemoryBusLocationRepository`만 존재. 학생/버스 사이에 비대칭이 있다(8.2절).
- `InMemoryLocationRepository`는 `@Primary`에 밀려 런타임에 선택되지 않으며, 프로덕션 코드에서 참조하는 곳이 없고 테스트만 쓴다 — 사실상 사문화된 폴백.

### 9.4 WebSocket(STOMP) 구성과 destination

설정은 `global/config/WebSocketConfig.java`(`@EnableWebSocketMessageBroker`).

| 항목 | 값 |
|---|---|
| endpoint | `/ws/location`, `setAllowedOriginPatterns("*")` |
| SockJS | **사용 안 함**(네이티브 WebSocket) — 프론트 `stomp_dart_client`가 그대로 맞음 |
| 애플리케이션 prefix | `/app` |
| user destination prefix | `/user` |
| 브로커 | **심플 브로커**(`/topic`, `/queue`) — 외부 메시지 브로커 미사용 |
| heartbeat | 양방향 `app.connection.heartbeat-ms`(기본 **10000ms**), 전용 `ThreadPoolTaskScheduler`(poolSize 1) |
| 인바운드 인터셉터 | `StompAuthChannelInterceptor` |

**destination 전체 5개** (인바운드 1 + 아웃바운드 4):

| 방향 | destination | payload | 대상 |
|---|---|---|---|
| IN | `/app/location` | `LocationReportRequest`(`@Valid`) — REST `POST /api/locations`와 **동일 계약** | 학생 앱 |
| OUT | `/user/{userId}/queue/location` | `LocationView` | 학생 본인·학부모 전원·담당 기사 |
| OUT | `/topic/tenant/{tenantId}/location` | `LocationView` | 해당 학원 관리자 |
| OUT | `/user/{userId}/queue/notifications` | `NotificationResponse` | 학생 본인·학부모 전원·담당 기사 |
| OUT | `/topic/tenant/{tenantId}/notifications` | `NotificationResponse` | 해당 학원 관리자 |

두 발행자(`LocationPushConsumer`, `WebSocketNotificationSender`)가 push 대상 해석을 `global/push/PushTargetResolver`로 공유한다 — `PushTargets(studentName, studentUserId, guardianUserIds, driverUserId, tenantId)`.

**인증 시점**: `CONNECT` 프레임에서 `Authorization: Bearer` 네이티브 헤더를 검사하고, 통과하면 `accessor.setUser(AuthUser)`로 세션에 principal을 고정한다. **이후 프레임은 재검증하지 않는다** — 결과적으로 **토큰이 만료돼도 기존 STOMP 세션은 계속 살아 있다**(12장 R13).

⚠ **`/app/location`에는 역할 검사가 없다.** REST 대응 엔드포인트의 `hasRole('STUDENT')`가 이 채널에는 적용되지 않는다(`LocationSocketController.java:28-31`) — 다만 서비스가 요청자 userId로 학생을 역조회하므로 **남의 좌표를 위조할 수는 없다.**

프론트 STOMP 게이트웨이(`StompGatewayImpl`)는 heartbeat 10초, 재연결 3초 간격, **연속 실패 5회면 포기**, 재연결 시 등록된 구독을 전부 재-SUBSCRIBE 한다.

### 9.5 스케줄러 목록과 주기 — 4개, 전부 활성

`@EnableScheduling`은 `BackendApplication.java:8`. 비활성·주석처리된 스케줄러는 **없다**.

| 스케줄러 | 주기 설정 키 | 기본값 | 하는 일 |
|---|---|---|---|
| `LocationSimulationScheduler` | `app.location.tick-ms` | **3초** | 활성 `LocationSource`를 순회하며 `tick()` |
| `ConnectionLossScheduler` | `app.connection.loss-check-ms` | **10초** | 끊긴 지 30초(`loss-grace-seconds`) 넘은 학생에 `StudentConnectionLostEvent` |
| `ApproachNoShowScheduler` | `app.drivesession.approach-check-ms` | **15초** | IN_PROGRESS + PICKUP 세션 대상, APPROACH/NO_SHOW 판정 |
| `SosEscalationScheduler` | `app.sos.escalation-check-ms` | **30초** | OPEN 상태 SOS 전수 스캔, 3분 초과 시 `SosEscalatedEvent` |

공통 설계:
- 4개 모두 `try/catch (Exception)`으로 감싸 **한 번의 실패가 다음 주기를 막지 않게** 한다.
- 매 틱마다 조건을 **전수 재평가**하지만, 알림은 `dedupKey` 멱등으로 1회만 나간다. "이미 보냈는지"를 스케줄러가 기억할 필요가 없어지는 구조다.

**임계값과 주기가 분리돼 있다는 점이 중요하다.** yml에 있는 건 "얼마나 자주 검사하는가"(주기)뿐이고, "언제 발동하는가"(임계값)는 **코드 상수**다:

| 상수 (`notification/NotificationThresholds.java`) | 값 | 의미 |
|---|---|---|
| `NO_SHOW` | `Duration.ofMinutes(10)` | 정류소 도착 +10분 미승차 |
| `APPROACH` | `Duration.ofMinutes(5)` | 도착 예상 5분 전 |
| `SOS_ESCALATION` | `Duration.ofMinutes(3)` | 관리자 미확인 3분 → 플랫폼 관리자 에스컬레이션 |

예외적으로 `app.connection.loss-grace-seconds`(**30초**)만 임계값인데 설정으로 나가 있다.

⚠ **스케줄러 관점의 공백**(12장 R8과 연결):
- **NO_SHOW 판정이 하원(DROPOFF)에는 없다** — `IN_PROGRESS` + `PICKUP`만 조회한다. 코드 주석은 "하원은 학원에서 이미 승차한 상태로 출발"이라 의도된 설계라고 밝힌다.
- **RoutePlan 없이 시작한 수동 운행은 판정 제외** — ETA 근거가 없어 아예 스킵된다.
- **ETA가 실시간 위치가 아니라 "세션 시작시각 + 계획 etaSeconds" 근사**다. 실제 버스 좌표를 반영하지 않아 지연 운행 시 판정이 어긋난다.
- **운행 세션 자동 종료 스케줄러가 없다.** 기사가 종료를 안 누르면 IN_PROGRESS로 남아 계속 스캔 대상이 된다. ※ 미확인: 그 경우의 운영 정책은 코드에서 확인할 수 없다.
- **데이터 정리(retention) 배치가 없다** — `notification_log`·`ride_event`가 무한히 쌓인다.

---

## 10. 스키마 관리 — Flyway

**스키마의 단일 소스는 Flyway이고, Hibernate는 검증만 한다.**

| 설정 | 값 | 의미 |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | **`validate`** | 엔티티 ↔ 실제 스키마 일치만 검증. **어긋나면 애플리케이션이 기동 실패한다** |
| `spring.flyway.locations` | `classpath:db/migration` (전 프로파일) | |
| `spring.flyway.locations` (`local`·`demo`) | `classpath:db/migration,classpath:db/migration-local` | 데모 시드를 이 두 프로파일에서만 추가. `prod` 에는 들어가지 않는다 |
| `spring.jpa.open-in-view` | `false` | 뷰 렌더링 중 lazy 로딩으로 커넥션을 붙들지 않게 하려는 설정 |

**마이그레이션 파일 5개**:

| 위치 | 파일 | 내용 |
|---|---|---|
| `db/migration/` (전 프로파일) | `V1__init_schema.sql` | 베이스라인 — 테이블 16 + 인덱스 2 + FK 13 |
| | `V3__ride_event_add_handover_type.sql` | `ride_event.type` CHECK에 `HANDOVER` 추가 |
| | `V4__notification_log_add_handover_done_type.sql` | `notification_log.type` CHECK에 `HANDOVER_DONE` 추가(9종) |
| | `V5__notification_log_add_route_published_type.sql` | 같은 CHECK에 `ROUTE_PUBLISHED` 추가(**10종**) |
| `db/migration-local/` (`local`·`demo` 전용) | `V2__seed_data.sql` | 데모 시드 — 테넌트 3 · 계정 5 · 노선 3 · 정류장 3 · 버스 3 · 학생 6 · 보호자연결 2. 비밀번호 해시는 Flyway placeholder `seedPasswordHash` 로 주입한다(`local` 은 평문 `password` 의 해시가 기본값, `demo` 는 `${SEED_PASSWORD_HASH}` 라 기본값이 부재 — 미주입 시 기동 실패) |

V1은 Hibernate가 생성한 DDL을 그대로 채택한 것이고(FK 이름이 자동 생성 해시), **이후 변경은 새 버전 파일로만 추가하며 V1은 수정하지 않는다.**

⚠ **V2가 스키마 경로가 아니라 시드 경로에 있다.** prod 프로파일에서는 `migration-local`이 로드되지 않으므로 **버전 2가 통째로 건너뛰어져 V1 → V3로 이어진다**(Flyway 기본 `outOfOrder=false`). 의도된 배치이지만, 나중에 누군가 V2를 스키마 변경으로 착각해 재사용하면 충돌한다.

명시 인덱스는 2개뿐이다 — `idx_ride_tenant_student(tenant_id, student_id, occurred_at)`, `idx_sos_status_occurred(status, occurred_at)`. 둘 다 "테넌트 + 대상 + 시간순"이라는 이 시스템의 대표 조회 패턴에 맞춘 것이다.

unique 제약 4개: `app_user.email` · `notification_log.dedup_key` · `student_guardian(student_id, guardian_id)` · `user_tenant_role(user_id, tenant_id, role)`.

**엔티티 정의 ↔ 실제 스키마 어긋남: 없음.** enum CHECK 제약과 자바 enum 상수가 전부 일치하고(`RideType`3↔V3, `NotificationType`10↔V5, `Role`5↔V1 등), 원시 타입(`double`)은 not null·박스 타입(`Double`)은 nullable로 일관되게 반영돼 있다. ※ 참고: 정적 대조 결과이며 실제 기동 검증은 하지 않았다(Docker 미기동).

**로컬 DB에 영속 볼륨을 두지 않은 것도 이 구조의 일부다.** `docker compose down` 후 `up` 하면 Flyway가 V1(스키마)+V2(시드)를 매번 새로 깔아 **항상 같은 시드 상태로 되돌아간다**(`docker-compose.yml:26-28`). Swagger로 반복 테스트하면서 쌓인 데이터를 지우려는 목적이다. 단 `stop`/`start`(컨테이너 유지)는 데이터가 남으므로 리셋하려면 반드시 `down`을 거쳐야 한다.

---

## 11. 인프라 — Docker Compose 구성

### 11.1 서비스 6개

| 서비스 | 이미지/빌드 | 호스트 포트 | depends_on |
|---|---|---|---|
| `postgres` | `postgres:16` | `5432:5432` | — |
| `redis` | `redis:7` | `6379:6379` | — |
| `kafka` | `apache/kafka:3.9.0` | `29092:29092` | — |
| `backend` | `build: ./backend` | **없음** (`expose: 8080`) | postgres(healthy) · redis · kafka |
| `frontend` | `build: ./frontend` | **없음** (`expose: 80`) | backend |
| `proxy` | `nginx:alpine` | **`80:80`** | backend · frontend |

**영속 볼륨이 0개다** — 컨테이너를 지우면 DB·Redis·Kafka 데이터가 전부 사라진다(10장 사유). 운영으로 가려면 반드시 손봐야 할 지점이다.

- **postgres healthcheck**: `pg_isready -U schoolbus`, interval 5s / timeout 3s / retries 5. backend가 `condition: service_healthy`로 기다린다 — 시작 순서 경쟁을 막는 장치다.
- **Kafka는 KRaft 모드**(Zookeeper 컨테이너 불필요). 리스너 3개: `PLAINTEXT://:9092`(컨테이너 간), `CONTROLLER://:9093`, `PLAINTEXT_HOST://:29092`(호스트에서 `bootRun` 할 때).
- **backend 환경변수**로 DB/Redis/Kafka 호스트를 서비스명으로 덮어쓴다(`postgres`/`redis`/`kafka`). 외부 API 키는 `./backend/.env`를 `required: false`로 읽으므로, **키가 없어도 기동은 되고 배차(auto-assign)만 401로 실패**한다. 대안은 `ROUTING_PROVIDER=osrm`.
- **frontend 빌드 인자**: `API_BASE_URL: ""`(상대경로 → 같은 출처), `ENABLE_QUICK_LOGIN: "true"` — **후자는 시드 계정 목록을 로그인 화면에 노출하므로 운영 이미지에서는 빼야 한다**고 주석이 명시한다.
- 컨테이너 안에서 실행되는 프론트는 **Web 타깃뿐**이다(모바일 앱은 컨테이너에서 실행할 수 없다).

### 11.2 nginx 리버스 프록시 (`infra/proxy/nginx.conf`)

| 경로 | 대상 | 타임아웃 |
|---|---|---|
| `/api/` | `backend_pool` | connect 5s / read 30s |
| `/ws/` | `backend_pool` | HTTP/1.1 + Upgrade, read/send **3600s**, `proxy_buffering off` |
| `^/(swagger-ui\|v3/api-docs)` | `backend_pool` | — |
| `/` | `frontend_pool` | — |

`client_max_body_size 10m`.

WebSocket 경로만 타임아웃이 1시간인 이유는 명확하다 — 연결을 오래 유지하는 게 목적이라 30초 read timeout이면 계속 끊긴다. `proxy_buffering off`도 같은 이유(버퍼링하면 실시간성이 사라진다).

**upstream `backend_pool`은 `least_conn` + `server backend:8080` 단일 인스턴스**다. 설정 파일 주석이 다중화 전 선결 조건 3가지를 스스로 명시한다:
1. `@Scheduled` 4개가 인스턴스마다 중복 실행 → 리더 선출/분산 락 필요
2. 위치 저장소가 InMemory
3. WebSocket 세션이 인스턴스 종속

※ 이 중 ②는 **부분적으로 낡은 서술**이다 — 학생 좌표는 이미 `RedisLocationRepository`가 `@Primary`이고, **버스 좌표만 여전히 InMemory**다(9.3절).

### 11.3 애플리케이션 설정 (`application.yml`)

프로파일별 별도 파일이 없다 — **`application.yml` 하나에 `---` 문서 구분자로 4개(default/local/prod/demo)가 들어 있다.**

| 프로파일 | 특징 |
|---|---|
| default | DB/Redis/Kafka를 `localhost`로. `spring.profiles.active: local` |
| `local` | `flyway.locations`에 `db/migration-local` 추가 → 데모 시드 적용. `seedPasswordHash` 기본값이 평문 `password` 의 해시 |
| `prod` | DB/Redis/Kafka 접속을 **전부 환경변수**로(`${DB_URL}` 등). `app.cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:}` — **기본값이 없어 미설정 시 전부 차단**. Mock 끄고 실 GPS 켬 |
| `demo` | **실제 배포에 쓰는 프로파일.** `prod` 를 상속하지 않고 접속 블록을 다시 적는다. `prod` 와 두 가지가 다르다 — (1) 데모 시드 로드(`db/migration-local` 추가). 시드가 없으면 `PLATFORM_ADMIN`·`ACADEMY_ADMIN` 을 API 로 만들 수 없어 **아무도 로그인할 수 없다** (2) Mock 위치 소스 활성. 실 기사 단말이 없어 `prod` 설정이면 버스가 정지 상태로 보인다. `seedPasswordHash` 는 `${SEED_PASSWORD_HASH}` 로 **기본값 부재** — 미주입 시 기동 실패(조용히 `password` 로 뜨는 사고 방지) |

주요 커스텀 설정:

| 키 | 값 | 용도 |
|---|---|---|
| `routing.provider` | `naver` (기본) | `osrm`으로 바꾸면 키 없이 무료 대체 |
| `routing.osrm.base-url` | `https://router.project-osrm.org` | **공개 데모 서버 — 운영 SLA 없음** |
| `routing.naver.key-id` / `key` | `${NAVER_DIRECTIONS_KEY_ID:}` / `${NAVER_DIRECTIONS_KEY:}` | **기본값 빈 문자열** |
| `routing.max-waypoints` | **7** | NCP Direction 15 실측(mid-waypoint 5 + start/goal 2). 초과 시 구간 분할 호출 |
| `app.cors.allowed-origins` | localhost 6종(3000/5173/4200/8081 등) | |

**`server.port` 설정이 없다** → Spring 기본 8080. **`logging.*` 설정도 없다** → Boot 기본 레벨.

**WebClient 타임아웃**(`global/config/WebClientConfig.java`): connect **3000ms**, response **5초**, read/write **5초**. routing 모듈이 `.block()`으로 동기 호출한다.

### 11.4 외부 경로 API 어댑터

`MapRouteClient` 포트에 구현 2개가 붙어 있다.

| 구현 | 활성 조건 | 특징 |
|---|---|---|
| `NaverMapRouteClient` | `routing.provider=naver` (기본) | NCP Direction 15, `option=trafast`. **leg별 duration을 NCP가 주지 않아 구간 Haversine 거리 비례로 근사** → 개별 stop ETA는 근사값. 연속 중복 좌표(같은 정류장 학생)는 호출 전 접는다 |
| `OsrmMapRouteClient` | `routing.provider=osrm` 또는 property 부재 시 fallback | 키 불필요. leg duration을 OSRM이 직접 주므로 **근사 없음** |

`routing.max-waypoints`(7)를 넘으면 경계를 공유하는 구간으로 나눠 여러 번 호출하고 거리·시간·polyline을 병합한다. ⚠ 청킹은 구간별 독립 호출이라 **전역 최적 경로가 아니라 "이어붙인 경로"**다.

※ 미확인: 두 어댑터의 재시도·서킷브레이커 유무는 확인하지 않았다.

**경로 최적화 알고리즘 자체는 외부 API가 아니라 자체 구현이다** — `HeuristicRouteEngine`(sweep 정렬 → nearest-neighbor → 2-opt, **Haversine 직선거리** 사용, 최적화 단계 외부 호출 0회)이 순서를 정하고, 확정된 순서에 대해서만 `MapRouteClient`로 실도로 거리·ETA를 받는다. 버스 배정은 `SweepAssigner`(depot 기준 방위각 정렬 후 좌석 정원만큼 순차 절단)가 담당하며, **반영하는 제약은 `seatCapacity` 하나뿐**이다(시간창·부하 분산·기사 배정 여부·보험 만료는 미반영).

---

## 12. 아키텍처 리스크 · 개선 후보

실측에서 드러난 것만 적는다. 각 항목은 **사실 → 영향 → 개선 방향** 한 줄씩이다.

### 보안 · 격리

**R1. DB 레벨 테넌트 격리가 없다**
- 사실: Hibernate `@Filter`·RLS·자동 조건 주입 어느 것도 없다. 모든 격리가 서비스 코드의 명시적 `tenantId` 전달에 의존한다.
- 영향: 새 조회 메서드에서 조건을 빠뜨리면 **조용히 전 학원 데이터가 노출된다.** 컴파일러도 테스트도 잡아주지 않는다.
- 개선: PostgreSQL RLS 또는 Hibernate `@Filter`를 도입해 "빠뜨리면 데이터가 안 나오는" 구조로 뒤집는다. 최소한 리포지토리 메서드 이름에 `ByTenantId`를 강제하는 컨벤션 검사라도 넣는다.

**R2. 격리 로직이 이원화돼 있다**
- 사실: `TenantGuard` 26개소 + 같은 조건(`isPlatformAdmin() || belongsToTenant(...)`)을 인라인으로 재구현한 5개소(attendance·schedule·sos의 승인 계열, tenant 상세, rideevent 정정).
- 영향: 격리 규칙을 바꾸려면 여섯 군데를 고쳐야 하고, 한 곳을 놓치면 규칙이 갈린다.
- 개선: 인라인 5개소를 `TenantGuard.requireAccessTo(admin, entityTenantId)` 같은 두 번째 헬퍼로 흡수한다(반환값이 아니라 검증만 하는 형태라 `resolveTenantId`와 시그니처가 다르다).

**R3. `GET /api/routes/{id}/stops`에 격리가 없다**
- 사실: `@PreAuthorize`도 없고 `AuthUser`를 받지도 않는다. 인증된 사용자면 누구나 다른 학원의 정류장 이름·좌표를 읽는다.
- 영향: 학원 A의 기사가 학원 B의 정류장 위치를 조회할 수 있다. 프론트가 이 엔드포인트를 쓰지 않아 드러나지 않았을 뿐이다.
- 개선: `AuthUser`를 받아 노선의 tenantId를 `TenantGuard`로 검증한다. 한 줄짜리 수정이다.

**R4. `POST /api/auth/signup`이 tenantId·role을 그대로 신뢰한다**
- 사실: `permitAll` + 요청 body의 역할을 검증 없이 수용 → **누구나 임의 학원의 `ACADEMY_ADMIN`으로 가입 가능**하다.
- 영향: 데모 환경에서는 편의지만, 그대로 배포하면 인증 체계 전체가 무의미해진다.
- 개선: self-signup은 `PARENT`/`STUDENT`로 제한하고 관리자·기사 계정은 초대·승인 경로로만 만든다. 현재 프론트에 회원가입 화면이 없으므로 **엔드포인트를 닫아도 지금은 잃는 기능이 없다.**

**R13. STOMP 세션에 토큰 만료가 반영되지 않는다**
- 사실: CONNECT 시 1회만 검증하고 이후 프레임은 재검증하지 않는다.
- 영향: 액세스 토큰이 만료되고 권한이 회수돼도 **이미 연결된 세션은 계속 메시지를 받는다.**
- 개선: 주기적 재검증(heartbeat에 편승) 또는 서버 측 세션 만료 타이머를 둔다.

### 데이터 · API 계약

**R5. 응답 시각에 타임존이 없다**
- 사실: 모든 응답 시각이 `LocalDateTime`/`LocalDate`/`LocalTime`이고, **`spring.jackson.*` 설정·커스텀 `ObjectMapper`·`@JsonFormat`이 프로젝트 전체에 0건**이다(grep 확인). DB 컬럼도 `timestamp`이지 `timestamptz`가 아니며, `TZ` 환경변수도 없다.
- 영향: 응답이 `"2026-07-29T14:30:00"`처럼 오프셋 없이 나간다. 클라이언트가 이를 UTC로 해석하면 정확히 **9시간 어긋난다.** 게다가 `LocalDateTime.now()`가 JVM 기본 타임존을 따르므로 **호스트에서 `bootRun`(KST)** 하느냐 **컨테이너로 띄우느냐(대개 UTC)**에 따라 저장값 자체가 달라진다. 현재 프론트는 절대 시각을 포기하고 "클라이언트 수신 시각"으로 신선도를 계산 중이다.
- 개선: (a) DTO를 `OffsetDateTime`/`Instant`로 바꾸고 DB를 `timestamptz`로 옮기거나, (b) "오프셋 없는 문자열 = KST 로컬시각"을 계약으로 못박고 양쪽에 적용한다. **`spring.jackson.time-zone` 설정만으로는 해결되지 않는다** — `LocalDateTime` 직렬화에는 오프셋이 붙지 않기 때문이다.

**R11. 에러 응답에 코드 필드가 없다**
- 사실: `ApiResponse(success, data, message)` 3필드가 전부다. `ErrorCode` enum이 8종 있지만 **이름이 응답 body에 실리지 않는다.** 또한 인증 실패(401)만 필터 체인이 처리해 **body 없는 빈 응답**이라 래퍼조차 안 탄다.
- 영향: 프론트가 에러 종류를 구분하려면 **HTTP 상태 + 한글 메시지 문자열 매칭** 외에 방법이 없다. 컨벤션이 문자열 매칭을 금지하고 있으므로 사실상 상태코드만으로 분기 중이다. 메시지 문구를 바꾸면 클라이언트가 깨진다.
- 개선: `ApiResponse`에 `code` 필드를 추가하고 `GlobalExceptionHandler`가 `ErrorCode.name()`을 실어 보낸다. 401 경로도 `AuthenticationEntryPoint`를 커스텀해 같은 래퍼로 통일한다.

**R12. 페이징이 전혀 없다**
- 사실: `Pageable`/`PageRequest`/`Page`를 참조하는 파일이 **0개**다. 모든 목록 API가 `ApiResponse<List<T>>`로 전건을 내려주고, 정렬은 리포지토리 메서드 이름에 고정돼 있다(`...OrderByOccurredAtDesc` 등).
- 영향: 알림 이력·SOS 이력·승하차 기록이 학원 규모에 비례해 무한히 커진다. 데모 규모에서는 문제없지만 운영에서는 첫 병목이 된다.
- 개선: 이력성 목록(`/api/notifications`, `/api/ride-events`, `/api/sos-events`)부터 커서 페이징을 도입한다. 프론트가 `GET /api/drive-sessions`·`GET /api/ride-events`에서 **전체를 받아 클라이언트가 필터링**하고 있으므로(예: `IN_PROGRESS` 찾기, busId 필터), 서버 필터 파라미터 추가가 같이 필요하다.

**R16. 승하차 순서 검증이 서버에 없다**
- 사실: `RideEventCommandService.record()`가 순서·중복을 검사하지 않는다. 승차 없이 하차를 보내도 200이 온다.
- 영향: 현재는 기사 앱이 버튼을 하나만 노출해 막고 있을 뿐이라, 다른 클라이언트나 직접 호출은 막히지 않는다. 기록 무결성이 UI에 의존한다.
- 개선: `record()`에서 해당 학생의 마지막 `RideEvent`를 조회해 허용 전이(`BOARD → ALIGHT → HANDOVER`)만 통과시킨다. 상태 전이를 엔티티에 두는 이 저장소의 기존 패턴과도 맞는다.

### 구조 · 운영

**R6. `route`(레거시)와 `routing`(신규)이 이중 구조다**
- 사실: 두 모듈은 테이블·엔티티가 완전히 분리돼 있고, 겹치는 지점은 **PICKUP 승차 좌표 하나**뿐이다(`Student.boardingStop`의 lat/lng를 routing이 읽어 쓴다). `Stop.seq`는 routing이 쓰지 않고 스스로 순서를 다시 계산한다. **정원 개념도 이원화**돼 있다 — `Route.assignCapacity`는 경고 표시용, `Bus.seatCapacity`는 실제 배정 판단용이며 **둘은 동기화되지 않는다.**
- 영향: 한 버스가 `Bus.route`(고정 노선)와 `RoutePlan`(당일 계획) 두 개의 순서를 동시에 가질 수 있고, **정합성을 맞추는 코드가 없다.** 프론트는 `/api/routes` 계열 4개를 하나도 호출하지 않는다.
- 개선: `route`를 "정류장 마스터 데이터"로 역할을 명시적으로 축소하고(문서·API 태그에 표기), `Bus.route`가 당일 운행 순서를 뜻하지 않음을 계약으로 못박는다. 정원은 한쪽으로 통일하거나 두 값의 의미 차이를 응답에 드러낸다.

**R7. 버스 위치에 push 경로가 없어 관제가 폴링이다**
- 사실: `BusLocationCommandService`에 `ApplicationEventPublisher`가 주입돼 있지 않아 이벤트를 발행하지 않는다. 관제 화면은 3초 REST 폴링.
- 영향: 학생 위치용 Kafka→STOMP 파이프라인이 완비돼 있는데 정작 화면이 있는 버스 위치는 쓰지 못한다. 관제 화면 수가 늘수록 서버 부하가 선형 증가한다.
- 개선: `BusLocationUpdatedEvent`를 추가해 학생 위치와 같은 경로(Kafka → `/topic/tenant/{id}/location`)를 타게 한다. 8.1절 6단계 이후를 그대로 재사용할 수 있으므로 신규 코드가 적다.

**R9. 버스 좌표 저장소가 InMemory다**
- 사실: `InMemoryBusLocationRepository`(ConcurrentHashMap)만 존재하고 Redis 구현이 없다.
- 영향: 재시작 시 전 버스 위치가 사라지고, 인스턴스를 늘리면 공유되지 않는다. 학생 좌표(Redis)와 비대칭이다.
- 개선: `RedisLocationRepository`와 같은 패턴으로 `RedisBusLocationRepository`를 추가한다. 포트가 이미 있으므로 **기존 코드 수정 없이 구현체 추가만으로 끝난다.**

**R8. 스케줄러에 분산 안전장치가 없다 — 수평 확장 불가**
- 사실: `@Scheduled` 4개 모두 리더 선출·분산 락 없이 무조건 실행된다. nginx upstream도 단일 인스턴스로 고정돼 있다.
- 영향: 백엔드를 2대 이상 띄우면 같은 판정이 인스턴스마다 돈다. 알림은 `dedupKey` unique 제약으로 중복이 막히지만 **Kafka로는 중복 이벤트가 그대로 발행된다.**
- 개선: ShedLock 같은 분산 락 또는 스케줄러 전용 인스턴스 프로파일(`@ConditionalOnProperty`)을 도입한다. R9와 함께 해결해야 수평 확장이 열린다.

**R10. Kafka 컨슈머 오류 처리와 Outbox가 없다**
- 사실: `@KafkaListener` 16개소에 `errorHandler`·DLT 설정이 없고, 릴레이는 AFTER_COMMIT 방식이라 Outbox가 아니다. `NewTopic` 빈이 없어 파티션·복제 계수도 브로커 자동 생성에 맡긴다.
- 영향: 커밋 직후 프로세스가 죽으면 이벤트가 유실되고, 소비 중 예외가 나면 메시지가 로그만 남기고 사라진다. **알림 누락은 이 도메인에서 안전 문제로 직결된다**(미승차·SOS).
- 개선: (a) `@RetryableTopic` + DLT를 붙여 실패 메시지를 보존하고, (b) 중요도 높은 이벤트(SOS·NO_SHOW)부터 Outbox 테이블 기반 릴레이로 옮긴다.

**R14. 모듈 순환 의존 5건**
- 사실: `bus`↔`route`, `bus`↔`student`(JPA 연관 유래), `drivesession`↔`notification`, `sos`↔`notification`(상수 `NotificationThresholds` 참조 유래), `global`↔`student`·`user`.
- 영향: 런타임 문제는 없지만, MSA로 떼어내려는 순간 컴파일이 안 된다. 특히 상수 참조 2건은 **가치 대비 손해가 큰 순환**이다.
- 개선: `NotificationThresholds`를 `global/policy`로 올리면 순환 2건이 즉시 사라진다(가장 저렴한 개선). `global`↔`student` 순환은 `PushTargetResolver`를 `notification` 모듈로 옮기는 방향을 검토한다.

**R15. actuator 의존성 없이 `/actuator/health`를 permitAll 한다**
- 사실: `SecurityConfig.java:61`에 경로는 열려 있으나 `spring-boot-starter-actuator`가 `build.gradle`에 없다 → **엔드포인트가 존재하지 않는다.**
- 영향: 헬스체크 URL로 쓸 수 없다. docker-compose backend 서비스에 healthcheck가 없는 것도 이 때문으로 보인다.
- 개선: actuator를 추가하고 `/actuator/health`만 노출한다(`management.endpoints.web.exposure.include=health`). 그러면 compose·프록시·오케스트레이터가 공통으로 쓸 준비 상태 신호가 생긴다.

### 인프라 · 운영 환경

**R17. 영속 볼륨이 0개다**
- 사실: postgres·redis·kafka 어디에도 볼륨이 없다(로컬 리셋 편의를 위한 **의도된 설정**).
- 영향: 그대로 배포하면 컨테이너 재생성 시 전 데이터가 사라진다.
- 개선: prod용 compose/오케스트레이터 매니페스트를 별도 파일로 분리하고 볼륨을 붙인다. 현재 파일은 **로컬 개발 전용**임을 문서와 파일명으로 명시한다.

**R18. 외부 경로 API 기본값이 키 없이는 실패한다**
- 사실: `routing.provider`의 기본값이 `naver`인데 키는 git에 없는 `.env`로만 주입된다. 대안 `osrm`은 공개 데모 서버(`router.project-osrm.org`)라 운영 SLA가 없다.
- 영향: 키가 없는 환경에서 배차 API가 "서버 오류"로만 보인다.
- 개선: 키 부재를 기동 시점에 감지해 명확한 경고 로그를 남기거나 자동으로 `osrm` fallback 한다. 운영에서는 OSRM 자체 호스팅을 검토한다.

**R19. `ENABLE_QUICK_LOGIN=true`가 compose 기본값이다**
- 사실: 로컬 `docker-compose.yml:99`이 빌드 인자로 켜 두어 로그인 화면에 시드 계정 4개(로컬 비밀번호 `password`)가 노출된다. Dockerfile 기본값은 false.
- 영향: 인증을 우회하지는 않지만 계정 목록이 그대로 보인다.
- 개선: **배포 경로는 해소됐다** — `.github/workflows/deploy-web.yml:44`가 `--dart-define=ENABLE_QUICK_LOGIN=false`를 고정으로 넘긴다. 남은 노출은 로컬 compose 빌드뿐이라 수용 범위다.

---

## 부록 — 요약 통계

| 항목 | 수 |
|---|---|
| 백엔드 도메인 모듈 | **14** (+ `global` = 15) |
| 모듈 간 순환 의존 | **5건** |
| `@Entity` / DB 테이블 | **16 / 16** (1:1, 고아 없음) |
| DB FK 제약 / 명시 인덱스 / unique 제약 | **13 / 2 / 4** |
| Flyway 마이그레이션 | **5** (V1·V3·V4·V5 공통 + V2 local 전용) |
| 도메인 enum / 상태 전이 검증이 있는 상태머신 | **10 / 5** |
| Kafka 토픽 | **15** (전부 프로듀서·컨슈머 존재) |
| Kafka 컨슈머 클래스 / `@KafkaListener` | **3 / 16** |
| 도메인 이벤트 종류 / 발행 지점 | **15종 / 15개소** |
| STOMP destination | **5** (인바운드 1 + 아웃바운드 4) |
| `@Scheduled` 스케줄러 | **4** (전부 활성) |
| `@PreAuthorize` 적용 | **55개소** (클래스 3 + 메서드 52) |
| `TenantGuard` 호출 / 인라인 격리 검사 | **26개소 / 5개소** |
| permitAll 경로 패턴 | **5** |
| 백엔드 컨트롤러 / 엔드포인트 | **15개**(location만 REST+STOMP 2개) **/ 69개**(REST 68 + STOMP 1) |
| 프론트가 실제 호출하는 REST 엔드포인트 | **22** (사용률 약 32%) |
| 프론트 라우트 / 화면이 있는 역할 | **9 / 3**(정의된 역할 5) |
| 에러 코드 enum / 전역 예외 핸들러 | **8 / 4** |
| 백엔드 테스트 파일 | **21** |
| docker-compose 서비스 / 영속 볼륨 | **6 / 0** |

**테스트가 없는 영역**(참고): Kafka 컨슈머 3종, STOMP 인증(`StompAuthChannelInterceptor`), 스케줄러 4개, `RedisLocationRepository`, `TenantGuard`, `PushTargetResolver`, `MapRouteClient` 구현 2종.

### ※ 미확인 항목

1. `rideevent`·`drivesession` command 계층의 개별 권한 검증 방식(`TenantGuard` 미호출은 확인, 대체 검증 코드의 전모는 미확인)
2. 기사가 운행 종료를 누르지 않은 `DriveSession`의 운영 처리 정책
3. `NaverMapRouteClient`·`OsrmMapRouteClient`의 재시도·서킷브레이커 유무
4. `ApiResponse` 래퍼를 벗어나 `ResponseEntity`를 직접 반환하는 컨트롤러가 있는지(일부 컨트롤러만 전수 확인)
5. `flutter_secure_storage`의 웹 빌드 시 실제 저장 매체
6. `routing.max-waypoints` 청킹 경로의 실행 검증(정적 코드만 확인 — 관련 보고서 `backend/report/2026-07-29-route-optimization-verification.md`가 저장소에 존재)







