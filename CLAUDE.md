# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

학원 통원버스 운행·학생 등하원 관리 **멀티 테넌트 플랫폼**의 **백엔드**(Spring Boot).

도메인 모듈 16개 + `demo`(데모 시뮬레이터) · `observability`(지표) + global 인프라(모듈 목록은 `docs/ARCHITECTURE.md §3.1`)가 엔티티~컨트롤러까지 구현돼 있고, 계획서 Phase 0~14 와 이월 묶음 F1~F6 이 전부 완료 상태다.

**⚠ 2026-10-02 저장소를 3개로 나눴다** — 한 조직 아래 배포처별로 하나씩. 커밋 이력은 `git filter-repo` 로 함께 옮겼고, 나누기 전 통합 저장소는 `mskim98/School-Bus` 에 보관돼 있다.

| 저장소 | 받는 것 | 배포 |
|---|---|---|
| **backend**(이 저장소) | `backend/` · `infra/` · `docker-compose*.yml` · **세 저장소 공통 사양 `docs/`** | AWS EC2 · 로컬(집 PC + Cloudflare Tunnel) |
| **web** | 관계자 웹(Next.js, 옛 `frontend/apps/academy-web`) · `design-system/` · `docs/CONVENTIONS_REACT.md` | Vercel |
| **mobile** | 앱 2종(`apps/manager-app` · `apps/parent-app`) · 공용 패키지 `packages/baraeda_{core,ui}` · `docs/CONVENTIONS_FLUTTER.md` | App Store · Play Store |

- **로컬에서는 세 저장소를 같은 폴더 아래 나란히 clone 한다**(`…/backend` · `…/web` · `…/mobile`). `docker-compose.app.yml`·`staging.yml` 의 `web` 은 `../web` 을 빌드하고, web 의 사양 대조 시험은 `../backend/docs/API_SPEC.md` 를 읽는다
- `docs/` 의 `frontend/apps/…` · `frontend/packages/…` 경로 표기는 나누기 전 이름이다 — web 은 저장소 루트, mobile 은 `apps/`·`packages/` 로 읽는다
- API 를 바꾸는 작업은 **backend 를 먼저 병합하고** web·mobile 을 뒤따라 병합한다

**git 추적 범위 — 앱 동작에 필요한 것 + 에이전트가 읽어야 하는 것 (2026-09-18 확장).** `backend/`(테스트·k6 부하 스크립트 포함) · `infra/` · `docker-compose*.yml` · `.github/workflows/deploy-backend.yml` 에 더해, **문서 Markdown(`docs/**/*.md`) · 이 파일(`CLAUDE.md`) · `.claude/PROJECT_NOTES.md` · `.claude/settings.json`** 을 추적한다.

- **뒤집은 이유** — `git worktree` 는 추적 파일만 체크아웃한다. Orca 가 만든 자식 워크트리에서 뜬 에이전트가 빌드 명령(`-PtestDbUrl` 필수)·Docker 포트·Flyway 재구성 정책·사양 4종을 모르는 채 시작했다. 2026-09-02(docs 전면 제외)·2026-09-09(`.claude`·`CLAUDE.md` 제외) 결정을 이 범위에서 뒤집는다.
- **여전히 제외** — 사람용 렌더(`docs/**` 의 HTML·PDF·PNG)와 기획 원본 `.docx`(파생본·바이너리) · 보고서(`report/`) · 라운드별 작업 기록(`.claude/` 나머지 — ⚠ **`.superpowers/` 는 2026-09-20 삭제**, 정본이 쓰던 근거 24개는 `docs/archive/sdd/`) · 재생성 가능한 색인(`graft/` · `.docgraph/`).
- ⚠ **원격은 공개 저장소다**(나누기 전 `mskim98/School-Bus`). 2026-09-18 결정은 "문서는 로컬 커밋만"이었으나 **2026-09-30 사용자 지시로 문서 포함 전부 push 했다**(1,325 커밋 · 비밀값 검사 0건). 이후 push 는 사용자에게 한 번 확인하고, push 전에는 `backend/.env`·`.env.local` 의 실제 값이 커밋에 없는지 검사한다.
- 새 워크트리에서는 색인을 한 번 만든다 — `graft build .` (부모의 캐시를 복사하므로 **1.7초**)

