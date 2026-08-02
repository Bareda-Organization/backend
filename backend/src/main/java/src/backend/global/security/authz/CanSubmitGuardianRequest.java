package src.backend.global.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 결석 신고·하차 위치 변경·시간 변경 요청 제출 권한.
 *
 * <p>필요 권한 {@code guardian:request:submit}. 현재 부여 역할은 학부모이지만,
 * <b>어느 역할이 이 권한을 갖는지는 {@link RolePermissions} 한 곳에서만 정한다</b> —
 * 그래서 이 애너테이션에는 역할 이름이 없고, 새 역할이 생겨도 이 파일과 컨트롤러는 바뀌지 않는다.
 *
 * <p>조회({@link CanReadOwnChildren})와 나눠 둔 이유는 요청 제출이 승인 워크플로를 발생시키는 쓰기이기 때문이다 — 조회 전용 보호자 계정이 생기면 여기서 갈라진다.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.GUARDIAN_REQUEST_SUBMIT + "')")
public @interface CanSubmitGuardianRequest {
}
