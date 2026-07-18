package src.backend.tenant.service.spec;

import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.tenant.dto.CreateTenantRequest;
import src.backend.tenant.dto.TenantResponse;

/**
 * 학원(테넌트) 관리의 계약(인터페이스) — 생성/목록/상세. 실제 구현은 TenantServiceImpl.
 *
 * <p>학원 생성·전체 목록은 플랫폼 관리자 전용이고, 상세는 소속 학원 관리자도 조회할 수 있다.
 */
public interface TenantService {

    /** 학원 생성(플랫폼 관리자). 이름 중복은 거부한다. */
    TenantResponse create(CreateTenantRequest req);

    /** 전체 학원 목록(플랫폼 관리자). */
    List<TenantResponse> list();

    /** 학원 상세 — 플랫폼 관리자는 임의 학원, 학원 관리자는 소속 학원만. */
    TenantResponse get(AuthUser admin, Long tenantId);
}
