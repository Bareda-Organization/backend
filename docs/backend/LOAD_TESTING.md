# F3 부하 시험 (좌석 L, 목표 12·13·14)

`IMPLEMENTATION_PLAN.md §5` 가 정한 4개 시나리오를 이 디렉터리 하나로 실행한다. 정본은 그 문서이고
여기는 실행 방법만 적는다 — 판정·근거는 `report-f3-l.md` §3 을 본다.

## 0. 사전 조건

- 백엔드가 `SPRING_PROFILES_ACTIVE=load` 로 포트 `18080` 에 떠 있을 것(`application-load.yml`)
- postgres `schoolbus_load`(15432) · redis(16379) 컨테이너가 떠 있을 것
- `psql` 로컬 바이너리는 이 호스트에 없다 — 전부 `docker exec school-bus-postgres-1 psql -U schoolbus -d schoolbus_load ...` 로 대신한다
- k6 `/opt/homebrew/bin/k6`(v2.1.0, `k6/ws` 확인 완료 — `k6/experimental/websockets` 아님)

## 1. 시나리오 1 — 동시 도래 폭주 (k6 아님, §5.4 근거)

```bash
docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load \
    -v n=10 -t -A -F',' < sql/scenario1_prep.sql   # 직접 실행하지 않는다 — observe.sh 가 대신 호출
```

대신 관측 스크립트 하나로 심기+대기+지표 수집을 전부 한다:

```bash
./sql/scenario1_observe.sh 10  "postgresql://schoolbus:schoolbus@localhost:15432/schoolbus_load" \
    http://localhost:18080/actuator/prometheus
./sql/scenario1_observe.sh 50  "postgresql://schoolbus:schoolbus@localhost:15432/schoolbus_load" \
    http://localhost:18080/actuator/prometheus
./sql/scenario1_observe.sh 200 "postgresql://schoolbus:schoolbus@localhost:15432/schoolbus_load" \
    http://localhost:18080/actuator/prometheus
```

결과는 `results/scenario1_N<N>_<tag>.json` 에 쌓인다(각 N 마다 별도 파일 — 누적 비교 가능).

## 2. 시나리오 2 — 위치 수신 처리량 (L1)

```bash
docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load \
    -v n=20 -t -A -F',' < sql/scenario2_prep.sql | grep -v '^$' > k6/scenario2_runs.csv

cd k6
k6 run -e SCENARIO2_CSV=./scenario2_runs.csv -e SCENARIO2_DURATION_SEC=60 \
    -e SCENARIO2_INTERVAL_SEC=7 --summary-export=../results/scenario2_N20.json \
    scenario2_position.js
```

N 을 10→50→200 으로 올려 반복한다(회차마다 계정·버스·회차를 새로 심으므로 `scenario2_prep.sql` 을 N 값만 바꿔 재실행하면 된다 — LOADPOS- 접두사 + 실행마다 다른 `tag` 라 이전 N 실행분과 안 겹친다).

**측정** — `position_post_duration_ms`(수신 처리 지연) · `ws_fanout_latency_ms`(팬아웃 지연, 근사 — VU 자기 송신의 메아리로 계산) · `position_post_failures`(§5.3 임계: 0) · `position_echo_received_total`/`positions_sent_total`(F5 S2 목표 2 — 수신≥송신×0.8 게이트, `k6/lib/config.js` 의 `STAFF_OBSERVER_LOGIN_ID`(`staffA`)로 WS 구독을 분리한다. §5.1 참고).

## 3. 시나리오 3 — 관제 팬아웃 (L2)

```bash
cd k6
# 트래픽 없이 연결 한계만 볼 때
k6 run -e SCENARIO3_TARGET_VUS=200 -e SCENARIO3_RAMP_SEC=60 -e SCENARIO3_HOLD_SEC=60 \
    --summary-export=../results/scenario3_N200.json scenario3_admin_fanout.js

# 팬아웃 지연을 실제로 보려면 시나리오 2 를 동시에 돌려 방송을 만든다(다른 터미널)
k6 run -e SCENARIO2_CSV=./scenario2_runs.csv -e SCENARIO2_DURATION_SEC=120 scenario2_position.js
```

**측정** — `ws_messages_received` · `ws_message_latency_ms`(근사 — 서버 `occurred_at` 과 k6 로컬 시계 차, 같은 호스트라 NTP 보정 없음) · `ws_connect_failures`(연결 실패가 늘기 시작하는 VU 수 = 무너지는 지점).

