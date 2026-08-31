package src.backend.run.command;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.GuardianStudent;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * Phase 9 §4.1~§4.3·§5.4 시험이 공통으로 쓰는 확정 회차·매니저 배치·보호자 연락처 데이터 —
 * {@link RunConfirmationFixtures}(회차·노선·학생) 를 그대로 감싸고, 이 네 핸들러 시험에만 필요한
 * 매니저 계정·배치·보호자 세 가지를 더한다.
 *
 * <p>{@code public} 인 이유는 {@link RunConfirmationFixtures} 와 같다 — {@code run.controller} ·
 * {@code boarding.controller} 네 시험 클래스가 패키지를 넘어 재사용한다.
 */
public class Phase9RosterFixtures {

    /** 이 헬퍼가 만드는 계정의 로그인 아이디 접두어 — 실행마다 달라 다른 시험과 겹치지 않는다. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final RunConfirmationFixtures base;

    private final ManagerRepository managerRepository;

    private final AccountRepository accountRepository;

    private final AssignmentRepository assignmentRepository;

    private final GuardianRepository guardianRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final RunConfirmationService confirmationService;

    public Phase9RosterFixtures(RunConfirmationFixtures base, ManagerRepository managerRepository,
            AccountRepository accountRepository, AssignmentRepository assignmentRepository,
            GuardianRepository guardianRepository, GuardianStudentRepository guardianStudentRepository,
            RunConfirmationService confirmationService) {
        this.base = base;
        this.managerRepository = managerRepository;
        this.accountRepository = accountRepository;
        this.assignmentRepository = assignmentRepository;
        this.guardianRepository = guardianRepository;
        this.guardianStudentRepository = guardianStudentRepository;
        this.confirmationService = confirmationService;
    }

    public long academyWithCoordinates() {
        return base.academyWithCoordinates();
    }

    public long bus(long academyId) {
        return base.bus(academyId);
    }

    public long stop(long academyId, String lat, String lng) {
        return base.stop(academyId, lat, lng);
    }

    public long route(long academyId, long busId, Weekday weekday, Direction direction, long... stopIds) {
        return base.route(academyId, busId, weekday, direction, stopIds);
    }

    public long student(long academyId, String name) {
        return base.student(academyId, name);
    }

    public void verifiedAddress(long studentId, long stopId, Weekday weekday, Direction direction, String lat,
            String lng) {
        base.verifiedAddress(studentId, stopId, weekday, direction, lat, lng);
    }

    /** idle 회차를 만들고 곧바로 {@link RunConfirmationService#confirmOne} 으로 확정까지 마친다. */
    public long confirmedRun(long academyId, long busId, LocalDate serviceDate, Direction direction,
            OffsetDateTime departTime, OffsetDateTime confirmAt) {
        long runId = base.idleRun(academyId, busId, serviceDate, direction, departTime, confirmAt);
        confirmationService.confirmOne(runId);
        return runId;
    }

    /** idle 상태 그대로 두는 회차 — §5.4 는 idle 도 조회할 수 있고, §4.2 는 idle 이면 409 여야 한다. */
    public long idleRun(long academyId, long busId, LocalDate serviceDate, Direction direction,
            OffsetDateTime departTime, OffsetDateTime confirmAt) {
        return base.idleRun(academyId, busId, serviceDate, direction, departTime, confirmAt);
    }

    /** 계정까지 연결된 매니저 1명을 만든다 — 반환값의 {@code accountId} 로 토큰을 발급한다. */
    public ManagerAccount manager(long academyId, ManagerRole role, String name) {
        String phone = "010-0000-" + String.format("%04d", SEQUENCE.incrementAndGet() % 10000);
        Manager manager = managerRepository
                .save(Manager.register(academyId, new ManagerProfile(name, phone, role, null)));
        Account account = accountRepository.save(Account.forSignup(academyId,
                "p9manager" + SEQUENCE.incrementAndGet() + System.nanoTime(), "{noop}password", name, phone, null,
                role == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT));
        manager.linkAccount(account.getId());
        managerRepository.save(manager);
        return new ManagerAccount(manager.getId(), account.getId());
    }

    /** 그 매니저를 그 회차에 배치한다({@code role_in_run} 이 이 값이 된다). */
    public void assign(long runId, long managerId, ManagerRole role) {
        assignmentRepository.save(Assignment.uponAssignment(runId, managerId, role, OffsetDateTime.now(), managerId));
    }

    /** 배치는 남긴 채로 매니저만 소프트 삭제한다(목표 16 — 탈퇴 매니저의 옛 토큰이 막히는지). */
    public void softDeleteManager(long managerId) {
        Manager manager = managerRepository.findById(managerId).orElseThrow();
        manager.delete(OffsetDateTime.now());
        managerRepository.save(manager);
    }

    /** 그 학생에 보호자 1명을 잇고, 그 보호자 계정의 연락처를 그대로 준다(목표 6 마스킹 시험의 원문). */
    public void guardianWithPhone(long academyId, long studentId, String rawPhone) {
        Account account = accountRepository.save(Account.forSignup(academyId, "p9guardian" + SEQUENCE.incrementAndGet()
                + System.nanoTime(), "{noop}password", "보호자" + SEQUENCE.get(), rawPhone, null, Role.PARENT));
        Guardian guardian = guardianRepository.save(Guardian.forSignup(academyId, account.getId(), account.getName(),
                rawPhone));
        guardianStudentRepository.save(GuardianStudent.uponLink(guardian.getId(), studentId, OffsetDateTime.now()));
    }

    /** {@link #manager} 가 돌려주는 두 id — 매니저 레코드 id 와 로그인 토큰을 발급할 계정 id. */
    public record ManagerAccount(long managerId, long accountId) {
    }
}
