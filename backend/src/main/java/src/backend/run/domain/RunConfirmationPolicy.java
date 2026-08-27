package src.backend.run.domain;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 회차가 <b>언제 확정되어야 하는가</b>를 정하는 유일한 지점(C-03 · ARCHITECTURE §9.2 ·
 * {@code ck_run_confirm_at}).
 *
 * <p>확정 시각을 파생값이 아니라 {@code run.confirm_at} <b>컬럼</b>으로 두는 것이 설계다 — 확정 배치가
 * "실행 시각이 지난 회차" 를 매 실행마다 조회하므로, 계산식으로 두면 그 조회가 전건 스캔이 된다.
 *
 * <p><b>두 시계를 섞지 않는다</b>(ARCHITECTURE §9.2) — 여기서 정하는 것은 <b>판정 시각</b>(회차가
 * 확정되어야 하는 때)이고, 배치가 도는 <b>실행 시각</b>은 이 클래스가 알지 못한다. 둘을 한 값으로
 * 다루면 배치가 늦게 돈 회차의 확정 시각이 실행 시각으로 밀려, 출발 30분 전이라는 약속이 깨진 것을
 * 아무도 관측하지 못한다.
 */
public final class RunConfirmationPolicy {

    /**
     * 출발 시각으로부터 얼마나 앞서 확정하는가 — 정책 상수이므로 설정이 아니라 코드에 둔다
     * (횡단 규칙 10: yml 로 빼면 운영에서 사양 값이 조용히 바뀐다).
     *
     * <p>이 값은 {@code ck_run_confirm_at} CHECK 와 <b>같은 값이어야 한다</b> — 어긋나면 저장이
     * 제약 위반으로 거부되므로, DB 가 이 상수의 잘못을 대신 잡아 준다.
     */
    private static final Duration CONFIRM_LEAD = Duration.ofMinutes(30);

    private RunConfirmationPolicy() {
    }

    /** 그 출발 시각의 확정 예정 시각 — 출발 30분 전이다. */
    public static OffsetDateTime confirmAtOf(OffsetDateTime departAt) {
        return departAt.minus(CONFIRM_LEAD);
    }
}
