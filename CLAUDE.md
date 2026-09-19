# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

학원 통원버스 운행·학생 등하원 관리 **멀티 테넌트 플랫폼**의 **백엔드**(Spring Boot).

14개 도메인 모듈 + global 인프라가 엔티티~컨트롤러까지 구현돼 있고, 계획서 Phase 0~14 와 이월 묶음 F1~F6 이 전부 완료 상태다.

**⚠ 2026-09-09 저장소를 백엔드 전용으로 정리했다가 2026-09-10 프론트엔드를 다시 열었다.** 옛 `frontend/`(Flutter) 는 디스크·git 양쪽에서 삭제한 상태 그대로이고(`git show <이전 main 해시>:frontend/...`), 지금 `frontend/` 는 **새로 만드는 것**이다. 프론트 배포 워크플로(`deploy-web.yml`)는 제거된 채이며 배포 구성은 다시 만든다.

**git 추적 범위 — 앱 동작에 필요한 것 + 에이전트가 읽어야 하는 것 (2026-09-18 확장).** `backend/`(테스트·k6 부하 스크립트 포함) · `infra/` · `docker-compose*.yml` · `.github/workflows/deploy-backend.yml` 에 더해, **문서 Markdown(`docs/**/*.md` · `backend/docs/**/*.md`) · 이 파일(`CLAUDE.md`) · `.claude/PROJECT_NOTES.md` · `.claude/settings.json`** 을 추적한다.

- **뒤집은 이유** — `git worktree` 는 추적 파일만 체크아웃한다. Orca 가 만든 자식 워크트리에서 뜬 에이전트가 빌드 명령(`-PtestDbUrl` 필수)·Docker 포트·Flyway 재구성 정책·사양 4종을 모르는 채 시작했다. 2026-09-02(docs 전면 제외)·2026-09-09(`.claude`·`CLAUDE.md` 제외) 결정을 이 범위에서 뒤집는다.
- **여전히 제외** — 사람용 렌더(`docs/**` 의 HTML·PDF·PNG)와 기획 원본 `.docx`(파생본·바이너리) · 보고서(`report/`) · 라운드별 작업 기록(`.claude/` 나머지 · `.superpowers/`) · 재생성 가능한 색인(`graft/` · `.docgraph/`).
- ⚠ **원격은 공개 저장소(`mskim98/School-Bus`)다.** 문서는 **로컬 커밋만** 유지하고 push 하지 않는다(2026-09-18 사용자 결정). 워크트리 체크아웃에는 로컬 커밋이면 충분하다.
- 새 워크트리에서는 색인을 한 번 만든다 — `graft build .` (부모의 캐시를 복사하므로 **1.7초**)

## Orca 워크트리를 만들 때 (2026-09-18)

```bash
orca worktree create --name <이름> --setup skip     # 기본 — 이걸 쓴다
orca worktree create --name <이름> --setup run      # 프론트 의존성이 필요할 때만
```

