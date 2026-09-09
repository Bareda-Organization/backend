package src.backend.student.dto;

import java.time.OffsetDateTime;

import src.backend.student.entity.LinkCode;

/**
 * 학생이 발급받은 인증 코드(S-05, API_SPEC §3.3 응답 {@code 201}).
 *
 * <p>코드가 응답에 실리는 것은 <b>학생 화면에 보여 주기 위해서</b>이고, 대조는 서버가 한다(§3.4).
 * 학부모 쪽 응답에는 코드가 실리지 않는다 — 실으면 클라이언트가 비교할 수 있게 되어 "서버 인증,
 * 클라이언트 대조 부재" 라는 전제가 깨진다.
 */
public record LinkCodeIssueResponse(String code, OffsetDateTime expiresAt) {

    public static LinkCodeIssueResponse from(LinkCode linkCode) {
        return new LinkCodeIssueResponse(linkCode.getCode(), linkCode.getExpiresAt());
    }
}
