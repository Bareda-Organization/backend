package src.backend.global.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학원 상세 조회 권한.
 *
 * <p>필요 권한 {@code tenant:read}. 현재 부여 역할은 학원 관리자·플랫폼 관리자이지만,
 * <b>어느 역할이 이 권한을 갖는지는 {@link RolePermissions} 한 곳에서만 정한다</b> —
 * 그래서 이 애너테이션에는 역할 이름이 없고, 새 역할이 생겨도 이 파일과 컨트롤러는 바뀌지 않는다.
 *
 * <p>학원을 만들고 고치는 것은 플랫폼 관리자 전용인 {@link CanManageTenants} 로 갈라져 있다.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.TENANT_READ + "')")
public @interface CanReadTenant {
}
