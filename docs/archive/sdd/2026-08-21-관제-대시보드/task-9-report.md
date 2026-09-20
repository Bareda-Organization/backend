# Task 9: 전체 검증 + 문서 갱신 — 보고

커밋 해시: `8ee4ee4`(문서 2건 갱신)

## 요약

spec §11 의 검증 항목 8개 중 6개는 실행해 완전히 통과를 확인했고, 2개(§11-1·§11-5)는
환경 제약으로 항목의 절반만 재현하고 나머지 절반은 "미검증"으로 남겼다(실패가 아니라
컨트롤러 지시대로 재기동·프록시 기동을 생략한 결과). §11-2~4 는 컨트롤러가 이미 실행해
확인한 결과를 그대로 인용한다.

## §11-1: 지표 노출 경로 확인

내부 스크레이프 확인:

```
$ docker compose exec -T prometheus wget -qO- --timeout=8 http://backend:8080/actuator/prometheus | head -3
# HELP application_ready_time_seconds Time taken for the application to be ready to service requests
# TYPE application_ready_time_seconds gauge
application_ready_time_seconds{application="school-bus",main_application_class="src.backend.BackendApplication"} 6.439
```

내부망에서는 지표 텍스트가 정상 노출된다.

외부 프록시 경유 확인은 `docker compose ps` 로 먼저 대상을 확인했다:

```
$ docker compose ps
9 services 중 proxy 없음 — backend·grafana·kafka·node-exporter·postgres·postgres-exporter·
prometheus·redis·redis-exporter 만 기동
```

**결과: 로컬에서는 proxy 미기동이라 외부 차단 확인 불가.** 운영 차단은 설정상으로만 확인했다 —
`infra/proxy/nginx.prod.conf:149` 에 `location /actuator { return 404; }` 블록이 존재하고
149행이 `location /actuator {` 선언 행이다(150행이 `return 404;`). 로컬 프록시(`infra/proxy/nginx.conf`)에
같은 차단 규칙이 있는지는 이번 검증 범위에서 제외했다(컨트롤러 지시대로 proxy 를 기동하지 않았음).

## §11-2: 스크레이프 대상 확인 (컨트롤러 확인분 인용)

컨트롤러가 직접 실행해 확인: backend·node·postgres·prometheus·redis 5개 job 전부 `up`.
직접 재실행하지 않았다.

## §11-3: URI cardinality 음성 대조 (컨트롤러 확인분 인용)

컨트롤러가 직접 실행해 확인: 서로 다른 id 로 `/api/buses/{1,2,3}` 호출 후 `uri` 라벨 조회 결과
`/actuator/health` · `/actuator/prometheus` · `/api/auth/login` · `/api/buses/{id}` — 원시 id
노출 0건, 패턴으로 정확히 묶임. 직접 재실행하지 않았다.

## §11-4: 스케줄러 정지 관측 (컨트롤러 확인분 인용, MON-8 완료 조건)

컨트롤러가 직접 실행해 확인: `docker compose stop redis` → 75초 → `start redis` 실험.

```
기준선      connection-loss 2.3s · location-simulation 3.9s
정지 75초 후 connection-loss 14.4s(증가) · location-simulation 26.6s(증가)
복구 후      회복 확인
```

부수 발견 2건(컨트롤러 기록, 인용):
- MON-14 발현 — Redis 지연 시 Tomcat 스레드 소진으로 `/actuator/prometheus` 스크레이프가
  타임아웃돼 backend target 이 `down` 이 되고 전 지표가 조회 불가(`context deadline exceeded`).
  복구 후 자동 회복.
- `schoolbus_scheduler_failures_total` 은 끝까지 0 — 스케줄러가 예외를 자체 흡수해 aspect 의
  catch 가 안 탐(계측 한계로 이미 문서화된 내용의 실측 확인).

직접 재실행하지 않았다.

## §11-5: 버스 위치 수신 정지 관측

