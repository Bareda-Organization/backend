package src.backend.academy.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.academy.entity.StaffStatus;
import src.backend.account.entity.Account;

/**
 * 관계자 계정 목록의 항목 1개(API_SPEC §6.6 {@code items[]}).
 *
 * <p>연락처를 마스킹하지 않는다 — 마스킹 대상은 매니저 앱 응답이고 메인 관리자 콘솔은 원문이다(§1.12).
 *
 * @param status 재직 상태. {@code account.status}(계정 상태 4종)가 아니라 {@code academy_staff.status}
 *               (2종)다 — 둘은 생명주기가 달라, 계정은 살아 있는데 그 학원에서는 퇴사한 상태가 존재한다
 */
public record StaffAccountSummaryResponse(Long accountId, String name, String loginId, String phone,
        String academyName, OffsetDateTime lastLoginAt, String status) {

    /** 학원 이름과 재직 상태는 계정만으로 알 수 없어 호출부가 찾아 넘긴다 — 목록 전체를 한 번에 조회한다. */
    public static StaffAccountSummaryResponse from(Account account, String academyName, StaffStatus status) {
        return new StaffAccountSummaryResponse(account.getId(), account.getName(), account.getLoginId(),
                account.getPhone(), academyName, account.getLastLoginAt(), status.name().toLowerCase(Locale.ROOT));
    }
}
