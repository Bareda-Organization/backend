package src.backend.request.command;

import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.event.ChangeRequestAutoRejectedEvent;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;

/**
 * 변경 요청 한 건을 자동 거절로 반영하는 <b>단일 진입점</b>(API_SPEC §1.6) — 폴링 스케줄러와
 * {@code moving} 종결 서비스가 이 클래스의 {@link #autoRejectOne} 만 부른다.
 *
 * <p>{@code ChangeRequestAutoRejectionScheduler}·{@code ChangeRequestAutoRejectionService} 와
 * 다른 빈으로 둔 이유는 두 가지다. 첫째, 호출부가 한 틱·한 회차 안에서 여러 건을 순회할 때 항목마다
 * <b>독립된 트랜잭션</b>이어야 하나가 실패해도 나머지가 함께 롤백되지 않는다 — 같은 빈 안에서
 * {@code this} 를 호출하면 Spring AOP 프록시를 우회해 트랜잭션이 새로 열리지 않는다. 둘째, 이
 * 클래스만 저장소·이벤트 발행에 의존하게 좁혀 두 호출부가 같은 부수효과를 공유한다는 것을 코드로
 * 보증한다({@code RunConfirmationPersistence} 와 같은 근거).
 */
@Component
@RequiredArgsConstructor
public class ChangeRequestAutoRejectionPersistence {

    private final ChangeRequestRepository changeRequestRepository;

    private final BoardingIntentRepository boardingIntentRepository;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 그 변경 요청을 대기중 → 자동거절로 갱신하고, 소진한 한도를 되돌리고, 통지 이벤트를 발행한다.
     *
     * <p><b>재최적화를 호출하지 않는다</b> — 자동 거절은 기존 노선을 유지한다(API_SPEC §1.6). 이
     * 메서드가 노선 계산·{@code route_version} 관련 저장소를 전혀 의존하지 않는 것 자체가 그 보장의
     * 근거다 — 부를 대상이 없으니 나중에 실수로 붙일 자리도 좁다.
     *
     * <p>조건부 UPDATE({@link ChangeRequestRepository#autoRejectIfPending})가 갱신에 실패하면(0행)
     * 그 즉시 반환한다 — 이미 처리됐거나(관리자 승인·거절), 동시에 도는 다른 호출이 먼저 이겼거나,
     * 대상이 없는 것이다. 어느 쪽이든 이 메서드가 할 일은 남지 않는다.
     *
     * @return 실제로 자동 거절을 반영했으면 {@code true}, 아니면(경쟁 패배 포함) {@code false}
     */
    @Transactional
    public boolean autoRejectOne(Long changeRequestId, OffsetDateTime decidedAt) {
        int updated = changeRequestRepository.autoRejectIfPending(changeRequestId, decidedAt);
        if (updated == 0) {
            return false;
        }

        ChangeRequest request = changeRequestRepository.findById(changeRequestId).orElseThrow();
        boardingIntentRepository.findByRunIdAndStudentId(request.getRunId(), request.getStudentId())
                .ifPresent(BoardingIntent::restoreChangeQuota);
        eventPublisher.publishEvent(new ChangeRequestAutoRejectedEvent(request.getAcademyId(), request.getId(),
                request.getRunId(), request.getStudentId(), request.getRequestedBy(), decidedAt));
        return true;
    }
}