증가 확인:

```
$ curl -s --get 'http://localhost:9090/api/v1/query' \
    --data-urlencode 'query=rate(schoolbus_bus_location_reports_total[1m])'
{"status":"success","data":{"resultType":"vector","result":[
  {"metric":{"application":"school-bus","instance":"backend:8080","job":"backend","origin":"MOCK"},
   "value":[1787292818.730,"0.3333333333333333"]}
]}}
```

`origin="MOCK"` 시계열의 1분 rate 가 약 0.33/s(3초당 1건)로, 서버 Mock(`app.location.bus-mock.enabled=true`
기본값)이 도는 동안 계속 증가한다.

**정지 시 거동은 미검증(재기동 필요).** `--app.location.bus-mock.enabled=false` 로 백엔드를
재기동해야 확인 가능한데, 컨트롤러 지시대로 백엔드를 재기동하지 않았다.

## §11-6: 프로비저닝 복원 확인

```
$ docker compose rm -sf grafana && docker compose up -d grafana && sleep 15
$ curl -s -u admin:admin 'http://localhost:3000/api/search?query=' \
    | python3 -c 'import sys,json;[print(d["uid"], d["title"]) for d in json.load(sys.stdin)]'
school-bus-api 1. API 개요
school-bus-server 2. 서버/JVM
school-bus-data 3. 데이터 계층
school-bus-pipeline 4. 파이프라인 생존
```

**대시보드 4장이 손으로 다시 만들지 않고 그대로 복원됐다.** `grafana` 컨테이너만 제거·재기동했고
다른 서비스는 건드리지 않았다.

## §11-7: 메모리 실사용 측정

```
$ docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}' | grep -E 'prometheus|grafana|exporter'
school-bus-grafana-1            86.07MiB / 7.75GiB
school-bus-prometheus-1         78.38MiB / 7.75GiB
school-bus-node-exporter-1      26.52MiB / 7.75GiB
school-bus-redis-exporter-1     21.59MiB / 7.75GiB
school-bus-postgres-exporter-1  20.34MiB / 7.75GiB
```

설계 §3.1 예산과 대조:

| 구성 요소 | 예산 | 실측 | 비고 |
|---|---|---|---|
| Prometheus | ~300MB | 78.38MiB | 예산의 약 26% — 스택 기동 후 시간이 짧아 축적된 시계열·샘플 수가 적기 때문 |
| Grafana | ~200MB | 86.07MiB | 예산의 약 43% — 동시 대시보드 조회 세션이 없어 쿼리 캐시·렌더링 버퍼가 비어 있기 때문 |
| exporter 3종 합계 | ~55MB | 68.45MiB(26.52+21.59+20.34) | **예산을 약 13MB(24%) 초과.** exporter 메모리는 수집 데이터량이 아니라 Go 런타임·HTTP 서버 등 고정 오버헤드가 대부분을 차지해, Prometheus·Grafana 처럼 데이터가 적을 때 낮게 나오는 효과가 적용되지 않는다 |

전체 합계는 232.9MiB(≈0.23GB)로 총 예산 ~550MB 대비 여전히 크게 낮다. **exporter 초과분을
"예산이 과했다"로 해석하지 않는다** — Prometheus·Grafana 저사용의 원인(시계열 축적 부족)과
exporter 초과의 원인(고정 오버헤드)은 서로 다른 메커니즘이다.

## §11-8: MeterBinder 등록 확인

```
$ for m in tomcat_threads_busy_threads hikaricp_connections_active jvm_threads_states_threads \
           jvm_gc_pause_seconds_sum process_files_open_files; do ... done
tomcat_threads_busy_threads → 1 건
hikaricp_connections_active → 1 건
jvm_threads_states_threads → 6 건
jvm_gc_pause_seconds_sum → 2 건
process_files_open_files → 1 건
```

