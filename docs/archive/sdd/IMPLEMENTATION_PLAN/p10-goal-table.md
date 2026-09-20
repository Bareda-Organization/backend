# Phase 10 완료 조건 — 목표 표 (착수 전 고정)

출처: `docs/IMPLEMENTATION_PLAN.md` Phase 10 절 완료 조건 **14항**
(직접 계수 — `awk '/^### Phase 10/,/^### Phase 11/' docs/IMPLEMENTATION_PLAN.md | grep -c '^- '` → `14`)
\+ Phase 9 이월 **5건** + 리뷰 미부착 **2건** = **목표 21항**.

작성 2026-08-31, Phase 9 마감 직후. 브랜치 `feat/baraeda-rebuild` · 분기점 **`a206e9b`**.
착수 전 판정 **Ruling 207~211** 을 전제로 쓰였다. **표만 읽고 착수하지 마라** — 원장 Phase 10 절을 함께 읽는다.

⚠ **이 표의 수치는 인용이다.** 판정 직전에 정본에서 다시 센다(횡단 규칙 24).

---

## 분기점 실측값 (2026-08-31, 조율자 직접 계수)

| 항목 | 값 | 세는 법 |
|---|:-:|---|
| 프로덕션 핸들러 | **75** | `grep -rE '@(Get\|Post\|Put\|Patch\|Delete)Mapping' --include='*.java' src/main/java \| wc -l` |
| 테스트 클래스 파일 | **161** | `find src/test/java -name '*Test.java' \| wc -l` |
| 테이블 | **40** | `V1__init_schema.sql` 의 `CREATE TABLE` |
| 작업 트리 | 깨끗 | `git status --porcelain` 빈 결과 |

⚠ **원장 Phase 9 비고는 테스트 클래스를 `160` 으로 적는다** — 그쪽은 **결과 XML 기준**이고 위는 **파일명 기준**이다. 계수 방법이 다르므로 어느 쪽이 틀린 것이 아니다. **판정 시 같은 방법으로 다시 센다.**

## ⚠ 이월 "부재" 주장 재계수 — 2건이 낡아 있었다

`phase-goal-loop §6.3` — 낡은 "없음" 은 판정을 틀리게 하는 데 그치지 않고 **이미 있는 것을 새로 만들게** 한다.

| 원장의 주장 | 실측 | 좌석이 할 일 |
|---|---|---|
| 산출물 `location` 모듈 | `location/entity/RunPosition.java` **이미 존재** (Phase 1 산출물, 파티션·이중 시계 주석 완비) | **엔티티를 새로 만들지 마라.** 저장소·서비스·컨트롤러만 신설 |
| 산출물 "Redis 최신 좌표" | `RedisConfig.java` + `spring-boot-starter-data-redis` + yml 3프로파일 **이미 배선** | 인프라를 다시 깔지 마라. **키 설계와 접근 계층만** |
| Ruling 87 — STOMP 계정 상태 게이트 부재 | ✅ **유효** — CONNECT 가 토큰 유효성·`isAccessToken` 만 보고 계정 상태 미판정 | 게이트를 신설 |
| Ruling 121 — `/topic/tenant/{id}` 옛 어휘 | ✅ **유효** — `StompAuthChannelInterceptor` 에 정규식 실재 | `academy` 로 교체 |
| Ruling 195 — `approval_requested` WS 방송 | ✅ **유효** — 엔드포인트가 `/ws/location` 하나뿐이라 방송 대상 부재 | 채널 신설 후 방송 |
| 이월 ② — 잘못된 `scope` 값 처리 미확인 | ✅ **유효** — `NavigationControllerTest` 8시험에 해당 갈래 부재 | 시험 신설 |
| `GET /students/{id}/bus-position` (LOC-02) | ✅ **미구현** — 클래스 레벨 매핑 전수 확인 | 신설 |
| `GET /students/{id}/route` (LOC-03) | ✅ **미구현** — 같은 방법으로 확인 | 신설 |

