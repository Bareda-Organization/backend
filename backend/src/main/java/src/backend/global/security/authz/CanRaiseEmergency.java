package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 비상 알림 발신·취소(EXC-04, Phase 11 T2 목표 5·9)에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#EMERGENCY_RAISE} 는 기사·동승자만 보유한다({@link RolePermissions}) — 두
 * 역할 모두 발신할 수 있어야 한다는 목표 5 요구가 이미 그 부여표에 반영돼 있다. 취소도 같은
 * 애너테이션을 쓴다 — 발신한 사람이 스스로 물리는 동작이라 별도 권한을 두지 않는다.
 *
 * <p>이 회차에 실제로 배치됐는지는 이 애너테이션이 아니라
 * {@link src.backend.run.access.RunAssignmentAccess#assertAssignedDriverOrEscort} 가 서비스
 * 계층에서 확인한다 — 권한 카탈로그는 "이 기능을 쓸 수 있는 역할인가"만 답하고, "이 회차 담당자인가"는
 * 인가가 아니라 자원 소유의 문제라 응답 코드가 다르다({@code FORBIDDEN}, 배치 여부를 드러내지 않음).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.EMERGENCY_RAISE + "')")
public @interface CanRaiseEmergency {
}
