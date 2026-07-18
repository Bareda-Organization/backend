⏺ User approved Claude's plan                                                                                                                           
⎿  Plan saved to: ~/.claude/plans/delegated-wandering-fairy.md · /plan to edit                                                                        
reference.md 전면 채택 리팩터 + location 실시간 push 채널

     Context (배경)                                                                                                                                     
                                                                                                                                                        
     backend/docs/reference.md(Backend Architecture & Development Convention v1.0)가 새로 들어왔다. SOLID·DIP·Event Driven(Kafka)·CQRS·Port/Adapter·MSA 
     전환 준비를 목표로 한 "최종 아키텍처 청사진"이다. 이 문서는 현재 코드 및 기존 컨벤션(CLAUDE.md, backend/docs/CODE_CONVENTIONS.html)과 3곳에서      
     충돌한다:                                                                                                                                          
                                                                                                                                                        
     1. 단순 CRUD 인터페이스 — 기존 규칙은 "service·repository 전부 spec/impl 분리"인데 reference.md 2장은 "단순 CRUD는 인터페이스 만들지               
     마라"(Bus/Tenant/Student를 반례로 지목).                                                                                                           
     2. 패키지 구조 — 현재 controller/service(spec·impl)/repository/entity/dto vs reference.md 3장                                                      
     command/query/domain/event/projection/repository/dto/controller/infrastructure(CQRS).                                                              
     3. 서비스 간 직접 호출 — 현재 RideEventServiceImpl→NotificationService 직접 호출 vs reference.md 6장 "이벤트(Kafka) 경유" 강제.                    
                                                                                                                                                        
     사용자 결정(확정):                                                                                                                                 
     - 적용 범위 = 전면 채택 + 리팩터 (기존 12개 모듈까지 새 구조로 재편)                                                                               
     - CRUD 인터페이스 = 기존도 정리 리팩터 (불필요 인터페이스 제거)                                                                                    
     - 이벤트 백본 = Kafka 지금 도입 (docker-compose 브로커 + producer/consumer)                                                                        
     - 리팩터 순서 = 빅뱅 재편 먼저 (기존 모듈 전부 재편 후 location 구현)                                                                              
     - 다음 기능 = location 실시간 push 채널 (백로그 최상단, reference.md 8장 CQRS+Projection 예시의 첫 실적용)                                         
                                                                                                                                                        
     의도한 결과: ① reference.md가 코드·문서의 단일 컨벤션이 된다. ② 기존 12개 모듈이 CQRS+Event+Infrastructure 구조로 통일된다. ③ 지금까지 없던        
     서버→클라이언트 실시간 push(위치·알림)가 동작한다.                                                                                                 
                                                                                                                                                        
     ▎ ⚠️ 가드레일(중요): "빅뱅 재편 먼저"라도 한 번에 컴파일 안 되는 거대 변경 금지. 모듈 단위로 재편하고 각 단계 끝에서 ./gradlew build(테스트 포함)  
     ▎ green을 유지한다. 아래 Phase 2가 모듈별로 쪼개진 이유. 이건 대규모·되돌리기 어려운 변경이므로, Phase 경계마다 커밋하고 빌드를 확인한 뒤 다음으로 
     ▎ 넘어간다.                                                                                                                                        
                                                                                                                                                        
     ---                                                                                                                                                
     목표 아키텍처 (모듈 표준 레이아웃)                                                                                                                 
                                                                                                                                                        
     <module>/                                                                                                                                          
       controller/       @RestController — command/query(concrete) 또는 Port(spec)만 주입                                                               
       command/          쓰기 서비스(생성/수정/삭제) — concrete. 이벤트 발행                                                                            
       query/            읽기 전용 서비스 — concrete. command를 호출하지 않음(CQRS)                                                                     
       domain/           @Entity, enum, 도메인 이벤트 값 타입, 상태전이 메서드
