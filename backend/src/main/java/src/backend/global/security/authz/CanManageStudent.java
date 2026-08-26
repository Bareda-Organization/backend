package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학생 등록 · 수정 · 퇴원(API_SPEC §5.11 {@code POST · PATCH · DELETE /staff/students})에 붙는
 * 메타 애너테이션.
 *
 * <p>{@link Permissions#STUDENT_WRITE} 는 학원 관계자만 보유한다({@link RolePermissions}) — 메인
 * 관리자도 학생 정보를 <b>읽을</b> 수는 있지만 쓰지는 못하므로, 읽기 애너테이션
 * ({@link CanReadStudentRecord})과 하나로 겸하면 그 경계가 사라진다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.STUDENT_WRITE + "')")
public @interface CanManageStudent {
}