- ⚠ **`--setup skip` 을 기본으로 쓴다.** 저장소에 `setup: pnpm install` 이 `run-by-default` 로 등록돼 있어, 생략하면 워크트리마다 프론트 의존성을 내려받는다. 백엔드만 만지는 작업에는 낭비다. **이 정책은 Orca CLI 로 못 바꾼다**(`orca repo` 에 설정 명령이 부재) — 앱 화면에서 바꾸거나 매번 플래그를 준다.
- `--setup run` 이 필요한 때 — `frontend/apps/academy-web` 또는 `frontend/packages/` 를 빌드·실행·시험하는 작업.
- ⚠⚠ **작업 창을 띄울 때 `--model` 을 빠뜨리면 그 창이 `opus[1m]` 로 뜬다.** `.claude/settings.json` 의 기본값이 **주 세션 기준으로 `opus[1m]`** 이기 때문이다(판정·병합 결정을 그 창에서 하므로 의도한 값이다). 작업 창은 성격이 달라서 **매번 명시해야 한다** — 2026-08-29 에 이 누락으로 구현 좌석 5개가 전부 opus 로 돌았고 한 개가 35만 토큰을 썼다(Skill `parallel-agents` §4.1).

  | 작업 | 붙일 것 |
  |---|---|
  | 결과 집계 · 로그 추출 · 개수 세기 | `--model claude-haiku-4-5` — **effort 는 주지 않는다** |
  | 구현 · 리뷰 | **`--model 'claude-sonnet-5[1m]' --effort high`** — ⭐ **sonnet 은 언제나 `[1m]`**(2026-09-19 사용자 상시 지시) |
  | 코드를 건드려 재현하는 디버깅 | 워크트리 + `--model claude-opus-5 --effort xhigh` |

  ⚠ **사양 충돌 판정 · 원인 미상 디버깅은 기본적으로 창을 띄우지 않는다 — 주 세션이 이미 `opus[1m]` · `xhigh` 다.** 별도 창이 값을 하는 경우는 둘뿐이다: ①**코드를 건드려 재현**해야 해서 워크트리 격리가 필요할 때 ②로그·파일을 대량으로 읽어 **주 세션의 조율 맥락을 밀어낼 때**. 둘 다 아니면 여기서 한다.

  **아래 등급으로 먼저 가고, 막히면 그때 올린다** (2026-09-18 사용자 결정). 미리 올려 두지 않는다 — Haiku→Sonnet 도, `xhigh`→`max` 도 같은 규칙이다. 공식 지침도 *"`max` 는 아래 단계에서 여유가 없다는 것이 측정으로 확인될 때만"*.

  배정 기준의 본문은 **Skill `parallel-agents` §4.1** 이다. 여기 다시 적지 않는다 — 두 벌이 되면 한쪽이 낡는다.
