package src.backend.exception.command;

import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.event.NoShowEscalatedEvent;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 미승차 케이스 한 건을 에스컬레이션으로 반영하는 <b>단일 진입점</b>(목표 1, API_SPEC §4.8) —
 * 폴링 스케줄러({@code NoShowEscalationScheduler})만 {@link #escalateOne} 을 부른다.
 *
 * <p>스케줄러와 다른 빈으로 둔 이유는 {@code ChangeRequestAutoRejectionPersistence} 와 같다 — 한
 * 틱이 여러 케이스를 순회할 때 항목마다 독립된 트랜잭션이어야 하나가 실패해도 나머지가 함께
 * 롤백되지 않는다(같은 빈 안에서 {@code this} 를 부르면 AOP 프록시를 우회해 트랜잭션이 새로 열리지
 * 않는다).
 */
@Component
@RequiredArgsConstructor
public class NoShowEscalationPersistence {

    private final NoShowCaseRepository noShowCaseRepository;

    private final RunRiderRepository runRiderRepository;

    private final RunRepository runRepository;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 그 미승차 케이스를 에스컬레이션으로 갱신하고, 관계자 통지 이벤트를 발행한다.
     *
     * <p>조건부 UPDATE({@link NoShowCaseRepository#escalateIfDue})가 갱신에 실패하면(0행) 그 즉시
     * 반환한다 — 이미 처리됐거나(응답 도착으로 해소), 동시에 도는 다른 틱이 먼저 이겼거나, 대상이
     * 없는 것이다. {@code academyId} · {@code studentId} · {@code runId} 는 {@code NoShowCase} 가
     * 직접 들고 있지 않아 {@code RunRider} → {@code Run} 을 순서대로 거쳐 얻는다 — 조건부 UPDATE
     * 가 이미 이겼으니 이 조회들은 존재를 전제해도 된다({@code findById().orElseThrow()}).
     *
     * @return 실제로 에스컬레이션을 반영했으면 {@code true}, 아니면(경쟁 패배 포함) {@code false}
     */
    @Transactional
    public boolean escalateOne(Long caseId, OffsetDateTime now) {
        int updated = noShowCaseRepository.escalateIfDue(caseId, now);
        if (updated == 0) {
            return false;
        }

        NoShowCase noShowCase = noShowCaseRepository.findById(caseId).orElseThrow();
        RunRider rider = runRiderRepository.findById(noShowCase.getRunRiderId()).orElseThrow();
        Run run = runRepository.findById(rider.getRunId()).orElseThrow();

        eventPublisher.publishEvent(
                new NoShowEscalatedEvent(run.getAcademyId(), caseId, run.getId(), rider.getStudentId(), now));
        return true;
    }
}
