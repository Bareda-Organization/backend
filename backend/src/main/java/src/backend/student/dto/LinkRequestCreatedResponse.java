package src.backend.student.dto;

import java.time.OffsetDateTime;

import src.backend.student.entity.LinkRequest;

/**
 * 연결 요청 접수 결과(P-02, API_SPEC §3.2 응답 {@code 201}).
 *
 * <p>요청이 <b>자체 식별자와 만료 시각을 반환</b>하는 이 계약이 {@code link_request} 를
 * {@code link_code} 와 별개 레코드로 둔 이유다(ERD §3.2) — 코드가 아직 생기지 않은 시점에
 * 돌려줄 값이 필요하다.
 */
public record LinkRequestCreatedResponse(Long linkRequestId, OffsetDateTime expiresAt) {

    public static LinkRequestCreatedResponse from(LinkRequest linkRequest) {
        return new LinkRequestCreatedResponse(linkRequest.getId(), linkRequest.getExpiresAt());
    }
}
