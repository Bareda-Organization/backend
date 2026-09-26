package src.backend.run.command;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.run.dto.ForcedAdditionRequest;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.student.command.StopMatcher;
import src.backend.student.command.StudentCommandService;
import src.backend.student.entity.Student;
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 검증을 마친 강제 추가를 저장한다(RTE-06, API_SPEC §5.7) — 이 클래스가 트랜잭션 경계이고 외부
 * 지오코딩 호출은 없다({@code ForcedAdditionCommandService} 가 트랜잭션 밖에서 끝낸 뒤 결과만
 * 넘어온다, §7 규칙 16).
 *
 * <p>①구간에서 재최적화·확정 배치를 부르지 않는다(Ruling 198) — 이 시점엔 회차가 아직 idle 이라
 * 확정된 노선이 부재하다. 나중에 도래하는 확정 배치({@link RunConfirmationService#confirmOne})가
 * 이 표를 읽어 그날 명단에 합친다.
 */
@Component
@RequiredArgsConstructor
public class ForcedAdditionStore {

    /** 신규 학생 직접 입력 등록 진입점(BR-095) — {@code student} 소유 테이블에 직접 쓰지 않는다. */
    private final StudentCommandService studentCommandService;

    private final StopMatcher stopMatcher;

    private final RunForcedAdditionRepository runForcedAdditionRepository;

    private final StagingRunGuard stagingRunGuard;

    /**
     * 회차를 잠근 뒤 학생(신규면 생성)·정차지를 확정하고 강제 추가를 {@code staged} 로 저장한다.
     *
     * <p>§20.2 — 파라미터 6개를 넘긴 채 둔다. 호출부가 하나({@link ForcedAdditionCommandService#add})
     * 뿐이고 여섯 값이 전부 이 저장에 필요한 서로 다른 도메인 값이라 record 로 묶어도 파라미터 수는
     * 줄지 않는다.
     *
     * @param existingStudent {@code student_id} 로 지정된 기존 학생, 또는 {@code new_student} 직접
     *                        입력이면 {@code null}(이 메서드가 새로 만든다)
     */
    @Transactional
    public RunForcedAddition stage(Run run, Long addedBy, Student existingStudent, ForcedAdditionRequest request,
            GeocodedPoint point, OffsetDateTime now) {
        stagingRunGuard.lockIdle(run);
        Long studentId = existingStudent != null ? existingStudent.getId()
                : studentCommandService.registerMinimal(run.getAcademyId(), request.newStudent().name());
        Long stopId = stopMatcher.matchOrCreate(run.getAcademyId(), point).getId();
        RunForcedAddition forcedAddition = RunForcedAddition.forRun(run.getId(), studentId, stopId, addedBy,
                now, request.note());
        return runForcedAdditionRepository.save(forcedAddition);
    }
}
