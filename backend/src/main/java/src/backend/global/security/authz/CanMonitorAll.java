package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 메인 관리자 콘솔의 전 학원 관제(O-05·06)에 붙는 메타 애너테이션 — 첫 사용처는 비상 알림 콘솔
 * (Phase 11 T2 목표 11, {@code GET /admin/emergencies})이다.
 *
 * <p>{@link Permissions#MONITOR_ALL} 은 메인관리자만 보유한다({@link RolePermissions}) —
 * {@link CanAckEmergency}({@code EMERGENCY_ACK})를 그대로 재사용하지 않은 이유가 이것이다. 그
 * 권한은 학원 관계자에게도 있어, 콘솔 게이트로 쓰면 학원 관계자가 다른 학원의 비상 알림까지 보게
 * 되어 학원 격리 예외({@code AcademyScopeExempt})의 판정 지점이 두 갈래로 흩어진다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.MONITOR_ALL + "')")
public @interface CanMonitorAll {
}
