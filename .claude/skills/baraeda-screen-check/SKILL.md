---
name: baraeda-screen-check
description: 관계자 웹(Next.js)을 실제 브라우저로 열어 화면을 확인한다. 지도·마커·명단처럼 "검사는 초록인데 화면이 틀린" 것을 판정할 때 쓴다. 로그인 절차, 지도 마커 읽는 법, 이 저장소에서 실제로 걸린 함정 4가지를 담는다.
allowed-tools: Bash(playwright-cli:*), Bash(docker:*), Bash(curl:*)
---

# 바래다 관계자 웹 — 화면 확인

## 언제 쓰나

**출처가 눈인 결함은 판정도 눈으로 한다.** 이 저장소에서 단위 검사를 전부 통과한 채
화면에서 틀린 경우가 **9회** 있었다(2026-09-19~20).

| 실제로 난 것 | 단위 검사 |
|---|---|
| 마커는 만들었는데 카메라가 안 움직여 "도착"이 지도 밖 | 통과 |
| 승하차지 선택 강조가 DOM 에 안 닿음 | 통과 |
| 마커가 제 좌표에서 38px 밀림 | 통과 |
| SDK 마커 클릭 이벤트가 안 불림 | 통과 |

지도·마커·좌표·색·레이아웃을 건드렸으면 **반드시 이 스킬로 눈 확인까지 한다.**

절차 자체(스모크 → 상호작용 → 시각 회귀 → 접근성)는 ECC `browser-qa` 스킬을 따른다.
여기에는 **이 저장소에서만 필요한 것**만 적는다 — 복제하지 않는다.

## 먼저 — 서버가 떠 있어야 한다

```bash
export PATH="/Applications/Code/Docker.app/Contents/Resources/bin:$PATH"
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/login   # 200 이어야 한다
```

⚠ **포트는 반드시 3000.** 네이버 지도 키의 서비스 URL 과 백엔드 CORS 허용 목록이 둘 다
`http://localhost:3000` 이라, 다른 포트로 열면 지도 SDK 의 `/v3/auth` 가 401 로 거절되고
화면에는 *"지도를 불러오지 못했습니다"* 만 뜬다.

데모 버스는 기동 **1분 뒤**부터 움직인다(확정 폴링 30초 + 시뮬레이터 시작 지연 15초).

## 로그인

비밀번호는 전부 `password`.

| 볼 화면 | 계정 |
|---|---|
| 운행 관리 `/dashboard` · 금일 운행 상세 `/today-run` | `staffA` |
| 전체 관제 `/monitoring` | `sysadmin` |

```bash
playwright-cli open http://localhost:3000/login
playwright-cli fill "아이디 입력칸" staffA
playwright-cli fill "비밀번호 입력칸" password
playwright-cli press Enter
playwright-cli goto http://localhost:3000/dashboard
```

⚠ **아이디 칸에는 `type` 속성이 없다.** JS 로는 `input.type === 'text'` 로 보이지만
CSS `input[type=text]` 는 **매칭되지 않는다.** 선택자로 집지 말고 순서로 집는다.

⚠ 인증 부트스트랩이 끝난 뒤 입력한다 — 그리는 도중에 넣으면 재렌더로 입력값이 사라진다.

## 지도에서 무엇을 어떻게 읽나

마커는 전부 `data-marker-id` 를 달고 있다. 이것이 마커를 집는 유일한 열쇠다.

| 마커 | id 형태 | 생김새 |
|---|---|---|
| 버스 | 회차 id (`"3"`, `"101"`) | 알약 칩 — 버스 아이콘 + 번호. 버스마다 색이 다르다 |
| 승하차지 | `stop-{stopId}` | 작은 초록 원 |
| 출발지·도착지 | `origin-{runId}` · `destination-{runId}` | 어두운 칩 — 글자 "출발"·"도착" |

```bash
# 지금 지도에 있는 마커 전부
playwright-cli eval "[...document.querySelectorAll('[data-marker-id]')].map(e => e.getAttribute('data-marker-id'))"

# 버스 칩의 글자와 색
playwright-cli eval "[...document.querySelectorAll('[data-marker-id]')].filter(e=>/호차/.test(e.innerText)).map(e=>({번호:e.innerText.trim(), 색:getComputedStyle(e).backgroundColor}))"

# 축척 바 (지도 확대 수준 판정)
playwright-cli eval "[...document.querySelectorAll('div,span')].filter(e=>e.children.length===0&&/^\\d+\\s*(m|km)$/.test(e.textContent.trim())).pop().textContent"
```

## ⚠ 이 저장소에서 실제로 걸린 함정 5가지

각각 한 회차씩 버렸다. 그대로 따라 하면 다시 걸린다.

**① 물리 클릭이 목록 항목에 안 먹는다.**
버스 목록의 `button[aria-pressed]` 을 좌표로 누르면 자식 `div` 가 클릭을 받아 버튼까지 안 올라간다.
→ JS 로 누른다: `playwright-cli eval "document.querySelectorAll('button[aria-pressed]')[4].click()"`

**② 목록은 7초마다 다시 그려진다**(실시간 폴링).
요소 손잡이를 미리 잡아 두면 낡는다 — **누르기 직전에 매번 새로 집는다.**

**③ 마커는 계속 움직인다.**
좌표를 재고 그 자리를 누르는 사이에 버스가 비켜간다. 좌표로 누르지 말고
`data-marker-id` 로 요소를 집어 `dispatchEvent(new MouseEvent('click', {bubbles:true}))` 를 보낸다.

**④ 색은 `style` 속성 문자열로 못 읽는다** — 브라우저가 정규화한다.
반드시 `getComputedStyle(el).backgroundColor` 로 읽는다.

**⑤ ⚠ iOS 시뮬레이터를 화면 좌표로 누르지 마라(osascript · `cliclick` · Computer Use 좌표 클릭) — 사용자 창이 눌린다.**
2026-09-30 하루에 **두 번**(조사 창 B2 · 작업 창 R44) 좌표 클릭이 시뮬레이터가 아니라 **사용자가 전체 화면으로 보던 Arc 브라우저 영상**에 들어갔다. Simulator 창이 다른 스페이스·화면에 있으면 앞으로 오지 않고, 좌표는 그 자리에 있는 남의 창을 누른다. **되돌릴 수 없고 사용자 작업을 건드린다.**
→ 앱 화면은 **조작 없이 띄우기**로만 찍는다: 보고 싶은 화면으로 곧장 가는 임시 `--dart-define`(시작 경로·계정)이나 딥링크(`xcrun simctl openurl <UDID> <앱 스킴>://…`)로 열고 `xcrun simctl io <UDID> screenshot <경로>`. 탭·끌기 같은 **조작의 검증은 위젯 시험**으로 한다. 좌표 클릭이 꼭 필요하면 먼저 사용자에게 묻는다.

## 판정할 때 남길 것

고쳤다고 말하기 전에 **화면에서 본 수치를 한 줄** 남긴다. "마커가 보인다" 가 아니라
"중앙에서 벗어난 거리 dx=6 dy=2", "축척 바 100m", "칩 4개: 2호차·3호차·4호차·5호차" 처럼.
이 저장소의 실패 9건은 전부 "코드상 맞는데 화면이 틀림" 이었고, 수치를 재지 않으면 구별이 안 된다.
