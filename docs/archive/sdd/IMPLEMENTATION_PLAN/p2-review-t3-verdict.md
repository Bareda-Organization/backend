# Phase 2 · Task 3 게이트 리뷰 판정

대상 `56752f2` → `07b8499`. **diff 2491줄을 5패스로 나눠 읽음**(main 코드 → 서비스·리포지토리 → SecurityConfig·Flyway → 컨트롤러 테스트 → 단말 테스트).

**diff 패키지 범위 주의** — 파일에 커밋 2개가 담겨 있고 stat 은 51파일 1412+/30- 다. 그중 `09fed25`(Task 2 리뷰 라운드 2 반영, `JwtTokenProvider` 외 4파일)는 **이 태스크 소유가 아니라** 판정 대상에서 제외했다. Task 3 커밋 `07b8499` 단독은 조율자가 준 47파일 1278+/20- 과 일치.

## 사양 준수

### ✅ 준수

- **선행조치 ① `roleHierarchy()` 배선** — `SecurityConfig.java:157` `RoleHierarchyImpl.fromHierarchy(RolePermissions.HIERARCHY)`. 종단 증거는 `DeviceControllerTest.단말_등록은_전_역할_6종이_전부_성공한다()` — `Role.values()` 6종을 실제 JWT 로 `POST /me/devices` 에 통과시킨다. 자기 픽스처가 아니라 프로덕션 애너테이션·부여표를 지난다
- **선행조치 ② 경로 문자열** — `/academies/search`(`AcademySearchController.java:23`) · `/auth/signup`(`SignupController.java:39`) 이 브리프 기대 문자열과 정확히 일치
- **선행조치 ③ `@SpringBootTest` 간헐 실패(Ruling 89)** — `build.gradle:118`(`systemProperty 'app.flyway-clean.suppressed'`) + `LocalFlywayCleanStrategy.java:52,62-65`. **가드 테스트 단언 무손상 확인** — `FlywayCleanStrategyGuardTest` 는 전 케이스가 `new MockEnvironment()` 로 전략을 직접 조립하므로(`:66,86,111,129,148,169`) 새 프로퍼티가 기본값 `false` 로 남아 `migrateRejectsRemoteDataSourceWithoutCleaning` · `migrateCleansLocalhostDataSourceInOrder` 를 포함한 실제 경로를 그대로 검증한다. `strategyBeanAbsentInDeploymentProfiles` · `contextFailsWhenLocalAndProdAreActiveTogether` 도 무영향. **겹①(`@Profile`)·겹②(생성자 재확인)는 그대로이고, 겹③(localhost 검증)은 억제 시 `clean()` 자체를 호출하지 않아 지키려는 위험이 미발생** — 안전장치 훼손 부재
- **두 축 분리** — `SignupController.java:44-46`(`@AuthenticatedOnly` + `@AllowedWhenPending`) · `:51-53`(`@AuthenticatedOnly` + `@AllowedWhenRejected`). 하나로 겸한 곳 부재. `§2.3` 을 rejected 도 호출할 수 있어야 한다는 `§1.4` 요구는 `AccountStatusGateInterceptor.java:56-61` 이 `allowedWhenPending || allowedWhenRejected` 로 판정해 `@AllowedWhenPending` 만으로 충족
- **회귀 수정의 정당성** — Phase 1 슬라이스 10개에 붙인 `excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class` 는 검증력을 깎지 않는다. 10개 diff 를 전건 확인한 결과 **추가된 것은 그 속성 하나뿐**이고, `@EntityScan`·`@Import(ClockConfig, JpaAuditingConfig)`·`ddl-auto=validate`·`EntityManager` 기반 단언이 전부 보존됐다. 그 10개가 리포지토리를 쓰지 않는다는 원인 분석도 사실
- **§2.1 inactive 제외** — `AcademyRepository.java:21-22` 가 `a.status = :status` 를 쿼리에 직접 넣고 `AcademySearchQueryService.java:23` 이 `ACTIVE` 만 넘긴다. Task 5 소관인 강제 장치는 미설계
- **§2.11 재등록 UK** — `DeviceCommandService.java:34-40` 이 `delete` → `flush()` → `save` 로 Hibernate 기본 플러시 순서를 뒤집는다. `DeviceControllerTest.단말_등록은_같은_기기를_두_번_등록해도_행이_늘지_않는다()` 가 행 수 1 **과** 토큰이 `fcm-token-2` 로 대체된 것을 함께 단언
- **에러 코드** — `VALIDATION_FAILED`(422) · `DUPLICATE_LOGIN_ID`(409) · `ACADEMY_NOT_FOUND`(404) · `REAPPLY_NOT_ALLOWED`(409) 4종이 `API_SPEC §8.1·§8.5` 사전값과 코드·HTTP 모두 일치
- **범위 밖 산출물** — `§2.5~§2.9` 핸들러 부재 · 학원 격리 강제 장치 부재 · `global/security/authz/`·`gate/` 무수정(부착만) 전부 확인

