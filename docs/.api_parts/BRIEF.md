# API 명세서 HTML 재편 — 좌석 공통 지시서 (2026-09-02)

**저장소** `/Users/mskim/Desktop/PJ/School-Bus` (모든 경로는 절대 경로로 `cat`/Read 한다. `find` 로 찾지 말고, 접힌 출력을 근거로 "없다" 고 결론 내지 마라).
**정본** `/Users/mskim/Desktop/PJ/School-Bus/docs/API_SPEC.md` (v1.0). 기능 정의·도메인 소속은 `/Users/mskim/Desktop/PJ/School-Bus/docs/FEATURE_SPEC.md §4`.
**산출물** 각 좌석은 지정된 **한 파일**(`docs/.api_parts/partN.html`)에 **본문 조각만** 쓴다. `<html>`·`<head>`·`<style>`·`<script>`·`<body>` 를 넣지 마라 — 껍데기는 조율자가 `shell_head.html`·`shell_tail.html` 로 이미 만들었고 CSS 클래스는 거기 정의돼 있다(열어서 클래스명을 확인하라).
**git 을 건드리지 마라.** `docs/` 는 git 무시 대상이라 add·commit 이 필요 없고, 다른 세션이 이 저장소에서 작업 중이다. 작업 트리·인덱스·HEAD·브랜치를 바꾸지 마라. 서브에이전트를 띄우지 마라.
**파일 쓰기가 도구에서 거부되면 전문을 메시지로 보내라.** 조율자가 저장한다.

## 1. 목표 — 무엇이 통과하면 끝인가

1. 배정된 도메인의 **정본 엔드포인트 전부**가 아래 §3 템플릿의 `details.ep` 블록 하나씩으로 존재한다 (누락 0).
2. 블록마다 6개 항목이 전부 있다 — **인증** · **요청** · **응답** · **에러 코드별 응답** · **부수 효과** · **구현 상태**. 하나라도 비면 미완.
3. 모든 사실은 정본(`API_SPEC.md`)에서 옮긴다. **정본에 없는 값(필드·상한·주기·정책)을 만들어 넣지 마라.** 정본에 없으면 "정본 미기재" 로 적는다.
4. 구현 상태는 **컨트롤러 코드를 직접 열어** 판정한다 (§5). 추측 금지.
5. 조각을 조율자가 이어 붙였을 때 태그가 균형이고, SVG 클래스·marker id 가 다른 좌석과 충돌하지 않는다 (§6 접두사).

## 2. 사실 규칙 — 정본 vs 코드 vs 판정

| 어긋남 | 이긴다 | 문서에 적는 방식 |
|---|---|---|
| 정본 ↔ 코드 | **정본** | 정본대로 적고, 구현 상태를 `불일치` 로 표시 + 비고에 `파일:줄` 과 무엇이 다른지 한 줄 |
| 정본 ↔ 아래 확정 판정 | **판정** (사용자 확정) | 판정대로 적고 `(Ruling NNN)` 표기 |
| 정본 안의 두 절이 서로 다름 | 판정 못 함 | 둘 다 적고 `⚠ 정본 내 불일치` 로 표시. 임의로 고르지 마라 |

**적용할 확정 판정** (`docs/IMPLEMENTATION_PLAN.md` 의 Ruling 표. 번호로 `grep -n '^| \*\*NNN\*\*'` 해 원문을 읽어라):
- **198** ①구간은 재최적화 호출 0회 — 확정 배치가 반영 (`§3.6` 각주에 이미 반영됨, 그대로 옮긴다)
- **201 · 204** 외부 내비: 공급자는 서버 설정이 정하고 응답이 알려줌 · 카카오 상한 4곳(경유지 3 + 목적지 1) · `truncated` (`§4.16` 에 반영됨)
- **207** 근접 알림 = 다음 미도착 승하차지까지 직선거리 300m 이내 최초 1회 (`§4.12` 부근)
- **209** WS 연결 엔드포인트는 `/ws/location` 하나, 채널은 STOMP 구독 목적지 (`§7`)
- **216** `§3.12` `popup` · `§3.13` `403`/`404 NOTIFICATION_NOT_FOUND` · `§3.14` `422` · `§5.17` `unacked_count`
- **217** `§2.11` 푸시 단말 등록은 이미 구현됨 (`DeviceController`), `pending` 허용
- **219** 되돌리기(`§4.7`) 시 기발송 알림은 정정하지 않고 **"승차 취소" · "하차 취소" 알림을 새로 발행** — 알림 종류 신설(Phase 12 진행 중). 코드에 아직 없으면 "Phase 12 진행 중" 으로 적는다
- **220** `NTF-06` 지연 알림(`§4.9`)은 정본에 있으나 **구현 소유 Phase 미배정**(오픈 이슈 W)
- **Phase 11 이월 ①** `§5.16`·`§6.11` 비상 알림 응답 필드가 정본과 코드에서 7건 어긋남 — 정본대로 적고 `불일치` 표시 (상세는 `.superpowers/sdd/IMPLEMENTATION_PLAN/report-p11-t2-fix1.md` 맨 끝)

