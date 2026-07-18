package src.backend.global.tenant;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 멀티 테넌시 접근 검증 헬퍼.
 * "어느 학원 데이터를 볼 수 있나"는 역할(@PreAuthorize)만으로는 부족하다 —
 * 학원 관리자는 자기 학원만, 플랫폼 관리자는 지정한 학원을 볼 수 있게 서비스 계층에서 검사한다.
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    /**
     * 관리자가 조회 대상으로 삼을 학원 id 를 결정·검증한다.
     * - 플랫폼 관리자: 어느 학원이든 가능하지만 tenantId 를 반드시 지정해야 한다.
     * - 학원 관리자: 미지정 시 본인 소속 학원으로, 지정 시 소속 학원인지 검증한다.
     */
    public static Long resolveTenantId(AuthUser admin, Long requested) {
        if (admin.isPlatformAdmin()) {
            if (requested == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "tenantId가 필요합니다");
            }
            return requested;
        }
        if (requested == null) {
            Long primary = admin.primaryTenantId();
            if (primary == null) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            return primary;
        }
        if (!admin.belongsToTenant(requested)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return requested;
    }
}
