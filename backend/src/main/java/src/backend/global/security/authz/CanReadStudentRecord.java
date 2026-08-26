package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학생 목록 · 상세 조회(API_SPEC §5.11 {@code GET /staff/students})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#STUDENT_READ_BASIC} 이 아니라
 * {@link Permissions#STUDENT_READ_SENSITIVE} 를 요구한다 — 이 응답이 사진 · 특이사항 · 연락처
 * 원문(L3)을 담기 때문이다(FEATURE_SPEC §6.2 · API_SPEC §1.12). 기본 권한으로 열면 기사 · 동승자 ·
 * 학부모가 학원 전체 명단의 민감 항목에 닿는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.STUDENT_READ_SENSITIVE + "')")
public @interface CanReadStudentRecord {
}