- **`effortLevel` 기본값도 `xhigh` 다**(주 세션이 opus 라서). `--effort` 를 빠뜨린 작업 창은 모델만 내려가고 **노력 수준은 `xhigh` 로 남는다** — 모델 누락과 같은 형태의 누출이라 위 표대로 둘을 같이 적는다.
- ⚠⚠ **Orca 가 띄우는 창에서는 `.claude/settings.json` 의 `model`·`effortLevel` 이 기준이 아니다.** Orca 는 자체 선택기 값을 **명령줄 인자로** 넘기고, 인자는 설정 파일보다 세다. 2026-09-18 실측 — 설정이 `opus[1m]`·`xhigh` 인데 실제 명령은 `claude --model 'opus[1m]' --effort 'medium'` 이었다(Orca 선택기가 Medium).
  - **Orca 를 거쳐 띄울 때는 Orca 의 `Effort`·`Model` 선택기를 맞춘다.** `settings.json` 은 **Orca 가 아무 값도 안 줄 때의 바닥값**이다
  - ⚠ **`Effort` 는 Orca 설정 화면에 없다 — 모델별로 딸린 값이라 모델을 고르는 자리에만 나타난다.** 저장 위치는 `~/Library/Application Support/orca/profiles/local-default/orca-data.json` 의 `settings.nativeChatSessionOptions.claude.valuesByModel["<모델>"].effort` 이고, 키 이름대로 **네이티브 채팅 창의 모델 선택기** 소속이다. 2026-09-18 접근성 스냅샷 실측 — 설정 화면·터미널 화면 어디에도 effort 요소가 부재했고 채팅 창은 접혀 있었다(`채팅 보기 표시` 토글)
  - ✅ **작업 창은 선택기를 쓰지 말고 명령을 직접 준다** — `orca terminal create --worktree <선택자> --title <이름> --command 'claude --model <모델> --effort <값>'`. Orca 공식 안내(`orca skills get orca-cli`)도 *"`worktree create --agent` 는 per-call model/effort 플래그가 없으니 `terminal create --command` 로 넘기라"* 고 적고 있다. 2026-09-18 실측으로 3종 전부 배너에서 확인 — `Haiku 4.5`(effort 표기 부재) · `Sonnet 5 with high effort` · `Opus 5 with xhigh effort`
  - ✅ **선택기를 우회해도 오케스트레이션은 그대로 붙는다.** `worker-start` 의 인자가 `(--agent <에이전트> | --terminal <핸들>)` 둘 중 하나이고, **`--model`·`--effort` 는 `--terminal` 과 함께 못 쓴다**(터미널이 이미 자기 인자로 떠 있으므로). **①`terminal create --command 'claude --model … --effort …'` → ②`worker-start --spec "<지시>" --terminal <핸들>`** 은 **이미 떠 있는 창에 붙일 때만** 쓴다 — 새로 띄우는 것은 아래 한 줄 경로다. 2026-09-18 실측 — `[ready] stage=input_accepted`, 워커가 지시를 읽고 답까지 냈다. **2026-09-18 오전에 `worker-start --agent claude` 가 `session is not attached` 로 실패한 것과 대비된다 — 깨져 있던 것은 오케스트레이션이 아니라 Orca 가 claude 를 대신 띄우는 부분이었다**
  - ✅✅ **한 줄 기동이 정식 경로다 — `worker-start --spec "<지시>" --worktree current --agent claude --model <모델> --effort <값>`.** 위 ①②(창을 먼저 만들고 붙이기)는 **아래 설정을 고치기 전의 우회책**이며 지금은 불필요하다. 2026-09-18 실측 — `launch.effective` 가 요청과 일치 · `term_` 핸들 · `ready`/`input_accepted` · `live` · 배너 `Sonnet 5` · `worker_done` 수신 · 해제 후 미정리 0건. `--model`·`--effort` 는 여전히 **`--terminal` 과 같이 못 쓴다**(창을 이미 그 인자로 띄웠으므로)
  - ⚠⚠ **그 한 줄이 5분 뒤 `exited` 로 죽으면 코드가 아니라 Orca 설정이다 — `설정 → 실험 → Chat UI → Default view`.** `worker-start --help` 가 *"워커가 어떻게 뜨는지는 사용자의 새 에이전트 탭 설정을 따르며 이를 위한 플래그는 부재"* 라고 적고 있다. 2026-09-18 실측 — `Default view = Chat UI` 이면 워커가 **화면 없는 구조화 채팅 세션**(`mode: structured` · `surface: background` · `structworker_` 핸들)으로 떠서 **`stage=dispatch_input` 에서 지시를 못 받고** 죽는다(`worker_done` 0건 · `worker-read` 는 `session is not attached`). **`Default view = Terminal chat` 으로 바꾸면 `mode: null` 로 정상 기동.** 앱 코드의 성립 조건은 `experimentalNativeChat && openAgentTabsInChatByDefault && experimentalStructuredNativeChat` 3개 전부 참일 때이므로 **가운데 하나만 끄면 되고 `Chat UI` 토글 자체는 켜 둔다.** 설정 변경 CLI 는 부재하고(`orca settings` 없음) 값은 `~/Library/Application Support/orca/profiles/local-default/orca-data.json` 의 `settings.openAgentTabsInChatByDefault` 에서 읽어 확인한다 — ⚠ **앱이 그 값을 메모리에 들고 있어 파일을 직접 고치지 않는다**
  - ✅ **상속 부재의 결정적 근거 — 워커의 모델 ID 에 `[1m]` 이 미부착.** 2026-09-18 실측: 조율자 세션이 `claude-opus-5[1m]`(1M 컨텍스트)인데 거기서 띄운 opus 워커가 **자기 시스템 프롬프트를 인용해 답한 값은 `claude-opus-5`**. 상속이면 `[1m]` 이 동반됐어야 한다. **배너로 판정하지 말고 본인에게 물어 확인**한다(Skill `parallel-agents §4.1.1`) — 3좌석 전원 진술 확보: `claude-haiku-4-5-20251001` · `claude-sonnet-5` · `claude-opus-5`
  - ⚠ **`worker_done` 은 자동으로 오지 않는다 — 워커가 명령을 직접 실행해야 한다.** 위 실측에서 Haiku 는 `orchestration send … --type worker_done …` 을 **코드 블록으로 출력만 하고 실행하지 않았고**, 조율자 인박스는 `No messages` 로 남았다. **2026-09-18 하루에 3회 전부 Haiku 에서만 재발**(Sonnet·Opus 는 6회 전부 정상 발신) — 경향이 아니라 **Haiku 고유 형태로 확정**. ⇒ **Haiku 좌석은 완료 수집에 기대지 말고 처음부터 `orca terminal read` 로 답을 회수**하고, 미settle 로 남은 Dispatch 는 `worker-stop` 으로 닫는다(한 줄 기동으로 만든 창은 `worker-start` 소유라 `stop`·`release` 가 창까지 닫는다 — **`terminal create` 로 미리 만든 창은 `retained` 로 남아 손으로 닫아야 한다**). 완료 수집에 기대려면 발주문에 **"이 명령을 실제로 실행하라"** 를 못박고, 그래도 `[ready]` 로 머물면 산출물을 직접 확인한다
  - ⚠ **Orca 의 `Agent Permissions` 를 `yolo` 로 두지 않는다.** `claude` 에 `--dangerously-skip-permissions` 가 붙어 권한 분류기가 통째로 꺼진다(ECC `common/hooks.md` 가 금지). 2026-09-18 에 이 플래그 때문에 확인 대화상자가 뜨고, 터미널 포커스 신호가 그 대화상자에 입력으로 들어가 **기동이 취소**되기도 했다. `manual` 로 둔다
