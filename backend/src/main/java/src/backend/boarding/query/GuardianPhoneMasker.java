package src.backend.boarding.query;

/**
 * 보호자 연락처를 매니저 앱 표시용으로 가린다(API_SPEC §1.12 L2 마스킹 · §4.2 · NFR-06,
 * Phase 9 목표 6) — 관계자 웹·메인 관리자 콘솔(§5.4·§5.11)은 이 클래스를 거치지 않고 원문을
 * 그대로 내보낸다.
 *
 * <p>형태는 사양 예시 {@code 010-2XXX-8814} — 앞자리·끝 4자리는 그대로, 가운데 자리는 첫 글자만 남긴다.
 * 저장 형식은 하이픈이 없거나 공백이 섞일 수 있어(가입·관계자 수정 경로가 형식을 강제하지 않음, BR-034)
 * 숫자만 뽑아 자릿수로 자른다 — 앞자리는 {@code 02} 면 2자리, 그 밖은 3자리. 가운데가 3~4자리가 아니면
 * 자를 근거가 없으므로 원문 대신 {@link #FULLY_MASKED} 를 돌려준다(가리지 못하면 전부 가린다).
 */
public final class GuardianPhoneMasker {

    /** 형식을 해석하지 못한 번호의 표시값 — 자릿수도 드러내지 않는다. */
    static final String FULLY_MASKED = "XXX-XXXX-XXXX";

    private GuardianPhoneMasker() {
    }

    /** 매니저 앱에 싣는 마스킹 값 — {@code null} 은 그대로 {@code null}. */
    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        int prefixLength = digits.startsWith("02") ? 2 : 3;
        int middleLength = digits.length() - prefixLength - 4;
        if (middleLength < 3 || middleLength > 4) {
            return FULLY_MASKED;
        }
        String middle = digits.substring(prefixLength, prefixLength + middleLength);
        return digits.substring(0, prefixLength) + "-" + middle.charAt(0) + "X".repeat(middleLength - 1) + "-"
                + digits.substring(prefixLength + middleLength);
    }
}
