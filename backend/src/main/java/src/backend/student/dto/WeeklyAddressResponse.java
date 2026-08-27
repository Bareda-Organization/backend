package src.backend.student.dto;

import java.util.Comparator;
import java.util.List;

import src.backend.student.entity.WeeklyAddress;

/**
 * 요일별 주소 조회·저장의 공통 응답(API_SPEC §3.7 — "응답은 PATCH 요청과 동일 구조").
 *
 * <p>정렬을 <b>여기서</b> 한다. 저장 컬럼이 {@code 'mon'}·{@code 'tue'} 같은 문자열이라 DB
 * {@code ORDER BY} 는 알파벳순({@code fri} 이 맨 앞)이 되어 화면의 요일 순서와 어긋난다 —
 * enum 선언 순서가 곧 월~일이므로 그것으로 세운다.
 */
public record WeeklyAddressResponse(List<WeeklyAddressEntryResponse> entries) {

    public static WeeklyAddressResponse from(List<WeeklyAddress> addresses) {
        return new WeeklyAddressResponse(addresses.stream()
                .sorted(Comparator.comparing(WeeklyAddress::getWeekday).thenComparing(WeeklyAddress::getDirection))
                .map(WeeklyAddressEntryResponse::from)
                .toList());
    }
}
