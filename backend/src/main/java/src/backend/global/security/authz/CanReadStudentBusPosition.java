package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학부모 앱의 실시간 버스 위치 조회(LOC-02, API_SPEC §3.11)에 붙는 메타 애너테이션.
 *
 * <p>{@code hasAuthority(...)} 가 아니라 {@code isAuthenticated()} 인 이유는 {@link CanManageWeeklyAddress}
 * 와 같다 — §3.11 은 권한 표 자체가 없고(카탈로그 31종 어디에도 대응 항목 부재), 좁히는 것은
 * {@code student.access.LinkedChildLookup} 의 자원 소속 검사다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface CanReadStudentBusPosition {
}
