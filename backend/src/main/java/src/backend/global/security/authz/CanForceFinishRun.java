package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 운행일이 지난 이동 중 회차의 강제 종료(API_SPEC §6.17, R47 Ruling 724)에 붙는 메타 애너테이션 — 메인관리자 전용이다.
 * 회차 상태를 실제로 바꾸는 쓰기라 조회 전용 {@link CanMonitorAll} 을 재사용하지 않고, 강제 확정의
 * {@link CanForceConfirmRun} 과도 갈라 {@link Permissions#RUN_FORCE_FINISH} 를 쓴다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.RUN_FORCE_FINISH + "')")
public @interface CanForceFinishRun {
}
