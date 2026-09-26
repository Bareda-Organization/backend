package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.run.dto.ForcedAdditionRequest;
import src.backend.run.dto.ForcedAdditionResponse;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.run.roster.ProjectedRosterReader;
import src.backend.student.service.AddressVerification;
import src.backend.student.entity.Student;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.StudentRepository;

/**
 * 관계자의 ①구간 강제 추가(RTE-06, API_SPEC §5.7, Ruling 197).
 *
 * <p><b>{@code @Transactional} 이 없는 것이 이 클래스의 요점이다</b> — {@link AddressVerification}
 * 이 외부 지오코딩을 호출한다({@code ChangeRequestCommandService} 와 같은 근거, §7 규칙 16). "구간
 * 판정 → 정원 판정 → 주소 검증" 은 여기서 트랜잭션 밖에 두고, 학생 생성·정차지 매칭·저장만
 * {@link ForcedAdditionStore} 의 짧은 트랜잭션에 맡긴다.
 *
 * <p>§5.7 은 <b>① 구간 전용</b>이다 — ②구간부터는 관계자도 예외 없이 {@code 403} 이다(사양 본문).
 * 그래서 {@link ChangeRequestCommandService} 처럼 "CLOSED 만 막는다" 가 아니라 "IMMEDIATE 만 허용한다"
 * 로 판정한다.
 */
@Service
@RequiredArgsConstructor
public class ForcedAdditionCommandService {

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final StudentRepository studentRepository;

    private final ProjectedRosterReader rosterReader;

    private final AddressVerification addressVerification;

    private final ForcedAdditionStore forcedAdditionStore;

    private final Clock clock;

    /** ①구간 전용 강제 추가(§5.7) — 정원·주소를 확인한 뒤 대기 상태로만 등록한다. */
    public ForcedAdditionResponse add(AuthUser requester, Long runId, ForcedAdditionRequest request) {
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        ChangeWindow window = ChangeWindowPolicy.segmentOf(run, OffsetDateTime.now(clock));
        if (window != ChangeWindow.IMMEDIATE) {
            // ①구간 전용(§5.7) — ②③구간은 관계자도 예외 없이 막힌다.
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }

        Student existingStudent = resolveExistingStudent(requester.academyId(), request);

        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), run.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        assertCapacityAvailable(run, bus);

        GeocodedPoint point = addressVerification.verifySingle(request.address());

        return ForcedAdditionResponse.from(
                forcedAdditionStore.stage(run, requester.accountId(), existingStudent, request, point,
                        OffsetDateTime.now(clock)));
    }

    /**
     * {@code student_id}·{@code new_student.name} 은 배타 조건이다(§5.7) — 기존 학생이면 여기서
     * 조회까지 끝내고, 신규 학생이면 {@code null} 을 돌려줘 {@link ForcedAdditionStore} 가 트랜잭션
     * 안에서 생성하게 한다.
     */
    private Student resolveExistingStudent(Long academyId, ForcedAdditionRequest request) {
        boolean hasExisting = request.studentId() != null;
        boolean hasNew = request.newStudent() != null && request.newStudent().name() != null
                && !request.newStudent().name().isBlank();
        if (hasExisting == hasNew) {
            // 둘 다 없거나 둘 다 있으면 어느 쪽으로 처리할지 정할 수 없다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!hasExisting) {
            return null;
        }
        return studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(request.studentId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
    }

    /**
     * 정원 판정(BUS-04) — 강제 추가 시점의 {@code run_rider} 는 아직 없으므로(회차가 idle), 확정 배치가
     * 만들 예정 명단({@link ProjectedRosterReader} — 대기 중인 강제 추가·이동 포함, OFF 제외)에 이번 1건을
     * 더해 {@link Bus#getStudentCapacity()} 와 비교한다(BR-043).
     */
    private void assertCapacityAvailable(Run run, Bus bus) {
        if (rosterReader.read(run).size() + 1 > bus.getStudentCapacity()) {
            throw new BusinessException(ErrorCode.CAPACITY_EXCEEDED);
        }
    }
}
