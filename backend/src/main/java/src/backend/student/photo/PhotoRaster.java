package src.backend.student.photo;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;

import src.backend.student.photo.spec.StudentPhoto;

/**
 * {@link PhotoResizer} 가 쓰는 래스터 단계 — 정수 배 하위 표본 읽기 · 절반씩 줄이기 · EXIF 회전 · 저장 형식 고르기 · 인코딩. 정책 상수(긴 변 상한)는
 * {@link PhotoResizer} 한 곳에 있다.
 */
final class PhotoRaster {

    private PhotoRaster() {
    }

    /**
     * 긴 변이 {@code 2 × PhotoResizer.MAX_EDGE} 이상 남는 가장 큰 정수 배로 건너뛰며 읽게 한다(BR-302) — 원본 해상도 래스터를 만들지 않으니 줄이기 한 건의
     * 메모리가 원본 크기가 아니라 약 1,024 ~ 2,048px 에 묶인다. 2배 이상의 표본을 남겨야 {@link #reduce} 의 마지막 맞춤이 평균을 내 계단이 눈에 띄지 않는다.
     */
    static ImageReadParam subsamplingFor(ImageReader reader, int width, int height) {
        int step = Math.max(1, Math.max(width, height) / (2 * PhotoResizer.MAX_EDGE));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(step, step, 0, 0);
        return param;
    }

    /** 긴 변이 상한의 2배 아래가 될 때까지 절반으로 줄이고 마지막에 맞춘다 — 한 번에 크게 줄이면 계단·물결무늬가 생긴다. */
    static BufferedImage reduce(BufferedImage source) {
        BufferedImage image = source;
        while (Math.max(image.getWidth(), image.getHeight()) >= 2 * PhotoResizer.MAX_EDGE) {
            image = scale(image, image.getWidth() / 2, image.getHeight() / 2);
        }
        double ratio = (double) PhotoResizer.MAX_EDGE / Math.max(image.getWidth(), image.getHeight());
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
    static BufferedImage orient(BufferedImage source, int orientation) {
        AffineTransform transform = transformFor(orientation, source.getWidth(), source.getHeight());
        if (transform == null) {
            return source;
        }
        boolean quarterTurn = orientation != 3;
        BufferedImage target = new BufferedImage(quarterTurn ? source.getHeight() : source.getWidth(),
                quarterTurn ? source.getWidth() : source.getHeight(), typeOf(source));
        var graphics = target.createGraphics();
        try {
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static AffineTransform transformFor(int orientation, int width, int height) {
        return switch (orientation) {
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> null;
        };
    }

    private static int typeOf(BufferedImage source) {
        return source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    /**
     * 줄인 사진을 저장할 형식 — 대개 받은 형식 그대로다. WebP 는 JDK 에 쓰기가 없어(읽기만 플러그인이 한다) JPEG 로, 투명 배경이 있으면 알파를
     * 지키려 PNG 로 저장한다. 확장자가 바뀌면 응답 Content-Type 도 그 확장자를 따른다({@link StudentPhoto#contentTypeOf}).
     */
    static String storedExtension(String received, BufferedImage reduced) {
        if (!"webp".equals(received)) {
            return received;
        }
        return reduced.getColorModel().hasAlpha() ? "png" : "jpg";
    }

    static Optional<byte[]> encode(BufferedImage image, String extension) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png".equals(extension) ? "png" : "jpg", out);
        return written ? Optional.of(out.toByteArray()) : Optional.empty();
    }
}
