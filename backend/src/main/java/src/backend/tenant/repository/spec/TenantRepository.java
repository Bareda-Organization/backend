package src.backend.tenant.repository.spec;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.tenant.entity.Tenant;

/**
 * 학원(테넌트) 저장소.
 */
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    boolean existsByName(String name);
}
