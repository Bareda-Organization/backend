# MVP 배포 설계 — AWS 단일 인스턴스 + Vercel + 앱마켓

- 작성일: 2026-08-10
- 상태: 설계 확정 대기
- 범위: 백엔드·인프라 AWS 배포, Flutter Web Vercel 배포, 모바일 앱 마켓 배포, 배포 자동화
- 비범위: 다중 인스턴스 스케일아웃, 무중단 배포, APM·알람 체계, 부하 테스트

---

## 0. 확정된 전제 (사용자 결정)

| # | 항목 | 결정 |
|---|---|---|
| 1 | 배포 성격 | 포트폴리오·데모용 |
| 2 | 도메인 | 보유(또는 신규 구입) — HTTPS 필수 |
| 3 | 앱 마켓 | Google Play + App Store **정식 출시** |
| 4 | 배포 방식 | GitHub Actions → ECR → EC2 |
| 5 | 인프라 | 관리형 전환 없이 **컨테이너 현행 유지** |

---

## 1. 결론 요약

**EC2 1대에 백엔드·Postgres·Redis·Kafka·nginx 를 컨테이너로 올리고, 웹은 Vercel, 앱은 스토어로 분리 배포하는 3-경로 구조.** AWS 관리형 서비스는 ECR·S3·SSM 만 채택하고 RDS·ElastiCache·MSK·API Gateway·ALB 는 전부 탈락.

```
                    ┌──────────────────────────────┐
  브라우저 ────────▶│ Vercel  app.<도메인>          │  Flutter Web (정적)
                    └───────────────┬──────────────┘
                                    │ https / wss  (크로스오리진)
  모바일 앱 ────────────────────────┤
  (Play / App Store)                │
                                    ▼
                    ┌──────────────────────────────┐
                    │ EC2 t3.medium  api.<도메인>   │
                    │ ┌──────────────────────────┐ │
                    │ │ nginx (443, TLS 종단)     │ │
                    │ └────────────┬─────────────┘ │
                    │              ▼               │
                    │     backend (Spring, 1개)    │
                    │      │      │      │         │
                    │  postgres  redis  kafka      │
                    └──────────────────────────────┘
                         │                    ▲
                    S3(백업)            ECR(이미지) ◀── GitHub Actions
```

---

## 2. AWS 관리형 서비스 검토 결과

### 2.1 탈락 — API Gateway

프론트가 **SockJS 없는 순수 STOMP over WebSocket** 사용(`frontend/lib/core/ws/impl/stomp_gateway_impl.dart:24`). API Gateway 의 WebSocket API 는 route selection expression 으로 메시지를 자체 파싱해 백엔드로 넘기는 모델이라 **Spring STOMP 브로커로의 투명 프록시 불가**. REST 만 API GW 로 보내고 WS 를 분리하면 진입점 이원화로 복잡도만 증가.

### 2.2 탈락 — ALB

WebSocket 지원과 ACM 무료 자동갱신은 장점이나, **월 약 $16 고정**에 EC2 가 1대뿐이라 분산이라는 본래 가치가 부재. 백엔드 다중화 시점에 재검토 대상.

### 2.3 탈락 — MSK

| 옵션 | 월 비용(개략) |
|---|---|
| MSK Serverless | 클러스터당 $0.75/시간 ≈ **$547** |
| MSK Provisioned (kafka.t3.small × 2 브로커 최소) | ≈ **$110** |
| 컨테이너 KRaft 단일 브로커 | **$0** |

서버 총비용의 3~14배. Kafka 가 시스템의 척추(`@KafkaListener` 18개 토픽 — 알림·위치 push·노선 재계획 전 경로가 경유)인 것과 **브로커 이중화 필요성은 별개**. 단일 인스턴스 데모에 다중 브로커는 과잉.

### 2.4 탈락 — ElastiCache

Redis 용도가 `RedisLocationRepository` 의 **TTL 9초 위치 캐시**(`app.location.tick-ms` 3초 × 3). 유실 시 다음 tick 에 자동 복구되는 데이터. 월 약 $17 지출 근거 부재.

### 2.5 탈락 — RDS

자동 스냅샷·PITR 은 명확한 이점이나 월 약 $22 추가. 데모 성격에서는 **EBS 볼륨 + 일 1회 `pg_dump` → S3** 로 대체 가능. 실사용자 데이터 축적 시점에 전환 검토.

### 2.6 채택 — nginx 유지

`infra/proxy/nginx.conf` 가 WebSocket 업그레이드·타임아웃·헤더 상속 함정까지 해결된 검증된 자산. **certbot 컨테이너 추가만으로 HTTPS 완결**. 추가 비용 0.

