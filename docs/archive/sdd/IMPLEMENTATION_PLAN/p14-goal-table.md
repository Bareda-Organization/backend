# Phase 14 목표 표 — 감사 · 보존 정리 · 운영 게이트

**작성 2026-09-03 · 착수 전 고정.** 분기점은 `feat/baraeda-rebuild` HEAD **`08e7878`**(Phase 13 완료 + X-08 정정). 착수 시 HEAD 가 다르면 이 표의 분기점을 먼저 갱신한다.

⚠ **이 표는 파생본이다.** 정본은 `docs/IMPLEMENTATION_PLAN.md` Phase 14 절 · `docs/API_SPEC.md §6.13` · `docs/FEATURE_SPEC.md §4.17`·`§6.3` · `docs/ERD.md §7` · `docs/TECH_DECISIONS.md §12.1`·`§13.1`·`§14.3`. **어긋나면 정본이 이긴다.** 아래 수치는 조율자가 정본·코드에서 직접 센 값이니 착수 시 다시 세어 대조하고 어긋나면 보고하라.

⚠ **`docs/` 는 git 추적 밖이다.** 워크트리에 따라오지 않으므로 정본은 메인 저장소 절대 경로 `/Users/mskim/Desktop/PJ/School-Bus/docs/` 로 `cat` 하라. `find` 로 찾지 마라.

---

## 0. ⚠ 착수 전 재계수 — 조율자가 이미 확인했다. **없는 것을 새로 만들지 마라**

### 🔴 이미 **있는** 것 — 다시 만들지 마라

| 대상 | 상태 |
|---|---|
| **`audit_log` 테이블·엔티티·열거형** | ✅ `V1` DDL(컬럼 12개, `category` CHECK `data_access`·`login`, `action` CHECK 7종) · `audit/entity/AuditLog`·`AuditAction`(READ·UPDATE·DELETE·LOGIN_SUCCESS·LOGIN_FAIL·BLOCK·UNBLOCK)·`AuditCategory` · `audit/repository/AuditLogRepository`. **쓰는 곳은 `AccountUnblockCommandService`(UNBLOCK) 하나뿐** — 나머지 6종 action 은 값만 있고 기록 경로가 부재 |
| **권한 `AUDIT_READ`** | ✅ `Permissions:136` · `RolePermissions:116`(메인 관리자). **메타 애너테이션은 부재**(`authz/Can*.java` 31개 중 audit 없음) → `@CanReadAudit` 신설(T1) |
| **로그인 실패 5회 차단** | ✅ `LoginCommandService` + `Account.recordLoginFailure`·`assertNotBlocked`. **감사 행은 미기록**(`grep -c audit LoginCommandService.java` = 0) |
| **actuator + prometheus** | ✅ `build.gradle:59·62` · `application.yml:205~212` `exposure.include: health,prometheus`(외부 도달 불가 — nginx 뒤) |
| **지표 5종** | ✅ `observability/` 6파일 — `schoolbus.run.confirmation.lag`(확정 배치 지연) · `schoolbus.scheduler.failures` · `schoolbus.scheduler.last.success.age` · `schoolbus.stomp.sessions` · `schoolbus.kafka.consume.failures` |
| **ShedLock** | ✅ `V4 shedlock` 테이블 · `ShedLockConfig` · `@SchedulerLock` **11곳** · `SchedulerLockConventionTest`(면제 목록 `hasSize` 고정, `fb9f06d`) |
| **resilience4j** | ✅ `resilience4j-spring-boot3:2.3.0` + `application.yml:79~` 서킷 설정. micrometer 가 classpath 에 있으면 `resilience4j.circuitbreaker.*` 지표가 **자동 등록**된다 — T3 이 `/actuator/prometheus` 실측으로 확인 |
| **배포 파이프라인** | ✅ `.github/workflows/deploy-backend.yml`(테스트 → ECR → S3 → SSM Send Command → EC2 `/opt/school-bus/infra/scripts/deploy.sh <SHA>`). 저장소의 `infra/scripts/deploy.sh` 가 그 원본 — T3 이 착수 시 `ls infra/scripts/` 로 실재 확인 |
| **장애 대응 문서** | ✅ `docs/DEPLOYMENT.md §8`(증상별 확인 7행) · `TECH_DECISIONS §14.2`(실패 모드 7행)·`§14.3`(수동 개입 4행) |
| **보존 기간 정본** | `ERD §7.2` — `notification_log` **14일 확정** · `rider_status_history`·사건 테이블 무기한 · **`audit_log` 미확정** · **`run_position` 미확정(법정 검토 대기)**. `§7.1` — `link_request`·`link_code`·`refresh_token` 만료분 hard delete 배치 대상 |
| **인덱스** | ✅ `ix_run_position_run_recorded (run_id, recorded_at)` · `ix_notification_log_academy_sent (academy_id, sent_at)` · `ix_audit_log_academy_occurred`. ⚠ **보존 정리의 삭제 술어(`recorded_at < :cutoff` 단독)에 맞는 인덱스는 부재** → T2 가 `V8` 로 추가 가능(§0 끝 참고) |

