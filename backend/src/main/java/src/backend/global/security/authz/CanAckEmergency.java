package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학원 관계자 화면의 비상 알림 조회·확인(EXC-04, Phase 11 T2 목표 10)에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#EMERGENCY_ACK} 는 학원 관계자만 보유한다({@link RolePermissions}, Ruling 884) — 확인 주체가 발신 학원의
 * 관계자로 한정돼야 "학원이 확인했습니다" 의 뜻이 맞는다. 메인 관리자는 열람만 하며(전용 콘솔 {@code /admin/emergencies} 는
 * {@link CanMonitorAll}) 이 애너테이션이 붙은 조회·확인에는 {@code 403} 이다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.EMERGENCY_ACK + "')")
public @interface CanAckEmergency {
}