### ❌ 문제 발견

- **누락 — `§2.2` 응답 상태 코드가 `201` 이 아니라 `200`.** 아래 Important #1
- **누락 — `app_version` 이 수신 후 폐기.** 아래 Important #2
- **누락 — 선행조치 ③ 의 이행 내역이 보고서 전체에 부재.** 아래 Important #3

### ⚠ diff 로 확인 불가 · 조율자가 판정할 것

1. **최우선 검증 지점 4(저장소 계층 확장)가 브리프에 전달되지 않았다.** `p2-task-3-brief.md` 전문에 `RefreshTokenRepository`·`VerificationCodeRepository`·전화번호 조회 요구가 **한 줄도 없다**(`.superpowers/sdd/IMPLEMENTATION_PLAN/` 전체 grep 에서도 그 이름이 부재). diff 실측 — `AccountRepository.java:12-15` 는 `existsByLoginId`·`findByLoginId` **2개뿐이고 전화번호 조회가 부재**, `RefreshTokenRepository`·`VerificationCodeRepository` 는 **파일 자체가 부재**. 확장 지시가 구현자에게 닿지 않았으므로 **이 태스크의 결함으로 매기지 않았다.** Task 4 브리프(`p2-task-4-brief.md:95-96`)가 `RefreshToken`·`VerificationCode` 엔티티와 부분 인덱스를 이미 서술하므로 **Task 4 로 넘기거나 별도 발주가 필요**하다. 부분 인덱스 사용 여부·무효 토큰 혼입 단언도 그때 함께 요구할 것
2. **`MeControllerTest.pending_계정이_me_를_부르면_403_AUTH_PENDING_이다()` 가 정정된 `§1.4`(커밋 `f61506a`, Ruling 98)와 어긋난다.** `API_SPEC.md:98` 은 pending 허용 목록에 `GET /me` · `POST`·`DELETE /me/devices` 를 포함하는데 이 테스트는 403 을 기대값으로 고정한다(`MeControllerTest.java:59-61`). **기능적 귀결을 함께 넘긴다 — 현재 코드에서는 pending 계정이 `POST /me/devices` 를 호출할 수 없어 단말이 등록되지 않고, 그러면 `§2.11` 이 단말 등록의 존재 이유로 든 "승인 결과 알림" 을 받을 수단이 사라진다.** Task 4 가 게이트 애너테이션 2개를 붙이면서 이 테스트도 함께 뒤집어야 한다
3. **보고서의 스위트 실측이 리뷰 대상 트리에서 측정된 것이 아니다.** 보고서 §3 은 분기점을 `56752f2` 로 적었고 `209 tests, 2 failed` 를 3회 관측했다고 하는데, `07b8499` 의 실제 부모는 `09fed25` 다(`git rev-parse 07b8499^` 로 확인). 즉 **구현자가 측정한 트리에는 Task 2 수정분 4파일이 없었다.** 조율자 실측(41클래스 212테스트·실패 2)이 실제 head 를 덮으므로 결과는 확인됐고, 209 ↔ 212 차이는 이 3커밋 간극으로 설명된다. **판정 근거로는 조율자 실측만 쓸 것**
4. **`API_SPEC §1.1` 베이스 경로 `/api/v1` 과 실제 서빙 경로가 다르다.** `application.yml` 에 `server.servlet.context-path`·`spring.mvc.servlet.path` 설정이 부재해 엔드포인트가 `/auth/signup` 으로 서빙된다. Ruling 80 이 bare path 를 지시했으므로 **구현자 판단이 아니라 조율자 결정 사항**이나, 아래 Important #6 의 실제 결함을 낳는다

