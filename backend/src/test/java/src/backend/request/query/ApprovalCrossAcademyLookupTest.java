package src.backend.request.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * 승인 상세가 변경 요청이 가리키는 회차·학생을 읽을 때 요청의 학원 조건을 함께 건다 — 그 학원 안에 없는 회차·학생은 행이 존재해도 읽지 않고 404 로 끝난다.
 */
class ApprovalCrossAcademyLookupTest {

    private static final long ACADEMY_ID = 1L;

    private static final long REQUEST_ID = 5L;

    private static final long RUN_ID = 10L;

    private static final long STUDENT_ID = 20L;

    private final ChangeRequestRepository changeRequestRepository = mock(ChangeRequestRepository.class);

    private final RunRepository runRepository = mock(RunRepository.class);

    private final BusRepository busRepository = mock(BusRepository.class);

    private final StudentRepository studentRepository = mock(StudentRepository.class);

    private final RunRiderRepository runRiderRepository = mock(RunRiderRepository.class);

    private final ApprovalQueryService service = new ApprovalQueryService(changeRequestRepository, runRepository,
            busRepository, runRiderRepository,
            new ApprovalSummaryAssembler(busRepository, studentRepository, null, null), null, null, null, null);

    private final AuthUser staff = new AuthUser(99L, ACADEMY_ID, Role.STAFF, AccountStatus.ACTIVE);

    @BeforeEach
    void setUp() {
        ChangeRequest changeRequest = mock(ChangeRequest.class);
        given(changeRequest.getAcademyId()).willReturn(ACADEMY_ID);
        given(changeRequest.getRunId()).willReturn(RUN_ID);
        given(changeRequest.getStudentId()).willReturn(STUDENT_ID);
        given(changeRequestRepository.findByIdAndAcademyId(REQUEST_ID, ACADEMY_ID))
                .willReturn(Optional.of(changeRequest));
        given(runRiderRepository.findAllByRunIdAndAcademyId(any(), any())).willReturn(List.of());
    }

    @Test
    @DisplayName("요청의 학원 안에 없는 회차는 다른 학원에 그 id 의 행이 있어도 404 RUN_NOT_FOUND 다")
    void 요청_학원_밖의_회차는_회차_없음이다() {
        Run otherAcademyRun = mock(Run.class);
        given(runRepository.findById(RUN_ID)).willReturn(Optional.of(otherAcademyRun));
        given(runRepository.findByIdAndAcademyId(RUN_ID, ACADEMY_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(staff, REQUEST_ID)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
    }

    @Test
    @DisplayName("요청의 학원 안에 없는 학생은 다른 학원에 그 id 의 행이 있어도 404 STUDENT_NOT_FOUND 다")
    void 요청_학원_밖의_학생은_학생_없음이다() {
        Run run = mock(Run.class);
        given(run.getId()).willReturn(RUN_ID);
        given(run.getBusId()).willReturn(30L);
        given(run.getServiceDate()).willReturn(java.time.LocalDate.of(2030, 4, 1));
        given(runRepository.findById(RUN_ID)).willReturn(Optional.of(run));
        given(runRepository.findByIdAndAcademyId(RUN_ID, ACADEMY_ID)).willReturn(Optional.of(run));
        given(busRepository.findByIdAndAcademyId(30L, ACADEMY_ID)).willReturn(Optional.of(mock(Bus.class)));
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(mock(Student.class)));
        given(studentRepository.findAllByAcademyIdAndIdIn(ACADEMY_ID, List.of(STUDENT_ID))).willReturn(List.of());

        assertThatThrownBy(() -> service.detail(staff, REQUEST_ID)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STUDENT_NOT_FOUND));
    }
}