## 4. 시나리오 4 — 온디맨드 계산 경합 (L2)

```bash
docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load \
    -v n=20 -t -A -F',' < sql/scenario4_prep.sql | grep -v '^$' > k6/scenario4_approvals.csv

curl -s http://localhost:18080/actuator/prometheus | grep schoolbus_routing_stub_load > /tmp/stub_before.txt

cd k6
k6 run -e SCENARIO4_CSV=./scenario4_approvals.csv \
    --summary-export=../results/scenario4_N20.json scenario4_approval_ondemand.js

curl -s http://localhost:18080/actuator/prometheus | grep schoolbus_routing_stub_load > /tmp/stub_after.txt
diff /tmp/stub_before.txt /tmp/stub_after.txt   # 격벽 거부·타임아웃·실패 주입 델타
```

**경합을 실제로 재현하려면** 시나리오 1(`scenario1_observe.sh`)과 이 스크립트를 동시에 돌린다 — 배치(BATCH)와 온디맨드(ON_DEMAND)가 같은 `StubMapRouteClient` 싱글턴의 `inFlight` 카운터를 공유해야 격벽 경합이 생긴다. 단독 실행은 온디맨드 경로 자체의 응답 시간만 잰다 — 어느 쪽으로 실행했는지 `report-f3-l.md` §3 에 적는다.

**측정** — `approval_detail_duration_ms`(§5.3: 임계 없음, 관측만) · 위 prometheus 델타(레이트리밋 초과 = 격벽 거부 건수).

## 5. 스텁 지연·실패 주입값 — 출처와 근거

실 Naver 지도 API 의 응답 시간·실패율 분포는 **측정 불가**(API 키 미보유, F3 L 판정 고정 — `IMPLEMENTATION_PLAN §5.5` ⚠). 아래 값은 실측이 아니라 **코드에 이미 있는 실 정책값에서 유추한 잠정값**이다.

| 값 | 근거 |
|---|---|
| `LOAD_STUB_MAX_CONCURRENT=4` | `application.yml` `resilience4j.bulkhead.instances.mapRoute.max-concurrent-calls: 4` — 그 주석 자체가 "NCP 공개 레이트리밋 미실측, 보수적 잠정값(2026-08-29)"이라고 명시. 스텁은 그 잠정값을 그대로 재사용(같은 잠정성을 상속) |
| 시나리오 1(배치) 지연: `min=300ms max=1200ms` | 배치 타임아웃(`RunConfirmationService.MAP_TIMEOUT=15s`)의 10% 미만 — 정상적인 지도 API 응답이 배치 타임아웃 안에 대부분 끝나는 상황을 재현하는 것이 목적이라, 타임아웃에 근접하지 않는 대역을 골랐다. 실측값이 아니라 **"정상 상황을 가정한 값"** 이라는 점을 결과와 함께 적는다 |
| 시나리오 4(온디맨드) 지연: `min=1000ms max=6000ms` | 온디맨드 타임아웃(`ApprovalPreviewResolver.ON_DEMAND_MAP_TIMEOUT=5s`)을 **의도적으로 넘나드는 대역** — 상한을 5초보다 높여 "타임아웃 모사→폴백" 분기(`StubMapRouteClient.applyLoadInjection`)가 실제로 관측되게 한다. 5초 미만이면 이 시나리오가 재현하려는 "관리자가 승인 화면에서 대기" 상황 자체가 안 생긴다 |
| 시나리오 4 `failure-rate=0.1` | 사양이 정한 값이 없어 **10%를 시험용으로 선택**(임의값임을 명시) — `CallerPolicy.ON_DEMAND` 경로에서 `MapRouteUnavailableException`(서킷 개방 모사)이 실제로 던져지는지 확인하는 것이 목적이지, 실 서킷 개방률을 재현하는 것이 아니다 |

시나리오 1·4 실행 시 실제로 준 값은 `bootRun` 커맨드 라인(아래)에 그대로 남기고, 결과 파일에도 같은 값을 병기한다.

```bash
LOAD_STUB_MIN_DELAY_MS=300 LOAD_STUB_MAX_DELAY_MS=1200 LOAD_STUB_MAX_CONCURRENT=4 \
    SPRING_PROFILES_ACTIVE=load ./gradlew bootRun   # 시나리오 1 구간

LOAD_STUB_MIN_DELAY_MS=1000 LOAD_STUB_MAX_DELAY_MS=6000 LOAD_STUB_FAILURE_RATE=0.1 \
    LOAD_STUB_MAX_CONCURRENT=4 SPRING_PROFILES_ACTIVE=load ./gradlew bootRun   # 시나리오 4 구간
```

