package src.backend.boarding.event;

import java.time.OffsetDateTime;

import src.backend.boarding.entity.RiderStatus;
import src.backend.global.common.enums.Direction;

/**
 * 승하차지가 이미 출발 처리된 뒤에 승차·하차·미승차가 기록됐음을 알리는 도메인 이벤트(Ruling 878) — 학부모 알림이 출발 때
 * 한 번 모아 보내는 경로({@link src.backend.run.event.StopDepartedEvent})에만 있어, 출발 뒤 기록은 알림이 영영 나가지 않던
 * 것을 기록 즉시 보내는 재료다.
 *
 * <p>{@code BoardingCommandService#updateStatus} 가 {@code run_stop} 행 잠금을 쥔 채 {@code departed_at} 을 읽어 <b>이미 출발
 * 처리된 경우에만</b> 발행한다 — 출발 때 묶음 발송과 같은 기록이 두 번 가거나 한 번도 안 가는 일이 없다. 출발 뒤 되돌리기는
 * 이 이벤트를 내지 않는다(알림 없음).
 */
public record RiderRecordedAfterDepartureEvent(Long runId, Long academyId, Direction direction, Long studentId,
        Long runRiderId, RiderStatus status, OffsetDateTime changedAt) {
}
