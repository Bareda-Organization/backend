package testsupport.live;

/**
 * 실 NCP API 시험({@code *LiveTest})이 도는 조건 — 환경변수에 자격증명이 있을 때다(BR-238).
 *
 * <p>자격증명은 gitignore 대상 {@code backend/.env} 에서만 오므로 워크트리·CI 에서는 이 시험들이 조용히
 * 건너뛰어지고, 리포트에는 {@code skipped} 로만 남아 초록과 구별되지 않는다. 그래서 병합 직전 검사처럼
 * "건너뛰면 안 되는" 실행은 {@code -PrequireLive} 를 준다 — 그러면 자격증명이 없을 때 건너뛰는 대신
 * 조건 평가가 예외를 던져 시험이 <b>실패</b>한다. 기본 실행(옵션 없음)은 예전처럼 건너뛴다.
 */
public final class LiveCredentials {

    /** {@code build.gradle} 이 {@code -PrequireLive} 를 이 이름의 시스템 속성으로 넘긴다. */
    static final String REQUIRE_LIVE_PROPERTY = "requireLive";

    private LiveCredentials() {
    }

    /** yml 과 같은 폴백 순서({@code NAVER_MAPS_*} → {@code NAVER_DIRECTIONS_*})로 자격증명을 찾는다. */
    public static boolean available() {
        return available(System.getenv(), Boolean.getBoolean(REQUIRE_LIVE_PROPERTY));
    }

    /** 환경을 인자로 받는 본체 — 환경변수를 바꿀 수 없는 시험에서 두 갈래를 모두 부르려고 갈랐다. */
    static boolean available(java.util.Map<String, String> env, boolean requireLive) {
        boolean present = hasValue(env, "NAVER_MAPS_KEY_ID", "NAVER_DIRECTIONS_KEY_ID")
                && hasValue(env, "NAVER_MAPS_KEY", "NAVER_DIRECTIONS_KEY");
        if (!present && requireLive) {
            throw new IllegalStateException("-PrequireLive 인데 NCP 자격증명이 없다 — backend/.env 에 "
                    + "NAVER_MAPS_KEY_ID/NAVER_MAPS_KEY(또는 NAVER_DIRECTIONS_*)를 두어야 실 API 시험을 건너뛰지 않는다");
        }
        return present;
    }

    private static boolean hasValue(java.util.Map<String, String> env, String preferred, String fallback) {
        return isNotBlank(env.get(preferred)) || isNotBlank(env.get(fallback));
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
