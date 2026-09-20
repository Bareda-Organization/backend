# SDD ledger — plan: docs/superpowers/plans/2026-08-10-mvp-배포-준비.md

Branch: feat/mvp-deployment (base 41be82a)

## ✅ 환경 해소 (2026-08-11) — Docker 기동 완료

사용자가 Docker Desktop 실행 + "도커 알아서 조작해줘" 권한 부여. 컨트롤러가 실행:
`docker stop db redis && docker compose up -d postgres redis kafka`
- school-bus-postgres-1 (healthy, 5432) · school-bus-redis-1 (6379) · school-bus-kafka-1 (29092)
- 다른 프로젝트의 `kafka`(9092/9093)·`zookeeper`(2181)는 **포트 무충돌이라 건드리지 않음.**
- 🔁 **원복 필요:** 작업 종료 시 `docker start db redis` (사용자의 다른 프로젝트 컨테이너).

Task 1: 착수 (BASE 24db9c4). ⚠️ task10-fixer 가 동시 작업 중이라 커밋이 교차한다 —
  범위 산출 시 각 태스크의 **자기 커밋 SHA** 로 계산할 것(단순 BASE..HEAD 금지).
Task 1: NEEDS_CONTEXT 에스컬레이션 — **계획서의 테스트 import 가 Boot 3 경로라 컴파일 실패.**
  컨트롤러 독립 확인:
   - `spring-boot-webmvc-test-4.1.0.jar` → `org/springframework/boot/webmvc/test/autoconfigure/AutoConfigureMockMvc.class` 존재
   - `spring-boot-test-autoconfigure-4.1.0.jar` → `web/servlet` 경로 항목 **0개**, 해당 클래스 부재
  → 계획이 틀렸고 코드가 맞다. Boot 4 모듈화(이 프로젝트가 `spring-boot-starter-webmvc` 신형
    아티팩트를 쓰는 것과 같은 맥락)의 패키지 재배치. 코드베이스에 MockMvc 선례가 없어 미발견 상태였음.
  **승인 후 지시:** ①한 줄씩 고치며 왕복하지 말고 그 파일 import 전부를 jar 기준으로 선검증
    ②검증 로직 3케이스는 불변 ③계획 원문 Task 1 Step 1 동반 수정(Task 7·8·10 선례)
    ④커밋 경로에 계획 md 추가(총 5개), `git add -A` 는 여전히 금지.
Task 1: 구현 완료 (880fe32, parent 8e07fb8) — DONE_WITH_CONCERNS. 리뷰 진행 중.
  - 정정 후 진짜 RED: `/actuator/health` **500**(NoResourceFoundException, 매핑 부재). 404 아님.
  - `/actuator/env` → **401**(시큐리티가 Dispatcher 도달 전 차단). 브리프 테스트는 isIn(401,404) 허용.
  - 이미지 `curl 8.18.0` 확인. 최초 확인 시도는 **앱 ENTRYPOINT 오버라이드 때문에 오탐**이었고
    `--entrypoint curl` 로 재확인 — 검증 방법 자체의 함정을 구현자가 스스로 잡아냄.
  - 구현자 우려(범위 밖 기록): 매핑 부재 시 기존 `GlobalExceptionHandler` 가 404 대신 500 반환.
  - 커밋 파일 5개만 정확히 포함(다른 에이전트 변경 미혼입) — 컨트롤러가 `git show --name-only` 로 확인.

Task 1: 리뷰 도착 — 스펙 ✅, 품질 **Approved**. Important 1건(plan-mandated)은 사용자 판단 대기.
  리뷰어 실측(강점): `management:` 블록이 첫 `---` 앞이라 local·prod 모두 적용 / 주석의
  "nginx 가 /actuator 를 404 로 막는다"가 `nginx.prod.conf:141-145` 의 `location /actuator { return 404; }`
  와 실제 일치 / Dockerfile 의 curl 설치가 `COPY --from=build` 앞·빌드 스테이지 밖이라 캐시 영향 없음,
  `eclipse-temurin:25-jre` 가 Debian 계열이라 `apt-get` 유효(Alpine 아님) / SecurityConfig 불변 확인.
  RED 원인 규명: `GlobalExceptionHandler` 의 `@ExceptionHandler(Exception.class)` catch-all 이
  `ExceptionHandlerExceptionResolver` 를 통해 `ResponseStatusExceptionResolver`(404 매핑 담당)보다
  **먼저** 실행되어 `NoResourceFoundException` 이 500 으로 바뀜 — 구현자 설명이 코드와 일치.
  - **finding (Important, plan-mandated) — 사용자 판단 대기:**
    `otherEndpointsAreNotReadable` 이 exposure 설정을 **실질적으로 검증하지 못한다.**
    `management.server.port` 미분리라 actuator 가 메인 DispatcherServlet 에 올라가고,
    SecurityConfig 가 `/actuator/health` 외 전부를 `anyRequest().authenticated()` 로 막는다.
    → `include` 에 `env` 가 실수로 포함되든 아니든 Security 가 먼저 401 을 준다. 누가 `include: '*'`
    로 바꿔도 테스트는 계속 그린. 즉 이중 방어(Security + exposure) 중 **exposure 쪽이 무테스트**.
    브리프가 `isIn(401, 404)` 를 명시했으므로 plan-mandated.
    컨트롤러 확인: `spring-security-test` 가 `build.gradle:63` 에 이미 존재 → `@WithMockUser` 로
    Security 를 통과시킨 뒤 404 를 확인하는 테스트 1개 추가가 저렴한 해소책.
Task 1: minor (deferred): 세 테스트 모두 `@SpringBootTest` 풀 컨텍스트 — 목적 대비 무거움(브리프 지정).
Task 1: minor (deferred, out-of-scope): `GlobalExceptionHandler` 가 미매핑 요청을 404 대신 500 으로
  바꾸는 패턴이 앱 전역에 잠재. 이번 태스크가 만든 문제 아니고 actuator 매핑 후엔 미도달.

Task 2: 구현 완료 (5169afc, parent eb6c088) — DONE. 리뷰 진행 중.
  검증: compose down/up 후 BackendApplicationTests BUILD SUCCESSFUL / bootRun 으로
  admin@school.com·platform@school.com 둘 다 accessToken 반환 / **DB 조회로 해시가 원래 리터럴과
  일치함을 확인(치환 실증)** — 마이그레이션만 통과하고 해시가 깨지는 경우를 배제. bootRun 종료, 8080 해제.
  1차 테스트 실패는 postgres 재기동 타이밍 = **환경 문제**로 분류(코드 결함 아님), 재실행으로 해소.
  인프라 3개 컨테이너 기동 상태로 인계.
