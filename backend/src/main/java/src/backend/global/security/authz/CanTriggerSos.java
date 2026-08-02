package src.backend.global.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * SOS 발신 권한.
 *
 * <p>필요 권한 {@code sos:trigger}. 현재 부여 역할은 학생이지만,
 * <b>어느 역할이 이 권한을 갖는지는 {@link RolePermissions} 한 곳에서만 정한다</b> —
 * 그래서 이 애너테이션에는 역할 이름이 없고, 새 역할이 생겨도 이 파일과 컨트롤러는 바뀌지 않는다.
 *
 * <p>따로 떼어 둔 이유는 유일한 긴급 알림 유발 기능이라 향후 선탑자·기사에게 열릴 가능성이 가장 높기 때문이다.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.SOS_TRIGGER + "')")
public @interface CanTriggerSos {
}
