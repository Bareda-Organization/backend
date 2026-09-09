# F3 부하 시험 (좌석 L, 목표 12·13·14)

`IMPLEMENTATION_PLAN.md §5` 가 정한 4개 시나리오를 이 디렉터리 하나로 실행한다. 정본은 그 문서이고
여기는 실행 방법만 적는다 — 판정·근거는 `report-f3-l.md` §3 을 본다.

## 0. 사전 조건

- 백엔드가 `SPRING_PROFILES_ACTIVE=load` 로 포트 `18080` 에 떠 있을 것(`application-load.yml`)
- postgres `schoolbus_load`(15432) · redis(16379) · kafka(29092) 컨테이너가 떠 있을 것
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
