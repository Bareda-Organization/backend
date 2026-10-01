package src.backend.student.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.photo.spec.StudentPhoto;

/** R46-KFIXBE K-3 — 저장 경로가 사진을 줄여서 저장소에 넘긴다(저장소 구현체와 무관하게 — S3 로 바뀌어도 같다). */
class StudentPhotoWriterTest {

    @Test
    void 저장소에는_긴_변이_512px_로_줄어든_사진이_넘어간다() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB), "png", png);
        StudentPhoto[] received = new StudentPhoto[1];
        PhotoStorage recording = new PhotoStorage() {
            @Override
            public String store(StudentPhoto photo) {
                received[0] = photo;
                return "/api/v1/files/photos/x.png";
            }

            @Override
            public void delete(String photoUrl) {
            }

            @Override
            public Optional<Resource> read(String fileName) {
                return Optional.empty();
            }
        };

        new StudentPhotoWriter(recording).store(StudentPhoto.of(png.toByteArray()));

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(received[0].content()));
        assertThat(Math.max(stored.getWidth(), stored.getHeight())).isEqualTo(512);
    }
}
