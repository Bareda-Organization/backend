package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 매니저 앱의 회차 목록·명단 조회(API_SPEC §4.1 {@code GET /manager/runs} · §4.2
 * {@code GET /runs/{runId}/roster})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#ROSTER_READ} 는 기사 · 동승자 · 학원 관계자 · 메인 관리자가 함께 보유한다
 * ({@link RolePermissions}) — 이 권한만으로는 "그 매니저가 배치된 회차인가" 를 좁히지 못한다. 그
 * 좁히는 자리는 {@code manager.access.ManagerRunAccess} 다({@link CanReadChangeRequest} ·
 * {@code student.access.GuardianChildAccess} 와 같은 형태 — 카탈로그 권한은 넓게, 자원 단위 판정은
 * 별도 계층에서).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.ROSTER_READ + "')")
public @interface CanReadRoster {
}