event/            과거형 이벤트 record(LocationUpdatedEvent 등) + 리스너                                                                         
projection/       이벤트→읽기모델(Redis) 빌더. 비즈니스 로직 없음                                                                                
repository/        JPA 접근(spec) + 비-JPA 구현(impl, 예: Redis)                                                                                 
dto/              요청/응답 record (Entity 직접 반환 금지)                                                                                       
infrastructure/   외부 기술 어댑터(KafkaPublisher, RedisXxxRepository, OsrmClient, FcmSender, WebSocket sender)

     - spec/impl(인터페이스)는 "변할 곳"만 — Port: LocationSource, NotificationSender, LocationRepository(→Redis), RouteEngine/EtaService(예정), 신규   
     DomainEventPublisher(Kafka impl). 단일 구현 CRUD 서비스는 concrete.                                                                                
     - 이벤트 과거형(reference.md 14장): LocationUpdatedEvent, RideCompletedEvent, StudentBoardedEvent, SosTriggeredEvent.                              
     - 외부 기술은 infrastructure/ (reference.md 13장): Kafka/Redis/WebSocket/OSRM 어댑터. 비즈니스 계층은 구현을 모른다.                               
                                                                                                                                                        
     ---                                                                                                                                                
     Phase 0 — 컨벤션 단일화 (문서)                                                                                                                     
                                                                                                                                                        
     리팩터의 기준 스펙을 먼저 확정한다(코드보다 먼저).                                                                                                 
                                                                                                                                                        
     - backend/docs/CODE_CONVENTIONS.html(기존 캐논 컨벤션 문서, HTML)을 reference.md 원칙으로 갱신 — 위 표준 레이아웃, spec/impl 판단 기준(변경        
     가능성), CQRS command/query 분리, 이벤트 과거형, Kafka 백본, infrastructure 계층. 전역 문서 정책(HTML + 인라인 SVG + Prism + Two-Pass) 준수.       
     - 전역 정책상 신규 기술문서는 HTML이어야 하므로, reference.md 내용을 CODE_CONVENTIONS.html에 흡수 후 reference.md는 삭제하거나 "→                  
     CODE_CONVENTIONS.html로 통합됨" 한 줄만 남긴다.                                                                                                    
     - CLAUDE.md(프로젝트) 컨벤션 문구 수정: "service·repository는 전부 spec/impl 분리" → "변경 가능성 있는 것(포트)만 spec/impl; 단순 CRUD는 구현체만, 
     CQRS command/query 분리, 이벤트는 Kafka 경유". PROJECT_MASTER_PLAN.md 11.3 컨벤션 절도 동일 갱신.                                                  
                                                                                                                                                        
     ---                                                                                                                                                
     Phase 1 — 공용 기반: Kafka + Redis + 이벤트 백본                                                                                                   
                                                                                                                                                        
     기존 모듈을 건드리기 전, 재편이 올라탈 인프라를 먼저 세운다.                                                                                       
                                                                                                                                                        
     1. 의존성 backend/build.gradle: implementation 'org.springframework.kafka:spring-kafka' 추가(Boot 4.1이 버전 관리). Redis 스타터는 이미 있음(현재  
     미사용).                                                                                                                                           
     2. docker-compose(backend/docker-compose.yml 또는 루트, 경로 확인): Kafka 서비스 추가 — KRaft 모드(Zookeeper 불필요)로. Postgres·Redis 옆에.       
     3. 설정 application.yml: spring.kafka.bootstrap-servers, producer(JSON 직렬화)·consumer(group-id, earliest, JSON) 블록. prod는                     
     ${KAFKA_BOOTSTRAP_SERVERS}.                                                                                                                        
     4. Redis 빈 global/config/RedisConfig(현재 없음): RedisTemplate/RedisConnectionFactory. Projection 읽기모델 저장소.                                
     5. 이벤트 백본 — global/event/에 DomainEvent(마커/공통 필드: eventId·occurredAt·tenantId), DomainEventPublisher(Port).                             
     global/infrastructure/KafkaEventPublisher(impl, KafkaTemplate 래핑).                                                                               
     6. DB↔Kafka 이중쓰기 정합성(중요): 서비스가 DB 저장과 Kafka 발행을 같은 트랜잭션에 두면, 롤백 시 유령 이벤트/커밋 후 브로커 다운 시 유실이 생긴다. 
     → ApplicationEventPublisher로 인프로세스 발행 → @TransactionalEventListener(phase=AFTER_COMMIT)가 KafkaEventPublisher.publish() 호출 패턴을        
     표준으로. (커밋 후에만 Kafka로 나감. 더 강한 보장은 outbox 패턴 — MVP는 AFTER_COMMIT, 하드닝 과제로 남김.)                                         
     7. Consumer 멱등: Kafka는 at-least-once라 중복 소비가 정상. 기존 NotificationService.notify의 dedupKey(unique 제약)가 그대로 방어막이 된다 — 이    
     설계가 Kafka 전환으로 오히려 더 중요해짐(문서에 명시).                                                                                             
                                                                                                                                                        
     ---                                                                                                                                                
     Phase 2 — 기존 12개 모듈 빅뱅 재편 (모듈별 green build)                                                                                            
                                                                                                                                                        
     각 모듈에 동일 패턴을 적용. 순서는 의존 그래프상 피의존(잎) 먼저: notification → student·tenant·user·bus·route →                                   
     rideevent·sos·location(cross-module 발신자) → auth. global은 Phase 1에서 처리됨.                                                                   
                                                                                                                                                        
     모듈당 반복 작업(대표: bus, notification):                                                                                                         
     1. 패키지 이동: @Entity+enum → domain/, 외부 어댑터 → infrastructure/, 이벤트 → event/.                                                            
     2. CQRS 분리: XxxService(spec)+XxxServiceImpl → command/XxxCommandService + query/XxxQueryService(concrete, 인터페이스 제거). 컨트롤러가 두        
     concrete를 주입. query는 command를 호출하지 않음.                                                                                                  
     3. 인터페이스 정리: 단일 구현 CRUD(Bus/Tenant/Student/Member(user)/Route/RideEvent/Sos/Auth/Notification 오케스트레이션·Location 앱서비스)의 spec  
     제거 → concrete. Port는 유지(LocationSource, NotificationSender, LocationRepository). @WebMvcTest의 @MockitoBean 타입이 인터페이스→concrete로      
     바뀌지만 Mockito는 concrete도 모킹하므로 타입명만 변경.                                                                                            
     4. 직접 호출 → 이벤트(reference.md 6장): RideEventCommandService가 NotificationService를 직접 부르는 대신 RideCompletedEvent/StudentBoardedEvent   
     발행 → notification의 consumer(@KafkaListener 또는 이벤트 리스너)가 notify(BOARD_DONE/ALIGHT_DONE...) 호출. sos도 SosTriggeredEvent로 동일 전환.   
     dedupKey 멱등 유지.                                                                                                                                
     5. 모듈 끝날 때마다 ./gradlew build green + 커밋.                                                                                                  
                                                                                                                                                        
     ▎ 이 Phase가 가장 크고 위험. 잎 모듈부터 하되, cross-module 이벤트 전환(4번)은 발신·수신 양쪽 모듈이 모두 재편된 뒤 한 커밋에서 스위치한다(그      
     ▎ 전까지는 기존 직접 호출 유지 → 항상 컴파일 가능).                                                                                                
                                                                                                                                                        
     --- 
