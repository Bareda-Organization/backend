package src.backend.request.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;

/**
 * {@link ChangeRequest} 영속성 접근 — {@code change_request} 는 {@code academy_id} 컬럼을 직접
 * 가진 학원 범위 자원이다(ERD §6.1).
 */
public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long> {

    /**
     * 승인 대기 목록(§5.6 관리자 승인 화면) — 접수 순으로 정렬해 먼저 온 요청이 먼저 보이게 한다.
     */
    List<ChangeRequest> findAllByAcademyIdAndStatusOrderByRequestedAtAsc(Long academyId, ChangeRequestStatus status);

    /**
     * 자동 거절 폴링 대상(API_SPEC §1.6) — 마감({@code deadline_at})이 이미 지난 대기 건.
     *
     * <p>시각이 촉발하는 <b>전 학원 대상</b> 조회라 좁힐 학원이 없다 — 학원 하나로 좁히면 나머지
     * 학원의 도래분이 거절되지 않는다({@code RunRepository.findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc}
     * 와 같은 근거). 호출부가 자동 거절 스케줄러({@code ChangeRequestAutoRejectionScheduler})뿐이라는
     * 전제 — 요청 경로에서 부르면 이 예외가 우회로가 된다.
     *
     * <p>{@code pageable} 은 한 틱이 한 번에 집는 상한이다 — 상한 없이 전건을 집으면 마감이 몰린
     * 틱 하나가 커넥션을 오래 붙든다({@code RunRepository} 의 확정 배치 조회와 같은 근거). T1 이 만든
     * 시그니처에 이 파라미터를 더한 것(T6) — 기존 조건·정렬은 그대로 두고 상한만 얹었다.
     */
    @AcademyScopeExempt(reason = "자동 거절 폴링은 시각이 촉발하는 전 학원 대상 조회라 좁힐 학원이 부재하다 — "
            + "학원 하나로 좁히면 나머지 학원의 도래분이 거절되지 않는다. 호출부는 자동 거절 스케줄러(T6)뿐이라는 "
            + "전제(RunRepository 의 확정 배치 조회와 같은 근거) — 요청 경로에서 부르면 이 예외가 우회로가 된다")
    @Query("SELECT c FROM ChangeRequest c WHERE c.status = :status AND c.deadlineAt <= :now ORDER BY c.deadlineAt ASC")
    List<ChangeRequest> findByStatusAndDeadlineAtLessThanEqual(@Param("status") ChangeRequestStatus status,
            @Param("now") OffsetDateTime now, Pageable pageable);

    /**
     * 한 회차의 미처리 요청 전부(API_SPEC §1.6) — {@code moving} 종결 서비스가 그 회차를 훑을 때
     * 쓴다. {@code academyId} 를 함께 받는 것은 그 회차가 실제로 그 학원 소속인지 여기서 한 번 더
     * 좁히기 위함이다 — 호출부가 이미 확인한 값을 다시 물어도 비용이 크지 않고, 이름에
     * {@code AcademyId} 가 있어야 파생 조회 규약(격리 검사)을 별도 예외 없이 통과한다.
     */
    List<ChangeRequest> findAllByAcademyIdAndRunIdAndStatus(Long academyId, Long runId, ChangeRequestStatus status);

    /**
     * 도래분 자동 거절을 조건부 UPDATE 로 반영한다(목표 6·목표 4 동시성) — 영향받은 행 수로 성공
     * 여부를 판정한다. {@code WHERE status = 'pending'} 조건 하나가 멱등성의 전부다 — 폴링과
     * {@code moving} 종결 서비스가 같은 건을 동시에 집어도, 먼저 행 잠금을 얻은 쪽만 갱신하고
     * 나중 쪽은 0행을 받는다({@code RunRepository.confirmIfIdle} 과 같은 근거). {@code SELECT} 로
     * 먼저 상태를 본 뒤 갱신하면 그 사이에 경쟁자가 끼어들 수 있어 이 보장이 성립하지 않는다.
     *
     * <p>{@code decided_by} 를 건드리지 않는다 — 자동 거절은 서버가 한 일이라 처리자가 없다
     * ({@link ChangeRequest#autoReject} 와 같은 규칙).
     *
     * @return 영향받은 행 수. 0이면 이미 처리됐거나(경쟁 패배 포함) 대상이 없는 것이다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "위 두 조회 중 하나가 이미 학원 범위(또는 전 학원 시각 촉발)로 골라낸 "
            + "change_request.id 하나에 대한 단건 조건부 갱신이다 — 그 조회가 이미 좁힌 대상이라 이 시점에 "
            + "학원을 다시 물을 근거가 없다(RunRepository.confirmIfIdle 과 같은 근거)")
    @Query("UPDATE ChangeRequest c SET c.status = src.backend.request.entity.ChangeRequestStatus.AUTO_REJECTED, "
            + "c.decidedAt = :decidedAt WHERE c.id = :id "
            + "AND c.status = src.backend.request.entity.ChangeRequestStatus.PENDING")
    int autoRejectIfPending(@Param("id") Long id, @Param("decidedAt") OffsetDateTime decidedAt);
}
