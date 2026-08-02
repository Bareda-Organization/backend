package src.backend.global.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 자녀의 위치·승하차·알림·신고·요청 이력 조회 권한.
 *
 * <p>필요 권한 {@code guardian:children:read}. 현재 부여 역할은 학부모이지만,
 * <b>어느 역할이 이 권한을 갖는지는 {@link RolePermissions} 한 곳에서만 정한다</b> —
 * 그래서 이 애너테이션에는 역할 이름이 없고, 새 역할이 생겨도 이 파일과 컨트롤러는 바뀌지 않는다.
 *
 * <p>관리자에게는 부여되지 않는다 — 관리자는 학원 단위로 보는 {@link CanMonitorOperations} 를 쓴다. 실제로 누가 이 사람의 자녀인지는 서비스 계층에서 보호자 연결을 확인한다.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.GUARDIAN_CHILDREN_READ + "')")
public @interface CanReadOwnChildren {
}