---

## 좌석 간 공유 계약 — 조율자가 고정한다

⚠ **아래 값이 바뀌면 조율자가 전 좌석에 정정을 보낸다**(`parallel-agents-git.md §16`). 좌석이 임의로 바꾸지 않는다.

| 계약 | 값 | 소유 좌석 | 소비 좌석 |
|---|---|:-:|:-:|
| Redis 최신 좌표 키 | `run:{runId}:position` | T1 | T3 · T4 |
| Redis 값 형식 | JSON — `lat` · `lng` · `recordedAt` · `receivedAt` · `currentStopName` | T1 | T3 · T4 |
| Redis TTL | **30분** — 운행 종료 후 자연 소멸. 이력은 `run_position` 이 갖는다 | T1 | T3 · T4 |
| 위치 수신 도메인 이벤트 | `RunPositionReceivedEvent(runId, lat, lng, recordedAt, receivedAt)` | T1 | T2 · T3 |
| STOMP 구독 목적지 4종 | `/topic/students/{studentId}/run` · `/topic/manager/runs/{runId}` · `/topic/academy/{academyId}/live` · `/topic/admin/live` | T2 | T2 |
| 연결 엔드포인트 | `/ws/location` **단일** (신설 부재) | T2 | — |
| 근접 판정 상한 | **300m** 코드 상수 (횡단 규칙 10) | T3 | — |
| 신호 유실 판정 | **2분** 코드 상수 | T4 | — |

⚠ **이벤트 생성자 인자 순서** — Phase 8 에서 `Long` 두 개의 순서가 뒤바뀌어 **컴파일은 되고 수신자 조회가 0건**이 된 사고가 있었다. 좌석은 생성자를 위 순서 그대로 쓰고, **인자 순서를 검사하는 시험을 함께 만든다.**

### Redis 테스트 베이스 — **`testsupport.redis.RedisTestContainerBase` 로 확정** (2026-08-31)

T1·T4 가 같은 목적의 베이스를 각자 만들어 두 벌이 됐다. **동작은 동일**하고(`@Testcontainers` + static `GenericContainer` + `@DynamicPropertySource` 로 `spring.data.redis.host`·`port` 등록) **차이는 이미지 태그와 자바독뿐**이다.

| | T1 `RedisTestContainerBase` | T4 `IsolatedRedisTestBase` |
|---|---|---|
| 상속하는 시험 | **1건** (`RunPositionRedisIntegrationTest`, 통과 실측) | **0건** |
| 이미지 | `redis:7-alpine` | `redis:7` |

**판정 — 클래스는 T1 것을 남기고, 이미지는 T4 의 `redis:7` 을 채택한다.**

- **T1 것을 남기는 이유** — 이미 통과가 실측된 시험이 상속 중이다. T4 판은 아직 소비자가 없어 **바꾸는 비용이 0 에 가깝다**
- **이미지를 `redis:7` 로 하는 이유** — `docker-compose.yml:36` 과 같은 태그다. 시험과 운영이 다른 이미지를 쓰면 **버전 차이로 나는 결함을 시험이 못 본다**. T4 의 근거가 옳다
- **자바독은 두 근거를 합친다** — T1 의 *"Testcontainers 2.0.5 BOM 에 Redis 모듈 부재"* + T4 의 *"Postgres 는 `@Transactional` 롤백으로 격리되지만 Redis 는 트랜잭션이 없어 키가 남는다"*. 뒤엣것이 **이 베이스가 존재하는 진짜 이유**라 반드시 남긴다

⚠ **소유 배정 경위 정정** — 조율자가 "Redis 테스트 지원은 T4 소유" 라고 메시지로 배정했으나 **T4 지시서에는 그런 문면이 없었고**, T1 이 그것을 지적했다. 지시서에 없는 배정을 메시지로만 보내면 받는 쪽이 근거를 대조할 수단이 부재하다. **~~T4 소유~~ → 위 판정으로 대체.**

