package src.backend.student.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import src.backend.student.photo.impl.LocalDiskPhotoStorage;

/** 사진 읽기가 저장 디렉터리 밖을 열지 못하는지(BR-214 · API_SPEC §5.11.1 "파일명 경로 이탈 거부"). */
class LocalDiskPhotoStorageReadTest {

    @TempDir
    Path temp;

    @Test
    void 저장_디렉터리_밖의_파일은_경로_이탈_이름으로_읽히지_않는다() throws Exception {
        Path root = Files.createDirectory(temp.resolve("photos"));
        Files.write(temp.resolve("secret.txt"), new byte[] {1, 2, 3});
        Files.write(root.resolve("ok.png"), new byte[] {9});
        LocalDiskPhotoStorage storage = new LocalDiskPhotoStorage(root.toString(), "/api/v1/files/photos");

        assertThat(storage.read("ok.png")).contains(new byte[] {9});
        assertThat(storage.read("../secret.txt")).as("상위 디렉터리").isEmpty();
        assertThat(storage.read("..\\secret.txt")).as("역슬래시").isEmpty();
        assertThat(storage.read("sub/ok.png")).as("경로 구분자").isEmpty();
        assertThat(storage.read("..")).isEmpty();
    }
}