같은 `bootRun` 프로세스 안에서 시나리오 1·2 를 먼저 돌리고, 필요하면 재기동 없이 그대로 시나리오 4 도 돌릴 수 있다 — 단 그러면 시나리오 1 용 지연값이 시나리오 4 에도 적용된다. **재현성이 우선이면 시나리오별로 재기동**하고, 시간이 아까우면 시나리오 1 값(짧은 지연)으로 통합 실행하되 "시나리오 4 도 배치용 지연값으로 쟀다"고 report 에 명시한다(둘 다 유효한 선택 — 이 판단은 실행 시점에 report §2 에 적는다).

### 5.1 온디맨드 값으로 실측(2026-09-05, F5 S2 목표 3)

위 표의 온디맨드 대역(`min=1000ms max=6000ms failure-rate=0.1 max-concurrent=4`) 하나로 `bootRun` 을
한 번만 띄우고 **시나리오 1(N=10)과 시나리오 4(N=20)를 동시에** 돌렸다 — 5.4 절이 이미 문서화한
트레이드오프의 반대쪽 선택이다(시나리오 1 용 짧은 지연값 대신 온디맨드 대역을 그대로 시나리오 1
배치 확정에도 적용). 그래서 아래 `throttled{caller=batch}` 는 "정상 대역에서의 배치 부하"가 아니라
**"온디맨드 대역을 배치가 같이 맞았을 때"** 값이다 — 시나리오 1 을 정상 대역으로 단독 측정한 값과
직접 비교하면 안 된다.

```bash
LOAD_STUB_MIN_DELAY_MS=1000 LOAD_STUB_MAX_DELAY_MS=6000 LOAD_STUB_FAILURE_RATE=0.1 \
    LOAD_STUB_MAX_CONCURRENT=4 SPRING_PROFILES_ACTIVE=load ./gradlew bootRun &

# 별도 터미널 — 동시 실행
bash sql/scenario1_observe.sh 10 "postgresql://schoolbus:schoolbus@localhost:15432/schoolbus_load" \
    http://localhost:18080/actuator/prometheus 2 300 &
docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q \
    -v n=20 -t -A -F',' < sql/scenario4_prep.sql | grep -v '^$' > k6/scenario4_approvals.csv
cd k6 && k6 run -e SCENARIO4_CSV=./scenario4_approvals.csv \
    --summary-export=../results/scenario4_ondemand.json scenario4_approval_ondemand.js
```

| 측정 | 값 |
|---|---|
| 온디맨드 승인 미리보기 `approval_detail_duration_ms` p95 | 4.56s (avg 790.7ms, 20/20 200 OK) |
| `throttled{caller=on_demand}` 델타 | 16 |
| `throttled{caller=batch}` 델타 | 6 (온디맨드 대역을 같이 맞은 값 — 위 주의사항 참고) |
| `timeout_total` 델타 | 1 (주입 지연이 타임아웃을 넘겨 직선근사 폴백으로 흡수) |
| `failure_injected_total` 델타 | 1 — `[map-route]` WARN 로그는 0건이라 BATCH 쪽으로 판정(코드상 `MapRouteUnavailableException` 은 `ON_DEMAND` 호출자에서만 던져지고, 던져졌다면 시나리오 4 가 503 을 받았어야 하는데 20/20 성공이라 정합) |

결과 파일: `results/goal3_ondemand_concurrent_summary.json`(위 표의 근거),
`results/scenario1_N10_130541593.json`, `results/scenario4_N20_ondemand_concurrent.json`.

---

## 6. 부하 한계 측정(2026-09-09) — "깨질 때까지 올려 한계를 찾는" 회차

위 1~5절은 **정해진 부하에서 약속을 지키는가**를 본다. 이 절은 반대로 **포화 지점**을 찾는다.
계획은 `docs/archive/sdd/IMPLEMENTATION_PLAN/load-capacity-plan.md`, 결과는
`backend/report/2026-09-09-부하-한계-측정.md`.

### 6.1 기동 — `SPRING_PROFILES_ACTIVE=load` 는 듣지 않는다

```bash
./gradlew bootRun --args='--spring.profiles.active=load --spring.devtools.restart.enabled=false \
    --app.routing.map.stub.load.min-delay-ms=300 --app.routing.map.stub.load.max-delay-ms=1200 \
    --app.routing.map.stub.load.max-concurrent=4'
```

