package src.backend.student.geocoding.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;

/**
 * 테스트·오프라인용 결정론적 지오코딩 — 같은 주소는 <b>언제나</b> 같은 좌표를 낸다.
 *
 * <p>난수나 시각을 섞지 않는 이유는 근접 병합 단언 때문이다. 좌표가 실행마다 갈리면 "근접한 두
 * 주소가 한 승하차지로 묶인다" 가 어느 날은 참이고 어느 날은 거짓이 되어, 그 초록이 코드에 대해
 * 아무것도 말하지 않는다(Ruling 157 — 판정 수단은 재현 가능해야 한다).
 *
 * <h2>계약 — 주소를 "도로명 + 번지" 로 읽는다</h2>
 *
 * <ul>
 *   <li><b>끝에 번지(정수)가 없으면 결과 0건</b>({@link Optional#empty()}) — 실 공급자의
 *       {@code totalCount == 0} 을 재현한다. {@code 422 ADDRESS_VERIFICATION_FAILED}(저장 보류)를
 *       테스트가 밟을 수 있어야 하므로 스텁이 "항상 성공" 이면 안 된다</li>
 *   <li><b>{@value #UNAVAILABLE_MARKER} 가 들어 있으면 공급자 장애</b>
 *       ({@link GeocodingUnavailableException}) — 결과 0건과 <b>다른</b> 실패다. 이 입력이 없으면
 *       "주소가 틀렸다({@code 422})" 와 "지금 못 부른다({@code 503})" 가 갈려 있는지 확인할 수단이
 *       부재해, 둘을 한 코드로 뭉갠 구현이 그대로 통과한다</li>
 *   <li><b>위도는 번지가 정한다</b> — 번지 1 차이가 약 {@value #LAT_STEP_METERS}m 라, 번지가 이웃한
 *       두 주소는 근접 병합 대상이고 번지가 열 남짓 벌어지면 별개 승하차지가 된다</li>
 *   <li><b>경도는 도로명이 정한다</b> — 도로명이 다르면 수백 m 떨어져 절대 묶이지 않는다</li>
 * </ul>
 *
 * <p>주소 문자열이 곧 좌표라는 이 성질이 있어야 "같은 주소 → 같은 승하차지" · "근접 주소 → 같은
 * 승하차지" · "먼 주소 → 다른 승하차지" 를 <b>입력만 바꿔</b> 가려낼 수 있다.
 */
@Component
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "stub")
public class StubGeocodingClient implements GeocodingClient {

    /**
     * 이 문자열이 들어간 주소는 공급자 장애로 답한다 — 네트워크 오류·서킷 개방을 재현하는 입력이다.
     *
     * <p>실 주소에 나타날 수 없는 말이라 오탐이 부재하다. 상수로 노출하는 것은 테스트가 이 값을 손으로
     * 옮겨 적지 않게 하기 위함이다.
     */
    public static final String UNAVAILABLE_MARKER = "지오코딩장애";

    /**
     * 주소 끝의 번지 — 앞을 도로명으로, 뒤 정수를 번지로 가른다.
     *
     * <p>자릿수를 9로 묶는다. 주소가 {@code varchar(255)} 라 숫자만 200자를 적어 보낼 수 있고, 상한이
     * 없으면 {@code Integer.parseInt} 가 터져 검증 실패가 {@code 422} 가 아니라 {@code 500} 이 된다.
     */
    private static final Pattern ROAD_AND_NUMBER = Pattern.compile("^(.*?)\\s*(\\d{1,9})\\s*$");

    /** 좌표를 서울 도심 부근에 두기 위한 기준점 — 값 자체에 의미는 없고 CHECK 범위 안이면 된다. */
    private static final BigDecimal BASE_LAT = new BigDecimal("37.500000");

    private static final BigDecimal BASE_LNG = new BigDecimal("126.500000");

    /** 번지 1 차이가 만드는 위도 간격 — {@code 0.0001} 도는 약 11m 다. */
    private static final BigDecimal LAT_PER_HOUSE_NUMBER = new BigDecimal("0.000100");

    /** 위 간격의 미터 환산값 — Javadoc 이 근접 병합 임계와 견줄 수 있도록 상수로 둔다. */
    private static final int LAT_STEP_METERS = 11;

    /** 도로명 1 차이가 만드는 경도 간격 — {@code 0.01} 도는 위도 37.5 에서 약 880m 다. */
    private static final BigDecimal LNG_PER_ROAD = new BigDecimal("0.010000");

    /** 좌표를 유효 범위 안에 가두는 나머지 연산의 법 — 번지·도로명이 아무리 커도 기준점 부근에 남는다. */
    private static final int COORDINATE_WRAP = 1000;

    /** {@code stop.lat}·{@code lng} 가 {@code numeric(9,6)} 이라 여기서 미리 맞춘다. */
    private static final int COORDINATE_SCALE = 6;

    @Override
    public Optional<GeocodedPoint> geocode(String address) {
        if (address.contains(UNAVAILABLE_MARKER)) {
            throw new GeocodingUnavailableException("스텁 장애 재현: " + address, null);
        }
        Matcher parsed = ROAD_AND_NUMBER.matcher(address.trim());
        if (!parsed.matches()) {
            return Optional.empty();
        }
        String roadName = parsed.group(1);
        int houseNumber = Math.floorMod(Integer.parseInt(parsed.group(2)), COORDINATE_WRAP);
        return Optional.of(new GeocodedPoint(
                shifted(BASE_LAT, LAT_PER_HOUSE_NUMBER, houseNumber),
                shifted(BASE_LNG, LNG_PER_ROAD, Math.floorMod(roadName.hashCode(), COORDINATE_WRAP)),
                address.trim()));
    }

    private static BigDecimal shifted(BigDecimal base, BigDecimal step, int multiplier) {
        return base.add(step.multiply(BigDecimal.valueOf(multiplier))).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }
}