- ⚠ **Haiku 4.5 에는 effort 를 주지 않는다.** 2026-09-18 실측 — `effortLevel: xhigh` 를 물려받은 상태로 `--model claude-haiku-4-5` 를 띄웠더니 **오류 없이 기동했고 배너에 effort 표기가 아예 없었다**(Sonnet 은 `Sonnet 5 with high effort`). Claude Code 가 지원하지 않는 모델에는 빼고 넘긴다. 명령줄에 직접 `--effort` 를 주는 것은 여전히 피한다.
- ⚠ Haiku 4.5 는 컨텍스트가 200K 고정이다(Sonnet·Opus 는 1M). 탐색이 넓은 작업에는 쓰지 않는다.
- ⭐⭐ **sonnet 작업 창은 예외 없이 `[1m]` 을 붙인다 — 2026-09-19 사용자 상시 지시.** *"앞으로 sonnet 은 오케스트레이터든 다른 방식이든 전부 1m 으로 띄워줘."* **접미사는 적어야만 붙고 부모에게서 상속되지 않는다** — 2026-09-19 실측: 조율 세션이 `claude-opus-5[1m]` 인데 `--model claude-sonnet-5` 로 띄운 워커 2개의 기동 기록이 `requested`·`effective` 둘 다 접미사 부재였다.
  - 확인 — `orca orchestration worker-show --dispatch <id> --json` 의 `result.worker.startOptions.launch.effective.model` 에 `[1m]` 이 실재하는지 본다. **배너로 판정하지 않는다**
  - ⚠ **쉘에서 대괄호가 글로브로 해석되므로 따옴표로 감싼다** — `--model 'claude-sonnet-5[1m]'`
  - ⚠ **`--effort` 는 별개다** — 모델만 바꾸고 빠뜨리면 노력 수준이 부모(`xhigh`)로 남는다
- ⚠ **모델을 섞으면 프롬프트 캐시가 갈린다** — 캐시는 모델별 이름공간이라 창마다 모델이 다르면 재사용이 끊긴다. 그래서 **비용을 줄일 때는 모델 교체보다 `--effort` 를 먼저 내린다**(공식 지침).
- ⚠⚠ **`worktreeBaseRef` 는 `refs/heads/main`(로컬)이어야 한다.** 기본값이 `origin/main` 이었고, push 하지 않는 저장소라 원격은 **360 커밋 뒤처져** 있었다 — 그대로 두면 워크트리가 몇 달 전 코드에서 갈라진다. 2026-09-18 에 `orca repo set-base-ref --repo id:88941bb9-3200-415e-a8a9-0e2d5bb4ab7a --ref refs/heads/main` 으로 고쳤다. 저장소를 다시 등록하면 이 값을 확인한다.