⚠ 환경변수로 주면 **`local` 프로파일로 뜬다**(2026-09-09 실측 — Gradle 데몬이 클라이언트 환경을
포크한 JVM 에 그대로 넘기지 않는다). 그러면 DB 도 포트도 `schoolbus`·8080 이 되어, 부하 시험이
개발용 DB 를 때린다. **커맨드라인 인자로 주는 형태만 검증됐다.**

`schoolbus_load` 가 없으면 먼저 만든다 — Flyway 가 기동 시 스키마·시드를 넣는다.

```bash
docker exec school-bus-postgres-1 psql -U schoolbus -d postgres -c "CREATE DATABASE schoolbus_load OWNER schoolbus"
```

### 6.2 스크립트

| 파일 | 하는 일 |
|---|---|
| `sql/r0_capacity_seed.sql` | 목표 규모 시드 — 학원 10 · 버스 100 · 학생 2,000 · 회차 200. **회차당 명단 20명이 실제로 붙는다**(시나리오 1의 빈 roster 와 다른 점) |
| `sql/r0_reset_runs.sql` | 그 회차를 다시 `idle` 로 되돌린다. 확정 산출물(확정 노선·버전·정차 순서·배정 학생)까지 지운다 — 안 지우면 다음 회차가 "동시 도래" 가 아니라 "재확정" 을 잰다 |
| `r2_round.sh <N> [초]` | 위치 수신 한 회차. 심기 → 표본 → k6 → 요약 |
| `r1_round.sh <VU> [ramp] [hold]` | 실시간 세션 한 회차 |
| `r3_mixed.sh <세션> [배율]` | 혼합 피크 — 세션·위치·확정 배치를 겹쳐 돌린다 |
| `sampler.sh <라벨> <초>` | 1초 간격 자원 표본 → `results/sample_<라벨>.csv` |
| `snapshot.sh <라벨>` | 자원 점 스냅샷 → `results/snapshot_<라벨>.json` |
| `summarize_sample.py <csv>` | 표본에서 최대·중앙값 |
| `start_sized.sh <코어> <힙> <아웃바운드>` | **인스턴스 크기를 흉내 내어 앱을 띄운다** — `bootJar` 산출물을 `-XX:ActiveProcessorCount`·`-Xmx` 로 직접 실행. 사양 산출용 |

### 6.3 시나리오 2 에 붙은 환경변수 2개

| 변수 | 기본 | 왜 |
|---|---|---|
| `SCENARIO2_OBSERVERS` | VU 전원(이전과 동일) | VU 전원이 academy live 를 구독하면 방송이 VU 수만큼 복제돼 **팬아웃 비용이 N² 로 는다.** 그러면 포화가 위치 수신 때문인지 팬아웃 때문인지 못 가른다. 세션 한계는 시나리오 3 이 따로 재므로 R2 는 2로 낮춰 돌렸다 |
| `SCENARIO2_JITTER` | `false` | 켜면 VU 마다 0~주기 사이 난수만큼 늦게 시작한다. 끈 쪽은 **전 차량이 같은 초에 송신하는 최악**, 켠 쪽은 평시. 같은 N 에서 p95 가 6,286ms ↔ 3,830ms 로 갈렸다 |

### 6.4 실행하며 밟은 함정

