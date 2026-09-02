package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 관계자 웹의 알림 로그 전수 조회(API_SPEC §5.17 {@code /staff/notifications})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#NOTIFICATION_LOG_READ} 는 {@link RolePermissions} 에서 이미
 * {@code Role.STAFF} 에 부여돼 있다 — 이 애너테이션은 그 권한 문자열을 오타 없이 참조하기 위한 래퍼일
 * 뿐이고, 권한 부여 자체를 새로 하지 않는다({@link CanReadReport} 와 같은 형태).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.NOTIFICATION_LOG_READ + "')")
public @interface CanReadNotificationLog {
}
