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

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.domain.ChangeWindow;

/**
 * 회차별 탑승 의사 — 확정 배치가 읽는 입력 두 축(일일 승하차지·금일 탑승 의사) 중 하나이며, ②구간 변경
 * 한도(C-04)의 카운터({@code change_used_count})를 함께 보유한다(ERD §3.4).
 *
 * <p>{@code change_used_count BETWEEN 0 AND 1} CHECK 는 DB 가 강제하지만, 엔티티도 같은 상한을 스스로
 * 지킨다({@link #consumeChangeQuota()}) — DB 제약에만 기대면 위반이 {@code DataIntegrityViolationException}
 * 으로 나와 {@code 403 CHANGE_LIMIT_REACHED} 응답이 되지 못한다.
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

    /** ②구간 변경 한도가 아직 남아 있는가 — 한도 단위는 이 행 자체(회차 1개), 목표 5. */
    public boolean hasChangeQuota() {
        return changeUsedCount < 1;
    }

    /**
     * ②구간 변경 한도 1회를 소비한다 — 이미 소진했으면 {@code 403 CHANGE_LIMIT_REACHED}(엔티티 단에서
     * 막아 DB CHECK 위반({@code DataIntegrityViolationException})으로 새지 않게 한다).
     */
    public void consumeChangeQuota() {
        if (!hasChangeQuota()) {
            throw new BusinessException(ErrorCode.CHANGE_LIMIT_REACHED);
        }
        this.changeUsedCount = 1;
    }

    /**
     * 소비한 한도를 되돌린다 — 자동 거절(목표 7)이 "횟수 미소진"(API_SPEC §1.6)을 지키려면 이 메서드가
     * 있어야 한다. 서버 처리 실패 시 기존 상태 복구 + 횟수 미소진(C-10)도 같은 메서드를 쓴다.
     */
    public void restoreChangeQuota() {
        this.changeUsedCount = 0;
    }

    /**
     * 변경 반영 — {@code riding} 을 바꾸고 그 판정이 어느 구간에서 이뤄졌는지·언제·누가 처리했는지를
     * 함께 남긴다. {@code appliedSegment} 는 {@link ChangeWindow#code()} 로 smallint 컬럼에 왕복한다.
     */
    public void applyRiding(boolean riding, ChangeWindow appliedSegment, OffsetDateTime changedAt, Long changedBy) {
        this.riding = riding;
        this.appliedSegment = appliedSegment.code();
        this.changedAt = changedAt;
        this.changedBy = changedBy;
    }
}