- **`bus_no` 는 `varchar(20)`** — `scenario2_prep.sql` 의 접두사·태그가 길어 N ≥ 100 에서만 터졌다. 접두사를 `LP-`, 태그를 `MISSMS`(7자)로 줄여 고쳤다
- **부하가 가장 높은 회차에서 `docker exec` 가 Docker Desktop 의 VM 을 멈춰 세웠다**(2회). 확정 배치 드레인 판정을 2초마다 SQL 로 세던 것이 원인 — 지금은 actuator 지표(`schoolbus_run_confirmation_lag_seconds_count` 증가분)로 센다. **호스트가 포화하는 회차에서 Docker 명령 실패는 환경 문제로 분류한다**
- **`lsof` 로 연결 수를 세면 연결 수백 개부터 표본기 자체가 1초를 넘겨** 버스트를 놓친다. `tomcat_connections_current_connections` 로 대체
- **1초 표본의 최대값은 회차마다 갈린다**(같은 N=1,200 에서 0.19 ~ 0.38). 판정에는 `process_cpu_time_ns_total` **누적 차**를 쓴다 — 표본 시점과 무관하다
- **macOS Docker Desktop 의 호스트→컨테이너 포트 전달(15432)이 고부하에서 26~29초씩 멈춘다**(O2, `Ruling 353`③, 근거 `FIX-LOAD2.md §3-2`) — 앱은 이미 보낸 쿼리의 응답을 기다리고, 같은 순간 Postgres 는 `ClientRead`(다음 명령을 기다림) 상태다. 즉 어느 쪽도 일을 안 하는 게 아니라 **바이트가 둘 사이 전달 경로에 묶인다** — 스레드 덤프는 전부 소켓 읽기, `pg_stat_activity` 는 실행 중 쿼리 0·잠금 0, CPU 는 한산(서명 3가지). Hikari 풀 크기를 5배로 늘려도 실패율이 그대로면 이 증상을 의심한다. **포화 지점을 찾는 회차는 앱(과 k6)을 Postgres 와 같은 Docker 네트워크에서 띄운다** — 호스트 앱 → 컨테이너 DB 경로로는 재지 않는다. 멈추면 Docker Desktop 을 재시작한다(컨테이너는 보존— `docker compose down` 아님). ⚠ 재시작 뒤 `restart: always` 로 설정된 다른 프로젝트 컨테이너가 같이 켜질 수 있다 — 재시작 후 `docker ps` 로 School-Bus 것만 남았는지 확인할 것

## 6.5 R46-LOAD(2026-10-01) — 폴링·시청 세션·2초 송신을 더한 재측정

R46 에서 서버·웹·앱이 바뀐 뒤(`IMPLEMENTATION_PLAN §8.84`) 09-09 와 **같은 목표 규모 시드**로 다시 쟀다. 09-09 시나리오에 없던 것 — 열린 앱·웹이 주기적으로 치는 REST 와 실제 구독 분포의 시청 세션 — 을 `scenario5_polling.js` 로 더했다. 결과 해석은 `backend/report/2026-10-01-부하-재측정.md`.

### 6.5.1 추가된 파일

| 파일 | 하는 일 |
|---|---|
| `sql/r46_parent_seed.sql` | `r0_capacity_seed.sql` **뒤에** 한 번 더 실행. 학생마다 보호자 1명(학부모 계정 `loadcap-…-par` + `guardian` + `guardian_student`) 2,000. 다시 실행해도 겹치지 않는다 |
| `r46_mint_tokens.py <출력.json> [학부모 수]` | 학부모 N명 · 관계자 11 · 메인 관리자의 접근 토큰을 **회차 직전에** 발급(유효 15분). k6 안에서 로그인하지 않는 이유 — 학부모 수백 명의 BCrypt 로그인 CPU 가 측정 구간(누적 CPU 차)에 섞인다 |
| `k6/scenario5_polling.js` | 폴링 + 시청 세션. 환경변수로 갈래를 켜고 끈다(0 이면 끔) |
| `sql/r46_link_position_riders.sql` | 위치용 회차(`LP-…`)에 명단 20명씩을 붙인다 — 없으면 학생 채널로 방송이 안 나가 시청 세션이 아무것도 못 받는다. `r3_mixed.sh` 의 `realistic` 모드가 회차마다 자동 실행 |
| `r46_judge.py <라벨>` | r3 회차를 사양 기준 5항(아래 6.5.4)으로 판정 |
| `r46_summarize.py <묶음 출력> <접두사>` | 회차 출력을 표 한 줄로 모은다(호스트 간섭 평균 포함) |

### 6.5.2 폴링 요청 묶음 — 앱·웹 코드에서 센 값

| 갈래 | 간격 | 요청 | 근거 |
|---|---|---|---|
| 학부모 앱 홈 | **90초** | `GET /students/{id}/runs` · `GET /students/{id}/change-requests` · `GET /notifications` — 3요청 동시 | 홈 `VisiblePoller`(`pollInterval` 90초)가 회차·변경 신청을 무효화, 탭 막대 `AppShell` 이 알림을 다시 받음. 앱이 백그라운드·다른 화면이 위에 있으면 멈춤 |
| 관계자 웹 대시보드 | **7초**(응답 뒤 예약) | `GET /staff/runs/live` + `GET /staff/dashboard` | `usePolling` · `LIVE_POLL_INTERVAL_MS` |
| 관계자 웹 금일 운행 | **7초** | `GET /staff/dashboard` + `GET /staff/runs/{id}/roster` + `GET /staff/runs/live` | 명단 조회는 호출마다 감사 기록(R46-AUDIT 로 10분 묶기) |
| 관계자 전 화면 공통 | 5초 · 30초 | `GET /staff/emergencies?status=open`(5초) · `GET /staff/signup-requests` + `GET /staff/approvals`(30초) | `EmergencyAlertProvider` · `ApprovalPendingProvider` — 열린 탭마다 돈다 |

