package src.backend.boarding.entity;

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

import src.backend.global.common.BaseTimeEntity;
import src.backend.global.common.enums.ChangeType;

/**
 * 회차별 탑승자 — 확정 배치가 만드는 회차 단위 명단 행이며 탑승 상태 5종의 저장처다(ERD §3.3 · C-02·C-06·C-07 · BRD-01~04).
 * {@code stop_id} 는 {@code run_stop} 이 아니라 승하차지 마스터를 참조한다 — 그날의 승하차지는 노선 버전이 바뀌어도 불변이기 때문이다.
 */
@Entity
@Table(name = "run_rider")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunRider extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "stop_id", nullable = false)
    private Long stopId;

    @Convert(converter = RiderStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    private RiderStatus status;

    /** {@code added}·{@code removed} 만 쓴다 — {@link ChangeType#SKIPPED} 는 {@code run_stop} 전용이며 CHECK 가 이 부분집합을 강제한다. */
    @Convert(converter = ChangeType.Db.class)
    @Column(name = "change", length = 10)
    private ChangeType change;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "seat_no")
    private Integer seatNo;

    @Column(name = "boarded_at")
    private OffsetDateTime boardedAt;

    @Column(name = "alighted_at")
    private OffsetDateTime alightedAt;

    @Column(name = "changed_at")
    private OffsetDateTime changedAt;

    private RunRider(Long runId, Long studentId, Long stopId) {
        this.runId = runId;
        this.studentId = studentId;
        this.stopId = stopId;
        this.status = RiderStatus.WAITING;
    }

    /** 확정 배치가 boarding_intent·weekly_address 를 반영해 회차별 명단 행을 만들 때 생성한다 — 초기 상태는 대기 중이다. */
    public static RunRider uponConfirmation(Long runId, Long studentId, Long stopId) {
        return new RunRider(runId, studentId, stopId);
    }

    /**
     * 관리자의 ②구간 취소형 승인(API_SPEC §5.6 "명단 제외(absent)")으로 이 학생을 오늘 명단에서
     * 뺀다 — 행을 지우지 않고 상태만 바꾼다. {@link src.backend.request.preview.ApprovalPreviewResolver
     * #candidateRosterOf} 가 {@code ABSENT} 를 이미 명단 조립에서 제외하므로, 이후 같은 회차의 다른
     * 승인·재계산이 이 학생을 다시 태우지 않는다.
     */
    public void markAbsent(OffsetDateTime changedAt) {
        this.status = RiderStatus.ABSENT;
        this.changedAt = changedAt;
    }

    /** 관리자의 ②구간 경유지 이동형 승인(API_SPEC §5.6, P-06)으로 오늘의 승하차지를 바꾼다. */
    public void relocateTo(Long newStopId, OffsetDateTime changedAt) {
        this.stopId = newStopId;
        this.changedAt = changedAt;
    }
}
