package src.backend.boarding.query;

/**
 * 보호자 연락처를 매니저 앱 표시용으로 가린다(API_SPEC §1.12 L2 마스킹 · §4.2 · NFR-06,
 * Phase 9 목표 6) — 관계자 웹·메인 관리자 콘솔(§5.4·§5.11)은 이 클래스를 거치지 않고 원문을
 * 그대로 내보낸다.
 *
 * <p>형태는 사양이 예시로 못박은 {@code 010-2XXX-8814} <b>하나뿐</b>이다 — 앞자리는 그대로 두고,
 * 가운데 자리는 첫 글자만 남기고 나머지를 {@code X} 로 바꾸며, 마지막 자리는 그대로 둔다.
 * 저장 형식(계정 전화번호, {@code 010-XXXX-XXXX})이 하이픈 3분할이 아니면 마스킹할 자리를
 * 특정할 근거가 없어 원문을 그대로 돌려준다 — 잘못 자른 마스킹은 오히려 없는 자릿수를 드러낸다.
 */
public final class GuardianPhoneMasker {

    private GuardianPhoneMasker() {
    }

    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        String[] parts = phone.split("-");
        if (parts.length != 3 || parts[1].isEmpty()) {
            return phone;
        }
        String middle = parts[1];
        String maskedMiddle = middle.charAt(0) + "X".repeat(middle.length() - 1);
        return parts[0] + "-" + maskedMiddle + "-" + parts[2];
    }
}
