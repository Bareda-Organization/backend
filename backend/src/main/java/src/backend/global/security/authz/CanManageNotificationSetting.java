package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 알림 수신 설정 조회·수정(API_SPEC §3.14 {@code GET · PATCH /me/notification-settings})에 붙는
 * 메타 애너테이션.
 *
 * <p>{@link Permissions#NOTIFICATION_SETTING_WRITE} 를 요구한다 — 조회에도 같은 값을 쓰는 이유는
 * READ 전용 권한 상수가 카탈로그에 부재하고, 이 값이 이미 학부모·학생 둘 다에게 부여돼(
 * {@code RolePermissions}) 두 역할을 함께 허용하는 목적에 그대로 맞기 때문이다({@link CanWriteIntent}
 * 도 같은 축 재사용 방식).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.NOTIFICATION_SETTING_WRITE + "')")
public @interface CanManageNotificationSetting {
}
