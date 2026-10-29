package src.backend.run.command;

/**
 * 확정 계산을 읽은 뒤 확정 저장이 회차 행 잠금 아래에서 명단 입력을 다시 읽었더니 달라졌다는 신호다 — 업무 오류도 확정 실패도 아니다. 아무것도 쓰지 않은 채 던지므로 저장 트랜잭션은 그대로 끝나고(롤백으로 표시하지 않는다),
 * 회차는 {@code idle} 로 남아 다음 틱이 바뀐 입력으로 다시 읽는다. 호출부는 이것을 실패 횟수 · 재시도 시각 · 실패 지표에 반영하지 않는다.
 */
public class ConfirmationInputsChangedException extends RuntimeException {

    public ConfirmationInputsChangedException(Long runId) {
        super("확정 계산 중 명단 입력이 바뀌었다 — 다음 틱에 다시 읽는다: runId=" + runId);
    }
}
