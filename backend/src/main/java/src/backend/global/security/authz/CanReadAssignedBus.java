package src.backend.global.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 담당 버스의 배차·명단·운행 이력·승하차·배포 노선 조회 권한.
 *
 * <p>필요 권한 {@code crew:assigned:read}. 현재 부여 역할은 운전기사·선탑자이지만,
 * <b>어느 역할이 이 권한을 갖는지는 {@link RolePermissions} 한 곳에서만 정한다</b> —
 * 그래서 이 애너테이션에는 역할 이름이 없고, 새 역할이 생겨도 이 파일과 컨트롤러는 바뀌지 않는다.
 *
 * <p>"기사인가"만 볼 뿐 "이 버스의 담당인가"는 모른다 — 그 판정은 서비스 계층의 BusCrewGuard 가 한다.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.CREW_ASSIGNED_READ + "')")
public @interface CanReadAssignedBus {
}