## 잘된 점

- **선행조치 ① 의 증거가 자기 픽스처가 아니다.** `DeviceControllerTest.java` 의 6역할 순회는 `RolePermissions.HIERARCHY` → `RoleHierarchy` 빈 → `@PreAuthorize("hasAuthority('DEVICE_REGISTER')")` 사슬을 실제로 통과시킨다. 배선이 없으면 6회 전부 403 이 되므로 이 단언은 실패한다
- **`§2.1` 테스트가 필터 부재를 실제로 잡는다.** `AcademySearchControllerTest.java:22-29` 는 활성·비활성 두 행을 같은 검색어 `P2T3QQQQ` 에 걸리게 심어 놓고 `items.length()` 를 1 로 단언한다 — `status` 조건이 빠지면 2가 되어 즉시 실패한다. "행이 나온다" 로 통과하는 형태가 아니다
- **안전장치를 우회하지 않고 clean 을 억제했다.** 프로퍼티를 `true` 로 읽었을 때 `clean()` 호출 자체를 하지 않는 형태라(`LocalFlywayCleanStrategy.java:62-65`), 겹③이 지키는 위험이 발생할 경로가 없다. `bootRun` 은 프로퍼티가 없어 종전 동작 유지
- **회귀 원인 분석이 정확하다.** `@EntityScan` 은 엔티티 스캔만 좁히고 리포지토리 컴포넌트 스캔은 `@ContextConfiguration` 의 베이스 패키지를 따른다는 진단이 맞고, 그 10개가 `EntityManager` 로만 검증한다는 사실까지 확인해 제외의 무해성을 뒷받침했다
- **`GlobalExceptionHandler` 정렬이 사양 방향이다.** `MethodArgumentNotValidException` 을 `INVALID_INPUT`(400)에서 `VALIDATION_FAILED`(422)로 옮기고, 방치하면 500 으로 떨어지던 `MissingServletRequestParameterException` 을 422 로 잡는다(`GlobalExceptionHandler.java:44-72`)
- **자기 점검 #10 을 "아니오" 로 냈다.** 범위 밖 수정 10파일을 스스로 지목하고 근거·대상 목록을 남겼다 — 정직한 보고이고, 이 리뷰가 그 판단을 검증할 수 있었던 것은 그 서술 덕분이다

## 지적

### Critical (반드시 수정)

부재. 데이터 손실·보안 구멍·되돌릴 수 없는 사고로 이어지는 항목은 발견되지 않았다.

### Important (수정 필요)

**1. `§2.2` 가 `200` 을 반환한다 — 사양은 `201` 이고, 틀린 주석과 틀린 단언이 그것을 고정했다**

`SignupController.java:38-42` 가 `ResponseEntity` 없이 `ApiResponse` 를 그대로 반환해 200 이 된다. `API_SPEC.md:249` 는 `**응답 `201`**`, `API_SPEC.md:41`(§1.1)은 "생성 `201`" 을 규정한다. 같은 태스크가 `POST /me/devices` 는 `DeviceController.java:35` 에서 `ResponseEntity.status(HttpStatus.CREATED)` 로 올바로 처리했으므로 **형태가 태스크 안에서 갈렸다.**

세 곳이 함께 어긋나 있다 —
- `SignupResponse.java:10` 주석이 `(API_SPEC §2.2, 201)` 이라 **코드와 반대**(`p2-controller-conventions.md §9` — "틀린 주석은 없는 주석보다 나쁘다")
- `SignupControllerTest.java:65` 가 `status().isOk()` 로 **틀린 값을 고정**

수정 — `DeviceController` 와 같은 형태로 201 을 반환하고, 단언을 `isCreated()` 로 바꾼다.

