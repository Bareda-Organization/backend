package src.backend.boarding.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import org.springframework.jdbc.core.JdbcTemplate;

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
 * 승하차 처리·되돌리기(§4.6·§4.7) 시험이 쓰는 실제 행 — {@code BoardingIntentFixtures}(Phase 8)와 같은
 * 이유로 정상 경로의 팩토리로 쌓는다.
 *
 * <p>{@code run.status='moving'} 전이는 이 워크트리에 T2 소유의 회차 시작 커맨드가 없어 팩토리로 만들
 * 수 없다 — {@link #startRun} 이 그 대신 {@link JdbcTemplate} 직접 UPDATE 로 전이시킨다(정상 경로가
 * 아직 없는 협력자를 시험이 대신 흉내 내는 지점, 판단 근거로 보고에 남긴다).
 */
public class BoardingCommandFixtures {

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

    private final RunRiderRepository runRiderRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final JdbcTemplate jdbcTemplate;

    private final EntityManager entityManager;

    public BoardingCommandFixtures(AcademyRepository academyRepository, BusRepository busRepository,
            StudentRepository studentRepository, GuardianRepository guardianRepository,
            GuardianStudentRepository guardianStudentRepository, AccountRepository accountRepository,
            AcademyStaffRepository academyStaffRepository, RunRepository runRepository,
            StopRepository stopRepository, RunRiderRepository runRiderRepository,
            ConfirmedRouteRepository confirmedRouteRepository, RouteVersionRepository routeVersionRepository,
            RunStopRepository runStopRepository, JdbcTemplate jdbcTemplate, EntityManager entityManager) {
        this.academyRepository = academyRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.guardianRepository = guardianRepository;
        this.guardianStudentRepository = guardianStudentRepository;
        this.accountRepository = accountRepository;
        this.academyStaffRepository = academyStaffRepository;
        this.runRepository = runRepository;
        this.stopRepository = stopRepository;
        this.runRiderRepository = runRiderRepository;
        this.confirmedRouteRepository = confirmedRouteRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.runStopRepository = runStopRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    public long academy() {
        String code = "P9T3" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, "승하차처리시험학원", "서울", null, null)).getId();
    }

    public long bus(long academyId) {
        String busNo = "승하차처리" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "00다0000", BusSeating.withDefaultCrew(16)))
                .getId();
    }

    public long student(long academyId, String name) {
        StudentProfile profile = new StudentProfile(name, null, null, null, null, null, null, null, null, null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
    }

    /** 보호자 1명 — {@link #guardian} 을 목표 7(학부모 알림) 시험이 수신자 확인에 쓴다. */
    public GuardianAccount guardian(long academyId, String name) {
        String loginId = "guardian" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", name,
                "010-0000-0000", null, Role.PARENT));
        long guardianId = guardianRepository
                .save(Guardian.forSignup(academyId, account.getId(), name, "010-0000-0000")).getId();
        return new GuardianAccount(account.getId(), guardianId);
    }

    /** {@link #guardian} 이 함께 만든 계정·보호자 식별자 쌍. */
    public record GuardianAccount(long accountId, long guardianId) {
    }

    public void linkChild(long guardianId, long studentId, OffsetDateTime linkedAt) {
        guardianStudentRepository.save(GuardianStudent.uponLink(guardianId, studentId, linkedAt));
    }

    /** 목표 7(관계자 통지) 수신 대상 — {@code AcademyStaff} 승인 행까지 함께 만든다. */
    public long staffAccount(long academyId) {
        String loginId = "staff" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", "관계자",
                "010-1111-1111", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    /**
     * 동승자 계정 — Ruling 203("동승자가 없는 회차는 없다고 가정한다")에 따라 회차별 배정 행 없이
     * 역할({@code Role.ESCORT}) 하나만으로 권한을 확인하므로, 여기서는 {@code account} 행만 만든다.
     */
    public long escortAccount(long academyId) {
        String loginId = "escort" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", "동승자",
                "010-2222-2222", null, Role.ESCORT)).getId();
    }

    /** 목표4(권한 거부) 시험용 — 동승자가 아닌 역할의 대표로 기사 계정을 쓴다. */
    public long driverAccount(long academyId) {
        String loginId = "driver" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", "기사",
                "010-3333-3333", null, Role.DRIVER)).getId();
    }

    /** 임의의 회차 — 초기 상태는 {@code idle} 이다({@link Run#forSchedule}). */
    public long run(long academyId, long busId, OffsetDateTime departTime, OffsetDateTime confirmAt) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY, departTime,
                confirmAt, "출발지", "도착지", null);
        return runRepository.save(run).getId();
    }

    /**
     * {@code moving} 상태의 회차 — 승하차 처리·되돌리기는 이 상태에서만 통과한다(§4.6·§4.7,
     * {@code RUN_NOT_MOVING}). T2 소유의 회차 시작 커맨드가 이 워크트리에 없어 직접 UPDATE 로 전이시킨다.
     */
    public long movingRun(long academyId, long busId, OffsetDateTime departTime, OffsetDateTime confirmAt) {
        long runId = run(academyId, busId, departTime, confirmAt);
        startRun(runId);
        return runId;
    }

    /**
     * 이미 만든 회차를 {@code moving} 으로 강제 전이한다 — 정상 경로(T2)가 이 워크트리에 부재하다.
     *
     * <p>{@link JdbcTemplate} 직접 UPDATE 는 영속성 컨텍스트를 거치지 않아, 이미 관리 중인 {@code Run}
     * 엔티티가 있으면 이후 {@code findByIdAndAcademyId} 가 DB 대신 그 캐시(옛 {@code idle})를 돌려준다.
     * {@link EntityManager#clear()} 로 비워야 서비스가 실제로 갱신된 값을 다시 읽는다.
     */
    public void startRun(long runId) {
        int updated = jdbcTemplate.update("UPDATE run SET status = 'moving' WHERE id = ?", runId);
        if (updated != 1) {
            throw new IllegalStateException("회차를 moving 으로 전이하지 못했다: runId=" + runId);
        }
        entityManager.clear();
    }

    public long stop(long academyId, String lat, String lng) {
        Stop stop = Stop.forVerifiedAddress(academyId, "정차지" + lat, "서울시 어딘가 " + lat, new BigDecimal(lat),
                new BigDecimal(lng));
        return stopRepository.save(stop).getId();
    }

    /** {@code waiting} 상태의 탑승자 1명 — {@link RunRider#uponConfirmation} 이 초기값을 보장한다. */
    public long runRider(long runId, long studentId, long stopId) {
        return runRiderRepository.save(RunRider.uponConfirmation(runId, studentId, stopId)).getId();
    }

    /**
     * {@code stop_skipped}(목표 7 후자 경로, C-05·§4.6) 검증용 확정 노선 1건 — 배포 버전 1건·정차 항목
     * 1건을 실제 행으로 쌓고 {@code run_stop.id} 를 돌려준다. 탑승자 행은 만들지 않는다 — 호출자가
     * {@link #runRider} 로 원하는 수만큼(잔여 판정 시나리오에 맞게) 별도로 붙인다는 전제다
     * ({@code BoardingIntentFixtures#confirmedSingleRiderStop} 과 달리 탑승자를 여러 명 두는 시나리오가
     * 있어 탑승자 생성을 분리했다).
     */
    public long confirmedRunStop(long runId, long stopId, OffsetDateTime confirmedAt) {
        confirmedRouteRepository.save(ConfirmedRoute.forRun(runId, confirmedAt));
        RouteVersion version = routeVersionRepository.save(RouteVersion.forConfirmedRoute(runId, 1,
                RouteVersionSource.CONFIRM_BATCH, 30, new BigDecimal("5.00"), confirmedAt, "fp", "engine", Map.of(),
                false, null, confirmedAt));
        confirmedRouteRepository.assignCurrentVersion(runId, version.getId());

        RunStop runStop = runStopRepository.save(RunStop.forStop(version.getId(), stopId, 1, confirmedAt));
        return runStop.getId();
    }

    /** 잔여 판정의 함정(목표 7)을 걸기 위해 이미 부재 처리된 탑승자를 직접 만든다 — {@code markAbsent} 는 회차 진행 중 경로가 없다. */
    public void markAbsent(long riderId) {
        int updated = jdbcTemplate.update("UPDATE run_rider SET status = 'absent' WHERE id = ?", riderId);
        if (updated != 1) {
            throw new IllegalStateException("탑승자를 absent 로 전이하지 못했다: riderId=" + riderId);
        }
        entityManager.clear();
    }
}
