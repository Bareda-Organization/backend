package src.backend.account.query;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.dto.MeResponse;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.ManagerRepository;
import src.backend.student.entity.Guardian;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 본인 프로필 조회(API_SPEC §2.10) — role 별 부가 필드(student_id · manager_id/role ·
 * linked_student_count)를 함께 채운다. {@code pending}·{@code rejected} 도 호출 가능하다고
 * API_SPEC 본문은 적지만, Task 3·4 부착 지침(p2-task-2-report.md)이 이 엔드포인트에 계정 상태
 * 게이트 애너테이션을 붙이지 않기로 확정했다 — 이 서비스는 그 결정을 그대로 따르고, 편차는
 * 태스크 보고서에 남긴다.
 */
@Service
public class MeQueryService {

    private final AccountRepository accountRepository;
    private final AcademyRepository academyRepository;
    private final StudentRepository studentRepository;
    private final GuardianRepository guardianRepository;
    private final GuardianStudentRepository guardianStudentRepository;
    private final ManagerRepository managerRepository;

    public MeQueryService(AccountRepository accountRepository, AcademyRepository academyRepository,
            StudentRepository studentRepository, GuardianRepository guardianRepository,
            GuardianStudentRepository guardianStudentRepository, ManagerRepository managerRepository) {
        this.accountRepository = accountRepository;
        this.academyRepository = academyRepository;
        this.studentRepository = studentRepository;
        this.guardianRepository = guardianRepository;
        this.guardianStudentRepository = guardianStudentRepository;
        this.managerRepository = managerRepository;
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Role role = account.getRole();

        MeResponse.Academy academy = resolveAcademy(account, role);
        String studentId = role == Role.STUDENT ? resolveStudentId(accountId) : null;
        Manager manager = (role == Role.DRIVER || role == Role.ESCORT)
                ? managerRepository.findByAccountId(accountId).orElse(null)
                : null;
        Integer linkedStudentCount = role == Role.PARENT ? resolveLinkedStudentCount(accountId) : null;

        return new MeResponse(account.getId(), account.getLoginId(), account.getName(), account.getPhone(),
                role.name().toLowerCase(Locale.ROOT), account.getStatus().name().toLowerCase(Locale.ROOT), academy,
                studentId, manager == null ? null : String.valueOf(manager.getId()),
                manager == null ? null : manager.getRole().name().toLowerCase(Locale.ROOT), linkedStudentCount);
    }

    /** {@code system_admin} 은 소속 학원이 없어 {@code null} 을 그대로 돌려준다(API_SPEC §2.10). */
    private MeResponse.Academy resolveAcademy(Account account, Role role) {
        if (role == Role.SYSTEM_ADMIN) {
            return null;
        }
        Academy academy = academyRepository.findById(account.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        return new MeResponse.Academy(String.valueOf(academy.getId()), academy.getName());
    }

    private String resolveStudentId(Long accountId) {
        return studentRepository.findByAccountId(accountId).map(s -> String.valueOf(s.getId())).orElse(null);
    }

    private Integer resolveLinkedStudentCount(Long accountId) {
        return guardianRepository.findByAccountId(accountId)
                .map(Guardian::getId)
                .map(guardianStudentRepository::countByGuardianIdAndUnlinkedAtIsNull)
                .map(Long::intValue)
                .orElse(0);
    }
}