**프론트엔드는 2026-09-10 사용자 결정으로 범위 안이다** — 2026-09-04 Ruling 255(영구 범위 밖)를 뒤집었다. `docs/IMPLEMENTATION_PLAN` 의 Phase F1~F4 `➖` 표기는 옛 Flutter 계획에 대한 것이라 그대로 두고, **프론트 작업의 창구는 `frontend/IMPLEMENTATION_PLAN.md` 로 분리**했다.

- 제품 3개 — 관계자 웹(**Next.js**, 메인 관리자 콘솔을 `(admin)` 라우트 그룹으로 합침) · 학부모·학생 앱(**Flutter**) · 매니저 앱(**Flutter**)
- **앱 하나가 로그인 결과의 역할로 갈라진다** — 학부모↔학생, 기사↔동승자. 갈리는 것은 화면이 아니라 권한과 진입점
- 코드 규칙은 `frontend/CONVENTIONS.md`(React) · `frontend/CONVENTIONS_FLUTTER.md`(Dart) 2종
- 디자인 시스템 사본은 `frontend/design-system/`(**읽기 전용**, 정본은 claude.ai 원격). ⚠ **킷이 구 기획 기반이라 `docs/` 와 어긋나는 곳이 있다** — 어긋나면 `docs/` 가 기준이고, 확인된 4건은 `frontend/IMPLEMENTATION_PLAN.md §4`

- **작업 전 반드시 [`docs/README.md`](docs/README.md) 를 먼저 읽는다.** 제품 사양 4종(`FEATURE_SPEC` · `PRD` · `USER_FLOWS` · `API_SPEC`)의 진입점이며, 이 4개가 **단일 소스(SoT)** 다. **2026-08-24 서비스 방향 전환으로 전면 재작성됐고, 순수 기획(To-Be)이라 구현 상태 표기가 없다** — 현재 코드는 상당 부분이 이 사양과 어긋나며 앞으로 사양에 맞춰 수정할 대상이다.
- **기반 문서는 `docs/FEATURE_SPEC.md`** — 공통 규칙 C-01~16, 정책 상수(확정 30분 전 · ②구간 회차당 1회 · 운행 시작 ±3분 등), 엔티티·상태머신, 기능 ID 체계가 여기서 정의되고 나머지 3종이 이를 참조한다. 새 기능 ID·상태값을 만들지 않는다.
- 문서의 `🔸` 는 구 기획에서 흡수해 **존치 미확정**인 항목이다 — 확정 사실로 취급하지 않는다. (근거 없는 신규 설계를 표시하던 `🆕` 는 2026-08-24 전건 승인되어 제거됐다.)
- **설계 문서는 `docs/ARCHITECTURE.md`(모듈·인가·노선 파이프라인·시간 기반 배치)와 `docs/ERD.md`(테이블·제약·인덱스)** 다. 둘 다 사양 4종에서 유도한 **To-Be 설계**이며 현재 코드와 다르다 — 코드를 이 설계에 맞추는 것이 앞으로의 작업이고, 방향 전환 이전의 코드 실측본은 `git show HEAD:docs/ARCHITECTURE.md` 로 본다.
- **이 서비스의 중심축은 시간이다** — 회차 `idle → confirmed` 전이는 사용자 조작이 아니라 **출발 30분 전 도래**가 일으킨다(`ARCHITECTURE §9`). 배치는 30초 폴링 + 조건부 UPDATE 멱등이고, "실행 시각"과 "판정 시각(출발−30분)" 두 시계를 절대 섞지 않는다.
- **구현 계획·진행 추적은 [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) 단일 창구다.** 세션 재개 시 §8 진행 추적 표에서 현재 Phase 를 확인하고, 작업이 끝나면 그 표를 갱신한다. 횡단 규칙은 §7(**24개**).
- **작업의 최소 단위는 Phase 가 아니라 기능 ID 1개이고, 그 단위를 `IMPLEMENTATION_PLAN §4.6` 의 TDD 사이클로 처리한다** — 목표 → RED(실패를 눈으로 확인) → GREEN(최소 구현) → REFACTOR → 검증. **실패를 보지 않은 테스트는 산출물로 인정하지 않는다.** 이 시스템의 결함 4종(시각·동시성·인가·개인정보 노출)은 전부 "통과하는 빈 테스트"와 구분되지 않는 형태라 RED 확인이 유일한 판별 수단이다.
- **코드 컨벤션은 `backend/docs/reference.md`** — 특히 §19(클래스·public 메서드·enum·이벤트·포트에 한 줄 설명 주석)와 §20(SRP·크기 기준·클린 코드)은 매 Phase 채점 대상이다.
- 기획 원본은 `backend/docs/학원 통학버스 통합관리 시스템.docx`(불변) 하나다. 루트 `projectInfo.md` 는 **2026-08-24 삭제** — 내용이 두 세대 낡아 오인용 위험이 컸다. 필요하면 `git show HEAD:projectInfo.md`.
- 사실이 여러 문서에서 어긋나면 **`docs/` 가 기준이다.**

