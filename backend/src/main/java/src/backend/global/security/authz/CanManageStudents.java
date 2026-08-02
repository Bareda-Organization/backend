package src.backend.global.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학생 등록·수정·퇴원·배정·보호자 연결 권한.
 *
 * <p>필요 권한 {@code student:manage}. 현재 부여 역할은 학원 관리자·플랫폼 관리자이지만,
 * <b>어느 역할이 이 권한을 갖는지는 {@link RolePermissions} 한 곳에서만 정한다</b> —
 * 그래서 이 애너테이션에는 역할 이름이 없고, 새 역할이 생겨도 이 파일과 컨트롤러는 바뀌지 않는다.
 *
 * <p>어느 학원의 학생인지는 이 애너테이션이 판정하지 않는다 — 서비스 계층의 TenantGuard 가 따로 본다.
 *
 * <p>클래스 레벨에도 붙일 수 있다. 스프링 시큐리티는 메서드 레벨 인가 애너테이션을 먼저 찾아
 * 있으면 즉시 쓰고 그때만 클래스 레벨을 보므로, 클래스 기본값을 특정 메서드에서 다른
 * 애너테이션으로 덮어쓰는 방식이 그대로 동작한다(둘이 충돌로 거부되지 않는다).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.STUDENT_MANAGE + "')")
public @interface CanManageStudents {
}
