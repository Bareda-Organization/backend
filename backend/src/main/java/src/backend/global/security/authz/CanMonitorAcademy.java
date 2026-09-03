package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 관계자 웹의 학원 단위 관제(Phase 13)에 붙는 메타 애너테이션 — 첫 사용처는 대시보드(§5.3
 * {@code GET /staff/dashboard})와 실시간 회차 스냅샷(§5.18 {@code GET /staff/runs/live})이다.
 *
 * <p>{@link CanReadStaffRoster}({@code STUDENT_READ_SENSITIVE})를 재사용하지 않은 이유 — 그 권한은
 * 명단의 민감정보(마스킹 대상 밖 보호자 연락처) 표시 규칙이 바뀌는 계기를 따라 부여·회수되고,
 * 이 애너테이션이 지키는 관제 화면(회차 진행률·위치·확인 응답 현황)은 그 표시 규칙과 무관하게
 * 바뀐다({@code StaffRosterController} 자바독이 {@code CanReadRoster} 와 {@code CanReadStaffRoster}
 * 를 가른 것과 같은 논리 — 대상 화면이 다르면 바뀌는 계기도 다르다는 것이 애너테이션을 가르는 기준).
 * {@link Permissions#MONITOR_ACADEMY} 는 이미 {@link RolePermissions} 에서 {@code Role.STAFF} 에
 * 부여돼 있다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.MONITOR_ACADEMY + "')")
public @interface CanMonitorAcademy {
}
