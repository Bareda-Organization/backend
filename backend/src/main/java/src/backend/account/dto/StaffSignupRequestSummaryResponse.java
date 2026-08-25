package src.backend.account.dto;

import java.time.OffsetDateTime;

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
        SignupRequestAcademyResponse academy, OffsetDateTime requestedAt, long academyStaffCount) {

    public static StaffSignupRequestSummaryResponse of(SignupRequest request, String name, String phone,
            SignupRequestAcademyResponse academy, long academyStaffCount) {
        return new StaffSignupRequestSummaryResponse(request.getId(), name, phone, academy,
                request.getRequestedAt(), academyStaffCount);
    }
}
