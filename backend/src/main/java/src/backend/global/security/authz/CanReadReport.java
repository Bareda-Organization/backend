package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 관계자 웹의 예외 보고 목록·상세 조회(API_SPEC §5.20 {@code /staff/reports})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#EXCEPTION_REPORT_READ} 는 관계자만 보유한다({@link RolePermissions}).
 * {@link Permissions#EXCEPTION_REPORT}(보고 작성, 기사·동승자 보유)와 다른 권한을 쓰는 이유는
 * {@link CanApproveChange} 가 {@code CHANGE_REQUEST_WRITE}(신청 본인)와 {@code CHANGE_APPROVE}
 * (관리 화면)를 가르는 것과 같다 — 쓰는 쪽과 조회하는 쪽의 역할이 겹치지 않는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.EXCEPTION_REPORT_READ + "')")
public @interface CanReadReport {
}
