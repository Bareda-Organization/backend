# 프론트엔드 개발 환경 셋업

백엔드를 로컬에서 띄우고 관계자 웹(Next.js)과 앱 2종(Flutter)을 그 백엔드에 붙이는 절차. 기준 커밋 `0b8aa3e0`(2026-09-30) · 2026-10-01 R46 변경(Redis 칸 나누기 · `FIXTURE_DB` · 시뮬레이터 끄기)을 분기점 `66a139f1` 에서 코드와 대조해 반영.

- 제품 구성·라운드 추적: `docs/frontend/IMPLEMENTATION_PLAN.md`
- 코드 규칙: `docs/frontend/CONVENTIONS_REACT.md` · `docs/frontend/CONVENTIONS_FLUTTER.md`
- 엔드포인트 계약: `docs/API_SPEC.md`
- 배포·스테이징: `docs/infra/DEPLOYMENT.md` · `docs/infra/STAGING.md`

## 1. 준비물

| 대상 | 필요한 것 |
|---|---|
| 공통 | Git · Docker Desktop(Compose v2, 실행 중) |
| 백엔드 | Java 25 — `backend/build.gradle` 의 toolchain 고정값 |
| 관계자 웹 | Node.js 22(`frontend/apps/academy-web/Dockerfile` 의 `node:22-alpine`) · npm |
| 앱 2종 | Flutter(각 `pubspec.yaml` 의 Dart `^3.12.2`) · iOS 는 Xcode |

## 2. 클론

```bash
git clone https://github.com/mskim98/School-Bus.git
cd School-Bus
```

- 백엔드는 설정 파일 없이 기동됨 — `local` 프로파일 기본값(`application.yml`)이 DB `localhost:15432` · Redis `localhost:16379` · JWT 키를 채움
- `backend/.env`(`backend/.env.example` 을 복사)는 **컨테이너 모드(§3.2)에서만** 읽힘 — `docker-compose.app.yml` 의 `env_file`. `bootRun` 은 이 파일을 읽지 않음
- 네이버 키(`NAVER_MAPS_KEY_ID` · `NAVER_MAPS_KEY` · 장소 검색용 `NAVER_SEARCH_CLIENT_ID` · `NAVER_SEARCH_CLIENT_SECRET`)가 없으면 주소 변환·경로 계산·장소 검색 호출만 실패하고 나머지는 동작

## 3. 백엔드 띄우기

개발용 Docker 구성은 파일 둘로 갈림. 둘 중 하나를 고름.

### 3.1 인프라만 컨테이너 + 백엔드는 터미널·IDE (반복 개발용)

```bash
docker compose up -d          # postgres · redis
cd backend
./gradlew bootRun             # http://localhost:8080
```

| 서비스 | 호스트 포트 | 비고 |
|---|---|---|
| postgres | `15432` | DB·사용자·비밀번호 모두 `schoolbus` |
| redis | `16379` | 컨테이너 안 포트는 6379 |
| backend(`bootRun`) | `8080` | 코드 수정 시 devtools 가 자동 재시작 |

포트나 DB 를 바꿔 띄울 때:

```bash
./gradlew bootRun --args='--server.port=8230 --spring.datasource.url=jdbc:postgresql://localhost:15432/<DB이름>'
```

### 3.2 전부 컨테이너 (웹까지 포함)

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.app.yml down
```

- `down` 에도 `-f` 두 개를 그대로 줌. 빼면 backend·proxy·관측 컨테이너가 남음(번거로우면 `export COMPOSE_FILE=docker-compose.yml:docker-compose.app.yml`)
- 모든 HTTP 가 proxy `:3000` 한 곳을 지남

| 접근 | 주소 |
|---|---|
| 관계자 웹 | `http://localhost:3000` |
| API | `http://localhost:3000/api/v1/...` |
| WebSocket | `ws://localhost:3000/ws/location` |
| Swagger UI | `http://localhost:3000/swagger-ui/index.html` |
| Grafana | `http://localhost:3001`(admin / admin) |
| Prometheus | `http://localhost:9090` |

