package src.backend.student.photo;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * JPEG 의 EXIF 회전값을 읽는다 — JDK 에는 EXIF 읽기가 없어 마커 구간과 TIFF 디렉터리를 직접 훑는다. {@link PhotoResizer} 만 쓴다.
 */
final class ExifOrientation {

    private static final int ORIENTATION_TAG = 0x0112;

    private ExifOrientation() {
    }

    /** JPEG 의 EXIF 회전값(1~8) — EXIF 가 없거나 이 태그가 없으면 1(돌리지 않음). 깨진 구조는 예외를 던져 호출부가 원본을 둔다. */
    static int of(byte[] jpeg) {
        int position = 2; // SOI(FFD8) 다음부터 마커 구간을 훑는다
        while (position + 4 <= jpeg.length && (jpeg[position] & 0xFF) == 0xFF) {
            int marker = jpeg[position + 1] & 0xFF;
            int length = unsigned16(jpeg, position + 2, false);
            if (marker == 0xDA) {
                break; // 이미지 데이터 시작 — 메타데이터는 이 앞에만 있다
            }
            if (marker == 0xE1 && startsWithExif(jpeg, position + 4)) {
                return tiffOrientation(jpeg, position + 10, Math.min(position + 2 + length, jpeg.length));
            }
            position += 2 + length;
        }
        return 1;
    }

    private static boolean startsWithExif(byte[] bytes, int offset) {
        byte[] exif = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        return offset + exif.length <= bytes.length
                && Arrays.equals(bytes, offset, offset + exif.length, exif, 0, exif.length);
    }

    private static int tiffOrientation(byte[] bytes, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little = bytes[tiff] == 'I';
        int directory = tiff + unsigned32(bytes, tiff + 4, little);
        if (directory + 2 > end) {
            return 1;
        }
        int entries = unsigned16(bytes, directory, little);
        for (int i = 0; i < entries; i++) {
            int entry = directory + 2 + i * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (unsigned16(bytes, entry, little) == ORIENTATION_TAG) {
                return unsigned16(bytes, entry + 8, little);
            }
        }
        return 1;
    }

    /** 4바이트 오프셋 — 사진 파일 안의 위치라 int 범위를 넘지 않는다(넘으면 음수가 되어 호출부의 범위 검사에서 걸린다). */
    private static int unsigned32(byte[] bytes, int offset, boolean little) {
        int low = unsigned16(bytes, little ? offset : offset + 2, little);
        int high = unsigned16(bytes, little ? offset + 2 : offset, little);
        return (high << 16) | low;
    }

    private static int unsigned16(byte[] bytes, int offset, boolean little) {
        int first = bytes[offset] & 0xFF;
        int second = bytes[offset + 1] & 0xFF;
        return little ? (second << 8) | first : (first << 8) | second;
    }
}