Task 2: 리뷰 도착 — 스펙 ✅, 품질 **Approved** (Critical·Important 0건).
  리뷰어 독립 확인: placeholder 이름이 시드(`V2:17`)와 yml 에서 일치 / local 기본 해시가 제거된
  리터럴과 **바이트 단위 동일**(로컬 로그인 무회귀) / `placeholders` 가 `locations` 와 **같은 local
  문서 안**에 위치 / `prod` 문서는 무변경이라 `db/migration-local` 미스캔·placeholder 미요구 →
  배포 기동 실패 위험 부재 / 시드 파일 전체 grep 으로 모든 `app_user` insert 가 같은 `v_hash` 재사용,
  **다른 곳에 남은 하드코딩 해시 없음** 확인.
  보안 목적 판정: 이번 커밋만으로는 "누구나 password 로그인" 구멍이 닫히지 않음 — **Task 3 의존은
  설계이지 갭이 아님**(브리프 제목이 "B1 준비"). 새로 열린 조용한 실패 경로도 없음: `local` 문서 밖에는
  기본값이 없어 미해결 placeholder 는 Flyway 가 **기동 실패**시킴(fail-closed).
Task 2: minor (deferred): 시드 주석의 "local·demo 프로파일에서만"이 이 커밋 시점엔 아직 사실이 아님
  (demo 프로파일 부재). 브리프 지정 문구라 plan-mandated. **→ Task 3 리뷰어가 이 문장이 사실이 되는지
  재확인할 것**(Task 3 이 프로파일명을 다르게 짓거나 스캔 범위를 달리하면 주석이 낡는다).
Task 2: minor (deferred): 체크섬 파손 위험 경고가 SQL 파일 자체 주석에는 부재(브리프·보고서에만 존재).
Task 2: complete (commit 5169afc, review clean — 2 minor deferred)

Task 3: 구현 완료 (326e403, parent 5169afc) — DONE. 리뷰 진행 중.
  **구현자가 자기 검증의 약점을 스스로 발견함** — 1차 B1 증거는 DB 가 이미 시딩된 상태라 약했음.
  `docker compose down` 으로 스키마를 완전히 비우고 재기동해 Flyway 가 V1~V5 를 직접 적용하는 것부터
  재검증. (증거가 될 수 없는 검증을 스스로 폐기한 사례 — 기록해 둘 만함.)
  - B4: 로그 `The following 1 profile is active: "demo"` 확인
  - B1: 빈 스키마 → Flyway 적용 → `admin@school.com`/`password` 실제 로그인 accessToken 확인
  - B2: **브리프의 "tick 로그" 지시가 실물과 어긋남**(성공 tick 은 로그 미기록, 경고 로그만 존재)
    → `GET /api/locations/buses` 5초 간격 2회 호출로 좌표 이동(origin=MOCK) 확인해 대체 검증.
  - Step3 통과 / Step4 거부(음성 대조) 둘 다 실행
  - 되돌린 뒤 local `BackendApplicationTests` BUILD SUCCESSFUL
  ⚠️ 구현자 우려: "tick 로그" 표현이 코드와 불일치 — Task 5 가드나 운영 문서가 이를 인용하면 확인 필요.
Task 3: 리뷰 도착 — 스펙 ✅, 품질 **Approved** (Critical·Important 0건).
  B2 대조표(리뷰어 작성): mock false→true / gps true→false / bus-mock false→true / bus-gps true→false
  = **4/4 전부 반전**. 하나라도 같으면 데모 정지 또는 prod 에 가짜 좌표 혼입.
  그 외 확인: `seedPasswordHash: ${SEED_PASSWORD_HASH}` 기본값 없음 / compose 는 `:?` 로 필수화
  (리뷰어가 `docker compose config` 를 주고·빼고 **직접 재실행**해 재확인) / demo 의 flyway.locations 에
  `db/migration-local` 존재 → **Task 2 시드 주석이 이제 사실**(Task 2 가 넘긴 확인 항목 해소) /
  diff 가 전부 `+` 라인이라 local·prod·공통 `management:` 무변경 / 들여쓰기 깊이가 prod 와 동일해
  **Task 5 가드의 문자열 매칭과 호환** / prod 의 `app:` 키가 demo 에 1:1 대응, 누락 없음.
  구현자의 tick 로그 주장 검증: `LocationSimulationScheduler.tick()` 직접 확인 — 성공 tick 은 로그 미기록,
  `catch` 블록에서만 `log.warn`. 구현자 주장 사실. API 좌표이동 대체 증거는 "로그만큼 직접적이진 않으나 합당".
Task 3: minor (deferred): 공통 섹션의 `spring.profiles.active: local`(application.yml:5)은 이 diff 가
  손대지 않아 그대로. 실제 B4 해소 경로는 compose 의 `SPRING_PROFILES_ACTIVE:${...:-demo}`(41be82a 부터 존재)
  이고 이번 태스크가 demo 프로파일을 실체화해 그 기본값이 유효해진 것 — 계획대로(plan-mandated).
  **다만 compose 를 거치지 않고 jar 를 직접 구동하면 여전히 localhost 로 떨어진다.**
Task 3: complete (commit 326e403, review clean — 2 minor deferred)

Task 4: 구현 완료 (115b929, parent 326e403) — DONE. 리뷰 진행 중.
  import 선검증 결과: 브리프 경로가 `spring-websocket-7.0.8.jar` 에 **그대로 존재** → 수정 불필요,
  계획 원문 동반 수정도 불필요. (Task 1 선례를 경고했으나 이번엔 계획이 맞았음.)
  RED 는 3-인자 생성자 부재("actual and formal argument lists differ in length")로,
  **import 문제와 구분됨**을 명시 확인. GREEN 1건.
  전체 회귀 `./gradlew test` — 44개 클래스 **249 tests, 0 failures/errors**.
  `BackendApplicationTests` 로 `${WS_ALLOWED_ORIGIN_PATTERNS:*}` → `String[]` relaxed binding 실동작 확인
  (단위 테스트는 생성자 직접 호출이라 이 변환을 검증하지 못하므로 별도 확인을 지시했던 항목).
  `deploy.sh` 는 이미 해당 변수를 주입 중 — 실물 확인 후 수정 불필요 판정.

Task 4: 리뷰 도착 — 스펙 ✅, 품질 **Approved**. **Critical·Important·Minor 전부 0건.**
  리뷰어 독립 확인:
  - 생성자 파급: `grep -rln WebSocketConfig backend --include=*.java` → 3건뿐이고
    `SecurityConfig.java:41` 은 **javadoc 언급**일 뿐 생성·의존 없음. 직접 `new` 하는 코드 부재 →
    DI 경로 하나뿐이라 3-인자 전환 파급 없음.
  - 단위 테스트 검증력 갭이 실제로 메워졌는지: `BackendApplicationTests` 는 슬라이스가 아닌 순수
    `@SpringBootTest` 라 `WebSocketConfig` 빈이 실제 생성되고, local 엔 해당 환경변수가 없으므로
    `${WS_ALLOWED_ORIGIN_PATTERNS:*}` → `String[]{"*"}` 바인딩 경로를 정확히 태움 → 갭 해소 확인.
  - **3중 방어** 확인: demo 는 기본값 없음 / compose 는 `:?` / `deploy.sh:47,59` 의 `get_param` 은
    Task 8 fix 로 빈 값·조회 실패 시 하드 실패. → 운영에서 조용히 `*` 로 뜨는 경로 부재.
  - 빈 배열 퇴화: local 은 단일 원소 `["*"]`(기존 동작 동일), demo·prod 는 3중 가드로 빈 문자열 통과 불가.
  - `git diff` hunk 2개뿐 — local·prod·`management:` 텍스트가 diff 에 전혀 미등장 → **Task 5 가드 호환**.
  - YAML `---` 3개로 4구획 독립 문서 — demo·prod 에 동일 `app:` 루트 키가 있어도 병합 충돌 없음.
