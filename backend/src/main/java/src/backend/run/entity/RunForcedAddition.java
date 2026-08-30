package src.backend.run.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ①구간 강제 추가(RTE-06, API_SPEC §5.7)의 대기 행 — 아직 {@code idle} 인 회차는
 * {@code run_rider} 가 존재하지 않아, 이 표에 학생·정차지를 남겨 두고 나중에 도래하는 확정 배치
 * ({@code RunConfirmationService#confirmOne})가 그날 명단에 합친다(Ruling 198 — ①구간에서는
 * 재최적화를 직접 부르지 않는다).
 *
 * <p>{@code change_request}(P-06 일일 변경)를 재사용하지 않는다 — 그 표는 "이미 명단에 있는 학생의
 * 정차지 이동"만 다루고, 이것은 "명단에 없는 학생을 새로 올리는" 동작이라 의미가 다르다(Ruling 197).
 */
@Entity
@Table(name = "run_forced_addition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunForcedAddition {

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

    @Column(name = "added_by", nullable = false)
    private Long addedBy;

    @Column(name = "added_at", nullable = false)
    private OffsetDateTime addedAt;

    private RunForcedAddition(Long runId, Long studentId, Long stopId, Long addedBy, OffsetDateTime addedAt) {
        this.runId = runId;
        this.studentId = studentId;
        this.stopId = stopId;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    /** 관계자가 ①구간에서 회차에 학생을 강제로 얹을 때 생성한다(RTE-06). */
    public static RunForcedAddition forRun(Long runId, Long studentId, Long stopId, Long addedBy,
            OffsetDateTime addedAt) {
        return new RunForcedAddition(runId, studentId, stopId, addedBy, addedAt);
    }
}
