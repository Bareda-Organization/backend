package src.backend.exception.dto;

import java.time.OffsetDateTime;

import src.backend.exception.entity.NoShowContact;
import src.backend.global.common.LowerCaseFormatter;

/**
 * 미승차 케이스의 연락 시도 한 줄(API_SPEC §4.2 {@code no_show_case.contacts[]}, §4.8, Ruling 823) — 수단 · 결과 · 시도 시각만 싣는다. 메모
 * 필드는 없다(기록 시트에 입력 칸이 없는 값). {@code attemptType} · {@code result} 는 소문자 문자열이다.
 */
public record NoShowContactView(String attemptType, String result, OffsetDateTime attemptedAt) {

    /** 연락 시도 엔티티를 응답 한 줄로 옮긴다. */
    public static NoShowContactView from(NoShowContact contact) {
        return new NoShowContactView(LowerCaseFormatter.lower(contact.getAttemptType().name()),
                LowerCaseFormatter.lower(contact.getResult().name()), contact.getAttemptedAt());
    }
}
