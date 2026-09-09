package src.backend.routing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.routing.entity.RouteVersion;

/**
 * {@link RouteVersion} 영속성 접근 — {@code route_version} 은 {@code academy_id} 컬럼이 부재한
 * <b>부모 경유</b> 자원이다(ERD §6.1). 확정 배치(Phase 7)는 {@code save()} 로 v1 을 새로 쌓기만 하고
 * 조회 메서드를 아직 필요로 하지 않아 상속 메서드 외에 선언하지 않는다 — 선언 메서드가 없으므로
 * {@code AcademyScopeRepositoryConventionTest} 의 조회별 표시 대상에도 걸리지 않는다.
 */
public interface RouteVersionRepository extends JpaRepository<RouteVersion, Long> {
}
