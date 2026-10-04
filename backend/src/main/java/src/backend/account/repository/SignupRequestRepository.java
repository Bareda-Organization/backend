package src.backend.account.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequest;
import src.backend.account.entity.SignupRequestStatus;
import src.backend.global.persistence.AcademyCount;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link SignupRequest} 영속성 접근. */
public interface SignupRequestRepository extends JpaRepository<SignupRequest, Long> {

    /**
     * 수락·거절할 요청을 잠그고 읽는다(§5.2·§6.5 · BR-063) — 같은 요청의 동시 처리가 둘 다 {@code pending} 을 보고
     * 진행하지 않게, 두 번째 요청은 첫 처리가 커밋된 뒤의 상태를 읽어 {@code 409 APPROVAL_ALREADY_DECIDED} 가 된다.
     */
    @AcademyScopeExempt(reason = "§5.2·§6.5 승인 — 학원 판정은 호출부가 읽은 행으로 AcademyScope.assertAccessible(§5.2) "
            + "또는 approver_type 필터(§6.5 메인 관리자 전 학원)로 한다. 잠금 조회는 findById 와 같은 범위")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SignupRequest r WHERE r.id = :id")
    Optional<SignupRequest> findByIdForUpdate(@Param("id") Long id);

    /**
     * 계정의 최신 가입 요청(API_SPEC §2.3 승인 대기 화면) — 재신청은 새 행을 쌓으므로(엔티티 javadoc),
     * 화면에 보여줄 "지금" 상태는 항상 가장 최근 요청 1건이다.
     */
    @AcademyScopeExempt(reason = "§2.3 본인 가입 상태 조회 — 계정 경유라 학원이 이미 결정, 학원 조건을 더해도 좁혀지는 것이 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<SignupRequest> findTopByAccountIdOrderByRequestedAtDesc(Long accountId);

    /**
     * 관계자가 보는 가입 요청 목록(API_SPEC §5.1) — 학원과 <b>승인 주체</b>로 함께 좁힌다.
     *
     * <p>{@code approverType} 이 이 축의 대상 역할을 정한다({@code staff} = 학부모·학생·기사·동승자).
     * 역할 목록을 나열하지 않고 승인 주체로 가르는 이유는 "누가 승인하는가" 가 가입 시점에 이미
     * 결정돼 행에 적혀 있기 때문이다 — 역할을 나열하면 역할이 하나 늘 때 이 목록과
     * {@code SignupCommandService} 의 판정이 갈린다.
     */
    Page<SignupRequest> findAllByAcademyIdAndApproverTypeAndStatus(Long academyId, ApproverType approverType,
            SignupRequestStatus status, Pageable pageable);

    /**
     * 미처리 배지(API_SPEC §5.1 {@code pending_count}) — 상태 필터와 <b>무관하게</b> 대기 건수를 센다.
     *
     * <p>목록의 {@code total_count} 로 대신할 수 없다 — 필터를 {@code accepted} 로 걸면 그 값은 처리
     * 완료 건수가 되어 배지가 필터를 따라 흔들린다.
     */
    long countByAcademyIdAndApproverTypeAndStatus(Long academyId, ApproverType approverType,
            SignupRequestStatus status);

    /**
     * 학원별 대기 중 가입 요청 수(API_SPEC §6.1 {@code pending_signup_count} · §6.6 {@code academy_pending_signup_count}) —
     * 한 페이지의 학원 전부를 한 번에 센다. 학원마다 {@link #countByAcademyIdAndApproverTypeAndStatus} 를 부르면 한 페이지(최대
     * 100건)가 질의 100건이 된다. 요청이 없는 학원은 행이 나오지 않으므로 호출부가 0 으로 채운다.
     */
    @Query("SELECT r.academyId AS academyId, COUNT(r) AS total FROM SignupRequest r "
            + "WHERE r.academyId IN :academyIds AND r.approverType = :approverType AND r.status = :status "
            + "GROUP BY r.academyId")
    List<AcademyCount> countByAcademyIdInGroupedByAcademyId(@Param("academyIds") Collection<Long> academyIds,
            @Param("approverType") ApproverType approverType, @Param("status") SignupRequestStatus status);

    /**
     * 메인 관리자가 보는 관계자 가입 요청 목록(API_SPEC §6.4) — 전 학원 범위다.
     */
    @AcademyScopeExempt(reason = "§6.4 메인 관리자 콘솔의 전 학원 조회(ARCHITECTURE §6.2 격리 예외) — 좁힐 학원이 부재. "
            + "호출부가 @CanApproveStaff 로 메인 관리자에게만 열린 경로에서만 부른다는 전제 — 관계자 경로에서 부르면 "
            + "타 학원 지원자의 이름·연락처가 그대로 샌다")
    Page<SignupRequest> findAllByApproverTypeAndStatus(ApproverType approverType, SignupRequestStatus status,
            Pageable pageable);
}
