package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 감사·접속 이력 조회 권한(SYS-01·02, API_SPEC §6.13) — {@link CanMonitorAll} 을 본떠 만든다
 * (Phase 14 T1 목표 3·4).
 *
 * <p>{@link Permissions#AUDIT_READ} 은 메인관리자만 보유한다({@code RolePermissions}) — 감사 이력은
 * 학원 관계자 화면이 아니라 운영사 콘솔 전용이라, 학원 격리 예외도 {@code /admin} 계열과 같은 근거로 연다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.AUDIT_READ + "')")
public @interface CanReadAudit {
}
