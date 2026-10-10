package src.backend.request.domain;

import java.time.OffsetDateTime;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.policy.PolicyConstants;
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
     * 임시 취소된 회차는 탑승 토글·변경 신청·승인을 받지 않는다(Ruling 376, BR-224) — {@link Run#cancel} 은
     * 상태를 바꾸지 않고 {@code canceledAt} 만 채우므로 {@link #segmentOf} 가 걸러내지 못한다. 대상 회차를 얻는
     * 지점({@code TargetRunLookup} · 저장 시점 재판정 · 승인 잠금 뒤)이 각자 이 검사를 부른다. 거절은 부르지 않는다.
     *
     * @throws BusinessException {@code 409 RUN_CANCELED}
     */
    public static void assertNotCanceled(Run run) {
        if (run.isCanceled()) {
            throw new BusinessException(ErrorCode.RUN_CANCELED);
        }
    }

    /** ② 마감 — 출발 시각 + 운행 시작 창(Ruling 870). 저장·응답의 {@code deadline_at} 과 자동 거절 시각이 모두 이 값이다. */
    public static OffsetDateTime deadlineOf(Run run) {
        return run.getDepartTime().plus(PolicyConstants.START_WINDOW);
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
     * <p><b>②의 끝은 "운행 시작({@code moving}) 또는 출발 시각 + {@link PolicyConstants#START_WINDOW} 중 먼저 오는
     * 시점"이다(Ruling 870).</b> 출발 시각이 지났어도 기사가 운행을 시작하지 않았으면 아직 노선을 고칠 수 있어 ③으로
     * 보지 않는다 — 10분은 운행 시작이 허용되는 마지막 시각(출발 ±10분 창의 끝)이라 그 뒤에는 시작할 수 없다. 마감
     * ({@link #deadlineOf})과 이 판정이 같은 시각을 쓰므로 자동 거절 배치·승인 처리·신청 접수가 한 경계를 공유한다.
     */
    public static ChangeWindow segmentOf(Run run, OffsetDateTime now) {
        if (run.getStatus() == RunStatus.MOVING || run.getStatus() == RunStatus.FINISHED) {
            return ChangeWindow.CLOSED;
        }
        if (!now.isBefore(deadlineOf(run))) {
            return ChangeWindow.CLOSED;
        }
        if (!now.isBefore(run.getConfirmAt())) {
            return ChangeWindow.APPROVAL_REQUIRED;
        }
        return ChangeWindow.IMMEDIATE;
    }
}
