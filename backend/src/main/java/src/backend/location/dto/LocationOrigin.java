package src.backend.location.dto;

/**
 * 위치 좌표의 출처. 조회 측이 "단말이 보고한 좌표인지, 서버 Mock 시뮬레이터가 만든 좌표인지"를
 * 구분할 수 있게 기록한다 — 관제 화면의 "단말/시뮬레이터" 표기가 이 값을 그대로 쓴다.
 *
 * <p>단말 보고 경로(학생 앱 {@code POST /api/locations}·기사 앱 {@code POST /api/locations/bus})는
 * 요청 본문의 값을 그대로 저장하고, 생략하면 {@link #GPS} 로 간주한다. 서버에는 좌표를 만든 주체를
 * 확인할 수단이 없으므로 단말이 보낸 값은 <b>자기신고</b>이며 신뢰 판단의 근거로 쓰지 않는다.
 * 반면 서버 내부 Mock 소스가 넣는 {@link #MOCK} 은 서버가 스스로 아는 사실이다.
 */
public enum LocationOrigin {

    /** 단말 GPS — 학생 앱·기사 앱이 실좌표라고 보고한 값(자기신고). */
    GPS,

    /** Mock 시뮬레이터가 생성한 좌표 — 서버의 {@code MockLocationSource}·{@code MockBusLocationSource}, 또는 시뮬레이션 모드로 보고한 단말. */
    MOCK
}
