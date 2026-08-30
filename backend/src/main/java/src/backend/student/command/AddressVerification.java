package src.backend.student.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.student.domain.VerifiedAddressEntry;
import src.backend.student.domain.VerifiedAddressEntry.AddressSlot;
import src.backend.student.domain.VerifiedAddressEntry.AddressText;
import src.backend.student.dto.WeeklyAddressEntryRequest;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;

/**
 * 요청의 주소를 좌표로 옮기고 실패를 사양의 오류 코드로 번역한다(STU-05, API_SPEC §3.7).
 *
 * <p><b>이 클래스에 {@code @Transactional} 이 없고, 저장 담당({@link WeeklyAddressStore})에는
 * {@link GeocodingClient} 가 없다.</b> 둘을 갈라 둔 것이 §7 규칙 16 을 지키는 방식이다 — 한 클래스에
 * 두면 검증을 트랜잭션 안으로 옮겨 놓아도 컴파일되고, 테스트는 스텁으로 돌아 즉시 반환하므로
 * <b>트랜잭션이 네이버 응답 시간만큼 열린 채로 남는 결함이 초록으로 통과</b>한다. 단언으로 잡을 수
 * 없는 것을 구조로 막는다.
 *
 * <p>{@code entries[]} 는 <b>전건 성사 아니면 전건 보류</b>다. 한 칸이라도 검증에 실패하면 어느
 * 것도 저장하지 않는다 — 부분 저장을 하면 {@code 422} 를 받은 사용자가 "무엇이 저장됐는지" 알 수단이
 * 부재하고, {@code §3.7} 이 그 경우의 응답 형태를 규정하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AddressVerification {

    private final GeocodingClient geocodingClient;

    /**
     * 요청의 모든 칸을 검증한다 — 하나라도 실패하면 {@code 422 ADDRESS_VERIFICATION_FAILED} 다.
     *
     * <p>요일 · 방향을 <b>먼저 전부</b> 해석한다. 뒤로 미루면 열 칸을 지오코딩한 뒤 마지막 칸의
     * 오타 하나로 전건이 버려져, 외부 호출 요금과 응답 시간만 쓰고 아무것도 남지 않는다.
     *
     * <p>실패한 주소를 {@code details.failed_entries} 에 담는다. 담지 않으면 14칸을 한 번에 보낸
     * 사용자가 <b>어느 주소를 고쳐야 하는지</b> 알 수 없어 전부를 다시 입력하게 된다.
     */
    public List<VerifiedAddressEntry> verifyAll(List<WeeklyAddressEntryRequest> entries) {
        List<AddressSlot> slots = entries.stream().map(AddressVerification::slotOf).toList();
        List<VerifiedAddressEntry> verified = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            WeeklyAddressEntryRequest entry = entries.get(i);
            AddressSlot slot = slots.get(i);
            geocode(entry.address()).ifPresentOrElse(
                    point -> verified.add(new VerifiedAddressEntry(slot, textOf(entry), point)),
                    () -> failed.add(entry.address()));
        }
        if (!failed.isEmpty()) {
            throw new BusinessException(ErrorCode.ADDRESS_VERIFICATION_FAILED, Map.of("failed_entries", failed));
        }
        return List.copyOf(verified);
    }

    /**
     * 칸 하나만 검증한다(P-06 일일 변경 요청, API_SPEC §3.8) — {@link #verifyAll} 과 실패 응답 모양은
     * 같되(§{@code failed_entries} 에 그 주소 하나), 요일·방향 슬롯이 없어 별도 경로로 둔다.
     */
    public GeocodedPoint verifySingle(String address) {
        return geocode(address)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_VERIFICATION_FAILED,
                        Map.of("failed_entries", List.of(address))));
    }

    /**
     * 공급자에 닿지 못한 것을 {@code 503} 으로 옮긴다 — {@code 422}(주소가 틀림)와 <b>다른 코드</b>다.
     *
     * <p>번역을 이 한 곳에서 하는 이유는 어댑터가 HTTP 상태를 정하지 않게 하기 위함이다
     * ({@link GeocodingUnavailableException} Javadoc).
     */
    private Optional<GeocodedPoint> geocode(String address) {
        try {
            return geocodingClient.geocode(address);
        } catch (GeocodingUnavailableException e) {
            throw new BusinessException(ErrorCode.ADDRESS_VERIFICATION_UNAVAILABLE);
        }
    }

    private static AddressSlot slotOf(WeeklyAddressEntryRequest entry) {
        return new AddressSlot(parse(Weekday.class, entry.weekday()), parse(Direction.class, entry.direction()));
    }

    private static AddressText textOf(WeeklyAddressEntryRequest entry) {
        return new AddressText(entry.address().trim(), entry.addressDetail());
    }

    /** 사양에 없는 요일·방향 값은 조용히 무시하지 않고 {@code 422} 로 거부한다(§9 enum 사전). */
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
