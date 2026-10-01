# 바래다 (BARAEDA) — 문서 지도

학원 통학버스 통합관리 서비스의 **문서 진입점**. 작업을 시작하기 전에 이 파일에서 필요한 문서를 고름.

> **이 폴더의 4개 문서가 제품 사양의 단일 소스(SoT).** 2026-08-24 서비스 방향 전환으로 전면 재작성됐으며,
> 주제별 하위 폴더는 여기에 종속.

## 0. 폴더 지도 (2026-09-20 통합)

**저장소의 모든 문서가 이 폴더 아래에 있다.** 2026-09-20 에 `backend/docs/` · `backend/load/README.md` ·
`frontend/CONVENTIONS*.md` · `frontend/IMPLEMENTATION_PLAN.md` 를 여기로 모았다.

| 위치 | 담는 것 | 판정 기준 |
|---|---|---|
| `docs/` **루트** | **전 모듈 공통 정본** — 사양 4종 · 설계 3종 · 구현 계획 | 백엔드도 프론트도 같은 것을 본다 |
| `docs/backend/` | 백엔드**만** — 코드 컨벤션 · 부하 시험 · 옛 계획 | 한 모듈에만 해당 |
| `docs/frontend/` | 프론트**만** — React·Flutter 규칙 · 프론트 계획 · 셋업 | 한 모듈에만 해당 |
| `docs/infra/` | 배포·운영**만** — 배포 절차 · 운영 계획 | 한 모듈에만 해당 |
| `docs/archive/` | **끝난** 기능별 계획·설계 (`plans/` · `specs/` · `sdd/` 목표 표·판정문) · 끝난 계획서 라운드·Phase 기록(`rounds/`, 원문 그대로) | 이력이지 지시가 아니다 |
| `docs/render/` | **사람용 렌더** — HTML · PDF · PNG. ⚠ **파생본이라 git 추적 밖** | `.md` 를 고쳐도 자동 동기화 부재 |
| `docs/source/` | **기획 원본** — `.docx` · 브레인스토밍 이미지. 추적 밖 | 불변 |

⚠ **추적되는 것은 `.md` 뿐이다**(`.gitignore` 의 `**/docs/**` + `!**/docs/**/*.md`).
`render/` · `source/` 는 디스크에만 있으므로 **이 두 폴더를 근거로 인용하는 문서는 워크트리에서 열리지 않는다.**

- **앱·패키지에 남긴 것** — `frontend/apps/*/README.md` · `frontend/packages/*/{README,CHANGELOG}.md`
  (npm·pub 표준 위치) · `frontend/apps/academy-web/{CLAUDE,AGENTS}.md`(도구가 디렉터리로 찾는다) ·
  `frontend/design-system/**`(원격 킷 사본, 읽기 전용).
- **`docs/.api_parts/`** 는 `API_SPEC.html` 을 조립하는 빌드 작업 폴더다 — 문서가 아니라 도구라 그대로 둔다.

---

## 1. 제품 사양 4종 (SoT)

| 문서 | 담는 것 | 언제 읽나 |
|---|---|---|
| **[PRD.md](PRD.md)** | 배경·문제, 제품 4종/사용자 6종, 목표·비목표, 핵심 시나리오, 배차 파이프라인, **확정 정책과 채택 이유**, 우선순위 P0~P2, NFR, KPI, 오픈 이슈 | 왜 이렇게 만드는지, 무엇을 먼저 만드는지 |
| **[FEATURE_SPEC.md](FEATURE_SPEC.md)** | 공통 규칙 C-01~18, **정책 상수**, 도메인 모델·엔티티·상태머신, 기능 인덱스 103개, 계층별 기능(P/S/M/A/O), **권한(RBAC)·민감 데이터 등급**, 미해결 문제 §8(X-01~09·Y 전건 해소 표시) | **기반 문서 — 가장 먼저 읽음.** 나머지가 여기의 ID·상수를 참조 |
| **[USER_FLOWS.md](USER_FLOWS.md)** | 역할별 조작 순서, 분기·차단, 알림 매트릭스, 크로스롤 타임라인, 실패·예외 경로 | 화면·기능을 건드리기 전에 사용자 여정 확인 |
| **[API_SPEC.md](API_SPEC.md)** | 엔드포인트별 경로·권한·요청/응답·에러, WebSocket, 에러 코드 사전, enum 사전 | 프론트↔백엔드 계약 확인, API 추가·변경 시 |

