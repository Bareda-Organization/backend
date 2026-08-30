package src.backend.request.domain;

/**
 * 변경 요청 3구간(API_SPEC §1.6, FEATURE_SPEC C-04) — 회차 출발 시각 기준의 처리 구간이다.
 *
 * <p>이름은 ①②③ 이 아니라 <b>그 구간에서 하는 일</b>로 짓는다. {@code boarding_intent.applied_segment}
 * · {@code change_request.window_segment} 는 DB 에 smallint 로 저장돼 있어 {@link #code()} 로
 * 정수값을 왕복한다.
 */
public enum ChangeWindow {

    /** ① 출발 30분 전까지 — 승인 없이 즉시 반영 + 노선 재최적화. */
    IMMEDIATE((short) 1),
    /** ② 30분 안쪽 ~ 출발 전 — 관리자 승인 경유, 회차당 1회. */
    APPROVAL_REQUIRED((short) 2),
    /** ③ 운행 시작 후(또는 출발 시각 도달) — 노선 변경 불가, 미등원 표시만 즉시 수용. */
    CLOSED((short) 3);

    private final short code;

    ChangeWindow(short code) {
        this.code = code;
    }

    /** DB smallint 컬럼에 저장할 정수값. */
    public short code() {
        return code;
    }
}
