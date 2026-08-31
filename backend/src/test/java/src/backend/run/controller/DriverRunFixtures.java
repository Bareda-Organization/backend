package src.backend.run.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.util.ReflectionTestUtils;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.GuardianStudent;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 기사·동승자 운행 조작(T2, Phase 9 goal 1·2·3·5·9·10) 시험이 쓰는 실제 행 — {@code RunConfirmationFixtures}
 * ·{@code ChangeRequestAutoRejectFixtures} 와 같은 이유로 정상 경로의 팩토리로 쌓는다.
 *
 * <p>{@link ChangeRequest#deadlineAt} 을 저장 전 직접 채우는 이유는 {@code ChangeRequestAutoRejectFixtures}
 * 자바독과 같다 — 그 컬럼을 채우는 생성 경로가 이 저장소에 아직 없다.
 */
public class DriverRunFixtures {

    /** 뒷정리 표시 — 이 헬퍼가 만드는 학원의 고정 이름. */
    public static final String ACADEMY_NAME = "운행조작시험학원";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final BusRepository busRepository;

    private final StopRepository stopRepository;

    private final StudentRepository studentRepository;

    private final AccountRepository accountRepository;

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final GuardianRepository guardianRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final ChangeRequestRepository changeRequestRepository;

    public DriverRunFixtures(AcademyRepository academyRepository, BusRepository busRepository,
            StopRepository stopRepository, StudentRepository studentRepository, AccountRepository accountRepository,
            ManagerRepository managerRepository, AssignmentRepository assignmentRepository,
            RunRepository runRepository, ConfirmedRouteRepository confirmedRouteRepository,
            RouteVersionRepository routeVersionRepository, RunStopRepository runStopRepository,
            RunRiderRepository runRiderRepository,
            AcademyStaffRepository academyStaffRepository, GuardianRepository guardianRepository,
            GuardianStudentRepository guardianStudentRepository, ChangeRequestRepository changeRequestRepository) {
        this.academyRepository = academyRepository;
        this.busRepository = busRepository;
        this.stopRepository = stopRepository;
        this.studentRepository = studentRepository;
        this.accountRepository = accountRepository;
        this.managerRepository = managerRepository;
        this.assignmentRepository = assignmentRepository;
        this.runRepository = runRepository;
        this.confirmedRouteRepository = confirmedRouteRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.runStopRepository = runStopRepository;
        this.runRiderRepository = runRiderRepository;
        this.academyStaffRepository = academyStaffRepository;
        this.guardianRepository = guardianRepository;
        this.guardianStudentRepository = guardianStudentRepository;
        this.changeRequestRepository = changeRequestRepository;
    }

    public long academy() {
        String code = "P9T2" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, ACADEMY_NAME, "서울", null, null)).getId();
    }

    public long bus(long academyId) {
        String busNo = "운행조작" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "22가" + SEQUENCE.incrementAndGet(),
                BusSeating.withDefaultCrew(16))).getId();
    }

    public long stop(long academyId, String lat, String lng) {
        Stop stop = Stop.forVerifiedAddress(academyId, "정차지" + SEQUENCE.incrementAndGet(), "서울시 어딘가 " + lat,
                new BigDecimal(lat), new BigDecimal(lng));
        return stopRepository.save(stop).getId();
    }

    public long student(long academyId, String name) {
        StudentProfile profile = new StudentProfile(name, null, null, null, null, null, null, null, null, null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
    }

    /** 계정이 연결된 학생(goal 2·9의 학생 알림 수신자 조회 대상, {@code STUDENT_NOT_FOUND} 조회 부재). */
    public long studentWithAccount(long academyId, String name) {
        long studentId = student(academyId, name);
        Account account = accountRepository.save(Account.forSignup(academyId, "학생" + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, Role.STUDENT));
        Student student = studentRepository.findById(studentId).orElseThrow();
        student.linkAccount(account.getId());
        studentRepository.save(student);
        return studentId;
    }

    /** 재직 관계자 1명(goal 2의 STAFF 알림 수신자 조회 대상) — 반환값은 계정 id. */
    public long staffAccount(long academyId, String name) {
        Account account = accountRepository.save(Account.forSignup(academyId, "직원" + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    /** 그 학생의 보호자 1명(goal 2·9의 PARENT 알림 수신자 조회 대상) — 반환값은 계정 id. */
    public long guardianOf(long academyId, long studentId, String name, OffsetDateTime linkedAt) {
        Account account = accountRepository.save(Account.forSignup(academyId, "부모" + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, Role.PARENT));
        Guardian guardian = guardianRepository.save(Guardian.forSignup(academyId, account.getId(), name,
                "010-0000-0000"));
        guardianStudentRepository.save(GuardianStudent.uponLink(guardian.getId(), studentId, linkedAt));
        return account.getId();
    }

    /** confirmed 상태 회차 1건 — {@code RunRepository#confirmIfIdle} 로 정상 경로(조건부 UPDATE)를 그대로 탄다. */
    public long confirmedRun(long academyId, long busId, Direction direction, OffsetDateTime departTime,
            OffsetDateTime confirmedAt) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), direction, departTime,
                departTime.minusMinutes(30), "출발지", "도착지", null);
        long runId = runRepository.save(run).getId();
        runRepository.confirmIfIdle(runId, confirmedAt);
        return runId;
    }

    /** {@code moving} 상태 회차 — 도착 처리(§4.5)만 단독으로 시험할 때 시작 처리를 거치지 않고 직접 전이한다. */
    public void startRun(long runId, OffsetDateTime startedAt) {
        Run run = runRepository.findById(runId).orElseThrow();
        run.start(startedAt);
        runRepository.save(run);
    }

    /** 기사·동승자를 등록하고 계정을 연결한 뒤 그 회차에 배치한다 — 반환값은 로그인 토큰에 실을 계정 id. */
    public long assignedManager(long academyId, long runId, ManagerRole role, String name, OffsetDateTime assignedAt) {
        Manager manager = managerRepository
                .save(Manager.register(academyId, new ManagerProfile(name, "010-0000-0000", role, null)));
        Role accountRole = role == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT;
        Account account = accountRepository.save(Account.forSignup(academyId, name + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, accountRole));
        manager.linkAccount(account.getId());
        managerRepository.save(manager);
        assignmentRepository.save(Assignment.uponAssignment(runId, manager.getId(), role, assignedAt, null));
        return account.getId();
    }

    /** 확정 노선 1버전 — {@code ChangeRequestAutoRejectFixtures#confirmedRouteWithVersion} 과 같은 형태. 반환값은 {@code route_version.id}. */
    public long confirmedRouteWithVersion(long runId, OffsetDateTime publishedAt) {
        confirmedRouteRepository.save(ConfirmedRoute.forRun(runId, publishedAt));
        RouteVersion version = routeVersionRepository.save(RouteVersion.forConfirmedRoute(runId, 1,
                RouteVersionSource.CONFIRM_BATCH, 30, new BigDecimal("10.00"), publishedAt, "fp-" + runId,
                "engine-v1", Map.of(), false, null, publishedAt));
        confirmedRouteRepository.assignCurrentVersion(runId, version.getId());
        return version.getId();
    }

    /** 그 배포 버전의 정차 항목 1건(학생 승하차지) — 넘긴 {@code seq} 순번을 그대로 매긴다. */
    public long runStopForStop(long routeVersionId, long stopId, int seq, OffsetDateTime eta) {
        return runStopRepository.save(RunStop.forStop(routeVersionId, stopId, seq, eta)).getId();
    }

    /** 그 회차의 라이더 1건 — {@code status} 가 {@code BOARDED} 면 {@code timestamp} 로 탑승 처리까지 마친다. */
    public long rider(long runId, long studentId, long stopId, RiderStatus status, OffsetDateTime timestamp) {
        RunRider rider = RunRider.uponConfirmation(runId, studentId, stopId);
        if (status == RiderStatus.BOARDED) {
            rider.board(timestamp);
        }
        return runRiderRepository.save(rider).getId();
    }

    /** 대기 중인 변경 요청(goal 3) — {@code deadlineAt} 은 공개 API 가 없어 직접 채운다(클래스 자바독 참고). */
    public long pendingChangeRequest(long academyId, long runId, long studentId, long requestedBy,
            OffsetDateTime requestedAt, OffsetDateTime deadlineAt) {
        ChangeRequest request = ChangeRequest.forRequest(academyId, runId, studentId,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, requestedBy, requestedAt);
        ReflectionTestUtils.setField(request, "deadlineAt", deadlineAt);
        return changeRequestRepository.save(request).getId();
    }
}