```bash
# 착수 시 직접 대조하라
grep -c 'audit' backend/src/main/java/src/backend/account/command/LoginCommandService.java        # 0
grep -rl 'auditLogRepository' backend/src/main | wc -l                                              # 1 (Unblock 뿐)
grep -rhoE 'registry\.(counter|gauge|timer)\("[^"]+"|Gauge\.builder\("[^"]+"' backend/src/main | sort -u   # 5종
grep -rn '@SchedulerLock' backend/src/main | wc -l                                                   # 11
ls backend/src/main/java/src/backend/global/security/authz/ | grep -ci audit                         # 0
```

### 부재한 것 — 이번에 만든다

- **감사 기록 경로 6종** — L3 조회(READ) · 로그인 성공·실패·차단(LOGIN_SUCCESS·LOGIN_FAIL·BLOCK). UNBLOCK 은 있다
- **핸들러 2개** — `§6.13 GET /admin/audit-logs` · `GET /admin/login-history` (98 → **100**)
- **`@CanReadAudit`** 메타 애너테이션
- **보존 정리 스케줄러** — `notification_log`·`run_position`·`refresh_token`·`link_code`·`link_request` (`@Scheduled` + `@SchedulerLock`, 11 → **12**)
- **지표 5종 신설** — 미확정 회차 수(gauge) · 알림 발송 실패(counter) · WS 발행 지연(timer) · ②구간 자동 거절(counter) · 미승차 에스컬레이션(counter). 지도 API 지표는 resilience4j 자동 등록 실측으로 갈음(부재하면 신설)
- **배포 게이트 스크립트** — `infra/scripts/deploy-gate.sh` + `deploy.sh` 호출 + 워크플로 연결
- **런북** — `docs/DEPLOYMENT.md §8` 확장(실패 모드별 수동 개입 경로, `TECH_DECISIONS §14.3` 표를 절차로)

⚠ **마이그레이션 — `V8` 하나, T2 소유, 내용은 인덱스만**(`run_position(recorded_at)` · `notification_log(sent_at)` 또는 `created_at` · `refresh_token(revoked_at/expires_at)` 등 삭제 술어용). 컬럼·테이블 변경이 필요하면 **만들기 전에 보고**. T1·T3 은 마이그레이션을 만들지 않는다.

---

## 1. 완료 조건 — **11항.** 정본 6항 + 재계수 신설 4항 + 전체 실측 1항 (Ruling 241)

