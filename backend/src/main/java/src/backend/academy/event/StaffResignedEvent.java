package src.backend.academy.event;

import java.time.OffsetDateTime;

/**
 * 관계자가 퇴사 처리됐음을 알리는 도메인 이벤트(ACAD-06, API_SPEC §6.7, Ruling 894 ②) — {@code notification} 모듈이 그
 * 계정의 푸시 단말을 지운다. 매니저 삭제({@link src.backend.manager.event.ManagerDeletedEvent})와 같은 형태다.
 *
 * <p>퇴사 처리 트랜잭션 안에서 발행되고 구독자도 그 안에서 반영한다 — 퇴사만 커밋되고 단말이 남으면 퇴사자 기기로 알림이 간다.
 */
public record StaffResignedEvent(Long accountId, OffsetDateTime resignedAt) {
}
