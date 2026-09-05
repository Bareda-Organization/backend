package src.backend.run.command;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.run.dto.TransferRequest;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunTransfer;
import src.backend.run.repository.RunTransferRepository;
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

    /**
     * @param point {@code address} 경로일 때만 채워진다 — {@code stop_id} 경로면 {@code null} 이고
     *              {@link TransferRequest#stopId()} 를 그대로 쓴다(배타 조건은 호출부가 이미 확인함)
     */
    @Transactional
    public RunTransfer stage(Run fromRun, Run toRun, Student student, TransferRequest request, GeocodedPoint point,
            Long requestedByAccountId, OffsetDateTime now) {
        Long stopId = point != null ? stopMatcher.matchOrCreate(toRun.getAcademyId(), point).getId()
                : request.stopId();
        RunTransfer transfer = RunTransfer.stage(student.getId(), fromRun.getId(), toRun.getId(), stopId,
                request.note(), requestedByAccountId, now);
        return runTransferRepository.save(transfer);
    }
}