| # | 무엇이 통과하면 끝인가 | 근거 | 좌석 |
|:-:|---|---|:-:|
| **1** | **L3 필드를 원문으로 싣는 응답 ~~5종~~ 4종**(`StudentDetailResponse`·`StaffRosterItemResponse`·`AdminRunRosterResponse`·`ManagerRosterResponse` — ~~`ChildListResponse`~~ 는 L1 만 실어 제외, Ruling 247)을 실제 HTTP 로 호출하면 `audit_log` 에 `category=data_access`·`action=read` 행이 **요청 1건당 1행** 생성 — `actor_account_id`·`academy_id`·`target_type`·`target_id`·`detail.student_ids`·`detail.fields` 채움 (Ruling 242) | `SYS-01` · `FEATURE_SPEC §6.3` L3 · `ERD audit_log` | T1 |
| **2** | 로그인 성공 → `login_success`, 비밀번호 불일치 → `login_fail`, 5회 도달 → `login_fail` + `block`(`block_event=true`) 행. `ip` 채움. 차단 해제(기존 UNBLOCK) 그대로 | `SYS-02` · `C-11` · `AuditAction` | T1 |
| **3** | `GET /admin/audit-logs` — 응답 항목 `actor`·`action`·`target_type`·`target_id`·`academy_name`·`occurred_at`, 쿼리 `academy_id`·`account_id`·`from`·`to`·페이징, `404 ACADEMY_NOT_FOUND`·`404 ACCOUNT_NOT_FOUND`. 메인 관리자만(`@CanReadAudit`), 관계자는 `403` | `§6.13` · `O-04` | T1 |
| **4** | `GET /admin/login-history` — `account_id`·`login_id`·`result`(`success`·`fail`)·`ip`·`occurred_at`·`block_event`, 같은 쿼리·에러 규칙. `category=login` 만 반환 | `§6.13` | T1 |
| **5** | 게이트 — `AccountStatusGateEndpoints` 문자열 총계 **90**(88+2) · `AuthFlowIntegrationTest` `hasSize(100)` · `.as(...)` 에 Phase 14 절 · `ControllerAuthorizationConventionTest` 면제 목록 **불변**(`hasSize(5)`) | Phase 9~13 연속 누락 자리 | T1 |
| **6** | 보존 정리 스케줄러가 돌면 `notification_log` **14일** 초과 행 · `run_position` **90일** 초과 행 · `refresh_token` 만료·폐기 **30일** 초과 행 · `link_code`·`link_request` 만료 행이 삭제되고, 기한 안 행은 남는다. `audit_log`·`rider_status_history`·사건 테이블은 **건드리지 않는다**. 보유 일수는 **코드 상수 한 클래스**(`RetentionPolicy`)에 두고 yml 로 빼지 않는다(`TECH_DECISIONS §12.2`) (Ruling 243 — 90·30 **2026-09-04 사용자 확정**) | `ERD §7.1·7.2` · `ARCHITECTURE §13.2` | T2 |
| **7** | 그 스케줄러에 `@SchedulerLock` 이 있고 `SchedulerLockConventionTest` 면제 목록 **불변**. 삭제는 회차(배치) 단위로 나눠 한 트랜잭션이 상한(예: 5,000행)을 넘지 않는다 — 실제로 상한보다 많은 행을 심어 2회 이상 나눠 지워지는 것을 시험으로 | `TECH_DECISIONS §12.1`(인스턴스 1 전제)·`§13.2` | T2 |
| **8** | `GET /actuator/prometheus` 응답에 `TECH_DECISIONS §13.1` 표 8행이 **전부** 이름으로 존재 — 기존 5종 + 신설(`schoolbus.run.unconfirmed`(gauge) · `schoolbus.notification.push.failures` · `schoolbus.websocket.publish.latency` · `schoolbus.change_request.auto_rejected` · `schoolbus.no_show.escalated`) + 지도 API(`resilience4j_circuitbreaker_*` 실측 또는 신설). **counter 는 기동 시 0 으로 선등록**해 발생 전에도 이름이 노출 | `§13.1` · `ARCHITECTURE §13.3` | T3 |
| **9** | `infra/scripts/deploy-gate.sh` 가 DB 에 `run.status='moving'` 행이 하나라도 있으면 **0 이 아닌 코드로 종료**하고 사유를 출력, 없으면 0. `deploy.sh` 가 이미지 pull **앞**에서 그 스크립트를 부르고 실패 시 중단. 로컬에서 `-PtestDbUrl` DB 에 moving 회차를 심어 스크립트 자체를 실증(시험 또는 실행 기록) | `§12.1` · Phase 14 완료 조건 5 | T3 |
| **10** | `docs/DEPLOYMENT.md §8` 에 실패 모드별 수동 개입 경로 — `TECH_DECISIONS §14.2` 7행·`§14.3` 4행을 **증상 → 확인 명령 → 조치 → 남길 것** 절차로. 강제 확정은 **API 부재(오픈 이슈 Y)** 라 "현재 수단 없음 — Y 판정 대기" 로 적는다 | Phase 14 완료 조건 6 · Ruling 244 | T3 |
| **11** | **전체 실측** — 좌석 0 · 새 DB · Redis 16379 · `--rerun`. 기준값 Phase 13: 199클래스 1105테스트 실패 3(전부 부하·환경 의존, 단독 통과) | 전 Phase 관례 | 조율자 |

