package src.backend.routing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.routing.entity.ConfirmedRoute;

/**
 * {@link ConfirmedRoute} 영속성 접근 — {@code confirmed_route} 는 {@code academy_id} 컬럼이 부재한
 * <b>부모 경유</b> 자원이다(ERD §6.1). PK 가 {@code run.id} 를 그대로 쓰므로 저장은 {@code save()}(최초
 * 확정 시 merge 경로)로 충분하고, 이 인터페이스는 "현재 버전 포인터 전진" 전용 갱신만 선언한다.
 */
public interface ConfirmedRouteRepository extends JpaRepository<ConfirmedRoute, Long> {

    /**
     * 노선 계산 산출물로 만든 {@code route_version} 을 저장한 뒤 "현재 버전" 포인터를 그 행으로
     * 옮긴다(확정 배치 RTE-08, Phase 7) — 엔티티 세터 대신 저장소 갱신을 쓰는 이유는
     * {@link ConfirmedRoute} 의 PK 가 저장 시점에 이미 채워져 있어 {@code save()} 가 {@code merge}
     * 경로를 타기 때문이다. {@code merge} 는 <b>새 관리 인스턴스</b>를 돌려주므로, 호출부가 들고 있던
     * 원본 객체를 이어서 고쳐도 영속 컨텍스트에 반영되지 않는다 — 그 함정을 피해 조건부 UPDATE 로
     * 직접 옮긴다.
     *
     * @return 영향받은 행 수. {@code confirmed_route.run_id} 가 이미 존재해야 1이 나온다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "확정 배치가 이미 학원과 무관하게 골라낸 run.id 하나의 confirmed_route 행에 대한 "
            + "단건 갱신이다 — 그 run 은 RunConfirmationService.confirmOne 진입 시점에 이미 존재가 확인된 대상이라 "
            + "여기서 학원을 다시 물을 근거가 없다(RunRepository.confirmIfIdle 과 같은 근거)")
    @Query("UPDATE ConfirmedRoute c SET c.currentVersionId = :versionId WHERE c.runId = :runId")
    int assignCurrentVersion(@Param("runId") Long runId, @Param("versionId") Long versionId);
}
