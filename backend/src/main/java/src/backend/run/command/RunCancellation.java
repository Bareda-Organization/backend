package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunCancelSource;
import src.backend.run.entity.RunStatus;
import src.backend.run.entity.RunTransfer;
import src.backend.run.entity.RunTransferStatus;
import src.backend.run.repository.RunTransferRepository;

/**
 * 회차 임시 취소의 유일한 지점(Ruling 372 ①) — 회차를 취소 표시하고 <b>그 회차로 들어오는 {@code staged} 이동</b>을
 * 지운다. 취소된 도착 회차는 확정되지 않아 이동이 영영 반영되지 않는데 출발 회차의 확정은 그 학생을 계속 빼므로,
 * 지우지 않으면 학생이 어느 버스에도 없다. 학생은 출발 회차 명단으로 돌아간다.
 *
 * <p>관계자 취소({@code RunCommandService#cancel})와 스케줄이 내일 회차를 취소하는 경로({@code ScheduleRunSync})가 모두
 * 여기를 지난다. {@code applied} 이동과 출발 회차가 취소된 이동은 지우지 않는다(후자는 §5.8.1 로 관계자가 지운다).
 * 취소를 푸는 것({@link Run#reinstate})은 이동을 되돌리지 않는다. 호출부 트랜잭션 안에서만 부른다.
 */
@Component
@RequiredArgsConstructor
public class RunCancellation {

    @PersistenceContext
    private EntityManager entityManager;

    private final RunTransferRepository runTransferRepository;

    private final RunTransferRemoval transferRemoval;

    private final Clock clock;

    /**
     * 회차를 잠그고 <b>다시 읽은 상태로 판정한 뒤</b> 취소 표시하고 들어오는 대기 이동을 지운다 — 잠금은 이동 등록
     * ({@code StagingRunGuard})이 같은 행을 잠그므로, 등록이 취소 커밋 뒤에 {@code RUN_CANCELED} 를 보게 하는 순서 보장이다.
     *
     * <p>호출부가 잠그기 전에 읽은 {@code run} 은 낡았을 수 있다 — 그 사이 확정·시작이 커밋됐는데 낡은 엔티티에 취소를
     * 얹으면 변경 감지가 행 전체를 UPDATE 해 그 전이를 되돌린다(BR-204). 그래서 잠금 조회가 아니라
     * {@code refresh(PESSIMISTIC_WRITE)} 로 잠그며 상태를 새로 읽는다(영속성 컨텍스트에 이미 있는 엔티티는 잠금 조회가
     * 상태를 다시 읽지 않는다).
     *
     * <p>이미 취소된 회차는 아무것도 바꾸지 않는다(멱등, Ruling 376 — 출처·시각 보존). 운행이 시작된 회차({@code moving}·
     * {@code finished})는 관계자에게 {@code 409 RUN_ALREADY_STARTED} 이고, 스케줄 경로는 시작 전(idle)이 아니면
     * 건너뛴다(이미 확정된 회차는 스케줄이 손대지 않는다, §5.10).
     */
    public void cancel(AuthUser requester, Run run, RunCancelSource source) {
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        if (run.isCanceled()) {
            return;
        }
        RunStatus status = run.getStatus();
        if (source == RunCancelSource.SCHEDULE && status != RunStatus.IDLE) {
            return;
        }
        if (status == RunStatus.MOVING || status == RunStatus.FINISHED) {
            throw new BusinessException(ErrorCode.RUN_ALREADY_STARTED);
        }
        run.cancel(OffsetDateTime.now(clock), source);
        runTransferRepository.findAllByToRunIdAndAcademyId(run.getId(), run.getAcademyId()).stream()
                .filter(transfer -> transfer.getStatus() == RunTransferStatus.STAGED)
                .map(RunTransfer::getId)
                .forEach(id -> transferRemoval.removeStaged(id, run.getAcademyId(), requester.accountId()));
    }
}
