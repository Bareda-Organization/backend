# 바래다 (BARAEDA) — 학원 통학버스 운행·등하원 관리 백엔드

학원 통학버스의 **노선 편성 · 운행 · 승하차 · 알림**을 다루는 멀티 테넌트 백엔드입니다.
Spring Boot 4 · Java 25 · PostgreSQL · Redis 로 만들었고, 이 저장소는 **백엔드 전용**입니다.

- 엔드포인트 **106개**(+ local 전용 개발 도구 1개) · 테스트 **218클래스 1,285개**
- API 문서는 실행 후 Swagger UI 에서 봅니다 (아래 §3)

---

## 1. 먼저 필요한 것

| 항목 | 비고 |
|---|---|
| **Docker** | PostgreSQL · Redis 를 컨테이너로 띄웁니다 |
| **Java 25** | Gradle toolchain 이 자동으로 받아 씁니다. JDK 를 따로 설치하지 않아도 됩니다 |

> `git clone` 후 별도 설정 파일 없이 바로 실행됩니다. 외부 지도 API 키는 **주소 검증·경로 계산 기능을 쓸 때만** 필요합니다(§6).

---

## 2. 실행

### 2.1 인프라만 컨테이너로, 앱은 IDE·터미널에서 (권장)

개발 중에는 이 방식이 편합니다 — 코드를 고치면 devtools 가 자동으로 다시 띄웁니다.

```bash
docker compose up -d postgres redis     # DB(15432) · Redis(16379)
cd backend && ./gradlew bootRun         # http://localhost:8080
```

### 2.2 전부 컨테이너로

```bash
docker compose up -d --build            # 위 둘 + backend + proxy(nginx)
```

이때 앱은 호스트에 포트를 열지 않고 **프록시(:80)를 통해서만** 닿습니다.

### 2.3 포트

| 서비스 | 주소 | 비고 |
|---|---|---|
| 백엔드 | `localhost:8080` | `bootRun` 으로 띄웠을 때 |
| 프록시 | `localhost:80` | 전부 컨테이너로 띄웠을 때 |
| PostgreSQL | `localhost:15432` | DB 도구로 직접 접속 가능 (`schoolbus` / `schoolbus`) |
| Redis | `localhost:16379` | |
| Prometheus · Grafana | `localhost:9090` · `localhost:3000` | 관측 스택. `docker compose up -d` 에 포함(계정 `admin` / `admin`) |

> **5432·6379 가 아닌 이유** — 개발 노트북에서 다른 프로젝트가 기본 포트를 쓰는 경우가 잦고, 그대로 두면 테스트가 남의 DB 에 붙어 **원인을 알기 어려운 인증 실패 수백 건**으로 나타납니다.

### 2.4 데이터 초기화

로컬 PostgreSQL 은 **의도적으로 영속 볼륨을 두지 않습니다.** 컨테이너를 지웠다 다시 띄우면 Flyway 가 스키마와 데모 데이터를 매번 새로 만듭니다.

```bash
docker compose down                     # 컨테이너 제거 (-v 는 붙이지 않습니다)
docker compose up -d postgres redis     # 스키마 + 시드 재생성
```

`stop`/`start` 는 데이터가 남습니다 — 되돌리려면 반드시 `down` 을 거칩니다.
**앱을 재시작하지 않고 되돌리려면 §4 의 초기화 API** 를 쓰세요.

---

## 3. Swagger UI 로 API 써 보기

앱을 띄운 뒤 브라우저에서 엽니다.

```
http://localhost:8080/swagger-ui/index.html     # 2.1 로 띄웠을 때
http://localhost/swagger-ui/index.html          # 2.2 로 띄웠을 때
```

### 3.1 로그인 → 토큰 넣기

1. **`0. 인증 · 가입`** 태그의 `POST /api/v1/auth/login` 을 펼치고 **Try it out**
2. 아래 계정 중 하나를 넣고 실행 — **로컬 비밀번호는 전부 `password`** 입니다
   ```json
   { "login_id": "staffA", "password": "password" }
   ```
3. 응답의 `access_token` 을 복사
4. 화면 오른쪽 위 **Authorize** 버튼 → 값을 붙여넣고 Authorize

이후 모든 요청에 `Authorization: Bearer <token>` 이 자동으로 붙습니다.

### 3.2 역할별 데모 계정

| 역할 | 아이디 | 볼 수 있는 것 |
|---|---|---|
| 메인 관리자 | `sysadmin` | 전 학원 범위 — 학원 생성·현황, 감사 로그 |
| 학원 관계자 | `staffA` | 학원 A 의 학생·차량·노선·배차·승인 |
| 학원 관계자 | `staffB` | 학원 B — **학원 격리 확인용**(A 의 데이터가 안 보여야 정상) |
| 학부모 | `parentA1` | 연결된 자녀 2명의 회차·위치·변경 신청 |
| 학생 | `studentA4` | 본인 회차·노선 |
| 기사 | `driverA1` | 담당 회차, 운행 시작, 위치 송신 |
| 동승자 | `escortA1` | 승하차 처리, 비상 알림 |