### 2.7 채택하는 AWS 서비스

| 서비스 | 용도 | 월 비용(개략) |
|---|---|---|
| **ECR** | 백엔드 이미지 저장소 — Actions→EC2 경로의 필수 고리 | ~$0.2 |
| **S3** | DB 덤프 백업 보관(7일) | ~$0.1 |
| **SSM Parameter Store** | JWT_SECRET·DB 비밀번호·NCP 키 보관. Standard 티어 무료 | $0 |
| **SSM Session Manager** | SSH 키·22번 포트 없이 EC2 접속 및 배포 명령 실행 | $0 |
| **CloudWatch Logs** | 컨테이너 로그 수집(보관 7일) — 인스턴스 사망 시에도 로그 생존 | 무료 티어 내 |
| **IAM OIDC 역할** | GitHub Actions 가 장기 액세스키 없이 AWS 접근 | $0 |
| Route 53 | DNS — 등록업체 무료 DNS 로 대체 가능 | $0.5 |

### 2.8 인스턴스 사이징 — t3.medium(4GB)

| 컨테이너 | 실사용 메모리(개략) |
|---|---|
| Spring Boot (Java 25) | 800MB ~ 1GB |
| Kafka (KRaft, heap 512m 하향 설정) | ~700MB |
| Postgres 16 | ~250MB |
| Redis + nginx | ~80MB |
| **합계** | **약 2GB** |

t3.small(2GB)은 OS 몫이 부재해 **OOM Killer 가 Kafka 또는 Spring 을 종료할 위험**. t3.medium 이 안전선. 프론트 컨테이너는 EC2 에서 제외(웹은 Vercel 담당)하므로 그만큼 여유 확보.

### 2.9 총 비용 (서울 리전, 개략치)

| 항목 | 월 |
|---|---|
| EC2 t3.medium | ~$38 |
| EBS gp3 30GB | ~$3 |
| Public IPv4 | ~$3.6 |
| ECR·S3·Route53 | ~$1 |
| Vercel Hobby / SSM / CloudWatch | $0 |
| **합계** | **약 $45 (6.5만원)** |

별도 1회성: Google Play 개발자 등록 $25, Apple Developer Program $99/년, 도메인 연 1~2만원.

---

## 3. 배포 차단 결함 (배포 전 반드시 해소)

### B1. prod 프로파일에 로그인 가능한 계정 부재 — **치명**

- 현상: 시드(`db/migration-local/V2__seed_data.sql`)가 local 프로파일 전용이라 prod DB 의 계정이 0개.
- 악화 요인: 최초 관리자 생성 경로가 코드상 부재. `AuthCommandService:46` 이 회원가입에서 `ATTENDANT`·`ACADEMY_ADMIN`·`PLATFORM_ADMIN` 을 403 으로 거부, `MemberController` 도 `PLATFORM_ADMIN` 생성을 거부(주석: "전역 관리자는 signup/seed 전용").
- 결과: 배포 성공해도 **누구도 로그인 불가**.
- 해소: **`demo` 프로파일 신설**. 시드 파일은 복제하지 않고 기존 `db/migration-local/V2__seed_data.sql` 을 `local`·`demo` 두 프로파일이 공유하되, 비밀번호 해시를 **Flyway placeholder `seedPasswordHash`** 로 외부화. local 은 기존 기본값(평문 `password`), demo 는 SSM 주입값을 쓴다 — 19KB 시드 중복 없이 배포 환경만 강한 비밀번호가 된다.
- ⚠️ 부작용: 적용 완료된 마이그레이션 파일을 수정하므로 **기존 로컬 DB 는 Flyway checksum mismatch 로 기동 실패**. `docker compose down` 후 재기동으로 해소(`stop`/`start` 로는 해소 불가).

### B2. prod 프로파일이 Mock 위치 소스를 비활성 — **데모 화면 정지**

- 현상: `application.yml` prod 섹션이 `location.mock.enabled=false`, `gps.enabled=true`, `bus-mock.enabled=false`, `bus-gps.enabled=true`.
- 결과: 실 기사 단말의 GPS push 가 없는 데모 환경에서 **버스가 움직이지 않아 실시간 관제 화면이 정지 상태로 보임**.
- 해소: `demo` 프로파일에서 mock 계열 활성화. prod 프로파일 자체는 원본 보존.

### B3. NCP Directions 키 부재 시 핵심 기능 500