## 3. 엔드포인트 블록 템플릿 — 이 마크업을 그대로 쓴다

```html
<details class="ep" id="ep-post-runs-runId-start">
  <summary>
    <span class="b b-post">POST</span>
    <span class="path">/runs/{runId}/start</span>
    <span class="title">운행모드 시작</span>
    <span class="ids">RUN-02 · M-10 · 정본 §4.4</span>
  </summary>
  <div class="body">
    <h4>인증 · 권한</h4>
    <dl class="kv">
      <dt>토큰</dt><dd><span class="b b-auth">Bearer 필수</span> <code>Authorization: Bearer {access_token}</code></dd>
      <dt>허용 계정 상태</dt><dd><code>active</code> 만 (게이트 §1.4)</dd>
      <dt>역할 · 권한</dt><dd>버스기사 — <code>RUN_START</code>. 배치되지 않은 회차는 <code>403 FORBIDDEN</code></dd>
      <dt>격리</dt><dd>소속 학원 + 배치된 회차 (§1.5)</dd>
    </dl>

    <h4>요청</h4>
    <table>
      <thead><tr><th>위치</th><th>이름</th><th>타입</th><th>필수</th><th>설명</th></tr></thead>
      <tbody>
        <tr><td>경로</td><td><code>runId</code></td><td>string</td><td>●</td><td>회차 식별자</td></tr>
        <tr><td>본문</td><td><code>client_key</code></td><td>string(UUID)</td><td>○</td><td>멱등키 (§1.7)</td></tr>
      </tbody>
    </table>
    <div class="code-card">
      <div class="code-card-header"><span class="code-card-dots"><span></span><span></span><span></span></span><span class="code-card-lang">HTTP · 요청 예시</span></div>
      <pre class="line-numbers"><code class="language-json">{ "client_key": "5f1c…" }</code></pre>
    </div>

    <h4>응답</h4>
    <p><code>200</code> — 변경 후 회차 상태를 그대로 반환 (§1.9).</p>
    <table>
      <thead><tr><th>필드</th><th>타입</th><th>필수</th><th>설명</th></tr></thead>
      <tbody>
        <tr><td><code>run_status</code></td><td>enum</td><td>●</td><td><code>moving</code></td></tr>
      </tbody>
    </table>
    <div class="code-card">
      <div class="code-card-header"><span class="code-card-dots"><span></span><span></span><span></span></span><span class="code-card-lang">JSON · 200 응답 예시</span></div>
      <pre class="line-numbers"><code class="language-json">{ "run_status": "moving", "started_at": "2026-08-24T08:31:12+09:00" }</code></pre>
    </div>

    <h4>에러 코드별 응답</h4>
    <table>
      <thead><tr><th>HTTP</th><th>코드</th><th>발생 조건</th><th><code>details</code></th></tr></thead>
      <tbody>
        <tr><td>409</td><td><code>RUN_START_WINDOW_CLOSED</code></td><td>출발 시각 ±10분 밖</td><td><code>depart_time</code> · <code>window_minutes</code></td></tr>
      </tbody>
    </table>
    <div class="code-card">
      <div class="code-card-header"><span class="code-card-dots"><span></span><span></span><span></span></span><span class="code-card-lang">JSON · 409 RUN_START_WINDOW_CLOSED 응답 예시</span></div>
      <pre class="line-numbers"><code class="language-json">{ "error": { "code": "RUN_START_WINDOW_CLOSED", "message": "…", "details": { "depart_time": "…", "window_minutes": 10 } } }</code></pre>
    </div>
    <details class="sub"><summary>공통 에러 (§1.11) — 401 TOKEN_EXPIRED · 403 AUTH_PENDING · 403 AUTH_ACCOUNT_BLOCKED · 403 FORBIDDEN · 403 ACADEMY_SCOPE_VIOLATION · 422 VALIDATION_FAILED</summary>
      <p>본문 형식은 §1.10 과 동일. 이 엔드포인트에서 특별한 뜻을 갖는 공통 코드만 위 표에 따로 적었다.</p>
    </details>

    <h4>부수 효과</h4>
    <ul>
      <li>상태 전이 <code>confirmed → moving</code>, 노선 전면 잠금</li>
      <li>알림 <code>run_started</code> → 관계자 · 학부모 · 학생 (NTF-05)</li>
      <li>WS 방송 <code>run_started</code> → <code>/ws/students/{id}/run</code> · <code>/ws/manager/runs/{id}</code> · <code>/ws/academy/{id}/live</code> · <code>/ws/admin/live</code></li>
    </ul>

    <h4>구현 상태</h4>
    <p class="status-line"><span class="b b-ok">구현</span> <code>run/controller/DriverRunController.java:41</code> — 2026-09-02 대조. 정본과 일치.</p>
  </div>
</details>
```