### 3.3 태그 구성

API 는 사용자 갈래별로 5개 태그로 묶여 있습니다.

`0. 인증 · 가입` · `1. 학부모 · 학생 앱` · `2. 매니저 앱 (버스기사 · 동승자)` · `3. 관계자 웹` · `4. 메인 관리자 콘솔`

`local` 로 띄우면 `9. 개발 도구` 태그가 하나 더 보입니다(§4).

### 3.4 계정 상태를 확인하는 계정

계정 상태 게이트(§1.4)를 눌러 볼 수 있는 계정도 있습니다. **모두 비밀번호는 `password`** 이고, 아래가 정상 동작입니다.

| 아이디 | 로그인 | 그 뒤 |
|---|:-:|---|
| `staffPending` | **성공(200)** | 대기 화면 조회(`GET /auth/signup-status`)·로그아웃만 열리고, 나머지는 **`403 AUTH_PENDING`** |
| `studentRejected` | **성공(200)** | 위와 같고 재신청(`POST /auth/signup/reapply`)이 하나 더 열립니다 |
| `driverBlocked` | **거부(403)** | 차단된 계정이라 로그인 단계에서 막힙니다. 해제는 메인 관리자 몫 |

> 승인 대기·거절 계정이 **로그인은 되는 것이 의도**입니다 — 대기 화면이 자기 상태를 물어봐야 하기 때문입니다. 막히는 것은 로그인이 아니라 그 뒤의 호출입니다.

---

## 4. 개발용 초기화 API

Swagger 로 이것저것 눌러 데이터가 어지러워졌을 때, **앱을 재시작하지 않고** 시드 상태로 되돌립니다.

```
POST /api/v1/dev/reset
```

- DB 를 비우고 스키마·시드를 새로 적재한 뒤 Redis 의 위치 캐시까지 지웁니다
- **초기화 후에도 쓰던 토큰이 그대로 유효**합니다 — 다시 로그인할 필요가 없습니다
- **`local` 프로파일에서만 존재합니다.** 배포 환경에는 이 경로 자체가 없고, 테스트 실행 중에도 등록되지 않습니다

---

## 5. 테스트

```bash
cd backend
./gradlew test                                  # 전체 (218클래스 1,285개, 4분 안팎)
./gradlew test --tests '*OpenApiCoverageTest'   # 한 클래스만
```

- **대부분이 실제 앱을 띄우는 통합 시험**이라 PostgreSQL·Redis 컨테이너가 떠 있어야 합니다
- 기본값은 `schoolbus` DB 를 씁니다. 개발 데이터를 지키려면 전용 DB 로 나눕니다
  ```bash
  ./gradlew test -PtestDbUrl=jdbc:postgresql://localhost:15432/<DB이름>
  ```
- ⚠ Gradle 은 입력이 안 바뀌면 테스트를 **건너뛰고도 성공을 출력**합니다(`UP-TO-DATE`). 판정용으로 돌릴 때는 `--rerun` 을 붙이고, 결과는 `build/test-results/test/TEST-*.xml` 에서 셉니다

---

## 6. 외부 지도 API 키 (선택)

주소 검증(지오코딩)과 노선 계산은 네이버 API 를 씁니다. **키가 없어도 앱은 뜨고 대부분의 API 가 동작하지만**, 주소를 등록·검증하는 경로는 실패합니다.

```bash
cp backend/.env.example backend/.env    # 값을 채웁니다. 이 파일은 커밋되지 않습니다
```

---

## 7. 저장소 구성

```
backend/          Spring Boot 애플리케이션 (도메인 14개 + global 인프라)
  src/main/       프로덕션 코드
  src/test/       테스트 218클래스
  load/           k6 부하 시험 스크립트
infra/            nginx · Prometheus · Grafana · 배포 스크립트
docker-compose.yml        로컬 실행
docker-compose.prod.yml   운영(EC2) 실행
```

git 에는 **동작에 필요한 것만** 올립니다 — 사양·설계 문서와 작업 기록은 로컬에만 둡니다.

---

## 8. 배포

`.github/workflows/deploy-backend.yml` 이 테스트 → 이미지 빌드 → ECR → EC2 배포를 수행합니다.
**수동 실행 전용**(`workflow_dispatch`)이며, 실행 전에 AWS 자원과 시크릿 3종이 필요합니다. 절차는 배포 문서를 따릅니다.