---

## 목표 21항 — 정본 항목 1:1 매핑

⚠ **정본 완료 조건 14항이 목표 1~14 와 1:1 로 대응한다**(`phase-goal-loop §6.2` — 총량이 아니라 항목을 대조했다). 15~21 은 Phase 9 이월과 리뷰 미부착분이다.

| # | 목표 — 무엇이 통과하면 끝인가 | 정본 | 좌석 |
|:-:|---|---|:-:|
| **1** | `moving` 회차에 `POST /runs/{runId}/position` 이 **`204`** | `§4.12` · LOC-01 | T1 |
| **2** | `idle`·`finished` 회차의 같은 호출이 **`409 RUN_NOT_MOVING`** | `§4.12` · `§8.4` | T1 |
| **3** | 한 번의 송신으로 **Redis 최신 좌표 갱신**과 **`run_position` 행 적재**가 **함께** 일어남 — 한쪽만 되는 상태가 부재 | `ARCH §10.1` | T1 |
| **4** | 채널 4종 방송 페이로드가 `§7.1` 과 일치. **`approval_requested` 포함** | `§7.1` · Ruling 195 | T2 |
| **5** | 학부모·학생 채널 구독자가 **`rider_changed` 를 수신하지 않음** | `C-08` · `§1.12` | T2 |
| **6** | **관제 채널(`academy`·`admin`)만 `eta` 포함** — 학부모·학생 채널 페이로드에 `eta` 키 부재 | `§7.1` · `C-08` | T2 |
| **7** | 권한 밖 채널 구독 시 **연결 종료 코드 `4403`** | `§7` | T2 |
| **8** | **`pending` 토큰의 CONNECT 와 SUBSCRIBE 가 거부** — `AUTH_PENDING` | Ruling 87 이월 | T2 |
| **9** | 마지막 수신 후 **2분** 경과 시 `bus-position` 응답이 `lat`·`lng` 부재 + `last_seen_at` 반환 | `§3.11` · Ruling 208 | T4 |
| **10** | 승하차 상태 변경이 관계자 채널에 **5초 이내** 반영 | `NFR-02` | T2 |
| **11** | `GET /students/{id}/bus-position` 이 LOC-02 응답 반환 · 연결 부재 자녀는 **`403`** | `§3.11` | T4 |
| **12** | `GET /students/{id}/route` 가 **승차지 이전 2개 · 승차지 · 하차지만** 반환 | `§3.10` · `P-08` | T4 |
| **13** | 다음 미도착 승하차지 **300m 진입 시 1회 발송** · **재진입에 재발송 부재** | `§4.12` · Ruling 207 | T3 |
| **14** | 전체 테스트 묶음 **단독 실행** 실패 0 (동시성 시험의 부하 의존 실패는 별도 분류) | 횡단 규칙 22 | 조율자 |
| **15** | 근접 판정 스케줄러가 **인스턴스 2개에서 1회만** 발송 — 조건부 UPDATE 선점 | **Ruling 212**(210 폐기) | T3 |