**2. `app_version` 을 받아서 버린다 — 컬럼도 필드도 있는데 항상 NULL 이 된다**

`DeviceRegisterRequest.java:13` 이 `app_version` 을 파싱하는데 `DeviceCommandService.java:39` 의 `DeviceToken.register(accountId, deviceId, token, platform)` 은 4인자라 그 값을 넘기지 않는다. 버릴 값이 아니다 — `V1__init_schema.sql:696` 에 `app_version varchar(20)` 컬럼이 있고 `DeviceToken.java:47-48` 에 매핑 필드가 있다. `API_SPEC.md:392` 가 선택 필드로 명시하므로, 클라이언트가 보낸 값이 조용히 사라지는 상태다.

브리프가 엔티티 수정을 금지(`§8`)했으나 **같은 태스크가 `DeviceToken.java` 에 `revoke(...)` 를 이미 추가했다** — 팩토리에 인자를 늘리는 것도 같은 성격이다. 최소한 **보고서에 결함으로 올렸어야** 했는데 그 서술도 부재하다.

수정 — `register(...)` 에 `appVersion` 을 추가하거나, 넘기지 않기로 했다면 요청 DTO 에서 필드를 빼고 그 근거를 남긴다. **받아 놓고 버리는 형태만은 남기지 않는다.**

**3. 선행조치 ③(Ruling 89)의 이행 내역이 보고서에 전혀 없다**

`build.gradle` 과 `LocalFlywayCleanStrategy` 변경은 이 태스크에서 **가장 넓게 퍼지는 변경**이다 — 전 `@SpringBootTest` 의 DB 초기화 동작을 바꾼다. 그런데 `p2-task-3-report.md` 전문에 `flyway`·`Ruling 89`·`suppress` 문자열이 **한 번도 나오지 않는다**(grep 0건). §4 가 "3회 연속 동일" 만 적을 뿐 무엇을 고쳐서 그렇게 됐는지가 부재하다.

브리프 §2 가 "다른 것보다 먼저 하라" 로 지시한 3건 중 1건이고, 자기 점검 #1 에 "예" 로 답한 항목이다. **코드는 맞게 고쳤으나 보고가 그것을 뒷받침하지 않아, 조율자가 diff 를 직접 읽지 않으면 이행 여부를 판정할 수 없는 상태**다. 다음 라운드 보고서에 ①원인 ②조치 ③가드 테스트 무손상 근거 ④연속 실행 결과를 명시할 것.

**4. `cleanSuppressed` 분기에 단언이 부재하다**

`LocalFlywayCleanStrategy.java:62-65` 의 새 조기 반환은 `FlywayCleanStrategyGuardTest` 가 덮지 않는다. 그 클래스는 나머지 전 분기(원격 거부·순서·파싱 실패·DataSource 부재·연결 예외·메타데이터 폴백)를 케이스별로 검증하는데, **이번에 추가된 분기만 검증 밖**이다.

이 분기가 조용히 죽으면(프로퍼티 이름 오타, `build.gradle` 의 `systemProperty` 줄 삭제) 증상은 **Ruling 89 가 닫으려 한 간헐 실패의 재발**이고, 그것은 실행마다 결과가 달라 원인 추적이 가장 비싼 형태다.

수정 — 같은 클래스에 2개를 더한다. ①`MockEnvironment` 에 `app.flyway-clean.suppressed=true` 를 넣으면 `flyway.clean()` 이 **호출되지 않고** `migrate()` 만 호출된다(`InOrder` 로 이미 쓰는 패턴 그대로) ②억제 시 원격 데이터소스여도 예외가 발생하지 않는다(설계 의도 고정).

**5. RED 관측이 단언별 기제를 격리하지 못했다**

보고서 §2 의 RED 는 전건이 **"컨트롤러 파일을 `/tmp` 로 옮김"** 한 가지 조작에서 나왔다. 그 결과 4개 파일의 실패 원인이 전부 **핸들러 부재(404)** 로 같아지고, 각 단언이 무엇을 잡는지가 구분되지 않는다. 브리프 자기 점검 #2 가 지적한 형태("RED 가 컴파일 오류 1종뿐이라 각 단언이 무엇을 잡는지 구분 불가")와 조작만 다르고 성격이 같은데 "예" 로 답했다.

