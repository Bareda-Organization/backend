package src.backend.student.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주소 검색 결과(§5.9 고정 노선 편성 화면) — <b>아직 아무것도 만들지 않은 상태</b>의 후보 한 지점.
 *
 * @param lat 지오코딩이 돌려준 위도. 화면이 지도에 임시 핀으로 찍고, 관계자가 옮길 수 있다
 * @param lng 같은 경도
 * @param displayName 공급자가 정규화한 주소 — 승하차지 표시명의 기본값으로 쓴다
 * @param nearby 이 좌표 50m 안의 기존 승하차지. 비어 있지 않으면 화면이 "이미 있는 자리" 를 알린다
 */
public record StopSearchResponse(BigDecimal lat, BigDecimal lng, String displayName, List<NearbyStop> nearby) {

    /**
     * 근처 승하차지 한 곳.
     *
     * @param distanceM 검색 좌표에서의 거리(m) — 관계자가 "같은 자리인가" 를 판단하는 값이다
     */
    public record NearbyStop(Long stopId, String name, String address, BigDecimal lat, BigDecimal lng,
            int distanceM) {
    }
}
