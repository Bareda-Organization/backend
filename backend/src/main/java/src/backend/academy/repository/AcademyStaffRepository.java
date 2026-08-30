package src.backend.academy.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.academy.dto.AcademyStaffAccountView;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.global.persistence.AcademyCount;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link AcademyStaff} 영속성 접근. */
public interface AcademyStaffRepository extends JpaRepository<AcademyStaff, Long> {

    /**
     * 학원의 관계자 수를 상태별로 센다 — 정원 판정({@code AcademyStaffQuota})의 선검사가 쓰는 유일한 조회다.
     *
     * <p>상태를 인자로 받는 이유는 정원이 "행이 몇 개인가" 가 아니라 <b>"재직자가 몇 명인가"</b> 이기
     * 때문이다(Ruling 139) — 퇴사 행은 남으므로 상태를 가리지 않고 세면 교체가 영구히 막힌다.
     */
    long countByAcademyIdAndStatus(Long academyId, StaffStatus status);

    /** 학원 상세(API_SPEC §6.3 {@code staff_accounts[]})가 쓰는 관계자 목록 — 퇴사 이력도 함께 보인다. */
    List<AcademyStaff> findAllByAcademyIdOrderByIdAsc(Long academyId);

    /**
     * 학원의 재직 관계자 1건을 찾는다({@code approval_requested} 알림 수신자, §9.7) —
     * {@code uk_academy_staff_academy_active} 가 학원당 재직 1건을 강제하므로 결과는 최대 1건이다.
     */
    Optional<AcademyStaff> findByAcademyIdAndStatus(Long academyId, StaffStatus status);

    /**
     * 목록 조회(API_SPEC §6.1 {@code staff_count})가 쓰는 학원별 재직 관계자 수.
     *
     * <p>한 페이지의 학원 전부를 한 번에 센다 — 학원마다 {@link #countByAcademyIdAndStatus} 를 부르면
     * 한 페이지(최대 100건)가 질의 100건이 된다.
     */
    @Query("SELECT s.academyId AS academyId, COUNT(s) AS total FROM AcademyStaff s "
            + "WHERE s.academyId IN :academyIds AND s.status = :status GROUP BY s.academyId")
    List<AcademyCount> countByAcademyIdInGroupedByAcademyId(@Param("academyIds") Collection<Long> academyIds,
            @Param("status") StaffStatus status);

    /**
     * 계정이 어느 학원의 관계자인지 찾는다(API_SPEC §6.7 {@code PATCH /admin/staff-accounts/{id}}).
     *
     * <p>{@code uk_academy_staff_account} 가 계정당 행 1개를 강제하므로 결과는 최대 1건이다 — 즉
     * 한 계정이 두 학원의 관계자를 겸할 수 없고, 재입사도 새 행이 아니라 기존 행을 되돌린다(Ruling 139).
     */
    @AcademyScopeExempt(reason = "§6.7 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "대상 학원은 이 조회의 결과로 비로소 결정되므로 학원을 조건에 넣으려면 이미 알고 있어야 한다는 "
            + "순환이 된다. account_id UNIQUE 가 행 1건을 특정해 학원 조건을 더해도 좁혀지는 것이 부재")
    Optional<AcademyStaff> findByAccountId(Long accountId);

    /**
     * 계정 여럿의 관계자 행을 한 번에 가져온다(API_SPEC §6.6 목록의 {@code status}·소속 학원).
     *
     * <p>한 페이지의 계정 전부를 한 번에 찾는다 — 계정마다 {@link #findByAccountId} 를 부르면
     * 한 페이지(최대 100건)가 질의 100건이 된다.
     */
    @AcademyScopeExempt(reason = "§6.6 메인 관리자 콘솔 — /admin 은 전 학원 범위이며 학원 격리의 명시적 예외다(§1.5). "
            + "대상 계정 집합은 앞선 조회(AccountRepository#findStaffAccountsForConsole)가 확정한 id 목록이라 "
            + "여기서 학원으로 좁히면 그 목록의 일부가 이유 없이 사라져 행과 계정의 짝이 어긋난다")
    List<AcademyStaff> findAllByAccountIdIn(Collection<Long> accountIds);

    /**
     * 탑승 의사 변경 알림({@code intent_changed}·{@code approval_requested}, API_SPEC §9.7)의 수신자
     * 조회 — 대상이 "관계자"(재직 스태프 전원)라 {@link #findByAccountId} 처럼 계정 하나가 아니라
     * 학원 하나에 딸린 계정 전부를 훑는다({@code AssignmentRepository#findAssignedManagerAccounts} 와
     * 같은 조인 형태). {@code status = ACTIVE} 만 남긴다 — 퇴사자는 알림을 받을 수신자가 아니다.
     *
     * <p>{@code s.academyId = :academyId} 가 WHERE 에 그대로 있어 학원으로 이미 좁혀진 조회이므로
     * {@code AcademyScopeExempt} 가 필요 없다.
     */
    @Query("SELECT new src.backend.academy.dto.AcademyStaffAccountView(a.id, a.name) "
            + "FROM AcademyStaff s, Account a "
            + "WHERE a.id = s.accountId AND s.academyId = :academyId AND s.status = src.backend.academy.entity.StaffStatus.ACTIVE")
    List<AcademyStaffAccountView> findActiveAccountsByAcademyId(@Param("academyId") Long academyId);
}
