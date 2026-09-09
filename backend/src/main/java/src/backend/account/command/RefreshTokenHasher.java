package src.backend.account.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * refresh 토큰 원문을 SHA-256 해시로 바꾼다 — {@link src.backend.account.entity.RefreshToken}
 * Javadoc 이 명시하듯 원문은 저장하지 않고 이 해시만 DB 에 넣는다.
 */
final class RefreshTokenHasher {

    private RefreshTokenHasher() {
    }

    static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JDK 표준 provider 가 지원해 이 분기는 실질적으로 도달하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
