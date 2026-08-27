package src.backend.student.photo.spec;

import java.util.Arrays;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 저장 포트에 넘기는 학생 사진 한 장 — 형식·크기 검증을 통과한 것만 이 타입이 된다
 * (API_SPEC §1.1 · §5.11 · STU-02·03).
 *
 * <p>{@code MultipartFile} 을 그대로 {@link PhotoStorage} 에 넘기지 않는 이유는 그러면 S3 같은
 * 다음 구현체가 서블릿 타입을 알아야 하기 때문이다 — 교체 축은 <b>어디에 두느냐</b>이지 요청을 어떻게
 * 받았느냐가 아니다(횡단 규칙 12).
 *
 * <p>상한과 허용 형식이 이 클래스의 상수인 것은 <b>사양이 정한 정책값</b>이라서다(횡단 규칙 10) —
 * 설정으로 빼면 운영에서 5MB 가 조용히 다른 값이 된다. 저장 <b>위치</b>는 환경마다 달라 설정이고,
 * 그쪽은 {@code LocalDiskPhotoStorage} 가 받는다.
 */
public record StudentPhoto(String extension, byte[] content) {

    /** API_SPEC §1.1 이 정한 상한 5MB. */
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private static final byte[] RIFF_SIGNATURE = {'R', 'I', 'F', 'F'};

    /** {@code RIFF} 4바이트와 길이 4바이트 뒤에 오는 형식 태그 — WEBP 는 여기가 {@code WEBP} 다. */
    private static final byte[] WEBP_TAG = {'W', 'E', 'B', 'P'};

    private static final int WEBP_TAG_OFFSET = 8;

    /**
     * 올라온 바이트열을 검증해 사진으로 만든다 — 형식·상한 위반은 {@code 422 VALIDATION_FAILED} 다
     * (§8.5 "필수 누락 · 형식 위반").
     *
     * <p>형식을 <b>내용의 서명</b>으로 가른다. 클라이언트가 신고한 {@code Content-Type} 과 확장자는
     * 요청자가 정하는 값이라, 그것을 믿으면 {@code face.png} 로 이름 붙인 임의의 바이트열이 이미지로
     * 저장되고 나중에 그 주소가 브라우저로 그대로 서빙된다.
     *
     * @throws BusinessException 이미지 3종(jpeg · png · webp)이 아니거나 5MB 를 넘을 때
     */
    public static StudentPhoto of(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new StudentPhoto(extensionOf(content), content);
    }

    private static String extensionOf(byte[] content) {
        if (startsWith(content, PNG_SIGNATURE, 0)) {
            return "png";
        }
        if (startsWith(content, JPEG_SIGNATURE, 0)) {
            return "jpg";
        }
        if (startsWith(content, RIFF_SIGNATURE, 0) && startsWith(content, WEBP_TAG, WEBP_TAG_OFFSET)) {
            return "webp";
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private static boolean startsWith(byte[] content, byte[] signature, int offset) {
        if (content.length < offset + signature.length) {
            return false;
        }
        return Arrays.equals(content, offset, offset + signature.length, signature, 0, signature.length);
    }
}
