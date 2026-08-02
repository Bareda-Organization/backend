package src.backend.global.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 운행 시작·종료, 버스 위치 보고, 담당 버스 학생 위치 조회 권한.
 *
 * <p>필요 권한 {@code drive:operate}. 현재 부여 역할은 운전기사 단독이지만,
 * <b>어느 역할이 이 권한을 갖는지는 {@link RolePermissions} 한 곳에서만 정한다</b> —
 * 그래서 이 애너테이션에는 역할 이름이 없고, 새 역할이 생겨도 이 파일과 컨트롤러는 바뀌지 않는다.
 *
 * <p>선탑자에게는 부여되지 않는다. 담당 버스 학생 위치 조회를 {@link CanReadAssignedBus} 가 아니라 여기 둔 것도 그래서다 — 저쪽으로 옮기면 선탑자에게 없던 권한이 생긴다.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.DRIVE_OPERATE + "')")
public @interface CanOperateDrive {
}
