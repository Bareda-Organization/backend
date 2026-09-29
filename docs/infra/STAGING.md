# 스테이징 — 팀원 체험용 서버 (집 PC + Cloudflare Tunnel)

멀리 있는 팀원이 관계자 웹과 Android 앱을 직접 써 보고 피드백하기 위한 서버. 실사용자용이 아니다 — 실사용자 단계는 `DEPLOYMENT.md`(AWS).

| 항목 | 결정 (2026-09-29 사용자 확정) |
|---|---|
| 서버 | 집 PC 1대(i5 10세대 · 16GB) · `docker-compose.staging.yml` 한 파일 |
| 공개 | Cloudflare Tunnel — 공유기 포트 개방 부재 · HTTPS 는 Cloudflare 가 처리 |
| 웹 | 같은 서버·같은 주소(Vercel 미사용 — 새로 고침 쿠키가 `SameSite=Strict` 라 주소가 갈리면 로그인 유지 불가) |
| 앱 | Android 만 · APK 를 서버의 `/download/` 에 두고 QR 로 설치. iOS 는 제외(원격 설치에 Apple 개발자 등록 필수) |
| 데이터 | 백엔드 `local,staging` 프로파일 — 데모 시드 + 버스 시뮬레이터. **매일 새벽 시드 상태로 초기화** · 팀원이 웹 머리말 **[테스트 데이터 초기화]** 로 언제든 초기화(`Ruling 364`) |

모든 요청은 `https://bus.<도메인>` → Cloudflare → 집 PC 의 `cloudflared` → `proxy`(nginx) → `/api`·`/ws` 는 backend, `/download` 는 APK 폴더, 나머지는 web.

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

⚠ GitHub 의 `main` 은 2026-09-09 이후 push 하지 않았고 문서는 공개 저장소에 올리지 않는다 — **GitHub 에서 받지 말고 Mac 에서 받는다.**

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
