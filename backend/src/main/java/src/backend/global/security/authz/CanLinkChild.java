package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 자녀 연결 3단계(API_SPEC §3.2·§3.3·§3.4 — 요청 · 코드 생성 · 코드 입력)에 붙는 메타 애너테이션.
 *
 * <p>{@code hasAuthority(...)} 가 아니라 {@code isAuthenticated()} 인 이유는 <b>권한 카탈로그
 * (FEATURE_SPEC §6.2)에 자녀 연결에 대응하는 항목이 부재</b>하기 때문이다. 카탈로그는 사양 정본이고
 * {@code RolePermissionsTest} 가 역할별 집합을 그 표와 정확히 대조하므로, 32번째 권한을 여기서
 * 지어내면 코드와 정본이 갈린다({@link AuthenticatedOnly} 와 같은 형태의 판단이다).
 *
 * <p><b>역할 좁힘은 자원 해석이 한다.</b> 학부모 경로는 토큰의 계정으로 {@code guardian} 을,
 * 학생 경로는 {@code student} 를 찾고 없으면 {@code 403 FORBIDDEN} 이다
 * ({@code student.access.GuardianChildAccess} · {@code ChildLinkCommandService}). 역할 문자열을
 * 표현식에 적는 길은 {@code ControllerAuthorizationConventionTest} 가 막고 있고, 막는 이유가
 * "역할이 하나 늘 때 전수 수정" 이라 그 규칙을 여기서만 깨뜨릴 근거가 부재하다.
 *
 * <p>{@link AuthenticatedOnly} 를 재사용하지 않은 것은 그 애너테이션의 근거가 "전 역할 공통이라
 * 구분할 권한 자체가 없다" 인 반면 이쪽은 "역할은 갈리는데 카탈로그에 항목이 없다" 로 <b>다른
 * 사유</b>이기 때문이다. 하나로 합치면 다음 사람이 어느 사유로 붙었는지 알 수단이 사라진다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface CanLinkChild {
}
