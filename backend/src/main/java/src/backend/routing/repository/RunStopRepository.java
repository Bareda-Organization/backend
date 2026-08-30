package src.backend.routing.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.routing.entity.RunStop;

/**
 * {@link RunStop} 영속성 접근 — {@code run_stop} 은 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1).
 */
public interface RunStopRepository extends JpaRepository<RunStop, Long> {

    /**
     * ③구간 미등원 토글이 잔여 0명을 확인한 뒤 건너뛸 정차 항목 1건을 찾는다(API_SPEC §3.6 ③) —
     * {@code run_stop} 은 {@code route_version_id} 로 배포 버전을 가리키고 {@code confirmed_route.
     * current_version_id} 가 그 버전을 가리키므로, 호출부가 그 체인을 먼저 따라 {@code routeVersionId}
     * 를 구한 뒤 이 조회로 그 승하차지의 정차 항목을 특정한다.
     *
     * <p>{@code routeVersionId} 근거는 {@code boarding.repository.RunRiderRepository#findByRunIdAndStudentId}
     * 와 같다 — 호출부가 이미 학원 소속을 확인한 회차의 확정 노선 버전이라는 전제다.
     */
    @AcademyScopeExempt(reason = "routeVersionId 는 호출부가 이미 학원 소속을 확인한 회차의 확정 노선 버전이라는 전제다 — "
            + "RunRepository.findByIdAndAcademyId 로 회차를 먼저 학원 범위에 좁힌 뒤 confirmed_route.current_version_id 로 "
            + "얻은 값만 넘긴다는 전제(RunRiderRepository.findByRunIdAndStudentId 와 같은 근거)")
    Optional<RunStop> findByRouteVersionIdAndStopId(Long routeVersionId, Long stopId);
}
