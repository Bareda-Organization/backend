package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * ②구간 승인 대기 목록·상세·결정(API_SPEC §5.5·§5.6 {@code /staff/approvals})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#CHANGE_APPROVE} 는 학원 관계자만 보유한다({@link RolePermissions}).
 * {@code /staff/approvals} 는 학부모·학생이 낸 변경 신청을 승인·거절하는 관리 화면이라, 신청 본인이
 * 조회하는 경로({@code CHANGE_REQUEST_WRITE})와는 다른 권한을 요구한다 — {@link CanManageRoute} 가
 * {@code ROUTE_READ}(전 역할 보유)를 쓰지 않는 것과 같은 이유다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.CHANGE_APPROVE + "')")
public @interface CanApproveChange {
}