- 사용자 5계층: 학생 / 학부모 / 운전기사 / 학원 관리자 / 플랫폼 관리자 — 학원 관리자와 플랫폼 관리자는 권한 범위가 완전히 다른 별개 역할
- 멀티 테넌시: `User`↔`Tenant` 는 N:M 이며 `UserTenantRole(user_id, tenant_id, role)` 연결 테이블로 표현한다. `User` 에 `tenant_id` 를 직접 박지 않는다. 격리는 DB 레벨(RLS·`@Filter`)이 아니라 **애플리케이션 코드**에서 강제된다.

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
```

## Docker — 개발 / 배포가 파일로 갈려 있다 (2026-09-18 분리)

| 파일 | 무엇 | 명령 |
|---|---|---|
| `docker-compose.yml` | **개발 인프라만** — postgres(15432) · redis(16379) | `docker compose up -d` |
| `+ docker-compose.app.yml` | **앱 오버레이** — backend · **관계자 웹** · proxy(:3000) · 관측 | `docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build` |
| `docker-compose.prod.yml` | **배포** — ECR 이미지 · 영속 볼륨 · TLS | `docs/DEPLOYMENT.md` |

**개발 방식은 둘 중 하나를 고른다.**
1. **인프라만 컨테이너 + 백엔드는 IDE** — `docker compose up -d` 후 `./gradlew bootRun`. Swagger 는 `http://localhost:8080/...`
2. **전부 컨테이너** — 위 오버레이 명령. **모든 HTTP 는 proxy(:3000) 한 곳을 지난다** — 관계자 웹 `http://localhost:3000` · API `.../api/v1/...` · Swagger `.../swagger-ui/index.html` · Grafana `:3001`(admin/admin) · Prometheus `:9090`

- ⚠⚠ **프록시가 `3000` 인 것은 우연이 아니다.** 네이버 지도 키의 **서비스 URL** 과 백엔드 **CORS 허용 목록**이 둘 다 `http://localhost:3000` 으로 등록돼 있다. 다른 포트로 열면 지도 SDK 의 `/v3/auth` 가 **401** 로 거절되고 화면에는 *"지도를 불러오지 못했습니다"* 만 뜬다(2026-09-18 `:80` 으로 열었다가 실제로 겪었고, 3000 으로 바꾸니 같은 요청이 200 이 됐다). 포트를 바꾸려면 **NCP 콘솔의 서비스 URL 부터** 바꿔야 한다

