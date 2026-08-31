package src.backend.location.proximity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
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
 * 근접 알림(NTF-04, Phase 10 목표 13·15) 시험이 쓰는 실제 행 — {@code DriverRunFixtures}·
 * {@code RunConfirmationFixtures} 와 같은 이유로 정상 경로의 팩토리로 쌓는다.
 *
 * <p>{@code Phase9RosterFixtures} 의 지오코딩 확정 파이프라인을 타지 않는다 — 근접 판정은 이미
 * 배포된 {@code run_stop}·{@code run_rider} 를 읽기만 하므로, {@code DriverRunFixtures} 가 도착
 * 처리 시험에 쓰는 것과 같은 형태로 그 행을 직접 심는 편이 노선 계산 스텁 설정 없이 더 좁다.
 */
public class ProximityFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

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

    public ProximityFixtures(AcademyRepository academyRepository, BusRepository busRepository,
            StopRepository stopRepository, StudentRepository studentRepository, AccountRepository accountRepository,
            GuardianRepository guardianRepository, GuardianStudentRepository guardianStudentRepository,
            RunRepository runRepository, ConfirmedRouteRepository confirmedRouteRepository,
            RouteVersionRepository routeVersionRepository, RunStopRepository runStopRepository,
            RunRiderRepository runRiderRepository) {
        this.academyRepository = academyRepository;
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
    }

    public long academy() {
        String code = "P10T3" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, "근접알림시험학원", "서울", null, null)).getId();
    }

    public long bus(long academyId) {
        String busNo = "근접알림" + SEQUENCE.incrementAndGet();
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

    /** 그 학생의 보호자 1명(근접 알림 수신자 조회 대상) — 반환값은 계정 id. */
    public long guardianOf(long academyId, long studentId, String name, OffsetDateTime linkedAt) {
        Account account = accountRepository.save(Account.forSignup(academyId, "부모" + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, Role.PARENT));
        Guardian guardian = guardianRepository.save(Guardian.forSignup(academyId, account.getId(), name,
                "010-0000-0000"));
        guardianStudentRepository.save(GuardianStudent.uponLink(guardian.getId(), studentId, linkedAt));
        return account.getId();
    }

    /**
     * {@code moving} 상태 회차 1건 — {@code confirmIfIdle} 로 확정한 뒤 {@code run.start} 로 곧장
     * 운행 중으로 전이한다({@code DriverRunFixtures#startRun} 과 같은 근거, 스케줄러가 실제로 대상으로
     * 고르는 상태는 {@code MOVING} 뿐이다).
     */
    public long movingRun(long academyId, long busId, Direction direction, OffsetDateTime departTime,
            OffsetDateTime confirmedAt, OffsetDateTime startedAt) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), direction, departTime,
                departTime.minusMinutes(30), "출발지", "도착지", null);
        long runId = runRepository.save(run).getId();
        runRepository.confirmIfIdle(runId, confirmedAt);
        Run moving = runRepository.findById(runId).orElseThrow();
        moving.start(startedAt);
        runRepository.save(moving);
        return runId;
    }

    /** 확정 노선 1버전 — {@code DriverRunFixtures#confirmedRouteWithVersion} 과 같은 형태. 반환값은 {@code route_version.id}. */
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

    /** 그 회차의 라이더 1건 — {@code ABSENT} 면 결석 처리까지 마친다(C-02 배제 시험용). */
    public long rider(long runId, long studentId, long stopId, RiderStatus status, OffsetDateTime timestamp) {
        RunRider rider = RunRider.uponConfirmation(runId, studentId, stopId);
        if (status == RiderStatus.ABSENT) {
            rider.markAbsent(timestamp);
        }
        return runRiderRepository.save(rider).getId();
    }
}