**규칙**
- `id` 는 `ep-{method}-{경로를 하이픈으로}` — `/`·`{`·`}` 를 하이픈으로 바꾸고 소문자 메서드. 두 메서드가 한 절에 묶인 경우(`GET · PATCH /x`)는 **블록을 둘로 나눈다**.
- 메서드 배지 클래스: `b-get` · `b-post` · `b-patch` · `b-put` · `b-delete`. 토큰 배지: `b-auth`(필수) · `b-noauth`(비인증 허용 경로 5개). 구현 배지: `b-ok`(구현) · `b-no`(미구현) · `b-diff`(불일치) · `b-hold`(조정 중 · 소유 미배정).
- `.ids` 에는 기능 ID(도메인 코드 · 계층 ID)와 **정본 절 번호**를 반드시 적는다 — 독자가 md 로 되돌아갈 수 있어야 한다.
- 예시 JSON 은 정본의 필드 표에서 조립한다. **정본에 없는 필드를 예시에 넣지 마라.** 값은 그럴듯한 예시면 된다 (식별자 형식은 정본 `§1.1`).
- 에러 코드별 응답 예시 code-card 는 **엔드포인트 고유 에러마다 1개** (`details` 가 있는 코드는 그 필드를 보여 준다). 고유 에러가 0개면 표 대신 "고유 에러 부재 — 공통 에러만" 한 줄.
- HTML 특수문자는 이스케이프 (`<` → `&lt;`, `>` → `&gt;`, `&` → `&amp;`). 코드 카드는 `pre.line-numbers` + `code.language-json` **둘 다** 필요.
- 요청 본문이 없는 GET 은 요청 예시 code-card 를 `HTTP · 요청 예시` 로 한 줄(`GET /api/v1/... ` + 헤더)만 넣는다.
- 정본이 "[조정 중]" 인 항목은 블록을 만들되 요청·응답 표에 "미확정 — 정본 §10" 을 적고 구현 배지 `b-hold`.

## 4. 도메인 절 구조

```html
<h2 id="dom-run">RUN — 운행 실행</h2>
<p class="dom-head">기능 8 · 주 사용자 버스기사 · 동승자 · 정본 §4.1 · §4.3 · §4.4 · §4.5 · §4.10 · §4.11 · §4.16 · 기능 정의 <code>FEATURE_SPEC §4.10</code></p>
<p>도메인 한 단락 — 이 도메인의 엔드포인트가 공유하는 규칙(권한 · 격리 · 상태 전이)만. 정본 절 서두의 문장을 옮긴다.</p>
<!-- 필요하면 도메인 규칙 표 · viz-frame 1개 -->
<!-- details.ep 블록들 — 정본 순서대로 -->
```

`h2` 의 `id` 는 조율자 목차(`shell_head.html` 의 `nav.toc`)와 **정확히 일치**해야 한다: `dom-auth` `dom-acad` `dom-stu` `dom-bus` `dom-mgr` `dom-sch` `dom-rte` `dom-req` `dom-att` `dom-run` `dom-rst` `dom-brd` `dom-exc` `dom-loc` `dom-ntf` `dom-mon` `dom-sys` · 공통 `common` · 도메인 인덱스 `domains` · `ws` · `errors` · `enums` · `pending`.

## 5. 구현 상태 대조 절차 (엔드포인트마다)

1. 컨트롤러 목록: `grep -rl '@RestController' /Users/mskim/Desktop/PJ/School-Bus/backend/src/main` (44개). 경로 접두사 `/api/v1` 은 `global/config/ApiPathPrefixConfig.java` 가 붙인다 — 컨트롤러의 `@RequestMapping` 에는 없다.
2. 경로 문자열로 찾는다: `grep -rn '"/runs/{runId}/start"' backend/src/main` 처럼 **따옴표 포함**으로. 클래스 레벨 `@RequestMapping` + 메서드 레벨 매핑을 합쳐 판정한다.
3. 판정 — `구현`(메서드·경로 실재) · `미구현`(부재) · `불일치`(실재하나 메서드/경로/권한/상태 코드가 정본과 다름 — 무엇이 다른지 한 줄). 권한은 메서드의 메타 애너테이션(`global/security/authz/` 의 `@CanXxx`)으로 본다.
4. `파일:줄` 을 적는다 (경로는 `backend/src/main/java/src/backend/` 이하만).
5. **본문 필드까지 전수 대조하지는 않는다** — 그것은 이 작업의 범위 밖이다. 단 Phase 11 이월 ①(비상 알림 응답 7건)처럼 **이미 알려진 불일치**는 적는다.

