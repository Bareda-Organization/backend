package src.backend.account.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import src.backend.account.entity.ApproverType;
import src.backend.global.common.enums.AccountStatus;

/** 회원가입 응답(API_SPEC §2.2, 201) — 승인 전이라 {@code account_status} 는 항상 {@code pending}. */
public record SignupResponse(
        @JsonProperty("account_status") String accountStatus,
        @JsonProperty("requested_at") OffsetDateTime requestedAt,
        String approver) {

    public static SignupResponse of(AccountStatus status, OffsetDateTime requestedAt, ApproverType approverType) {
        return new SignupResponse(status.name().toLowerCase(), requestedAt, approverType.name().toLowerCase());
    }
}