**읽는 순서:** FEATURE_SPEC → PRD → USER_FLOWS → API_SPEC

**문서 경계** — 같은 사실을 두 곳에 적지 않음. 정책의 **내용**은 FEATURE_SPEC, **채택 이유**는 PRD, **조작 순서**는 USER_FLOWS, **계약**은 API_SPEC. 표·근거 문단이 중복되면 결함.

---

## 2. 표기 규칙

| 기호 | 뜻 |
|---|---|
| 🔸 | 신규 기획 4종(v2.1)에는 없고 **구 기획 문서에서 흡수**한 항목. 값은 신규 정책에 맞춰 고쳤으나 항목 자체의 존치 여부는 미확정 — **검토 후 삭제 가능** |
| ⚠ | 함정·리스크 |

**판정 기준** — 🔸 는 신규 4종 **전체** 기준. 짝이 되는 원천 하나에만 없고 다른 신규 문서에 있으면 붙이지 않음.

일괄 확인:

```bash
grep -n '🔸' docs/FEATURE_SPEC.md docs/PRD.md docs/USER_FLOWS.md docs/API_SPEC.md
```

---

## 3. 설계 문서

| 문서 | 담는 것 |
|---|---|
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | 모듈 경계 · 계층 규칙 · 인가 3층 · **노선 계산 파이프라인** · **시간 기반 배치**(출발 30분 전 도래) · 실시간 전달 · 인프라 · 리스크 |
| **[ERD.md](ERD.md)** | 테이블 42개 · 컬럼 · 관계 · 제약 · 인덱스 · 학원 격리 · 보존 정책. Mermaid ERD 5장 |
| **[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)** | **구현 추적의 메인 문서** — 현 코드 처분 방침 · Flyway 재작성 · Mock/Swagger 일치 · 테스트·부하 테스트 전략 · **기능 단위 TDD 사이클(§4.6)** · 횡단 규칙 **25개** · 진행 추적 표 · 열린 항목 · **Ruling 색인(§11)** · 최근 라운드(`R46-*`). 끝난 Phase 0~14·F1~F4·라운드 기록은 [archive/rounds/](archive/rounds/README.md)(원문 그대로). **세션 재개 시 여기부터** |
| **[TECH_DECISIONS.md](TECH_DECISIONS.md)** | **기술 선택과 불채택** — Security 경계 · 배치 · 상태 전이 · 시각 주입 · 운영 · 관측 · 장애 대응. 왜 그 라이브러리를 **안 쓰기로** 했는지의 근거 |


**전부 To-Be 설계**이며 현재 코드와 다르다. 방향 전환 이전의 코드 실측 기록이 필요하면 `git show HEAD:docs/ARCHITECTURE.md` 로 조회.

**구조를 그림으로 먼저 훑고 싶으면** [render/architecture/architecture-overview.html](render/architecture/architecture-overview.html) 을 연다 — 런타임 구성 · 요청 네 갈래 · 모듈 지도 · 인가 3층 · 노선 계산 파이프라인 · **시간 기반 배치** · 3구간 타임라인 · 실시간·알림을 SVG 10장으로 압축한 요약본(§9 기술 선택은 최신, **§1~8 은 2026-08-24 초판 기준이라 비상 알림·아웃박스·RBAC 미반영**). 서술과 리스크 목록은 원본을 본다.

## 4. 주제별 문서

**이 폴더 루트가 상위 계약이고 아래는 한 모듈에만 해당하는 세부.** 사실이 어긋나면 **루트가 기준.**

### 4.1 백엔드 — `docs/backend/`

| 문서 | 담는 것 |
|---|---|
| **[backend/CODE_CONVENTIONS.md](backend/CODE_CONVENTIONS.md)** | 코드 컨벤션 — spec/impl 판단기준 · 패키지 구조 · CQRS · Event 규칙 · `CODE_CONVENTIONS §19` 주석 · `CODE_CONVENTIONS §20` SRP·크기. **매 Phase 채점 대상** |
| **[backend/LOAD_TESTING.md](backend/LOAD_TESTING.md)** | k6 부하 시험 — 스크립트 위치(`backend/load/`) · 실행법 · 측정 결과 읽는 법 |
| `backend/plans/` | **옛 계획 3건** — 인가 간접층(2026-08-02) · 모니터링 · MVP 갭 보완(둘 다 2026-08-21). 이력이며 지시가 아니다 |

