package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 자녀의 일일 변경 신청(API_SPEC §3.8 {@code POST /students/{id}/change-requests})에 붙는
 * 메타 애너테이션.
 *
 * <p>{@link Permissions#CHANGE_REQUEST_WRITE} 를 요구한다 — FEATURE_SPEC §6.2 권한 카탈로그가
 * 이 권한을 학부모에게만 부여한다.
 *
 * <p>좁히는 것은 {@code student.access.LinkedChildLookup} 이다 — 토큰의 계정으로 보호자를 찾고,
 * 연결된 자녀가 아니면 {@code 403 FORBIDDEN} 이다({@link CanManageWeeklyAddress} 와 같은 형태).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.CHANGE_REQUEST_WRITE + "')")
public @interface CanRequestChange {
}
