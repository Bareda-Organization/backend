package src.backend.global.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학원 생성·전체 목록 조회·depot 좌표 설정 권한.
 *
 * <p>필요 권한 {@code tenant:manage}. 현재 부여 역할은 플랫폼 관리자이지만,
 * <b>어느 역할이 이 권한을 갖는지는 {@link RolePermissions} 한 곳에서만 정한다</b> —
 * 그래서 이 애너테이션에는 역할 이름이 없고, 새 역할이 생겨도 이 파일과 컨트롤러는 바뀌지 않는다.
 *
 * <p>학원 관리자에게는 부여되지 않는다 — 학원 관리자가 할 수 있는 것은 자기 학원 상세를 보는 {@link CanReadTenant} 까지다.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.TENANT_MANAGE + "')")
public @interface CanManageTenants {
}
