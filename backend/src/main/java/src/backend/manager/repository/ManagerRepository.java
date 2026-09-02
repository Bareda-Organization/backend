package src.backend.manager.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.manager.entity.Manager;

/** {@link Manager} 영속성 접근. */
public interface ManagerRepository extends JpaRepository<Manager, Long> {

    @AcademyScopeExempt(reason = "계정 경유 조회 — 계정 자체가 이미 학원 범위 안이라 매니저 쪽에 조건을 더해도 좁혀지는 것이 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<Manager> findByAccountId(Long accountId);

    /**
     * 가입 승인이 연결할 매니저 1건(AUTH-11 · API_SPEC §5.2) — 학원과 <b>역할</b>로 함께 좁힌다.
     *
     * <p>역할을 조건에 넣는 이유는 {@code manager.role} 이 앱 권한을 결정하기 때문이다(C-06) —
     * 기사 계정을 동승자 레코드에 이으면 계정의 역할과 레코드의 역할이 갈리고, 그 계정은 승하차를
     * 기록할 수 있는지 없는지가 보는 곳마다 달라진다. 어긋난 지정은 {@code 404 MANAGER_NOT_FOUND} 로
     * 학생 쪽과 같은 형태로 답한다(§5.2).
     */
    Optional<Manager> findByIdAndAcademyIdAndRoleAndDeletedAtIsNull(Long id, Long academyId, ManagerRole role);

    /**
     * 한 학원의 매니저 목록·검색(MGR-01, §5.13 {@code ?q=}) — 학원 조건이 <b>쿼리에 고정</b>돼 있다.
     *
     * <p>{@code q} 가 비면 전체, 있으면 이름 부분 일치다. 두 경우를 메서드로 가르지 않은 이유는
     * 갈라 두면 <b>한쪽에만</b> {@code deleted_at IS NULL} 이 붙는 형태가 실제로 생기기 때문이다 —
     * 그러면 검색어를 넣는 순간 삭제된 매니저가 되살아난다.
     *
     * <p>{@code LOWER(...) LIKE ... ESCAPE '\\'} 는 {@link src.backend.global.persistence.LikeEscape}
     * 가 이스케이프한 입력을 해석하기 위한 짝이다 — 없으면 {@code q="%"} 하나가 전체 매칭이 된다.
     */
    @Query("SELECT m FROM Manager m WHERE m.academyId = :academyId AND m.deletedAt IS NULL "
            + "AND (:name IS NULL OR LOWER(m.name) LIKE :name ESCAPE '\\')")
    Page<Manager> searchByAcademyId(@Param("academyId") Long academyId, @Param("name") String namePattern,
            Pageable pageable);

    /**
     * 수정·삭제 대상 매니저 1건(MGR-03·04, §5.13) — 학원이 어긋나거나 이미 삭제됐으면 빈 결과이고
     * 호출부가 그것을 {@code 404 MANAGER_NOT_FOUND} 로 답한다.
     *
     * <p>이미 삭제된 매니저를 대상 밖에 두는 것이 <b>재삭제로 {@code deleted_at} 이 덮어써지는 것</b>을
     * 막는다 — 덮어쓰면 언제 그만뒀는지가 지워진다.
     */
    Optional<Manager> findByIdAndAcademyIdAndDeletedAtIsNull(Long id, Long academyId);

    /**
     * 여러 매니저의 이름·연락처를 한 번에 가져온다(Phase 11 T2, EXC-04) — 비상 알림 목록
     * ({@code EmergencyStaffQueryService})이 신고 행마다 담긴 {@code raised_by}(manager.id)를
     * 이름·전화로 바꿀 때 쓴다.
     */
    @AcademyScopeExempt(reason = "호출부가 emergency_alert.raised_by 만 넘긴다는 전제 — 그 값은 신고 접수 시점에 "
            + "RunAssignmentAccess#assertAssignedDriverOrEscort 가 이미 학원 범위로 확인한 배치의 manager_id 라 "
            + "academy_id 로 다시 좁혀도 결과가 달라지지 않는다. 요청 파라미터의 식별자를 직접 넘기면 이 예외가 우회로가 된다")
    List<Manager> findAllByIdIn(Collection<Long> ids);
}