**"무엇이 깨지면 거짓이 되는가"** 를 항마다 답할 수 있어야 한다 — 그것이 §3 심을 변형 목록이다.

---

## 2. 신설 핸들러 — **2개 (98 → 100).** 정본 `§6.13` 에서 직접 셈

| 메서드 · 경로 | 권한 | 좌석 | 게이트 |
|---|---|:-:|:-:|
| `GET /admin/audit-logs` | `AUDIT_READ`(메인 관리자) | T1 | 거부측 +1 |
| `GET /admin/login-history` | `AUDIT_READ` | T1 | 거부측 +1 |

T2·T3 은 핸들러를 만들지 않는다(게이트 미변경).

---

## 3. ⚠ 이 Phase 에서 가장 의심스러운 지점

### 3.1 🔴 감사 기록의 **단위**와 **위치** — 목표 1 의 본체 (Ruling 242, 잠정)

`SYS-01` 문면은 *"누가 · 언제 · 어느 학생의 어떤 등급"* 이다. 명단 응답 한 번에 학생 30명이 실리므로 **학생마다 1행**이면 명단 조회 1회 = 30행 — 하루 수천 행. 조율자 잠정 판정: **요청 1건 = 1행**, `target_type` = 자원 종류(`student`·`run_roster`·`child_list`), `target_id` = 학생 id 또는 회차 id, `detail` jsonb = `{"student_ids":[…], "fields":["photo_url","address","note","guardian_phone"]}`. "어느 학생" 은 `detail.student_ids` 로 답한다.

**위치** — 컨트롤러가 아니라 **응답을 조립하는 서비스**에서 기록한다(응답에 L3 가 실제로 실렸는지는 서비스만 안다). 애스펙트로 뭉뚱그리지 마라 — 어떤 필드가 나갔는지 알 수 없다. **같은 트랜잭션에 넣지 않는다** — 조회 실패가 감사 실패로, 감사 실패가 조회 실패로 번지지 않게 `REQUIRES_NEW` 또는 이벤트로 분리하고, 근거를 자바독에.

**심어라** — ①기록 호출을 no-op 으로 ②`detail.student_ids` 를 빈 배열로 ③`ManagerRosterResponse`(동승자 명단, 사진 L3)를 감사 대상에서 빼기 → 각각 그 시험만 실패해야 한다.

### 3.2 로그인 감사는 **비밀번호 대조 결과에 따라 갈린다** — 목표 2

`LoginCommandService` 자바독이 *"차단 판정은 대조 앞, 실패 카운터는 대조 뒤"* 를 명시한다. 감사 행도 같은 순서 — `login_fail` 은 **카운터가 올라간 그 자리**, `block` 은 **상한 도달 전이 그 자리**. 차단된 계정의 재시도(대조 전 `403`)는 `login_fail` 을 **올리지 않는다**(카운터도 안 오르니 같은 규칙). **심어라** — 차단 계정 재시도에 `login_fail` 행을 남기게 → 잡는 시험이 있어야 한다.

