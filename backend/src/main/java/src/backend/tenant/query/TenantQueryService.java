package src.backend.tenant.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.tenant.dto.TenantResponse;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;

/**
 * 학원(테넌트) 목록/상세 조회 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다.
 * 상세 조회는 플랫폼 관리자(전체)와 소속 학원 관리자(자기 학원)만 허용한다.
 */
@Service
public class TenantQueryService {

    private final TenantRepository tenantRepository;

    public TenantQueryService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public List<TenantResponse> list() {
        return tenantRepository.findAll().stream().map(TenantResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public TenantResponse get(AuthUser admin, Long tenantId) {
        if (!admin.isPlatformAdmin() && !admin.belongsToTenant(tenantId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));
        return TenantResponse.of(tenant);
    }
}