Task 4: complete (commit 115b929, review clean — 0 findings)

Task 5: 구현 완료 (0675fa9, parent 115b929) — DONE. 리뷰 진행 중.
  브리프 기대 문자열이 실물 `application.yml` 과 **완전 일치** → Task 2·3·4 설정 수정 불필요
  (컨트롤러가 "브리프가 틀렸을 수도 있으니 고치기 전에 판정하라"고 지시했던 항목 — 이번엔 브리프가 맞았음).
  **음성 대조를 3개 가드 각각에 개별 수행**(Step 3 지정 1개 + 나머지 2개). 매번 정확히 해당 가드만
  AssertionError·종료코드 1 로 실패하고 무관한 가드는 통과 유지 → 가드가 서로 독립적으로 작동함을 입증.
  매번 직후 `git checkout` 복구 + `git diff --stat` 빈 출력 확인.
  컨트롤러 확인: 커밋에 테스트 파일 1개만 포함, `git status` 클린 — **워킹트리 오염 없음**
  (`git checkout application.yml` 이 남의 미커밋 변경을 날릴 위험을 사전 경고했던 항목).

Task 5: 리뷰 도착 — 스펙 ✅, 품질 **Approved**. Important 1건은 **범위 밖 커버리지 갭**(plan-mandated-absent).
  리뷰어 독립 확인: 테스트 3개 직접 재실행(`failures="0" errors="0"`) / 실물 application.yml 을 열어
  테스트가 검사하는 문자열·들여쓰기를 손으로 대조 — 불일치 없음.
  - `doesNotContain("...${SEED_PASSWORD_HASH:")` 가 **파일 전역** 검사라, 중복 키·다른 프로파일·뒤에
    덧붙인 줄에 숨긴 약한 기본값도 잡힌다. `${VAR:-x}` 셸식 트릭도 Spring 은 첫 `:` 뒤를 전부 기본값으로
    보므로 여전히 걸림 — 우회 경로를 손으로 추적했으나 발견 못 함.
  - `sectionOf()` 의 "첫 매칭 반환"이 현재 안전한 근거: `grep -n on-profile` 로 각 마커가 **정확히 1회**만
    등장함을 확인(가정이 아니라 실측).
  - cwd 상대 경로는 신규 위험 아님 — `ControllerAuthorizationConventionTest:28` 이 이미 같은 방식이고,
    `settings.gradle` 이 `backend/` 에 있어 Gradle 기본 workingDir 이 구조적으로 맞음.
Task 5: minor (deferred): `sectionOf()` 가 매칭 유일성을 검사하지 않음 — 훗날 주석에 `on-profile: prod` 가
  등장하면 **조용히 엉뚱한 섹션**을 반환. 침묵 회귀를 잡는 게 목적인 파일에 침묵 사각이 하나 있는 셈.
Task 5: complete (commit 0675fa9, review clean — 1 minor deferred, Important 1건은 아래 후속 판단으로 이관)

## 🟠 후속 판단 대기 — 같은 성격의 커버리지 갭 2건 (사용자 결정 필요)

둘 다 "지금 동작은 안전하나 **감시망이 없다**"는 형태이고, 둘 다 brief 가 3개만 지정한 결과(plan-mandated-absent).
Task 5 리뷰어가 **하나의 후속 태스크로 묶어 처리할 것을 권고**함.

1. **[Task 1 발] `/actuator/env` 테스트가 exposure 설정을 검증하지 못함.**
   Security 가 먼저 401 을 주므로 `include: '*'` 로 바뀌어도 테스트는 계속 그린.
   해소책: `spring-security-test`(build.gradle:63 에 이미 존재)의 `@WithMockUser` 로 Security 를 통과시킨 뒤
   404 를 확인하는 테스트 1개 추가.
2. **[Task 5 발] prod 프로파일이 `app.ws.allowed-origin-patterns` 를 오버라이드하지 않음.**
   컨트롤러 실측(`grep -n` on application.yml): base 75행 `${WS_ALLOWED_ORIGIN_PATTERNS:*}` /
   demo 185행 기본값 없음 / **prod 섹션(121~)에는 해당 키 자체가 부재** → prod 는 base 의 `*` 를 상속.
   현재 배포 경로에서는 안 터짐(compose 가 `:?` 로 항상 주입, `SPRING_PROFILES_ACTIVE` 기본값이 `demo`).
   compose 밖에서 prod 로 띄우면 **모든 출처 허용**으로 뜬다.
   해소책: prod 섹션에 `allowed-origin-patterns: ${WS_ALLOWED_ORIGIN_PATTERNS}` 추가 + 가드 1줄.

Task 13: 착수 (BASE 115b929). 문서 3종만 수정하므로 Task 5 와 **병렬** 진행.
Task 13: 구현 완료 (378c0a2, parent 0675fa9) — DONE_WITH_CONCERNS. 리뷰 진행 중.
  **범위 밖 드리프트 보고**(고치지 않고 보고만 — 컨트롤러 판단 필요):
   - `docs/API_SPEC.md:1994,1998` · `docs/USER_FLOWS.md:56` · `docs/ARCHITECTURE.md:760,771,775,835`
     — 전부 "local 프로파일에서만"·"전부 password" 계열로 **demo 누락**.
   - `docs/plans/2026-08-02-...DOC-문서갱신.md:319` (이력용) · html 렌더 3곳(원본 미수정이라 당연).
  ⚠️ CLAUDE.md 는 **`docs/` 가 사실의 기준(SoT)**이라고 규정한다 — SoT 4종이 거짓이면 이 규정이 무너진다.
Task 13: 리뷰 도착 — 스펙 ✅, 품질 **Approved** (Critical·Important 0건).
  리뷰어 독립 확인: 새 서술을 실물 `application.yml` 과 대조 — local·demo 둘 다 `flyway.locations` 에
  `db/migration-local` 포함, prod 는 오버라이드 자체가 없어 base 만 사용, local 만 리터럴 해시 기본값 보유.
  **문서 주장과 정확히 일치 — 새 거짓을 만들지 않음.**
  구현자 grep 패턴에 의존하지 않고 대상 3개 파일을 **전문 재독**해 추가 드리프트 탐색 → 없음.
  README 링크 2개 디스크 존재 확인. HTML 미접촉, 3개 파일만 커밋.
  구현자의 범위 밖 드리프트 보고를 **3곳 표본 검증** — 전부 보고대로. 과장·누락 없음.
Task 13: complete (commit 378c0a2, review clean — 0 findings)

## ✅ 13개 태스크 전부 완료 — 최종 전체 리뷰 단계

최종 리뷰 범위: **49c0dd9..HEAD** (19 commits, 24 files, +3545).

