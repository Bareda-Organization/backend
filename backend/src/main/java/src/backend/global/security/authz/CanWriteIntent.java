package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 회차별 탑승 토글(API_SPEC §3.6 {@code PATCH /students/{id}/runs/{runId}/intent})에 붙는
 * 메타 애너테이션.
 *
 * <p>{@link Permissions#INTENT_WRITE} 를 요구한다 — 권한 카탈로그(FEATURE_SPEC §6.2)에서
 * 이 권한의 보유 역할이 학부모뿐이라({@code RolePermissions}), 학생 계정 호출은 이 애너테이션
 * 단계에서 이미 {@code 403} 이 된다(§3.6 에러 표의 "학생 계정 호출 포함"). 어느 자녀인지 좁히는
 * 것은 {@code student.access.GuardianChildAccess} 의 몫이라 이 애너테이션은 관여하지 않는다
 * ({@link CanReadLinkedChild} 와 같은 축 분리).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.INTENT_WRITE + "')")
public @interface CanWriteIntent {
}