Phase 3 — location 실시간 push 채널 (플래그십)

     백로그 최상단 기능을 새 아키텍처의 레퍼런스 구현으로 만든다. 현재 WebSocket/STOMP 인프라는 완비돼 있으나 서버→클라이언트 push(broadcast)만 
     없음(SimpMessagingTemplate 미사용, /topic은 heartbeat용 최소구성).
     
     1. Command → Event: LocationServiceImpl.ingest()(모든 좌표 합류 단일 지점, location/service/impl/LocationServiceImpl.java:77)에서
     locationRepository.save() 후 LocationUpdatedEvent(과거형) 발행 → AFTER_COMMIT → Kafka.
     2. Projection → Redis: LocationUpdatedEvent consumer가 CurrentLocationProjection을 Redis에 갱신. InMemoryLocationRepository를 
     RedisLocationRepository(infrastructure/, TTL + 다중 인스턴스)로 교체 — LocationRepository Port 덕에 LocationService 무수정(reference.md 8장 예시
     그대로). Query side는 Redis에서 읽음.
     3. 서버→클라이언트 push(핵심 신규): projection/consumer가 SimpMessagingTemplate으로 push. 보안 급소 — /topic 브로드캐스트는 구독자 모두에게 열려
     학부모가 남의 자녀 위치를 볼 수 있다. 해결:
       - WebSocketConfig에 user destination 활성화(enableSimpleBroker("/topic","/queue") + setUserDestinationPrefix("/user")), push는
     convertAndSendToUser(principalName, "/queue/location", payload)로 대상자 본인에게만.
       - StompAuthChannelInterceptor(현재 CONNECT만 검증)에 SUBSCRIBE 인가 추가 — 구독 destination을 AuthUser의 4계층
     스코프(본인/자녀/담당버스/테넌트)와 대조해 범위 밖 구독 거부. 이 프로젝트 핵심인 "계층별 조회 권한 분리"를 push에서도 강제.
     4. 알림 push(공짜로 딸려옴): NotificationSender Port에 WebSocketNotificationSender(infrastructure/) 추가 — LogNotificationSender와 병행 등록.
     notify() 호출부 무변경으로 BOARD_DONE·SOS·CONNECTION_LOST가 실시간 push된다.
     
     ---
     검증 (end-to-end)
     
     - 각 Phase 경계: cd backend && ./gradlew build (전체 테스트 green). DB 불필요 단위/슬라이스 테스트는 즉시, @SpringBootTest는 인프라 필요.
     - 인프라 기동: docker compose up -d postgres redis kafka (Kafka 추가 후).
     - 실시간 push 시나리오(Phase 3):
       a. parent@school.com이 WebSocket /ws/location CONNECT(JWT) 후 자기 자녀 위치 구독.
       b. Mock 스케줄러(또는 student@가 POST /api/locations)로 좌표 유입 → LocationUpdatedEvent → Kafka → projection이 Redis 갱신 → parent에게 push 도착
     확인.
       c. 다른 자녀(권한 밖) 구독 시도 → 거부(SUBSCRIBE 인가) 확인.
       d. 승하차 기록(driver@) → BOARD_DONE 알림이 parent에게 실시간 push로도 도착 확인.
       e. SOS 발신→에스컬레이션이 이벤트 경유로도 기존과 동일 동작(멱등 1회 발송) 확인.
       - 클라이언트: websocat 또는 간단 STOMP 스크립트. redis-cli로 projection 키 확인, Kafka 콘솔 컨슈머로 토픽 흐름 확인.
     - 문서 갱신: PROJECT_MASTER_PLAN.md 12.1/12.2 — 실시간 채널 ✅, 아키텍처 이관 기록. 메모리 school-bus-backend-mvp.md·school-bus-code-conventions.mdd
     갱신.
     
     ---
     위험 & 가드레일 (정직한 평가)
     
     - 이건 동작 중인 MVP를 Kafka+CQRS로 전면 재작성하는, 되돌리기 어려운 대규모 작업이다. 단일 모놀리식 앱에서 인프로세스 통신에 Kafka는 엄밀히는
     과설계지만(사용자가 tradeoff 인지 후 선택), MSA 전환 대비라는 목적은 정당하다.
     - 완충: Phase 2의 이벤트 전환은 발신·수신 양쪽 재편 완료 후 한 커밋에서만 스위치하고, 그 전까지 기존 직접 호출을 유지해 항상 빌드 green.
     AFTER_COMMIT 발행으로 DB↔Kafka 정합성 확보. dedupKey로 중복 소비 방어.
     - 여러 세션에 걸칠 분량 — Phase 단위로 끊어 진행/커밋.
     
     ---
     후속 (이번 범위 밖, 백로그 갱신만)
     
     attendance(중)·schedule(중~대)·routing(대, 외부 API) 상세 계획은 코드 조사로 확정됨 — PROJECT_MASTER_PLAN.md에 12.3 절로 추가(공통:
     NotificationService.notify+dedupKey, SosEscalationScheduler 패턴, 명단=StudentRepository.findByAssignedBusId 파생 재사용). 미연결 NotificationType:
     SCHEDULE_RESULT→schedule, APPROACH/NO_SHOW→routing. 이번 작업(location)과 독립이라 문서 백로그 갱신만 하고 구현은 이후로.
     
     ---
     핵심 파일
     
     - 문서: backend/docs/CODE_CONVENTIONS.html(캐논 갱신), backend/docs/reference.md(흡수 후 정리), CLAUDE.md, backend/docs/PROJECT_MASTER_PLAN.md
     - 인프라: backend/build.gradle(+spring-kafka), docker-compose(+Kafka KRaft), application.yml(+kafka/redis), global/config/RedisConfig(신규),
     global/event/{DomainEvent,DomainEventPublisher}(신규), global/infrastructure/KafkaEventPublisher(신규)
     - 재편 대표: bus(CQRS+인터페이스 제거 패턴), notification(consumer로 전환), rideevent/sos(직접호출→이벤트)
     - 플래그십(location): location/service/impl/LocationServiceImpl.java(이벤트 발행),
     location/repository/impl/InMemoryLocationRepository.java→RedisLocatio