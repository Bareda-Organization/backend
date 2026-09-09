package src.backend.monitoring.controller;

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

import java.util.Map;

/**
 * §6.8·§6.9 관제 컨트롤러 시험이 공통으로 쓰는 실제 행 — {@code ProximityFixtures}(회차·노선·정차)와
 * {@code Phase9RosterFixtures}(매니저 배치)·{@code EmergencyFixtures}(메인관리자·직원 계정)가 각각
 * 다른 패키지에 흩어져 있어 이 컨트롤러 시험에 필요한 조합(운행중 회차 + 배치된 기사·보호 인력 +
 * 학생 원문 연락처 + 메인관리자·직원 계정)을 한 번에 주는 것이 없다 — 그래서 이 패키지 전용으로
 * 새로 쌓는다({@code ProximityFixtures} 자바독과 같은 근거).
 */
public class AdminMonitoringFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final BusRepository busRepository;

    private final StopRepository stopRepository;

    private final StudentRepository studentRepository;

    private final AccountRepository accountRepository;

    private final GuardianRepository guardianRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    public AdminMonitoringFixtures(AcademyRepository academyRepository, AcademyStaffRepository academyStaffRepository,
            BusRepository busRepository, StopRepository stopRepository, StudentRepository studentRepository,
            AccountRepository accountRepository, GuardianRepository guardianRepository,
            GuardianStudentRepository guardianStudentRepository, RunRepository runRepository,
            ConfirmedRouteRepository confirmedRouteRepository, RouteVersionRepository routeVersionRepository,
            RunStopRepository runStopRepository, RunRiderRepository runRiderRepository,
            ManagerRepository managerRepository, AssignmentRepository assignmentRepository) {
        this.academyRepository = academyRepository;
        this.academyStaffRepository = academyStaffRepository;
        this.busRepository = busRepository;
        this.stopRepository = stopRepository;
        this.studentRepository = studentRepository;
        this.accountRepository = accountRepository;
        this.guardianRepository = guardianRepository;
        this.guardianStudentRepository = guardianStudentRepository;
        this.runRepository = runRepository;
        this.confirmedRouteRepository = confirmedRouteRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.runStopRepository = runStopRepository;
        this.runRiderRepository = runRiderRepository;
        this.managerRepository = managerRepository;
        this.assignmentRepository = assignmentRepository;
    }

    public long academy() {
        String code = "P13T2" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, "관제시험학원", "서울", null, null)).getId();
    }

    public long bus(long academyId) {
        String busNo = "관제" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "22다" + SEQUENCE.incrementAndGet(),
                BusSeating.withDefaultCrew(16))).getId();
    }

    public long stop(long academyId, String lat, String lng) {
        Stop stop = Stop.forVerifiedAddress(academyId, "정차지" + SEQUENCE.incrementAndGet(), "서울시 어딘가 " + lat,
                new BigDecimal(lat), new BigDecimal(lng));
        return stopRepository.save(stop).getId();
    }

    /** 사진·연락처 원문까지 채운 학생(목표 10 원문 단언용) — {@code ProximityFixtures#student} 와 달리 값을 넣는다. */
    public long student(long academyId, String name, String studentPhone, String photoUrl) {
        StudentProfile profile = new StudentProfile(name, studentPhone, photoUrl, null, null, null, null, null, null,
                null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
    }

    /** {@code moving} 상태 회차 1건 — {@code confirmIfIdle} 로 확정한 뒤 {@code run.start} 로 전이한다. */
    public long movingRun(long academyId, long busId, Direction direction, OffsetDateTime departTime,
            OffsetDateTime confirmedAt, OffsetDateTime startedAt, Integer estDurationMin) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), direction, departTime,
                departTime.minusMinutes(30), "출발지", "도착지", estDurationMin);
        long runId = runRepository.save(run).getId();
        runRepository.confirmIfIdle(runId, confirmedAt);
        Run moving = runRepository.findById(runId).orElseThrow();
        moving.start(startedAt);
        runRepository.save(moving);
        return runId;
    }

    /** {@code confirmed}(운행 전) 상태로 남겨 두는 회차 — {@code moving} 필터 시험의 대조군. */
    public long confirmedRun(long academyId, long busId, Direction direction, OffsetDateTime departTime,
            OffsetDateTime confirmedAt) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), direction, departTime,
                departTime.minusMinutes(30), "출발지", "도착지", null);
        long runId = runRepository.save(run).getId();
        runRepository.confirmIfIdle(runId, confirmedAt);
        return runId;
    }

    public long confirmedRouteWithVersion(long runId, OffsetDateTime publishedAt) {
        confirmedRouteRepository.save(ConfirmedRoute.forRun(runId, publishedAt));
        RouteVersion version = routeVersionRepository.save(RouteVersion.forConfirmedRoute(runId, 1,
                RouteVersionSource.CONFIRM_BATCH, 30, new BigDecimal("10.00"), publishedAt, "fp-" + runId,
                "engine-v1", Map.of(), false, null, publishedAt));
        confirmedRouteRepository.assignCurrentVersion(runId, version.getId());
        return version.getId();
    }

    public long runStopForStop(long routeVersionId, long stopId, int seq, OffsetDateTime eta) {
        return runStopRepository.save(RunStop.forStop(routeVersionId, stopId, seq, eta)).getId();
    }

    /** 그 정차 항목을 도착 처리한다(목표 8 뒷항 — 도착 처리된 정차는 {@code eta=null}). */
    public void markArrived(long runStopId, OffsetDateTime arrivedAt) {
        RunStop runStop = runStopRepository.findById(runStopId).orElseThrow();
        runStop.markArrived(arrivedAt);
        runStopRepository.save(runStop);
    }

    public long rider(long runId, long studentId, long stopId) {
        return runRiderRepository.save(RunRider.uponConfirmation(runId, studentId, stopId)).getId();
    }

    /** 계정까지 연결된 매니저 1명 — {@code Phase9RosterFixtures#manager} 와 같은 형태. */
    public ManagerAccount manager(long academyId, ManagerRole role, String name) {
        String phone = "010-1000-" + String.format("%04d", SEQUENCE.incrementAndGet() % 10000);
        Manager manager = managerRepository
                .save(Manager.register(academyId, new ManagerProfile(name, phone, role, null)));
        Account account = accountRepository.save(Account.forSignup(academyId,
                "p13t2mgr" + SEQUENCE.incrementAndGet() + System.nanoTime(), "{noop}password", name, phone, null,
                role == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT));
        manager.linkAccount(account.getId());
        managerRepository.save(manager);
        return new ManagerAccount(manager.getId(), account.getId(), phone);
    }

    public void assign(long runId, long managerId, ManagerRole role) {
        assignmentRepository.save(Assignment.uponAssignment(runId, managerId, role, OffsetDateTime.now(), managerId));
    }

    /** 그 학생에 보호자 1명을 잇고 원문 연락처를 준다(목표 10 {@code guardian_phone} 원문 단언용). */
    public void guardianOf(long academyId, long studentId, String rawPhone) {
        Account account = accountRepository.save(Account.forSignup(academyId, "p13t2guardian"
                + SEQUENCE.incrementAndGet() + System.nanoTime(), "{noop}password", "보호자" + SEQUENCE.get(), rawPhone,
                null, Role.PARENT));
        Guardian guardian = guardianRepository.save(Guardian.forSignup(academyId, account.getId(),
                account.getName(), rawPhone));
        guardianStudentRepository.save(GuardianStudent.uponLink(guardian.getId(), studentId, OffsetDateTime.now()));
    }

    public long systemAdminAccount(String name) {
        Account account = Account.forSignup(null, "p13t2admin" + SEQUENCE.incrementAndGet() + System.nanoTime(), "x",
                name, "010-0000-0000", null, Role.SYSTEM_ADMIN);
        account.approveSignup();
        return accountRepository.save(account).getId();
    }

    public long staffAccount(long academyId, String name) {
        Account account = accountRepository.save(Account.forSignup(academyId, "p13t2staff"
                + SEQUENCE.incrementAndGet() + System.nanoTime(), "x", name, "010-0000-0000", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    public record ManagerAccount(long managerId, long accountId, String phone) {
    }
}
