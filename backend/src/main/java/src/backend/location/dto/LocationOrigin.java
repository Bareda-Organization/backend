package src.backend.location.dto;

/**
 * 위치 좌표의 출처. 조회 측이 "실제 GPS 인지 시뮬레이션 값인지"를 구분할 수 있게 기록한다.
 * MVP 단계에서는 MOCK 이 대부분이며, 실 GPS 전환 시 학생 앱이 보고하는 좌표는 GPS 로 남는다.
 */
public enum LocationOrigin {

    /** 학생 스마트폰 GPS(실 연동). */
    GPS,

    /** Mock 시뮬레이터가 생성한 좌표(MVP). */
    MOCK
}
