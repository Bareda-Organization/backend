package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * "인증만 되면 되고, 권한(permission)으로 더 나눌 게 없는" 엔드포인트에 붙인다
 * (API_SPEC §2.3·2.4·2.7·2.8·2.10 — 승인대기 조회·재신청·로그아웃·비밀번호변경·본인프로필).
 *
 * <p>계정 상태 게이트({@code pending}·{@code rejected}·{@code blocked} 판정)와는
 * <b>축이 다르다.</b> 이 애너테이션은 "인증이 필요한가"만 답하고, "그 계정이 지금 호출 가능한
 * 상태인가"는 답하지 않는다 — 두 판단을 하나로 겸하면 계정 상태 규칙이 바뀔 때마다 인가
 * 표현까지 함께 흔들린다. 예를 들어 §2.4(재신청)는 이 애너테이션에 더해 "rejected 계정만"
 * 이라는 상태 제약이 있는데, 그 제약은 계정 상태 게이트가 별도로 강제한다.
 *
 * <p>{@code hasAuthority(...)} 가 아니라 {@code isAuthenticated()} 를 쓴다 — 권한 카탈로그
 * ({@link Permissions})에는 "인증 여부"에 대응하는 항목이 없다(전 역할 공통이라 구분할 권한
 * 자체가 없는 것이 이 애너테이션이 존재하는 이유다).
 *
 * <p>역할은 갈리는데 카탈로그에 대응 항목이 없는 엔드포인트(자녀 연결 · 변경 요청 조회 · 회차 비상
 * 목록 · 학생 버스 위치 · 주소 변경 등)도 같은 식을 쓴다. 권한 카탈로그(FEATURE_SPEC §6.2)는 사양
 * 정본이고 {@code RolePermissionsTest} 가 역할별 집합을 그 표와 대조하므로, 여기서 권한을 지어내지
 * 않는다. 역할 좁힘은 {@code *.access} 계층의 자원 해석(예: {@code GuardianChildAccess})이 하고,
 * 컨트롤러에 역할 문자열을 적는 길은 {@code ControllerAuthorizationConventionTest} 가 막는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface AuthenticatedOnly {
}
