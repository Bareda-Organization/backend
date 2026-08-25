package src.backend.account.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.account.entity.VerificationCode;
import src.backend.account.entity.VerificationPurpose;

/** {@link VerificationCode} 영속성 접근. */
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    /**
     * 연락처·목적으로 가장 최근 발급된 인증 코드를 찾는다(API_SPEC §2.9 아이디·비밀번호 복구 —
     * 사용자가 제출한 코드를 이 결과와 대조한다). 같은 연락처·목적으로 재발송이 일어나면 이전 코드는
     * 무효 취급해야 하므로 최신 1건만 본다.
     */
    Optional<VerificationCode> findTopByPhoneAndPurposeOrderByCreatedAtDesc(String phone, VerificationPurpose purpose);
}
