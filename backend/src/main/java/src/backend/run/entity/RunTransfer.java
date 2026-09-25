package src.backend.run.entity;

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
 * 버스 간 이동(F4 S1, API_SPEC §5.8, RTE-07, Ruling 256)의 대기 행 — 출발·도착 두 회차
 * 어느 쪽도 즉시 재최적화를 부르지 않고, 이 표에 남겨 두었다가 둘 중 먼저 도는 확정 배치
 * ({@code RunConfirmationService#confirmOne})가 그 회차 쪽 절반(제외 또는 추가)을 반영한다 —
 * 강제 추가({@link RunForcedAddition})와 같은 "대기 후 배치 합류" 형태다(Ruling 197·198 계열).
 *
 * <p>{@link RunTransferStatus#APPLIED} 는 <b>도착 회차</b> 확정이 명단에 더한 상태다 — 출발 회차가 먼저
 * 확정돼도 {@link RunTransferStatus#STAGED} 로 남아, 도착 회차 확정 전까지 도착 회차의 정원 판정과
 * {@code TRANSFER_ALREADY_STAGED} 방어가 그 학생을 계속 본다(BR-093). 확정 배치는 상태로 대상을 거르지
 * 않고 항상 재계산하므로(자기 치유) 이 행 자체는 재시도에 안전하다.
 */
@Entity
@Table(name = "run_transfer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "from_run_id", nullable = false)
    private Long fromRunId;

    @Column(name = "to_run_id", nullable = false)
    private Long toRunId;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "note")
    private String note;

    @Convert(converter = RunTransferStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    private RunTransferStatus status;

    @Column(name = "requested_by_account_id", nullable = false)
    private Long requestedByAccountId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    private RunTransfer(Long studentId, Long fromRunId, Long toRunId, Long stopId, String note,
            Long requestedByAccountId, OffsetDateTime createdAt) {
        this.studentId = studentId;
        this.fromRunId = fromRunId;
        this.toRunId = toRunId;
        this.stopId = stopId;
        this.note = note;
        this.status = RunTransferStatus.STAGED;
        this.requestedByAccountId = requestedByAccountId;
        this.createdAt = createdAt;
    }

    /** 관계자가 학생을 다른 회차로 이동 신청할 때 생성한다(RTE-07). */
    public static RunTransfer stage(Long studentId, Long fromRunId, Long toRunId, Long stopId, String note,
            Long requestedByAccountId, OffsetDateTime createdAt) {
        return new RunTransfer(studentId, fromRunId, toRunId, stopId, note, requestedByAccountId, createdAt);
    }
}
