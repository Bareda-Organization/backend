package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 발신한 비상 알림의 처리 상태 조회(API_SPEC §4.15 {@code GET /runs/{runId}/emergencies})에 붙는
 * 메타 애너테이션.
 *
 * <p>{@code hasAuthority(...)} 가 아니라 {@code isAuthenticated()} 인 이유는 {@link CanReadChangeRequest}
 * 와 같다 — 권한 카탈로그(FEATURE_SPEC §6.2)에 이 조회 전용 항목이 부재하다. {@link
 * Permissions#EMERGENCY_RAISE} 의 대상이 우연히 이 조회와 같은 기사·동승자이긴 하나, 발신(쓰기) 권한을
 * 조회(읽기)에 재사용하지 않는다(같은 판단, {@code CanReadChangeRequest} 가 {@code
 * CHANGE_REQUEST_WRITE} 를 재사용하지 않은 것과 동일).
 *
 * <p>좁히는 것은 {@code run.access.RunAssignmentAccess#assertAssignedDriverOrEscort} 다 — 배치되지
 * 않은 회차(존재하지 않는 회차·다른 학원 회차 포함)는 {@code 403 FORBIDDEN}(§1.11)이다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface CanReadRunEmergencies {
}