- `/actuator` 는 proxy 가 라우팅하지 않아 404 가 정상. 헬스는 `docker compose -f docker-compose.yml -f docker-compose.app.yml exec backend curl -s localhost:8080/actuator/health`
- proxy 가 `:3000` 인 이유: 네이버 지도 키의 서비스 URL 과 백엔드 CORS 허용 목록이 둘 다 `http://localhost:3000` 으로 등록됨. 다른 포트로 열면 지도 SDK 인증이 401 로 거절되어 "지도를 불러오지 못했습니다" 만 표시됨. 포트를 바꾸려면 NCP 콘솔의 서비스 URL 부터 바꿈

## 4. 기동 확인

`bootRun`(3.1) 기준:

```bash
curl -s http://localhost:8080/actuator/health          # {"groups":["liveness","readiness"],"status":"UP"}
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/v3/api-docs   # 200
```

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- 로그인 응답(`data` 안)의 `access_token` 값을 우측 상단 **Authorize** 에 넣음. `Bearer ` 접두사 없이 토큰 값만 — 인증 스킴이 HTTP bearer 로 등록돼 있어 헤더는 자동으로 붙음

## 5. 로그인 계정 (Flyway 로컬 시드)

- 로그인 값은 **이메일이 아니라 `login_id`**. 비밀번호는 전부 `password`(로컬 전용. 배포·스테이징은 별도 값)
- 요청: `POST /auth/login`(베이스 경로 `/api/v1` 생략 — `API_SPEC §1.1` 표기) · 본문 `{"login_id":"staffA","password":"password"}` · 헤더 `X-Client-Type: app | web`(웹은 refresh 토큰을 쿠키로, 앱은 본문으로 받음 — `API_SPEC §1.2.1`)
- 연속 5회 실패 시 계정이 `blocked`(`driverBlocked` 시드가 이 상태)

`backend/src/main/resources/db/migration-local/V2__seed_data.sql`(계정 20개)의 학원 A(`바래다학원 A`)·B·C:

| 역할 | 학원 A | 학원 B | 학원 C(운영정지) |
|---|---|---|---|
| 시스템 관리자(`system_admin`) | `sysadmin`(소속 학원 없음) | | |
| 학원 관리자(`staff`) | `staffA` | `staffB` | `staffC` |
| 학부모(`parent`) | `parentA1` · `parentA2` · `parentA3` | `parentB1` | |
| 학생(`student`) | `studentA4` | `studentB1` | |
| 기사(`driver`) | `driverA1` · `driverA2` | `driverB1` | |
| 동승자(`escort`) | `escortA1` · `escortA2` | `escortB1` | |

승인·차단 상태 시연용(모두 학원 A): `staffPending` · `parentPending`(승인 대기) · `studentRejected`(가입 거절) · `driverBlocked`(차단).

`db/migration-local/V13__demo_fleet.sql`(학원 A 소속 6개): 기사 `driverD3` · `driverD4` · `driverD5` · 동승자 `escortD3` · `escortD4` · `escortD5`.

`db/migration-demo/V14__demo_scale.sql`(학원 10곳 · 학생 600명 규모): `local` 프로파일에서만 적재됨(테스트 DB 는 제외). 계정 규칙:

| 역할 | 로그인 값 |
|---|---|
| 학원 관리자 | `staff01` ~ `staff10` |
| 기사 | `driver011` ~ (학원 번호 2자리 + 호차 1~3) |
| 동승자 | `escort011` ~ |
| 학부모 | `parent01001`(학원 01 의 학생 001) ~ `parent10060` |

## 6. 데이터를 시드 상태로 되돌리기

방법 셋. 상황에 맞게 고름(앞의 두 방법은 3.1 방식 기준).

