package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 로그인 차단 계정 조회·해제(API_SPEC §6.10·§6.12 {@code /admin/blocked-accounts})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#ACCOUNT_UNBLOCK} 은 메인 관리자만 보유한다({@link RolePermissions}) — 차단은
 * 계정 단위이고 해제 권한이 학원 관계자에게 열리면 자기 학원 밖 계정까지 풀 수 있게 된다(C-11).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.ACCOUNT_UNBLOCK + "')")
public @interface CanUnblockAccount {
}
