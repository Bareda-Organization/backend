# School-Bus 문서 지도

학원 통학버스 통합관리 서비스의 **문서 진입점**이다. 작업을 시작하기 전에 이 파일에서 필요한 문서를 고른다.

> **이 폴더의 4개 문서가 제품 사양의 단일 소스(SoT)다.** 백엔드·프론트엔드를 아우르는 상위 계약이며,
> 각 모듈의 심화 문서(`backend/docs/`, `frontend/docs/`)는 여기에 종속된다.
> 사람이 브라우저로 읽는 렌더링 버전은 `html/` 에 있다 — 내용은 같고 매체만 다르다.

---

## 1. 이 폴더의 문서 4종

| 문서 | 무엇이 있나 | 언제 읽나 |
|---|---|---|
| **[PRODUCT_SPEC.md](PRODUCT_SPEC.md)** | 서비스 목적·사용자 계층·도메인 데이터·배차·승하차·예외·알림 정책·법적 요건·로드맵 + **MVP 구현 범위 매트릭스**·**미구현 갭 목록** | 무엇을 만드는 서비스인지, 지금 어디까지 됐는지 |
| **[USER_FLOWS.md](USER_FLOWS.md)** | 역할별 진입 지도 + 플로우별 "행동 → 화면 → API → 결과" 추적. **A부 구현됨 / B부 설계됨·미구현** 으로 분리 | 화면·기능을 건드리기 전에 사용자 여정 확인 |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | 백엔드 모듈 구조·프론트 레이어·인증인가·멀티테넌시·데이터 모델·실시간 파이프라인·비동기·인프라 + **아키텍처 리스크** | 구조를 바꾸거나 새 모듈을 추가할 때 |
| **[API_SPEC.md](API_SPEC.md)** | 전체 엔드포인트 명세(권한·테넌트 격리·구현 상태·프론트 사용 여부) + 공통 규약·enum 사전·WebSocket | 프론트↔백엔드 계약 확인, API 추가·변경 시 |

**읽는 순서 (처음 이 저장소를 보는 경우):** PRODUCT_SPEC → USER_FLOWS → ARCHITECTURE → API_SPEC

구조를 **그림으로 먼저 훑고 싶으면** [html/architecture-overview.html](html/architecture-overview.html) 을 연다 — 런타임 구성·요청 3갈래·모듈 지도·계층 통과·인증인가 3층·이벤트 릴레이·위치 파이프라인을 SVG 7개로 압축한 요약본이다(실측 2026-08-23). `ARCHITECTURE.md` 를 대체하지 않으며, 서술과 리스크 목록은 원본을 본다.

배포 관련 문서는 이 4종과 별도다 — 운영 절차는 **[DEPLOYMENT.md](DEPLOYMENT.md)**, 설계 근거(관리형 서비스 채택 검토·차단 결함·비용)는 **[superpowers/specs/2026-08-10-mvp-배포-design.md](superpowers/specs/2026-08-10-mvp-배포-design.md)**.

---

## 2. 표기 규칙

문서 전반에서 구현 상태를 아래 4개 기호로만 표시한다. **기획된 것과 구현된 것을 반드시 가른다.**

| 기호 | 뜻 |
|---|---|
| ✅ | 구현 — 실제 동작한다 |
| 🟡 | 부분 — 동작하나 하드코딩·Mock·제약이 섞여 있다 |
| ⬜ | 미구현 — 설계·기획만 있다 |
| ➖ | 범위 밖 — MVP 대상이 아니다 |

`⚠` 는 함정·리스크, `※ 미확인:` 은 조사 범위에서 확인하지 못한 항목이다.
**근거 없는 서술을 두지 않는다** — 사실에는 파일 경로나 엔드포인트가 붙는다.

---

## 3. 지금 상태 한 줄 요약

MVP는 **기사 앱 + 관리자 웹**까지 동작하고, **학생·학부모 앱은 화면이 없다.**

- 정의된 역할 **5개** 중 화면이 있는 역할은 **3개** — `DRIVER`(2화면), `ACADEMY_ADMIN`·`PLATFORM_ADMIN`(각 4화면, 화면 동일·tenant 결정 방식만 다름)
- `STUDENT`·`PARENT`는 로그인은 성공하나 **"준비 중" 배너만** 본다
- 전체 라우트 **9개**, 프론트가 호출하는 REST 엔드포인트 **22개**
- 백엔드는 SOS·결석신고·일정변경·기초데이터 관리까지 구현돼 있으나 **프론트가 쓰지 않는다**
- 자동 판정(미승차·근접·SOS 에스컬레이션)은 **스케줄러로 실제 동작하지만, 그 결과를 볼 화면이 없다**

상세와 우선순위는 `PRODUCT_SPEC.md` 11~12장 참조.

---

## 4. 모듈별 심화 문서 (이 폴더 밖)

이 폴더가 상위 계약이고, 아래는 각 모듈의 구현 세부다. **사실이 어긋나면 이 폴더가 기준이다.**

| 문서 | 성격 |
|---|---|
| `backend/docs/PROJECT_MASTER_PLAN.md` | 백엔드 **구현 계획·진행 추적·백로그** (기획 부분은 `PRODUCT_SPEC.md` 로 이관됨) |
| `backend/docs/reference.md` | 백엔드 코드 컨벤션 — spec/impl 판단기준, CQRS, Event 규칙 |
| `backend/docs/BACKEND_ARCHITECTURE.html` | 백엔드 한정 아키텍처 심화판 (사람이 읽는 렌더) |
| `backend/docs/CODE_CONVENTIONS.html` | `reference.md` 의 사람용 렌더 |
| `backend/docs/API_ENDPOINTS.html` | 백엔드 API 심화판 (사람이 읽는 렌더) |
| `frontend/docs/FLUTTER_FRONTEND_PLAN.md` | 프론트 구현 계획·진행 추적 |
| `frontend/docs/FLUTTER_CODE_CONVENTIONS.md` | 프론트 코드 컨벤션 |
| `frontend/docs/DESIGN_SYSTEM.md` | 디자인 시스템 (색·간격·컴포넌트·상태 표기) |
| `backend/docs/학원 통학버스 통합관리 시스템.docx` | 기획 **원본**(불변) — 원문 근거가 필요할 때만 |
| `projectInfo.md` (루트) | 초기 작업 지시서 — **역사적 원본**. 현재 사실과 어긋나므로 참고용 |

---

## 5. 문서 포맷 규칙

- 이 폴더의 `.md` **4종이 원본**이다. `html/` 은 같은 내용의 사람용 렌더다 — 단 `html/architecture-overview.html` 은 렌더가 아니라 **그림 중심의 독립 요약본**이며 원본이 없다.
- **원본을 고쳤다고 HTML을 자동 동기화하지 않는다** — 필요하면 별도로 요청한다.
- Claude가 매 세션 재참조하는 문서는 토큰 효율을 위해 **Markdown으로 유지**한다.
- 새 기술 문서를 HTML로 만들 때는 전역 `html-docs` 정책(sketch 테마·인라인 SVG·Prism code-card)을 따른다.
