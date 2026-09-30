package src.backend.run.command;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.run.dto.TransferCapacityDetail;
import src.backend.run.dto.TransferRequest;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunTransfer;
import src.backend.run.repository.RunTransferRepository;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.roster.ProjectedRosterReader;
import src.backend.student.command.StopMatcher;
import src.backend.student.entity.Student;
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 검증을 마친 버스 간 이동을 저장한다(RTE-07, API_SPEC §5.8) — 이 클래스가 트랜잭션 경계이고 외부
 * 지오코딩 호출은 없다({@code TransferCommandService} 가 트랜잭션 밖에서 끝낸 뒤 결과만 넘어온다,
 * {@link ForcedAdditionStore} 와 같은 근거, §7 규칙 16).
 *
 * <p>①구간에서 두 회차 어느 쪽에도 재최적화·확정 배치를 부르지 않는다(Ruling 198) — 저장만 하고
 * 끝난다. 나중에 도래하는 각 회차의 확정 배치({@link RunConfirmationService#confirmOne})가 이
 * 표를 읽어 출발 쪽은 제외, 도착 쪽은 추가로 반영한다.
 */
@Component
@RequiredArgsConstructor
public class TransferStore {

    private final StopMatcher stopMatcher;

    private final RunTransferRepository runTransferRepository;

    private final StagingRunGuard stagingRunGuard;

    private final BusRepository busRepository;

    private final ProjectedRosterReader rosterReader;

    /**
     * 두 회차를 id 오름차순으로 잠근 뒤 정차지를 확정하고 이동을 {@code staged} 로 저장한다.
     *
     * <p>CODE_CONVENTIONS §20.2 — 파라미터 7개를 넘긴 채 둔다. 호출부가 하나({@link TransferCommandService#transfer})
     * 뿐이고 일곱 값이 전부 이 저장에 필요한 서로 다른 도메인 값이라, record 로 묶어도 그 record 를
     * 만드는 자리가 호출부로 그대로 옮겨갈 뿐 파라미터 수는 줄지 않는다 — 유일한 인접 동타입 쌍
     * ({@code fromRun}·{@code toRun})은 이름으로 구분된다.
     *
     * @param point {@code address} 경로일 때만 채워진다 — {@code stop_id} 경로면 {@code null} 이고
     *              {@link TransferRequest#stopId()} 를 그대로 쓴다(배타 조건은 호출부가 이미 확인함)
     */
    @Transactional
    public RunTransfer stage(Run fromRun, Run toRun, Student student, TransferRequest request, GeocodedPoint point,
            Long requestedByAccountId, OffsetDateTime now) {
        // 잠금 순서를 id 로 고정해 두 이동이 서로의 회차를 반대 순서로 잠그는 교착을 막는다.
        boolean fromFirst = fromRun.getId() < toRun.getId();
        stagingRunGuard.lockIdle(fromFirst ? fromRun : toRun);
        stagingRunGuard.lockIdle(fromFirst ? toRun : fromRun);
        assertRoomFor(toRun, student.getId());
        Long stopId = point != null ? stopMatcher.matchOrCreate(toRun.getAcademyId(), point).getId()
                : request.stopId();
        RunTransfer transfer = RunTransfer.stage(student.getId(), fromRun.getId(), toRun.getId(), stopId,
                request.note(), requestedByAccountId, now);
        return runTransferRepository.save(transfer);
    }

    /**
     * 두 회차를 잠근 <b>뒤</b> 도착 회차의 소속(BR-271)·정원(BR-206)을 다시 본다 — {@link TransferCommandService} 의
     * 선검사는 잠금 밖이라 자리 1개 남은 회차로 요청 둘이 동시에 오거나, 그 사이 학생이 도착 회차에 강제 추가되면 통과한다.
     */
    private void assertRoomFor(Run toRun, Long studentId) {
        Bus toBus = busRepository.findByIdAndAcademyId(toRun.getBusId(), toRun.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        ProjectedRoster toRoster = rosterReader.read(toRun);
        if (toRoster.contains(studentId)) {
            throw new BusinessException(ErrorCode.STUDENT_ALREADY_IN_RUN);
        }
        long current = toRoster.size();
        if (current + 1 > toBus.getStudentCapacity()) {
            throw new BusinessException(ErrorCode.CAPACITY_EXCEEDED,
                    new TransferCapacityDetail(current, toBus.getStudentCapacity()));
        }
    }
}
