# 스테이징 — 팀원 체험용 서버 (집 PC + Cloudflare Tunnel)

멀리 있는 팀원이 관계자 웹과 Android 앱을 직접 써 보고 피드백하기 위한 서버. 실사용자용이 아니다 — 실사용자 단계는 `DEPLOYMENT.md`(AWS).

| 항목 | 결정 (2026-09-29 사용자 확정) |
|---|---|
| 서버 | 집 PC 1대(i5 10세대 · 16GB) · `docker-compose.staging.yml` 한 파일 |
| 공개 | Cloudflare Tunnel — 공유기 포트 개방 부재 · HTTPS 는 Cloudflare 가 처리 |
| 웹 | 같은 서버·같은 주소(Vercel 미사용 — 새로 고침 쿠키가 `SameSite=Strict` 라 웹과 API 가 다른 **사이트**가 되면 로그인 유지 불가). 운영은 `Ruling 481` 로 웹만 Vercel 이지만 웹·API 를 같은 사이트의 커스텀 도메인(`app.<도메인>` · `api.<도메인>`)에 두어 `Strict` 가 성립하게 한 것이고(`DEPLOYMENT.md §12.2`), 스테이징은 도메인 1개(`bus.<도메인>`)라 서버 한 곳에 묶음 |
| 앱 | Android 만 · APK 를 서버의 `/download/` 에 두고 QR 로 설치. iOS 는 제외(원격 설치에 Apple 개발자 등록 필수) |
| 데이터 | 백엔드 `local,staging` 프로파일 — 데모 시드 + 버스 시뮬레이터. **매일 새벽 시드 상태로 초기화** · 팀원이 웹 머리말 **[테스트 데이터 초기화]** 로 언제든 초기화(`Ruling 364`) |

모든 요청은 `https://bus.<도메인>` → Cloudflare → 집 PC 의 `cloudflared` → `proxy`(nginx) → `/api`·`/ws` 는 backend, `/download` 는 APK 폴더, 나머지는 web.

프록시 설정은 `infra/proxy/nginx.staging.conf` 다(개발용 `nginx.conf` 가 아니다, 2026-09-30 BR-230). 인터넷에 열린 주소라 운영 프록시의 보호를 옮겼다 — ①로그인·가입·복구·자녀 연결 경로는 접속자 IP 당 분당 20회로 제한(초과 `429`) ②**Swagger UI·`/v3/api-docs` 는 공개하지 않는다**(`404` — API 스펙은 로컬 `localhost:8080` 에서 본다) ③Cloudflare 의 `CF-Connecting-IP` 를 사설 대역(터널 컨테이너)에서 온 요청에서만 채택해 감사 로그의 접속 IP 가 터널 주소로 찍히지 않는다. 설정을 고친 뒤 문법은 파일 머리말의 `nginx -t` 한 줄로 확인한다.

---

## 1. 준비물

- 도메인 1개 — **Cloudflare Registrar 에서 사면** 네임서버 변경 단계가 없어진다
- Cloudflare 계정(무료)
- 집 PC 에 Ubuntu Server 24.04 LTS
- NCP(네이버 클라우드) 콘솔 — 지도 키에 새 주소·앱 패키지를 등록해야 한다(§5)

## 2. 집 PC 설치 (1회)

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # 다시 로그인해야 적용
```

- BIOS 에서 **정전 후 자동 전원 켜기**(AC Power Recovery = On) — 모든 컨테이너가 `restart: unless-stopped` 라 PC 만 켜지면 서버도 돌아온다

## 3. 코드와 비밀값 옮기기

⚠ GitHub 의 `main` 은 2026-09-30 23:13 시점(문서 포함 전부 push, `CLAUDE.md` 참고)까지만 반영돼 있고 그 뒤 R46 커밋은 push 전이다(push 는 사용자 확인 후 — `git log origin/main -1` 로 시점 확인). **최신 코드가 필요하면 GitHub 이 아니라 Mac 에서 받는다.**

```bash
# Mac: 시스템 설정 → 일반 → 공유 → "원격 로그인" 켜기. 그다음 집 PC 에서
git clone ssh://<mac 사용자>@<mac IP>/Users/mskim/Desktop/PJ/School-Bus
cd School-Bus