**프론트엔드는 2026-09-10 사용자 결정으로 범위 안이다** — 2026-09-04 Ruling 255(영구 범위 밖)를 뒤집었다. `docs/IMPLEMENTATION_PLAN` 의 Phase F1~F4 `➖` 표기는 옛 Flutter 계획에 대한 것이라 그대로 두고, **프론트 작업의 창구는 `docs/frontend/IMPLEMENTATION_PLAN.md` 로 분리**했다.

- 제품 3개 — 관계자 웹(**Next.js**, 메인 관리자 콘솔을 `(admin)` 라우트 그룹으로 합침) · 학부모·학생 앱(**Flutter**) · 매니저 앱(**Flutter**)
- **앱 하나가 로그인 결과의 역할로 갈라진다** — 학부모↔학생, 기사↔동승자. 갈리는 것은 화면이 아니라 권한과 진입점
- 코드 규칙은 web 저장소 `docs/CONVENTIONS_REACT.md`(React) · mobile 저장소 `docs/CONVENTIONS_FLUTTER.md`(Dart) 2종 — 코드 옆에 둔다(2026-10-02)
- 디자인 시스템 사본은 web 저장소 `design-system/`(**읽기 전용**, 정본은 claude.ai 원격). ⚠ **킷이 구 기획 기반이라 `docs/` 와 어긋나는 곳이 있다** — 어긋나면 `docs/` 가 기준이고, 확인된 4건은 `docs/frontend/IMPLEMENTATION_PLAN.md §4`

- **작업 전 반드시 [`docs/README.md`](docs/README.md) 를 먼저 읽는다.** 제품 사양 4종(`FEATURE_SPEC` · `PRD` · `USER_FLOWS` · `API_SPEC`)의 진입점이며, 이 4개가 **단일 소스(SoT)** 다. **2026-08-24 서비스 방향 전환으로 전면 재작성됐고, 순수 기획(To-Be)이라 구현 상태 표기가 없다** — 현재 코드는 상당 부분이 이 사양과 어긋나며 앞으로 사양에 맞춰 수정할 대상이다.
- **기반 문서는 `docs/FEATURE_SPEC.md`** — 공통 규칙 C-01~18, 정책 상수(확정 30분 전 · ②구간 회차당 1회 · 운행 시작 ±10분 등), 엔티티·상태머신, 기능 ID 체계가 여기서 정의되고 나머지 3종이 이를 참조한다. 새 기능 ID·상태값을 만들지 않는다.
- 문서의 `🔸` 는 구 기획에서 흡수해 **존치 미확정**인 항목이다 — 확정 사실로 취급하지 않는다. (근거 없는 신규 설계를 표시하던 `🆕` 는 2026-08-24 전건 승인되어 제거됐다.)
- **설계 문서는 `docs/ARCHITECTURE.md`(모듈·인가·노선 파이프라인·시간 기반 배치)와 `docs/ERD.md`(테이블·제약·인덱스)** 다. 둘 다 사양 4종에서 유도한 **To-Be 설계**이며 현재 코드와 다르다 — 코드를 이 설계에 맞추는 것이 앞으로의 작업이고, 방향 전환 이전의 코드 실측본은 `git show HEAD:docs/ARCHITECTURE.md` 로 본다.
- **이 서비스의 중심축은 시간이다** — 회차 `idle → confirmed` 전이는 사용자 조작이 아니라 **출발 30분 전 도래**가 일으킨다(`ARCHITECTURE §9`). 배치는 30초 폴링 + 조건부 UPDATE 멱등이고, "실행 시각"과 "판정 시각(출발−30분)" 두 시계를 절대 섞지 않는다.
- **구현 계획·진행 추적은 [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) 단일 창구다.** 세션 재개 시 §8 진행 추적 표에서 현재 Phase 를 확인하고, 작업이 끝나면 그 표를 갱신한다. 횡단 규칙은 §7(**25개**). **2026-10-01 정리 — 본문은 규칙(§0~§7)·진행 추적 표(§8)·열린 항목(§9)·Ruling 색인(§11)과 최근 라운드 절(`R46-*`)만 두고, 끝난 Phase·라운드 기록은 원문 그대로 `docs/archive/rounds/` 로 옮겼다**(파일·절 번호 범위는 그 폴더의 `README.md` — 옛 절 번호(8.45 등)는 같은 번호 머리글로 찾는다). Ruling 번호는 §11 색인에서 기록 위치를 찾는다.
- **작업의 최소 단위는 Phase 가 아니라 기능 ID 1개이고, 그 단위를 `IMPLEMENTATION_PLAN §4.6` 의 TDD 사이클로 처리한다** — 목표 → RED(실패를 눈으로 확인) → GREEN(최소 구현) → REFACTOR → 검증. **실패를 보지 않은 테스트는 산출물로 인정하지 않는다.** 이 시스템의 결함 4종(시각·동시성·인가·개인정보 노출)은 전부 "통과하는 빈 테스트"와 구분되지 않는 형태라 RED 확인이 유일한 판별 수단이다.
- **코드 컨벤션은 `docs/backend/CODE_CONVENTIONS.md`** — 특히 §19(클래스·public 메서드·enum·이벤트·포트에 한 줄 설명 주석)와 §20(SRP·크기 기준·클린 코드)은 매 Phase 채점 대상이다.
- 기획 원본은 `docs/source/학원 통학버스 통합관리 시스템.docx`(불변) 하나다. 루트 `projectInfo.md` 는 **2026-08-24 삭제** — 내용이 두 세대 낡아 오인용 위험이 컸다. 필요하면 `git show HEAD:projectInfo.md`.
- 사실이 여러 문서에서 어긋나면 **`docs/` 가 기준이다.**
- ⭐ **2026-09-20 에 저장소의 모든 문서를 `docs/` 아래로 모았다.** 옛 `backend/docs/` · `backend/load/README.md` · `frontend/CONVENTIONS*.md` · `frontend/IMPLEMENTATION_PLAN.md` 는 **더 이상 그 자리에 없다.** 폴더 지도는 `docs/README.md §0`.

  | 위치 | 받는 것 |
  |---|---|
  | `docs/` 루트 | **전 모듈 공통 정본** — 사양 4종 · `ARCHITECTURE` · `ERD` · `TECH_DECISIONS` · `IMPLEMENTATION_PLAN` |
  | `docs/backend/` | `CODE_CONVENTIONS.md`(옛 `reference.md`) · `LOAD_TESTING.md` · `plans/` |
  | `docs/frontend/` | `IMPLEMENTATION_PLAN.md` · `SETUP.md` (규칙 2종은 2026-10-02 web·mobile 저장소로 이관) |
  | `docs/infra/` | `DEPLOYMENT.md` · `STAGING.md` · `OPERATIONS_PLAN.html` |
  | `docs/archive/` | 끝난 기능별 계획·설계 (옛 `docs/superpowers/`) · `rounds/` = 끝난 계획서 라운드·Phase 기록(2026-10-01 이관) |
  | `docs/render/` · `docs/source/` | 사람용 렌더 · 기획 원본. ⚠ **추적 밖이라 워크트리에서 열리지 않는다** |

  - **주제 폴더는 한 모듈에만 해당하는 것만 받는다** — 백엔드도 프론트도 보는 사양·설계는 루트에 남는다
  - 앱·패키지의 `README.md`·`CHANGELOG.md`·`CLAUDE.md`·`AGENTS.md` 는 **도구가 디렉터리로 찾으므로 제자리에 둔다**

