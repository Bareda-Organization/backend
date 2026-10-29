package src.backend.student.query;

import java.util.Comparator;

/**
 * 이름의 자연 정렬 — 숫자 덩어리는 크기로, 나머지는 글자 순(대소문자 무시)으로 비교한다("학생2" &lt; "학생10").
 *
 * <p>숫자 덩어리는 앞의 0 을 떼고 <b>자릿수 → 사전순</b>으로 비교한다 — 숫자로 바꿔 비교하면 아주 긴 숫자에서 넘친다.
 * 앞 0 만 다른 이름("학생01" · "학생1")은 서로 같다고 보지 않고 마지막에 원문 순으로 가른다(순서가 정해져야 쪽 경계가 안정된다).
 */
final class NaturalNameOrder {

    static final Comparator<String> INSTANCE = NaturalNameOrder::compare;

    private NaturalNameOrder() {
    }

    private static int compare(String left, String right) {
        int[] position = {0, 0};
        while (position[0] < left.length() && position[1] < right.length()) {
            int result = compareAt(left, right, position);
            if (result != 0) {
                return result;
            }
        }
        int byRemaining = Integer.compare(left.length() - position[0], right.length() - position[1]);
        return byRemaining != 0 ? byRemaining : left.compareTo(right);
    }

    /** 두 위치의 한 덩어리를 견주고 위치를 그 뒤로 옮긴다 — 숫자 덩어리는 크기로, 그 밖은 글자 하나씩 대소문자 없이. 같으면 0. */
    private static int compareAt(String left, String right, int[] position) {
        if (isDigit(left.charAt(position[0])) && isDigit(right.charAt(position[1]))) {
            int start = position[0];
            int otherStart = position[1];
            position[0] = endOfDigits(left, start);
            position[1] = endOfDigits(right, otherStart);
            return compareNumbers(left.substring(start, position[0]), right.substring(otherStart, position[1]));
        }
        int byChar = Character.compare(Character.toLowerCase(left.charAt(position[0])),
                Character.toLowerCase(right.charAt(position[1])));
        position[0]++;
        position[1]++;
        return byChar;
    }

    private static int endOfDigits(String text, int from) {
        int end = from;
        while (end < text.length() && isDigit(text.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int compareNumbers(String left, String right) {
        String trimmedLeft = withoutLeadingZeros(left);
        String trimmedRight = withoutLeadingZeros(right);
        int byLength = Integer.compare(trimmedLeft.length(), trimmedRight.length());
        return byLength != 0 ? byLength : trimmedLeft.compareTo(trimmedRight);
    }

    private static String withoutLeadingZeros(String digits) {
        int first = 0;
        while (first < digits.length() - 1 && digits.charAt(first) == '0') {
            first++;
        }
        return digits.substring(first);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
