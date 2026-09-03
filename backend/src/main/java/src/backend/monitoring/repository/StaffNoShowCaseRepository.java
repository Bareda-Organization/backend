package src.backend.monitoring.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.exception.entity.NoShowCase;
import src.backend.monitoring.dto.StaffNoShowCaseView;

/**
 * {@link NoShowCase} 의 <b>대시보드 전용</b> 접근(§5.3 {@code runs[].no_show_cases[]}, BRD-05) —
 * {@code no_show_case} 는 {@code academy_id} 컬럼이 부재한 이중 부모 경유 자원이다({@code no_show_case}
 * → {@code run_rider} → {@code run}, {@code RunStopRepository
 * #findAllByRouteVersionIdAndAcademyIdOrderBySeq} 와 같은 형태).
 *
 * <p>{@code exception.repository} 패키지에 두지 않는 이유는 쓰기 소유 경계다 — {@code exception/}
 * 은 T3 소유이고({@code p13-task-t1.md} 범위 밖 목록), 이 조회는 대시보드 전용이라 그 모듈의
 * 관심사가 아니다. {@code NoShowCase} 엔티티를 다른 모듈에서 JPQL 로 참조하는 것은
 * {@code AssignmentRepository#findAssignedManagers} 가 {@code Run} 을 참조하는 것과 같은 이미
 * 확립된 관례다.
 */
public interface StaffNoShowCaseRepository extends JpaRepository<NoShowCase, Long> {

    /**
     * 회차 목록의 <b>진행 중</b>({@code resolvedAt IS NULL}) 미승차 에스컬레이션 케이스를 학생·
     * 승하차지 이름과 함께 읽는다.
     *
     * <p>{@code stop_id} 는 {@code run_stop} 이 아니라 승하차지 마스터를 가리킨다({@code RunRider}
     * 자바독) — 그래서 {@code student.entity.Stop} 을 조인한다.
     */
    @Query("SELECT new src.backend.monitoring.dto.StaffNoShowCaseView(rr.runId, s.name, st.name, n.expiresAt) "
            + "FROM NoShowCase n "
            + "JOIN RunRider rr ON rr.id = n.runRiderId "
            + "JOIN Student s ON s.id = rr.studentId "
            + "JOIN Stop st ON st.id = rr.stopId "
            + "JOIN Run r ON r.id = rr.runId "
            + "WHERE r.academyId = :academyId AND rr.runId IN :runIds AND n.resolvedAt IS NULL")
    List<StaffNoShowCaseView> findActiveByAcademyIdAndRunIdIn(@Param("academyId") Long academyId,
            @Param("runIds") Collection<Long> runIds);
}