- ⚠ **오버레이를 썼으면 `down` 에도 `-f` 두 개를 그대로 준다.** 빼면 compose 가 인프라 파일만 읽어 backend·proxy·관측 컨테이너가 **살아남는다.** 번거로우면 `export COMPOSE_FILE=docker-compose.yml:docker-compose.app.yml`
- ⚠ **`/actuator` 는 프록시가 라우팅하지 않는다 — 404 가 정상이다.** 헬스는 `docker compose ... exec backend curl -s localhost:8080/actuator/health`
- ⚠ **컨테이너 모드에서 Redis 포트를 덮어야 한다**(`SPRING_DATA_REDIS_PORT: 6379`). `application.yml` 기본값 16379 는 **호스트에 낸 포트**라, 호스트만 덮고 두면 **기동은 되는데 헬스가 DOWN 으로 남는다**(2026-09-18 실제 발생)

앱 기동 후 API 테스트는 Swagger UI 를 쓴다 — 로그인 응답의 `access_token`(응답 봉투 `data` 안)을 우측 상단 Authorize에 넣으면 이후 요청에 자동으로 붙는다. 로그인 계정은 Flyway 시드(`db/migration-local/V2__seed_data.sql`) 참조 — **로컬**은 비밀번호가 모두 `password`(배포 환경은 다름, 아래 Flyway 항목 참고).

**로컬 postgres는 의도적으로 영속 볼륨이 없다**(2026-07-22~, Swagger로 반복 테스트해도 항상 시드 상태로 되돌리기 위함) — `docker compose down`(컨테이너 제거) 후 `docker compose up -d postgres redis`로 다시 띄우면 Flyway가 스키마(V1)+데모 시드(V2)를 매번 자동으로 새로 구성한다(수동 `DROP SCHEMA`/`volume rm` 불필요, `DataInitializer`는 2026-07-20 삭제됨). `stop`/`start`(컨테이너를 제거하지 않음)는 데이터가 유지된다 — 리셋하려면 반드시 `down`을 거칠 것.

**배포**는 `docs/DEPLOYMENT.md`를 따른다. 설계 근거(관리형 서비스 채택 검토·차단 결함·비용)는 `docs/superpowers/specs/2026-08-10-mvp-배포-design.md`. 운영은 EC2 1대 + `docker-compose.prod.yml`이며 **백엔드 인스턴스는 반드시 1개**다(`@Scheduled` 중복·InMemory 버스위치·WS 세션 로컬 보관).

## Stack / 주요 특이사항

