package src.backend.tenant.dto;

import src.backend.tenant.entity.Tenant;

/**
 * 학원(테넌트) 응답.
 */
public record TenantResponse(
        Long id,
        String name,
        Double lat,
        Double lng) {

    public static TenantResponse of(Tenant tenant) {
        return new TenantResponse(tenant.getId(), tenant.getName(), tenant.getLat(), tenant.getLng());
    }
}
