package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 복구 응답 — API_SPEC §2.9 는 응답 스키마를 규정하지 않아(Task 4 판단, 보고서 ⑥) 세 경우를
 * 하나의 레코드로 표현한다. {@code verificationCode} 미전달(코드 발송) · 아이디 복구 성공 ·
 * 비밀번호 복구 성공 — 셋 중 실제로 채워지는 필드만 남기고 나머지는 {@code null} 로 비운다
 * ({@code NON_NULL} 로 JSON 에서도 뺀다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecoverResponse(Boolean codeSent, String loginId, String temporaryPassword) {

    /** SMS 인증 코드 발송 요청 처리 결과(입력에 {@code verification_code} 가 없었던 경우). */
    public static RecoverResponse ofCodeSent() {
        return new RecoverResponse(true, null, null);
    }

    /** {@code type=login_id} 복구 성공 — 등록된 아이디를 그대로 알려준다. */
    public static RecoverResponse loginIdRevealed(String loginId) {
        return new RecoverResponse(null, loginId, null);
    }

    /** {@code type=password} 복구 성공 — 임시 비밀번호를 발급해 그대로 돌려준다(별도 발송 채널 부재). */
    public static RecoverResponse passwordReset(String temporaryPassword) {
        return new RecoverResponse(null, null, temporaryPassword);
    }
}
