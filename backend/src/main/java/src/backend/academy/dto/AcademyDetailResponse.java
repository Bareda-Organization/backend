package src.backend.academy.dto;

import java.util.List;
import java.util.Locale;

import src.backend.academy.entity.Academy;

/**
 * 학원 상세(API_SPEC §6.3 GET) — §6.1 항목에 주소·연락처·메모·관계자 목록·운영 지표를 더한 형태다.
 *
 * <p>필드가 12개로 {@code reference.md §20} 의 기준을 넘는다. 목록 항목을 중첩 객체로 품으면 줄어들지만
 * 그러면 응답 JSON 이 §6.1 과 다른 모양이 되고, 사양이 "§6.1 항목 + 추가분" 이라는 <b>평평한</b> 형태를
 * 정하고 있어 그쪽을 따랐다. 두 가지 일을 하고 있어서가 아니라 상세 화면 하나가 그만큼을 요구한다.
 */
public record AcademyDetailResponse(Long id, String code, String name, String region,
        long staffCount, long userCount, String status,
        String address, String contact, String memo,
        List<AcademyStaffAccountResponse> staffAccounts, AcademyStatsResponse stats) {

    public static AcademyDetailResponse from(Academy academy, long staffCount, long userCount,
            List<AcademyStaffAccountResponse> staffAccounts) {
        return new AcademyDetailResponse(academy.getId(), academy.getCode(), academy.getName(),
                academy.getRegion(), staffCount, userCount,
                academy.getStatus().name().toLowerCase(Locale.ROOT),
                academy.getAddress(), academy.getContact(), academy.getMemo(),
                staffAccounts, AcademyStatsResponse.empty());
    }
}
