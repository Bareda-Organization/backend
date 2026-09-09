package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 복구 응답 — API_SPEC §2.9 는 응답 스키마를 규정하지 않아(Task 4 판단, 보고서 ⑥) 세 경우를
 * 하나의 레코드로 표현한다. {@code verificationCode} 미전달(코드 발송) · 아이디 복구 성공 ·
 * 비밀번호 복구 성공 — 셋 중 실제로 채워지는 필드만 남기고 나머지는 {@code null} 로 비운다
 * ({@code NON_NULL} 로 JSON 에서도 뺀다).
 *
 * @param codeSent SMS 인증 코드 발송 요청을 처리했음을 알린다
 * @param loginId {@code type=login_id} 복구 결과
 * @param temporaryPassword <b>미인증 응답으로 나가는 평문 자격 증명이다.</b> SMS 연동이 부재해
 *     발급한 비밀번호를 알릴 다른 채널이 없어 본문에 그대로 싣는다 — 이 필드가 있는 한
 *     {@code POST /auth/recover} 는 "코드를 맞히면 그 자리에서 계정을 넘겨주는" 엔드포인트다.
 *     대조 상한(리뷰 라운드 1 C1)이 그 경로를 좁혔을 뿐 닫지는 못한다 — 공격자는 재발급마다 5회씩
 *     새 코드를 대조할 수 있고 발송 요청 자체의 빈도 제한은 부재하다(그 제한은 Phase 14 운영 게이트
 *     소관). <b>SMS 발송을 붙이는 사람은 이 필드를 지우고 코드 발송과 같은 채널로 돌려야 한다.</b>
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