### 3.3 보존 정리는 **지워야 할 것보다 지우면 안 될 것이 본체다** — 목표 6·7

`audit_log`·`rider_status_history`·`no_show_*`·`exception_report`·`emergency_alert`·`route_version`·`run_stop` 은 **무기한**(`ERD §7.1·7.2`). 정리 스케줄러가 이것들에 닿는 경로가 없어야 한다. **심어라** — ①`notification_log` 컷오프를 14일 → 1일로 ②`run_position` 삭제 술어에서 `recorded_at` 조건 제거 ③`audit_log` 삭제를 추가 → 각각 잡혀야 한다. 시험은 **기한 안 행과 밖 행을 같이 심고 안 행이 남는 것**까지 단언한다.

**배치 크기 상한** — 첫 실행 때 몇 달치가 쌓여 있으면 한 트랜잭션 DELETE 가 락을 오래 쥔다. 상한으로 나눠 지우고, 시험은 상한+1 행을 심어 **2회 실행 뒤에 0** 이 되는 것을 본다.

### 3.4 지표는 **이름이 노출되는가**가 판정이다 — 목표 8

Micrometer counter 는 첫 `increment` 전까지 `/actuator/prometheus` 에 안 나온다. 기동 시 `registry.counter(name)` 로 선등록하지 않으면 "발생한 적이 없어서 없는 것" 과 "만들지 않은 것" 이 구별되지 않는다. 시험은 **아무 이벤트도 일으키지 않은 상태**에서 8행 이름이 전부 있는지 본다. **심어라** — 선등록 한 줄 제거 → 그 시험이 잡아야 한다.

`schoolbus.run.unconfirmed`(미확정 회차 수)는 *"`confirm_at` 이 지났는데 `idle`"* 이다 — `RunConfirmationMetrics` 옆에 두고 **주기 갱신 gauge**(스케줄러 틱마다 계산). 정의가 `§13.4` 알럿 첫 행("`confirm_at + 5분` 경과인데 `idle` ≥ 1")과 같은 술어여야 한다.

### 3.5 배포 게이트는 **EC2 에서 도는 스크립트**다 — 목표 9

워크플로의 `EC2 배포` 단계는 SSM 으로 `deploy.sh <SHA>` 를 부른다. 게이트는 **그 스크립트 안**(이미지 pull 전)에 있어야 GitHub 러너의 DB 접근 문제가 없다. `deploy-gate.sh` 는 `docker compose exec -T postgres psql -tAc "SELECT count(*) FROM run WHERE status='moving'"` 형태로 **compose 서비스 이름·DB 이름·역할을 `docker-compose.prod.yml` 에서 읽어** 맞춘다(추측 금지). 로컬 실증은 `DATABASE_URL`/`PGHOST` 류 환경변수로 로컬 15432 를 가리키게 하는 스위치를 둔다. **이미 moving 인 회차를 강제 종료하는 경로를 만들지 마라**(`§14.3` "강제 종료를 만들지 않는다").

### 3.6 런북은 **정본 두 곳을 절차로 옮기는 것**이지 새 판단이 아니다 — 목표 10

`TECH_DECISIONS §14.2·14.3` 이 무엇을 할지 정했고 `DEPLOYMENT §8` 이 증상별 확인을 적었다. 둘을 **한 표**(증상 → 확인 명령 → 조치 → 남길 것)로 합친다. `docs/` 는 git 추적 밖이라 **워크트리에 없다** — T3 은 메인 저장소 절대 경로의 `DEPLOYMENT.md` 를 직접 고친다(다른 좌석은 그 파일을 안 건드린다). 어투는 전역 규칙(개조식 체언 종결, 금지 어휘).

---

## 4. 좌석 분할 — 3개 병렬. **기준은 파일 축**

