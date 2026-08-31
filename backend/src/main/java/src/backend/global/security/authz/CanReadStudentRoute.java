package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학부모 앱의 자녀 노선 조회(LOC-03, API_SPEC §3.10, "권한 학부모·학생")에 붙는 메타 애너테이션.
 *
 * <p>{@link CanReadRoute} 와 같은 {@link Permissions#ROUTE_READ} 를 쓴다 — 그 권한이 이미 전 역할
 * 보유라 매니저 조회({@code §4.3})와 겹치는 자리다. "어느 학생인가" 로 좁히는 것은 이 애너테이션이
 * 아니라 {@code student.access.LinkedChildLookup} 이다({@link CanManageWeeklyAddress} 와 같은
 * 판단 — 인가 애너테이션은 "무엇을 할 수 있는가" 만 답한다).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.ROUTE_READ + "')")
public @interface CanReadStudentRoute {
}
