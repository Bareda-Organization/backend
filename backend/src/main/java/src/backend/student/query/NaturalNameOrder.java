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
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            if (isDigit(left.charAt(i)) && isDigit(right.charAt(j))) {
                int start = i;
                int otherStart = j;
                while (i < left.length() && isDigit(left.charAt(i))) {
                    i++;
                }
                while (j < right.length() && isDigit(right.charAt(j))) {
                    j++;
                }
                int byNumber = compareNumbers(left.substring(start, i), right.substring(otherStart, j));
                if (byNumber != 0) {
                    return byNumber;
                }
            } else {
                int byChar = Character.compare(Character.toLowerCase(left.charAt(i)),
                        Character.toLowerCase(right.charAt(j)));
                if (byChar != 0) {
                    return byChar;
                }
                i++;
                j++;
            }
        }
        int byRemaining = Integer.compare(left.length() - i, right.length() - j);
        return byRemaining != 0 ? byRemaining : left.compareTo(right);
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
