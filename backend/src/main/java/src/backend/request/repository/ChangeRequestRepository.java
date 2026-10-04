package src.backend.request.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.entity.ChangeRequestType;
import src.backend.global.persistence.AcademyCount;

/**
 * {@link ChangeRequest} 영속성 접근 — {@code change_request} 는 {@code academy_id} 컬럼을 직접
 * 가진 학원 범위 자원이다(ERD §6.1).
 */
public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long> {

    /**
     * 승인 목록(§5.5 — 30분 안쪽 변경 승인) — DB 에서 페이지 단위로 잘라 가져온다({@code Ruling 358}).
     * {@code windowSegment} 로 ②구간 건만 고른다 — ①구간 신청은 승인 없이 {@code approved} 로 저장되고
     * 마감도 없어 이 목록 대상이 아니다(BR-076). 정렬은 호출부가 {@code pageable} 에 실어 넘긴다 —
     * {@code pending} 은 마감 임박 순, 결정된 상태는 결정 시각 역순으로 서로 다르기 때문이다
     * ({@code ApprovalQueryService#sortOf}).
     */
    Page<ChangeRequest> findAllByAcademyIdAndStatusAndWindowSegment(Long academyId, ChangeRequestStatus status,
            Short windowSegment, Pageable pageable);

    /**
     * 한 회차에 승인된 경유지 이동 요청들(P-06, Phase 8) — 확정 배치가 그날의 승하차지를 조립할 때
     * "일일 변경이 요일별 주소를 이긴다" 우선순위를 적용하는 원천이다({@code RunConfirmationService}).
     * 접수 순으로 정렬해, 같은 학생이 같은 회차에 두 번 신청했을 때(② 승인이 겹치는 드문 경우) 호출부가
     * <b>나중 신청이 이긴다</b> 규칙을 "마지막 값으로 덮어쓰기"로 그대로 구현할 수 있게 한다.
     */
    List<ChangeRequest> findAllByAcademyIdAndRunIdAndTypeAndStatusOrderByRequestedAtAsc(Long academyId, Long runId,
            ChangeRequestType type, ChangeRequestStatus status);

    /**
     * 한 학생의 변경 요청 이력(§3.9 상태 조회)을 상한({@code limit})만큼만 — 최근 신청이 먼저 보이도록 접수 역순으로 정렬하고, §3.9 가 전 기간을
     * 무한정 싣지 않게 한다(BR-251).
     */
    List<ChangeRequest> findByAcademyIdAndStudentIdOrderByRequestedAtDesc(Long academyId, Long studentId,
            org.springframework.data.domain.Pageable limit);

    /** 학생의 {@code status} 별 건수 — 이력이 잘려도 홈 배지({@code pending_count})는 전체를 세야 한다. */
    long countByAcademyIdAndStudentIdAndStatus(Long academyId, Long studentId, ChangeRequestStatus status);

    /** 관계자 화면의 승인 건 단건 조회(§5.5 상세) — 다른 학원 건은 존재를 숨겨 비어 있다(§1.5, BR-133). */
    Optional<ChangeRequest> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 승인·거절 결정(§5.6)을 위해 행을 잠그고 읽는다 — 자동 거절(운행 시작·출발 시각 폴링)과 겹치면 먼저 잡은
     * 쪽이 끝날 때까지 기다린 뒤 커밋된 상태를 보므로, 메모리 상태로 판정하고 덮어써 {@code auto_rejected} 를
     * 뒤집던 경합이 사라진다(BR-028). 두 탭의 동시 승인도 두 번째가 결정된 상태를 보고 409 가 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ChangeRequest c WHERE c.id = :id AND c.academyId = :academyId")
    Optional<ChangeRequest> findForDecision(@Param("id") Long id, @Param("academyId") Long academyId);

    /**
     * 승인 대기 배지 수(§5.5 목록의 {@code pending_count}) — {@code status} 조회 파라미터가
     * {@code pending} 이 아닌 값으로 필터링된 화면에서도, 관리자가 지금 처리해야 할 건수는 그대로
     * 알아야 한다. 그래서 목록에 실린 {@code items} 개수({@code status} 필터를 그대로 따름)와
     * 별개로 <b>항상 대기중 건수</b>를 센다 — 배지 용도가 아니라면 {@code items.size()} 로 충분해
     * 별도 필드일 이유가 없다.
     */
    long countByAcademyIdAndStatus(Long academyId, ChangeRequestStatus status);

    /**
     * 자동 거절 폴링 대상(API_SPEC §1.6) — 마감({@code deadline_at})이 이미 지난 대기 건.
     *
     * <p>시각이 촉발하는 <b>전 학원 대상</b> 조회라 좁힐 학원이 없다 — 학원 하나로 좁히면 나머지
     * 학원의 도래분이 거절되지 않는다({@code RunRepository.findDueForConfirmation}
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

    /**
     * 학생들의 변경 요청에 남은 주소·좌표·사유를 지운다(개인정보 파기, Ruling 480 ②·520) — 행은 남긴다(승하차 이력이
     * 이 행을 참조하고 {@code ON DELETE RESTRICT}). relocate 의 {@code new_address} 는 {@code ck_change_request_new_address}
     * 가 NOT NULL 을 요구해 {@code placeholder} 로 덮고, cancel 처럼 원래 비어 있던 주소는 그대로 비운다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "보존 정리 배치(Ruling 480 ②) — 학생 id 는 전 학원의 파기 대상 조회가 골라낸 값이고, "
            + "부르는 주체가 스케줄러라 요청 주체의 소속이 부재")
    @Query("""
            UPDATE ChangeRequest c
            SET c.newAddress = CASE WHEN c.newAddress IS NULL THEN NULL ELSE :placeholder END,
                c.newLat = NULL, c.newLng = NULL, c.reason = NULL
            WHERE c.studentId IN :studentIds
            """)
    int anonymizeAddressesOfStudents(@Param("studentIds") Collection<Long> studentIds,
            @Param("placeholder") String placeholder);

    /**
     * 기간에 <b>결정된</b> 변경 요청의 결정별 건수(§6.18 {@code change_requests}) — 승인 · 거절 · 자동 거절만 센다. 시각은 {@code decided_at}, 범위는
     * 반열림 구간 {@code [from, to)} 이다.
     */
    @Query("SELECT c.status AS status, COUNT(c) AS total FROM ChangeRequest c WHERE c.academyId IN :academyIds "
            + "AND c.status <> src.backend.request.entity.ChangeRequestStatus.PENDING "
            + "AND c.decidedAt >= :from AND c.decidedAt < :to GROUP BY c.status")
    List<ChangeRequestStatusCount> countDecidedByStatus(@Param("academyIds") Collection<Long> academyIds,
            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 기간에 <b>접수된</b> 변경 요청의 학원별 건수(§6.18 {@code academies[].change_request_count}) — 시각은 {@code requested_at}, 반열림 구간이다. */
    @Query("SELECT c.academyId AS academyId, COUNT(c) AS total FROM ChangeRequest c WHERE c.academyId IN :academyIds "
            + "AND c.requestedAt >= :from AND c.requestedAt < :to GROUP BY c.academyId")
    List<AcademyCount> countRequestedByAcademy(@Param("academyIds") Collection<Long> academyIds,
            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /**
     * 마감이 {@code (now, until]} 안인 대기 중 변경 요청을 회차별로 묶는다(§6.18 {@code attention.expiring_change_requests[]}) — 놓치면 자동 거절된다.
     * 이미 마감이 지난 요청은 자동 거절 배치가 곧 거두므로 싣지 않는다. 마감이 가장 빠른 회차부터.
     */
    @Query("SELECT c.runId AS runId, MIN(c.deadlineAt) AS deadlineAt, COUNT(c) AS total FROM ChangeRequest c "
            + "WHERE c.academyId IN :academyIds AND c.status = src.backend.request.entity.ChangeRequestStatus.PENDING "
            + "AND c.deadlineAt > :now AND c.deadlineAt <= :until GROUP BY c.runId ORDER BY MIN(c.deadlineAt), c.runId")
    List<ExpiringChangeRequestRow> findExpiringByRun(@Param("academyIds") Collection<Long> academyIds,
            @Param("now") OffsetDateTime now, @Param("until") OffsetDateTime until);

    /** 회차별 대기 중 변경 요청 수(§6.18 {@code pending_change_count}). */
    @Query("SELECT c.runId AS runId, COUNT(c) AS total FROM ChangeRequest c WHERE c.academyId IN :academyIds "
            + "AND c.runId IN :runIds AND c.status = src.backend.request.entity.ChangeRequestStatus.PENDING "
            + "GROUP BY c.runId")
    List<RunPendingCount> countPendingByRun(@Param("academyIds") Collection<Long> academyIds,
            @Param("runIds") Collection<Long> runIds);
}
