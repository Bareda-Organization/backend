package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학원 관계자 화면의 비상 알림 조회·확인(EXC-04, Phase 11 T2 목표 10)에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#EMERGENCY_ACK} 는 학원 관계자와 메인관리자 둘 다 보유한다({@link
 * RolePermissions}) — 목록 조회를 확인과 같은 권한으로 묶는다(볼 수 있어야 확인도 할 수 있다는
 * 전제). 메인관리자 전용 콘솔({@code /admin/emergencies})은 이 애너테이션을 쓰지 않는다 —
 * {@link CanMonitorAll} 참고(학원 관계자가 함께 통과해 버리면 학원 격리 예외가 의도보다 넓어진다).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.EMERGENCY_ACK + "')")
public @interface CanAckEmergency {
}
