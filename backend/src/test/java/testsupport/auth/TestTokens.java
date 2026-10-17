package testsupport.auth;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/** 시험이 {@code Authorization} 헤더에 싣는 {@code Bearer} 접근 토큰을 만드는 공용 도우미 — 시험 클래스마다 복사하던 토큰 메서드의 단일 정의. */
public final class TestTokens {

    private TestTokens() {
    }

    /** 활성 계정의 접근 토큰 — 학원 소속이 없는 계정(메인 관리자)은 {@code academyId} 에 {@code null}. */
    public static String 토큰(JwtTokenProvider tokenProvider, long accountId, Long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    /** 학원 번호에서 계정 번호를 {@code academyId * 1000 + 1} 로 정하는 관계자 토큰 — 학원마다 서로 다른 관계자가 필요한 시험이 쓴다. */
    public static String 관계자_토큰(JwtTokenProvider tokenProvider, long academyId) {
        return 토큰(tokenProvider, academyId * 1000 + 1, academyId, Role.STAFF);
    }

    /** 학원 소속이 없는 메인 관리자 토큰. */
    public static String 메인관리자_토큰(JwtTokenProvider tokenProvider, long accountId) {
        return 토큰(tokenProvider, accountId, null, Role.SYSTEM_ADMIN);
    }
}
