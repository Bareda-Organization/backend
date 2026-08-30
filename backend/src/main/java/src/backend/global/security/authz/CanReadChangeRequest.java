package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 자녀의 변경 신청 상태 조회(API_SPEC §3.9 {@code GET /students/{id}/change-requests})에 붙는
 * 메타 애너테이션.
 *
 * <p>{@code hasAuthority(...)} 가 아니라 {@code isAuthenticated()} 인 이유는 <b>권한 카탈로그
 * (FEATURE_SPEC §6.2)에 이 조회에 대응하는 항목이 부재</b>하기 때문이다({@link CanManageWeeklyAddress}
 * 와 같은 형태의 판단 — 카탈로그에 있는 {@link Permissions#CHANGE_REQUEST_WRITE} 는 §3.8 신청
 * 자체에만 대응하고, 조회는 별도 항목이 없다).
 *
 * <p>좁히는 것은 {@code student.access.LinkedChildLookup} 이다 — 연결 부재 자녀는 {@code 403
 * FORBIDDEN} 이다(§3.9 에러 표).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface CanReadChangeRequest {
}
