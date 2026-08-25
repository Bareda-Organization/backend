package src.backend.account.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import src.backend.account.entity.Account;
import src.backend.account.entity.SignupRequest;
import src.backend.global.common.enums.AccountStatus;

/** 승인 대기 화면 응답(API_SPEC §2.3) — {@code pending}·{@code rejected} 토큰으로 호출한다. */
public record SignupStatusResponse(
        String status,
        AcademySummary academy,
        @JsonProperty("requested_at") OffsetDateTime requestedAt,
        @JsonProperty("reject_reason") String rejectReason,
        @JsonProperty("academy_contact") String academyContact) {

    /** {@code rejected} 가 아니면 {@code reject_reason} 은 언제나 비운다(§2.3 — rejected 일 때만 필드). */
    public static SignupStatusResponse of(Account account, src.backend.academy.entity.Academy academyEntity,
            SignupRequest latest) {
        AcademySummary academy = new AcademySummary(academyEntity.getName(), academyEntity.getRegion(),
                academyEntity.getCode());
        String rejectReason = account.getStatus() == AccountStatus.REJECTED ? latest.getRejectReason() : null;
        return new SignupStatusResponse(account.getStatus().name().toLowerCase(), academy, latest.getRequestedAt(),
                rejectReason, academyEntity.getContact());
    }

    public record AcademySummary(String name, String region, String code) {
    }
}
