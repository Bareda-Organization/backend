package src.backend.run.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.BaseTimeEntity;

/**
 * 회차({@link Run})의 확정 배치 기록 컬럼 — 확정 실행 시각 · 연속 실패 횟수 · 재시도 허용 시각. 전부 읽기 전용이다: 엔티티가 아니라 {@code RunRepository} 의 조건부 UPDATE 가 DB 에서 직접
 * 쓴다. 테이블과 컬럼 이름은 {@code run} 그대로이고 엔티티 크기 때문에 필드 묶음만 떼어 놓았다.
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class RunConfirmationState extends BaseTimeEntity {

    /** 확정 배치가 실제로 실행된 시각. 지연 실행 추적 근거(PRD §5.2). */
    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    /**
     * 확정 배치가 이 회차에서 연속으로 실패한 횟수 — 성공하면 0으로 되돌아간다.
     *
     * <p>값을 이 컬럼에 두는 이유는 배치가 재시작되면 인메모리 카운터는 사라지기 때문이다. 이 필드는
     * 읽기 전용이다 — 증가·초기화는 {@code RunRepository} 의 조건부 UPDATE 가 DB 에서 직접 하고,
     * 엔티티를 통해 쓰지 않는다(아래 {@code confirmIfIdle}·{@code recordFailure} 참고). 조건부 UPDATE 인
     * 이유는 확정 배치의 멱등성이 영향받은 행 수로 판정되어야 하는데, 엔티티 변경 후
     * {@code save()} 로는 "내가 실제로 idle 이던 행을 바꿨는지" 를 알 수 없기 때문이다.
     */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /**
     * 확정 배치가 이 회차를 다시 시도해도 되는 가장 이른 시각 — {@code null} 이면 바로 대상이다. 판정 시각
     * ({@code confirm_at})과 다른 시계다. {@code consecutive_failures} 와 같은 이유로 엔티티 변경이 아니라 조건부 UPDATE 가 쓴다.
     */
    @Column(name = "confirm_retry_at")
    private OffsetDateTime confirmRetryAt;
}
