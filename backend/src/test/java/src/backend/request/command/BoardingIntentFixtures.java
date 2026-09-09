package src.backend.request.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
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
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
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
 * 탑승 의사 토글 시험이 쓰는 실제 행 — {@code RunConfirmationFixtures}(Phase 7)와 같은 이유로 정상
 * 경로의 팩토리로 쌓는다.
 */
public class BoardingIntentFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final BusRepository busRepository;

    private final StudentRepository studentRepository;

    private final GuardianRepository guardianRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final AccountRepository accountRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final RunRepository runRepository;

    private final StopRepository stopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    public BoardingIntentFixtures(AcademyRepository academyRepository, BusRepository busRepository,
            StudentRepository studentRepository, GuardianRepository guardianRepository,
            GuardianStudentRepository guardianStudentRepository, AccountRepository accountRepository,
            AcademyStaffRepository academyStaffRepository, RunRepository runRepository,
            StopRepository stopRepository, ConfirmedRouteRepository confirmedRouteRepository,
            RouteVersionRepository routeVersionRepository, RunStopRepository runStopRepository,
            RunRiderRepository runRiderRepository) {
        this.academyRepository = academyRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.guardianRepository = guardianRepository;
        this.guardianStudentRepository = guardianStudentRepository;
        this.accountRepository = accountRepository;
        this.academyStaffRepository = academyStaffRepository;
        this.runRepository = runRepository;
        this.stopRepository = stopRepository;
        this.confirmedRouteRepository = confirmedRouteRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.runStopRepository = runStopRepository;
        this.runRiderRepository = runRiderRepository;
    }

    public long academy() {
        String code = "P8T2" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, "탑승토글시험학원", "서울", null, null)).getId();
    }

    public long bus(long academyId) {
        String busNo = "탑승토글" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "00나0000", BusSeating.withDefaultCrew(16)))
                .getId();
    }

    public long student(long academyId, String name) {
        StudentProfile profile = new StudentProfile(name, null, null, null, null, null, null, null, null, null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
    }

    /**
     * 보호자 1명 — {@code guardian.account_id} 가 FK NOT NULL 이라({@code fk_guardian_account}) 실제
     * {@code account} 행을 먼저 만들어야 한다({@code LinkedChildLookup} 자체는 토큰의 {@code accountId}
     * 클레임만으로 조회하지만, 그 클레임이 가리키는 행이 없으면 이 저장 자체가 제약 위반이다).
     */
    public GuardianAccount guardian(long academyId, String name) {
        String loginId = "guardian" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", name,
                "010-0000-0000", null, Role.PARENT));
        long guardianId = guardianRepository
                .save(Guardian.forSignup(academyId, account.getId(), name, "010-0000-0000")).getId();
        return new GuardianAccount(account.getId(), guardianId);
    }

    /** {@link #guardian} 이 함께 만든 계정·보호자 식별자 쌍 — 토큰은 {@code accountId}, 연결은 {@code guardianId} 를 쓴다. */
    public record GuardianAccount(long accountId, long guardianId) {
    }

    public void linkChild(long guardianId, long studentId, OffsetDateTime linkedAt) {
        guardianStudentRepository.save(GuardianStudent.uponLink(guardianId, studentId, linkedAt));
    }

    /**
     * 알림 수신 대상(학원 관계자) 계정 1명 — {@code AcademyStaffRepository#findActiveAccountsByAcademyId}
     * 가 {@code account} 테이블과 조인하므로, 보호자와 달리 실제 {@code account} 행이 있어야 알림이
     * {@code notification_log} 에 실제로 적재된다.
     */
    public long staffAccount(long academyId) {
        String loginId = "staff" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", "관계자",
                "010-1111-1111", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    /** 임의의 3구간 판정에 쓸 회차 — {@code departTime}·{@code confirmAt} 은 호출자가 고정 시계 기준으로 계산해 넘긴다. */
    public long run(long academyId, long busId, OffsetDateTime departTime, OffsetDateTime confirmAt) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY, departTime,
                confirmAt, "출발지", "도착지", null);
        return runRepository.save(run).getId();
    }

    public long stop(long academyId, String lat, String lng) {
        Stop stop = Stop.forVerifiedAddress(academyId, "정차지" + lat, "서울시 어딘가 " + lat, new BigDecimal(lat),
                new BigDecimal(lng));
        return stopRepository.save(stop).getId();
    }

    /**
     * ③구간 "잔여 0명이면 run_stop.skipped" 검증용 — 확정 노선 1건·배포 버전 1건·정차 항목 1건·탑승자
     * 1명을 실제 행으로 쌓는다. 반환값은 {@code run_stop.id} 다.
     */
    public long confirmedSingleRiderStop(long runId, long studentId, long stopId, OffsetDateTime confirmedAt) {
        confirmedRouteRepository.save(ConfirmedRoute.forRun(runId, confirmedAt));
        RouteVersion version = routeVersionRepository.save(RouteVersion.forConfirmedRoute(runId, 1,
                RouteVersionSource.CONFIRM_BATCH, 30, new BigDecimal("5.00"), confirmedAt, "fp", "engine",
                Map.of(), false, null, confirmedAt));
        confirmedRouteRepository.assignCurrentVersion(runId, version.getId());

        RunStop runStop = runStopRepository.save(RunStop.forStop(version.getId(), stopId, 1, confirmedAt));
        runRiderRepository.save(RunRider.uponConfirmation(runId, studentId, stopId));
        return runStop.getId();
    }
}
