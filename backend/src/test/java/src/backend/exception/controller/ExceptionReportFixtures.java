package src.backend.exception.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.entity.ExceptionReport;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.exception.repository.ExceptionReportRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 현장 예외 보고(T3, Phase 11 goal 15·16) 시험이 쓰는 실제 행 — {@code DriverRunFixtures} 와 같은
 * 이유로 정상 경로의 팩토리로 쌓는다(그 클래스는 T2 소유라 직접 재사용하지 않고 필요한 부분집합만
 * 따로 둔다).
 */
public class ExceptionReportFixtures {

    private static final String ACADEMY_NAME = "예외보고시험학원";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final BusRepository busRepository;

    private final StopRepository stopRepository;

    private final StudentRepository studentRepository;

    private final AccountRepository accountRepository;

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final ExceptionReportRepository exceptionReportRepository;

    public ExceptionReportFixtures(AcademyRepository academyRepository, BusRepository busRepository,
            StopRepository stopRepository, StudentRepository studentRepository, AccountRepository accountRepository,
            ManagerRepository managerRepository, AssignmentRepository assignmentRepository,
            RunRepository runRepository, RunRiderRepository runRiderRepository,
            AcademyStaffRepository academyStaffRepository, ExceptionReportRepository exceptionReportRepository) {
        this.academyRepository = academyRepository;
        this.busRepository = busRepository;
        this.stopRepository = stopRepository;
        this.studentRepository = studentRepository;
        this.accountRepository = accountRepository;
        this.managerRepository = managerRepository;
        this.assignmentRepository = assignmentRepository;
        this.runRepository = runRepository;
        this.runRiderRepository = runRiderRepository;
        this.academyStaffRepository = academyStaffRepository;
        this.exceptionReportRepository = exceptionReportRepository;
    }

    public long academy() {
        String code = "P11T3" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, ACADEMY_NAME, "서울", null, null)).getId();
    }

    public long bus(long academyId) {
        String busNo = "예외보고" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "22나" + SEQUENCE.incrementAndGet(),
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

    /** confirmed 상태 회차 1건 — {@code DriverRunFixtures#confirmedRun} 과 같은 형태. */
    public long confirmedRun(long academyId, long busId, Direction direction, OffsetDateTime departTime,
            OffsetDateTime confirmedAt) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), direction, departTime,
                departTime.minusMinutes(30), "출발지", "도착지", null);
        long runId = runRepository.save(run).getId();
        runRepository.confirmIfIdle(runId, confirmedAt);
        return runId;
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

    /** 같은 학원 소속이지만 어떤 회차에도 배치되지 않은 기사·동승자(FORBIDDEN 분기용). 반환값은 계정 id. */
    public long unassignedManager(long academyId, ManagerRole role, String name) {
        Manager manager = managerRepository
                .save(Manager.register(academyId, new ManagerProfile(name, "010-0000-0000", role, null)));
        Role accountRole = role == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT;
        Account account = accountRepository.save(Account.forSignup(academyId, name + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, accountRole));
        manager.linkAccount(account.getId());
        managerRepository.save(manager);
        return account.getId();
    }

    /** 학원 관계자 계정 1명(§5.20 조회 주체) — 반환값은 로그인 토큰에 실을 계정 id. */
    public long staffAccount(long academyId, String name) {
        Account account = accountRepository.save(Account.forSignup(academyId, "직원" + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    /** 그 회차의 라이더 1건(보호자 부재 보고의 {@code rider_id} 대상) — 반환값은 {@code run_rider.id}. */
    public long rider(long runId, long studentId, long stopId) {
        RunRider rider = RunRider.uponConfirmation(runId, studentId, stopId);
        return runRiderRepository.save(rider).getId();
    }

    /**
     * 예외 보고 1건을 직접 심는다(goal 16 필터 시험의 매칭·비매칭 행 조성용) — {@code type} 이
     * {@code GUARDIAN_ABSENT} 면 {@code runRiderId} 를 반드시 넘겨야 한다({@code CommandService} 를
     * 거치지 않고 저장소에 직접 넣으므로 DB CHECK 만 그 조건을 지킨다).
     */
    public long exceptionReport(long academyId, long runId, ExceptionReportType type, String memo,
            long reportedBy, OffsetDateTime reportedAt, Long runRiderId) {
        ExceptionReport report = ExceptionReport.forReport(academyId, runId, type, memo, reportedBy, reportedAt);
        if (runRiderId != null) {
            report.assignRunRider(runRiderId);
        }
        return exceptionReportRepository.save(report).getId();
    }
}