- 사용자 6종(`Role` enum): 학부모 `PARENT` · 학생 `STUDENT` · 운전기사 `DRIVER` · 동승자 `ESCORT` · 학원 관계자 `STAFF` · 메인 관리자 `SYSTEM_ADMIN` — 학원 관계자와 메인 관리자는 권한 범위가 완전히 다른 별개 역할
- 멀티 테넌시: 계정 1개가 학원 1곳에 속한다(`account.academy_id`, 메인 관리자만 null — `ck_account_academy_scope`). 옛 `User`↔`Tenant` N:M · `UserTenantRole` 연결 테이블은 2026-08-24 방향 전환으로 소멸했다. 격리는 DB 레벨(RLS·`@Filter`)이 아니라 **애플리케이션 코드**에서 강제된다 — 범위를 토큰에서만 얻는 `AcademyScope` + 저장소 쿼리의 학원 조건(`ARCHITECTURE §6`).

## Orca 로 워크트리·작업 창을 띄울 때

🔴 **`orca worktree create` · `worker-start` · `terminal create` 를 치기 전에 Skill `orca-launch` 를 먼저 읽는다.** `--setup skip` 기본 · 작업 창마다 `--model`·`--effort` 명시(sonnet 은 `[1m]`) · 한 줄 기동 · `Default view` 함정 · Haiku 의 `worker_done` 미발신 · `worktreeBaseRef` 가 거기 있다. 조율(기다리기·정산)은 이어서 Skill `orchestration`.

## Build & run (backend)

모든 명령은 `backend/` 디렉토리에서 실행한다. Gradle wrapper 사용.

