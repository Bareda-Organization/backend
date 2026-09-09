package src.backend.global.config;

/**
 * Swagger 태그 이름 상수. 컨트롤러의 {@code @Tag} 와 {@link OpenApiConfig} 의 태그 설명이
 * 같은 문자열을 가리켜야 Swagger UI 에서 한 그룹으로 묶인다 — 한 글자만 달라도 같은 이름의 그룹이
 * 둘로 갈라지고, 그것이 화면에서만 드러나 컴파일·시험 어디에도 걸리지 않으므로 상수로 고정한다.
 * 구분은 {@code docs/API_SPEC.md} §2~§6 의 도메인 절과 1:1 이다.
 */
public final class ApiTags {

    /** §2 — 로그인·가입 신청·학원 검색. 일부는 비인증 허용. */
    public static final String AUTH = "0. 인증 · 가입";

    /** §3 — 학부모·학생 앱: 자녀 조회, 실시간 위치, 승하차지 변경 요청. */
    public static final String PARENT_STUDENT = "1. 학부모 · 학생 앱";

    /** §4 — 매니저 앱(버스기사·동승자): 담당 회차, 승하차 처리, 운행 시작·종료. */
    public static final String MANAGER = "2. 매니저 앱 (버스기사 · 동승자)";

    /** §5 — 관계자 웹: 학원 단위 가입 승인·구성원 관리, 노선·배차. */
    public static final String STAFF = "3. 관계자 웹";

    /** §6 — 메인 관리자 콘솔: 전 학원 범위, 학원 생성·현황. */
    public static final String ADMIN = "4. 메인 관리자 콘솔";

    /**
     * 개발 도구 — {@code local} 프로파일에서만 뜬다. 사양(§2~§6)에 대응하는 절이 없고 배포물에도 없어
     * 태그를 따로 둔다.
     */
    public static final String DEV = "9. 개발 도구 (local 전용)";

    private ApiTags() {
    }
}