5개 지표 전부 1건 이상 반환. 부하를 걸어 값이 실제로 움직이는지도 확인했다 — 컨테이너
내부에서 `/api/auth/login` 을 20회 호출한 뒤 `http_server_requests_seconds_count{uri="/api/auth/login",status="200"}`
값을 연속 조회하면 6 → 26 으로 증가한다(스크레이프 주기 경과에 따라 순차 반영). 요청 전
`hikaricp_connections_active` 는 0, 부하 중 `tomcat_threads_busy_threads` 는 1로 확인돼
지표가 정적 등록이 아니라 실제 요청에 반응함을 확인했다.

## Step 8b: 무침투 원칙 최종 확인 (전 브랜치 기준)

```
$ git diff --name-only 26111d6..HEAD -- backend/src/main/java/src/backend \
    | grep -v '^backend/src/main/java/src/backend/observability/' || echo "도메인 수정 없음"
backend/src/main/java/src/backend/global/security/SecurityConfig.java
```

`SecurityConfig.java` 1건만 나왔다. 이는 컨트롤러가 사전에 명시한 **의도된 예외**다 —
`/actuator/prometheus` 를 `permitAll` 로 여는 변경(MON-11 완료 조건)이며, 실제 도메인 로직
(버스·학생·인증 판단 등)은 손대지 않았다. 그 외 도메인 파일은 나오지 않았다.

## Step 9: 계획서 진행 추적 갱신

`backend/docs/plans/2026-08-21-모니터링-구현계획.md` §4 를 갱신했다:

- **MON-8** — `✅ 완료` · 커밋 `a8f5dd5`(계측) · `0224f90`(리뷰 반영) · 검증 요약(§11-4 인용) + 부수 발견 2건 기록
- **MON-9** — `✅ 완료` · 커밋 `a184486`
- **MON-10** — `✅ 완료` · 커밋 `2123bc3`
- **MON-11** — `✅ 완료` · 커밋 `2123bc3`(actuator/prometheus 노출) · `e929383`(Tomcat MBean) ·
  `c1b9f4b`(Prometheus+exporter 3종 기동) · `3c3709d`(Grafana 기동+프로비저닝) ·
  `6af4702`·`b6afee1`·`0b34367`·`a30a789`(대시보드 4장). 전송 대상 판단 결과를 **Prometheus**로
  확정 기록(EC2 1대 내 컨테이너 추가, 실측 메모리 합계 232.9MiB로 여유 확인)
- **MON-13**·**MON-12** 는 `⬜ 예정` 그대로 유지 — 완료로 표기하지 않았다

## Step 10: 아키텍처 문서 갱신

`docs/ARCHITECTURE.md` 인프라 표에 행 추가:

```
| 관측 | Prometheus 3 + Grafana 11 | 지표 수집·대시보드 4장. `/actuator/prometheus` 를 내부망에서만 스크레이프 | `9090:9090` · `3000:3000` |
```

`docs/DEPLOYMENT.md:591` 의 "알람·APM 부재 · 장애 인지는 수동" 서술은 **고치지 않았다** —
확인 결과 그 줄은 그대로 있고("| 알람·APM 부재 | CloudWatch Logs 만 수집. 장애 인지는 수동 |"),
이번 작업으로 바뀐 것은 조회 수단의 유무뿐이며 알람은 여전히 부재이기 때문이다.

## Step 11: 커밋

```
$ git add backend/docs/plans/2026-08-21-모니터링-구현계획.md docs/ARCHITECTURE.md
$ git commit -m "docs: 관제 대시보드 구축 반영 — MON-8·9·10·11 완료 (알람 MON-13 은 미착수)"
[feat/mvp-deployment 8ee4ee4] ...
 2 files changed, 10 insertions(+), 5 deletions(-)
```

## 검증 항목 통과/미검증 집계

