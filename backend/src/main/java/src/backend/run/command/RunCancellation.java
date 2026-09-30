package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunCancelSource;
import src.backend.run.entity.RunTransfer;
import src.backend.run.entity.RunTransferStatus;
import src.backend.run.repository.RunRepository;
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

    private final RunRepository runRepository;

    private final RunTransferRepository runTransferRepository;

    private final RunTransferRemoval transferRemoval;

    private final Clock clock;

    /**
     * 회차를 잠근 채 취소 표시하고 들어오는 대기 이동을 지운다 — 잠금은 이동 등록({@code StagingRunGuard})이 같은 행을
     * 잠그므로, 등록이 취소 커밋 뒤에 {@code RUN_CANCELED} 를 보게 하는 순서 보장이다.
     */
    public void cancel(AuthUser requester, Run run, RunCancelSource source) {
        Run locked = runRepository.findLockedByIdAndAcademyId(run.getId(), run.getAcademyId()).orElse(run);
        locked.cancel(OffsetDateTime.now(clock), source);
        runTransferRepository.findAllByToRunIdAndAcademyId(locked.getId(), locked.getAcademyId()).stream()
                .filter(transfer -> transfer.getStatus() == RunTransferStatus.STAGED)
                .map(RunTransfer::getId)
                .forEach(id -> transferRemoval.removeStaged(id, locked.getAcademyId(), requester.accountId()));
    }
}
