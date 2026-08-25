package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학부모·학생·기사·동승자 가입 승인(API_SPEC §5.1·§5.2 {@code /staff/signup-requests})에 붙는
 * 메타 애너테이션.
 *
 * <p>{@link Permissions#SIGNUP_APPROVE} 는 학원 관계자만 보유한다({@link RolePermissions}) —
 * 관계자(`role=staff`) 가입 승인은 별개 권한({@link Permissions#STAFF_APPROVE})이라
 * {@link CanApproveStaff} 가 따로 있다. 하나로 겸하면 관계자가 자기 후임을 승인할 수 있게 된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.SIGNUP_APPROVE + "')")
public @interface CanApproveSignup {
}