| 방법 | 동작 |
|---|---|
| `bootRun` 재기동(3.1) | `local` 프로파일은 **기동마다** DB 를 `clean()` 후 다시 적재(`LocalFlywayCleanStrategy`). 데이터소스가 `localhost` 일 때만 실행되고 아니면 기동 실패 |
| `POST /dev/reset` (`/api/v1` 아래) | 앱 재시작 없이 DB 를 시드 상태로 되돌리고 위치 캐시를 비운 뒤 **내일 회차만** 생성. 인증만 요구(역할 무관). `local` 프로파일에서만 존재. 응답 `data.cleared_position_keys` |
| `docker compose down` 후 `up` | postgres 컨테이너에 영속 볼륨이 없어 컨테이너 재생성 시 초기화. 컨테이너 모드(3.2)의 backend 는 `clean()` 을 끈 채 기동하므로 backend 재시작만으로는 초기화되지 않음 |

- `docker compose down` 에는 `-v` 를 붙이지 않음
- `stop` / `start`(컨테이너 유지)는 데이터가 남음
- `POST /dev/reset` 이 지우는 위치 캐시는 Redis 키 패턴 전체 — **같은 Redis(`16379`)의 같은 칸(database)을 쓰는 다른 백엔드의 최신 좌표도 함께 지워짐**
- 백엔드를 둘 이상 동시에 띄우면(예: 작업 창마다 전용 DB) 같은 회차 번호의 위치 키가 섞여 지도의 버스가 두 경로를 번갈아 나옴(2026-10-01 R46-PARENT 실측). 서버마다 `--spring.data.redis.database=<번호>` 로 같은 Redis 안에서 칸을 나눔 — 번호는 서버끼리 달라야 하고 0 은 기본 칸(공유)이라 피함(Redis 기본 칸은 0~15). 시험(`./gradlew test`)은 Testcontainers Redis 라 해당 없음
- 관계자 웹 머리말의 **[테스트 데이터 초기화]** 버튼은 `NEXT_PUBLIC_TEST_DATA_RESET=true` 로 빌드·기동한 웹에만 보임(기본은 숨김)
- 시드 SQL 을 고친 뒤 이미 적용된 DB 로 앱만 다시 띄우면 `FlywayValidateException` 으로 기동 실패 — 체크섬 불일치이므로 `down` → `up` 으로 재구성. 단 `bootRun`(3.1)은 기동마다 `clean()` 하므로 해당 없고, 컨테이너 모드(3.2)와 `-PtestDbUrl` 시험 DB 에 해당

## 7. 관계자 웹 (`frontend/apps/academy-web`)

```bash
cd frontend/apps/academy-web
npm install
```

`.env.local`(git 추적 밖 — 새로 만듦)에 네이버 지도 웹 SDK 클라이언트 ID:

```
NEXT_PUBLIC_NAVER_MAP_CLIENT_ID=<키>
```

```bash
npm run dev      # http://localhost:3000
```

| 항목 | 값 |
|---|---|
| API 주소 | 환경변수 `NEXT_PUBLIC_API_BASE_URL`(기본 `http://localhost:8080`) 뒤에 `/api/v1` 이 자동으로 붙음 |
| WebSocket | 같은 변수의 스킴을 `ws` 로 바꾸고 `/ws/location` 을 붙임(`/api/v1` 은 붙지 않음) |
| 검사 | `npx next typegen && npx tsc --noEmit`(라우트 타입을 먼저 생성) · `npm run lint` · `npx vitest run --exclude '**/*[Rr]ealBackend*.test.ts'`(실서버 계약 시험 제외 — CI 와 같은 명령) |

- ⚠ **웹은 `:3000` 에서만 지도 인증·CORS 가 맞음.** `npm run dev` 는 기본 `3000` 을 쓰므로 3.2 의 proxy 와 동시에 띄울 수 없음 — 둘 중 하나만
- `NEXT_PUBLIC_*` 값은 브라우저 번들에 그대로 실림. 지도 키는 비밀이 아니고 보호는 NCP 콘솔의 서비스 URL 등록으로 함

## 8. 앱 2종 (`frontend/apps/manager-app` · `frontend/apps/parent-app`)

### 8.1 최초 1회 — 생성 코드 만들기

