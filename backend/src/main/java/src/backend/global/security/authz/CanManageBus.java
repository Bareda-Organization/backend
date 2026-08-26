package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 차량 등록·관리(API_SPEC §5.12 {@code /staff/buses})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#BUS_MANAGE} 는 학원 관계자만 보유한다({@link RolePermissions}) — 메인
 * 관리자에게 부여되지 않은 것이 사양이라, 차량은 학원이 스스로 관리하고 콘솔은 관제만 한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.BUS_MANAGE + "')")
public @interface CanManageBus {
}
