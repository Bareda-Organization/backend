package src.backend.boarding.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.ChangeType;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link RunRider} 영속성 접근 — {@code run_rider} 는 {@code academy_id} 컬럼이 부재한 <b>부모 경유</b>
 * 자원이다(ERD §6.1). 확정 배치는 {@code saveAll()} 로 회차별 명단을 한 번에 쌓는다. 조회 메서드는 ②구간 승인 미리보기가 그 명단을 다시 읽기 위해 둔다.
 * 인원 집계는 {@link RunRiderCountQueries}, 알림·방송 대상 조회는 {@link RunRiderRecipientQueries} 에 선언하고 이 저장소가 상속해 노출한다.
 */
public interface RunRiderRepository
        extends JpaRepository<RunRider, Long>, RunRiderCountQueries, RunRiderRecipientQueries {

    /**
     * 회차 1건의 현재 라이더 명단 전체(②구간 승인 미리보기의 기준선) — 확정 배치가 쌓은
     * 뒤 그동안 승인된 변경까지 반영된 <b>지금</b> 상태를 읽는다({@code weekly_address} 가 아니라
     * 이쪽을 기준선으로 삼는 이유는 승인 미리보기 판단 근거 참조).
     *
     * <p>{@code academy_id} 컬럼이 부재해 학원 조건을 붙일 자리가 <b>부모(Run) 조인뿐</b>이다
     * (ERD §6.1 부모 경유 — {@code WeeklyAddressRepository} 와 같은 형태).
     */
    @Query("""
            SELECT rr FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.runId = :runId
              AND r.academyId = :academyId
            """)
    List<RunRider> findAllByRunIdAndAcademyId(@Param("runId") Long runId, @Param("academyId") Long academyId);

    /**
     * 탑승자 id 목록을 학원으로 좁혀 읽는다 — 목록 조립이 명단 전체 대신 화면에 나온 탑승자만 읽게 한다. 학원 조건은 부모(Run) 조인이다.
     */
    @Query("""
            SELECT rr FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.id IN :ids
              AND r.academyId = :academyId
            """)
    List<RunRider> findAllByIdInAndAcademyId(@Param("ids") Collection<Long> ids,
            @Param("academyId") Long academyId);

    /**
     * 그 회차의 탑승자 1명을 잠금 없이 읽는다(읽기 전용 조회용) — {@code excludedStatus} 인 행은 없는 것으로 본다. 학원 조건은 부모(Run) 조인이다.
     */
    @Query("""
            SELECT rr FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.id = :id
              AND rr.runId = :runId
              AND r.academyId = :academyId
              AND rr.status <> :excludedStatus
            """)
    Optional<RunRider> findByIdAndRunIdAndAcademyIdAndStatusNot(@Param("id") Long id, @Param("runId") Long runId,
            @Param("academyId") Long academyId, @Param("excludedStatus") RiderStatus excludedStatus);

    /**
     * 회차 여러 개의 명단을 한 번에(BR-252) — {@link #findAllByRunIdAndAcademyId} 를 회차마다 부르던 목록 조회가
     * 페이지 크기만큼 질의를 내지 않게 한다. 학원 조건은 같은 부모(Run) 조인이다.
     */
    @Query("""
            SELECT rr FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.runId IN :runIds
              AND r.academyId = :academyId
            """)
    List<RunRider> findAllByRunIdInAndAcademyId(@Param("runIds") Collection<Long> runIds,
            @Param("academyId") Long academyId);

    /**
     * ③구간 미등원 토글(API_SPEC §3.6)이 상태를 옮길 그 학생의 명단 행 1건 —
     * {@code uk_run_rider_run_student} UNIQUE(암시)가 결과를 한 건으로 좁힌다.
     *
     * <p>{@code runId} 는 호출부가 {@code RunRepository.findByIdAndAcademyId} 로 이미 학원 범위에
     * 좁혀 얻은 회차의 식별자라는 전제다 — {@code request.repository.BoardingIntentRepository
     * #findByRunIdAndStudentId} 와 같은 근거(Ruling 153·180 {id} 지목 관례).
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunRepository.findByIdAndAcademyId 로 이미 학원 범위에 좁혀 얻은 "
            + "회차의 식별자라는 전제다(BoardingIntentRepository.findByRunIdAndStudentId 와 같은 근거). run_rider 는 "
            + "academy_id 컬럼이 부재해 부모(run) 조인 없이는 재확인할 수도 없다")
    Optional<RunRider> findByRunIdAndStudentId(Long runId, Long studentId);

    /**
     * {@link #findByRunIdAndStudentId} 와 같은 탑승자 행을 <b>행 잠금</b>으로 읽는다(BR-303) — ③구간 미등원 토글이 이
     * 행을 읽고 {@code absent} 로 바꾸므로, {@link #findByIdAndRunIdAndStatusNot}(승하차·BR-267)와 같은 잠금으로
     * 직렬화한다. 승차가 먼저 커밋됐다면 토글은 그 뒤의 상태({@code boarded})를 읽어 거절된다. 트랜잭션 안에서만 부른다.
     */
    @AcademyScopeExempt(reason = "findByRunIdAndStudentId 와 같은 근거 — 호출부가 학원 범위로 좁힌 회차의 식별자만 넘긴다는 전제")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RunRider> findLockedByRunIdAndStudentId(Long runId, Long studentId);

    /**
     * 승하차 처리(API_SPEC §4.6·§4.7)가 다룰 탑승자 1건 — {@code status} 가 {@link RiderStatus#ABSENT}
     * 인 행은 제외한다. §4.6 이 "{@code absent} 로 명단에서 제외된 탑승자"를 {@code 404 RIDER_NOT_FOUND}
     * 로 명시하기 때문에, 존재하지 않는 {@code riderId} 와 이미 제외된 탑승자를 이 메서드 하나로
     * 같은 404 에 합류시킨다 — 호출부가 두 사유를 따로 가를 필요가 없다.
     *
     * <p>{@code runId} 근거는 {@link #findByRunIdAndStudentId} 와 같다 — 호출부가 이미 학원 범위로
     * 좁혀 얻은 회차의 식별자라는 전제다.
     *
     * <p><b>탑승자 행을 잠근다</b>(BR-267, {@code PESSIMISTIC_WRITE}) — 승하차·되돌리기·연락 기록이 이 행을 읽고
     * 상태를 바꾸므로, 같은 탑승자에 대한 서로 다른 {@code client_key} 요청이 둘 다 옛 상태를 읽고 이력을 두 번
     * 남기지 못하게 한다. 뒤 요청은 앞 요청이 커밋한 뒤의 상태를 읽는다. 트랜잭션 안에서만 부른다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunRepository.findByIdAndAcademyId 로 이미 학원 범위에 좁혀 얻은 "
            + "회차의 식별자라는 전제다(findByRunIdAndStudentId 와 같은 근거)")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RunRider> findByIdAndRunIdAndStatusNot(Long id, Long runId, RiderStatus excludedStatus);

    /**
     * 회차 1건의 명단 전체(하원 시작 시 일괄 승차) — {@link #findAllByRunIdAndAcademyId} 와
     * 달리 학원 조인이 없다. 호출부(회차 시작 커맨드)가 이미 {@code RunRepository
     * .findByIdAndAcademyId} 로 학원 범위를 확인한 뒤 그 {@code runId} 만 넘긴다는 전제다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunRepository.findByIdAndAcademyId 로 이미 학원 범위에 좁혀 얻은 "
            + "회차의 식별자라는 전제다(findByRunIdAndStudentId 와 같은 근거)")
    List<RunRider> findAllByRunId(Long runId);

    /**
     * 그 회차들 중 이 학생이 명단에 있는 회차 — 학생 회차 판정이 회차마다 명단을 따로 묻지 않게 한 번에
     * 모은다(BR-058). 학원 조건은 {@code run} 부모 조인으로 건다({@link #findAllByRunIdAndAcademyId} 와 같은 형태).
     *
     * <p>버스 간 이동으로 빠진 행({@code change = removed})은 세지 않는다(BR-016) — 명단에 남는 것은 관계자·매니저
     * 표시용이고 그 학생은 그 버스에 없다. {@code change} 가 비면 변경 없음이라 포함한다.
     */
    @Query("""
            SELECT rr.runId FROM RunRider rr
            JOIN Run r ON r.id = rr.runId
            WHERE rr.studentId = :studentId
              AND rr.runId IN :runIds
              AND r.academyId = :academyId
              AND (rr.change IS NULL OR rr.change <> :removed)
            """)
    List<Long> findRunIdsByAcademyIdAndStudentIdAndRunIdIn(@Param("academyId") Long academyId,
            @Param("studentId") Long studentId, @Param("runIds") Collection<Long> runIds,
            @Param("removed") ChangeType removed);
}