## 🔴 최종 리뷰 결과 — Critical 2건 (둘 다 컨트롤러가 독립 재현/확인)

**C1. `.env` 값 미인용 → Compose 가 bcrypt 해시를 잘라 먹는다. B1 재발, 그런데 배포는 "성공" 보고.**
compose dotenv 는 인용되지 않은 값의 `$VAR` 를 확장한다. bcrypt 는 `$2y$10$...` 로 `$` 가 3개.
컨트롤러 실측:
```
UNQUOTED → H: $2y$10                                (해시 소실 + "variable not set" 경고)
QUOTED   → H: $2y$10$N9qo8uLOickgx2ZMRZoMyeIjZAg... (보존, 경고 0건)
```
조용한 실패 경로: 컨테이너 기동 성공 → Flyway 시드 정상 적용(SQL 오류 없음) → health UP →
SSM Success → 워크플로 "배포 성공". 그런데 `BCryptPasswordEncoder.matches()` 가 항상 false →
**데모 계정 전부 로그인 불가.** 유일한 단서는 stderr 경고인데 종료코드에 영향 없음.
**왜 태스크 리뷰를 통과했나:** Task 3 은 `bootRun` 으로 검증(`.env` 경로 미경유), Task 8·10 은 `.env` 의
**존재**만 검증. `.env` 를 **쓰는 쪽과 읽는 쪽의 인용 규약**이 어느 태스크의 범위도 아니었다.
→ 이것이 "태스크 경계" 리뷰가 잡아야 할 전형. plan-mandated(계획 Task 8 Step 1 이 이 형태).

**C2. awslogs 드라이버가 요구하는 CloudWatch Logs 권한이 인스턴스 역할에 부재 → 모든 컨테이너 기동 실패.**
컨트롤러 확인: `docker-compose.prod.yml:21-26` 이 6개 서비스 전부에 `driver: awslogs` +
`awslogs-create-group: "true"`. `DEPLOYMENT.md` §2.3 의 역할은 관리형 2개(SSM·ECR read) + 인라인 4개
(SSM 파라미터·kms:Decrypt·배포버킷 read·백업버킷 rw) — **`logs:*` 가 하나도 없음.**
Docker 는 로깅 드라이버를 컨테이너 프로세스 **시작 전에** 초기화하므로 `up -d` 가 postgres 부터 실패.

## 최종 리뷰 Important 6건 + Minor 9건 — fix 웨이브 1회로 처리 (final-fixer, BASE 378c0a2)

머지 전 필수: C1 · C2. 강력 권고: I1(AL2023 cron 부재) · I6(NAVER 키 필수 등록 안내) ·
I3(init-cert 의 틀린 "다음 단계") · 후속판단-2(prod WS 출처 미오버라이드).
함께 처리: I2(`.htpasswd` 안내 모순) · I4(MAX_WAIT 600→1200) · I5(실패 시 진단 로그 미도달) ·
I7(§7 sudo 누락) · M1~M4(문서 사실 정정).
후속으로 남김: M5~M9 + 원장 deferred + `/actuator/env` exposure 테스트.

## fix 웨이브 완료 (e4ccd10 · 8ee9ead · cc55dd3) — 14건 전부 수정. scoped re-review 진행 중

- C1: `write_env` 헬퍼로 전 항목 작은따옴표 인용 + `get_param` 작은따옴표 거부 가드 +
  `.env` 작성 직후 `compose config` 되읽기 검증(1-1 단계) + 계획 Task 8 블록 동기화.
  음성 대조: 수정 전 `$$2y$$10`(잘림) / 수정 후 전문 보존. 새 가드의 실패·통과 케이스 둘 다 실행.
- C2: §2.3 에 `CloudWatchLogsWrite`(`logs:CreateLogGroup/CreateLogStream/PutLogEvents/DescribeLogStreams`,
  `log-group:/school-bus/demo*`) + 생략 불가 사유 문단.
- I1~I7·후속2·M1~M4 전부 처리. 후속2 는 음성 대조 2가지(prod `ws:` 제거 / 기본값 `:*` 부여) 모두 FAILED 확인,
  `git checkout` 대신 사본 복구로 남의 변경 보호.
- 검증: `bash -n` 4개 OK / YAML 파싱은 pyyaml 부재로 `ruby -ryaml` 사용 /
  `./gradlew test --rerun-tasks` **253 tests, 0 fail** / DEPLOYMENT.md 자리표시자·어투 위반 0건 /
  계획 원문 ↔ 실물 Task 6·7·8·9·10·11 전부 일치.
- 테스트 수 정정: 지시서의 "249 기준"은 낡은 값. Task 4 시점 249 → Task 5 가 3개 추가 = 252 →
  이번 1개 추가 = **253**. 구현자 지적이 맞고 컨트롤러 수치가 틀렸음.

## ✅ scoped re-review 완료 — 14건 전부 ADDRESSED, 신규 Critical/Important 파손 없음

재검증자가 **직접 실행해 확인**한 것(추론과 구분해 보고함):
- C1: 스크래치에 실물 compose 사본 + 인용/미인용 `.env` 2종으로 재현 — 미인용 `$$2y$$10`(6자) /
  인용 60자 보존. `write_env` 가 **11개 항목 전부**에 적용, 옛 `printf 'VAR=%s\n'` 잔존 0.
- **1-1 가드가 무동작이 아님을 증명**: 가드 파이프라인을 떼어 두 방향 실행 → `len=6` 중단 / `len=60` 통과.
  compose 버전 의존성도 안전 — 이스케이프를 안 하는 버전이면 sed 가 무동작이 되지만 값은 여전히
  60자라 통과하고, 잘린 경우엔 어느 쪽이든 6자라 실패.
- 작은따옴표 거부 가드가 정상 값을 막지 않음: `openssl rand -base64 24/48` 산출물(`+`·`/`·`=`)·
  bcrypt·콤마 목록·`#` 포함 값 등 8개를 라운드트립 비교해 **바이트 일치**.
- I5 가 실제 실행됨: `if ! $COMPOSE up -d; then` 형태라 `if` 조건절은 errexit 대상 밖 → 본문 진입 →
  로그 stderr 덤프 → `exit 1`. `|| true` 는 logs 에만 붙어 종료코드를 가리지 않음.
- 후속2 가드 음성 대조를 python 으로 **독립 재현**(prod `ws:` 제거 / 기본값 `:*` 부여 → 둘 다 불성립).
  `sectionOf()` 한계도 통과 — 새 주석이 `${WS_ALLOWED_ORIGIN_PATTERNS:*}` 를 품지만 앞에
  `allowed-origin-patterns: ` 가 없어 `doesNotContain` 오발 없음.
- M2: 문서의 `§` 참조 **20곳을 전수 대조** — 어긋난 것 0.
- 계획 원문 ↔ 실물: Task 8(129줄) 완전 일치, Task 6·7·9 도 일치.
- 저장소 전수 스캔으로 낡은 문자열(`MAX_WAIT_SECONDS=600`·`9f2c1ab`·`NCP 키 3개`·
  `별도 태스크로 진행 중`) 잔존 **0건** 확인.

