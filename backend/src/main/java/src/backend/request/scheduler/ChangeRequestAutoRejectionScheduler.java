package src.backend.request.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.request.command.ChangeRequestAutoRejectionPersistence;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.repository.ChangeRequestRepository;

/**
 * ②구간 자동 거절 폴링의 진입점(API_SPEC §1.6) — 마감({@code deadline_at})이 지난 대기 요청을 모아
 * {@link ChangeRequestAutoRejectionPersistence#autoRejectOne} 을 건별로 부른다.
 *
 * <p>{@code RunConfirmationScheduler} 와 같은 골격(배치 상한 · 회차별 격리 · 초기 지연 설정)을 따르되
 * <b>전용 스레드 풀을 두지 않는다</b> — 그쪽은 외부 지도 API 호출(느린 I/O)을 여러 건 동시에 흘려보낼
 * 이유가 있었지만, 이쪽은 DB 조건부 UPDATE 하나뿐이라 순차 처리로도 한 틱(30초)을 넘길 근거가 없다.
 *
 * <p><b>한 건의 실패가 다른 건을 막지 않는다</b>(목표 5) — 건마다 개별 {@code try-catch} 로 감싼다.
 * 실패한 건은 {@code pending} 으로 남아(그 건의 트랜잭션만 롤백) 다음 틱에 다시 대상이 된다 —
 * {@code Run} 과 달리 이 엔티티에는 실패 횟수 카운터가 없어 별도로 기록할 것도 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeRequestAutoRejectionScheduler {

    /**
     * 한 틱이 한 번에 집는 상한(목표 6) — 상한 없이 전건을 집으면 마감이 몰린 틱 하나가 커넥션을
     * 오래 붙든다({@code ChangeRequestRepository.findByStatusAndDeadlineAtLessThanEqual} 의
     * {@code pageable} 이 이 값을 받는다).
     */
    static final int BATCH_SIZE = 50;

    private final ChangeRequestRepository changeRequestRepository;

    private final ChangeRequestAutoRejectionPersistence persistence;

    private final Clock clock;

    /**
     * 마감이 지난 대기 요청을 자동 거절한다.
     *
     * <p>폴링 주기를 설정으로 받는 이유는 <b>테스트에서 배경 실행을 미루기 위함</b>이다 —
     * {@code RunConfirmationScheduler} 와 같은 형태({@code build.gradle} 이
     * {@code app.request.autoreject.initial-delay-ms} 를 하루로 늦춘다).
     *
     * <p>한 틱 안에서 모든 건을 순차 처리한 뒤 반환한다 — 그래야 이 메서드를 직접 호출하는 테스트가
     * 완료를 동기적으로 관측할 수 있다.
     */
    @Scheduled(fixedDelayString = "${app.request.autoreject.poll-interval-ms:30000}",
            initialDelayString = "${app.request.autoreject.initial-delay-ms:0}")
    public void rejectDueChangeRequests() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ChangeRequest> due = changeRequestRepository.findByStatusAndDeadlineAtLessThanEqual(
                ChangeRequestStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE));

        for (ChangeRequest request : due) {
            autoRejectSafely(request.getId(), now);
        }
    }

    /**
     * 요청 1건을 자동 거절하고, 무엇이 됐든 실패를 삼켜 다음 틱으로 넘긴다(목표 5).
     *
     * <p>{@code BusinessException} 처럼 예상한 실패로 좁히지 않고 {@link Exception} 전체를 잡는다 —
     * 좁히면 예상 못 한 버그가 이 루프를 끊어 <b>이 틱의 나머지 건 처리 여부와 무관하게 배치 자체가
     * 실패로 끝난다</b>({@code RunConfirmationScheduler.confirmSafely} 와 같은 근거).
     */
    private void autoRejectSafely(Long changeRequestId, OffsetDateTime now) {
        try {
            persistence.autoRejectOne(changeRequestId, now);
        } catch (Exception e) {
            log.warn("변경 요청 {} 자동 거절 실패 — 다음 틱에 재시도한다", changeRequestId, e);
        }
    }
}
