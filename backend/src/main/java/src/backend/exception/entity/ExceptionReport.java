package src.backend.exception.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 현장 예외 보고 — 보호자 부재·도로 통제 등 현장 상황을 관계자에게 통지하고 사후 확인 가능한 형태로
 * 남긴다(ERD §3.5 · BRD-07).
 *
 * <p>{@code academy_id}·{@code run_id}·{@code run_rider_id} 는 논리적 부모이나 DB FK 가 미설정이다
 * (ERD §4.2). {@code type='guardian_absent'} 일 때 {@code run_rider_id} 필수 CHECK 는 DB 가 강제하고,
 * 이 태스크는 필드 매핑까지만 다뤄 그 조건은 팩토리가 검증하지 않는다.
 */
@Entity
@Table(name = "exception_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExceptionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "run_rider_id")
    private Long runRiderId;

    @Convert(converter = ExceptionReportType.Db.class)
    @Column(name = "type", length = 20, nullable = false)
    private ExceptionReportType type;

    @Column(name = "memo", columnDefinition = "text", nullable = false)
    private String memo;

    @Column(name = "reported_by", nullable = false)
    private Long reportedBy;

    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt;

    private ExceptionReport(Long academyId, Long runId, ExceptionReportType type, String memo,
            Long reportedBy, OffsetDateTime reportedAt) {
        this.academyId = academyId;
        this.runId = runId;
        this.type = type;
        this.memo = memo;
        this.reportedBy = reportedBy;
        this.reportedAt = reportedAt;
    }

    /** 현장에서 예외 상황이 보고됐을 때 생성한다 — {@code run_rider_id} 는 보호자 부재 보고에서만 채워지며 팩토리 밖에서 다룬다. */
    public static ExceptionReport forReport(Long academyId, Long runId, ExceptionReportType type,
            String memo, Long reportedBy, OffsetDateTime reportedAt) {
        return new ExceptionReport(academyId, runId, type, memo, reportedBy, reportedAt);
    }

    /**
     * {@code type=guardian_absent} 보고에서만 호출한다(Phase 11, API_SPEC §4.13) — 위 팩토리
     * javadoc 이 예고한 "팩토리 밖" 이 이 메서드다. {@code ck_exception_report_run_rider} 가
     * "guardian_absent 면 run_rider_id 필수"를 DB 에서 강제하므로, 호출부가 이 메서드를 걸러
     * 부르지 않아도 저장 시점에 위반이 500 이 아니라 제약으로 드러난다(선검사는 커맨드 서비스가 한다).
     */
    public void assignRunRider(Long runRiderId) {
        this.runRiderId = runRiderId;
    }
}
