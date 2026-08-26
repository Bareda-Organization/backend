package src.backend.manager.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.manager.entity.Assignment;

/**
 * {@link Assignment} 영속성 접근 — 지금은 <b>배치 여부 판정</b>(MGR-04) 하나만 쓰고, 배치 자체를
 * 만드는 경로(§5.14 {@code PATCH /staff/runs/{runId}/assignment})는 아직 부재하다.
 *
 * <p>배치 API 보다 이 저장소가 먼저 있는 이유는 <b>삭제 차단이 배치의 존재에 기대기</b> 때문이다 —
 * MGR-04 를 만들려면 "배치돼 있다" 를 물을 자리가 먼저 있어야 한다.
 */
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /**
     * 이 매니저가 어느 회차에든 배치돼 있는지 본다 — 있으면 삭제가 {@code 409 MANAGER_ASSIGNED} 다
     * (MGR-04 · API_SPEC §5.13).
     *
     * <p><b>이 선검사가 유일한 방어다.</b> ERD 는 {@code manager → assignment} 를 FK RESTRICT 로 두어
     * "DB 도 방어" 라 적지만, §5.13 의 삭제는 행을 지우지 않는 soft delete({@code deleted_at} UPDATE)라
     * FK 가 발동할 자리가 부재하다. 이 조회를 건너뛰면 배치된 매니저가 조용히 삭제되고, 그 회차의
     * 담당자는 목록에서 사라진 채 남는다.
     *
     * <p>회차의 취소·종료 여부를 조건에 넣지 않는다 — 지난 회차의 배치도 그 매니저가 실제로 운행한
     * 기록이라, 지우면 과거 운행의 담당자를 답할 수단이 사라진다.
     */
    @AcademyScopeExempt(reason = "assignment 는 run 부모 경유라 학원 조건을 걸 자리가 조인뿐인데(ERD §6.1), "
            + "여기서 학원으로 좁히면 삭제 차단이 오히려 약해진다 — 어떤 이유로든 타 학원 회차에 붙은 배치가 "
            + "있으면 그것도 막아야 삭제 뒤에 담당자가 사라지는 회차가 생기지 않는다. "
            + "호출부가 학원 조건으로 좁혀 조회한 Manager 의 id 만 넘긴다는 전제 — 요청 파라미터의 "
            + "managerId 를 넘기면 타 학원 매니저의 배치 여부가 새어 이 예외가 우회로가 된다")
    boolean existsByManagerId(Long managerId);
}
