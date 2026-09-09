package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 고정 노선 편성·최적화(API_SPEC §5.9 {@code /staff/routes})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#ROUTE_MANAGE} 는 학원 관계자만 보유한다({@link RolePermissions}).
 *
 * <p><b>조회에도 {@code ROUTE_READ} 가 아니라 이것을 쓴다.</b> {@code ROUTE_READ} 는 학부모·학생·
 * 기사·동승자까지 전 역할이 가진 권한이라, 목록·상세에 그것을 걸면 {@code /staff/routes} 가 전 역할에
 * 열린다 — 이 경로가 돌려주는 것은 학원의 <b>편성 전체</b>이고 학부모·기사가 보는 노선은 자기 회차로
 * 좁혀진 별도 경로(§3.10 · §4.3)다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.ROUTE_MANAGE + "')")
public @interface CanManageRoute {
}
