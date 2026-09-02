package src.backend.exception.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.exception.command.NoShowEscalationPersistence;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;

/**
 * 미승차 에스컬레이션 폴링의 진입점(목표 1, API_SPEC §4.8) — 대기 만료 + 무응답 케이스를 모아
 * {@link NoShowEscalationPersistence#escalateOne} 을 건별로 부른다. {@code ChangeRequestAutoRejectionScheduler}
 * 와 같은 골격(배치 상한 · 건별 격리 · 초기 지연 설정 · 전용 스레드 풀 없음)이다.
 *
 * <p>{@code @SchedulerLock}(TECH_DECISIONS §3.2, Phase 11 T4 배선 명세) — {@link
 * NoShowEscalationPersistence#escalateOne} 의 조건부 UPDATE({@code escalateIfDue})가 이중
 * 에스컬레이션은 이미 막지만, 락이 없으면 인스턴스마다 같은 대상을 반복 조회한다 —
 * {@code ChangeRequestAutoRejectionScheduler} 와 완전히 같은 근거다. {@code RunConfirmationScheduler}
 * 만 예외인 이유는 {@code ShedLockConfig} 자바독에 별도로 있다. {@code lockAtMostFor} 는 같은 30초
 * 폴링을 쓰는 {@code NotificationOutboxWorker}·{@code ChangeRequestAutoRejectionScheduler} 의
 * 선례(폴링 주기의 4배)를 그대로 따라 {@code PT2M} 으로 잡았다 — 죽은 인스턴스가 있어도 2분 안에는
 * 다음 인스턴스가 이어받는다.
 *
 * <p><b>한 건의 실패가 다른 건을 막지 않는다</b> — 건마다 개별 {@code try-catch} 로 감싼다. 실패한
 * 건은 {@code escalated_at} 이 그대로 {@code NULL} 이라(그 건의 트랜잭션만 롤백) 다음 틱에 다시
 * 대상이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoShowEscalationScheduler {

    /**
     * 한 틱이 한 번에 집는 상한 — 상한 없이 전건을 집으면 만료가 몰린 틱 하나가 커넥션을 오래
     * 붙든다({@code NoShowCaseRepository.findDueForEscalation} 의 {@code pageable} 이 이 값을 받는다).
     */
    static final int BATCH_SIZE = 50;

    private final NoShowCaseRepository noShowCaseRepository;

    private final NoShowEscalationPersistence persistence;

    private final Clock clock;

    /**
     * 대기 만료 + 무응답 케이스를 에스컬레이션한다.
     *
     * <p>폴링 주기를 설정으로 받는 이유는 <b>테스트에서 배경 실행을 미루기 위함</b>이다 —
     * {@code ChangeRequestAutoRejectionScheduler} 와 같은 형태.
     *
     * <p>한 틱 안에서 모든 건을 순차 처리한 뒤 반환한다 — 그래야 이 메서드를 직접 호출하는 테스트가
     * 완료를 동기적으로 관측할 수 있다.
     */
    @Scheduled(fixedDelayString = "${app.exception.noshow-escalation.poll-interval-ms:30000}",
            initialDelayString = "${app.exception.noshow-escalation.initial-delay-ms:0}")
    @SchedulerLock(name = "noshow-escalation", lockAtMostFor = "PT2M")
    public void escalateDueNoShowCases() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<NoShowCase> due = noShowCaseRepository.findDueForEscalation(now, PageRequest.of(0, BATCH_SIZE));

        for (NoShowCase noShowCase : due) {
            escalateSafely(noShowCase.getId(), now);
        }
    }

    /**
     * 케이스 1건을 에스컬레이션하고, 무엇이 됐든 실패를 삼켜 다음 틱으로 넘긴다.
     *
     * <p>{@code BusinessException} 처럼 예상한 실패로 좁히지 않고 {@link Exception} 전체를 잡는다 —
     * 좁히면 예상 못 한 버그가 이 루프를 끊어 이 틱의 나머지 건 처리 여부와 무관하게 배치 자체가
     * 실패로 끝난다({@code ChangeRequestAutoRejectionScheduler.autoRejectSafely} 와 같은 근거).
     */
    private void escalateSafely(Long caseId, OffsetDateTime now) {
        try {
            persistence.escalateOne(caseId, now);
        } catch (Exception e) {
            log.warn("미승차 케이스 {} 에스컬레이션 실패 — 다음 틱에 재시도한다", caseId, e);
        }
    }
}
