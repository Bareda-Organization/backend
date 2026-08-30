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
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.StudentRepository;

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

    private final StudentRepository studentRepository;

    private final StopMatcher stopMatcher;

    private final RunForcedAdditionRepository runForcedAdditionRepository;

    /**
     * @param existingStudent {@code student_id} 로 지정된 기존 학생, 또는 {@code new_student} 직접
     *                        입력이면 {@code null}(이 메서드가 새로 만든다)
     */
    @Transactional
    public RunForcedAddition stage(Run run, Long addedBy, Student existingStudent, ForcedAdditionRequest request,
            GeocodedPoint point, OffsetDateTime now) {
        Student student = existingStudent != null ? existingStudent
                : studentRepository.save(newStudent(run.getAcademyId(), request));
        Long stopId = stopMatcher.matchOrCreate(run.getAcademyId(), point).getId();
        RunForcedAddition forcedAddition = RunForcedAddition.forRun(run.getId(), student.getId(), stopId, addedBy,
                now);
        return runForcedAdditionRepository.save(forcedAddition);
    }

    /**
     * §5.7 의 신규 학생 직접 입력은 이름만 받는다(RTE-06) — 전체 등록(STU-01)의 사진·학년·반 등은
     * 이 경로의 범위 밖이라 나머지 {@link StudentProfile} 필드는 비워 둔다.
     */
    private static Student newStudent(Long academyId, ForcedAdditionRequest request) {
        StudentProfile profile = new StudentProfile(request.newStudent().name(), null, null, null, null, null, null,
                null, null, null);
        return Student.register(academyId, profile);
    }
}
