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
     *
     * <p>②구간 취소형 승인(API_SPEC §5.6 "명단 제외(absent)")도 같은 메서드를 쓴다 — 행을 지우지
     * 않고 상태만 바꾼다. {@link src.backend.request.preview.ApprovalPreviewResolver#candidateRosterOf}
     * 가 {@code ABSENT} 를 이미 명단 조립에서 제외하므로, 이후 같은 회차의 다른 승인·재계산이 이
     * 학생을 다시 태우지 않는다.
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

    /** 동승자가 승차를 확인한다(BRD-01, API_SPEC §4.6 {@code status=boarded}). */
    public void board(OffsetDateTime changedAt) {
        this.status = RiderStatus.BOARDED;
        this.boardedAt = changedAt;
        this.changedAt = changedAt;
    }

    /** 동승자가 하차를 확인한다(BRD-02, API_SPEC §4.6 {@code status=alighted}). */
    public void alight(OffsetDateTime changedAt) {
        this.status = RiderStatus.ALIGHTED;
        this.alightedAt = changedAt;
        this.changedAt = changedAt;
    }

    /** 동승자가 미승차를 확정한다(BRD-04, API_SPEC §4.6 {@code status=no_show}). */
    public void markNoShow(OffsetDateTime changedAt) {
        this.status = RiderStatus.NO_SHOW;
        this.changedAt = changedAt;
    }

    /**
     * 하원 회차 시작 시 배치가 명단 전원을 일괄 승차 처리한다(C-07 · BRD-03, 목표 11).
     *
     * <p>{@link #board} 와 저장 값 자체는 같지만(상태·{@code boarded_at}·{@code changed_at}) 메서드를
     * 따로 둔다 — 동승자가 개별 확인한 것과 시스템이 일괄 반영한 것은 이력에 어떤 {@code actor_type}
     * 을 남길지 호출부가 판단해야 하는 서로 다른 사건이고, 메서드 이름이 그 판단 지점을 코드에서
     * 바로 드러내야 하기 때문이다.
     */
    public void autoBoard(OffsetDateTime changedAt) {
        this.status = RiderStatus.BOARDED;
        this.boardedAt = changedAt;
        this.changedAt = changedAt;
    }

    /**
     * 되돌리기(BRD-05, API_SPEC §4.7)로 직전 상태로 되돌린다.
     *
     * <p>{@code boarded_at}·{@code alighted_at} 은 건드리지 않는다 — "언제 승차했었는가" 라는 사실은
     * 되돌려도 사라지지 않고, 지우면 되돌리기 전 이력을 재구성할 근거가 {@code rider_status_history}
     * 밖에 남지 않는다.
     */
    public void revertTo(RiderStatus previousStatus, OffsetDateTime changedAt) {
        this.status = previousStatus;
        this.changedAt = changedAt;
    }
}
