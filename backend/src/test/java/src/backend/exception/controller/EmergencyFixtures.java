package src.backend.exception.controller;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
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
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 비상 알림(EXC-04, Phase 11 T2 목표 5~11) 시험이 쓰는 실제 행 — {@code DriverRunFixtures} 와 같은
 * 이유로 정상 경로의 팩토리로 쌓는다. 이 태스크의 소유 경로(exception·admin·notification/domain/impl)
 * 밖의 픽스처를 다른 도메인의 클래스와 공유하지 않고 이 패키지에 독립으로 둔다 — 병렬 좌석이 같은
 * 파일을 동시에 건드릴 여지를 없앤다.
 */
public class EmergencyFixtures {

    public static final String ACADEMY_NAME = "비상알림시험학원";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final BusRepository busRepository;

    private final AccountRepository accountRepository;

    private final ManagerRepository managerRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRepository runRepository;

    private final AcademyStaffRepository academyStaffRepository;

    public EmergencyFixtures(AcademyRepository academyRepository, BusRepository busRepository,
            AccountRepository accountRepository, ManagerRepository managerRepository,
            AssignmentRepository assignmentRepository, RunRepository runRepository,
            AcademyStaffRepository academyStaffRepository) {
        this.academyRepository = academyRepository;
        this.busRepository = busRepository;
        this.accountRepository = accountRepository;
        this.managerRepository = managerRepository;
        this.assignmentRepository = assignmentRepository;
        this.runRepository = runRepository;
        this.academyStaffRepository = academyStaffRepository;
    }

    public long academy() {
        String code = "P11T2" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, ACADEMY_NAME, "서울", null, null)).getId();
    }

    public long bus(long academyId) {
        String busNo = "비상" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "22나" + SEQUENCE.incrementAndGet(),
                BusSeating.withDefaultCrew(16))).getId();
    }

    /** confirmed 상태 회차 1건 — 비상 신고는 확정 여부와 무관하게 성립해야 하므로 확정만 해 둔다. */
    public long confirmedRun(long academyId, long busId, OffsetDateTime departTime) {
        return confirmedRun(academyId, busId, departTime, Direction.TO_ACADEMY);
    }

    /**
     * 방향을 지정하는 confirmed 상태 회차 1건(목표 5 — direction 필드가 실제 값을 반영하는지 보려면
     * to_academy 아닌 값이 필요하다).
     */
    public long confirmedRun(long academyId, long busId, OffsetDateTime departTime, Direction direction) {
        Run run = Run.forSchedule(academyId, busId, null, LocalDate.of(2030, 4, 1), direction, departTime,
                departTime.minusMinutes(30), "출발지", "도착지", null);
        long runId = runRepository.save(run).getId();
        runRepository.confirmIfIdle(runId, departTime.minusMinutes(30));
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

    /** 재직 관계자 1명(목표 6의 STAFF 알림 수신자) — 반환값은 계정 id. */
    public long staffAccount(long academyId, String name) {
        Account account = accountRepository.save(Account.forSignup(academyId, "직원" + SEQUENCE.incrementAndGet(), "x",
                name, "010-0000-0000", null, Role.STAFF));
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, account.getId()));
        return account.getId();
    }

    /** 메인관리자 1명(목표 6의 SYSTEM_ADMIN 알림 수신자) — active 여야 팬아웃 조회에 잡힌다. */
    public long systemAdminAccount(String name) {
        Account account = Account.forSignup(null, "관리자" + SEQUENCE.incrementAndGet(), "x", name, "010-0000-0000",
                null, Role.SYSTEM_ADMIN);
        account.approveSignup();
        return accountRepository.save(account).getId();
    }

    /**
     * 학부모 1명(목표 7 — 비상 알림을 받으면 안 되는 계정) — 반환값은 계정 id. 팬아웃 회귀가 흔히
     * {@code findAllByRoleAndStatus(role, ACTIVE)} 형태로 status 를 조건에 넣으므로, PENDING 인 채로
     * 두면 그런 회귀조차 우연히 걸러져 시험이 아무것도 검증하지 못한다 — active 로 승인까지 한다.
     */
    public long parentAccount(long academyId, String name) {
        Account account = Account.forSignup(academyId, "부모" + SEQUENCE.incrementAndGet(), "x", name,
                "010-0000-0000", null, Role.PARENT);
        account.approveSignup();
        return accountRepository.save(account).getId();
    }

    /** 학생 1명(목표 7 — 비상 알림을 받으면 안 되는 계정) — 반환값은 계정 id. 근거는 {@link #parentAccount} 와 같다. */
    public long studentAccount(long academyId, String name) {
        Account account = Account.forSignup(academyId, "학생" + SEQUENCE.incrementAndGet(), "x", name,
                "010-0000-0000", null, Role.STUDENT);
        account.approveSignup();
        return accountRepository.save(account).getId();
    }
}