```bash
cd backend
./gradlew bootRun          # 앱 실행 (devtools 자동 재시작 포함)

# ⚠ 테스트류는 -PtestDbUrl 이 필수다(2026-09-14~). 안 주면 설정 단계에서 즉시 실패한다 —
# 좌석 여러 개가 같은 공유 DB(schoolbus)로 조용히 떨어져 서로의 행을 밟는 사고를 막는 장치다.
# Redis 는 별도 인자 없이 모든 시험에 자동으로 격리된 컨테이너가 붙는다(ContextCustomizerFactory).
# bootRun·compileJava 는 이 인자와 무관하다 — 영향 없이 그대로 돈다(병합 직후 컴파일 확인 용도로 그대로 쓴다).
./gradlew build -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름>            # 전체 빌드 + 테스트
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름>            # 전체 테스트
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름> --tests 'src.backend.BackendApplicationTests'   # 단일 테스트 클래스
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름> --tests '*.메서드명'                             # 단일 테스트 메서드

# 실 네이버 API 시험(@Tag("live") 3클래스)은 기본 test 에서 빠져 있다 — 따로 돈다. backend/.env 의 자격증명 필요.
# Directions 15 일일 한도가 바닥났으면(Ruling 361) 그 응답은 실패가 아니라 건너뜀이고, 다른 오류는 그대로 실패한다.
./gradlew liveTest -PtestDbUrl=jdbc:postgresql://localhost:15432/<전용DB이름>
# 한도 회피(Directions 5 전환)는 경로·상한을 짝으로: NAVER_DIRECTIONS_PATH=/map-direction/v1/driving NAVER_DIRECTIONS_MAX_POINTS=7 ./gradlew liveTest …
# 병합 직전처럼 "자격증명이 없으면 건너뛰지 말고 실패" 해야 하면 test 에 -PrequireLive 를 주면 라이브 시험까지 포함해 돈다.
```

**한 명령 검증.** 저장소 루트의 `scripts/verify.sh` 가 백엔드 시험(전용 DB 자동 생성·삭제)을 CI(`.github/workflows/ci.yml`)와 같은 명령으로 돈다. 실 API 시험은 뺀다. 관계자 웹·앱은 각 저장소의 `scripts/verify.sh`. CI 상세·로그 정책은 `docs/infra/DEPLOYMENT.md §5.1`.

## Docker — 개발 / 배포가 파일로 갈려 있다 (2026-09-18 분리)

| 파일 | 무엇 | 명령 |
|---|---|---|
| `docker-compose.yml` | **개발 인프라만** — postgres(15432) · redis(16379) | `docker compose up -d` |
| `+ docker-compose.app.yml` | **앱 오버레이** — backend · **관계자 웹** · proxy(:3000) · 관측 | `docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build` |
| `docker-compose.prod.yml` | **배포** — ECR 이미지 · 영속 볼륨 · TLS | `docs/infra/DEPLOYMENT.md` |
| `docker-compose.staging.yml` | **스테이징(팀원 체험용 · 집 PC)** — 단독 파일 · `local,staging` 프로파일 · 메모리 DB · Cloudflare Tunnel | `docs/infra/STAGING.md` |

**개발 방식은 둘 중 하나를 고른다.**
1. **인프라만 컨테이너 + 백엔드는 IDE** — `docker compose up -d` 후 `./gradlew bootRun`. Swagger 는 `http://localhost:8080/...`
2. **전부 컨테이너** — 위 오버레이 명령. **모든 HTTP 는 proxy(:3000) 한 곳을 지난다** — 관계자 웹 `http://localhost:3000` · API `.../api/v1/...` · Swagger `.../swagger-ui/index.html` · Grafana `:3001`(admin/admin) · Prometheus `:9090`

- ⚠⚠ **프록시가 `3000` 인 것은 우연이 아니다.** 네이버 지도 키의 **서비스 URL** 과 백엔드 **CORS 허용 목록**이 둘 다 `http://localhost:3000` 으로 등록돼 있다. 다른 포트로 열면 지도 SDK 의 `/v3/auth` 가 **401** 로 거절되고 화면에는 *"지도를 불러오지 못했습니다"* 만 뜬다(2026-09-18 `:80` 으로 열었다가 실제로 겪었고, 3000 으로 바꾸니 같은 요청이 200 이 됐다). 포트를 바꾸려면 **NCP 콘솔의 서비스 URL 부터** 바꿔야 한다

