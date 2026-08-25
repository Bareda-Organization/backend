package src.backend.request.entity;

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
 * 회차별 탑승 의사 — 확정 배치가 읽는 입력 두 축(일일 승하차지·금일 탑승 의사) 중 하나이며, ②구간 변경
 * 한도(C-04)의 카운터({@code change_used_count})를 함께 보유한다(ERD §3.4).
 *
 * <p>{@code change_used_count BETWEEN 0 AND 1} CHECK 는 DB 가 강제하고, 카운터 증가·한도 판정 메서드는
 * 이 태스크 범위 밖이다(Phase 8 담당) — 팩토리는 초기값 0 만 채운다.
 *
 * <p>{@code created_at} 만 있고 {@code updated_at} 이 없어 {@code BaseTimeEntity} 를 상속하지 않는다
 * (엔티티 작성 규약 §4.4, Ruling 62) — auditing 은 두 컬럼을 모두 가진 테이블에만 쓰고, 이 컬럼은 평범한
 * 필드로 두어 값을 팩토리 파라미터로 받는다(호출부가 {@code Clock} 에서 얻어 넘긴다).
 */
@Entity
@Table(name = "boarding_intent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardingIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "riding", nullable = false)
    private boolean riding;

    @Column(name = "change_used_count", nullable = false)
    private int changeUsedCount;

    @Column(name = "applied_segment")
    private Short appliedSegment;

    @Column(name = "changed_at")
    private OffsetDateTime changedAt;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    private BoardingIntent(Long runId, Long studentId, OffsetDateTime createdAt) {
        this.runId = runId;
        this.studentId = studentId;
        this.riding = true;
        this.changeUsedCount = 0;
        this.createdAt = createdAt;
    }

    /** 회차가 만들어질 때(또는 학생이 그 회차에 처음 등장할 때) 기본값(탑승 ON·한도 미사용)으로 생성한다. */
    public static BoardingIntent forRun(Long runId, Long studentId, OffsetDateTime createdAt) {
        return new BoardingIntent(runId, studentId, createdAt);
    }
}
