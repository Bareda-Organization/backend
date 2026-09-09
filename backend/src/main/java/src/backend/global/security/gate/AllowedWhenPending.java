package src.backend.global.security.gate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code pending} 계정도 호출할 수 있는 핸들러 표시(API_SPEC §1.4 — 승인 대기 조회 · 로그아웃 2개).
 * {@code rejected} 계정도 이 표시가 붙은 핸들러를 그대로 통과한다 — rejected 의 허용 집합이
 * pending 의 허용 집합을 포함하기 때문에({@link AccountStatusGateInterceptor} 판정 로직 참고),
 * 포함 관계를 표현하려고 {@link AllowedWhenRejected}를 나란히 붙일 필요가 없다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedWhenPending {
}
