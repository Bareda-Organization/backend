package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 관계자 가입 승인(API_SPEC §6.4·§6.5 {@code /admin/staff-signup-requests})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#STAFF_APPROVE} 는 메인 관리자만 보유한다({@link RolePermissions}).
 * {@link CanManageAcademy}(학원 CRUD)와 나눠 둔 이유는 §6.2 권한 카탈로그가 두 권한을 나눠 정의하기
 * 때문이며, 합치면 "학원은 만들 수 있으나 관계자 승인은 못 한다" 는 배분이 표현 불가가 된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.STAFF_APPROVE + "')")
public @interface CanApproveStaff {
}
