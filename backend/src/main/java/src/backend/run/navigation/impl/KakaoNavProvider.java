package src.backend.run.navigation.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import src.backend.run.navigation.spec.NavProvider;
import src.backend.run.navigation.spec.NavProviderName;

/**
 * 카카오내비 어댑터 — MVP 유일 구현(2026-08-31 확정, Ruling 204).
 *
 * <p>{@code 4} 는 카카오 SDK 레퍼런스 {@code navigateIntent(destination, option, viaList)} 의
 * {@code viaList} 가 <b>"경유지 목록(최대: 3개)"</b> 로 명시한 값에 목적지 1을 더한 것이다 — 앱에서
 * 사용자가 손으로 넣을 수 있는 경유지 수(5)와는 다른 값이라(API_SPEC §4.16 경고), 그 값과 헷갈리지
 * 않도록 이 클래스 밖으로 내보내지 않는다(목표 22). {@code application.yml} 로 빼지 않는 이유는
 * 이 값이 배포 환경이 아니라 <b>SDK 벤더</b>가 정하는 사실이라, 인프라 값처럼 다루면 값을 바꿀 자리가
 * "그 자리가 아닌 곳"에 생기기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "app.navigation.provider", havingValue = "kakao", matchIfMissing = true)
public class KakaoNavProvider implements NavProvider {

    private static final int MAX_STOPS = 4;

    @Override
    public NavProviderName name() {
        return NavProviderName.KAKAO;
    }

    @Override
    public int maxStops() {
        return MAX_STOPS;
    }
}
