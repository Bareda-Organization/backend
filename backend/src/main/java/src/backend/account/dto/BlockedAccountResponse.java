package src.backend.account.dto;

import java.time.OffsetDateTime;

import src.backend.account.entity.Account;

/**
 * 차단 계정 목록의 항목 1개(API_SPEC §6.10 {@code items[]}).
 *
 * <p>연락처를 싣지 않는다 — §6.10 표가 요구하는 것은 "누가 언제 왜 몇 번 만에 막혔나" 이고, 개인정보는
 * 요구된 항목을 넘어 실을수록 노출 지점만 늘어난다(§1.12).
 *
 * @param academyName 소속 학원. 메인 관리자 계정은 학원 소속이 부재해 {@code null}
 * @param reason      {@code account.block_reason} — 화면의 "차단 사유"
 */
public record BlockedAccountResponse(Long accountId, String loginId, String name, String academyName,
        OffsetDateTime blockedAt, int failedAttempts, String reason) {

    /** 학원 이름은 계정만으로 알 수 없어 호출부가 찾아 넘긴다 — 목록 전체를 한 번에 조회해 건별 질의를 피한다. */
    public static BlockedAccountResponse from(Account account, String academyName) {
        return new BlockedAccountResponse(account.getId(), account.getLoginId(), account.getName(), academyName,
                account.getBlockedAt(), account.getFailedAttempts(), account.getBlockReason());
    }
}