| 항목 | 상태 |
|---|---|
| §11-1 지표 노출 경로 | 부분 — 내부 통과, 외부(프록시) 미검증(proxy 미기동) |
| §11-2 스크레이프 대상 | 통과(컨트롤러 확인분 인용) |
| §11-3 cardinality 음성 대조 | 통과(컨트롤러 확인분 인용) |
| §11-4 스케줄러 정지 관측 | 통과(컨트롤러 확인분 인용) |
| §11-5 위치 수신 정지 관측 | 부분 — 증가 확인 통과, 정지 시 거동 미검증(재기동 필요) |
| §11-6 프로비저닝 복원 | 통과 |
| §11-7 메모리 실사용 | 통과(exporter 예산 초과 사실대로 기록) |
| §11-8 MeterBinder 등록 | 통과 |

8개 중 6개 완전 통과, 2개는 절반 통과 + 절반 미검증(환경 제약에 따른 의도된 생략, 실패 아님).

## 수정 라운드 1 — MON-10 완료 표기 정정

리뷰에서 나머지 전부(재현 결과·미검증 표기·ARCHITECTURE 갱신·무침투 확인)는 정확하다는 판정을
받았고, **MON-10 완료 표기 1건**만 근거 없음으로 지적됐다.

**문제.** §4 에 `MON-10 ✅ 완료 · 커밋 2123bc3` 로 적었으나, 커밋 `2123bc3` 이 실제로 한 일은
`exposure.include: health → health,prometheus`(prometheus 엔드포인트 추가) 뿐이었다. MON-10 의
원래 정의(162행) — `metrics`·`info` 추가 노출 + `show-details` 인증 사용자 한정 완화 — 는
어느 것도 처리되지 않았다. 커밋 메시지 자체도 `(MON-11)` 로 스스로를 표시하고 있어, Task 9
브리프가 "MON-10(Task 1)"으로 매핑한 근거가 없었다.

**정정 방향.** 단순히 "예정"으로 되돌리지 않았다 — `show-details` 인증 완화는
`docs/superpowers/specs/2026-08-21-관제-대시보드-design.md` §3.2 에서 이미 **채택하지 않기로
확정**된 항목이기 때문이다(`application.yml:91~92` 의 판단 — 상세 노출 시 DB URL·Redis 호스트가
인증 없이 노출 — 이 여전히 유효). 이걸 "예정"으로 두면 다음 세션이 남은 할 일로 오인한다.

**반영한 내용:**
- §2 MON-10 항목(162행 부근) — 표제에 "범위 축소 확정(2026-08-21, Task 9)" 추가, 원래 범위·확정
  결과(폐기 사유 명시)·실제 진행(prometheus 노출만·MON-11 요구분)을 구분해 서술
- §4 체크박스(233행 부근) — `✅ 완료` 를 걷어내고 `🟡 범위 축소` 로 변경, `prometheus` 노출은
  완료(MON-11 요구로 처리) · `metrics`·`info` 추가 노출은 미착수 · `show-details` 완화는 폐기로
  하위 항목을 나눠 기록

**손대지 않은 것:**
- MON-8·MON-9·MON-11 매핑 — 리뷰어가 `git show --stat` 대조로 전부 정확하다고 판정, 수정 없음
- §11-7 메모리 예산표 — exporter 68.45MiB 초과를 이유로 설계 문서를 고치지 않음. 리뷰어 판정대로
  기동 직후 단일 측정값으로 예산을 고치면 시계열이 쌓인 뒤 다시 어긋난다. 다만 여기 단서를 남긴다:
  **지금 총합이 낮다는 것이 향후에도 안전하다는 뜻은 아니다** — exporter 오버헤드는 데이터량과
  무관하게 고정이라 줄어들 일이 없는 반면, Prometheus·Grafana 는 시계열이 누적될수록 늘어나는
  쪽이라 배포 후 재측정이 적절한 트리거다.

**커밋:** `d874735` — `backend/docs/plans/2026-08-21-모니터링-구현계획.md` 1개 파일만.
