package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 엔드포인트가 요구하는 권한({@link Permissions} 상수)을 적는 인가 애너테이션 — 값이 둘 이상이면
 * 그중 하나만 보유해도 통과한다.
 *
 * <p>{@code {value}} 는 스프링 시큐리티의 애너테이션 템플릿이 값으로 바꾼다
 * ({@code SecurityConfig#annotationTemplateExpressionDefaults}). 값은 컴파일 타임 상수라
 * 오탈자는 런타임 403 이 아니라 컴파일 에러가 된다. 이 권한만으로 자원 단위(내 회차 · 내 자녀)를
 * 좁히지 못하는 경우의 좁힘은 {@code *.access} 계층의 몫이다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyAuthority('{value}'.split(','))")
public @interface Can {

    /** 요구하는 {@link Permissions} 상수. */
    String[] value();
}
