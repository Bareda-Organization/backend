package src.backend.global.security.access;

import java.util.Optional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 요청 주체가 닿을 수 있는 학원 범위를 판정한다(API_SPEC §1.5).
 *
 * <p>목록 쿼리에 붙일 조건값({@link #resolveListScope})과 자원 1건의 접근 허용 여부
 * ({@link #assertAccessible})가 같은 판정에서 나오도록 한 곳에 뒀다 — 두 곳에 나눠 두면 한쪽만
 * 고쳐져 "단건은 막는데 목록은 새는" 상태가 된다(ARCHITECTURE §6.1 이 지목한 사고 지점).
 *
 * <p>범위는 <b>토큰에서만</b> 온다. 요청 본문·쿼리로 받은 학원 식별자는 범위를 정하는 데 쓰지 않고
 * 토큰과의 대조에만 쓴다 — 그 값을 신뢰하면 격리를 우회하는 가장 쉬운 경로가 열린다(API_SPEC §1.5).
 */
public final class AcademyScope {

    private AcademyScope() {
    }

    /**
     * 목록 조회를 좁힐 학원 id 를 결정한다 — 요청이 다른 학원을 지정하면
     * {@link ErrorCode#ACADEMY_SCOPE_VIOLATION}.
     *
     * <p><b>빈 결과는 메인 관리자가 학원을 지정하지 않은 경우 하나뿐</b>이고, 그때만 전 학원 조회가
     * 허용된다(ARCHITECTURE §6.2). 호출부가 빈 결과를 "조건 없음" 으로 흘려보내면 그 경로가 곧
     * 격리 구멍이므로, {@code Optional} 로 반환해 처리를 강제한다.
     */
    public static Optional<Long> resolveListScope(AuthUser requester, Long requestedAcademyId) {
        if (requester.hasPlatformScope()) {
            return Optional.ofNullable(requestedAcademyId);
        }
        if (requestedAcademyId != null) {
            assertAccessible(requester, requestedAcademyId);
        }
        return Optional.of(requester.academyId());
    }

    /**
     * 자원 1건의 소속 학원이 요청자의 범위 안인지 대조한다 — 밖이면
     * {@link ErrorCode#ACADEMY_SCOPE_VIOLATION}.
     *
     * <p>{@code resourceAcademyId} 가 null 이면 대조 대상이 없는 것이라 거부한다 — 소속 미상 자원을
     * 통과시키면 학원 컬럼을 읽지 못한 조회 경로가 그대로 우회로가 된다.
     */
    public static void assertAccessible(AuthUser requester, Long resourceAcademyId) {
        if (requester.hasPlatformScope()) {
            return;
        }
        if (!requester.academyId().equals(resourceAcademyId)) {
            throw new BusinessException(ErrorCode.ACADEMY_SCOPE_VIOLATION);
        }
    }
}
