package src.backend.request.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.entity.ChangeRequestType;

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
     * 한 회차에 승인된 경유지 이동 요청들(P-06, Phase 8) — 확정 배치가 그날의 승하차지를 조립할 때
     * "일일 변경이 요일별 주소를 이긴다" 우선순위를 적용하는 원천이다({@code RunConfirmationService}).
     * 접수 순으로 정렬해, 같은 학생이 같은 회차에 두 번 신청했을 때(② 승인이 겹치는 드문 경우) 호출부가
     * <b>나중 신청이 이긴다</b> 규칙을 "마지막 값으로 덮어쓰기"로 그대로 구현할 수 있게 한다.
     */
    List<ChangeRequest> findAllByAcademyIdAndRunIdAndTypeAndStatusOrderByRequestedAtAsc(Long academyId, Long runId,
            ChangeRequestType type, ChangeRequestStatus status);

    /**
     * 한 학생의 변경 요청 이력(§3.9 상태 조회) — 최근 신청이 먼저 보이도록 접수 역순으로 정렬한다.
     */
    List<ChangeRequest> findAllByAcademyIdAndStudentIdOrderByRequestedAtDesc(Long academyId, Long studentId);

    /**
     * 자동 거절 폴링 대상(API_SPEC §1.6) — 마감({@code deadline_at})이 이미 지난 대기 건. T6 의
     * 자동 거절 스케줄러가 소비한다(이 저장소가 만드는 것은 조회까지이고, 스케줄러 자체는 이 태스크
     * 범위 밖이다).
     *
     * <p>시각이 촉발하는 <b>전 학원 대상</b> 조회라 좁힐 학원이 없다 — 학원 하나로 좁히면 나머지
     * 학원의 도래분이 거절되지 않는다({@code RunRepository.findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc}
     * 와 같은 근거). 호출부가 자동 거절 스케줄러뿐이라는 전제 — 요청 경로에서 부르면 이 예외가
     * 우회로가 된다.
     */
    @AcademyScopeExempt(reason = "자동 거절 폴링은 시각이 촉발하는 전 학원 대상 조회라 좁힐 학원이 부재하다 — "
            + "학원 하나로 좁히면 나머지 학원의 도래분이 거절되지 않는다. 호출부는 자동 거절 스케줄러(T6)뿐이라는 "
            + "전제(RunRepository 의 확정 배치 조회와 같은 근거) — 요청 경로에서 부르면 이 예외가 우회로가 된다")
    @Query("SELECT c FROM ChangeRequest c WHERE c.status = :status AND c.deadlineAt <= :now ORDER BY c.deadlineAt ASC")
    List<ChangeRequest> findByStatusAndDeadlineAtLessThanEqual(@Param("status") ChangeRequestStatus status,
            @Param("now") OffsetDateTime now);
}