- 현상: `routing.provider` 기본값이 `naver`. 키가 없으면 배차·시뮬레이션·노선 생성이 401 을 받아 500 으로 표출.
- 해소: SSM Parameter Store 에 `NAVER_DIRECTIONS_KEY_ID`·`NAVER_DIRECTIONS_KEY` 저장 후 주입. 키 확보 불가 시 `ROUTING_PROVIDER=osrm` 로 폴백(무료 공개 OSRM, 정확도·안정성 열위).

### B4. `SPRING_PROFILES_ACTIVE` 미설정 시 기본값이 `local`

- 현상: `application.yml` 의 `spring.profiles.active: local` 이 기본값. 현재 `docker-compose.yml` 은 프로파일을 주입하지 않음.
- 결과: 운영에서 **localhost DB 를 바라보고 로컬 데모 시드가 실행**될 위험.
- 해소: 운영 compose 에서 `SPRING_PROFILES_ACTIVE` 명시 주입. 미설정 시 기동 실패하도록 방어 검토.

### B5. Actuator 부재 — 배포 성공 판정 수단 없음

- 현상: `build.gradle` 에 actuator 의존성 부재. 헬스 엔드포인트 없음.
- 결과: 컨테이너 healthcheck·배포 후 검증을 "포트가 열렸는가" 수준으로만 가능. DB·Kafka 연결 실패를 기동 성공으로 오판.
- 해소: `spring-boot-starter-actuator` 추가, `health` 만 노출, `/actuator` 는 nginx 에서 외부 차단(내부 healthcheck 전용).
- 참고: `SecurityConfig:65` 가 `/actuator/health` 를 이미 `permitAll` 처리 — 보안 설정 변경 불필요.
- 한계: Spring Boot 기본 health 에 **Kafka 인디케이터 부재**. 자동 포함 대상은 DataSource·Redis·디스크·ping. Kafka 장애는 health 로 감지 불가하며 CloudWatch 로그로 확인.

### B6. Vercel 에 Flutter 빌더 부재

- 현상: Vercel 은 Flutter Web 을 인식하는 프리셋이 부재. Git 연동만으로는 빌드 불가.
- 해소: **GitHub Actions 에서 `flutter build web` 수행 후 Vercel CLI 로 prebuilt 산출물 배포**(`vercel deploy --prebuilt`). 빌드 시 `--dart-define=API_BASE_URL=https://api.<도메인>` 주입.

---

## 4. 아키텍처 상세

### 4.1 EC2 구성 (운영 compose)

로컬 `docker-compose.yml` 과 **별도 파일**(`docker-compose.prod.yml`)로 분리. 로컬 파일은 개발 재현성(볼륨 없음·포트 개방) 목적이라 운영에 부적합.

| 서비스 | 로컬 대비 변경 |
|---|---|
| `postgres` | **named volume 부착**(영속화), 호스트 포트 미개방, 비밀번호 SSM 주입 |
| `redis` | 호스트 포트 미개방 |
| `kafka` | named volume 부착, `KAFKA_HEAP_OPTS=-Xmx512m -Xms512m`, 호스트 포트 미개방 |
| `backend` | ECR 이미지 pull(빌드 아님), `SPRING_PROFILES_ACTIVE=demo`, `.env` 주입, `awslogs` 로깅 드라이버 |
| `frontend` | **제거** — 웹은 Vercel 담당 |
| `proxy` | 443 개방, TLS 인증서 마운트, `/` 라우팅 제거(프론트 부재), Swagger 는 basic auth 보호 |
| `certbot` | **신설** — 인증서 발급·자동 갱신 |

### 4.2 단일 인스턴스 제약 (스케일아웃 금지)

`infra/proxy/nginx.conf` 에 이미 기록된 제약이 여전히 유효.

1. `@Scheduled` 4개(`LocationSimulationScheduler`·`ConnectionLossScheduler`·`ApproachNoShowScheduler`·`SosEscalationScheduler`)가 인스턴스마다 실행 → **알림 중복 발송**
2. 버스 위치 저장소가 `InMemoryBusLocationRepository` → 인스턴스 간 미공유 (학생 위치는 `RedisLocationRepository` 로 이미 해소)
3. WebSocket STOMP 세션이 인스턴스 로컬 → 브로커 릴레이 부재

→ **backend 컨테이너는 반드시 1개**. 오토스케일링·다중 태스크 구성 금지. 이 제약은 문서와 운영 compose 주석 양쪽에 명시.

### 4.3 배포 시 다운타임

단일 인스턴스이므로 `docker compose up -d` 재생성 중 **수십 초의 다운타임 발생**. 데모 성격에서 수용. 무중단이 필요해지면 4.2 의 3개 제약 해소가 선행 조건.

