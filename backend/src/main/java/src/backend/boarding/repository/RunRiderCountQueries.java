package src.backend.boarding.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import src.backend.boarding.dto.StaffRunRiderAggregateView;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link RunRider} 의 집계 조회 묶음 — 회차·승하차지·상태별 인원 수와 대시보드 집계. 호출부는 이 묶음을 직접 주입하지 않고
 * {@link RunRiderRepository} 가 상속해 노출하는 메서드로 쓴다.
 */
@NoRepositoryBean
public interface RunRiderCountQueries extends Repository<RunRider, Long> {

    /** 회차의 탑승자 수(상태 무관)를 센다 — 호출부가 이미 학원 범위에 좁힌 회차의 식별자를 넘긴다는 전제다({@link RunRiderRepository#findAllByRunId} 와 같은 근거). */
    @AcademyScopeExempt(reason = "runId 는 호출부가 RunRepository.findByIdAndAcademyId 로 이미 학원 범위에 좁혀 얻은 "
            + "회차의 식별자라는 전제다(findAllByRunId 와 같은 근거)")
    long countByRunId(Long runId);

    /**
     * 그 승하차지에 아직 남은(부재 처리되지 않은) 탑승자 수(API_SPEC §3.6 ③ "잔여 0명이면 {@code run_stop}
     * 을 {@code skipped}") — 방금 부재로 바꾼 학생을 포함해 센 뒤 0이면 그 정차지를 건너뛴다.
     *
     * <p>{@code runId} 근거는 {@link RunRiderRepository#findByRunIdAndStudentId} 와 같다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "findByRunIdAndStudentId 와 같은 근거(AcademyScope.assertAccessible 선확인)")
    long countByRunIdAndStopIdAndStatusNot(Long runId, Long stopId, RiderStatus status);

    /**
     * 그 회차에서 아직 그 상태인 라이더 수(RUN-06·C-15) — 하원 마지막 하차·②구간 종결 뒤의
     * 종료 판정({@code RunCompletionService})이 "아직 탑승 중({@code BOARDED})이 0명인가"를 이 메서드로
     * 묻는다.
     *
     * <p>{@code runId} 근거는 {@link RunRiderRepository#findByRunIdAndStudentId} 와 같다 — 호출부가 이미 학원 범위로
     * 좁혀 얻은 회차의 식별자만 넘긴다는 전제다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "findByRunIdAndStudentId 와 같은 근거(AcademyScope.assertAccessible 선확인)")
    long countByRunIdAndStatus(Long runId, RiderStatus status);

    /**
     * 그 회차 라이더를 상태별로 센 값을 질의 1번으로 읽는다. 승하차 방송이 5상태를 {@link #countByRunIdAndStatus} 로 5번 묻지 않도록 하나로 묶는다.
     * 인원이 0인 상태는 결과에 없다. {@code runId} 근거는 {@link #countByRunIdAndStatus} 와 같다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "countByRunIdAndStatus 와 같은 근거(AcademyScope.assertAccessible 선확인)")
    @Query("SELECT r.status AS status, COUNT(r) AS total FROM RunRider r WHERE r.runId = :runId GROUP BY r.status")
    List<RiderStatusCount> countGroupedByStatus(@Param("runId") Long runId);

    /**
     * 그 승하차지에 아직 남은(부재·미승차 둘 다 빠진) 탑승자 수 — {@code no_show} 도 잔여 0명 판정에서
     * 빠져야 한다. 다중값 NOT 제외를 메서드명만으로 표현할 수 없어 {@code @Query} 로 직접 쓴다.
     *
     * <p>{@code runId} 근거는 {@link RunRiderRepository#findByRunIdAndStudentId} 와 같다.
     */
    @AcademyScopeExempt(reason = "runId 는 호출부가 이미 학원 소속을 확인한 회차의 식별자라는 전제다 — "
            + "findByRunIdAndStudentId 와 같은 근거(AcademyScope.assertAccessible 선확인)")
    @Query("""
            SELECT COUNT(rr) FROM RunRider rr
            WHERE rr.runId = :runId
              AND rr.stopId = :stopId
              AND rr.status NOT IN :excludedStatuses
            """)
    long countByRunIdAndStopIdAndStatusNotIn(@Param("runId") Long runId, @Param("stopId") Long stopId,
            @Param("excludedStatuses") Collection<RiderStatus> excludedStatuses);

    /**
     * 회차 목록의 탑승자를 {@code (runId, status, change)} 조합으로 묶어 센다(§5.3 {@code metrics.
     * boarded}·{@code no_show}·{@code absent} 및 {@code runs[].boarded_count}·{@code total_count}·
     * {@code added_count}·{@code removed_count}) — 관계자 웹 대시보드 전용이라는 뜻으로
     * 메서드 이름에 용도를 남긴다(Ruling 238).
     *
     * <p>한 쿼리로 두 축(상태·변경 구분)을 함께 묶는 이유는 {@link StaffRunRiderAggregateView}
     * 자바독을 본다 — 소비 측(서비스 계층)이 필요한 축으로 접어 합산한다. {@code run_rider} 는
     * {@code academy_id} 컬럼이 부재한 부모 경유 자원이라(ERD §6.1, {@code Run} 경유) 학원 조건을
     * {@code run} 쪽 조인에 건다.
     */
    @Query("SELECT new src.backend.boarding.dto.StaffRunRiderAggregateView(rr.runId, rr.status, rr.change, "
            + "COUNT(rr)) FROM RunRider rr JOIN Run r ON r.id = rr.runId "
            + "WHERE r.academyId = :academyId AND rr.runId IN :runIds "
            + "GROUP BY rr.runId, rr.status, rr.change")
    List<StaffRunRiderAggregateView> aggregateForStaffDashboard(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);
}
