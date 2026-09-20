# Task B1 리포트 — Phase 2 기반 3커밋

- 작성일: 2026-08-03
- 브랜치: `feat/mvp-expansion-backend`
- 시작 HEAD: `e1c8337`
- 상태: **DONE**

## 요약

| 항목 | 결과 |
|---|---|
| 커밋 | 4개 — `776296c` · `e9304a5` · `a460771` · `5e6c6c5`(후속) |
| 치환한 `@PreAuthorize` | **24 / 61** (남은 37은 커밋 ④~⑦ 몫) |
| 신규 프로덕션 파일 | 22 (상수 1 + 부여표 1 + 애너테이션 20) |
| 변경 프로덕션 파일 | 6 (컨트롤러 5 + `SecurityConfig`) |
| 신규 테스트 파일 | 1 (`RolePermissionsTest`, 9 케이스) |
| **변경한 기존 테스트** | **0** ← 설계 목표 달성 |
| 전체 스위트 | **38 클래스 / 217 테스트 / 실패 0** (기준선 37/208 + 신규 1클래스 9테스트) |
| Docker | 사용하지 않음 |

설계 문서의 접근 방식(`RoleHierarchy` 를 역할→권한 부여표로 전용)은 **검증됐다.** 커밋 ③이 초록이므로
설계 §6 의 판단 기준("이 커밋이 초록이면 접근 방식 전체가 검증된 것")을 충족한다. 커밋 ④ 진행 가능.

---

## 커밋 ① `776296c` — permission 상수 + 부여표 + `RoleHierarchy` 빈

### 만든 것

| 파일 | 내용 |
|---|---|
| `global/security/authz/Permissions.java` | `public static final String` 20개 (설계 §2.3 그대로) |
| `global/security/authz/RolePermissions.java` | `HIERARCHY` 33줄 (3+2+2+3+11+12) |
| `global/security/SecurityConfig.java` | `@Bean RoleHierarchy roleHierarchy()` 추가 |
| `RolePermissionsTest.java` | 9 케이스 |

### 설계에서 벗어난 점 2가지 (의도적)

**1. `HIERARCHY` 를 텍스트 블록이 아니라 상수 조립으로 만들었다.**