환경변수 — `POLL_PARENT_APPS`(열린 앱 수, 기본 570 = 학부모 1,900명의 30%) · `POLL_PARENT_INTERVAL_SEC`(90) · `POLL_STAFF_DASH_TABS`(11) · `POLL_STAFF_TODAY_TABS`(10) · `POLL_STAFF_INTERVAL_SEC`(7) · `WS_VIEWERS`(학부모가 자기 학생 채널 `/topic/students/{id}/run` 구독) · `WS_STAFF`(학원 채널) · `WS_ADMIN`(관제 채널) · `SCENARIO5_DURATION_SEC` · `SCENARIO5_RAMP_SEC`. 열린 앱 비율 30%·관계자 탭 수는 **가정**이다(조사 D 의 값을 그대로 씀).

학부모 폴링은 `constant-arrival-rate`(앱 수 ÷ 주기)로 모사한다 — 앱 570대를 VU 570개로 만들면 k6 가 서버와 CPU 를 다툰다. 관계자 탭은 탭 하나 = VU 하나의 `요청 → sleep(7초)` 반복이라 응답이 늦어지면 요청도 늦어진다(응답 뒤 예약과 같은 형태).

### 6.5.3 `r2_round.sh`·`r3_mixed.sh` 에 더한 환경변수 (기본값은 09-09 와 같은 동작)

| 변수 | 쓰는 곳 | 뜻 |
|---|---|---|
| `ROUND_LABEL` | r2 · r3 | 결과 파일 이름표(기본 `r2_n<N>` · `r3_s<세션>_x<배율>`). 09-09 결과 파일과 겹치지 않게 `r46_…` 로 준다 |
| `SCENARIO2_INTERVAL_SEC` | r2 | 위치 송신 주기. 앱 실제 값은 **2초**(`position_constants.dart` `transmissionInterval`) |
| `R3_INTERVAL` | r3 | 같음(기본 5) |
| `R3_POLLING=1` | r3 | 폴링을 같이 돌린다 |
| `R3_ADMINS=<N>` | r3 | 관계자 웹 동시 사용자 수 — 메인 관리자 10% + 학원 관계자 90%. 폴링을 자동으로 켠다(6.5.4) |
| `R3_MODE=realistic` | r3 | 세션을 학부모(자기 학생 채널)·관계자 `R3_WS_STAFF`(10)·메인 관리자 `R3_WS_ADMIN`(2)로 나눈다. 기본 `admin` 은 09-09 처럼 세션 전원이 관제 채널 |

두 스크립트는 회차 시작 때 앞 회차가 심은 위치용 회차(`LP-…`, moving)를 종료한다 — 근접 판정 스케줄러가 움직이는 회차 수에 비례해 일해서, 안 끝내면 회차를 거듭할수록 배경 부하가 늘어 회차끼리 비교가 안 된다. 끝에 **서버 방송 전달 건수**(송신 실행기 완료 태스크 차)·**Hikari 연결 대기 시간초과**·**5xx**·**버려진 위치 방송**의 증가분을 낸다.

```bash
cd backend/load
docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q < sql/r0_capacity_seed.sql
docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q < sql/r46_parent_seed.sql
# 서버: §6.1 의 load 기동에 --spring.data.redis.database=<칸> 을 더한다(공유 Redis 를 다른 작업과 나눌 때)

ROUND_LABEL=r46_r2_n100_i2_a SCENARIO2_INTERVAL_SEC=2 ./r2_round.sh 100 60                  # 위치 수신, 2초 송신
R3_INTERVAL=2 R3_POLLING=1 R3_MODE=realistic ROUND_LABEL=r46_r3_real ./r3_mixed.sh 1900 1  # 혼합 피크: 2초 송신 + 폴링 + 실제 구독 분포
```

### 6.5.4 통과 판정 — 관리자 동시 50 (`Ruling 484`)

**관제 세션 2,000 은 통과 기준이 아니라 한계 측정 참고값이다**(`IMPLEMENTATION_PLAN §5.3`). 통과는 목표 규모(학원 10 · 버스 100 · 학생 2,000 · 위치 2초) + **관리자 동시 50**(학원 관계자 45 + 메인 관리자 5) + 학부모·학생 시청 570~1,900 에서 사양 기준 5항을 지키는가로 정한다.