`*.g.dart` · `*.freezed.dart` 는 git 이 추적하지 않음. 새로 받은 저장소에서는 없으므로 아래를 먼저 실행:

```bash
cd frontend/packages/baraeda_core && flutter pub get && dart run build_runner build --delete-conflicting-outputs
cd ../../apps/manager-app         && flutter pub get && dart run build_runner build --delete-conflicting-outputs
cd ../parent-app                  && flutter pub get
```

- `parent-app` 은 `build_runner` 를 돌려도 생성 파일이 0개

### 8.2 실행

앱 디렉터리(`manager-app` 또는 `parent-app`)에서 — 두 앱의 인자가 같음:

```bash
flutter run \
  --dart-define=API_BASE_URL=http://localhost:8080/api/v1 \
  --dart-define=NAVER_MAP_CLIENT_ID=<키>
```

| 인자 | 없을 때 |
|---|---|
| `API_BASE_URL` | 기본값 `http://localhost:8080/api/v1`(`lib/core/constants/api_constants.dart`). 다른 포트·DB 로 띄운 백엔드에 붙일 때 지정 |
| `NAVER_MAP_CLIENT_ID` | **지도가 그려지지 않고 회색 격자만 표시**(SDK 가 키 없이 초기화되어 `NClientUnspecifiedException` code 800 — 2026-09-30 실측). 키 값은 어떤 파일에도 커밋하지 않음 |
| `KAKAO_NAVI_APP_KEY` | **매니저 앱만.** 비면 운행 화면의 `[카카오내비 길안내]` 버튼이 없음(§8.4). 키는 사용자 자원이라 저장소에 없음 |

- 키 값: 웹의 `frontend/apps/academy-web/.env.local` 의 `NEXT_PUBLIC_NAVER_MAP_CLIENT_ID` 와 같은 값
- 로그인 응답의 `refresh_token` 이 본문에 오므로 앱은 `X-Client-Type: app` 을 명시해 호출(`ApiConstants.clientType`)

빌드만 확인할 때(iOS 시뮬레이터용):

```bash
flutter build ios --simulator --debug \
  --dart-define=API_BASE_URL=http://localhost:8080/api/v1 \
  --dart-define=NAVER_MAP_CLIENT_ID=<키>
```

### 8.3 검사

```bash
flutter analyze
flutter test --exclude-tags real_backend   # 단위·위젯. 실서버 계약 시험(@Tags(['real_backend']))은 제외 — CI 와 같은 명령
```

- 한 번에 전부(백엔드 + 웹 + Flutter 4곳): 저장소 루트에서 `scripts/verify.sh`. 골라서는 `scripts/verify.sh web flutter`. CI(`.github/workflows/ci.yml`)가 같은 검사를 돎(`docs/infra/DEPLOYMENT.md §5.1`)
- `--exclude-tags` 는 패키지의 `dart_test.yaml` 에 태그 선언이 있어야 걸러짐(선언이 없으면 아무것도 안 걸러지는 빈 플래그). 실서버 계약 시험 파일 머리에는 `@Tags(['real_backend'])` 가 붙어 있어야 함

- 실서버 계약 시험은 `flutter test --tags real_backend --dart-define=API_BASE_URL=http://localhost:<전용포트>/api/v1` 로 돌림. 주소를 주지 않으면 스스로 실패함(`test/support/real_backend_target.dart`). **공유 DB(`schoolbus`)가 아닌 전용 DB 로 띄운 백엔드**에만 겨눔 — 이 시험은 실행하면서 DB 의 행을 바꿈
- 학부모 앱 실서버 시험 중 DB 에 SQL 로 픽스처를 심는 1건은 `--dart-define=FIXTURE_DB=<그 서버가 물고 있는 DB 이름>` 도 같이 받음(`parent-app/test/support/real_backend_target.dart`). 주지 않으면 던지지 않고 그 1건만 건너뜀 — `API_BASE_URL` 과 달리 생략이 실패가 아님
- 실서버 시험용 백엔드를 다른 서버와 같은 Redis 에 붙여 띄울 때는 `bootRun --args` 에 `--spring.data.redis.database=<번호>`(§6)를 같이 줌. 지도에 자기 위치 송신기를 붙여 확인할 때는 `DemoRunSimulator` 가 같은 회차에 위치를 써서 경로가 섞이므로 `--app.demo.enabled=false`(§9.6)도 줌 — 시뮬레이터가 실서버 시험 결과를 바꾸는지는 확인 못 함
- 백엔드 시험은 `-PtestDbUrl` 이 필수. `backend/scripts/test.sh` 가 전용 DB 를 만들고 끝나면 지움

