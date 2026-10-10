package src.backend.global.common.enums;

import java.util.List;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 계정 상태 4종 — {@code account.status}(CHECK 로 강제)의 값 도메인이다.
 *
 * <p>{@link Role} 과 같은 자리({@code global/common/enums})에 둔다 — {@code Role} 이 엔티티
 * ({@code Account})와 {@code AuthUser}(글로벌 보안 계층) 양쪽에서 쓰이는 것과 동일한 이유로,
 * {@code account} 도메인 밖(게이트·JWT)에서도 문자열이 아니라 이 타입으로 상태를 주고받기
 * 위해서다 — 대소문자·오탈자를 컴파일 에러로 만들어, "게이트가 소문자 리터럴을 기대하는데
 * 발급 쪽이 {@code name()}(대문자)을 그대로 넘겨 전부 어긋나는" 사고를 원천 차단한다
 * (Phase 2 Task 2 리뷰 라운드 1 Important #2).
 */
public enum AccountStatus {

    /** 승인 대기. */
    PENDING,
    /** 정상 사용. */
    ACTIVE,
    /** 가입 거절됨. */
    REJECTED,
    /** 반복 실패 등으로 차단됨. */
    BLOCKED;

    /**
     * 알림을 받는 계정 상태 — 로그인 차단({@link #BLOCKED})은 로그인 보안이지 알림 중단이 아니다(Ruling 861 ① · 865). 가입 대기·
     * 거절은 받지 않는다. 비상 알림의 메인 관리자 수신자 선정과 응답 {@code notified} 수가 이 한 곳을 같이 쓴다.
     */
    public static final List<AccountStatus> NOTIFIABLE = List.of(ACTIVE, BLOCKED);

    /** {@link AccountStatus} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<AccountStatus> {
        public Db() {
            super(AccountStatus.class);
        }
    }
}
