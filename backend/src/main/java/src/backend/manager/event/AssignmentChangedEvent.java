package src.backend.manager.event;

import java.time.OffsetDateTime;
import src.backend.global.common.enums.ManagerRole;

/**
 * 회차의 기사·동승자 배치가 바뀌었음을 알리는 도메인 이벤트 — {@code assignment_changed} 알림(API_SPEC §9.7
 * "당일 배치 변경 → 해당 매니저", MGR-05)이 이 이벤트를 구독한다.
 *
 * <p>발행 지점은 둘이다 — 관계자의 수동 배치(§5.14)와 확정 배치의 동승자 자동 배정(Ruling 330). 두 경로가
 * 같은 이벤트를 쓰는 이유는 매니저가 받는 사실("이 회차에 배치됐다")이 같기 때문이며, 경로를 구별해야 하면
 * 필드를 더한다. 배치를 바꾼 트랜잭션이 롤백되면 발행되지 않아야 하므로 구독자는 커밋 후에 처리한다.
 *
 * @param managerId 새로 배치된 매니저 — 이 매니저의 계정이 알림 수신자다
 */
public record AssignmentChangedEvent(Long runId, Long academyId, Long managerId, ManagerRole role,
        OffsetDateTime changedAt) {
}
