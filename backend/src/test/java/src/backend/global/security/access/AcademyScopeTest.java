package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 학원 범위 판정 자체(API_SPEC §1.5)를 스프링 없이 고정한다 — 여기서 정한 값이 저장소 조건과
 * 단건 대조 양쪽에 그대로 쓰이므로, HTTP 왕복 없이도 판정 규칙을 단독으로 관측할 수 있어야 한다.
 */
class AcademyScopeTest {

    private static final Long ACADEMY_A = 1L;
    private static final Long ACADEMY_B = 2L;

    private static final AuthUser STAFF_A =
            new AuthUser(100L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    private static final AuthUser SYSTEM_ADMIN =
            new AuthUser(999L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);

    @Test
    void 타_학원_자원을_단건_대조하면_ACADEMY_SCOPE_VIOLATION_이다() {
        assertThatThrownBy(() -> AcademyScope.assertAccessible(STAFF_A, ACADEMY_B))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.ACADEMY_SCOPE_VIOLATION);
    }

    @Test
    void 자기_학원_자원은_단건_대조를_통과한다() {
        AcademyScope.assertAccessible(STAFF_A, ACADEMY_A);
    }

    /** 소속을 알 수 없는 자원을 통과시키면, 학원 컬럼을 못 읽은 조회 경로가 그대로 우회로가 된다. */
    @Test
    void 소속_학원이_없는_자원은_단건_대조에서_거부된다() {
        assertThatThrownBy(() -> AcademyScope.assertAccessible(STAFF_A, null))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.ACADEMY_SCOPE_VIOLATION);
    }

    @Test
    void 메인_관리자는_타_학원_자원에도_접근한다() {
        AcademyScope.assertAccessible(SYSTEM_ADMIN, ACADEMY_B);
    }

    /** §1.5 "요청 본문·쿼리의 academy 식별자는 신뢰 대상 밖" 을 판정 지점에 고정한다. */
    @Test
    void 요청이_지정한_학원이_토큰과_다르면_ACADEMY_SCOPE_VIOLATION_이다() {
        assertThatThrownBy(() -> AcademyScope.resolveListScope(STAFF_A, ACADEMY_B))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.ACADEMY_SCOPE_VIOLATION);
    }

    @Test
    void 요청이_학원을_지정하지_않으면_토큰의_학원으로_좁힌다() {
        assertThat(AcademyScope.resolveListScope(STAFF_A, null)).contains(ACADEMY_A);
    }

    @Test
    void 요청이_토큰과_같은_학원을_지정하면_그대로_좁힌다() {
        assertThat(AcademyScope.resolveListScope(STAFF_A, ACADEMY_A)).contains(ACADEMY_A);
    }

    /** 빈 결과는 메인 관리자가 학원을 지정하지 않은 경우 하나뿐이다 — 다른 역할에서 비면 격리 구멍이다. */
    @Test
    void 메인_관리자가_학원을_지정하지_않으면_범위가_비어_전_학원이_된다() {
        assertThat(AcademyScope.resolveListScope(SYSTEM_ADMIN, null)).isEmpty();
    }

    @Test
    void 메인_관리자가_학원을_지정하면_그_학원으로_좁힌다() {
        assertThat(AcademyScope.resolveListScope(SYSTEM_ADMIN, ACADEMY_B)).contains(ACADEMY_B);
    }
}
