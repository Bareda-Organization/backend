package src.backend.global.security.gate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code rejected} 계정에게만 추가로 열리는 핸들러 표시(API_SPEC §1.4 — 재신청 1개).
 * {@link AllowedWhenPending} 이 붙은 핸들러는 이 표시 없이도 rejected 에게 이미 허용되므로,
 * 이 애너테이션은 {@code pending} 에게는 열리지 않는 핸들러에만 붙인다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedWhenRejected {
}
