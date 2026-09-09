package src.backend.request.controller;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.GuardianStudent;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * {@code ChangeRequestControllerTest} 전용 — 학부모(보호자)·학원 관계자 계정을 실제 가입 경로로
 * 쌓는다({@link RunConfirmationFixtures} 와 같은 이유: {@code LinkedChildLookup}·알림 수신자 조회가
 * 읽는 대상이 정상 경로로 쌓인 행이어야 한다).
 *
 * <p>{@code Guardian.account_id} 는 실제 DB FK(fk_guardian_account)라 토큰의 accountId 클레임만으로는
 * {@code LinkedChildLookup} 이 통과하지 않는다 — {@link Account} 행을 먼저 만들고 그 id 로
 * {@link Guardian}·{@link GuardianStudent} 를 이어야 한다.
 */
public class ChangeRequestFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AccountRepository accountRepository;

    private final GuardianRepository guardianRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final AcademyStaffRepository academyStaffRepository;

    public ChangeRequestFixtures(AccountRepository accountRepository, GuardianRepository guardianRepository,
            GuardianStudentRepository guardianStudentRepository, AcademyStaffRepository academyStaffRepository) {
        this.accountRepository = accountRepository;
        this.guardianRepository = guardianRepository;
        this.guardianStudentRepository = guardianStudentRepository;
        this.academyStaffRepository = academyStaffRepository;
    }

    /**
     * 학생 1명에 연결된 학부모 계정을 만들고 그 {@code accountId} 를 돌려준다 — 돌려준 값을 그대로
     * 토큰의 accountId 클레임에 넣으면 {@code LinkedChildLookup} 이 통과한다.
     */
    public long parentLinkedTo(long academyId, long studentId) {
        long accountId = account(academyId, Role.PARENT, "학부모");
        Guardian guardian = guardianRepository.save(Guardian.forSignup(academyId, accountId, "학부모", "01000000000"));
        guardianStudentRepository.save(GuardianStudent.uponLink(guardian.getId(), studentId, OffsetDateTime.now()));
        return accountId;
    }

    /**
     * 연결된 자녀가 없는 학부모 계정 — "연결 부재 자녀 403" 대조군(단언 6)에 쓴다. 같은 학원이든
     * 아니든 {@code GuardianStudent} 행 자체가 없으므로 어느 학생에 대해서도 403 이어야 한다.
     */
    public long parentWithoutLink(long academyId) {
        long accountId = account(academyId, Role.PARENT, "미연결학부모");
        guardianRepository.save(Guardian.forSignup(academyId, accountId, "미연결학부모", "01000000001"));
        return accountId;
    }

    /**
     * 그 학원의 활성 관계자 계정 — {@code IntentNotificationListener#appendApprovalRequested} 가
     * 읽는 수신자다(목표 2). 학원당 활성 관계자 1명 제약(uk_academy_staff_academy_active)이라 학원마다
     * 한 번만 부른다.
     */
    public long staffOf(long academyId) {
        long accountId = account(academyId, Role.STAFF, "관계자");
        academyStaffRepository.save(AcademyStaff.uponApproval(academyId, accountId));
        return accountId;
    }

    private long account(long academyId, Role role, String name) {
        String loginId = "crf" + SEQUENCE.incrementAndGet() + System.nanoTime();
        Account account = Account.forSignup(academyId, loginId, "해시", name, "01000000000", null, role);
        account.approveSignup();
        return accountRepository.save(account).getId();
    }
}
