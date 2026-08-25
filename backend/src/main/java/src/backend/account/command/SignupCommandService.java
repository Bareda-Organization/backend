package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.dto.ReapplyRequestPayload;
import src.backend.account.dto.ReapplyResponse;
import src.backend.account.dto.SignupRequestPayload;
import src.backend.account.dto.SignupResponse;
import src.backend.account.entity.Account;
import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 가입 신청 생명주기(AUTH-01·AUTH-03, API_SPEC §2.2·§2.4) — 최초 신청과 거절 후 재신청을 함께 다룬다. */
@Service
public class SignupCommandService {

    private final AccountRepository accountRepository;
    private final SignupRequestRepository signupRequestRepository;
    private final AcademyRepository academyRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public SignupCommandService(AccountRepository accountRepository,
            SignupRequestRepository signupRequestRepository, AcademyRepository academyRepository,
            PasswordEncoder passwordEncoder, Clock clock) {
        this.accountRepository = accountRepository;
        this.signupRequestRepository = signupRequestRepository;
        this.academyRepository = academyRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** form 회원가입(AUTH-01) — 계정을 {@code pending} 으로 만들고 승인 요청을 큐에 쌓는다. */
    @Transactional
    public SignupResponse signup(SignupRequestPayload payload) {
        if (accountRepository.existsByLoginId(payload.loginId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
        }
        Academy academy = findActiveAcademy(payload.academyId());
        Role role = Role.valueOf(payload.role().toUpperCase());

        Account account = Account.forSignup(academy.getId(), payload.loginId(),
                passwordEncoder.encode(payload.password()), payload.name(), payload.phone(), null, role);
        accountRepository.save(account);

        ApproverType approverType = approverTypeFor(role);
        OffsetDateTime requestedAt = OffsetDateTime.now(clock);
        signupRequestRepository.save(
                SignupRequest.uponSubmission(academy.getId(), account.getId(), role, approverType, requestedAt));

        return SignupResponse.of(account.getStatus(), requestedAt, approverType);
    }

    /** 거절 후 재신청(AUTH-03) — 학원을 다시 선택하고, 새 승인 요청 행을 쌓는다(이력 보존). */
    @Transactional
    public ReapplyResponse reapply(Long accountId, ReapplyRequestPayload payload) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Academy academy = findActiveAcademy(payload.academyId());

        account.reapply(academy.getId());

        OffsetDateTime requestedAt = OffsetDateTime.now(clock);
        ApproverType approverType = approverTypeFor(account.getRole());
        signupRequestRepository.save(SignupRequest.uponSubmission(academy.getId(), account.getId(),
                account.getRole(), approverType, requestedAt));

        return new ReapplyResponse(account.getStatus().name().toLowerCase(), requestedAt);
    }

    /** role=staff 는 메인 관리자가, 그 외 역할은 학원 관계자가 승인한다(API_SPEC §2.2). */
    private ApproverType approverTypeFor(Role role) {
        return role == Role.STAFF ? ApproverType.SYSTEM_ADMIN : ApproverType.STAFF;
    }

    private Academy findActiveAcademy(String rawAcademyId) {
        Long academyId = parseAcademyId(rawAcademyId);
        return academyRepository.findById(academyId)
                .filter(a -> a.getStatus() == AcademyStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    private Long parseAcademyId(String rawAcademyId) {
        try {
            return Long.valueOf(rawAcademyId);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.ACADEMY_NOT_FOUND);
        }
    }
}