## 6. SVG 시각화 규칙 (좌석별 접두사)

각 좌석은 배정된 SVG 를 **인라인 `<svg>`** 로 `.viz-frame` 안에 그린다. `viewBox="0 0 1100 H"` + `width="100%"`. 텍스트 크기 — 제목 `bold 16px` · 보조 `14px` · 캡션 `12.5~13px`. 텍스트 색 `#3a3531`/`#6b6359`, 선 `#c0b6a8`, 박스 `fill:rgba(R,G,B,.10)` + `stroke` 진한 색(정상 `#0e7490` · 완료 `#047857` · 주의 `#b45309` · 문제 `#dc2626` · 보조 `#7c3aed`). **밝은 글씨 금지.** `text-anchor:middle` 라벨을 가장자리 x 에 두지 마라. 개별 요소 덮어쓰기는 프레젠테이션 속성이 아니라 인라인 `style=""`.
**`<style>` 안 클래스명과 `<marker id>` 전부에 좌석 접두사를 붙인다** — 다른 좌석과 겹치면 조용히 깨진다. "도해" 라는 말을 쓰지 마라 — "그림" 또는 종류 이름. 캡션(`.cap`)은 그림이 말하지 못하는 것만(판정 기준 · 색의 뜻).
ASCII 아트 금지 — `.viz-frame` 안에 `<pre>` 를 두지 마라.

| 좌석 | 접두사 | 그릴 것 |
|---|---|---|
| W1 | `c1-` `c2-` `c3-` | ①토큰 발급·재발급·무효화 흐름(앱 vs 웹 refresh 경로 분기) ②3구간 타임라인(출발−30분 · 출발 · moving, 각 구간의 처리) ③도메인 인덱스 지도(17 도메인을 계층 6개와 잇는 그림) |
| W2 | `a1-` | 가입 → 승인 → 활성화 → 레코드 연결 시퀀스(계정 상태 게이트가 어느 요청을 막는지) |
| W3 | `r1-` `r2-` | ①②구간 승인 흐름(요청 접수 → 미리보기 → 승인/거절/자동 거절 → 재배포 → ack) ②확정 노선 산출 파이프라인 입력·출력(탑승 의사 · 일일 주소 → 확정 → 매니저 배포) |
| W4 | `b1-` | 탑승 상태 전이도(waiting · boarded · alighted · absent · no_show + 되돌리기 + 미승차 에스컬레이션 시계) |
| W5 | `w1-` `w2-` | ①WebSocket 연결·구독·거부·재연결 시퀀스(CONNECT → SUBSCRIBE → 4403 ERROR / heartbeat / 끊김 → 재연결 → REST 전량 동기화) ②알림 발송 경로(도메인 이벤트 → outbox → 푸시 / 인앱 목록 / WS 방송의 대상 차이) |

## 7. 보고 (메시지로, 4항만 · 이 순서)

1. **판단 근거** — 고른 길과 버린 길, 이유 (특히 정본 안에서 어긋난 곳을 어떻게 적었는지)
2. **우려 · 확신 없는 지점 · 지시와 다르게 판단한 것** — 반드시 적는다. "목록에 없어서 범위 밖으로 봤다" 는 **표 누락 신고**로 적어라
3. **실측 3줄** — 산출물 파일 경로 · `details.ep` 블록 수 · 구현 상태 배지 집계(구현/미구현/불일치/조정 중)
4. **정본에서 발견한 낡은 값·모순 목록** (있으면) — 파일:줄

"무엇을 했는가" 서술은 쓰지 마라. 확인하지 못한 항목은 "미확인" 으로 남기고 통과로 적지 마라.

## 8. 어휘

문서 본문은 **개조식 체언 종결**(`~였음`·`~습니다` 금지, `발생 · 부재 · 반영` 으로 끝냄). 증상·대상이 특정되지 않는 말(흔들리다 · 깨지다 · 상류 · 훨씬) 금지. 외래어 전문용어는 우리말이 있으면 우리말(스텁 → 가짜 응답 객체). `refresh 토큰`·`커밋`·`트랜잭션` 처럼 바꾸면 뜻이 흐려지는 것은 그대로.
