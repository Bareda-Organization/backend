package src.backend.academy.dto;

import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.account.entity.Account;

/**
 * 학원 상세에 실리는 관계자 1명(API_SPEC §6.3 {@code staff_accounts[]}).
 *
 * <p>연락처를 마스킹하지 않는다 — 마스킹 대상은 매니저 앱 응답이고 메인 관리자 콘솔은 원문이다(§1.12).
 *
 * @param status 재직 상태. {@code account.status}(계정 상태)가 아니라 {@code academy_staff.status} 다 —
 *               둘은 생명주기가 달라, 계정은 살아 있는데 그 학원에서는 퇴사한 상태가 존재한다
 */
public record AcademyStaffAccountResponse(Long accountId, String name, String loginId, String phone,
        StaffStatus status) {

    public static AcademyStaffAccountResponse from(AcademyStaff staff, Account account) {
        return new AcademyStaffAccountResponse(account.getId(), account.getName(), account.getLoginId(),
                account.getPhone(), staff.getStatus());
    }
}