### 4.4 도메인·TLS

| 호스트 | 대상 | 인증서 |
|---|---|---|
| `api.<도메인>` | EC2 nginx | Let's Encrypt (certbot, 90일 자동 갱신) |
| `app.<도메인>` | Vercel | Vercel 자동 발급 |

### 4.5 크로스오리진 처리

웹(`app.<도메인>`)과 API(`api.<도메인>`)가 서로 다른 출처가 되므로 로컬의 same-origin 이점이 소멸.

- REST: `CORS_ALLOWED_ORIGINS=https://app.<도메인>` 주입. `SecurityConfig:89` 가 `/api/**` 에 적용.
- WebSocket: `frontend/lib/core/api/api_config.dart:29` 가 **`https` → `wss` 자동 유도**하므로 클라이언트 코드 수정 불필요. 서버 측 `WebSocketConfig:39` 의 `setAllowedOriginPatterns("*")` 는 **운영에서 허용 목록으로 축소**(보안 하드닝 항목).
- Vercel rewrites 로 same-origin 을 만드는 대안은 **Vercel 이 WebSocket 프록시를 미지원**하므로 배제.

### 4.6 시크릿 관리

```
SSM Parameter Store (SecureString)
  /school-bus/demo/JWT_SECRET
  /school-bus/demo/DB_PASSWORD
  /school-bus/demo/NAVER_DIRECTIONS_KEY_ID
  /school-bus/demo/NAVER_DIRECTIONS_KEY
  /school-bus/demo/SEED_ADMIN_PASSWORD
        │  EC2 인스턴스 역할로 조회
        ▼
  배포 스크립트가 /opt/school-bus/.env 생성 (600)
        ▼
  docker compose --env-file
```

- GitHub Actions 는 **IAM OIDC 로 역할 assume** — 장기 액세스키를 저장소 시크릿에 두지 않음.
- `.env` 는 git 미추적 상태 유지(`.gitignore:21` 에 이미 존재).

### 4.7 백업

- 일 1회 `pg_dump` → `s3://<버킷>/db/YYYY-MM-DD.sql.gz`, S3 수명주기로 7일 후 삭제.
- 복구 절차를 운영 문서에 기재(검증 없는 백업은 백업이 아님).

### 4.8 관측

- 컨테이너 로그를 `awslogs` 드라이버로 CloudWatch Logs 전송, 보관 7일.
- `/actuator/health` 를 compose healthcheck 와 배포 후 스모크 테스트에 사용. 외부 노출은 nginx 에서 차단.

---

## 5. CI/CD 설계

### 5.1 백엔드 — `.github/workflows/deploy-backend.yml`

트리거: `main` push 중 `backend/**` 변경.

1. Java 25 셋업 → `./gradlew test` (실패 시 배포 중단)
2. `./gradlew bootJar`
3. Docker 이미지 빌드 → ECR push (태그: `git sha` + `latest`)
4. IAM OIDC 로 역할 assume
5. **SSM Send Command** 로 EC2 에서 배포 스크립트 실행 — SSH 키·22번 포트 불필요
6. `/actuator/health` 스모크 테스트, 실패 시 워크플로 실패 처리

**EC2 에서 빌드하지 않는 이유**: Gradle(Java 25) 빌드가 2GB 안팎을 소비해 t3.medium 에서도 실행 중인 컨테이너들과 경합 시 OOM 위험.

### 5.2 웹 — `.github/workflows/deploy-web.yml`

트리거: `main` push 중 `frontend/**` 변경.

1. Flutter 셋업 → `flutter test`
2. `flutter build web --release --dart-define=API_BASE_URL=https://api.<도메인> --dart-define=ENABLE_QUICK_LOGIN=false`
3. `vercel deploy --prebuilt --prod`

**`ENABLE_QUICK_LOGIN` 은 반드시 false** — 시드 계정 목록이 로그인 화면에 노출됨.

### 5.3 앱 — 수동 릴리스

스토어 심사가 임계경로이므로 자동화 대상에서 제외. 빌드 명령만 문서화.

---

## 6. 배포 순서

