package src.backend.global.tenant;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 멀티 테넌시 접근 검증 헬퍼.
 * "어느 학원 데이터를 볼 수 있나"는 역할(@PreAuthorize)만으로는 부족해 서비스 계층에서 검사한다.
 *
 * <p>옛 {@code User}↔{@code Tenant} N:M 멤버십 전제(플랫폼 관리자의 임의 학원 접근 등)는
 * {@code AuthUser} 가 단일 소속으로 축소되며 성립하지 않게 됐다 — 지금은 본인 소속 학원 하나로만
 * 판단하고, 역할별 접근 범위 재설계는 Phase 2 로 미룬다.
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    /** 관리자가 조회 대상으로 삼을 학원 id 를 결정·검증한다 — 지정 시 본인 소속과 일치해야 한다. */
    public static Long resolveTenantId(AuthUser admin, Long requested) {
        if (admin.academyId() == null || (requested != null && !requested.equals(admin.academyId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return admin.academyId();
    }
}
