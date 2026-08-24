# 바래다 (BARAEDA) — 문서 지도

학원 통학버스 통합관리 서비스의 **문서 진입점**. 작업을 시작하기 전에 이 파일에서 필요한 문서를 고름.

> **이 폴더의 4개 문서가 제품 사양의 단일 소스(SoT).** 2026-08-24 서비스 방향 전환으로 전면 재작성됐으며,
> 각 모듈의 심화 문서(`backend/docs/`, `frontend/docs/`)는 여기에 종속.

---

## 1. 제품 사양 4종 (SoT)

| 문서 | 담는 것 | 언제 읽나 |
|---|---|---|
| **[PRD.md](PRD.md)** | 배경·문제, 제품 4종/사용자 6종, 목표·비목표, 핵심 시나리오, 배차 파이프라인, **확정 정책과 채택 이유**, 우선순위 P0~P2, NFR, KPI, 오픈 이슈 | 왜 이렇게 만드는지, 무엇을 먼저 만드는지 |
| **[FEATURE_SPEC.md](FEATURE_SPEC.md)** | 공통 규칙 C-01~17, **정책 상수**, 도메인 모델·엔티티·상태머신, 기능 인덱스 102개, 계층별 기능(P/S/M/A/O), **권한(RBAC)·민감 데이터 등급**, 미해결 X-01~03 | **기반 문서 — 가장 먼저 읽음.** 나머지가 여기의 ID·상수를 참조 |
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
| **[ERD.md](ERD.md)** | 테이블 39개 · 컬럼 · 관계 · 제약 · 인덱스 · 학원 격리 · 보존 정책. Mermaid ERD 5장 |
| **[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)** | **구현 추적의 메인 문서** — 현 코드 처분 방침 · Flyway 재작성 · Mock/Swagger 일치 · 테스트·부하 테스트 전략 · Phase 0~14 + F1~F4 · 진행 추적 표. **세션 재개 시 여기부터** |
| **[TECH_DECISIONS.md](TECH_DECISIONS.md)** | **기술 선택과 불채택** — Security 경계 · 배치 · 상태 전이 · 시각 주입 · 운영 · 관측 · 장애 대응. 왜 그 라이브러리를 **안 쓰기로** 했는지의 근거 |
| **[DEPLOYMENT.md](DEPLOYMENT.md)** | 운영 배포 절차. 설계 근거는 [superpowers/specs/2026-08-10-mvp-배포-design.md](superpowers/specs/2026-08-10-mvp-배포-design.md) |

**전부 To-Be 설계**이며 현재 코드와 다르다. 방향 전환 이전의 코드 실측 기록이 필요하면 `git show HEAD:docs/ARCHITECTURE.md` 로 조회.

**구조를 그림으로 먼저 훑고 싶으면** [html/architecture-overview.html](html/architecture-overview.html) 을 연다 — 런타임 구성 · 요청 네 갈래 · 모듈 지도 · 인가 3층 · 노선 계산 파이프라인 · **시간 기반 배치** · 3구간 타임라인 · 실시간·알림을 SVG 10장으로 압축한 요약본(§9 기술 선택은 최신, **§1~8 은 2026-08-24 초판 기준이라 비상 알림·아웃박스·RBAC 미반영**). 서술과 리스크 목록은 원본을 본다.

## 4. 모듈별 심화 문서 (이 폴더 밖)

이 폴더가 상위 계약이고 아래는 각 모듈의 구현 세부. **사실이 어긋나면 이 폴더가 기준.**

| 문서 | 성격 |
|---|---|
| `backend/docs/reference.md` | 백엔드 코드 컨벤션 — spec/impl 판단기준, CQRS, Event 규칙 |
| `backend/docs/CODE_CONVENTIONS.html` · `OBSERVABILITY_DASHBOARDS.html` | 백엔드 심화판 (사람이 읽는 렌더). ⚠ 방향 전환 이전 서술이 일부 남음 |
| `frontend/docs/FLUTTER_CODE_CONVENTIONS.md` · `DESIGN_SYSTEM.md` · `FRONTEND_SETUP.md` | 프론트 컨벤션·디자인 시스템·로컬 세팅. 계획은 `IMPLEMENTATION_PLAN.md` F1~F4 |
| `backend/docs/학원 통학버스 통합관리 시스템.docx` | 기획 **원본**(불변) — 원문 근거가 필요할 때만 |
| `docs/brainstorming/` | UI 시안(`.dc.html` 3종) · 디자인 토큰 · 유저플로우 이미지 · 기본정보 PDF |

⚠ **이 심화 문서들은 아직 구 사양 기준.** 방향 전환(2026-08-24) 이후 갱신되지 않았으므로, 사양이 어긋나면 `docs/` 4종이 기준.

---

## 5. 문서 포맷 규칙

- 이 폴더의 **`.md` 가 원본**. `html/` 은 사람용 렌더이며 원본을 고쳤다고 자동 동기화하지 않음 — 필요하면 별도 요청.
- Claude 가 매 세션 재참조하는 문서는 토큰 효율을 위해 **Markdown 유지**.
- 새 기술 문서를 HTML 로 만들 때는 전역 `html-docs` 정책(sketch 테마·인라인 SVG·Prism code-card)을 따름.