| 단계 | 내용 | 소요(예상) |
|---|---|---|
| **0** | 배포 차단 결함 B1~B6 해소 (코드·설정 변경) | 1~2일 |
| **1** | AWS 기반 구성 — VPC 기본, EC2, ECR, S3, SSM 파라미터, IAM 역할·OIDC | 반나절 |
| **2** | 도메인 DNS 설정 + certbot 발급 → `https://api.<도메인>` 기동 확인 | 반나절 |
| **3** | GitHub Actions 백엔드 파이프라인 연결 → 자동 배포 검증 | 반나절 |
| **4** | Vercel 프로젝트 생성 + 웹 파이프라인 연결 → `https://app.<도메인>` 오픈 | 반나절 |
| **5** | 보안 하드닝 검증 (CORS·WS origin·Swagger 보호·포트·시크릿) | 반나절 |
| **6** | Google Play 등록 → 내부테스트 → 정식 심사 | 1~2주 |
| **7** | App Store 등록 → 심사 | 2~4주 |

**1~5 단계 완료 시점에 웹 데모가 공개 가능**. 6~7 은 병렬 진행하며 스토어 심사 대기가 지배적.

### 앱 스토어 준비물 (별도 산출 필요)

- 개인정보처리방침 URL (호스팅 필요 — `app.<도메인>/privacy` 로 배치)
- Google Play 데이터 안전 양식 — **위치 데이터 수집 신고 필수**
- 백그라운드 위치 사용 시 Google 별도 심사 양식 (반려 빈발 구간)
- iOS `Info.plist` 위치 권한 사용 목적 문구
- 앱 아이콘·스크린샷·설명

---

## 7. 산출 파일 목록

| 파일 | 성격 |
|---|---|
| `docker-compose.prod.yml` | ✅ 작성 완료 — 운영 컨테이너 구성 |
| `infra/proxy/nginx.prod.conf` | ✅ 작성 완료 — 443·TLS·Swagger 보호·프론트 라우팅 제거 |
| `infra/certbot/init-cert.sh` | 신규 — 최초 인증서 발급 |
| `infra/scripts/bootstrap-ec2.sh` | 신규 — EC2 초기 세팅(docker·compose·에이전트) |
| `infra/scripts/deploy.sh` | 신규 — SSM 이 호출하는 배포 스크립트 |
| `infra/scripts/backup-db.sh` | 신규 — pg_dump → S3 |
| `.github/workflows/deploy-backend.yml` | 신규 |
| `.github/workflows/deploy-web.yml` | 신규 |
| `backend/src/main/resources/db/migration-local/V2__seed_data.sql` | 수정 — 비밀번호 해시를 Flyway placeholder 로 외부화 |
| `backend/src/main/resources/application.yml` | 수정 — actuator 노출·시드 placeholder·`demo` 프로파일·WS origin |
| `backend/build.gradle` | 수정 — actuator 추가 |
| `backend/Dockerfile` | 수정 — 런타임 이미지에 curl(컨테이너 healthcheck 용) |
| `backend/src/main/java/src/backend/global/config/WebSocketConfig.java` | 수정 — origin 허용 목록 외부화 |
| `frontend/vercel.output.config.json` | 신규 — Vercel prebuilt 라우팅·캐시 헤더 |
| `docs/DEPLOYMENT.md` | 신규 — 운영 절차서(배포·롤백·복구) |

---

## 8. 검증 기준

배포 완료 판정은 아래 전부 통과를 조건으로 한다.

1. 컨테이너 내부에서 `/actuator/health` 가 `UP` 반환 (DataSource·Redis 포함, **Kafka 는 미포함**). 외부에서는 404 여야 정상
2. `https://app.<도메인>` 에서 데모 계정 로그인 성공
3. 관리자 관제 화면에서 **버스 마커가 실제로 이동**(B2 해소 확인)
4. 배차 시뮬레이션 실행이 500 미발생(B3 해소 확인)
5. WebSocket 연결이 `wss` 로 수립되고 위치 갱신 수신
6. `main` 에 커밋 push 시 자동 배포 후 1~5 재통과
7. 브라우저 콘솔에 CORS 오류 부재
8. `nmap` 기준 EC2 개방 포트가 80·443 뿐 (22·5432·6379·9092 폐쇄)
9. `pg_dump` 백업이 S3 에 적재되고, 복구 리허설 1회 성공

---

## 9. 알려진 한계 (수용)

| 한계 | 사유 |
|---|---|
| 배포 시 수십 초 다운타임 | 단일 인스턴스. §4.2 제약 해소가 선행 조건 |
| 인스턴스 사망 시 복구 수동 | 데모 성격. Auto Scaling Group 미구성 |
| DB 백업 주기 24시간 (최대 24시간 유실) | RDS PITR 미채택의 대가 |
| Kafka 단일 브로커 (복제 없음) | 브로커 사망 시 미소비 이벤트 유실 |
| 알람·APM 부재 | CloudWatch Logs 만 수집. 장애 인지는 수동 |