⚠ **목표 15 의 "1회" 는 *발송* 기준이다.** Ruling 212 로 210 이 폐기되면서 **중복 *수행* 방지(ShedLock)는 Phase 11 로 빠졌다.**
조건부 UPDATE 선점이 막는 것은 두 인스턴스가 같은 승하차지를 **두 번 발송**하는 것이고, 두 인스턴스가 같은 판정을
**각자 수행**하는 것은 막지 않는다. R3 리뷰가 결함 주입으로 실측했다 — 그 가드를 없애면 두 스레드 모두 선점에
성공해 갱신 수가 2가 된다. 즉 이 한 줄이 지금 유일한 방어선이며, 그 위에 `NotificationOutbox` 의 `dedup_key`
유일성 제약이 이중 방어선으로 작동한다(정본에 명시되지 않았으나 실측으로 확인).
| **16** | `NavigationControllerTest` 에 **잘못된 `scope` 값** 갈래가 실재하고 그 처리를 검사 | P9 이월 ② | T4 |
| **17** | `§4.2`·`§5.4` 두 명단이 공유하는 헬퍼에 변형을 심으면 **양쪽 시험이 함께 실패** | P9 이월 ③ | T4 |
| **18** | **운행 시작 시 노선 잠금**을 명시적으로 검사하는 시험이 실재 — 잠금을 푸는 변형에 그 시험이 실패 | P9 이월 ④ | T1 |
| **19** | 동시성 시험 3종의 `TimeoutException` 에 **순서를 정할 수단**을 넣어 전체 실행에서도 통과 | P9 이월 ① | T1 |
| **20** | P9 T2 수정 라운드 3 **재리뷰 미부착** 판정 — 그 결정이 옳았는지 | P9 이월 ⑤ | 게이트 리뷰 |
| **21** | `968bc9f`(조율자 직접 편집, 학원 조건 부모 조인) 리뷰 판정 | `§10` 사각지대 | 게이트 리뷰 |

---

## 좌석 분할 — 4개 병렬

파일 소유가 겹치지 않도록 갈랐다. **겹치는 파일이 있으면 좌석이 아니라 조율자가 병합한다.**

| 좌석 | 범위 | 목표 | 주 산출 파일 |
|:-:|---|---|---|
| **T1** | 위치 수신·저장 (LOC-01) | 1·2·3 · 18 · 19 | `location/` 저장소·서비스·컨트롤러 · `run_position` 접근 |
| **T2** | WebSocket 채널 — 인가 + 방송 | 4·5·6·7·8 · 10 | `global/config/WebSocketConfig` · `global/security/StompAuthChannelInterceptor` · 방송 발행자 |
| **T3** | 근접 알림 (NTF-04) | 13 · 15 | `location/proximity/` · 스케줄러 · `notification` 이벤트 발행 |
| **T4** | 학부모 조회 (LOC-02·03) | 9 · 11 · 12 · 16 · 17 | `student/controller/` 신설 2개 · 조회 서비스 |

**의존** — T3·T4 가 T1 의 Redis 키를 소비한다. 값은 위 공유 계약 표에 고정돼 있으므로 **T1 완료를 기다리지 않고 병렬로 간다.**

⚠ **T2 가 가장 크다.** 인가와 방송을 한 좌석에 둔 이유는 둘이 같은 파일(`StompAuthChannelInterceptor`·`WebSocketConfig`)을 만지기 때문이다. 가르면 **git 인덱스가 아니라 소스가 충돌**한다.

---

## 좌석 공통 규칙

1. **분기점 확인** — 착수 전 `git log --oneline -1` 이 `a206e9b` 인지 본다. 다르면 즉시 `BLOCKED` 로 보고
2. **`git add` 는 파일 경로로.** `-A` · `.` 금지
3. **서브에이전트를 띄우지 마라** — 리뷰는 조율자가 붙인다
4. **음성 대조는 커밋 후에** — 변형 1회 = 원복 1회 = `git status --porcelain` 확인 1회를 묶어서 돈다
5. **보고서는 4항만** — ①판단 근거(고른 길과 버린 길) ②우려·확신 없는 지점 ③실측 3줄(커밋 해시·실패 **클래스 이름**·테스트 수) ④심은 변형 목록
6. **인프라 부재 실패는 환경 문제로 분류**해 코드 결함과 구분한다
7. **`find` 로 파일을 찾지 말고 절대 경로를 `cat` 하라** — 접힌 출력을 근거로 "없다" 를 결론 내지 마라
8. **파일 쓰기가 거부되면 전문을 메시지로 보내라** — 조율자가 저장한다
