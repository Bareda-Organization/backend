package src.backend.run.navigation.spec;

/**
 * 외부 내비게이션 공급자 값 도메인 — 응답 {@code provider} 필드가 이 값을 그대로 싣는다
 * (API_SPEC §4.16 · 용어집 {@code nav_provider} · Ruling 201).
 *
 * <p>{@code TMAP} 은 <b>자리만 둔다</b> — 스킴 규격이 미공개라 상한이 1(목적지만)이고, MVP 는
 * {@code KAKAO} 단독이다(Ruling 204). 구현체가 없다고 값까지 지우면 응답 스펙이 향후 공급자를
 * 미리 알리는 용도를 잃는다.
 */
public enum NavProviderName {

    /** 카카오내비 — MVP 유일 구현({@link src.backend.run.navigation.impl.KakaoNavProvider}). */
    KAKAO,
    /** 티맵 — 미구현. 앱 실행 스킴의 경유지 파라미터에 공식 근거가 없어 어댑터를 만들지 않는다. */
    TMAP
}
