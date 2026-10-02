package src.backend.student.photo;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import src.backend.student.photo.spec.StudentPhoto;

/**
 * 업로드 사진을 긴 변 {@value #MAX_EDGE}px 로 줄인다(R46-KFIXBE K-3, Ruling 705) — 학생 목록 한 화면이 행마다 사진 한 장(약 4MB)을 받아 60명이면
 * 약 228MB 가 전송됐다. 표시 크기는 작아 원본 해상도가 필요한 화면이 없다. 새 의존성 없이 JDK {@code ImageIO} 만 쓴다.
 *
 * <p><b>줄이지 못하면 원본 그대로다</b> — {@code ImageIO} 가 읽지 못하는 형식(CMYK JPEG · 깨진 파일), 이미 작은 사진, 디코딩이 너무 무거운 사진,
 * 다루지 않는 EXIF 회전. 줄이기가 업로드를 막는 이유가 되면 안 된다. 이미 저장된 파일은 바꾸지 않는다(개발 단계).
 *
 * <p><b>신뢰할 수 없는 입력을 디코딩하는 첫 자리</b>다 — 5MB 이하 파일도 해상도가 크면 디코딩에 수백 MB 가 든다(압축 폭탄). 헤더만 읽어 픽셀 수가
 * {@value #MAX_DECODE_PIXELS} 를 넘으면 디코딩하지 않는다.
 *
 * <p><b>휴대폰 JPEG 의 EXIF 회전을 지킨다</b> — 세로 사진은 픽셀이 가로로 저장되고 EXIF 가 "돌려 보라" 고 적는다. 다시 인코딩하면 그 정보가 사라져
 * 사진이 누워 보인다. 회전 3 · 6 · 8 은 돌려서 줄이고, 거울상(2 · 4 · 5 · 7)은 드물어 원본을 둔다.
 *
 * <p><b>WebP 도 줄인다</b>(R47 Ruling 745) — JDK 기본 {@code ImageIO} 는 못 읽어 원본(최대 5MB)이 그대로 저장됐다. 읽기 전용 플러그인
 * (TwelveMonkeys {@code imageio-webp})이 읽고, 쓰기는 JDK 에 없어 JPEG(투명 배경이면 PNG)로 저장한다. 같은 헤더 크기 검사가 디코딩 앞에 서 있다.
 */
public final class PhotoResizer {

    private static final Logger log = LoggerFactory.getLogger(PhotoResizer.class);

    /** 긴 변 상한(px) — 정책 상수라 코드에 둔다({@code StudentPhoto} 의 5MB 와 같은 이유). */
    static final int MAX_EDGE = 512;

    /** 디코딩을 허용하는 최대 픽셀 수 — 4천만 화소는 래스터 약 160MB 다. 일반 휴대폰 사진(약 1,200만)은 넉넉히 들어온다. */
    static final long MAX_DECODE_PIXELS = 40_000_000L;

    private static final int EXIF_ORIENTATION_TAG = 0x0112;

    private PhotoResizer() {
    }

    /** 줄인 사진을 돌려준다 — 줄이지 않기로 했거나 줄이지 못했으면 받은 객체 그대로다. */
    public static StudentPhoto shrink(StudentPhoto photo) {
        try {
            return resize(photo).orElse(photo);
        } catch (IOException | RuntimeException e) {
            log.warn("[photo] 사진을 줄이지 못해 원본을 저장한다", e);
            return photo;
        }
    }

    private static Optional<StudentPhoto> resize(StudentPhoto photo) throws IOException {
        int orientation = "jpg".equals(photo.extension()) ? exifOrientation(photo.content()) : 1;
        if (orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7) {
            return Optional.empty();
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(photo.content()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (Math.max(width, height) <= MAX_EDGE || (long) width * height > MAX_DECODE_PIXELS) {
                    return Optional.empty();
                }
                BufferedImage reduced = reduce(orient(reader.read(0), orientation));
                String extension = storedExtension(photo.extension(), reduced);
                return encode(reduced, extension).map(bytes -> new StudentPhoto(extension, bytes));
            } finally {
                reader.dispose();
            }
        }
    }

    /** 긴 변이 상한의 2배 아래가 될 때까지 절반으로 줄이고 마지막에 맞춘다 — 한 번에 크게 줄이면 계단·물결무늬가 생긴다. */
    private static BufferedImage reduce(BufferedImage source) {
        BufferedImage image = source;
        while (Math.max(image.getWidth(), image.getHeight()) >= 2 * MAX_EDGE) {
            image = scale(image, image.getWidth() / 2, image.getHeight() / 2);
        }
        double ratio = (double) MAX_EDGE / Math.max(image.getWidth(), image.getHeight());
        return scale(image, Math.max(1, (int) Math.round(image.getWidth() * ratio)),
                Math.max(1, (int) Math.round(image.getHeight() * ratio)));
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, typeOf(source));
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** EXIF 회전값 3(180도) · 6(시계 90도) · 8(반시계 90도)만 돌린다. 그 밖의 값은 그대로다. */
    private static BufferedImage orient(BufferedImage source, int orientation) {
        int width = source.getWidth();
        int height = source.getHeight();
        AffineTransform transform = switch (orientation) {
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> null;
        };
        if (transform == null) {
            return source;
        }
        boolean quarterTurn = orientation != 3;
        BufferedImage target = new BufferedImage(quarterTurn ? height : width, quarterTurn ? width : height,
                typeOf(source));
        var graphics = target.createGraphics();
        try {
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static int typeOf(BufferedImage source) {
        return source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    /**
     * 줄인 사진을 저장할 형식 — 대개 받은 형식 그대로다. WebP 는 JDK 에 쓰기가 없어(읽기만 플러그인이 한다) JPEG 로, 투명 배경이 있으면 알파를
     * 지키려 PNG 로 저장한다. 확장자가 바뀌면 응답 Content-Type 도 그 확장자를 따른다({@link StudentPhoto#contentTypeOf}).
     */
    private static String storedExtension(String received, BufferedImage reduced) {
        if (!"webp".equals(received)) {
            return received;
        }
        return reduced.getColorModel().hasAlpha() ? "png" : "jpg";
    }

    private static Optional<byte[]> encode(BufferedImage image, String extension) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png".equals(extension) ? "png" : "jpg", out);
        return written ? Optional.of(out.toByteArray()) : Optional.empty();
    }

    /** JPEG 의 EXIF 회전값(1~8) — EXIF 가 없거나 이 태그가 없으면 1(돌리지 않음). 깨진 구조는 예외를 던져 호출부가 원본을 둔다. */
    static int exifOrientation(byte[] jpeg) {
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
            if (unsigned16(bytes, entry, little) == EXIF_ORIENTATION_TAG) {
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