```bash
# 관리자 50 · 시청 570 — 통과 판정 회차(위치 2초 · 폴링 · 실제 구독 분포 · 확정 배치 100건)
ROUND_LABEL=r46_r3_G50_v570_a R3_ADMINS=50 R3_INTERVAL=2 ./r3_mixed.sh 570 1
# 관리자 수를 올려 여유 배수를 잰다(메인 관리자 10% 비율 유지) — 50 → 200 → 500
ROUND_LABEL=r46_r3_G200_v570_a R3_ADMINS=200 R3_INTERVAL=2 ./r3_mixed.sh 570 1
# 판정(회차 결과 파일에서 5항을 읽는다) · 표 요약
./r46_judge.py r46_r3_G50_v570_a
./r46_summarize.py <묶음 출력 파일> r46_r3_
```

`R3_ADMINS` 는 `scenario5_polling.js` 의 `ADMIN_USERS` 로 전달된다 — 메인 관리자 10%(전체 관제 `ADMIN_LIVE` WS + `GET /admin/academies/{id}/runs/live` 7초 + `/admin/emergencies` 5초 + `/admin/runs/attention` 30초), 학원 관계자 90%(절반은 대시보드 탭, 절반은 금일 운행 탭 + 각자 학원 채널 WS · 비상 5초 · 승인 30초). 학원 관계자 계정이 학원당 1개라 45 는 같은 계정의 열린 탭 수이다.

**학원 1 에 탭의 1/9 만 둔다** — 위치용 회차 100대가 전부 학원 1 에 있고 그 학원의 `runs/live` 응답이 2.8MB(회차당 약 28KB)다. 실제는 학원 10곳이 각 10대라, 학원 1 에 탭 1/9 를 두면 응답 총량과 학원 채널 방송 수신 건수가 "45탭 × 10대" 와 같아진다.

| # | 판정 기준 | 사양 | 값을 읽는 곳 |
|:-:|---|---|---|
| 1 | 위치 POST 실패 0 | §5.3 | `position_post_failures` |
| 2 | 송신 주기 2초 유지 | `NFR-03` | 송신 달성률 ≥ 95% · POST p95 < 2,000ms |
| 3 | WS 배달이 5초 안 | `NFR-02` | 채널마다 방송 지연 p95 ≤ 5,000ms(p99·max 병기) |
| 4 | 확정이 출발 30분 전 안 | `C-03` · `RTE-02` | 배치 드레인 ≤ 1,800초 |
| 5 | 미확정 0 | `TECH_DECISIONS §13.4` | 확정 건수 = 도래시킨 건수 · 남은 0건 |

### 6.5.5 이번에 밟은 함정

- **WS 봉투의 `run_id` 는 JSON 문자열이다**(`"192"` — Ruling 332). `scenario2_position.js` 가 숫자와 `===` 로 비교해 **echo 가 항상 0 으로 잡혔다**(`position_echo_received_total: count>0` 임계가 있어도 k6 종료 코드 99 로만 드러남 — 요청 실패 0 인데 99). `Number(body.run_id)` 로 고쳤다. 식별자 문자열화는 09-09 측정 뒤(2026-09-25)에 들어간 변경이라 09-09 수치는 영향이 없고, 이 시나리오를 09-25 이후 코드에서 처음 돌릴 때 걸리는 함정이다
- **회차마다 위치용 회차가 쌓인다** — 위 종료 처리가 없으면 같은 N 의 같은 시험이 회차를 거듭할수록 요청당 CPU 가 늘어난다
- **접근 토큰 유효시간 15분** — 토큰 발급과 측정 시작 사이가 길면 401 이 난다. 회차 직전에 발급한다

## 7. 회차를 화면으로 보기 — Grafana "5. 부하 시험" 대시보드

로그·요약 파일을 기다리지 않고, 회차가 도는 동안 사람이 직접 화면으로 본다. 앱은 지금까지와 같이
**호스트에서** `load` 프로파일로 뜬다.