### 4.2 프론트엔드 — `docs/frontend/`

| 문서 | 담는 것 |
|---|---|
| **[frontend/IMPLEMENTATION_PLAN.md](frontend/IMPLEMENTATION_PLAN.md)** | **프론트 작업의 단일 창구** — 제품 3종(관계자 웹 Next.js · 학부모 앱 · 매니저 앱) · 최근 라운드 추적(`R46-*`) · 디자인 킷과 정본이 어긋나는 항목. 끝난 F2~F5·라운드 기록은 [archive/rounds/](archive/rounds/README.md) |
| **[frontend/CONVENTIONS_REACT.md](frontend/CONVENTIONS_REACT.md)** | React·Next.js 규칙 — 기능 폴더 구조 · `index.ts` 공개 창구 · Emotion |
| **[frontend/CONVENTIONS_FLUTTER.md](frontend/CONVENTIONS_FLUTTER.md)** | Dart·Flutter 규칙 |
| **[frontend/SETUP.md](frontend/SETUP.md)** | 개발 환경 셋업 — 인프라·백엔드 기동 · 관계자 웹(:3000) · 앱 2종 실행 인자(`API_BASE_URL` · `NAVER_MAP_CLIENT_ID` · 매니저 앱 `KAKAO_NAVI_APP_KEY`) · 카카오내비 사용자 준비물(`SETUP §8.4`) · 여러 백엔드를 동시에 띄울 때 Redis 칸 나누기 · 로그인 계정 (2026-09-30 재작성 · 2026-10-01 R46 반영) |

### 4.3 인프라 — `docs/infra/`

| 문서 | 담는 것 |
|---|---|
| **[infra/DEPLOYMENT.md](infra/DEPLOYMENT.md)** | 운영 배포 절차(EC2 1대 + `docker-compose.prod.yml`) · 백업·복구(`DEPLOYMENT §7`) · 운영 관측·경보(`DEPLOYMENT §11`) · 관계자 웹 Vercel 배포(`DEPLOYMENT §12`, `Ruling 481`) · 운영 규칙(`DEPLOYMENT §13`) · 외부 연동 준비물(`DEPLOYMENT §14`). 설계 근거는 [archive/specs/2026-08-10-mvp-배포-design.md](archive/specs/2026-08-10-mvp-배포-design.md) |
| `TEST_SCENARIOS.html` | 팀원 테스트 시나리오(사람용 · git 추적 밖) — 역할 6종 체크리스트 74항목 · 초기화 뒤 시간표 · 알려진 문제 K1~K12. 스테이징 `/download/` 로 배포 |
| **[infra/STAGING.md](infra/STAGING.md)** | 팀원 체험용 서버 — 집 PC + Cloudflare Tunnel + `docker-compose.staging.yml` · Android APK QR 설치(`Ruling 363`) · 운영(AWS) 변경 중 스테이징에 해당하지 않는 것(`STAGING §10`) |
| `infra/OPERATIONS_PLAN.html` | 운영 계획(사람용 렌더) |

### 4.4 원본·렌더 — 추적 밖

| 위치 | 담는 것 |
|---|---|
| `docs/source/학원 통학버스 통합관리 시스템.docx` | 기획 **원본**(불변) — 원문 근거가 필요할 때만 |
| `docs/source/brainstorming/` | UI 시안(`.dc.html` 3종) · 디자인 토큰 · 유저플로우 이미지 · 기본정보 PDF |
| `docs/render/` | `API_SPEC.html` · `FEATURE_SPEC.pdf` · `CODE_CONVENTIONS.html` · `OBSERVABILITY_DASHBOARDS.html` · `architecture/` · `diagrams/` |

⚠ **`render/` 의 HTML·PDF 는 방향 전환(2026-08-24) 이후 갱신되지 않은 것이 섞여 있다.**
어긋나면 `.md` 가 기준이다.

---

## 5. 문서 포맷 규칙

- **`.md` 가 원본**. `render/` 는 사람용 렌더이며 원본을 고쳤다고 자동 동기화하지 않음 — 필요하면 별도 요청.
- Claude 가 매 세션 재참조하는 문서는 토큰 효율을 위해 **Markdown 유지**.
- 새 기술 문서를 HTML 로 만들 때는 전역 `html-docs` 정책(sketch 테마·인라인 SVG·Prism code-card)을 따름.