- ⚠ **오버레이를 썼으면 `down` 에도 `-f` 두 개를 그대로 준다.** 빼면 compose 가 인프라 파일만 읽어 backend·proxy·관측 컨테이너가 **살아남는다.** 번거로우면 `export COMPOSE_FILE=docker-compose.yml:docker-compose.app.yml`
- ⚠ **`/actuator` 는 프록시가 라우팅하지 않는다 — 404 가 정상이다.** 헬스는 `docker compose ... exec backend curl -s localhost:8080/actuator/health`
- ⚠ **컨테이너 모드에서 Redis 포트를 덮어야 한다**(`SPRING_DATA_REDIS_PORT: 6379`). `application.yml` 기본값 16379 는 **호스트에 낸 포트**라, 호스트만 덮고 두면 **기동은 되는데 헬스가 DOWN 으로 남는다**(2026-09-18 실제 발생)

앱 기동 후 API 테스트는 Swagger UI 를 쓴다 — 로그인 응답의 `access_token`(응답 봉투 `data` 안)을 우측 상단 Authorize에 넣으면 이후 요청에 자동으로 붙는다. 로그인 계정은 Flyway 시드(`db/migration-local/V2__seed_data.sql`) 참조 — **로컬**은 비밀번호가 모두 `password`(배포 환경은 다름, 아래 Flyway 항목 참고).

**로컬 postgres는 의도적으로 영속 볼륨이 없다**(2026-07-22~, Swagger로 반복 테스트해도 항상 시드 상태로 되돌리기 위함) — `docker compose down`(컨테이너 제거) 후 `docker compose up -d postgres redis`로 다시 띄우면 Flyway가 스키마(V1)+데모 시드(V2)를 매번 자동으로 새로 구성한다(수동 `DROP SCHEMA`/`volume rm` 불필요, `DataInitializer`는 2026-07-20 삭제됨). `stop`/`start`(컨테이너를 제거하지 않음)는 데이터가 유지된다 — 리셋하려면 반드시 `down`을 거칠 것.

**배포**는 `docs/infra/DEPLOYMENT.md`를 따른다. 설계 근거(관리형 서비스 채택 검토·차단 결함·비용)는 `docs/archive/specs/2026-08-10-mvp-배포-design.md`. 운영은 EC2 1대 + `docker-compose.prod.yml`이며 **백엔드 인스턴스는 반드시 1개**다(`@Scheduled` 중복·InMemory 버스위치·WS 세션 로컬 보관).

## Stack / 주요 특이사항

