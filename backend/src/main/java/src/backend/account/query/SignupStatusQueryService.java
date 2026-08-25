package src.backend.account.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.dto.SignupStatusResponse;
import src.backend.account.entity.Account;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 가입 심사 진행 상태 조회(AUTH-01, API_SPEC §2.3) — pending 계정이 자신의 승인 대기 화면을 그리는 데 쓴다. */
@Service
public class SignupStatusQueryService {

    private final AccountRepository accountRepository;
    private final SignupRequestRepository signupRequestRepository;
    private final AcademyRepository academyRepository;

    public SignupStatusQueryService(AccountRepository accountRepository,
            SignupRequestRepository signupRequestRepository, AcademyRepository academyRepository) {
        this.accountRepository = accountRepository;
        this.signupRequestRepository = signupRequestRepository;
        this.academyRepository = academyRepository;
    }

    @Transactional(readOnly = true)
    public SignupStatusResponse getStatus(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        SignupRequest latest = signupRequestRepository.findTopByAccountIdOrderByRequestedAtDesc(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Academy academy = academyRepository.findById(account.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        return SignupStatusResponse.of(account, academy, latest);
    }
}
