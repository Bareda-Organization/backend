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
     * ③구간(운행 시작 후) 미등원 토글이 반영될 때 부재로 표시한다(ATT-01·02, API_SPEC §3.6).
     *
     * <p>{@code changedAt} 만 남기고 {@code note}·{@code seat_no} 는 건드리지 않는다 — 자리 배정은
     * 재최적화가 없는 이 구간에서 바뀔 이유가 없다(C-04 ③ · C-05, 노선·순번 불변).
     */
    public void markAbsent(OffsetDateTime changedAt) {
        this.status = RiderStatus.ABSENT;
        this.changedAt = changedAt;
    }
}
