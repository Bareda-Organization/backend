package src.backend.request.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.repository.ChangeRequestRepository;

/**
 * 회차가 {@code moving} 으로 전이하는 순간 그 회차의 미처리 변경 요청을 전부 종결하는 서비스
 * (API_SPEC §1.6 "출발 시각 도달 또는 {@code moving} 전이 중 먼저 오는 시점").
 *
 * <p>{@code POST /runs/{runId}/start}({@code RunStartCommandService}, Phase 9 소유, Ruling 196)가
 * 회차를 {@code moving} 으로 바꾸는 <b>같은 트랜잭션 안에서</b> {@link #terminateForRun} 을 동기
 * 호출한다 — 아래 "왜 폴링만으로는 부족한가"가 요구하는 배선이 그것이다.
 *
 * <p><b>왜 폴링만으로는 부족한가</b> — {@code ChangeRequestAutoRejectionScheduler} 는 최대 30초
 * (poll-interval) 주기로만 도래분을 훑는다. 회차가 {@code moving} 으로 바뀐 그 순간과 다음 폴링
 * 틱 사이에는 <b>최대 30초의 창</b>이 남고, 그 창 안에서는 이미 출발한 회차에 대한 승인 조작(관리자의
 * 뒤늦은 승인·거절)이 여전히 {@code pending} 인 변경 요청을 실제로 통과시킬 수 있다. 이 서비스가
 * {@code moving} 전이와 <b>동기</b>로 붙어야 그 창이 완전히 닫힌다 — 배선을 잊으면 이 위험은 코드
 * 어디에도 드러나지 않고 조용히 남는다.
 *
 * <p>처리 로직은 폴링과 동일한 {@link ChangeRequestAutoRejectionPersistence#autoRejectOne} 을
 * 그대로 재사용한다 — 조건부 UPDATE 가 유일한 멱등 장치이므로, 폴링이 이 서비스보다 먼저 같은 건을
 * 집었더라도(먼저 오는 시점이 출발 시각 쪽이었던 경우) 이 서비스의 호출은 0행 갱신으로 조용히
 * 끝난다(목표 3·목표 4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeRequestAutoRejectionService {

    private final ChangeRequestRepository changeRequestRepository;

    private final ChangeRequestAutoRejectionPersistence persistence;

    /**
     * 그 회차의 미처리 변경 요청을 전부 자동 거절로 종결한다.
     *
     * <p><b>한 건의 실패가 다른 건을 막지 않는다</b> — {@code ChangeRequestAutoRejectionScheduler} 와
     * 같은 근거로 건마다 개별 {@code try-catch} 로 감싼다. 이 서비스가 Phase 9 에 의해 회차 시작
     * 트랜잭션 안에서 불릴 것을 감안하면, 한 건의 실패로 전체를 던져 회차 시작 자체를 막는 것은 이
     * 서비스의 책임 밖이다 — 실패한 건은 {@code pending} 으로 남아 다음 폴링 틱이 다시 집는다.
     *
     * @param academyId 그 회차가 속한 학원 — 호출부가 이미 확인한 값을 조회에도 실어 좁힌다
     * @param runId     {@code moving} 으로 전이한 회차
     * @param decidedAt 이 종결의 판정 시각 — 호출부(회차 시작 처리)가 쓰는 시각을 그대로 받는다.
     *                  여기서 새로 시각을 만들지 않는 것이 두 시계를 섞지 않는 원칙(ARCHITECTURE §9.2)이다
     */
    public void terminateForRun(Long academyId, Long runId, OffsetDateTime decidedAt) {
        List<ChangeRequest> pending = changeRequestRepository.findAllByAcademyIdAndRunIdAndStatus(academyId, runId,
                ChangeRequestStatus.PENDING);

        for (ChangeRequest request : pending) {
            autoRejectSafely(request.getId(), decidedAt);
        }
    }

    private void autoRejectSafely(Long changeRequestId, OffsetDateTime decidedAt) {
        try {
            persistence.autoRejectOne(changeRequestId, decidedAt);
        } catch (Exception e) {
            log.warn("변경 요청 {} 의 moving 종결 자동 거절 실패 — 다음 폴링 틱에 재시도한다", changeRequestId, e);
        }
    }
}
