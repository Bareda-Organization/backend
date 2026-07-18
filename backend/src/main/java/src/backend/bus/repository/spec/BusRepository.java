package src.backend.bus.repository.spec;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.bus.entity.Bus;

/**
 * 버스 저장소. 학원(테넌트) 단위로 조회해 데이터 격리를 지킨다.
 */
public interface BusRepository extends JpaRepository<Bus, Long> {

    List<Bus> findByTenantId(Long tenantId);
}
