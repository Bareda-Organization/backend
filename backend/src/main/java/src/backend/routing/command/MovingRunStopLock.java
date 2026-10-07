package src.backend.routing.command;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.entity.RunStatus;

/**
 * 운행 중 회차의 노선 잠금 판정(ARCHITECTURE §8.5 운행 시작과 동시에 노선 잠금, BR-052) — 승하차지 좌표를 바꾸는 두 경로(노선의
 * 승하차지 저장 · 승하차지 수정, Ruling 849)가 같은 판정을 쓰도록 한 곳에 둔다.
 *
 * <p>운행일이 어제보다 이른 끝나지 않은 회차는 잠금 대상이 아니다({@link MovingRunWindowPolicy}, R46-KFIXBE K-1).
 */
@Component
@RequiredArgsConstructor
public class MovingRunStopLock {

    private final RunStopRepository runStopRepository;

    private final MovingRunWindowPolicy movingRunWindowPolicy;

    /**
     * 좌표가 바뀌는 승하차지 중 하나라도 운행 중 회차의 현재 노선에 서면 {@code 403 CHANGE_WINDOW_CLOSED} 다 — 이름·주소만 고치는 것은
     * 판정에 쓰이지 않아 호출하지 않는다. 빈 목록은 통과한다.
     */
    public void assertNotRelocating(Long academyId, List<Long> relocatedStopIds) {
        if (!relocatedStopIds.isEmpty()
                && runStopRepository.existsOnMovingRun(relocatedStopIds, academyId, RunStatus.MOVING,
                        movingRunWindowPolicy.earliestServiceDate())) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
    }
}
