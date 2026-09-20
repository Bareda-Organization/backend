# SDD ledger — plan: docs/superpowers/plans/2026-08-21-관제-대시보드.md

Spec: docs/superpowers/specs/2026-08-21-관제-대시보드-design.md (읽음)
Branch: feat/mvp-deployment · BASE(Task1) = 26111d6

## Pre-flight 충돌 스캔

| 검사 대상 | 공유 파일 / 인터페이스 | 결과 |
|---|---|---|
| T1 → T2·T3·T4·T5 | `/actuator/prometheus` 노출 | 정합. T1 이 build.gradle·application.yml 만 건드리고 나머지는 미접촉 |
| T2 ↔ T3 | 둘 다 `location/` 하위 수정 (T2=scheduler 2개, T3=command·projection) | **파일 중복 없음.** 단 T3 Step8 이 `git add .../location/` 디렉토리 단위 — T2 가 먼저 커밋되므로 안전 |
| T5 ↔ T6 | `docker-compose.yml` 공동 수정 | 순차 의존 명시됨(T6 선행=T5). 정합 |
| T7 ↔ T8 | `grafana/dashboards/` 같은 폴더, 다른 파일 | 정합 |
| T2·T3·T4 → T7·T8 | 지표명(Micrometer 점표기 → Prometheus 밑줄표기) | 정합. Counter `_total` 접미사 규칙 계획서에 명시됨 |
| T5 → T7·T8 | job 이름 `backend`·`postgres`·`redis`·`node` | 정합 |
| T6 → T7·T8 | datasource uid `prometheus` | 정합 |
| T1 자체 정합성 | 명시한 테스트 vs 명시한 설정 | **결함 발견 2건 → 아래 Ruling 참조** |
| T2 자체 정합성 | 테스트(시계 주입) vs 구현(생성자 2개) | 정합 |
| T3 자체 정합성 | 테스트 vs 구현 | 정합 |
| T4 자체 정합성 | 테스트(이벤트 mock) vs 구현(@EventListener) | 정합 |
| T5~T9 자체 정합성 | 설정 파일 vs 검증 명령 | 정합 |