신규 minor 2건 (비차단):
- `DEPLOYMENT.md` §3·§4 가 "시크릿 값에 작은따옴표 금지"를 안내하지 않음 — 이제 `get_param` 이
  하드 실패시키므로 수동으로 비밀번호를 정하는 운영자가 사전 경고 없이 1단계 중단을 만날 수 있음.
  실패는 시끄럽고 메시지도 정확해 배포 사고로는 미연결.
- 1-1 가드가 `SEED_PASSWORD_HASH` 하나만 검사(다른 값은 깨지면 시끄럽게 실패하므로 의도적 범위).

Out-of-scope: `init-cert.sh:28` 의 `--env-file` 부재(M6, 이미 deferred) — **최초 발급 경로에서는
`if` 조건이 거짓이라 미실행**, 재발급 시에만 문제이고 실패가 시끄러움. 비차단.

**Verdict: Ready to merge.** 머지 블로커 없음.

## 🟡 fixer 가 새로 발견 (미수정, 범위 밖) — 컨트롤러 확인 완료

**`DEPLOYMENT.md` 가 §2.9 인증서 최초 발급을 §2.10 도메인 A 레코드 연결보다 먼저 배치.**
컨트롤러가 절 순서 실측: `318:### 2.9 인증서 최초 발급` → `329:### 2.10 도메인 연결`.
`certonly --standalone` 의 HTTP-01 챌린지는 **도메인이 EIP 를 가리켜야** 통과하므로, 문서 순서대로
따라가면 발급이 실패한다. §2.1 순서 요약도 동일. **최초 배포를 막는 순서 결함** — 사용자 판단 필요.

**후속판단-1 (actuator exposure 무테스트) 판정 = 머지 후 후속.** 최종 리뷰어 근거: 실제 노출은 3중으로
막혀 있음((a) `include: health` 로 매핑 부재 (b) SecurityConfig `anyRequest().authenticated()`
(c) nginx `return 404`). 지금 위험은 0 이고 감시망만 부재. **단 우선순위는 높게** — 누가 디버깅하려
`include: '*'` 로 바꾸고 되돌리는 걸 잊으면 인증된 아무 사용자에게나 `/actuator/env` 가 열린다.
브랜치 전체(f3436bf..HEAD = 91 commits/250 files)가 아니라 **이 계획의 범위만** 잡음 —
앞부분은 R1 인가 리팩터링 등 이전 계획의 작업이라 이미 별도로 리뷰됨.

⚠️ **application.yml 은 Task 1·2·3·4 가 모두 건드린다 — 이 넷은 반드시 순차 진행.**
  Task 2 는 추가로 `docker compose down/up` 으로 DB 를 리셋하므로 다른 에이전트의 테스트와 겹치면 안 된다.

## 환경 제약 (아래는 2026-08-10 기준 이력 — 위에서 해소됨)
- 로컬 Docker 에 **다른 프로젝트 컨테이너**(postgres:15 `db`, redis `redis`, cp-kafka+zookeeper)가
  5432·6379 를 점유 중. School-Bus compose 기동 불가.
- 영향: Task 1·2·3 (@SpringBootTest·bootRun 검증 필요) 착수 불가.
- 미영향: Task 4 의 단위테스트(Mockito), Task 5~13.
- 사용자 판단 대기 중 — Docker 없는 태스크부터 진행.

## ▶ 세션 재개 (2026-08-11)

사용자 지시: "Task 1부터 순서대로, 에이전트 팀 이용".
- 실행 순서: Task 8 fix → Task 10 리뷰 → Task 1 → 2 → 3 → 4 → 5 → 11 → 12 → 13 → 최종 리뷰.
  (Task 1~3 은 Docker 필요 — 세션 시작 시 데몬 자체가 꺼져 있어 사용자에게 기동 요청함.
   대기 중 Docker 무관 항목인 Task 8 fix·Task 10 리뷰를 먼저 착수.)
- **컨트롤러 관찰(Task 8 finding 재평가):** Task 3 Step 2 가 `SEED_PASSWORD_HASH` 를,
  Task 4 Step 6 이 `WS_ALLOWED_ORIGIN_PATTERNS` 를 compose 에 `${VAR:?}` 로 추가한다.
  즉 Task 3·4 가 끝나면 이전 세션이 "걸러지지 않는다"고 지목한 두 변수도 compose 단에서 걸린다.
  finding 은 여전히 유효하나(실패 지점이 실패 시점에 드러나야 함) 심각도는 완화 — NAVER_*·
  ROUTING_PROVIDER 는 여전히 compose required 가 아니다.

## ⏸ 세션 종료 (2026-08-10, 사용자 요청)

재개 시 이 순서로:
1. Task 8 리뷰 도착함 — 스펙 ✅, 품질 Not approved. **미해결 finding 1건, fix round 미착수.**
   - **finding (Important, 컨트롤러가 재현 검증 완료):** `deploy.sh` 의 `get_param` 실패가
     조용히 삼켜진다. `set -e` 는 **인자 자리의 명령 치환 실패를 잡지 않는다.**
     재현: `bash -c 'set -euo pipefail; f(){ return 3; }; printf "x=%s\n" "$(f)"; echo REACHED'`
     → `x=` 출력 + `REACHED` 출력 + 종료코드 0. 즉 SSM 권한 오류·파라미터 오타 시
     빈 값을 .env 에 쓴 채 진행하고, 3분 스모크 대기 뒤 "unhealthy" 로만 드러난다.
   - 완화되는 4개: compose 가 `${VAR:?}` 로 필수 선언한 ECR_REGISTRY·DB_PASSWORD·
     CORS_ALLOWED_ORIGINS·JWT_SECRET (`:?` 는 빈 문자열도 미설정으로 취급).
     **걸러지지 않는 것: SEED_PASSWORD_HASH · WS_ALLOWED_ORIGIN_PATTERNS** — 여기가 위험.
   - 수정 방향(제안): `get_param` 안에서 조회 실패나 빈 값이면 즉시 `exit 1` 하도록 가드 추가.
     `deploy.sh` 뿐 아니라 **계획 원문(Task 8 Step 1)도 함께 고쳐야 한다** — 계획이 원인 제공.
2. Task 10(4084054) 리뷰 미실시 → `review-package PLAN d1a8d24 4084054` 후 리뷰
   - 구현자 보고: DONE, 우려사항 없음. 브리프 YAML 그대로 이관, 필수 주석 4개 보존,
     `permissions.id-token: write` 존재, `deploy.sh $TAG` 계약 일치 확인. YAML 파싱 통과.
   - 리뷰 시 중점: SSM commands 배열의 따옴표 구조(의도적 변수 확장), 시크릿 이름,
     루트 docker-compose.yml 로 띄우는 테스트 인프라 포트가 local 프로파일 기본값과 맞는지
3. Task 11(웹 CI) · Task 12(운영 절차서) 미착수 — 브리프는 이미 추출돼 있음
4. Task 1~5, 13 은 Docker 필요: `docker stop db redis && docker compose up -d postgres redis kafka`
   (컨트롤러 권한으로 차단됨 — 사용자가 직접 실행해야 함. 끝나면 `docker start db redis` 로 원복)

