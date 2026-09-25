package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학부모·학생·매니저 비밀번호 초기화(API_SPEC §5.22 {@code POST /staff/accounts/{accountId}/password-reset})에
 * 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#ACCOUNT_PASSWORD_RESET} 는 학원 관계자만 보유한다({@link RolePermissions}) — 관계자
 * 계정의 초기화는 메인 관리자 경로(§6.7 {@link CanManageStaffAccount})라 권한을 나눈다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.ACCOUNT_PASSWORD_RESET + "')")
public @interface CanResetAccountPassword {
}