Ruling: T1 테스트를 MockMvc 대신 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` 로 바꾼다
 — 이유: `webAppContextSetup` MockMvc 는 `ServerHttpObservationFilter` 를 거치지 않을 수 있어 `http_server_requests` 가 아예 기록되지 않는다. 그러면 설정이 정상인데도 테스트가 실패하거나, 반대로 "버킷 없음"을 설정 탓으로 오진한다.
 — 틀렸을 때 비용: 테스트가 느려짐(실서버 기동). 기능 영향 없음.

Ruling: T1 의 cardinality 테스트는 인증 토큰을 실제 로그인으로 얻어 `/api/buses/{id}` 를 호출한다
 — 이유: 미인증 요청은 Security 필터에서 끊겨 핸들러 매핑 전에 반환되므로 `uri` 라벨이 패턴으로 해석되지 않는다. 그 상태로는 통과해도 의미가 없다(요청이 기록되지 않아 assert 가 공허하게 참).
 — 대안: 로그인 불가 시 **공허하게 통과하는 테스트를 쓰지 말고 BLOCKED 로 보고**한다. cardinality 검증은 T9 Step3 에 실서버 기준으로 이미 있다.
 — 틀렸을 때 비용: T1 테스트 1개가 T9 와 중복. 중복 검증은 손해가 아님.

## 진행

Ruling: 관측을 `src.backend.observability` 별도 모듈로 격리하고 **도메인 코드 수정 0줄**로 간다 (사용자 지시, 2026-08-21)
 — 처음에 나는 "계측은 도메인 안에서 호출해야 하므로 양방향 의존이라 분리가 과하다"고 판단했으나, 실측해 보니 확장점 5개가 **이미 전부 존재**한다:
   `@Scheduled`/`@KafkaListener`(AOP 대상) · `BusLocationUpdatedEvent`(이미 발행) · `List<NotificationSender>`(이미 List 주입) · STOMP 세션 이벤트.
   이것들만 쓰면 의존이 `observability` → 도메인 **단방향**이 되고 도메인 파일은 미접촉이다. 사용자 지시가 옳았다.
 — 대가: `spring-boot-starter-aop` 의존성 1개. AOP 프록시가 실제로 걸리는지는 단위 테스트로 증명 불가라 Task 2 에 통합 테스트를 넣었다.
 — 한계(계획서에 기록): 스케줄러 4개가 자기 안에서 예외를 이미 잡으므로 aspect 의 실패 카운터는 거의 오르지 않는다. **경과 게이지는 정상 동작**한다. 실패 카운터까지 채우려면 도메인 수정이 필요해 범위 밖.
 — 틀렸을 때 비용: AOP 프록시 미적용 시 지표가 조용히 빈다. Task 2 통합 테스트가 그 경우 FAIL 하도록 설계했다.

Task 1: 진행 중 (sdd-t1, sonnet) — 이 판정의 영향 없음(build.gradle 의존성 + application.yml 만 건드림)
Task 1: 구현 완료 (sdd-t1, commit 2123bc3) — 268 → 271 통과, 실패 0
Task 1: 구현자 자진 신고 — brief 밖 SecurityConfig 수정(`/actuator/prometheus` permitAll 1줄)
  컨트롤러 확인: 경로가 `/actuator/**` 가 아닌 단일 경로 · nginx.prod.conf:149 의 404 차단 미변경 ·
  docker-compose.prod.yml 미변경 · 로컬 nginx 는 `location /` 로 frontend 에 보내 백엔드에 미도달
  → **보안 설정 변경이라 사용자 확인 대기 중.** 대안(관리 포트 분리)과 함께 제시함
Task 1: 리뷰 진행 중 (sdd-t1-review, sonnet) — review-a8bc728..2123bc3.diff
Task 1: 관찰 — 테스트가 `global/metrics/PrometheusEndpointTest.java` 에 있음.
  Task 2~4 는 `observability/` 로 가므로 위치 불일치. 리뷰어 판정 대기 후 처리
Task 1: 리뷰 완료 (sdd-t1-review) — 스펙 ✅ / 품질 조건부 승인 (Critical 0 · Important 2 · Minor 1)
Task 1: minor (deferred): PrometheusEndpointTest 에서 java.util.List.of() 완전정규명 인라인 사용
  (저장소 관례는 상단 import 후 List.of). 다음에 그 파일을 만질 때 정리 — 최종 리뷰에서 triage 대상
Task 1: "Cannot verify" 항목 컨트롤러가 해소 — 보고서의 "지시받은 대로"는 이 원장의 Ruling 2건
  (MockMvc→RANDOM_PORT, cardinality 테스트 실 토큰)이며 리뷰어는 brief·diff 만 봐서 알 수 없음. 실제 갭 아님
Task 1: fix round 1/5 진행 중 — Important-1(긍정 검증 추가) · Important-2(application.yml:95 주석)
  SecurityConfig Javadoc 은 보류 지시 — permitAll vs 관리포트 분리 결정에 따라 정답이 달라짐
Task 1: fix round 1/5 (2 addressed, 0 open; commits 2123bc3..f3ec4d8)
Task 1: parked — SecurityConfig.java:34 클래스 Javadoc 의 공개 경로 목록에 /actuator/prometheus 누락
  Ruling: 보류. permitAll 유지 vs 관리포트(management.server.port) 분리가 사용자 확인 대기 중이며,
  결정에 따라 이 Javadoc 의 정답이 달라진다(포트 분리면 현재 상태가 그대로 맞음). 결정 후 정리한다.
  틀렸을 때 비용: 보안 설정 클래스 주석이 공개 경로 하나를 누락한 채로 남음 — 다음 사람이 "공개 경로는 3개뿐"으로 오인 가능.
Task 1: complete (commits a8bc728..f3ec4d8, review clean, 1 parked)

Ruling(사용자 결정, 2026-08-21): `/actuator/prometheus` 는 `permitAll` 유지. 관리 포트 분리(management.server.port) 미채택
 — 근거: 이번 단계가 로컬 한정이고, 접근 경계는 인증이 아니라 네트워크다(운영 nginx `/actuator` 404 유지 · backend 호스트 포트 미개방 · 로컬 nginx 는 `location /` 로 frontend 에 보냄). 노출은 `/actuator/**` 가 아닌 단일 경로.
 — prod 노출(2단계)에서 IP 제한을 붙일 때 관리 포트 분리를 함께 재검토한다.
 — 틀렸을 때 비용: 로컬 dev 에서 `bootRun` 시 `localhost:8080/actuator/prometheus` 가 인증 없이 열림(개발자 자기 머신 한정).
Task 1: parked 항목 해제 — SecurityConfig Javadoc 정리를 sdd-t1 에 지시(주석만, Gradle 금지 — sdd-t2 동시 실행 중)
Task 2: 진행 중 (sdd-t2, sonnet) — BASE=f3ec4d8
Task 1: parked 항목 해소 완료 (commit 1454189) — 주석 1건, 인가 규칙 코드 미변경 확인.
  "한 경로만, /actuator/** 전체 아님" 명시 + 네트워크가 경계라는 근거 포함. **Task 1 보류 0건.**
Task 1: FINAL — complete (commits a8bc728..1454189, review clean, parked 0)
Task 2: 구현 완료 (sdd-t2, commit a8f5dd5) — 271 → 277 통과, 실패 0. **무침투 확인: 도메인 수정 0건**
Task 2: 구현자가 brief 코드 오류 3건 신고 — (1) spring-boot-starter-aop 는 Boot 4.1 에서
  spring-boot-starter-aspectj 로 개명(구 좌표 BOM 부재), (2) 테스트/구현 패키지가 달라 package-private
  생성자 접근 불가 → public 전환, (3) public 생성자 2개로 Spring 이 주입 대상 못 고름 → @Autowired 명시
Ruling: 계획서·스펙의 잘못된 아티팩트명을 정정했다 (docs 커밋 예정)
  — 이유: 내 계획서 오류다. 이 저장소는 같은 계열 개명을 이미 두 번 겪었고(web→webmvc, webflux→webclient)
    CLAUDE.md 에도 있는데 내가 AOP 에 적용하지 못했다. 그대로 두면 이 계획을 다시 읽는 사람이 같은 곳에서 막힌다.
  — 틀렸을 때 비용: 없음(이미 구현이 aspectj 로 동작 중이며 문서를 사실에 맞춘 것뿐)
Task 2: 리뷰 진행 중 (sdd-t2-review) — 보정 3건의 타당성 + AOP 통합테스트가 진짜 증명하는지 + Gauge 등록 스레드안전성
Task 2: 리뷰 완료 (sdd-t2-review) — 스펙 ✅ / 품질 승인 (Critical 0 · Important 1 · Minor 2)
  리뷰어 검증: 태그 4값 수기 재계산 일치 · AOP 통합테스트는 프록시 부재 시 반드시 FAIL(진짜 증명) ·
  computeIfAbsent 가 매핑함수 1회 실행 보장이라 Gauge 중복등록 불가 · 예외 재던짐 확인 · 책임분리 지켜짐
Task 2: minor (deferred): 없음 — Minor 2건 모두 이번 라운드에 포함(사실오류 1 + 보고서 서술 1)
Ruling: 리뷰어의 Important(생성자 public 대신 테스트 이동)를 수용한다
  — 이유: 저장소가 테스트/대상 패키지 1:1 을 전역으로 지키고 있음을 리뷰어가 실증했다. 내 브리프가
    테스트 위치를 observability/ 로 잡은 게 원인이며, 관행을 따르면 프로덕션 접근제어자를 넓히지 않아도 된다.
  — 틀렸을 때 비용: 테스트 파일 이동 1건. 되돌리기 쉬움.
Ruling: build.gradle 주석의 "Boot 4.1부터"를 "4.0.0-M3"로 정정하고 내 계획서·스펙도 함께 고친다
  — 이유: 개명은 4.0.0-M3(공식 마이그레이션 가이드 #aop-starter-pom, spring-boot#42948)다. 결론은 맞지만 근거가 틀렸다.
  — 틀렸을 때 비용: 없음(사실 정정).
Task 2: fix round 1/5 진행 중 (sdd-t2)
Task 2: fix round 1/5 (Important ①~④ + Minor + 보고서서술 전부 addressed, 0 open; commits fac00b3..0224f90)
Task 2: complete (commits f3ec4d8..0224f90, review clean, parked 0) — 277 통과, 무침투 유지
Task 10 추가 (사용자 요청, 2026-08-21): 대시보드 4장 패널별 해설 문서
  → backend/docs/OBSERVABILITY_DASHBOARDS.html. **Task 9 완료 후 착수**(패널 확정 전에 쓰면 즉시 낡음)
  → ⚠ 착수 전 `html-docs` 스킬 필독 — 이 저장소는 사람이 읽는 기술문서를 HTML+인라인SVG 로 쓴다(.md 아님)
  → 핵심: PromQL 나열이 아니라 "읽는 법". 단독으로는 원인을 못 가리는 조합 5가지를 반드시 담는다
Task 3: 구현 완료 (sdd-t3, commit a184486) — 277 → 281 통과. 무침투 확인: 도메인 수정 0건
Task 3: 리뷰 완료 (sdd-t3-review) — 스펙 ✅ / 품질 **미승인** (Critical 0 · Important 3 · Minor 2)
  핵심: Kafka aspect 의 catch 분기 무검증 + **라이브 확인이 aspect 부착을 증명하지 못함**
  (aspect 는 실패시에만 카운터를 올리므로 안 걸려 있어도 같은 결과). Task 2 의 프록시 증명 테스트에 대응물 부재
  이중계측 없음 확인 · 알림 카운터가 "실제 발송 수" 맞음 확인 · 접근자 2개 실코드 대조 확인
Task 3: parked — NotificationSender fan-out 에 예외 격리 부재
  (NotificationCommandServiceImpl.notify():52 forEach, @Order·try/catch 전무 → 한 sender 가 던지면 뒤가 막힘)
  Ruling: Task 3 이전부터 있던 **도메인 결함**이라 무침투 제약상 이 Task 에서 고칠 수 없다. 계측 sender 의 send() 는
    카운터 증가 한 줄뿐이라 실제 위험은 낮다. 별도 항목으로 추적한다.
  틀렸을 때 비용: 어느 sender 가 예외를 던지면 뒤 sender(Log·WebSocket)의 알림이 실제로 안 나감.
Task 3: minor (deferred): 두 aspect 의 케밥 변환 로직 중복(schedulerName/consumerName) — 공통 유틸 추출 후보. 최종 리뷰에서 triage
Task 3: minor (deferred): 식별자 태그 음성 대조는 현재 시그니처상 항상 통과 — 미래 회귀 가드로만 유효
Ruling: 내 계획서의 consumer 태그 기대값 `routing-replan` 이 틀렸다 → 실측 `routing-replan-event` 로 정정
  — 이유: RoutingReplanEventConsumer 는 Event 가 Consumer 앞에 붙어 접미사 제거 후 routing-replan-event 가 된다. 구현이 맞다.
  — 틀렸을 때 비용: 없음(문서를 실측에 맞춘 것).
Task 3: fix round 1/5 진행 중 — Important-1(catch 분기 테스트 + 프록시 부착 증명) · Important-2(@TransactionalEventListener AFTER_COMMIT)
Task 4: 구현 완료 (sdd-t4, commit cc448f4) — 무침투 확인: 도메인 수정 0건. 순수 추가 2파일
Task 4: 리뷰 완료 (sdd-t4-review) — 스펙 ✅ / 품질 **승인** (Critical 0 · Important 0 · Minor 0)
  리뷰어 독립검증: javap 로 spring-websocket-7.0.8.jar 역어셈블해 이벤트 생성자 시그니처 확인 ·
  지표명 충돌 grep 대조 · updateAndGet 의 CAS 재시도 시맨틱 정확 · Gauge 약한참조 함정은 싱글턴 빈 필드라 무해
  핵심: 기존 프로덕션 `LocationSocketEventListener` 가 **같은 이벤트 2개를 이미 구독**해 동작 중
  → "이벤트가 실제로 발행되는가"는 프로덕션에서 이미 검증된 상태. Task 2·3 과 달리 새 메커니즘이 아님
Task 4: minor (deferred): STOMP 핸드셰이크→이벤트 발행의 자동화된 증명 부재
  (기존 LocationSocketEventListener 조차 테스트 없음). 리뷰어 제안: @SpringBootTest(RANDOM_PORT) +
  실제 STOMP 클라이언트로 connect/disconnect 후 /actuator/prometheus 에서 값 확인 — 별도 태스크 후보. 최종 리뷰에서 triage
Task 4: ⏸ 전체 회귀 미확인 — 구현자가 대상 테스트 3개만 돌림. sdd-t3 수정 완료 후 컨트롤러가 한 번에 실행 예정
Task 4: 전체 회귀 확인 완료 — 컨트롤러가 `./gradlew test --rerun-tasks` 직접 실행, **288개 통과 실패 0**
  (281 Task3 + 4 Task3수정 + 3 Task4 = 288 로 수치 정합). 관측 테스트 17개 / 6개 클래스
Task 4: complete (commit cc448f4, review clean, 1 deferred minor)
Task 3: fix round 1/5 완료 보고 (commit 71b8771) — 재리뷰 진행 중 (sdd-t3-rereview)
Task 5: 착수 (Task 1 에만 의존하므로 Task 3 재리뷰와 병행)
Task 3: fix round 1/5 (Important-1 ①② · Important-2 전부 addressed, 0 open; commits d42254e..71b8771)
  재리뷰 독립검증: SampleConsumer 는 src/test 에 있고 애너테이션 없어 프로덕션 스캔 미해당(grep 확인) ·
  BusLocationUpdatedEvent 발행 지점은 ingest() 단 한 곳(grep) · reportSelf·ingest 둘 다 @Transactional 이고
  MockBusLocationSource.tick 은 빈 경유 외부호출이라 프록시 통과 → AFTER_COMMIT 로 누락되는 경로 부재 ·
  부착 테스트는 실제 프로덕션 빈(BusLocationPushConsumer)을 주입받아 isAopProxy 확인 → 미부착 시 반드시 FAIL
Task 3: complete (commits 0224f90..71b8771, review clean, 1 parked + 2 deferred minor)

=== 계측 3종(Task 2·3·4) 종료 — 288개 통과, 관측 테스트 17개/6클래스, 도메인 수정 0줄 ===

Task 5: 컨트롤러 직접 검증 — 스크레이프 대상 **5/5 up** (backend·node·postgres·prometheus·redis)
  `schoolbus_scheduler_last_success_age_seconds` **4건** — approach-no-show 4.5s · connection-loss 9.9s ·
  location-simulation 0.9s · sos-escalation 20.1s (각 주기 3·10·15·30초와 정합)
  `schoolbus_stomp_sessions` 1건 값 0
  → **Task 2~4 계측이 Prometheus 까지 실제로 도달함을 최초 실증**
Task 5: node-exporter 기동 실패 — macOS Docker Desktop 이 `rslave` 마운트 전파 미지원
  Ruling: `- /:/host:ro,rslave` → `rslave` 제거. 내 계획서가 리눅스 기준 설정을 그대로 쓴 결함이다.
    macOS 에서 node-exporter 가 보는 "호스트"는 맥이 아니라 Docker Desktop 의 리눅스 VM 이라는 한계를 compose 주석에 남기게 함.
  틀렸을 때 비용: 로컬 디스크·load average 수치를 맥 것으로 오해. 운영(리눅스)에서는 정상 동작.
Task 5: **발견 — `tomcat_threads_*` 지표가 통째로 부재** (`tomcat_sessions_*` 만 존재)
  원인: Spring Boot 가 Tomcat MBean 레지스트리를 기본 비활성으로 두어 TomcatMetrics 가 스레드 풀 지표를 못 읽음
  영향: 설계 §6.1 의 스레드 포화 패널 4개가 전부 빈 값이 됨. Task 9 Step 8 에서 잡으려던 항목이 조기 발현
  Ruling: Task 5 에서 함께 고친다 — `server.tomcat.mbeanregistry.enabled: true` 추가 + 백엔드 재기동 + **실제 지표 확인**.
    이유: 대시보드(Task 7) 제작 전에 지표가 있어야 하고, 지금 sdd-t5 가 스택을 들고 있어 재기동 비용이 가장 싸다.
    틀렸을 때 비용: Task 5 리뷰 범위가 compose 밖으로 조금 넓어짐. 지표 부재로 빈 패널을 만드는 것보다 싸다.
Task 5: 구현 완료 (sdd-t5, commit c1b9f4b) — docker-compose.yml + prometheus.yml 2파일
  컨트롤러 재확인: target 5/5 up · 스케줄러 게이지 4건 · stomp 1건 · 메모리 prometheus 40MB + exporter 3종 29MB (예산 내)
Task 5: ⚠ 내가 추가 지시한 Tomcat mbeanregistry 항목이 커밋에 미포함(application.yml 미변경, tomcat_threads_* 여전히 부재)
  Ruling: **Task 5b 로 분리한다.** 이유 — 그 지시는 Task 5 브리프 범위 밖에서 내가 끼워 넣은 것이고,
    커밋 c1b9f4b 는 브리프대로 정확히 완료됐다. 리뷰 범위를 흐리지 않고 별도로 추적하는 편이 낫다.
    틀렸을 때 비용: 항목 하나가 별도 커밋으로 갈라짐. 추적은 원장이 한다.
Task 5b: 진행 중 (sdd-t5) — server.tomcat.mbeanregistry.enabled: true + 실지표 확인 + http_server_requests p95 확인
Task 5: 리뷰 진행 중
Task 5: 리뷰 완료 (sdd-t5-review) — 스펙 ✅ / 품질 **승인** (Critical 0 · Important 0 · Minor 2)
  리뷰어 확인: 이미지 태그 4종 전부 고정(latest 없음) · 보존7일·주기15초가 설계와 일치 ·
  depends_on 조건 없는 리스트라 backend 사망 후에도 prometheus 생존(관측도구 본분에 부합) ·
  postgres-exporter 는 service_healthy, redis 는 healthcheck 부재라 조건 없는 게 맞음 ·
  node-exporter macOS 한계가 주석(154~161행)에 정직하게 기록됨
Task 5: minor (deferred→Task 6 에서 처리): postgres-exporter DATA_SOURCE_NAME 평문 비밀번호에
  "로컬 전용, prod 는 별도 처리" 경고 주석 부재 — 관측 스택을 prod compose 로 옮길 때 그대로 복사할 위험
Task 5: minor (deferred): 관측 스택 박스형 헤더 주석이 파일의 기존 단일라인 스타일과 다름 — 내 brief 원문 탓. 최종 리뷰에서 triage
Task 5: complete (commit c1b9f4b, review clean, 2 deferred minor)
Task 5b: complete (commit e929383) — application.yml 1파일. tomcat_threads_* 3종 수집 확인
  (busy=1 · current=10 · config_max=200). 설계 §6.1 스레드 포화 패널의 지표 확보
Ruling: 계획서 Task 9 의 검증 명령에서 `localhost:8080` 을 컨테이너 네트워크 호출로 정정
  — 이유: 로컬 backend 는 `expose: 8080` 만 있고 `ports` 가 없어 호스트에서 닿지 않는다. proxy(:80)도
    기본 기동 대상이 아니라 떠 있지 않다(실제로 HTTP 000 연결거부 확인). 내가 에이전트들에게 준
    "proxy 경유" 지시가 틀렸고 그 탓에 p95 확인이 두 번 헛돌았다.
  — 틀렸을 때 비용: 없음(실측으로 확인한 사실 반영).

=== 컨트롤러 직접 검증 (2026-08-21, 부하 해소 후) ===
  cardinality 음성 대조: uri 라벨 = /actuator/health · /actuator/prometheus · /api/auth/login · /api/buses/{id}
    → **원시 id 노출 0건. 설계 §10 R1 실측으로 닫힘**
  히스토그램: /actuator/health p95 = 0.133s 계산됨 (API 엔드포인트는 표본 부족으로 NaN — 설정 결함 아님)
  Tomcat: busy=1 · current=10 · config_max=200
  Grafana datasource: uid=prometheus · health OK
  대시보드 지표 9종 전부 존재: jvm_threads_states_threads(6) · hikaricp_connections_active/pending ·
    jvm_gc_pause_seconds_sum(2) · process_files_open_files · node_load1 · pg_stat_activity_count(25) ·
    redis_evicted_keys_total · redis_memory_used_bytes
  → **Task 7·8 대시보드가 쓸 지표가 전부 확보됐다. 빈 패널이 생길 항목 없음**
Task 6: 구현 완료 (sdd-t6, commit 3c3709d) — docker-compose.yml + grafana provisioning 3파일 + .gitkeep
  컨트롤러 확인: datasource uid=prometheus · health OK("Successfully queried the Prometheus API") ·
  .gitkeep 포함 · Task 5 리뷰의 Minor(평문 비밀번호 경고 주석) 함께 반영됨
Task 6: 리뷰 진행 중 (sdd-t6-review)
Task 7: 착수 — 실측 확인된 지표만 쓰도록 브리프에 목록 주입
Task 6: 리뷰 완료 (sdd-t6-review) — 스펙 ✅ / 품질 **승인** (Critical 0 · Important 0 · Minor 1)
  리뷰어 확인: uid 고정 · allowUiUpdates=false · foldersFromFilesStructure=false · 볼륨 2개 모두 :ro ·
  이미지 태그 고정 · prod 파일 미변경 · 포트 3000 충돌 없음
Task 6: minor (deferred): `.gitkeep` 의 존재 이유가 저장소 어디에도 안 남음(파일 0바이트, 커밋 본문 없음)
  → 향후 지우려는 사람이 이유를 모름. Task 7 이 그 폴더에 JSON 을 채우면 자연 해소되므로 최종 리뷰에서 triage
Task 6: ⚠ 절차 누락 — 구현자가 `task-6-report.md` 를 작성하지 않음. 검증은 컨트롤러가 직접 수행해 결과에는 영향 없음
Task 6: complete (commit 3c3709d, review clean, 1 deferred minor)
Task 7: 구현 완료 (sdd-t7, commit 6af4702) — 1-api.json · 2-server.json. 쿼리 27개 중 26개 값 반환
  (빈 1개 = "전체 에러율", 최근 5분 5xx 가 0건이라 빈 벡터 — 정상)
Task 7: 리뷰 완료 (sdd-t7-review) — 스펙 ✅ / 품질 **승인** (Critical 0 · Important 2 · Minor 1)
  리뷰어 실측 재확인: brief 이탈 3건 전부 타당 · datasource uid 21개 패널 전수 `prometheus` ·
  process/system_cpu_usage 는 이미 percentunit(내 우려는 반증됨) · rate(5m) vs scrape(15s) 는 표준 권장 ·
  JSON 유효·gridPos 겹침 없음·refId 중복 없음
Task 7: Important-1 — GC 패널 unit "s" 인데 expr 은 무차원 비율(초/초) → percentunit. **값도 설명도 맞는데 화면만 틀리는 형태**
Task 7: Important-2 — 커넥션 획득 대기 패널이 확보된 `_max` 를 안 그림(평균만) → 스파이크 희석
Ruling: 내 브리프가 설계 스펙 §6.1 보다 좁았다 — "커넥션 사용시간"(hikaricp_connections_usage_seconds_*)·
  "스왑"(node_memory_SwapTotal_bytes) 두 패널 누락. 두 지표 모두 실재(리뷰어 실측). **수정 라운드에 포함시킨다.**
  — 이유: 리뷰어는 "구현자 책임 아님"으로 참고 전달했으나, 스펙 준수 관점에서는 채워야 할 구멍이다.
  — 틀렸을 때 비용: 패널 2개 추가 작업. 안 채우면 설계와 구현이 어긋난 채로 남는다.
Task 7: minor (deferred): 디스크 mountpoint 하드코딩(/var/lib/docker). fstype 필터는 4개 마운트가 걸려 더 모호하다는
  실측 근거로 **현행 유지 판정**. EC2 재확인 경고는 description 에 이미 존재
Task 7: fix round 1/5 진행 중 — Important 2건 + 누락 패널 2개
Task 8: 진행 중 (sdd-t8)
Task 7: fix round 1/5 (Important 2건 + 누락 패널 2개 전부 addressed, 0 open; commit 0b34367)
  재리뷰 검증: GC unit s→percentunit(expr·description 은 원래 맞아 미변경 — 불필요 수정 없음 확인) ·
  id8 에 _max 추가 후 제목 "(평균·최대)" 갱신, 두 시계열 모두 초 단위로 일치 ·
  id20 커넥션 사용시간 신규(평균+최대, description 에 "평균은 낮은데 최대만 튀면 트랜잭션 안 외부호출 신호") ·
  id21 스왑 신규(macOS 단서 포함, 다른 node 패널과 동일 형태) ·
  21개 패널 gridPos 전수 계산해 겹침 없음 확인, refId 중복 없음, datasource uid 존재
Task 7: complete (commits b3833f8..0b34367, review clean, 1 deferred minor)
Task 8: 구현 완료 (sdd-t8, commit b6afee1) — 3-data.json(10패널) · 4-pipeline.json(6패널)
  19개 타깃 중 16개 값 반환. 빈 3개는 기반 Counter 미증가로 시계열 미생성(정상)
Task 8: 리뷰 진행 중 (sdd-t8-review) — 설계 §6.2·§4.1 과 직접 대조 지시함(브리프가 좁을 수 있어서)

=== 컨트롤러 실측 실험: MON-8 정지 감지 + MON-14 발현 (2026-08-21) ===
실험: `docker compose stop redis` → 75초 대기 → `start redis` → 50초 대기 (down 미사용)

[1] 기준선        connection-loss 2.3s · location-simulation 3.9s · sos-escalation 17.1s
[2] Redis 정지 후  connection-loss 14.4s(증가) · location-simulation 26.6s(증가) · sos-escalation 14.6s
[3] 복구 직후      전 지표 "없음" → backend target `down` (`context deadline exceeded`)
[4] 60초 뒤        5/5 up 으로 자동 회복

**발견 1 — MON-14 가 예상보다 무겁다.** Redis 지연 시 Tomcat 스레드가 소진돼
  `/actuator/prometheus` 스크레이프 자체가 타임아웃 → **모든 지표 조회 불가**.
  관측이 가장 필요한 순간에 대시보드가 통째로 빈다. **견고성 항목이 아니라 관측의 전제 조건.**
  → 계획서 MON-14 항목에 실측 반영함.

**발견 2 — MON-1 이 Redis 반경을 넓혔다.** `location-simulation` 도 함께 정지했다.
  버스 좌표를 Redis 로 옮기면서 이 스케줄러가 Redis 에 의존하게 됐다.
  (실험 설계 시 나는 이걸 "Redis 무관 스케줄러"로 가정했는데 틀렸다.)

**발견 3 — 실패 카운터는 끝까지 0.** `schoolbus_scheduler_failures_total` 이 한 번도 증가하지 않았다.
  스케줄러 4개가 자기 안에서 예외를 잡아 삼키므로 aspect 의 catch 가 안 탄다 —
  **Task 2 에서 문서화한 한계가 실측으로 확인됨.** 경과 게이지만이 정지를 드러낸다.

**MON-8 판정: 동작함.** 정지 시 경과 게이지가 증가하고 복구 시 회복된다(설계 §11-4 완료 조건 충족).
사용자 요청 접수 (2026-08-21): 관리자 웹 운영 현황 화면 + API — BG 계획서에 **T7(BG-21·BG-22)** 로 기록
  **착수 시점: 관제 대시보드 작업(Task 9·10) 완료 후.** architectural 이라 brainstorming 선행 필요
  실측 확인: BusDetailResponse.RosterEntry 에 **탑승 상태 필드가 없고**, "누락 학생" 조회 API 도 부재
  (미승차는 NO_SHOW 알림으로만 나감). 위치·경로·명단·승하차기록은 이미 다 있음 — 재구현 금지
Task 8: 리뷰 (sdd-t8-review) — 스펙 ❌(설계 §6.2 대비 패널 3개 누락, **내 브리프 축소 탓**) / 품질 승인
  리뷰어 실측: byName 매처 4개가 백엔드 소스의 실제 태그값과 일치 · 배수 계산 18/60·60/200·90/300·180/600 정확 ·
  legend {{consumer}} 하드코딩 아님 · Redis 축출 description 이 설계 경고문 충족 · 단위 전부 일치
Task 8: fix round 1/5 (누락 패널 3개 + Minor 전부 addressed, 0 open; commit a30a789)
  재리뷰 검증: 캐시적중률 percentunit(분자·분모 모두 rate 라 무차원) · Redis 클라이언트 description 이
  "누수" vs "0=재시작·전면 끊김" 두 모양 구분 · Kafka 처리량이 소비지연과 같은 행 짝 배치,
  "지연↑+처리량↓=컨슈머 정지" vs "지연↑+처리량 유지=발행량 초과" 구분 ·
  활성커넥션 사각지대 명시 · 기존 datname 필터 4개·락 패널 무변경 확인 · gridPos 전수 계산
Task 8: complete (commits 6af4702..a30a789, review clean, 0 parked)
Ruling: **브리프가 설계보다 좁은 문제가 Task 7·8 연속 발생.** 남은 Task 9·10 은 내가 직접 스펙과 대조해 보강한 뒤 착수한다.
  — 이유: 에이전트는 브리프만 보고, 브리프는 계획서에서 뽑히므로 계획서가 좁으면 아무도 모른다. 두 번 다 리뷰어가 잡았다.
  — 틀렸을 때 비용: 내 대조가 부실하면 같은 누락이 3번째로 반복된다.

=== 대시보드 4장 확정 ===
  1. API 개요        school-bus-api       (Task 7)
  2. 서버/JVM        school-bus-server    21패널 (Task 7 + 수정)
  3. 데이터 계층      school-bus-data      13패널 (Task 8 + 수정)
  4. 파이프라인 생존   school-bus-pipeline  6패널  (Task 8)
Task 9: 완료 보고 (sdd-t9, commit 8ee4ee4) — 8항목 중 6개 완전 통과, 2개 부분(지시대로 생략)
  §11-1 내부 200 통과 / 외부 404 는 proxy 미기동으로 미검증(운영 차단은 nginx.prod.conf:149 설정상 유지)
  §11-5 수신 증가 확인 / 정지 거동은 재기동 필요로 미검증
  §11-6 프로비저닝 복원 · §11-7 메모리 · §11-8 MeterBinder 는 직접 실행
  ⚠ exporter 3종 메모리 68.45MiB — 설계 §7 예산 55MB 대비 24% 초과. 원인: Go 런타임 고정 오버헤드는
    시계열량에 비례해 낮아지지 않음. 전체 합계는 총예산 대비 여전히 낮음
  컨트롤러 확인: DEPLOYMENT.md:591 "알람·APM 부재" 유지됨 ✅ · MON-13 은 `[ ] ⬜ 예정` 유지 ✅
  (내 grep 이 "선행 완료"라는 주석 문구에 오탐했으나 실제 체크박스는 미체크)
Task 9: fix round 1/5 (MON-10 허위 완료 표기 addressed; commit d874735)
  `✅ 완료` → `🟡 범위 축소`. show-details 완화는 "폐기"로 확정(예정 아님), 근거는 design §3.2
Task 9: complete (commits 81f952d..d874735, review clean, 0 parked)
Task 10: 구현 완료 (sdd-t10, commit cfd5387) — OBSERVABILITY_DASHBOARDS.html, SVG 4개, Two-Pass 통과
  구현자 발견: **로컬 docker-compose.yml 에 maxmemory 미설정**(실측 `maxmemory 0`)이라 Redis 축출이
  로컬에서 재현 불가 — 운영(`--maxmemory 128mb --maxmemory-policy allkeys-lru`)에서만 발생. 문서에 명시함
  구현자 자체 수정: SVG hp- 그림에서 `<b>` 태그가 `<text>` 밖으로 이탈한 버그를 렌더 검증 중 발견·수정(`<tspan font-weight="bold">`)
Task 10: minor (deferred): 축출 패널 description 이 "운영 128MB"라고 적어 로컬 무제한임을 암시하나,
  **로컬에서 이 패널이 항상 0이라는 사실**을 직접 말하지는 않음. 최종 리뷰에서 triage
Task 10: 리뷰 진행 중
Task 10: 리뷰 완료 (sdd-t10-review) — 스펙 ✅ / 품질 **승인** (Critical 0 · Important 0 · Minor 2)
  리뷰어 전수 검증: 43패널을 Grafana JSON 4개에서 직접 추출해 문서 표와 대조(누락·추가 0) ·
  스케줄러 주기 3·10·15·30초를 소스 직접 대조 · bus-ttl-seconds=15 · Tomcat max=200 ·
  Lettuce 60초 · 로컬 maxmemory 부재 vs prod 128mb 확인 · 커스텀 지표 6종·컨슈머 태그 4종 대조 ·
  html-docs 기계검증 전부 통과(viz 내 pre 0 · 클래스/marker 중복 0 · 태그 균형 · TOC↔heading 일치) ·
  **헤드리스 Chrome PDF 재렌더로 4개 그림 눈으로 확인** · 개조식 종결 위반 0건
Task 10: minor (deferred): §5.2 축출 패널 행에 "로컬은 항상 0" 미기재(§7.2 에는 있고 링크로 연결됨)
Task 10: minor (deferred): §7.2 가 "설정 부재로 추론"으로 서술 — 실측(`maxmemory 0` 직접 조회) 근거를 인용하면 강도가 올라감
Task 10: ⚠ 리뷰어 "확인 불가" — 문서의 `t=125s`·`t=135s` 타임라인 값은 원시 로그가 아니라 **재구성값**이다.
  산술적으로 앞뒤가 맞고 왜곡은 없으나 원본 타임스탬프 미확보. **실측처럼 읽힐 여지가 있어 최종 리뷰에서 triage 대상**
Task 10: complete (commit cfd5387, review clean, 2 deferred minor)

=== 모니터링 Task 1~10 전부 완료 · 최종 브랜치 리뷰로 이동 ===
최종 수정: fix wave 1 (4건 전부 addressed; commit 62ac543)
  재리뷰 검증: gridPos 10패널 좌표 직접 검산(겹침·공백 없이 연속) · max-uri-tags 경로를
  spring-boot-micrometer-metrics-4.1.0.jar 의 spring-configuration-metadata.json 에서 재확인 ·
  매핑 애너테이션 82건 grep 재확인 · **headless Chrome PDF+스크린샷 렌더로 캡션 클리핑 없음 확인** ·
  무침투 유지(`grep -v observability` 공백)
최종 수정: parked — 그림의 `t=125s` 눈금에 "약"·"(산출값)" 표기가 없어 `t=135s` 와 시각적 무게가 다름
  Ruling: **park.** 요약문(§7)이 t=125s·t=135s 둘 다 산출값이라 명시하고 있고, 재리뷰가 non-blocking 판정했다.
    fix wave 는 1회이며 잔여는 판정으로 남긴다(SDD 절차).
  틀렸을 때 비용: 그림만 보는 독자가 t=125s 를 실측으로 오인할 여지. 본문을 읽으면 해소됨.
최종 수정: (b) 판정 — max-uri-tags=150 은 부족·과잉 위험 모두 낮음. 라우트 템플릿 단위 집계라
  실제 고유 uri ≈ 90 안팎 추정. 다만 실측 미검증이므로 "배포 후 관측 필요"는 유효

=== 모니터링 작업 완결 (Task 1~10 + 최종 리뷰 + 수정 1회) ===
  커밋 28개 · 318 테스트 통과 · 도메인 코드 수정 0줄 · 최종 판정 **병합 가능**