특히 **기제가 곧 요구사항인 두 단언**이 미검증이다 —
- `학원_검색은_inactive_학원을_반환하지_않는다` — 404 로 실패한 것은 `AcademyRepository.java:21` 의 `a.status = :status` 가 있어야 통과한다는 사실을 보이지 않는다. 그 조건만 빼고 RED 를 봐야 이 단언이 살아 있음이 증명된다
- `단말_등록은_같은_기기를_두_번_등록해도_행이_늘지_않는다` — 404 로 실패한 것은 `DeviceCommandService.java:34-36` 의 `delete`+`flush` 가 UK 충돌을 막는다는 사실을 보이지 않는다

권고 — 다음 라운드에서 **조건 한 줄씩만 제거한 RED** 를 이 2건에 대해 재관측하고 출력을 남긴다. 스위트 전체 재실행은 불요하며 `--tests '*.AcademySearchControllerTest'` · `'*.DeviceControllerTest'` 로 좁힌다.

**6. CORS 등록 경로가 실제 서빙 경로를 덮지 않는다**

`SecurityConfig.java:123` 이 `source.registerCorsConfiguration("/api/**", configuration)` 인데, 같은 커밋이 `:74-76` 에서 공개 경로 매처를 `/api/auth/**` 에서 **접두사 없는 bare path** 로 바꿨다. 즉 이 태스크가 만든 7개 경로(`/academies/search`·`/auth/signup`·`/auth/signup-status`·`/auth/signup/reapply`·`/me`·`/me/devices`·`/me/devices/{token}`)는 **어느 것도 `/api/**` 에 걸리지 않아 CORS 설정이 적용되지 않는다.**

증상은 서버 테스트로는 보이지 않는다(MockMvc 는 preflight 를 거치지 않음). 브라우저에서 관계자 웹·메인 관리자 콘솔이 붙는 시점에 **preflight 실패로 전 요청이 차단**된다. 차단 방향이라 보안 구멍은 아니나 기능 정지다.

근본 원인은 ⚠4(베이스 경로 `/api/v1` 미설정)이고 **결정은 조율자 몫**이다. 두 갈래 중 하나를 택하면 된다 — ①`server.servlet.context-path: /api/v1` 을 두면 매처·CORS 는 컨텍스트 내부 경로로 판정되므로 bare path 가 그대로 맞고 CORS 매핑만 `/**` 계열로 정정 ②컨트롤러 경로에 접두사를 붙이면 Ruling 80 의 기대 문자열이 함께 바뀐다. **Task 4 가 로그인 쿠키 `Path=/api/auth`(§1.2.1)를 심기 전에 결정돼야 한다** — 그 값도 같은 결정에 걸린다.

### Minor (개선 권장)

