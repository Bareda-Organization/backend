package src.backend.boarding.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.ActorType;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RiderStatusHistory;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RiderStatusHistoryRepository;
import src.backend.boarding.repository.RunRiderRepository;

/**
 * 하원 회차 시작 시 명단 전원 일괄 승차(C-07 · BRD-03, 목표 11) — 회차 시작 커맨드(T2 소유)가
 * 이 메서드를 직접 부른다. 이 클래스는 T3(승하차 처리) 소유이고 호출자는 T2 소유라 두 태스크의
 * 경계가 이 메서드 시그니처 하나다.
 *
 * <p>{@code runId} 는 호출부(T2)가 이미 {@code RunRepository.findByIdAndAcademyId} 로 학원 범위를
 * 확인한 값이라는 전제다 — {@link RunRiderRepository#findAllByRunId} 의 자바독과 같은 근거.
 */
@Service
@RequiredArgsConstructor
public class AutoBoardingService {

    private final RunRiderRepository runRiderRepository;

    private final RiderStatusHistoryRepository riderStatusHistoryRepository;

    /**
     * 대기 중인(아직 부재·미승차로 처리되지 않은) 탑승자만 일괄 승차 처리한다 — 이미 부재·미승차로
     * 빠진 탑승자는 회차 시작 시점에도 여전히 빠진 채로 둔다(그 판단을 이 시점에 되돌릴 근거가 없다).
     *
     * @return 실제로 승차 처리된 인원 수(목표 11, 호출부의 응답 계산값 재료)
     */
    @Transactional
    public int boardAllForDropOff(Long runId, OffsetDateTime now) {
        List<RunRider> riders = runRiderRepository.findAllByRunId(runId);
        int boardedCount = 0;
        for (RunRider rider : riders) {
            if (rider.getStatus() != RiderStatus.WAITING) {
                continue;
            }
            RiderStatus fromStatus = rider.getStatus();
            rider.autoBoard(now);
            riderStatusHistoryRepository.save(RiderStatusHistory.of(new RiderStatusHistory.Context(rider.getId(),
                    fromStatus, RiderStatus.BOARDED, false, null, null, null, null, ActorType.SYSTEM, now, null)));
            boardedCount++;
        }
        return boardedCount;
    }
}
