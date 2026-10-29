package src.backend.exception.command;

import java.util.Locale;

import src.backend.exception.entity.EmergencyType;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 비상 신고 요청 값의 해석 규칙 — 종류 문자열 변환 · 기타 종류의 메모 필수 · 조치 메모 정리. */
final class EmergencyRequestRules {

    private EmergencyRequestRules() {
    }

    /** 요청의 종류 문자열을 {@link EmergencyType} 으로 바꾼다 — 알 수 없는 값은 {@code VALIDATION_FAILED}. */
    static EmergencyType parseType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "accident" -> EmergencyType.ACCIDENT;
            case "vehicle_fault" -> EmergencyType.VEHICLE_FAULT;
            case "student_emergency" -> EmergencyType.STUDENT_EMERGENCY;
            case "etc" -> EmergencyType.ETC;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }

    /** 종류가 {@code ETC} 이면 메모가 비어 있을 수 없다 — 어긋나면 {@code VALIDATION_FAILED}. */
    static void assertMemoForEtc(EmergencyType type, String memo) {
        if (type == EmergencyType.ETC && (memo == null || memo.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** 조치 메모는 앞뒤 공백을 지우고, 비었으면 메모가 없는 것으로 본다(Ruling 541) — 공백만 있는 문자열을 저장하지 않는다. */
    static String normalizeMemo(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        return memo.strip();
    }
}
