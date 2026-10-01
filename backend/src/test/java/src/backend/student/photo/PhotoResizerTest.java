package src.backend.student.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import src.backend.student.photo.spec.StudentPhoto;

/**
 * R46-KFIXBE K-3 — 업로드 사진은 긴 변 512px 로 줄여 저장한다. 목록 한 화면이 행마다 사진 한 장(약 4MB)을 받던 것을 줄이는 수정이다.
 * ImageIO 가 못 읽거나(WebP · 깨진 파일) 이미 작으면 원본 그대로이고, 휴대폰 사진의 회전 정보(EXIF)는 지켜서 옆으로 누운 사진을 만들지 않는다.
 */
class PhotoResizerTest {

    @Test
    void 큰_PNG_는_긴_변이_512px_로_줄고_형식은_그대로다() throws Exception {
        StudentPhoto shrunk = PhotoResizer.shrink(StudentPhoto.of(png(2000, 1000)));

        BufferedImage image = decode(shrunk);
        assertThat(image.getWidth()).isEqualTo(512);
        assertThat(image.getHeight()).as("가로세로 비율을 지킨다").isEqualTo(256);
        assertThat(shrunk.extension()).isEqualTo("png");
    }

    @Test
    void 큰_JPEG_도_같은_크기로_줄고_JPEG_로_남는다() throws Exception {
        StudentPhoto shrunk = PhotoResizer.shrink(StudentPhoto.of(jpeg(700, 1400, 0)));

        BufferedImage image = decode(shrunk);
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(512);
        assertThat(shrunk.extension()).isEqualTo("jpg");
    }

    @Test
    void 이미_작거나_못_읽는_사진은_원본_그대로다() throws Exception {
        StudentPhoto small = StudentPhoto.of(png(400, 300));
        assertThat(PhotoResizer.shrink(small)).as("512px 이하는 다시 인코딩하지 않는다").isSameAs(small);

        // PNG 서명만 있고 본문이 깨진 파일 — 검증(서명)은 통과하지만 ImageIO 가 못 읽는다
        StudentPhoto broken = StudentPhoto.of(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4});
        assertThat(PhotoResizer.shrink(broken)).isSameAs(broken);
    }

    /** 세로로 찍은 휴대폰 사진은 픽셀이 가로로 저장되고 EXIF 가 "90도 돌려 보라" 고 적는다 — 그것을 지우고 줄이면 사진이 누워 보인다. */
    @Test
    void EXIF_회전_정보를_따라_돌려서_줄인다() throws Exception {
        StudentPhoto shrunk = PhotoResizer.shrink(StudentPhoto.of(jpeg(1400, 700, 6)));

        BufferedImage image = decode(shrunk);
        assertThat(image.getWidth()).as("90도 돌려 세로 사진이 된다").isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(512);
    }

    /** 거울상 회전(2·4·5·7)은 다루지 않는다 — 줄이다 틀리느니 원본을 둔다. */
    @Test
    void 거울상_EXIF_사진은_원본_그대로다() throws Exception {
        StudentPhoto mirrored = StudentPhoto.of(jpeg(1400, 700, 2));

        assertThat(PhotoResizer.shrink(mirrored)).isSameAs(mirrored);
    }

    /**
     * 신뢰할 수 없는 업로드를 디코딩하는 것은 처음이다 — 5MB 이하 파일도 해상도가 크면 디코딩에 수백 MB 가 든다(압축 폭탄). 픽셀 수가 상한을
     * 넘으면 디코딩하지 않고 원본을 둔다.
     */
    @Test
    void 픽셀_수가_상한을_넘는_사진은_디코딩하지_않고_원본_그대로다() throws Exception {
        BufferedImage huge = new BufferedImage(6500, 6500, BufferedImage.TYPE_BYTE_GRAY); // 42,250,000 픽셀 > 상한 40,000,000
        StudentPhoto original = StudentPhoto.of(encode(huge, "png"));

        assertThat(PhotoResizer.shrink(original)).isSameAs(original);
    }

    private static byte[] png(int width, int height) throws IOException {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png");
    }

    /** 지정한 EXIF 회전값({@code orientation} 0 이면 EXIF 없음)을 단 JPEG — 픽셀은 {@code width × height} 그대로 저장한다. */
    private static byte[] jpeg(int width, int height, int orientation) throws IOException {
        byte[] plain = encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
        if (orientation == 0) {
            return plain;
        }
        // TIFF(빅엔디안) 헤더 + IFD0 항목 1개(태그 0x0112, SHORT, 1개, 값) + 다음 IFD 없음
        byte[] app1Body = {'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1,
                0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        int segmentLength = app1Body.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write(segmentLength >> 8);
        out.write(segmentLength & 0xFF);
        out.write(app1Body);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(StudentPhoto photo) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(photo.content()));
    }
}
