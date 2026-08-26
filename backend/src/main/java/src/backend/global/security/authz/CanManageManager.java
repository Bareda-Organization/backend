package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 매니저(기사·동승자) 등록·수정·삭제(API_SPEC §5.13 {@code /staff/managers})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#MANAGER_MANAGE} 는 등록과 <b>배치</b>(MGR-05·06)를 함께 가리킨다 — 배치
 * 엔드포인트(§5.14)가 생기면 같은 애너테이션을 쓴다. 인력을 세우는 권한과 회차에 세우는 권한을
 * 가르면 관계자 1명뿐인 학원에서 어느 쪽도 혼자 끝내지 못한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.MANAGER_MANAGE + "')")
public @interface CanManageManager {
}
