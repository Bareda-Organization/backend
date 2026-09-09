package src.backend.student.dto;

import java.math.BigDecimal;
import java.util.Locale;

import src.backend.student.entity.WeeklyAddress;

/**
 * 요일 × 방향 한 칸의 저장 결과(API_SPEC §3.7 응답 — {@code entries[]} + 항목별 좌표·검증 여부).
 *
 * <p>{@code stopId} 를 함께 싣는다. 사양 표에 없는 필드이나, 이 값이 없으면 클라이언트도 테스트도
 * <b>"주소만 저장되고 승하차지 매칭을 건너뛴 구현"</b> 과 정상 구현을 구별할 수 없다 — Phase 6 노선
 * 계산의 입력이 바로 이 연결이다(ARCHITECTURE §8.1).
 *
 * <p>요일·방향은 <b>소문자</b>로 되돌린다 — 요청과 같은 값 공간이어야 클라이언트가 보낸 것과 받은
 * 것을 대조할 수 있다(§9 enum 사전).
 */
public record WeeklyAddressEntryResponse(
        String weekday,
        String direction,
        String address,
        String addressDetail,
        BigDecimal lat,
        BigDecimal lng,
        boolean verified,
        Long stopId) {

    public static WeeklyAddressEntryResponse from(WeeklyAddress address) {
        return new WeeklyAddressEntryResponse(
                address.getWeekday().name().toLowerCase(Locale.ROOT),
                address.getDirection().name().toLowerCase(Locale.ROOT),
                address.getAddress(),
                address.getAddressDetail(),
                address.getLat(),
                address.getLng(),
                address.isVerified(),
                address.getStopId());
    }
}
