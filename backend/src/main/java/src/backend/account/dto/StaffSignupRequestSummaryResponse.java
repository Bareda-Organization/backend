package src.backend.account.dto;

import java.time.OffsetDateTime;

import src.backend.account.entity.Account;
import src.backend.account.entity.SignupRequest;

/**
 * 메인 관리자가 보는 관계자 가입 요청 1건(API_SPEC §6.4 {@code items[]}).
 *
 * <p>{@link SignupRequestSummaryResponse} 와 나눠 둔 이유는 두 목록이 다른 것을 싣기 때문이다 —
 * 이쪽은 {@code role} 이 항상 {@code staff} 라 실을 이유가 없고, 대신 학원과 그 학원의 현재 관계자
 * 수가 필요하다. 한 DTO 로 겸하면 관계자 축 응답에 타 학원 정보를 실을 자리가 생긴다.
 *
 * @param academyStaffCount 그 학원의 재직 관계자 수 — 이 값이 없으면 메인 관리자는 <b>승인을 눌러
 *                          409 를 받아야</b> 정원이 찼다는 것을 알게 된다
 */
public record StaffSignupRequestSummaryResponse(Long requestId, String name, String phone,
        SignupRequestAcademyResponse academy, OffsetDateTime requestedAt, long academyStaffCount,
        CurrentStaff currentStaff) {

    /**
     * 그 학원의 재직 관계자(§6.4, Ruling 807) — 정원이 1명이라 객체 하나다. 승인 단추가 꺼진 이유와 푸는 방법(그 사람 퇴사
     * 처리)을 처리 화면에 보이려는 값이라 마지막 로그인 시각까지 싣는다.
     *
     * @param lastLoginAt 로그인한 적 없으면 {@code null}
     */
    public record CurrentStaff(String name, String loginId, OffsetDateTime lastLoginAt) {

        /** 계정에서 재직 관계자 표시값을 옮긴다. */
        public static CurrentStaff from(Account account) {
            return new CurrentStaff(account.getName(), account.getLoginId(), account.getLastLoginAt());
        }
    }

    /** 요청 한 건과 신청자·학원 표시값을 조립한다 — {@code currentStaff} 는 재직 관계자가 없으면 {@code null} 이다. */
    public static StaffSignupRequestSummaryResponse of(SignupRequest request, String name, String phone,
            SignupRequestAcademyResponse academy, long academyStaffCount, CurrentStaff currentStaff) {
        return new StaffSignupRequestSummaryResponse(request.getId(), name, phone, academy,
                request.getRequestedAt(), academyStaffCount, currentStaff);
    }
}