# git 에 없는 비밀값 파일 2개 (네이버 API 키 · 웹 지도 키)
scp <mac 사용자>@<mac IP>:Desktop/PJ/School-Bus/backend/.env backend/.env
scp <mac 사용자>@<mac IP>:Desktop/PJ/School-Bus/frontend/apps/academy-web/.env.local frontend/apps/academy-web/.env.local
```

## 4. Cloudflare Tunnel

1. Cloudflare 대시보드 → Zero Trust → Networks → Tunnels → **Create a tunnel** → `cloudflared` 선택
2. 설치 명령 화면에 나오는 **토큰**(`eyJ…`)만 복사한다 — 설치 명령은 실행하지 않는다(compose 가 띄운다)
3. Public Hostname — Subdomain `bus` · Domain `<도메인>` · Service **`HTTP`** · URL **`proxy:80`**

## 5. 네이버 지도 키에 새 주소 등록

NCP 콘솔 → Maps → 애플리케이션 수정. **안 하면 지도가 401 로 막혀 "지도를 불러오지 못했습니다" 만 뜬다.**

- Web 서비스 URL — `https://bus.<도메인>` 추가 (기존 `http://localhost:3000` 은 개발용이라 남긴다)
- Android 앱 패키지 이름 — `com.baraeda.parent_app` · `com.baraeda.manager_app`

## 6. `.env` 작성 후 기동

저장소 루트(`School-Bus/.env`, git 무시 대상)에 쓴다.

```bash
# 시드 계정 전부가 쓸 비밀번호의 BCrypt 해시
docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" '<팀원에게 알려 줄 비밀번호>' | tr -d ':\n'; echo
openssl rand -hex 32   # JWT_SECRET 용
```

```dotenv
COMPOSE_FILE=docker-compose.staging.yml
PUBLIC_URL=https://bus.<도메인>
# ⚠ 작은따옴표 필수 — 해시 속 `$` 를 compose 가 변수로 읽지 않게 한다
SEED_PASSWORD_HASH='$2y$10$...'
JWT_SECRET=<위 openssl 결과>
TUNNEL_TOKEN=<§4 토큰>
```

```bash
mkdir -p downloads
docker compose up -d --build      # COMPOSE_FILE 덕에 -f 불필요. 첫 빌드 수 분
docker compose ps                 # 6개 전부 running
```

- 넷 중 하나라도 비면 compose 가 기동 전에 멈춘다 — 저장소에 공개된 비밀번호·JWT 키로 뜨는 일을 막는 장치
- 확인 — 브라우저에서 `https://bus.<도메인>` 로그인. 계정 ID 는 `backend/src/main/java/src/backend/global/common/SeedFixtures.java`(`staffA` · `parentA1` · `driverA1` 등), 비밀번호는 위에서 정한 값

## 7. APK 빌드 · 올리기 · QR

Mac 에서 빌드한다(두 앱 모두).

```bash
cd frontend/apps/parent-app      # 매니저 앱은 manager-app
flutter build apk --release \
  --dart-define=API_BASE_URL=https://bus.<도메인>/api/v1 \
  --dart-define=NAVER_MAP_CLIENT_ID=<웹 .env.local 과 같은 ID>
scp build/app/outputs/flutter-apk/app-release.apk <집 PC>:School-Bus/downloads/parent.apk
```

- ⚠ **`/api/v1` 까지 붙인다.** 빠뜨리면 모든 요청이 404
- 크기 — 학부모 125MB · 매니저 132MB(모든 CPU 종류를 한 파일에 담은 크기). 줄이려면 `--split-per-abi` 후 `app-arm64-v8a-release.apk` 만 올린다
- 파일만 바꾸면 되고 서버 재시작은 필요 없다
- **테스트 시나리오** — `docs/TEST_SCENARIOS.html`(git 추적 밖)을 같은 폴더에 올린다: `scp docs/TEST_SCENARIOS.html <집 PC>:School-Bus/downloads/` → 팀원은 `https://bus.<도메인>/download/TEST_SCENARIOS.html`. ⚠ 문서의 서버 주소 자리(`bus.<도메인>`)를 실제 도메인으로 바꿔서 올린다
- **QR** — PC Chrome 에서 `https://bus.<도메인>/download/` 을 열고 주소창의 공유 → **QR 코드 만들기**. 팀원은 찍고 → `parent.apk` 또는 `manager.apk` → "출처를 알 수 없는 앱 설치" 허용 → 설치

## 8. 초기화

시드의 운행 시각이 **초기화 시각 기준**이라 하루가 지나면 버스가 움직이지 않는다. 방법 셋 — 결과는 같다(DB 시드 상태 · 버스 다시 출발).