⚠️ 다른 프로젝트 컨테이너(db·kafka·redis·zookeeper)는 **건드리지 않은 채로 종료**. 원복할 것 없음.

## 진행

Task 6: complete (commits 41be82a..e569dba, review clean)
Task 6: minor (deferred): init-cert.sh 의 `docker compose stop proxy` 가 --env-file 없이 실행 — 동작은 하나 실패 시 원인 파악이 헷갈림
Task 6: minor (deferred): `grep -q 'proxy'` 가 부분 문자열 매치 — 이름에 proxy 포함된 다른 컨테이너도 잡힘
Task 6: minor (deferred): proxy 를 내린 뒤 certbot 이 실패하면 proxy 가 내려간 채로 남고 복구 안내가 없음 (운영상 날카로운 지점 — 최종 리뷰에서 트리아지)

Task 7: 스펙 ✅ (브리프와 바이트 단위 동일), 품질 Not approved → fix round 1 진행
Task 7: 리뷰어 Critical 주장 **반증됨** — compose 다운로드 URL 대문자 `Linux` 가 404 라는 판정이었으나,
  두 URL 모두 200 + ELF 바이너리 반환 확인(GitHub 릴리스 경로가 대소문자 무시). 리뷰어는 자산 목록만
  보고 추론했고 URL 을 호출하지 않았음. → Critical 아님. 단 문서화되지 않은 동작에 의존하지 않도록
  소문자 고정으로 격하 반영(계획 원문도 함께 수정).
Task 7: minor (deferred): 스왑 멱등성 가드가 /swapfile 존재만 확인 — swapon·fstab 반영 여부는 미확인
Task 7: minor (deferred): compose 플러그인 재설치 가드 부재 (재실행 시 무조건 덮어씀)
Task 7: minor (deferred): error trap 부재 — 실패 시 원시 stderr 만 보이고 복구 안내 없음
Task 7: minor (deferred): 다운로드한 compose 바이너리 체크섬 미검증 (.sha256 자산이 존재함)
Task 7: fix round 1/5 (1 addressed, 0 open; commits 437d9bc..0d6d82a)
Task 7: complete (commits e569dba..0d6d82a, review clean)

Task 8: fix round 1/5 (1 addressed, 0 open; commit 097fa7e) — get_param 실패/빈값/None 가드 +
  호출부를 단독 대입문으로 재구성, 계획 원문 Task 8 Step 1 동반 갱신.
Task 8: 재검증 근거 — 재검증자가 직접 재현. 수정 전 패턴은 `REACHED`+exit 0+빈 값 기록,
  수정 후 단독 대입문은 set -e 로 즉시 종료(exit 3, REACHED 미출력). 함수 안 `exit` 서브셸 함정은
  `return` + 호출부 대입문 전파로 회피. `local x=$(cmd)` 가 종료코드를 가리는 별도 함정도
  선언(`local name="$1" value`)과 대입(`if ! value=...`)을 분리해 회피 — 이것도 별도 재현으로 검증.
Task 8: minor (deferred): `.env` 주석 1줄 추가는 finding 범위 초과(무해, 동작 변경 없음).
Task 8: minor (deferred): `"None"` 문자열 분기는 재현 실험에 미포함 — 빈 문자열과 같은 분기라 위험은 낮음.
Task 8: complete (commits 44734fd..097fa7e, review clean — 2 minor deferred)

Task 10: 리뷰 도착 — 스펙 ✅ (브리프 YAML 과 바이트 단위 동일, diff 로 검증), 품질 Not approved.
  - **finding (Important, plan-mandated):** `aws ssm wait command-executed` 는 delay 5s × 20회 =
    **100초 고정 상한**이고 CLI 로 연장 불가(botocore waiters-2.json 확인, --delay/--max-attempts 미제공).
    그런데 `deploy.sh` 는 자체 헬스체크만 최대 3분(seq 1 36 × sleep 5) 대기하고, 그 앞에 SSM
    get-parameter 9회·docker login·ECR pull 이 붙는다. 정상 배포에서도 waiter 가 먼저 만료되고
    이어지는 상태 조회가 `InProgress` 를 받아 `::error::배포 실패` 로 종료 — **거짓 실패**.
    수정 방향: waiter 대신 get-command-invocation 수동 폴링 루프(예: 10분).
  - ⚠️ 항목 해소(컨트롤러): "EC2 CPU 아키텍처 미확인 — arm64 면 amd64 이미지 pull 실패".
    설계 문서 §2.8·비용표가 **t3.medium**(x86_64)로 고정하고, bootstrap-ec2.sh:21 이
    `docker-compose-linux-x86_64` 를 하드코딩. amd64 일관 — 실제 갭 아님.
  - Task 10: minor (deferred): 테스트 잡의 `compose up -d postgres redis kafka` 가 준비 완료를
    기다리지 않음(redis·kafka 는 healthcheck 부재). Gradle 컴파일 시간에 가려질 뿐 경합은 존재.
  - Task 10: minor (deferred): `:latest` 태그 push 가 이 파이프라인에 소비자 없음(디버깅 편의).

Task 11: 구현 완료 (a27f8d7, BASE 097fa7e) — DONE_WITH_CONCERNS. 리뷰 진행 중.
  구현자 실측: 실제 flutter build web 성공, dart-define 이름 코드와 일치, frontend/build/ 는 gitignore.
  구현자 우려 2건은 리뷰어에게 **독립 판정 지시**함:
   (a) 브리프가 ENABLE_QUICK_LOGIN 위치를 api_config.dart 로 적었으나 실물은 core/config/feature_flags.dart
   (b) no-cache 정규식이 flutter.js·manifest.json·favicon.png 미포함.
       구현자 변론("기존 nginx.conf 도 동일하니 새 격차 아님")은 논점 이탈 —
       판단 기준은 "기존과 같은가"가 아니라 "Vercel 기본 cache-control 이 무엇인가"다.

Task 11: 리뷰 도착 — 스펙 ✅ (브리프와 바이트 단위 동일, 허용 파일 2개만 생성), 품질 **Approved**.
  구현자 우려 2건 독립 판정 결과:
   (a) 확인됨, 결함 아님 — dart-define **이름**이 feature_flags.dart:23-24 의 문자열과 일치하므로
       값은 정상 전달. 계획 원문의 "api_config.dart" 표기가 틀린 것뿐(문서 부정확).
   (b) 구현자 변론은 틀린 기준이었으나 **결론은 결함 아님** — 리뷰어가 Vercel 공식 문서로 확인:
       라우트에 안 잡힌 파일의 기본값이 `public, max-age=0, must-revalidate` 라 매 요청 재검증.
       즉 stale 서빙 위험 부재. 계획이 경고한 사고는 명시적 `immutable`/긴 max-age 가 있어야 발생.
