package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 매니저 앱의 회차 경로 조회(API_SPEC §4.3 {@code GET /runs/{runId}/route}, RUN-03·M-08·M-09,
 * Ruling 205)에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#ROUTE_READ} 는 전 역할이 보유한다({@link RolePermissions}) — 학부모 · 학생도
 * 포함된다. 이 애너테이션만으로는 그 요청 주체가 <b>이 회차에 배치된 매니저</b>인지 좁히지 못하고,
 * 그 판정은 {@code manager.access.ManagerRunAccess} 가 한다({@link CanReadRoster} 와 같은 근거) —
 * 학부모 · 학생 토큰은 여기를 통과해도 매니저 레코드가 없어 그 계층에서 {@code 403} 이 된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.ROUTE_READ + "')")
public @interface CanReadRoute {
}
