package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 자녀·본인 당일 회차 목록 조회(API_SPEC §3.5, P-04 · S-01, "권한 학부모(연결 자녀) · 학생(본인)")에
 * 붙는 메타 애너테이션.
 *
 * <p>{@link CanReadStudentRoute} 와 같은 판단으로 {@link Permissions#STUDENT_READ_BASIC} 을 쓴다 —
 * 그 권한은 관계자·기사·동승자·메인관리자까지 보유해 이 애너테이션만으로는 "학부모·학생" 으로 좁힐 수
 * 없다. "어느 학생인가·그 외 역할을 막는가" 는 이 애너테이션이 아니라
 * {@code src.backend.student.access.StudentRunsAccess} 가 맡는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.STUDENT_READ_BASIC + "')")
public @interface CanReadStudentRuns {
}