Task 11: minor (deferred): `flutter.js` 를 no-cache 목록에 명시하면 플랫폼 기본값에 기대지 않아 더 방어적.
Task 11: minor (deferred): vercel `--token` 을 프로세스 인자로 전달(Vercel 공식 CI 패턴, 러너 일회성이라 수용).
Task 11: ⚠️ 항목 — GitHub `vars.API_BASE_URL`·`secrets.VERCEL_*` 등록은 이 diff 범위 밖.
  **Task 12 절차서가 실제로 이를 다루는지 컨트롤러가 확인할 것** (Task 12 디스패치에 해당 지시 포함함).
Task 11: complete (commits 097fa7e..a27f8d7, review clean — 2 minor deferred)

Task 12: 구현 완료 (24db9c4, BASE a27f8d7) — DONE_WITH_CONCERNS.
Task 12: 리뷰 도착 — 스펙 ✅ (10개 섹션 전부, Missing/Extra 없음), 품질 Needs fixes.
  리뷰어 실측 확인(강점): SSM 9개가 deploy.sh:29-37 과 이름·순서까지 일치 / GitHub 시크릿·변수 표가
  워크플로 2개와 **워크플로→문서 방향으로도** 대조해 누락 없음 / IAM Sid 4개가 각 스크립트 실제 요구
  액션과 대응 / 복구 절차의 컨테이너·DB 사용자명이 compose·backup-db.sh 와 일치 / §10 이 설계 §9 와 동일 /
  브리프 오기("11개")를 베끼지 않고 실물 9개로 정정.
  - **finding (Important):** `.htpasswd` 워크어라운드가 §2.12(341-343)·§5(431)·§8(495) 세 곳 모두
    "로컬→S3 재업로드" 뿐이라 **지금 깨진 EC2 를 고치지 못한다.** 파일이 사라지는 시점은 직전 배포이고
    그때 `docker compose up -d` 가 이미 실행된 뒤다. S3 에 올려도 EC2 로컬 디스크에는 다음 배포까지
    반영되지 않는다 — 진단만 있고 즉시 복구 명령이 부재. 운영자가 "확인했다"에서 멈추고 조용히 당하는 구조.
  - Task 12: minor: :58 "워크플로 2개 모두 ap-northeast-2 기본값" 부정확 — deploy-web.yml 은 AWS 미호출.
  - Task 12: minor: :164 디스크 30GB 근거가 설계 §2.8 에 있다고 했으나 §2.8 은 메모리 사이징만 다룸.
  - Task 12: minor: :321 nginx.prod.conf 의 `api.example.com` 을 "2곳"이라 했으나 실제 4곳
    (33·50·54·55행). 파일 자체 주석 10행의 "3곳"도 부정확. `sed /g` 라 절차 수행에는 무영향.
  - ⚠️ 항목: IAM 정책 JSON 은 실호출 검증 불가 — 각 스크립트가 요구하는 액션에서 역산해 논리 대조만 수행.
  - **처리 계획:** Task 10 fix 가 `.htpasswd` 삭제 자체를 없애므로 세 섹션의 전제가 바뀐다.
    → task10-fixer 완료를 기다린 뒤 Task 12 fix 라운드에서 Important 1건 + minor 3건을 함께 반영.

## 🔴 신규 결함 발견 (Task 12 작업 중, 컨트롤러가 독립 확인 완료)

**`.htpasswd` 가 백엔드 배포마다 삭제된다 — Swagger Basic Auth 붕괴.**
- `infra/proxy/.htpasswd` 는 `.gitignore:26` 으로 **git 미추적**(시크릿이라 의도적).
- `deploy-backend.yml:78` 이 `aws s3 sync infra "s3://$BUCKET/infra" --delete` — 러너의 체크아웃 기준
  이라 그 파일이 없고, `--delete` 가 S3 쪽 사본을 지운다.
- `deploy-backend.yml:93` 이 `aws s3 sync s3://$BUCKET/infra /opt/school-bus/infra --delete` — EC2 에서도 삭제.
- 확인 방법: `git ls-files infra/proxy/` → `nginx.conf`·`nginx.prod.conf` 만 추적됨(.htpasswd 부재).
- 성격: **plan-mandated** — `--delete` 동기화는 계획 Task 10 Step 1 YAML 원문에 있다.
- 처리: 같은 파일(`deploy-backend.yml`)을 고치는 **Task 10 fix 라운드에 합쳐서 처리**한다.

## ✅ 사용자 판단 (2026-08-11): "둘 다 고쳐줘"

계획 원문과 충돌하는 두 finding(waiter 100초 상한 · `--delete` 가 `.htpasswd` 삭제) **모두 수정 승인**.
Task 7·8 과 같은 방식으로 **계획 원문도 동반 수정**한다. → Task 10 fix round 1 디스패치 (BASE 24db9c4).

Task 10: fix round 1/5 (2 addressed 주장, 재검증 대기; commit 8e07fb8, parent 24db9c4)
  - waiter → `get-command-invocation` 수동 폴링(상한 600초, 10초 간격). `InvocationDoesNotExist` 는
    SSM waiter 도 retry 로 취급하는 에러라 조용히 재시도, 실패 응답이 STATUS 를 덮어쓰지 않게 처리.
  - 두 sync 모두 `--exclude proxy/.htpasswd`. `--delete` 가 필터 제외 파일을 삭제 대상에서도 뺀다는 점을
    `aws s3 sync help` + awscli `filters.py` 소스로 교차검증(scratchpad venv 에 awscli 설치해 직접 실행).
  - 스텁 4시나리오: (A)InProgress×2→Success exit 0 (B)즉시 Failed exit 1(1초, 10분 대기 없음)
    (C)영구 InProgress→상한초과 exit 1 (D)InvocationDoesNotExist 1회 후 Success exit 0.
    → **음성 대조 포함**(B·C 가 실패해야 할 때 실패함을 보임).

Task 10: 재검증 전건 ADDRESSED, 신규 파손 없음. 재검증자가 **직접 재현**한 근거:
  - 상태 분류: `case ... in Success|Cancelled|TimedOut|Failed|Cancelling) break` (L126-128).
    `Pending`·`InProgress`·`Delayed` 는 case 미해당 → 자동 재폴링. 잊기 쉬운 `Delayed`(진행 중)·
    `Cancelling`(종료) 둘 다 정확히 분류. 오판하는 값 **0개**.
  - 상한 초과: 루프 자연 종료 후 `STATUS != Success` → `ELAPSED >= MAX` 분기가 `exit 1`. 성공 유출 경로 부재.
  - 명령 치환: `if POLLED_STATUS="$(... 2>&1)"; then STATUS="$POLLED_STATUS"` — aws 실패 시 대입 자체가
    미실행이라 STATUS 를 덮어쓰지 않음(구현자 주장이 코드로 확인됨).
  - `ELAPSED=$((...))` 는 산술 **대입문**이라 `((...))` 가 결과 0 에서 비영 종료하는 함정에 미해당 — 별도 확인.
  - `--exclude proxy/.htpasswd` 경로 기준: 두 sync 모두 source/dest 루트가 `…/infra` 로 끝나므로
    동일하게 `<root>/proxy/.htpasswd` 를 가리킴. 한쪽만 어긋나는 경우 아님.
  - 계획 원문 ↔ 실물: python 으로 블록 추출 후 바이트 단위 대조 — IDENTICAL(구현자 주장 재현 확인).