관측(Prometheus·Grafana)은 School-Bus 전용 컨테이너가 아니라 **여러 프로젝트가 함께 쓰는 로컬 관측
스택**(`/Users/mskim/Desktop/PJ/observability-stack`, 별도 저장소)을 쓴다 — School-Bus 전용
`docker-compose.observe.yml`·`infra/observability/prometheus/prometheus-load.yml` 은 이 스택과 같은
일(부하 시험용 Prometheus·Grafana)을 중복으로 해서 **삭제했다**(O1 · 2026-09-26 `4e85dad8`). **사람이
따라 칠 명령 전체는 그 저장소의 README 를 그대로 따른다** — 복사하지 않는다(복사본은 낡는다).
School-Bus 만의 값은 아래 셋뿐이다.

| 값 | 무엇 |
|---|---|
| `APP` | `school-bus` |
| 호스트 앱 `TARGET` | `host.docker.internal:18080` — `load` 프로파일 포트(§6.1), 8080 이 아니다 |
| 대시보드 폴더 `DIR` | 이 저장소의 `infra/observability/grafana/dashboards`(절대경로로 준다) |

```bash
# 1. 범용 스택 기동 (한 번만 — 다음 회차부터는 그대로 둬도 된다)
cd /Users/mskim/Desktop/PJ/observability-stack && make up

# 2. School-Bus 를 프로젝트로 등록 + 전용 대시보드 연결 (한 번만 — 재기동 없이 반영된다)
make register APP=school-bus TARGET=host.docker.internal:18080 PATH=/actuator/prometheus
make dashboards PROJECT=school-bus DIR="$(git -C <이 저장소 경로> rev-parse --show-toplevel)/infra/observability/grafana/dashboards"

# 3. 앱 기동 — §6.1 과 동일, load 프로파일이 포트 18080 에 뜬다
./gradlew bootRun --args='--spring.profiles.active=load --spring.devtools.restart.enabled=false \
    --app.routing.map.stub.load.min-delay-ms=300 --app.routing.map.stub.load.max-delay-ms=1200 \
    --app.routing.map.stub.load.max-concurrent=4'

# 4. k6 원격 쓰기를 켜고 회차 실행 (기본은 꺼짐 — 켜지 않으면 지금까지와 동일하게 로그·요약 파일만 남는다)
K6_PROM_RW=1 ./r1_round.sh 500      # 또는 r2_round.sh · r3_mixed.sh

# 5. 브라우저에서 확인 — http://localhost:3300 (admin/admin) → "5. 부하 시험"
#    상단 testid 변수에서 방금 돌린 회차(r1_vu500 등)를 고른다

# 6. 끝나면 스택 내리기 — 다른 프로젝트가 같이 쓰고 있을 수 있으니 확인 후(볼륨은 안 지워진다)
make down
```

⚠ `docker-compose.app.yml` 의 prometheus·grafana(호스트 포트 3001·9090, "전부 컨테이너" 개발 모드)는
**이것과 다른 용도**다 — 그쪽은 backend 를 컨테이너(8080 스크레이프)로 띄운 상태를 배포 흉내로
관측하고, 이 절은 backend 를 호스트에서 `load` 프로파일(18080)로 띄운 상태를 관측한다. "5. 부하 시험"
대시보드의 쿼리는 스크레이프 `job` 이 아니라 앱이 늘 붙이는 공통 태그 `application="school-bus"`
(`application.yml` 의 `management.metrics.tags.application`)로 거르므로 — 컨테이너 모드로 부하를
걸어도, 이 범용 스택으로 등록해도 값이 같은 대시보드에 찬다.
(대시보드 1~4 는 여전히 `job="backend"` 로 걸러 컨테이너 모드 전용이다 — 아래 참고.)

**무너짐 신호 3개** — 상단 판정 패널(stat 4개)이 이 값을 바로 보여준다.
1. **힙 사용률 최대가 85% 를 넘는다** — GC 압박으로 응답이 튀기 시작하는 지점(2026-09-09 부하 한계 측정에서
   실제로 힙 3.8GB 까지 올라간 뒤 프로세스가 죽었다).
2. **5xx 비율이 0 이 아니다** — 정상 회차는 항상 0이다.
3. **Hikari 타임아웃 누적 또는 방송 버림 누적이 오른다** — DB 커넥션 고갈(HikariCP) 또는 팬아웃 큐
   포화(BR-170, Ruling 349 — 위치만 버려지는 게 정상이고 그 외 이벤트가 버려지면 결함이다)로 읽는다.

⚠ 대시보드 1~4(API·서버·데이터·파이프라인)는 `job="backend"` 를 8080(컨테이너) 기준으로 만든 것이라
이 모드(18080·호스트)에서는 값이 비어 보인다 — 부하 시험 중에는 "5. 부하 시험" 하나만 본다.
