package src.backend.exception.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.exception.entity.ExceptionReport;
import src.backend.exception.entity.ExceptionReportType;

/** {@link ExceptionReport} 영속성 접근 — 조회는 전부 학원으로 좁혀져 호출부가 조건을 빼먹을 자리가 부재하다. */
public interface ExceptionReportRepository extends JpaRepository<ExceptionReport, Long> {

    /**
     * 상세 조회 대상 보고 1건(API_SPEC §5.20 상세) — 학원이 어긋나면 빈 결과이고 호출부가 그것을
     * {@code 404 REPORT_NOT_FOUND} 로 답한다({@code BusRepository#findByIdAndAcademyId} 와 같은 형태
     * — "없음" 과 "남의 학원" 을 응답에서 가르지 않는다).
     */
    Optional<ExceptionReport> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 목록 조회(API_SPEC §5.20 목록) — {@code type}·{@code run_id}·{@code reported_at} 구간(= {@code date}
     * 하루치, 관계자 웹에서 이미 학원 자정 기준으로 환산해 넘긴다) 이 전부 선택적이다.
     *
     * <p>{@code :type IS NULL OR ...} 형태로 필터를 가르지 않고 <b>한 쿼리 안에서 널 검사</b>로 처리한다
     * ({@code ManagerRepository#searchByAcademyId} 와 같은 근거) — 필터별로 메서드를 나누면 한쪽에만
     * 조건이 빠지는 사고가 실제로 난다.
     */
    @Query("""
            SELECT er FROM ExceptionReport er
            WHERE er.academyId = :academyId
              AND (:type IS NULL OR er.type = :type)
              AND (:runId IS NULL OR er.runId = :runId)
              AND (:from IS NULL OR er.reportedAt >= :from)
              AND (:to IS NULL OR er.reportedAt < :to)
            ORDER BY er.reportedAt DESC
            """)
    List<ExceptionReport> search(@Param("academyId") Long academyId, @Param("type") ExceptionReportType type,
            @Param("runId") Long runId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
