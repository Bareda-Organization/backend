package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
public class SignupCommandService {

    /** V1__init_schema.sql 의 UNIQUE 제약명 — TOCTOU 충돌을 이 제약으로만 좁혀 잡는 데 쓴다. */
    private static final String LOGIN_ID_UNIQUE_CONSTRAINT = "uk_account_login_id";

    private final AccountRepository accountRepository;
    private final SignupRequestRepository signupRequestRepository;
    private final AcademyRepository academyRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * form 회원가입(AUTH-01) — 계정을 {@code pending} 으로 만들고 승인 요청을 큐에 쌓는다.
     *
     * <p>{@code existsByLoginId} 사전 확인은 대부분의 중복을 저렴하게 걸러내지만, 동시에 같은
     * {@code login_id} 로 두 요청이 들어오면 둘 다 사전 확인을 통과한 뒤 {@code uk_account_login_id}
     * UNIQUE 제약에서 충돌한다(TOCTOU) — {@code saveAndFlush} 로 즉시 반영해 그 충돌을
     * {@link DataIntegrityViolationException} 으로 잡아 409 {@code DUPLICATE_LOGIN_ID} 로
     * 옮긴다. 이게 없으면 전역 예외 처리기의 catch-all 로 떨어져 500 이 된다.
     *
     * <p>제약명을 {@link #LOGIN_ID_UNIQUE_CONSTRAINT} 로 좁혀 확인한다(보완 리뷰 Minor #1) — 좁히지
     * 않으면 이 계정에 앞으로 다른 UNIQUE·FK 제약이 늘었을 때 그 위반까지 전부 "아이디 중복" 으로
     * 잘못 답하게 된다.
     */
    @Transactional
    public SignupResponse signup(SignupRequestPayload payload) {
        if (accountRepository.existsByLoginId(payload.loginId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
        }
        Academy academy = findActiveAcademy(payload.academyId());
        Role role = Role.valueOf(payload.role().toUpperCase(Locale.ROOT));

        Account account = Account.forSignup(academy.getId(), payload.loginId(),
                passwordEncoder.encode(payload.password()), payload.name(), payload.phone(), null, role);
        try {
            accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            if (!isLoginIdUniqueViolation(e)) {
                throw e;
            }
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
        }

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
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Academy academy = findActiveAcademy(payload.academyId());

        account.reapply(academy.getId());

        OffsetDateTime requestedAt = OffsetDateTime.now(clock);
        ApproverType approverType = approverTypeFor(account.getRole());
        signupRequestRepository.save(SignupRequest.uponSubmission(academy.getId(), account.getId(),
                account.getRole(), approverType, requestedAt));

        return new ReapplyResponse(account.getStatus().name().toLowerCase(Locale.ROOT), requestedAt);
    }

    /** 원인 체인에서 {@link ConstraintViolationException} 을 찾아 제약명이 login_id UNIQUE 인지만 본다. */
    private boolean isLoginIdUniqueViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && LOGIN_ID_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
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
