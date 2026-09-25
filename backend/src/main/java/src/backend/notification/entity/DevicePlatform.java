package src.backend.notification.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 푸시 수신 단말 플랫폼 3종 — {@code device_token.platform}(CHECK 로 강제)의 값 도메인이다.
 */
public enum DevicePlatform {

    /** 안드로이드 앱 단말. */
    ANDROID,
    /** iOS 앱 단말. */
    IOS,
    /** 웹 브라우저(관계자 웹) 단말. */
    WEB;

    /** {@link DevicePlatform} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<DevicePlatform> {
        public Db() {
            super(DevicePlatform.class);
        }
    }
}