### 8.4 카카오내비 길안내 — 매니저 앱 (RUN-08 · `Ruling 530~534`)

- 동작: 운행 화면 `[카카오내비 길안내]`(확정 ~ 운행 중 · 기사만) → `GET /runs/{runId}/navigation?scope=remaining`(`API_SPEC §4.16`) → 공식 SDK `kakao_flutter_sdk_navi` 의 `NaviApi.navigate` 가 카카오내비를 실행. 미설치면 `[설치하기]` 가 SDK 의 설치 안내 페이지(스토어로 연결)를 엶
- **키가 없으면 버튼이 없음** — 키 없이도 빌드·시험은 통과. 실기기 동작은 아래 준비물이 끝난 뒤에만 확인 가능

**사용자 준비물**(2026-10-01 Kakao Developers 문서 기준)

| # | 할 일 | 비고 |
|:-:|---|---|
| 1 | [Kakao Developers](https://developers.kakao.com/console/app) 에서 앱 생성 | 길안내는 카카오 로그인·동의항목 불필요. **별도 "카카오내비 사용 설정" 스위치는 없음**(문서 확인) |
| 2 | [앱] > [플랫폼 키] > **네이티브 앱 키** 확인 | 키마다 플랫폼 정보 등록 필요 |
| 3 | 네이티브 앱 키에 **Android** 등록 | 패키지명 `com.baraeda.manager_app` + **디버그·릴리스 키 해시 모두**(개발자마다 디버그 키스토어가 달라 각자 등록). 카카오내비 앱이 이 값으로 호출 앱을 검증 — 미등록이면 길안내 실패 |
| 4 | 네이티브 앱 키에 **iOS** 등록 | 번들 ID `com.baraeda.managerApp` |
| 5 | 빌드에 앱 키 주입 | `--dart-define=KAKAO_NAVI_APP_KEY=<네이티브 앱 키>` (`flutter run` · `flutter build apk/ipa` 공통) |
| 6 | iOS URL scheme 에 키 주입 | `frontend/apps/manager-app/ios/Flutter/Local.xcconfig`(git 밖 — 없으면 새로 만듦)에 `KAKAO_NATIVE_APP_KEY = <네이티브 앱 키>` 한 줄. 이 값이 `Info.plist` 의 `kakao<키>` 가 됨. 비우면 자리표시 `kakaoplaceholder` |
| 7 | 카카오내비 앱 설치된 실기기에서 `confirmed` 회차로 확인 | 시뮬레이터·에뮬레이터에는 카카오내비가 없어 `[설치하기]` 안내까지만 확인 가능 |

- 쿼터: 카카오내비 API 는 월간·일간 쿼터가 있고 상향은 카카오와의 협의가 필요(Kakao Developers 카카오내비 개요)
- 이미 들어 있는 설정(손댈 것 없음): iOS `Info.plist` `LSApplicationQueriesSchemes` = `kakaonavi-sdk`(SDK 의 설치 확인이 이 목록만 봄) · Android `<queries>` 의 카카오내비 패키지(`com.locnall.KimGiSa`)는 SDK 매니페스트가 병합

## 9. API 연결 시 확인할 것

1. **기본 주소**: `http://localhost:8080/api/v1` (proxy 모드는 `http://localhost:3000/api/v1`). 모든 API 경로에 `/api/v1` 접두사(`ApiPathPrefixConfig.API_PREFIX`)
2. **응답 봉투**: 성공 응답 본문은 `{"success":true,"data":{...},"message":null}`. 필드는 전부 snake_case
3. **인증**: `Authorization: Bearer <access_token>`. 인증 없이 열린 경로는 아래뿐이고 나머지는 전부 토큰 필요
   - `/api/v1` 아래 `GET /academies/search` · `POST /auth/signup` · `login` · `refresh` · `recover`(`PublicEndpoints`)
   - `/actuator/health` · `/actuator/prometheus` · `/ws/**` · `/swagger-ui/**` · `/v3/api-docs/**`
4. **CORS**(`/api/**` 만 대상): `app.cors.allowed-origins` 목록에 있는 출처만 허용. `local` 기본값:
   `http://localhost:3000` · `:5173` · `:4200` · `:8081` · `http://127.0.0.1:3000` · `:5173`
   - 목록 밖 출처는 `403`. `CORS_ALLOWED_ORIGINS`(쉼표 구분)를 주면 **기본 목록 전체를 대체**함 — 예: `CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5555 ./gradlew bootRun`
   - `prod` · `demo` · `staging` 은 기본값이 없어 미지정 시 허용 출처가 0개
5. **실시간 위치 스트림**: `/ws/location`(STOMP over WebSocket, 접두사 `/api/v1` 없음). 허용 출처는 REST 와 별개인 `app.ws.allowed-origin-patterns`(환경변수 `WS_ALLOWED_ORIGIN_PATTERNS`) — `local` 기본값은 `*`(모든 출처), `prod` · `demo` · `staging` 은 기본값이 없어 미지정 시 기동 실패. Origin 헤더를 보내지 않는 네이티브 앱은 이 제한과 무관
6. **버스 위치 시뮬레이터**: 위치는 기사 단말이 올리는 값이고 서버는 2분만 유효한 값으로 봄 — 아무도 올리지 않으면 지도가 비어 있음. `local` 프로파일은 `DemoRunSimulator` 가 기동 15초 뒤부터 기사 단말 자리를 대신해 시드 회차를 출발시키고 위치를 2초마다 올림. 끄려면 `--app.demo.enabled=false`(기본 `true` — `local` 프로파일에서만 존재)
7. **계약 문서**: 엔드포인트 목록·요청·응답·오류 코드는 `docs/API_SPEC.md`. 실제 스키마는 Swagger UI 가 가장 최신

## 10. 문제 해결

- **포트 충돌**(`15432` · `16379` · `8080` · `3000`): `lsof -iTCP -sTCP:LISTEN -P` 로 점유 프로세스 확인. 호스트 포트는 `docker-compose.yml` 의 `ports` 에서 조정
- **로그인 `401`**: 이메일이 아니라 `login_id`(§5)를 보냈는지, 비밀번호가 `password` 인지 확인. 이전 경로 `/api/auth/login` 은 존재하지 않음 — `/api/v1/auth/login`
- **로그인은 되는데 이후 요청이 `401`**: Swagger Authorize 에 `Bearer ` 접두사를 붙였는지 확인(토큰 값만 입력)
- **CORS 오류**: ① 개발 서버 주소가 허용 목록과 프로토콜·호스트·포트까지 일치하는지(`http://localhost:3000` 과 `http://127.0.0.1:3000` 은 다른 출처) ② 설정을 바꾼 뒤 백엔드를 재시작했는지 ③ 요청 경로가 `/api/` 로 시작하는지
- **지도가 회색 격자 / "지도를 불러오지 못했습니다"**: 앱은 `NAVER_MAP_CLIENT_ID` 인자(§8.2), 웹은 `.env.local` 의 키와 `:3000` 포트(§7)를 확인
- **`flutter run` 이 `*.freezed.dart` · `*.g.dart` 없다고 실패**: §8.1 의 `build_runner` 를 실행
- **`docker compose up` 후 backend 가 계속 재시작**(3.2): `docker compose -f docker-compose.yml -f docker-compose.app.yml logs backend` 로 원인 확인
