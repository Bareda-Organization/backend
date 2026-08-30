package src.backend.run.navigation.spec;

/**
 * 활성 외부 내비 공급자 포트(§7 규칙 12 교체 축) — 서버가 넘길 수 있는 승하차지 개수의 상한만
 * 알려준다(API_SPEC §4.16, Ruling 204). 딥링크 조립은 이 포트의 책임이 아니다 — 그것은
 * OS·앱 버전·스토어 폴백까지 묶인 클라이언트 영역이라 서버가 만들지 않는다.
 *
 * <p>구현체 선택은 {@code app.navigation.provider} 한 곳이 정한다(Ruling 201) — 요청 파라미터로
 * 받지 않는 이유는 공급자를 바꿀 때마다 앱을 새로 배포해야 하는데, 정작 바뀌는 값(상한)은 서버만
 * 아는 것이라 앱이 고를 근거가 없기 때문이다.
 */
public interface NavProvider {

    /** 응답 {@code provider} 필드에 실릴 값. */
    NavProviderName name();

    /**
     * 이 공급자에 한 번에 넘길 수 있는 승하차지(경유지 + 목적지) 개수의 상한.
     *
     * <p>이 값 자체를 어떻게 정했는지는 구현체({@code KakaoNavProvider}) 안에만 있다 — 공급자마다
     * SDK 근거가 달라, 포트 쪽에 숫자를 두면 공급자를 늘릴 때 이 계약이 공급자별 사실을 대신
     * 말하게 된다.
     */
    int maxStops();
}
