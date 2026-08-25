package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 단말 등록·해지(API_SPEC §2.11 {@code POST/DELETE /me/devices})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#DEVICE_REGISTER} 는 전 역할이 보유하지만, 컨트롤러가 이 애너테이션을
 * 붙이지 않으면 {@code ControllerAuthorizationConventionTest} 가 "인가 애너테이션 없는 매핑
 * 메서드"로 잡는다 — 전 역할 보유라 해서 인가 표현 자체를 생략할 수 있는 것은 아니다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.DEVICE_REGISTER + "')")
public @interface CanRegisterDevice {
}