- **`SecurityConfig.java:75-76` 이 Task 4 경로 3개를 선반영했다** — `/auth/login`·`/auth/refresh`·`/auth/recover` 는 핸들러가 없어 무해하나 Task 4 소유다. 브리프 §8 의 "만들지 마라" 경계에 걸린다
- **`ErrorCode.java:11` 의 `INVALID_INPUT` 이 미사용으로 남았다** — 같은 커밋이 `CONFLICT`·`DUPLICATE_EMAIL` 을 미사용이라 지웠는데 마지막 소비처를 잃은 이것만 남겼다. 기준을 하나로 맞출 것
- **로케일 의존 대소문자 변환** — `SignupCommandService.java:53`(`payload.role().toUpperCase()`) · `DeviceCommandService.java:38`(`request.platform().toUpperCase()`) · 응답 DTO 의 `name().toLowerCase()` 다수. 기본 로케일이 터키어면 `driver` → `DRİVER` 가 되어 `Role.valueOf` 가 `IllegalArgumentException` 을 던진다(`i` 를 포함하는 값이 `driver` 하나 존재). `toUpperCase(Locale.ROOT)`·`toLowerCase(Locale.ROOT)` 로 고정
- **LIKE 와일드카드 미이스케이프** — `AcademyRepository.java:22`. `q=%` 로 검색하면 전 활성 학원이 반환된다. 비인증 경로라 열람 대상이 학원 공개 정보뿐이지만, 검색 상한도 부재해(`AcademySearchQueryService.java:23` 이 `List` 를 그대로 반환) 학원 수가 늘면 응답 크기가 무제한으로 는다. `ESCAPE` 처리 + 상한을 함께 검토
- **`existsByLoginId` 선확인은 TOCTOU 다** — `SignupCommandService.java:49-51`. `uk_account_login_id`(`V1__init_schema.sql:66`)가 최종 방어이나, 동시 가입이 겹치면 `DataIntegrityViolationException` 이 catch-all 로 떨어져 **409 가 아니라 500** 이 된다. 제약 위반을 `DUPLICATE_LOGIN_ID` 로 변환하는 처리를 검토
- **`revoke` 가 이미 해지된 행의 `revoked_at` 을 덮어쓴다** — `DeviceCommandService.java:51-52` 가 `revokedAt IS NULL` 을 걸지 않아, 재호출마다 해지 시각이 갱신된다. `DeviceToken.java` 주석이 내세운 "이력을 남긴다" 와 어긋난다
- **`Account.reapply()` 의 `REAPPLY_NOT_ALLOWED` 경로가 미검증** — `Account.java:131-134`. `rejected_계정만_reapply_를_부를_수_있다()` 는 pending(게이트가 막음)과 rejected(성공)만 보는데, **`active` 계정은 게이트를 그대로 통과해**(`AccountStatusGateInterceptor.java:46`) 이 도메인 가드가 유일한 방어가 된다. `API_SPEC.md:283` 이 `409 REAPPLY_NOT_ALLOWED` 를 명시하므로 active 토큰 케이스를 단언에 추가할 것
- **`NOT_FOUND` 는 `§8` 사전에 없는 코드다** — `MeQueryService.java:51-52` · `SignupStatusQueryService.java:31,33`. 계정 부재는 사전상 `ACCOUNT_NOT_FOUND`(404)다. 발생 경로가 이례적이나 클라이언트가 문자열로 분기하므로 사전값을 쓸 것
- **6역할 테스트의 토큰이 계정 행과 어긋난다** — `DeviceControllerTest` 의 순회에서 `SYSTEM_ADMIN` 계정은 `academyId=null` 로 만들면서 토큰에는 `academyId` 를 실어 발급한다. 단언(201)에는 영향이 없으나 픽스처가 성립 불가능한 상태를 만든다
- **전역 snake_case 전략 부재로 DTO 마다 수동 `@JsonProperty`** — `application.yml`·설정 클래스 어디에도 `PropertyNamingStrategies.SNAKE_CASE` 가 없어 이번 8개 DTO 에 14개를 손으로 붙였다. 하나를 빠뜨려도 컴파일·테스트가 잡지 못하고 `API_SPEC §1.1`("필드 명명 `snake_case`")이 깨진다. **Task 4·5 에서 DTO 가 배로 늘기 전에** 전역 설정으로 옮길지 결정할 것

## 판정

**사양 준수:** ❌ 문제 발견 — 누락 3건(`§2.2` 201 · `app_version` 폐기 · 선행조치 ③ 미보고). 초과 산출물은 자기 신고분(Phase 1 슬라이스 10개)이 **정당한 회귀 수정으로 확인**되고, 그 외에는 `SecurityConfig` 의 Task 4 경로 3개(Minor)뿐

**품질:** 수정 필요

**근거:** 선행조치 3건은 코드로 전부 이행됐고 안전장치·검증력 손상이 부재하며, `§2.1`·`§2.11` 의 핵심 단언은 기제 부재를 실제로 잡는 형태다. 다만 `§2.2` 응답 코드가 사양과 다른 채 틀린 주석·틀린 단언으로 고정됐고, 문서화된 요청 필드 하나가 조용히 버려지며, 가장 넓게 퍼지는 변경(Flyway clean 억제)이 보고서에 부재한 데다 그 새 분기에 단언이 없다 — 이 4건을 고치기 전에는 이 태스크를 신뢰할 수 없다.
