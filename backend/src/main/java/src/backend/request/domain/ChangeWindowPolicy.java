package src.backend.request.domain;

import java.time.OffsetDateTime;

import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/**
 * 3구간 판정의 유일한 지점(IMPLEMENTATION_PLAN:817) — 컨트롤러나 각 command 에 흩어지면 같은 요청이
 * 호출 경로에 따라 다른 구간으로 판정될 수 있어 이 클래스 하나로 모은다.
 */
public final class ChangeWindowPolicy {

    private ChangeWindowPolicy() {
    }

    /**
     * {@code now} 는 반드시 주입된 {@code Clock} 에서 얻은 서버 시계여야 한다 — 요청에 실린
     * {@code requested_at} 을 넘기면 "판정 주체는 서버 시계다"(목표 14, Ruling 194)가 깨진다. 그래서
     * 이 시그니처는 애초에 요청 접수 시각을 받는 파라미터를 두지 않는다.
     *
     * <p>{@code run.confirmAt} 은 컬럼값을 그대로 읽는다 — {@code RunConfirmationPolicy.confirmAtOf}
     * 로 재계산하지 않는다({@link Run} 자바독, ARCHITECTURE §9.2 두 시계 분리). 재계산하면 배치가 늦게
     * 돈 회차의 판정 기준이 실행 시각 쪽으로 밀린다.
     *
     * <p>⚠ <b>판정 항목 — "출발 시각은 지났는데 운행이 아직 시작되지 않은" 창.</b> API_SPEC §1.6 은
     * ②를 "출발 전", ③을 "운행 시작 후"로만 적어 그 사이가 명시되지 않는다. 이 구현은 그 창을 <b>③
     * (CLOSED)</b>으로 판정한다 — 근거 둘.
     * <ol>
     *   <li>②의 마감({@code deadline_at})이 곧 회차 출발 시각이다(API_SPEC §5.6). 마감이 지난 뒤에도
     *       ②로 접수를 받으면 승인할 시간이 0인 요청을 큐에 쌓는 것과 같다.</li>
     *   <li>API_SPEC §1.6 은 "② 구간 요청이 출발 시각 도달 또는 {@code Run.status → moving} 중
     *       <b>먼저 오는 시점</b>까지 미처리로 남으면 자동 거절"이라고 명시한다 — 즉 사양 스스로
     *       출발 시각 도달을 {@code moving} 전이와 <b>동격의 마감 신호</b>로 다룬다. 그 시점부터는
     *       이미 "처리 대상에서 빠진" 상태이므로 새 요청을 ②로 받아들이는 것은 이 전제와 어긋난다.</li>
     * </ol>
     */
    public static ChangeWindow segmentOf(Run run, OffsetDateTime now) {
        if (run.getStatus() == RunStatus.MOVING || run.getStatus() == RunStatus.FINISHED) {
            return ChangeWindow.CLOSED;
        }
        if (!now.isBefore(run.getDepartTime())) {
            return ChangeWindow.CLOSED;
        }
        if (!now.isBefore(run.getConfirmAt())) {
            return ChangeWindow.APPROVAL_REQUIRED;
        }
        return ChangeWindow.IMMEDIATE;
    }
}