- **Spring Boot 4.1.0**, **Java 25**(toolchain 고정), Gradle. 웹 스타터는 신형 아티팩트명 `spring-boot-starter-webmvc`(테스트는 `spring-boot-starter-webmvc-test`)를 사용한다 — 구버전 `spring-boot-starter-web`이 아님.
- **스키마는 Flyway가 관리**(`spring-boot-starter-flyway`+`flyway-database-postgresql`, 2026-07-20부터)한다 — `ddl-auto: validate`로 Hibernate는 검증만.
- **개발 단계에서는 마이그레이션을 새 버전으로 쌓지 않아도 된다**(2026-08-23 정책 변경). 스키마를 바꿔야 하면 **기존 파일(`V1__init_schema.sql` 포함)을 직접 고치고 로컬 DB를 통째로 재구성**하는 편을 우선한다 — `docker compose down` 후 `docker compose up -d postgres redis`. 로컬은 영속 볼륨이 없어 데이터를 잃을 것이 없고, 버전 파일이 늘어나 스키마의 최종 형태를 여러 파일에 흩어 놓는 것보다 낫다. 기존 파일을 고치면 체크섬이 바뀌어 **이미 적용된 DB는 `FlywayValidateException`으로 기동에 실패**하므로, 재구성 없이 앱만 다시 띄우면 실패한다는 점만 기억한다(코드 결함이 아니라 재구성 누락 신호다).
- **이 예외는 "아직 아무 영속 환경에도 적용되지 않은 마이그레이션"에만 해당한다.** demo·prod에 한 번이라도 적용된 뒤에는 원칙이 뒤집혀 **기존 파일 수정 금지 · `V{n}` 추가만 허용**이다. 운영 DB는 볼륨이 있어 재구성으로 되돌릴 수 없고, 체크섬 불일치는 곧 기동 불가다. 첫 배포 시점에 이 항목을 갱신할 것. 데모 시드는 별도 위치 `db/migration-local/`에 있고 **`local`·`demo` 두 프로파일에서만** `spring.flyway.locations`에 추가된다(`prod`엔 안 들어감). 시드 계정의 비밀번호 해시는 Flyway placeholder `seedPasswordHash`로 주입한다 — local은 `application.yml`의 기본값(평문 `password`), demo는 SSM에서 받은 값이라 **배포 환경의 비밀번호는 `password`가 아니다.**
- 기본 패키지가 `src.backend`이고 Gradle `group = 'src'`이다(비관례적). 새 클래스는 이 `src.backend` 하위에 두어 `@SpringBootApplication` 컴포넌트 스캔 범위를 유지한다.
- **코드 컨벤션 상세는 `backend/docs/reference.md`(Claude 참조용, Markdown)를 먼저 읽는다.** spec/impl 판단기준·패키지 구조·CQRS·Event 규칙 등 전체 원칙이 정리돼 있다. 사람이 브라우저로 보는 동일 내용의 렌더링 버전은 `backend/docs/CODE_CONVENTIONS.html`(시각화 포함) — 둘은 원칙은 같고 매체만 다르며, Claude는 세션마다 `reference.md`를 참조한다.
- **핵심 요약**: service·repository는 "구현이 바뀔 가능성이 있는가"를 기준으로만 `spec`(인터페이스) / `impl`(구현체) 하위 패키지로 분리한다(단순 CRUD는 분리하지 않음) — 예 `bus/service/spec/BusService.java` + `bus/service/impl/BusServiceImpl.java`. 컨트롤러 등은 `spec`만 의존한다. `package-info.java`는 두지 않는다(패키지 레벨 애너테이션이 필요할 때만 예외).

**설계 제약·데이터 모델·기능 범위는 `docs/` 를 본다** — 공통 규칙·엔티티·상태머신·권한은 `docs/FEATURE_SPEC.md`(§2·§3·§6), 정책 채택 이유와 우선순위는 `docs/PRD.md`(§6·§7), 엔드포인트 계약은 `docs/API_SPEC.md`. 현 코드의 구조(위치추적 Mock 추상화 등)는 `docs/ARCHITECTURE.md`(§7·§8) — 단 이쪽은 신규 사양 미반영 상태다.

## 응답/문서 규칙

이 사용자는 Spring 입문 단계의 백엔드 개발자다. 전역 `~/.claude/CLAUDE.md` 규칙(한글 응답, 개념별 1줄 요약 → 번호 흐름 → request→처리→response, Controller/Service/Repository 연계 설명, 흔한 오해 1개 포함)을 따른다.

**문서 포맷 규칙**: 사람이 브라우저로 보는 기술문서(아키텍처·규칙 등)는 전역 정책대로 HTML+인라인 SVG로 작성한다. 단 **Claude가 매 세션 재참조하는 사양·설계·진행추적·컨벤션 문서(`docs/` 8종, `reference.md` 등)는 토큰 효율을 위해 Markdown으로 유지**한다(HTML의 SVG·CSS 골격은 재로딩 비용만 크다). 같은 내용의 인간용 HTML 렌더(예: `CODE_CONVENTIONS.html`)가 별도로 존재할 수 있으며, 둘은 원칙만 동기화하고 서로 대체하지 않는다.

**HTML 문서 수정 규칙 (2026-07-18 확정)**: `CODE_CONVENTIONS.html` 등 사람용 HTML 문서는 **사용자가 명시적으로 요청한 경우에만** 생성·수정한다. Markdown 원본(`reference.md` 등)을 바꿨다고 해서 대응하는 HTML을 자동으로 동기화하지 않는다 — 필요하면 사용자가 별도로 요청한다.
