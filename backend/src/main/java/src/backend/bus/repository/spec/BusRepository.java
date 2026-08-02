package src.backend.bus.repository.spec;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.bus.entity.Bus;

/**
 * 버스 저장소. 학원(테넌트) 단위로 조회해 데이터 격리를 지킨다.
 */
public interface BusRepository extends JpaRepository<Bus, Long> {

    List<Bus> findByTenantId(Long tenantId);

    /**
     * 담당 기사 기준 조회 — 기사 앱이 자기 busId 를 알아내는 진입점(GET /api/buses/me).
     * 도메인상 기사 1명 = 버스 1대지만, 스키마에 유니크 제약이 없어 List 로 받고 호출부에서 첫 건을 쓴다
     * (Optional 로 받으면 데이터가 꼬였을 때 500 이 난다).
     */
    List<Bus> findByDriverIdOrderByIdAsc(Long driverId);

    /**
     * 담당 선탑자 기준 조회 — 선탑자 앱이 자기 busId 를 알아내는 진입점(GET /api/buses/me).
     * driver 와 같은 이유로 유니크 제약이 없어 List 로 받고 호출부에서 첫 건을 쓴다.
     */
    List<Bus> findByAttendantIdOrderByIdAsc(Long attendantId);

    /**
     * 학원 안에서 이 사람이 기사로 배정된 버스들 — 멤버십 해제를 막는 참조(I-7) 확인용.
     * findByDriverIdOrderByIdAsc 를 재사용하지 않는다: 테넌트 필터가 없어 다른 학원의 배정까지 걸리면
     * 지울 수 있는 구성원도 못 지우게 된다.
     */
    List<Bus> findByDriverIdAndTenantId(Long driverId, Long tenantId);

    /** 학원 안에서 이 사람이 선탑자로 배정된 버스들 — driver 와 같은 이유로 테넌트를 함께 건다. */
    List<Bus> findByAttendantIdAndTenantId(Long attendantId, Long tenantId);
}
