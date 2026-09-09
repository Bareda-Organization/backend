package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 강제 확정 콘솔 개입(API_SPEC §6.14, F3 S2 목표 11)에 붙는 메타 애너테이션 — idle 로 정체된
 * 회차를 강제 폴백으로 확정하는 것은 메인관리자 전용 개입이다.
 *
 * <p>{@link Permissions#RUN_FORCE_CONFIRM} 을 별도 상수로 둔 이유는 {@link CanMonitorAll}
 * ({@code MONITOR_ALL})을 재사용하지 않기 위함이다 — 그 권한은 조회 전용이고, 이 애너테이션이
 * 게이트하는 동작은 회차 상태를 실제로 바꾸는 쓰기다({@link RolePermissions} 부여표 참고).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.RUN_FORCE_CONFIRM + "')")
public @interface CanForceConfirmRun {
}