| 좌석 | 담당 목표 | 소유(쓰기) 파일 | 신설 핸들러 | 마이그레이션 |
|:-:|---|---|:-:|:-:|
| **T1 감사** | 1·2·3·4·5 | `audit/` 전부 · `authz/CanReadAudit.java`(신설) · `account/command/LoginCommandService.java` · L3 응답을 조립하는 서비스 5개(`student/query/*`·`boarding/query/RosterQueryService`·`monitoring/query/AdminRunRosterQueryService` 등 — **감사 호출 1줄씩만**) · 게이트 2파일 | 2 | 없음 |
| **T2 보존 정리** | 6·7 | `global/retention/`(신설 — `RetentionPolicy`·`RetentionCleanupScheduler`) · 삭제 메서드를 더할 리포지토리 5개(`NotificationLogRepository`·`RunPositionRepository`·`RefreshTokenRepository`·`LinkCodeRepository`·`LinkRequestRepository`) · `V8`(인덱스만) | 0 | `V8` |
| **T3 관측·게이트·런북** | 8·9·10 | `observability/` · 지표를 올릴 자리 4곳(`NotificationOutboxWorker`·`ChangeRequestAutoRejectionScheduler`·`NoShowEscalationScheduler`·WS 발행 리스너 — **counter/timer 호출 1줄씩만**) · `infra/scripts/deploy-gate.sh`(신설)·`deploy.sh` · `docs/DEPLOYMENT.md`(메인 저장소) | 0 | 없음 |

**겹침 점검** — T1 의 `RosterQueryService`(감사 호출)와 T3 의 `NotificationOutboxWorker`(지표)는 서로 다른 파일. T2 의 `NotificationLogRepository`(삭제 메서드)와 T3 의 `NotificationOutboxWorker`(같은 모듈, 다른 파일). **같은 파일을 두 좌석이 고치는 자리는 없다** — 발견하면 보고하고 멈춘다. `ErrorCode`·`Permissions`·`RolePermissions` 는 **아무도 건드리지 않는다**(`ACADEMY_NOT_FOUND`·`ACCOUNT_NOT_FOUND`·`AUDIT_READ` 전부 실재).

---

## 5. 좌석 공통 규칙 — `p14-common.md`

## 6. 실행

```bash
cd <워크트리>/backend
SPRING_DATA_REDIS_PORT=16379 ./gradlew test --tests <클래스> -PtestDbUrl=jdbc:postgresql://localhost:15432/<배정받은_DB> --rerun
```
포트·DB·XML 계수 규칙은 `p14-common.md §4`. ⚠ **`--tests` 경로는 실제 패키지로** — Phase 13 에서 `student.query.*`·`auth.*` 오기로 0 매치가 두 번 났다. `find src/test -name '<클래스>.java'` 로 먼저 확인.

---

## 7. 이월 · 오픈 이슈 처리

### Phase 13 이월 9건 — **이 Phase 에 흡수하지 않는다**
①~⑨(`IMPLEMENTATION_PLAN` Phase 13 절 끝)는 전부 시험 강화·소단위·판정 대기라 이 Phase 범위 밖. ⑥ X-08 은 해소됨.

### 오픈 이슈 — 이 Phase 를 막는 것
| ID | 내용 | 처리 |
|---|---|---|
| **Y (신설, Ruling 244)** | 강제 확정 콘솔 개입(`TECH_DECISIONS §14.3`)의 API 가 `API_SPEC` 에 부재 | 런북에 "수단 없음 — Y 판정 대기" 로 적고 **엔드포인트를 만들지 않는다**. 설계 문서는 사양이 아니다(`PRD §0`) |
| ~~X-09~~ (Ruling 243) | `run_position` 보유 **90일** · `audit_log` 삭제 부재 | **2026-09-04 사용자 확정.** 상수 자바독에 확정 날짜 |
| L-01~09 법정 요건 | 운영 전환 시 재확인(`ARCHITECTURE R7`) | 이 Phase 무관 |
