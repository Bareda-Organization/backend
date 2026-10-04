package src.backend.exception.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.exception.entity.ExceptionReport;
import src.backend.exception.entity.ExceptionReportType;

/** {@link ExceptionReport} 영속성 접근 — 조회는 전부 학원으로 좁혀져 호출부가 조건을 빼먹을 자리가 부재하다. */
public interface ExceptionReportRepository extends JpaRepository<ExceptionReport, Long> {

    /**
     * 목록 조회(API_SPEC §5.20 목록) — {@code type}·{@code run_id}·{@code reported_at} 구간(= {@code date}
     * 하루치, 관계자 웹에서 이미 학원 자정 기준으로 환산해 넘긴다) 이 전부 선택적이다.
     *
     * <p>{@code type}·{@code runId} 는 {@code :type IS NULL OR ...} 형태로 처리한다({@code
     * ManagerRepository#searchByAcademyId} 와 같은 근거) — 필터별로 메서드를 나누면 한쪽에만 조건이
     * 빠지는 사고가 실제로 난다.
     *
     * <p>{@code from}·{@code to} 는 같은 널 검사 형태를 쓰지 않는다 — {@code OffsetDateTime} 파라미터가
     * {@code :from IS NULL} 처럼 <b>비교 없이 단독으로만</b> 등장하면 Postgres 가 그 자리의 타입을
     * 추론하지 못해 {@code could not determine data type of parameter} 로 500 이 난다(실측 — {@code
     * String}·{@code Long} 인 {@code type}·{@code runId} 는 같은 구조에서도 재현되지 않는다). 그래서
     * 항상 구체값을 요구하고, "필터 없음"은 {@link ExceptionReportQueryService} 가 극단 경계값(연도
     * 1·9999)으로 채워 넘긴다 — null 을 걸러내는 책임을 SQL 에서 서비스 계층으로 옮긴 것이다.
     */
    @Query("""
            SELECT er FROM ExceptionReport er
            WHERE er.academyId = :academyId
              AND (:type IS NULL OR er.type = :type)
              AND (:runId IS NULL OR er.runId = :runId)
              AND er.reportedAt >= :from
              AND er.reportedAt < :to
              AND (:handled IS NULL OR (CASE WHEN er.handledAt IS NULL THEN false ELSE true END) = :handled)
            ORDER BY er.reportedAt DESC
            """)
    List<ExceptionReport> search(@Param("academyId") Long academyId, @Param("type") ExceptionReportType type,
            @Param("runId") Long runId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("handled") Boolean handled, Limit limit);

    /**
     * 목록 상단의 처리·미처리 건수(§5.20 {@code counts}, Ruling 814) — {@link #search} 에서 {@code handled} 조건만 뺀
     * 같은 조건이라 상한(200건)과 무관하게 조건에 맞는 전량을 센다.
     */
    @Query("""
            SELECT COUNT(er.handledAt) AS handled, COUNT(er) - COUNT(er.handledAt) AS unhandled
            FROM ExceptionReport er
            WHERE er.academyId = :academyId
              AND (:type IS NULL OR er.type = :type)
              AND (:runId IS NULL OR er.runId = :runId)
              AND er.reportedAt >= :from
              AND er.reportedAt < :to
            """)
    ReportHandledCounts countByHandled(@Param("academyId") Long academyId,
            @Param("type") ExceptionReportType type, @Param("runId") Long runId,
            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 학원 안의 보고 1건 — 남의 학원·없는 보고는 빈 결과로 같다(존재 비노출, §5.20 처리 표시). */
    java.util.Optional<ExceptionReport> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 아직 처리되지 않은 보고만 처리됨으로 표시한다(Ruling 814 · §5.20) — 이미 처리된 보고는 0행이라 처음 처리자·시각이
     * 그대로 남는다. 두 관계자가 동시에 눌러도 행 잠금을 먼저 얻은 쪽만 갱신한다({@code EmergencyAlertRepository#ackIfUnacked}
     * 와 같은 형태). 학원 조건을 이 쿼리에도 다시 건다.
     *
     * @return 영향받은 행 수 — 0 이면 이미 처리된 보고(또는 이 학원 소속이 아님)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ExceptionReport er SET er.handledAt = :handledAt, er.handledBy = :handledBy "
            + "WHERE er.id = :id AND er.academyId = :academyId AND er.handledAt IS NULL")
    int handleIfUnhandled(@Param("id") Long id, @Param("academyId") Long academyId,
            @Param("handledBy") Long handledBy, @Param("handledAt") OffsetDateTime handledAt);
}