- **Spring Boot 4.1.1**, **Java 25**(toolchain 고정), Gradle. 웹 스타터는 신형 아티팩트명 `spring-boot-starter-webmvc`(테스트는 `spring-boot-starter-webmvc-test`)를 사용한다 — 구버전 `spring-boot-starter-web`이 아님.
- **스키마는 Flyway가 관리**(`spring-boot-starter-flyway`+`flyway-database-postgresql`, 2026-07-20부터)한다 — `ddl-auto: validate`로 Hibernate는 검증만.
- **개발 단계에서는 마이그레이션을 새 버전으로 쌓지 않아도 된다**(2026-08-23 정책 변경). 스키마를 바꿔야 하면 **기존 파일(`V1__init_schema.sql` 포함)을 직접 고치고 로컬 DB를 통째로 재구성**하는 편을 우선한다 — `docker compose down` 후 `docker compose up -d postgres redis`. 로컬은 영속 볼륨이 없어 데이터를 잃을 것이 없고, 버전 파일이 늘어나 스키마의 최종 형태를 여러 파일에 흩어 놓는 것보다 낫다. 기존 파일을 고치면 체크섬이 바뀌어 **이미 적용된 DB는 `FlywayValidateException`으로 기동에 실패**하므로, 재구성 없이 앱만 다시 띄우면 실패한다는 점만 기억한다(코드 결함이 아니라 재구성 누락 신호다). 단 `local` 프로파일 `bootRun` 은 기동마다 `clean()` 후 다시 적재하므로(`LocalFlywayCleanStrategy`) 이 실패가 나지 않는다 — 실패하는 곳은 `clean()` 을 끄는 `-PtestDbUrl` 시험 DB · 컨테이너 모드 backend(`--app.flyway-clean.suppressed=true`)다.
- **이 예외는 "아직 아무 영속 환경에도 적용되지 않은 마이그레이션"에만 해당한다.** demo·prod에 한 번이라도 적용된 뒤에는 원칙이 뒤집혀 **기존 파일 수정 금지 · `V{n}` 추가만 허용**이다. 운영 DB는 볼륨이 있어 재구성으로 되돌릴 수 없고, 체크섬 불일치는 곧 기동 불가다. 첫 배포 시점에 이 항목을 갱신할 것. 데모 시드는 별도 위치 `db/migration-local/`에 있고 **`local`·`demo` 두 프로파일에서만** `spring.flyway.locations`에 추가된다(`prod`엔 안 들어감). 데모 규모 시드 `db/migration-demo/`(학원 10곳 · 학생 600명)는 **`local` 에서만** 추가되고 시험 JVM 은 `build.gradle` 이 뺀다. 시드 계정의 비밀번호 해시는 Flyway placeholder `seedPasswordHash`로 주입한다 — local은 `application.yml`의 기본값(평문 `password`), demo는 SSM에서 받은 값이라 **배포 환경의 비밀번호는 `password`가 아니다.**
- 기본 패키지가 `src.backend`이고 Gradle `group = 'src'`이다(비관례적). 새 클래스는 이 `src.backend` 하위에 두어 `@SpringBootApplication` 컴포넌트 스캔 범위를 유지한다.
- **코드 컨벤션 상세는 `docs/backend/CODE_CONVENTIONS.md`(Claude 참조용, Markdown)를 먼저 읽는다.** spec/impl 판단기준·패키지 구조·CQRS·Event 규칙 등 전체 원칙이 정리돼 있다. 사람이 브라우저로 보는 동일 내용의 렌더링 버전은 `docs/render/CODE_CONVENTIONS.html`(시각화 포함) — 둘은 원칙은 같고 매체만 다르며, Claude는 세션마다 `CODE_CONVENTIONS.md`를 참조한다.
- **핵심 요약**: service·repository는 "구현이 바뀔 가능성이 있는가"를 기준으로만 `spec`(인터페이스) / `impl`(구현체) 하위 패키지로 분리한다(단순 CRUD는 분리하지 않음) — 예 `routing/map/spec/MapRouteClient.java` + `routing/map/impl/NaverDirectionsClient.java`(`bus` 같은 단순 CRUD 모듈은 `command/` · `query/` 만 두고 `spec`/`impl` 이 없다). 컨트롤러 등은 `spec`만 의존한다. `package-info.java`는 두지 않는다(패키지 레벨 애너테이션이 필요할 때만 예외).

**설계 제약·데이터 모델·기능 범위는 `docs/` 를 본다** — 공통 규칙·엔티티·상태머신·권한은 `docs/FEATURE_SPEC.md`(§2·§3·§6), 정책 채택 이유와 우선순위는 `docs/PRD.md`(§6·§7), 엔드포인트 계약은 `docs/API_SPEC.md`. 모듈 경계·인가 3층·노선 파이프라인·시간 기반 배치는 `docs/ARCHITECTURE.md`(§3·§5·§6·§8·§9) 다.

## 응답/문서 규칙

이 사용자는 Spring 입문 단계의 백엔드 개발자다. 전역 `~/.claude/CLAUDE.md` 규칙(한글 응답, 개념별 1줄 요약 → 번호 흐름 → request→처리→response, Controller/Service/Repository 연계 설명, 흔한 오해 1개 포함)을 따른다.

**문서 포맷 규칙**: 사람이 브라우저로 보는 기술문서(아키텍처·규칙 등)는 전역 정책대로 HTML+인라인 SVG로 작성한다. 단 **Claude가 매 세션 재참조하는 사양·설계·진행추적·컨벤션 문서(`docs/` 8종, `CODE_CONVENTIONS.md` 등)는 토큰 효율을 위해 Markdown으로 유지**한다(HTML의 SVG·CSS 골격은 재로딩 비용만 크다). 같은 내용의 인간용 HTML 렌더(예: `CODE_CONVENTIONS.html`)가 별도로 존재할 수 있으며, 둘은 원칙만 동기화하고 서로 대체하지 않는다.

**HTML 문서 수정 규칙 (2026-07-18 확정)**: `CODE_CONVENTIONS.html` 등 사람용 HTML 문서는 **사용자가 명시적으로 요청한 경우에만** 생성·수정한다. Markdown 원본(`CODE_CONVENTIONS.md` 등)을 바꿨다고 해서 대응하는 HTML을 자동으로 동기화하지 않는다 — 필요하면 사용자가 별도로 요청한다.
