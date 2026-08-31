package src.backend.exception.scheduler;

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
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 미승차 에스컬레이션 스케줄러 시험({@code NoShowEscalationSchedulerTest})이 쓰는 실제 행 —
 * {@link src.backend.request.command.ChangeRequestAutoRejectFixtures} 와 같은 이유로 정상 경로의
 * 팩토리로 쌓는다. 이 시험 하나만 재사용하므로 {@code exception.scheduler} 패키지 안에 둔다(공용화가
 * 필요해지면 그때 옮긴다).
 */
public class NoShowEscalationFixtures {

    /** 뒷정리 표시 — 스케줄러 시험이 자기 행만 골라 지우는 데 쓴다. */
    public static final String ACADEMY_NAME = "미승차에스컬레이션시험학원";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;
    private final AcademyStaffRepository academyStaffRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final StopRepository stopRepository;
    private final AccountRepository accountRepository;
    private final RunRepository runRepository;
    private final RunRiderRepository runRiderRepository;
    private final NoShowCaseRepository noShowCaseRepository;

    public NoShowEscalationFixtures(AcademyRepository academyRepository, AcademyStaffRepository academyStaffRepository,
            BusRepository busRepository, StudentRepository studentRepository, StopRepository stopRepository,
            AccountRepository accountRepository, RunRepository runRepository, RunRiderRepository runRiderRepository,
            NoShowCaseRepository noShowCaseRepository) {
        this.academyRepository = academyRepository;
        this.academyStaffRepository = academyStaffRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.stopRepository = stopRepository;
        this.accountRepository = accountRepository;
        this.runRepository = runRepository;
        this.runRiderRepository = runRiderRepository;
        this.noShowCaseRepository = noShowCaseRepository;
    }

    public long academy() {
        String code = "P11T1" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, ACADEMY_NAME, "서울", null, null)).getId();
    }

    /** 관계자 계정 — 에스컬레이션 알림 수신자 확인에 쓴다. */
    public long staffAccount(long academyId) {
        String loginId = "staff" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        Account account = accountRepository.save(Account.forSignup(academyId, loginId, "{noop}password", "관계자",
                "010-1111-1111", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    public long bus(long academyId) {
        String busNo = "에스컬레이션" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "22나" + SEQUENCE.incrementAndGet(),
                BusSeating.withDefaultCrew(16))).getId();
    }

    public long student(long academyId, String name) {
        StudentProfile profile = new StudentProfile(name, null, null, null, null, null, null, null, null, null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
    }

    public long stop(long academyId) {
        String suffix = String.valueOf(SEQUENCE.incrementAndGet());
        return stopRepository.save(Stop.forVerifiedAddress(academyId, "정류장" + suffix, "서울시 어딘가 " + suffix,
                new BigDecimal("37.5"), new BigDecimal("127.0"))).getId();
    }

    /** 임의의 회차 — 스케줄러는 회차 상태를 보지 않으므로 초기 상태(idle) 그대로 쓴다. */
    public long run(long academyId, long busId, OffsetDateTime departTime) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30), "출발지", "도착지", null);
        return runRepository.save(run).getId();
    }

    public long runRider(long runId, long studentId, long stopId) {
        return runRiderRepository.save(RunRider.uponConfirmation(runId, studentId, stopId)).getId();
    }

    /** 대기 만료 시각을 직접 지정한 미승차 케이스 — 스케줄러가 그 시각으로 도래 여부를 가른다. */
    public long noShowCase(long runRiderId, OffsetDateTime startedAt, OffsetDateTime expiresAt) {
        return noShowCaseRepository.save(NoShowCase.forRunRider(runRiderId, startedAt, expiresAt, startedAt)).getId();
    }
}
