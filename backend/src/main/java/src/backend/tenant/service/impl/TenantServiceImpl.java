package src.backend.tenant.service.impl;

import src.backend.tenant.service.spec.TenantService;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.tenant.dto.CreateTenantRequest;
import src.backend.tenant.dto.TenantResponse;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;

/**
 * {@link TenantService} 기본 구현 — 학원 생성/목록/상세.
 * 상세 조회는 플랫폼 관리자(전체)와 소속 학원 관리자(자기 학원)만 허용한다.
 */
@Service
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;

    public TenantServiceImpl(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional
    public TenantResponse create(CreateTenantRequest req) {
        if (tenantRepository.existsByName(req.name())) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 존재하는 학원 이름입니다");
        }
        Tenant saved = tenantRepository.save(Tenant.builder().name(req.name()).build());
        return TenantResponse.of(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantResponse> list() {
        return tenantRepository.findAll().stream().map(TenantResponse::of).toList();
    }

    @Override
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
