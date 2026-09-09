package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증 없이 호출 가능한 엔드포인트에 붙인다(API_SPEC §2.1·2.2·2.5·2.6·2.9 — 학원 검색·회원가입·
 * 로그인·토큰재발급·아이디비밀번호복구). {@code @PreAuthorize} 를 달지 않는 순수 표식(marker)
 * 애너테이션이다 — 실제 인증 생략은 {@code SecurityConfig} 의 {@code authorizeHttpRequests}
 * 매처(예: {@code requestMatchers("/api/v1/auth/login").permitAll()})가 강제한다(Task 2·3 소유).
 * SpEL 에는 {@code permitAll()} 이라는 표현식이 없어(그건 HttpSecurity 매처 개념이다)
 * {@code @PreAuthorize("permitAll()")} 로 흉내 내면 평가 시점에 예외가 난다.
 *
 * <p>이 애너테이션이 강제하는 것은 하나뿐이다 — {@code ControllerAuthorizationConventionTest}
 * 가 "이 메서드는 의도적으로 공개됐다"를 <b>허용 목록과 정확히 대조</b>하게 한다. 목록에 없는
 * 메서드에 이 애너테이션을 붙이면 실패하고, 목록에 있는데 애너테이션이 빠지면(엔드포인트가
 * 삭제됐거나 이름이 바뀌면) 그것도 실패한다 — 아무나 조용히 공개 범위를 넓히지 못하게 한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
}
