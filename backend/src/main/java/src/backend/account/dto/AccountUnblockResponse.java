package src.backend.account.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.account.entity.Account;

/**
 * 차단 해제 응답(API_SPEC §6.12) — {@code account_status} · {@code unblocked_by} · {@code unblocked_at}.
 *
 * <p>{@code failed_attempts} 를 싣지 않는다. §6.12 표가 요구하지 않을뿐더러, 싣게 되면 "카운터가
 * 0 인가" 를 응답만 보고 판정할 수 있어 <b>실제로 저장됐는지</b>를 아무도 확인하지 않게 된다 —
 * 그 축은 DB 행을 직접 읽는 단언이 맡는다.
 */
public record AccountUnblockResponse(String accountStatus, Long unblockedBy, OffsetDateTime unblockedAt) {

    public static AccountUnblockResponse from(Account account) {
        return new AccountUnblockResponse(account.getStatus().name().toLowerCase(Locale.ROOT),
                account.getUnblockedBy(), account.getUnblockedAt());
    }
}
