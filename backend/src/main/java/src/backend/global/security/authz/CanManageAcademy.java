package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학원 등록·조회·수정·비활성화(API_SPEC §6.1~§6.3 {@code /admin/academies})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#ACADEMY_MANAGE} 는 메인 관리자만 보유한다({@link RolePermissions}). 상수와
 * 부여표는 Phase 2 가 이미 만들었으나 그것을 컨트롤러에 표현할 자리가 부재했다 — 컨트롤러가 역할
 * 문자열을 쓰지 않고 인가를 표현하는 유일한 수단이 이 애너테이션이다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.ACADEMY_MANAGE + "')")
public @interface CanManageAcademy {
}