Task 10: minor (deferred): `MAX_WAIT_SECONDS=600` 은 튜닝값. 최초 배포처럼 ECR pull 이 느리면 초과 가능성.
Task 10: minor (deferred): 지속 에러 시 10초마다 `::warning::` 반복 — 로그 소음.
Task 10: complete (commit 8e07fb8, review clean — 4 minor deferred)

✅ 후속 반영 완료 — Task 12 fix round 1(eb6c088)에서 `docs/DEPLOYMENT.md` 갱신.

Task 12: fix round 1/5 (4 addressed, 0 open; commit eb6c088, parent 880fe32)
Task 12: **구현자가 리뷰어보다 한 발 더 나간 발견** — `--exclude` 는 삭제뿐 아니라 **다운로드도** 막는다.
  즉 `.htpasswd` 를 S3 에 올려두는 방식은 애초에 성립 불가였고, EC2 로컬 생성만이 유효한 경로.
  재검증자가 AWS 공식 문서("Use of Exclude and Include Filters")로 확인 — 필터가 전송 대상과
  `--delete` 삭제 후보 판정에 **동일하게** 적용됨. 문서 구조 변경의 근거가 성립.
Task 12: 재검증자 실물 대조(즉시 복구 절차): `APP_DIR=/opt/school-bus` 일치 /
  compose 볼륨 `./infra/proxy/.htpasswd:/etc/nginx/.htpasswd:ro` → 호스트 경로가 문서와 일치 /
  서비스명 `proxy` 일치 / `--env-file` 필요성 근거 확인(compose 가 서비스 하나만 대상이어도 전체 파일을
  파싱·보간하므로 `:?` 필수 변수에서 에러) / `bootstrap-ec2.sh` 에 `usermod -aG docker` 부재 →
  `sudo` 정당 / 바인드 마운트는 파일 재생성 시 inode 가 바뀌어 restart 필요 — 서술 타당.
Task 12: minor (deferred): 즉시 복구 절차가 `$INSTANCE_ID` 셸 변수 재사용을 가정 — 별도 세션이면 비어 있음
  (§6 롤백에도 있는 기존 컨벤션이라 이번 diff 가 만든 문제는 아님).
Task 12: minor (deferred): 즉시 복구 3단계가 한 코드 블록에 SSM 접속과 세션 내부 명령을 함께 담아
  통째 붙여넣기 시 미동작 가능 — §2.12 상단 최초 생성 절차는 두 블록으로 분리돼 있어 일관성 저하.
Task 12: complete (commits a27f8d7..24db9c4 + fix eb6c088, review clean — 5 minor deferred)

Task 9: complete (commits 960dbb6..d1a8d24, review clean — Critical/Important/Minor 전무)
Task 9: ⚠️ 항목 해소 — 리뷰어가 "pg_dump 의 로컬 소켓 인증 방식 미확인" 으로 남긴 건,
  컨트롤러가 이전에 실행한 `docker exec db psql -U schoolbus` 가 비밀번호 요구 없이
  `FATAL: role "schoolbus" does not exist` 까지 도달한 것으로 확인됨(공식 postgres 이미지의
  initdb --auth-local=trust 기본값). `-h` 없는 소켓 접속이라 비밀번호 불필요 — 실제 갭 아님.

---

## 후속 2건 처리 (2026-08-11, 실배포 착수 전)

**후속 1 — 인증서/도메인 순서 역전. 해소.**
- `docs/DEPLOYMENT.md` §2.9 ↔ §2.10 교체 → **§2.9 도메인 연결 → §2.10 인증서 최초 발급**.
  §2.9 에 `api` A 레코드 선행 이유(HTTP-01 챌린지가 도메인을 조회해 EIP 80 으로 접속)와
  `dig +short` 전파 확인 명령 추가. §2.10 에 전제 문장 추가.
- §2.9 는 SSM 세션 안에서 하는 작업이 아니므로 옛 §2.9 의 "같은 SSM 세션 안에서" 를
  "SSM Session Manager 로 EC2 에 접속해(§2.8 과 같은 방법)" 로 교체 — 세션 연속성 전제가 끊겼기 때문.
- §2.1 순서 요약 갱신: 나열 순서 교체 + "순서가 중요한 지점 **둘 → 셋**"(A 레코드 선행을 (a)로 추가).
- `infra/scripts/bootstrap-ec2.sh` 완료 안내를 3단계 → 4단계로: A 레코드 연결(§2.9)을 인증서 발급(§2.10) 앞에 삽입.
  **계획 원문(Task 7 블록)도 동반 수정** — python 정규식 추출 후 바이트 비교로 `일치=True` 재확인.
- §2.11~§2.14 번호는 불변이라 `init-cert.sh`·`nginx.prod.conf`·`deploy-backend.yml` 의 상호참조는 손대지 않음.
- 미채택: `init-cert.sh` 에 DNS 선행 검사 추가. 문서·안내가 순서를 명시하므로 스크립트에 실패 조건을
  하나 더 만드는 편익이 작다고 판단(resolver 캐시·NXDOMAIN 오탐으로 정상 발급을 막을 위험).

**후속 2 — `docs/` SoT 4종의 `demo` 프로파일 누락. 해소.**
- `API_SPEC.md` §18: 시드 적용 범위를 "local 전용" → "`local`·`demo` 두 프로파일", 계정 표 제목을
  "`local` 에서만 전부 `password`" 로 바꾸고 `seedPasswordHash` placeholder 경고 블록 추가.
- `USER_FLOWS.md` §0.5 제목·시드 문단 동일 취지로 갱신.
- `ARCHITECTURE.md` §10 표 2곳(`flyway.locations`·`migration-local` 행), §11.3 프로파일 표를
  **3개 → 4개**로 늘리고 `demo` 행 신설(prod 대비 차이 2가지 + 기본값 부재 이유).
- 같은 성격의 잔여 드리프트 2건 동반 정정 — `ARCHITECTURE.md` R19 · `PRODUCT_SPEC.md` M4 의
  `ENABLE_QUICK_LOGIN` 서술. "배포 파이프라인이 생기면 고정한다" 는 이미 해소됨
  (`deploy-web.yml:44` 가 `--dart-define=ENABLE_QUICK_LOGIN=false` 고정). 남은 노출은 로컬 compose 뿐.
- `backend/.../application.yml:21` 주석 "local 프로파일은 시드를 더한다" → "local·demo".

**검증:** `./gradlew test` BUILD SUCCESSFUL(253 tests) / `docs/DEPLOYMENT.md` 어투 위반 grep 0건 /
§2 헤딩 번호 연속성 2.1~2.14 확인 / 계획 원문 ↔ `bootstrap-ec2.sh` 바이트 일치.

**남은 것:** 실 AWS 첫 배포에서만 실증되는 C2·I1·I3·I6·I7. 이 브랜치는 그때까지 계속 미push 유지.
