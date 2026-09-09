package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 운행 스케줄·일일 회차 관리(API_SPEC §5.10 {@code /staff/schedules} · {@code /staff/runs})에 붙는
 * 메타 애너테이션.
 *
 * <p>{@link Permissions#SCHEDULE_MANAGE} 는 학원 관계자만 보유한다({@link RolePermissions}) — 메인
 * 관리자에게 부여되지 않은 것이 사양이라, 운행 계획은 학원이 스스로 세우고 콘솔은 관제만 한다.
 *
 * <p>회차 <b>배치</b>(§5.14)는 이 권한이 아니라 {@link CanManageManager}({@code MANAGER_MANAGE}) 다 —
 * FEATURE_SPEC §6.2 가 "매니저(기사·동승자) 등록·<b>배치</b>" 를 그 권한의 설명으로 적는다. 경로가
 * {@code /staff/runs} 아래라는 이유로 이쪽에 묶으면 권한 카탈로그와 코드가 갈린다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.SCHEDULE_MANAGE + "')")
public @interface CanManageSchedule {
}
