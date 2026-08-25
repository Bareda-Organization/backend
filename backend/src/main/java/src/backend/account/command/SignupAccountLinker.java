package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.dto.SignupLinkPayload;
import src.backend.account.entity.Account;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.ManagerRepository;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.GuardianStudent;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 가입 수락 시 계정을 실제 레코드에 잇는다(AUTH-11 · API_SPEC §5.2).
 *
 * <p>연결 없이 {@code active} 로 만들면 <b>로그인은 되는데 아무 데이터도 못 보는 계정</b>이 생긴다 —
 * 화면은 비어 있고 서버는 정상 응답이라, 사용자도 관계자도 무엇이 잘못됐는지 알 수 없다. 그래서
 * 연결 누락은 조용한 성공이 아니라 {@code 422 LINK_REQUIRED} 다.
 *
 * <p><b>연결 대상을 전부 확인한 뒤에 쓴다.</b> 한 건씩 확인하며 쓰면 목록 뒤쪽에서 실패했을 때 앞쪽
 * 연결만 남은 계정이 생기고, 그 상태는 "자녀가 둘인데 하나만 보이는" 형태라 아무 에러도 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SignupAccountLinker {

    private final StudentRepository studentRepository;

    private final GuardianRepository guardianRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final ManagerRepository managerRepository;

    private final Clock clock;

    /**
     * 승인 대상 계정의 <b>역할</b>이 무엇을 연결할지 정한다 — 요청 본문이 정하지 않는다.
     *
     * <p>본문이 정하게 두면 학부모 요청에 {@code manager_id} 를 실어 매니저 레코드를 가로챌 수 있다.
     * {@code staff}·{@code system_admin} 이 여기 닿는 것은 축 분리가 깨진 것이므로 {@code 403} 이다.
     */
    public void link(Account account, SignupLinkPayload link) {
        switch (account.getRole()) {
            case PARENT -> linkGuardian(account, resolveStudents(account, link));
            case STUDENT -> linkStudent(account, resolveStudents(account, link));
            case DRIVER, ESCORT -> linkManager(account, link);
            default -> throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * 연결할 학생을 전부 찾아 돌려준다 — 하나라도 어긋나면 아무것도 쓰기 전에 실패한다.
     *
     * <p>같은 식별자가 두 번 실린 요청은 {@code 409 ALREADY_LINKED} 다. DB
     * {@code uk_guardian_student} 에 맡기면 같은 결과가 {@code 500} 으로 나가고, 그 시점엔 이미
     * 영속성 컨텍스트가 망가져 같은 요청의 다른 작업까지 함께 죽는다.
     */
    private List<Student> resolveStudents(Account account, SignupLinkPayload link) {
        List<Long> studentIds = link == null ? null : link.studentIds();
        if (studentIds == null || studentIds.isEmpty()) {
            throw new BusinessException(ErrorCode.LINK_REQUIRED);
        }
        if (studentIds.size() != Set.copyOf(studentIds).size()) {
            throw new BusinessException(ErrorCode.ALREADY_LINKED);
        }
        return studentIds.stream()
                .map(studentId -> studentRepository
                        .findByIdAndAcademyIdAndDeletedAtIsNull(studentId, account.getAcademyId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND)))
                .toList();
    }

    /**
     * 학부모는 {@code guardian} 한 행에 자녀 N 행을 잇는다(P-02) — 다자녀는 <b>연결 추가만</b> 하며
     * 재가입 경로가 부재하다(§5.2).
     *
     * <p>이미 있는 보호자 행을 재사용하는 이유는 자녀마다 보호자를 만들면 학부모 한 명이 시스템 안에서
     * 여러 사람이 되어, 연락처를 고쳐도 일부 자녀의 명단에만 반영되기 때문이다.
     */
    private void linkGuardian(Account account, List<Student> students) {
        Optional<Guardian> existing = guardianRepository.findByAccountId(account.getId());
        existing.ifPresent(guardian -> assertNotLinked(guardian, students));

        Guardian guardian = existing.orElseGet(() -> guardianRepository.save(Guardian.forSignup(
                account.getAcademyId(), account.getId(), account.getName(), account.getPhone())));
        OffsetDateTime linkedAt = OffsetDateTime.now(clock);
        students.forEach(student -> guardianStudentRepository.save(
                GuardianStudent.uponLink(guardian.getId(), student.getId(), linkedAt)));
    }

    private void assertNotLinked(Guardian guardian, List<Student> students) {
        boolean alreadyLinked = students.stream()
                .anyMatch(student -> guardianStudentRepository
                        .existsByGuardianIdAndStudentId(guardian.getId(), student.getId()));
        if (alreadyLinked) {
            throw new BusinessException(ErrorCode.ALREADY_LINKED);
        }
    }

    /**
     * 학생 계정은 학생 레코드 <b>하나</b>에 붙는다 — 둘 이상을 지정하면 어느 쪽이 본인인지 정할 수단이
     * 부재하므로 {@code 422 VALIDATION_FAILED} 다. 조용히 첫 번째를 고르면 나머지가 사라진 것을
     * 아무도 모른다.
     */
    private void linkStudent(Account account, List<Student> students) {
        if (students.size() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        students.get(0).linkAccount(account.getId());
    }

    /** 기사·동승자는 {@code manager} 레코드에 붙는다 — 역할까지 맞는 행만 대상이다({@code ManagerRepository}). */
    private void linkManager(Account account, SignupLinkPayload link) {
        Long managerId = link == null ? null : link.managerId();
        if (managerId == null) {
            throw new BusinessException(ErrorCode.LINK_REQUIRED);
        }
        Manager manager = managerRepository
                .findByIdAndAcademyIdAndRoleAndDeletedAtIsNull(managerId, account.getAcademyId(),
                        managerRoleOf(account.getRole()))
                .orElseThrow(() -> new BusinessException(ErrorCode.MANAGER_NOT_FOUND));
        manager.linkAccount(account.getId());
    }

    /** 계정 역할({@link Role})과 배치 역할({@link ManagerRole})은 값 도메인이 달라 여기서 잇는다. */
    private ManagerRole managerRoleOf(Role role) {
        return role == Role.DRIVER ? ManagerRole.DRIVER : ManagerRole.ESCORT;
    }
}