설계 §4.5 는 텍스트 블록 예시를 보여 줬지만, 텍스트 블록 안의 `student:manage` 는 리터럴이라
`Permissions.STUDENT_MANAGE` 와 **공유되지 않는다.** 브리프가 명시한 요구("애너테이션과 부여표가
같은 컴파일 타임 상수를 공유해야 한다 — 문자열을 양쪽에 따로 적으면 오타=컴파일에러 이점이 사라진다")를
만족시키려면 상수를 참조해야 해서 `grant(Role, String...)` 헬퍼로 조립했다.

부수 효과로 **§4.4 의 "우변에 `ROLE_` 금지" 규칙이 구조적으로 강제된다** — 좌변은 `Role` 열거형으로만
받고 `ROLE_` 접두어는 헬퍼 안에서만 붙으므로, `ROLE_A > ROLE_B` 줄을 쓰려면 헬퍼를 우회해야 한다.
(우회 자체는 여전히 가능하므로 테스트로도 이중 고정했다.)

**2. `RolePermissionsTest` 에 케이스 1개를 추가했다.**

설계가 요구한 2종(역할별 도달 집합 특성화 / 우변 `ROLE_` 금지) 외에
`아무_역할에도_부여되지_않은_권한은_없다` 를 넣었다. `Permissions` 상수를 리플렉션으로 전부 훑어
부여표에 없는 것이 있으면 실패한다 — 부여를 빠뜨린 permission 을 애너테이션에 쓰면 그 엔드포인트가
**아무도 못 쓰는 상태**가 되는데, 그건 403 이 나기 전까지 조용하다. 저비용이라 넣었다.
불필요하다고 판단되면 삭제해도 나머지 8개는 독립적이다.

또 `어느_역할도_다른_역할로_확장되지_않는다` 를 문자열 파싱이 아니라 **도달성 기준**으로도 확인한다
(파싱 검사와 별개). 문법이 맞아도 결과적으로 역할이 열리는 경우를 잡기 위해서다.

### 인가 동작 무변경 확인

부여표 우변에 `ROLE_` 이 하나도 없으므로 기존 `hasRole`/`hasAnyRole` 판정은 그대로다.
이 커밋 시점에 `@PreAuthorize` 61곳은 손대지 않았고, 전체 스위트 217/0 통과.

### Spring Security 7.1.0 소스 재확인

설계 §7-5 가 "`DefaultAuthorizationManagerFactory` 내부는 읽지 않았다"고 남긴 구멍을 메웠다.
`hasAuthority` 가 계층 확장을 타는 경로 전체를 소스로 확인했다.

```
PrePostMethodSecurityConfiguration:120~123  @Autowired(required=false) setRoleHierarchy(RoleHierarchy)
  → AbstractSecurityExpressionHandler:162~166  setRoleHierarchy → getDefaultAuthorizationManagerFactory().setRoleHierarchy(..)
    → DefaultAuthorizationManagerFactory:118~119  hasAuthority(s) → createManager(AuthorityAuthorizationManager.hasAuthority(s))
      → DefaultAuthorizationManagerFactory:152~153  createManager → authorizationManager.setRoleHierarchy(this.roleHierarchy)
```

`createManager` 가 `hasRole` 계열과 `hasAuthority` 계열 **양쪽**에 같은 계층을 주입한다.
설계의 가정이 맞았다.

---

## 커밋 ② `e9304a5` — 메타 애너테이션 20개

설계 §3.3 표 그대로 20개를 만들었다. 어느 컨트롤러에도 붙이지 않은 순수 추가.

- `@Target({METHOD, TYPE})` 3개: `@CanManageStudents` · `@CanManageMembers` · `@CanManageBuses`
- `@Target({METHOD})` 17개
- 표현식은 전부 `@PreAuthorize("hasAuthority('" + Permissions.XXX + "')")`

**`compileJava` 통과가 곧 상수식 조립이 유효하다는 증명이다** — 애너테이션 값에 들어가는
`"hasAuthority('" + 상수 + "')"` 가 컴파일 타임 상수식으로 접힌다는 것이 컴파일러에 의해 확인됐다.

공통 골격이 20개 모두 동일해 손으로 쓰면 오탈자가 나기 쉬워 생성 스크립트로 찍었다
(스크립트는 스크래치패드에 두었고 저장소에 커밋하지 않았다). javadoc 은 애너테이션마다 다르게 썼다 —
필요 권한, 현재 부여 역할, 그리고 **헷갈리기 쉬운 이웃 애너테이션과의 경계**를 적었다.
예: `@CanManageBuses` javadoc 은 "기사·선탑자가 자기 담당 버스를 보는 것은 이게 아니라
`@CanReadAssignedBus` 다"라고 명시한다.

애너테이션 javadoc에는 **역할 이름을 규범으로 적지 않았다** ("현재 부여 역할은 …이지만, 어느 역할이
이 권한을 갖는지는 `RolePermissions` 한 곳에서만 정한다"). 여기 역할을 적어 두면 부여표가 바뀔 때
20개 javadoc 이 조용히 거짓이 된다.

---

## 커밋 ③ `a460771` — 컨트롤러 5개 치환 (24곳)

### 치환 내역 (24곳 전수)

| 컨트롤러 | 줄 | 이전 | 이후 |
|---|---|---|---|
| DriveSession | :50 | `hasRole('DRIVER')` | `@CanOperateDrive` |
| DriveSession | :64 | `hasRole('DRIVER')` | `@CanOperateDrive` |
| DriveSession | :76 | `hasAnyRole('DRIVER','ATTENDANT')` | `@CanReadAssignedBus` |
| DriveSession | :90 | `hasAnyRole('DRIVER','ATTENDANT')` | `@CanReadAssignedBus` |
| DriveSession | :104 | A | `@CanMonitorOperations` |
| Location | :59 | `hasRole('STUDENT')` | `@CanReportOwnLocation` |
| Location | :70 | `hasRole('STUDENT')` | `@CanReadOwnRecords` |
| Location | :81 | `hasRole('PARENT')` | `@CanReadOwnChildren` |
| Location | :91 | `hasRole('DRIVER')` | `@CanOperateDrive` |
| Location | :101 | A | `@CanMonitorOperations` |
| Location | :109 | `hasRole('DRIVER')` | `@CanOperateDrive` |
| Location | :123 | A | `@CanMonitorOperations` |
| Location | :137 | `hasRole('PARENT')` | `@CanReadOwnChildren` |
| RideEvent | :49 | `hasRole('ATTENDANT')` | `@CanRecordRideEvent` |
| RideEvent | :64 | `hasAnyRole('ATTENDANT','ACADEMY_ADMIN','PLATFORM_ADMIN')` | `@CanCorrectRideEvent` |
| RideEvent | :77 | `hasRole('STUDENT')` | `@CanReadOwnRecords` |
| RideEvent | :89 | `hasRole('PARENT')` | `@CanReadOwnChildren` |
| RideEvent | :102 | `hasAnyRole('DRIVER','ATTENDANT')` | `@CanReadAssignedBus` |
| RideEvent | :117 | A | `@CanMonitorOperations` |
| Tenant | :48 | `hasRole('PLATFORM_ADMIN')` | `@CanManageTenants` |
| Tenant | :57 | `hasRole('PLATFORM_ADMIN')` | `@CanManageTenants` |
| Tenant | :66 | A | `@CanReadTenant` |
| Tenant | :77 | `hasRole('PLATFORM_ADMIN')` | `@CanManageTenants` |
| Member | :43 (클래스) | A | `@CanManageMembers` |

설계 §1 의 파일:라인이 전부 실측과 일치했다(R3 커밋 3개가 컨트롤러를 안 건드렸다는 브리프의
설명대로였다). 치환은 라인 번호가 아니라 `매핑 애너테이션 + @PreAuthorize` 두 줄 묶음을 키로 삼아
각 묶음이 파일 내에서 **정확히 1회** 나타남을 단언하며 수행했다.

컨트롤러 3개의 클래스 javadoc 에 있던 "`@PreAuthorize` 로 역할을 제한한다" 서술을
"권한 애너테이션으로"로 고쳤다(DriveSession·Location·RideEvent). 문서 드리프트 방지.

### 남은 개수 검산

```
컨트롤러 전체 '@PreAuthorize('  : 61 → 37   (24 감소, 정확히 치환한 수)
```

| 남은 컨트롤러 | 개수 | 커밋 |
|---|---:|---|
| Attendance | 5 | ⑥ |
| Bus | 2 | ④ |
| Notification | 2 | ⑦ |
| Route | 3 | ⑤ |
| Routing | 10 | ⑤ |
| LocationChange | 3 | ⑥ |
| Schedule | 5 | ⑥ |
| Sos | 6 | ⑦ |
| Student | 1 | ④ |
| **합계** | **37** | |

> 참고: `grep '@PreAuthorize'`(괄호 없이)로 세면 **39** 가 나온다. 차이 2는
> `BusController:50` 과 `RoutingController:37` 의 **javadoc 언급**이다(코드 아님).
> 커밋 ④·⑤ 에서 그 javadoc 문구도 함께 고쳐야 설계 §6 커밋 ⑦ 의 검산이 깨끗해진다.

### 허용 경로 개별 결과 — 이것이 유일한 증거

거부 테스트(403 기대)는 `RoleHierarchy` 빈이 없어도 통과하므로 신호가 아니다.
**200 을 기대하는 허용 경로만이 치환이 실제로 동작한다는 증거다.** 슬라이스별 개별 결과:

**DriveSessionControllerTest (3/3 통과) — 허용 경로 2건**

| 테스트 | 기대 | 결과 |
|---|---|---|
| `roster_as_attendant_is_allowed` | 200 | **PASS** ← 신호 |
| `busHistory_as_attendant_is_allowed` | 200 | **PASS** ← 신호 |
| `start_as_attendant_returns_403` | 403 | PASS (신호 아님) |

**LocationControllerTest (10/10 통과) — 허용 경로 4건**

| 테스트 | 기대 | 결과 |
|---|---|---|
| `report_as_student_is_allowed` | 200 | **PASS** ← 신호 |
| `children_locations_as_parent_is_allowed` | 200 | **PASS** ← 신호 |
| `reportBus_as_driver_is_allowed` | 200 | **PASS** ← 신호 |
| `tenantBusLocations_as_admin_is_allowed` | 200 | **PASS** ← 신호 |
| `children_locations_as_student_returns_403` | 403 | PASS (신호 아님) |
| `tenantBusLocations_as_driver_returns_403` | 403 | PASS (신호 아님) |
| `report_as_parent_returns_403` | 403 | PASS (신호 아님) |
| `reportBus_as_parent_returns_403` | 403 | PASS (신호 아님) |
| `report_without_auth_returns_401` | 401 | PASS (신호 아님) |
| `reportBus_without_auth_returns_401` | 401 | PASS (신호 아님) |

**RideEventControllerTest (4/4 통과) — 허용 경로 1건**

| 테스트 | 기대 | 결과 |
|---|---|---|
| `post_as_attendant_is_allowed` | 200 | **PASS** ← 신호 |
| `post_as_student_returns_403` | 403 | PASS (신호 아님) |
| `post_as_driver_returns_403` | 403 | PASS (신호 아님) |
| `post_without_auth_returns_401` | 401 | PASS (신호 아님) |

**TenantControllerTest (2/2 통과) — 허용 경로 1건**

| 테스트 | 기대 | 결과 |
|---|---|---|
| `create_as_platform_admin_is_allowed` | 200 | **PASS** ← 신호 |
| `create_as_academy_admin_returns_403` | 403 | PASS (신호 아님) |

**MemberControllerTest (8/8 통과) — 허용 경로 5건**

| 테스트 | 기대 | 결과 |
|---|---|---|
| `register_as_academy_admin_is_allowed` | 200 | **PASS** ← 신호 |
| `detail_as_academy_admin_is_allowed` | 200 | **PASS** ← 신호 |
| `update_as_academy_admin_is_allowed` | 200 | **PASS** ← 신호 |
| `resetPassword_as_academy_admin_returnsNoPasswordInBody` | 200 | **PASS** ← 신호 |
| `remove_as_academy_admin_is_allowed` | 200 | **PASS** ← 신호 |
| `resetPassword_shortPassword_returns_400` | 400 | PASS (설계 예측과 달리 신호 아님 — 아래 참조) |
| `register_as_student_returns_403` | 403 | PASS (신호 아님) |
| `remove_as_driver_returns_403` | 403 | PASS (신호 아님) |

**허용 경로 합계 13건 전부 통과.**

### 음성 대조 — 이 13건이 우연히 통과하는 게 아님을 증명

허용 경로가 통과했다는 것만으로는 "빈이 실제로 일하고 있다"를 증명하지 못한다(예: 애너테이션이
어떤 이유로 무시되고 있어도 200 이 난다). 그래서 `RoleHierarchy` 빈을 **일시적으로 무력화**하고
같은 5개 슬라이스를 돌렸다.

| 슬라이스 | 빈 제거 시 실패 |
|---|---|
| DriveSessionControllerTest | 2 — `roster_as_attendant_is_allowed`, `busHistory_as_attendant_is_allowed` |
| LocationControllerTest | 4 — `tenantBusLocations_as_admin_is_allowed`, `children_locations_as_parent_is_allowed`, `report_as_student_is_allowed`, `reportBus_as_driver_is_allowed` |
| RideEventControllerTest | 1 — `post_as_attendant_is_allowed` |
| TenantControllerTest | 1 — `create_as_platform_admin_is_allowed` |
| MemberControllerTest | 5 — `register`·`detail`·`update`·`resetPassword`·`remove` as academy admin |
| **합계** | **13 — 허용 경로와 정확히 일치. 거부·미인증 테스트는 전부 초록 유지.** |

설계 §5.2 의 예측(빈이 없으면 허용 경로가 전부 403)이 **실측으로 확인**됐고,
"거부 테스트만 보고 안심하면 안 된다"는 경고도 실측으로 확인됐다.
확인 후 빈은 `git checkout` 으로 원복했고 커밋에는 이 임시 변경이 들어가지 않았다.

### 설계 §5.2 의 예측 1건 정정 — 14건이 아니라 13건

설계는 `MemberControllerTest:114 resetPassword_shortPassword_returns_400` 이 빈 누락 시
`400 → 403` 으로 바뀐다고 예측했다("인가가 검증보다 먼저 돌기 때문"). **실측은 반대다 — 400 그대로다.**

이유: `@Valid @RequestBody` 검증은 **핸들러 메서드 인자 해석 단계**에서 일어나고,
메서드 시큐리티는 그 메서드 호출을 감싸는 AOP 인터셉터다. 인자 해석이 먼저 실패하면
메서드는 애초에 호출되지 않아 인가 평가까지 가지 않는다. 순서가 설계 서술과 뒤바뀌어 있다.

**영향 없음** — 이 테스트는 어느 쪽이든 통과하므로 회귀 위험이 아니다. 다만
"먼저 깨지는 건수"는 14가 아니라 **13**이고, 이 테스트는 신호 목록에서 빼야 한다.

### 설계 §7-3 의 미확인 항목 부분 해소

설계는 "`@Target` 에 `TYPE` 을 넣는 결정은 정적 근거뿐이고, 실제 200 을 확인하는 테스트가
커밋 ④ 까지 없다"고 남겼다. 그런데 **`MemberController:43` 이 클래스 레벨이라 커밋 ③ 에서 이미
`TYPE` 타깃이 실전 검증됐다** — `MemberControllerTest` 의 허용 경로 5건이 전부 클래스 레벨
`@CanManageMembers` 를 타고 200 을 냈다.

남은 미확인은 `student:manage`·`bus:manage` 두 경로다(전용 슬라이스 없음). 다만 세 애너테이션의
메커니즘이 동일하므로 위험도는 설계가 적은 것보다 낮다. `BusController` 는
**클래스 레벨 + 메서드 레벨 공존**이라 성격이 다르니 커밋 ④ 에서 슬라이스 신설을 여전히 권한다.

---

## 커밋 `5e6c6c5` (후속) — `RoleHierarchy` 빈을 `static` 으로

컨트롤러가 Spring Security **7.0 공식 레퍼런스**를 확인해 준 결과를 반영했다.

### 자동 연결은 실제로 됐다 — 대비책 불필요

문서의 `architecture.html` 예제는 `RoleHierarchy` 빈과 **함께**
`MethodSecurityExpressionHandler` 빈을 명시적으로 등록한다. 설계는 그게 불필요하다고
판단했는데(`PrePostMethodSecurityConfiguration` 이 `@Autowired(required=false)` 로 자동 연결),
**설계의 판단이 맞았다.**

증거는 커밋 ③ 의 음성 대조다. `MethodSecurityExpressionHandler` 를 등록하지 않은 상태에서

- `RoleHierarchy` 빈이 **있으면** 허용 경로 13건이 200 → 자동 연결이 됐다
- `RoleHierarchy` 빈을 **빼면** 정확히 그 13건이 403 → 200 을 만든 원인이 그 빈이다

즉 `RoleHierarchy` 빈 하나만으로 `hasAuthority` 확장이 동작한다.
**`MethodSecurityExpressionHandler` 빈은 추가하지 않았고, 추가할 필요도 없다.**
다음 사람이 문서 예제를 보고 "우리는 왜 이게 없지?" 하고 넣으려 할 수 있으니 여기 남긴다 —
넣어도 동작하겠지만 불필요한 중복이다.

### `static` 은 붙였다 — 이게 실제 수정 사항

문서의 `RoleHierarchy` 예제는 **전부 `@Bean static`** 이다. 이유가 실재한다.

메서드 시큐리티 인프라는 이 빈을 **BeanPostProcessor 단계**에서 참조한다. 인스턴스 메서드로 두면
그 시점에 `SecurityConfig` 자체가 먼저 인스턴스화돼야 하고, `SecurityConfig` 는 생성자로
`JwtAuthenticationFilter` 를 받으므로 그 필터까지 후처리가 끝나기 전에 끌려 나온다
("is not eligible for getting processed by all BeanPostProcessors" 부류의 문제).
`static` 이면 설정 클래스를 인스턴스화하지 않고 빈만 만들 수 있어 그 사슬이 끊긴다.

- 이 메서드는 `RolePermissions.HIERARCHY`(정적 상수)만 읽으므로 `static` 변환이 깨끗하다
- 옆의 `filterChain`·`corsConfigurationSource` 는 `jwtAuthenticationFilter`·`allowedOrigins`
  인스턴스 상태를 쓰므로 `static` 이 될 수 없다 → **이 클래스에서 유일한 `static @Bean` 이 된다**
- 유일하다는 점 때문에 다음 사람이 "일관성"을 이유로 지우기 쉬워, **왜 이것만 static 이고
  옆 것들은 될 수 없는지**를 javadoc 에 적었다

**슬라이스 테스트에서는 이 문제가 드러나지 않았다**(`static` 이전에도 217/0 통과, 경고 없음).
슬라이스는 최소 컨텍스트라 `JwtAuthenticationFilter` 체인이 얕기 때문으로 보인다.
즉 **이 수정은 테스트가 잡아 준 게 아니라 문서 근거로 선제 적용한 것**이다.
전체 앱 컨텍스트(`BackendApplicationTests`)는 Docker 가 필요해 확인하지 못했다 — §미확인 참조.

`static` 적용 후 허용 경로 13건 전부 통과 유지, 전체 38/217/0.

### 부여표 형식이 문서화된 공개 용법임을 확인

문서 `method-security.html` 에 다음 예제가 그대로 실려 있다.

```java
@Bean
static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy("ROLE_ADMIN > permission:read");
}
```

우변이 `ROLE_` 접두어 없는 permission 문자열이다. 설계가 소스 직독(`RoleHierarchyImpl` 의
`split("\\s+>\\s+")`)으로만 뒷받침하던 "이건 트리가 아니라 임의 문자열 도달성 맵"이라는 주장이
**문서가 권장하는 용법**으로 확인됐다. 버전 업에서 조용히 깨질 위험이 낮다.

### `hasAuthority` 를 쓴 것도 맞다

문서가 명시하듯 `hasRole('X')` 는 `hasAuthority('ROLE_X')` 의 단축형이다.
permission 을 `hasRole` 로 쓰면 `ROLE_student:manage` 를 찾게 돼 절대 매칭되지 않는다.
애너테이션 20개는 전부 `hasAuthority` 로 작성했으므로 이 함정에 걸리지 않는다.

---

## 검증 방법과 전체 결과

각 커밋마다:

```bash
cd backend && ./gradlew compileJava compileTestJava   # 통과
cd backend && ./gradlew test                          # 통과
```

최종(HEAD = `5e6c6c5`), 캐시 없이 전부 재실행:

```
./gradlew test --rerun-tasks
→ BUILD SUCCESSFUL
→ 38 클래스 / 217 테스트 / 실패 0 / 에러 0
```

기준선 37/208 대비 **+1 클래스 / +9 테스트**(전부 신규 `RolePermissionsTest`). **Docker 미사용.**

### ⚠️ 중간에 나온 17건 실패는 환경 문제였다 (코드 결함 아님)

`./gradlew clean test` 를 한 번 돌렸을 때 17건이 실패했다. 원인은 **같은 작업 디렉토리에서 다른
에이전트가 동시에 Gradle 을 돌린 것**이다. 실패 스택이 전부
`FileNotFoundException at ClassPathResource` / `ClassNotFoundException` 이었다 —
테스트 JVM 이 도는 중에 `build/` 가 지워질 때 나오는 신호다.
실제로 같은 시각에 다른 에이전트의 커밋(`5400e69`)이 브랜치에 들어왔다.

`--rerun-tasks` 로 재실행하니 217/0 이었고, 그 사이 실패했던 파일들의 결과 XML 도 0 실패로 덮였다.
**코드 결함이 아니다.** 다만 이 저장소에서 여러 에이전트가 동시에 작업 중이면
`./gradlew clean` 은 피하는 게 좋다.

---

## 브리프 준수 확인

| 요구 | 결과 |
|---|---|
| permission 20개 안 채택 | ✅ 20개 그대로 |
| `RoleHierarchy` 빈을 `SecurityConfig` 안에 | ✅ 슬라이스 6개 `@Import` 무수정 |
| `ROLE_ → ROLE_` 간선 0개 강제 | ✅ 헬퍼로 구조적 강제 + 테스트 2종으로 고정 |
| `@Target` 에 `TYPE` 포함 (3개) | ✅ 클래스 레벨 `@CanManageMembers` 실전 검증까지 완료 |
| `RouteController:61` 무보호 미변경 | ✅ 손대지 않음 (이번 범위 밖) |
| 커밋 ④~⑧ 미착수 | ✅ 37곳 그대로 남김 |
| 권한 증감 0 | ✅ 허용 역할 집합이 완전히 같은 것만 묶음 |
| 기존 테스트 수정 0 | ✅ 0개 |
| `Co-Authored-By` 없음 / 한글 커밋 | ✅ |
| 미추적 파일 커밋 안 함 | ✅ 생성 스크립트는 스크래치패드에 둠 |
| Docker 미사용 | ✅ |

`ROLE_ → ROLE_` 금지 근거는 브리프 지시대로 **부여표 파일(`RolePermissions.java`) javadoc 에
전문으로 적었다** — 두 가지 이유(서비스 계층과 답이 갈림 / 한 줄 추가로 `ride:record` 가 관리자에게
조용히 열림)를 구체적인 파일 이름과 함께 남겼다.

---

## 우려사항 / 다음 사람에게

1. **`grep '@PreAuthorize'` 는 39를 반환한다** — 실제 애너테이션 37 + javadoc 언급 2
   (`BusController:50`, `RoutingController:37`). 설계 §6 커밋 ⑦ 의 검산식
   (`grep '@PreAuthorize(' → 20건 + 1건`)을 쓸 때 이 2건을 함께 정리해야 깨끗해진다.

2. **`operations:monitor` 는 도메인 경계를 넘는다** (설계 §7-8). 이번에 그중 5곳 중 5곳을 전부
   치환했다(Location 2 · RideEvent 1 · DriveSession 1 · Notification 1 → Notification 은 커밋 ⑦).
   실제로 붙여 보니 이름이 도메인을 넘는 것이 읽기에 어색하진 않았다. 쪼갤지는 사용자 판단.

3. **`RolePermissionsTest` 의 `hasSize(33)` 은 부여표를 바꿀 때 함께 고쳐야 한다.**
   의도적으로 깨지게 두었다 — 부여표 변경이 무의식적으로 지나가지 않게 하는 장치다.

4. **WebSocket 은 여전히 간접층 밖이다** (설계 §7-2). `StompAuthChannelInterceptor:77` 은
   `@PreAuthorize` 를 타지 않아 새 역할 추가 시 손으로 봐야 한다. 이 사실을
   `RolePermissions` javadoc "새 역할을 추가할 때" 절에 명시해 두었다.

5. **커밋 ④ 이전에 `BusController` 슬라이스 신설을 권한다.** 클래스 레벨 + 메서드 레벨 공존이
   이 저장소에서 유일하고, 커밋 ④ 가 그 조합을 두 애너테이션으로 바꾸는 유일한 지점이다.
   `MemberController` 로 검증된 것은 "클래스 레벨 단독"이지 "공존"이 아니다.

6. **`static @Bean RoleHierarchy` 를 지우지 마라.** 이 클래스에서 유일한 `static @Bean` 이라
   일관성을 이유로 제거될 위험이 있다. 근거는 javadoc 에 적어 뒀다.
   **`static` 여부는 슬라이스 테스트가 잡아 주지 않는다** — 있으나 없으나 217/0 이다.
   회귀해도 조용하다는 뜻이니 코드 리뷰에서 봐야 한다.

7. **`MethodSecurityExpressionHandler` 빈은 추가하지 않았고 필요 없다.** 공식 문서 예제에는
   있지만 우리는 자동 연결이 동작함을 음성 대조로 확인했다. 문서를 보고 "왜 없지?" 하며
   넣지 마라 — 동작은 하겠지만 불필요한 중복이다.

## ※ 미확인

- **전체 앱 컨텍스트를 띄워 보지 못했다.** `BackendApplicationTests` 는 Postgres(Docker)가 필요한데
  전역 규칙상 Docker 를 임의로 켜지 않았다. `static` 이 해결한다고 서술한 BeanPostProcessor 조기
  초기화 문제는 **문서 근거에 기반한 선제 조치**이고, 실제로 그 경고가 이 앱에서 났는지는
  확인하지 않았다. Docker 를 켤 수 있을 때 `./gradlew build` 로 컨텍스트 로드 한 번 확인하면 좋다.
- **커밋 ④~⑦ 의 37곳은 손대지 않았다.** 이 리포트의 검증 범위는 치환한 24곳뿐이다.
