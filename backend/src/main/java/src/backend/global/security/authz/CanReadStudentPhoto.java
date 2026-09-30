package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학생 사진 파일 조회(API_SPEC §5.11.1 {@code GET /files/photos/{fileName}})에 붙는 메타 애너테이션.
 *
 * <p>사진은 L3 라 {@link Permissions#STUDENT_READ_PHOTO} 를 요구한다(FEATURE_SPEC §6.3). 요청자 학원과 사진
 * 주인의 학원이 같은지는 권한이 아니라 서비스가 쿼리 조건으로 판정한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.STUDENT_READ_PHOTO + "')")
public @interface CanReadStudentPhoto {
}
