package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 회차 1건의 확정·예정 노선 조회(API_SPEC §5.19 {@code GET /staff/runs/{runId}/route})에 붙는다 —
 * <b>학원 관계자와 메인 관리자가 같이 쓰는 유일한 관제 조회</b>다(R22, 2026-09-20 사용자 승인).
 *
 * <p>{@link CanMonitorAcademy} 를 그대로 쓰지 않는 이유 — 그 권한은 {@code Role.STAFF} 에만 있고
 * {@code Role.SYSTEM_ADMIN} 은 {@link Permissions#MONITOR_ALL} 을 따로 갖는다({@link RolePermissions}
 * 가 역할 간 상속을 금지한 결과다). 그래서 메인 관리자의 전체 관제 화면(§6.8)에서 버스를 눌러
 * 노선을 그리려 하면 이 엔드포인트가 {@code 403 FORBIDDEN} 으로 막혔다 — 화면은 존재하는데 기능이
 * 통째로 동작하지 않았다(2026-09-20 브라우저 실측).
 *
 * <p>{@code CanMonitorAcademy} 쪽 표현식을 넓히지 <b>않는다</b>. 그 애너테이션은 관계자 대시보드
 * (§5.3)·실시간 회차(§5.18)에도 붙어 있고, 그 두 조회는 {@code requester.academyId()} 로 범위를
 * 좁히므로 {@code academyId} 가 {@code null} 인 메인 관리자가 들어가면 빈 결과가 조용히 나온다.
 * 넓히는 범위를 이 엔드포인트 하나로 가둔다.
 *
 * <p>학원 격리는 그대로다 — 어느 권한으로 들어오든 {@code AcademyScope#assertAccessible} 이 회차의
 * 소속 학원을 대조하고, 메인 관리자만 그 대조를 통과한다(§1.5).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyAuthority('" + Permissions.MONITOR_ACADEMY + "', '" + Permissions.MONITOR_ALL + "')")
public @interface CanMonitorRunRoute {
}