| 방법 | 누가 | 걸리는 시간 |
|---|---|---|
| 관계자 웹 머리말 **[테스트 데이터 초기화]** | 로그인한 팀원 누구나 | 수 초 · 누른 사람은 로그아웃된다 |
| `docker compose restart backend` | 집 PC 운영자 | 약 20초 접속 불가 |
| 매일 새벽 자동 (아래 cron) | — | 약 20초 |

```bash
crontab -e
0 5 * * * cd $HOME/School-Bus && docker compose restart backend
```

- ⚠ **모든 팀원의 데이터가 함께 되돌아간다** — 다른 사람이 테스트 중이면 먼저 알린다
- 초기화 뒤 앱은 다음 토큰 갱신 때 로그인 화면으로 돌아간다(로그인 유지 토큰이 지워지기 때문 — 결함 아님)

## 9. 코드 갱신 · 문제 확인

```bash
git pull && docker compose up -d --build   # PUBLIC_URL 을 바꿨다면 web 까지 다시 빌드된다
docker compose logs -f backend
```

| 증상 | 원인 |
|---|---|
| 지도만 안 뜸(401) | §5 NCP 등록 누락 |
| 웹 로그인 직후 다시 로그인 화면 | `PUBLIC_URL` 과 실제 접속 주소가 다름(쿠키·CORS 둘 다 이 값) |
| 앱이 서버에 못 붙음 | APK 빌드 때 `API_BASE_URL` 의 `https`·`/api/v1` 누락 |
| `bus.<도메인>` 이 502/1033 | `cloudflared` 컨테이너 중지 또는 Public Hostname 의 URL 이 `proxy:80` 이 아님 |
| 요청이 느리거나 실패, 백엔드 로그에 `Connection is not available` | DB 연결 풀(기본 10개)이 마름 — 연결 대기 상한 3초(`connection-timeout`) 뒤 실패. 5초 넘게 연결을 쥔 스레드의 스택이 `leak-detection` 경고로 로그에 남으므로 `docker compose logs backend \| grep -i leak` 로 누가 쥐었는지 찾음 |

## 10. 운영(AWS) 변경 중 스테이징에 해당하지 않는 것 (2026-10-01 R46 확인)

R46 운영 작업(`DEPLOYMENT §7` 백업 · `DEPLOYMENT §11` 관측 · `DEPLOYMENT §12` Vercel · `DEPLOYMENT §13` 운영 규칙)이 이 서버 절차를 바꾸지 않는 근거. `docker-compose.staging.yml` 을 직접 읽은 결과다.

| 운영 변경 | 스테이징 |
|---|---|
| 매시 DB 백업 · 매일 사진 백업 · 복구 연습(`Ruling 480`·`500`) | **해당 없음** — postgres 가 `tmpfs`(메모리)라 디스크에 데이터가 없고 매일 새벽 시드로 초기화하는 서버. 백업 대상이 아님 |
| 경보 수신(텔레그램·이메일, Prometheus·Alertmanager) | **해당 없음** — 컨테이너 6개(postgres · redis · backend · web · proxy · cloudflared)뿐이고 관측·경보 컨테이너가 없음 |
| 첫 메인 관리자 러너(`FirstSystemAdminBootstrap`) | **동작 안 함** — 시드에 메인 관리자(`sysadmin`)가 이미 있어 이 러너는 값을 읽지 않고 건너뜀. 스테이징에는 `BOOTSTRAP_ADMIN_*` 변수를 넣지 않음 |
| 웹 Vercel 배포 · 허용 출처 검사(`infra/scripts/deploy.sh`) | **해당 없음** — 이 검사는 운영 배포 스크립트에만 있음. 스테이징은 `PUBLIC_URL` 한 값이 REST 허용 출처(`CORS_ALLOWED_ORIGINS`)와 WebSocket 허용 출처(`WS_ALLOWED_ORIGIN_PATTERNS`)에 그대로 들어감 |
| prod 프로파일 명시(`b18c60bf`) | **해당 없음** — 스테이징은 `local,staging` 프로파일이고 필수 환경변수는 §6 의 넷 |
| 이미지 태그 부 버전 고정(`postgres:16.15` · `redis:7.4.11` 등) | **적용됨** — 별도 조치 없음. 태그를 올릴 때는 `docker-compose.staging.yml` 의 값을 바꿈 |
| 연결 대기 3초 · 누수 감지 5초(`R46 D #17`) | **적용됨** — `staging` 프로파일에 들어 있음(§9 증상표) |
