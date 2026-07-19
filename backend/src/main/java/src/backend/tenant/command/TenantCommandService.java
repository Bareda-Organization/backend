package src.backend.tenant.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.tenant.dto.CreateTenantRequest;
import src.backend.tenant.dto.TenantResponse;
import src.backend.tenant.dto.UpdateTenantLocationRequest;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;

/** 학원(테넌트) 생성 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다. 이름 중복은 거부한다. */
@Service
public class TenantCommandService {

    private final TenantRepository tenantRepository;

    public TenantCommandService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public TenantResponse create(CreateTenantRequest req) {
        if (tenantRepository.existsByName(req.name())) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 존재하는 학원 이름입니다");
        }
        Tenant saved = tenantRepository.save(Tenant.builder()
                .name(req.name()).lat(req.lat()).lng(req.lng()).build());
        return TenantResponse.of(saved);
    }

    @Transactional
    public TenantResponse updateLocation(Long tenantId, UpdateTenantLocationRequest req) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학원을 찾을 수 없습니다"));
        tenant.updateLocation(req.lat(), req.lng());
        return TenantResponse.of(tenant);
    }
}
