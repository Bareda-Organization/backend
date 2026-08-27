package src.backend.student.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.student.photo.impl.LocalDiskPhotoStorage;
import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.photo.spec.StudentPhoto;

/**
 * 사진 파일과 학생 행이 <b>같은 결말</b>을 맞는지 — 목표 12 의 트랜잭션 경계 조항(브리프 §5).
 *
 * <p><b>이 클래스만 {@code @Transactional} 이 아니다.</b> 나머지 테스트는 롤백으로 끝나 커밋 뒤에만
 * 도는 정리(옛 파일 삭제)가 한 번도 실행되지 않는다 — 그 상태로 단언하면 구현이 있든 없든 통과한다.
 * 대신 만든 행을 {@link #뒷정리한다()} 가 직접 지운다.
 *
 * <p>검사하는 어긋남은 두 방향이고 둘 다 조용하다.
 *
 * <ol>
 *   <li><b>DB 는 커밋됐는데 파일이 부재</b> — 사진 없는 {@code photo_url} 을 가리키는 학생이 남는다</li>
 *   <li><b>파일은 저장됐는데 DB 가 롤백</b> — 아무도 가리키지 않는 파일이 디스크에 쌓인다</li>
 * </ol>
 *
 * <p>저장이 실패한 뒤 <b>학생 행이 남았는지</b>를 SQL 로 직접 읽는다. 응답 코드만 보면 커밋해 놓고
 * 500 을 반환하는 형태와 구별되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudentPhotoTransactionBoundaryTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    private static final String BASE = "/api/v1/staff/students";

    /** 이 클래스가 만든 행만 지우기 위한 이름 접두사 — 시드와 다른 태스크의 행을 밟지 않는다. */
    private static final String NAME_PREFIX = "P5T6경계";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RecordingPhotoStorage photoStorage;

    @Value("${app.photo.local.root}")
    private String photoRoot;

    /**
     * 실제 저장을 그대로 하되 <b>무엇을 저장했는지 기록</b>하고, 원할 때 실패시킨다.
     *
     * <p>기록이 필요한 이유는 롤백된 요청의 응답에 {@code photo_url} 이 실리지 않기 때문이다 — 서버가
     * 어느 파일을 만들었는지 아는 수단이 이것뿐이라, 그 파일이 지워졌는지 확인할 길이 여기서 나온다.
     */
    static class RecordingPhotoStorage implements PhotoStorage {

        private final PhotoStorage delegate;

        private final List<String> stored = new ArrayList<>();

        private boolean failOnStore;

        RecordingPhotoStorage(PhotoStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public String store(StudentPhoto photo) {
            if (failOnStore) {
                throw new IllegalStateException("사진 저장 실패 시뮬레이션");
            }
            String photoUrl = delegate.store(photo);
            stored.add(photoUrl);
            return photoUrl;
        }

        @Override
        public void delete(String photoUrl) {
            delegate.delete(photoUrl);
        }
    }

    @TestConfiguration
    static class RecordingPhotoStorageConfig {

        @Bean
        @Primary
        RecordingPhotoStorage recordingPhotoStorage(LocalDiskPhotoStorage delegate) {
            return new RecordingPhotoStorage(delegate);
        }
    }

    @BeforeEach
    void 기록을_비운다() {
        photoStorage.stored.clear();
        photoStorage.failOnStore = false;
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM student WHERE name LIKE ?", NAME_PREFIX + "%");
    }

    /**
     * 사진 저장이 실패하면 학생 행도 남지 않는다.
     *
     * <p>이것이 어긋나면 사진 없는 {@code photo_url} 을 가진 학생이 명단에 남고, 관계자는 등록이
     * 실패했다고 들은 뒤에도 그 학생을 보게 된다.
     */
    @Test
    void 사진_저장에_실패하면_학생_행이_남지_않는다() throws Exception {
        photoStorage.failOnStore = true;
        String name = NAME_PREFIX + "저장실패";

        mockMvc.perform(multipart(BASE).file(데이터_파트(등록_본문(name, null)))
                        .file(사진_파트(png()))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().is5xxServerError());

        assertThat(학생_수(name))
                .as("저장이 실패했으면 학생 행이 남으면 안 된다")
                .isZero();
    }

    /**
     * 트랜잭션이 되돌아가면 그 사이 저장했던 파일도 지워진다.
     *
     * <p>사양에 없는 성별로 요청을 실패시킨다({@code 422}) — 사진은 이미 저장된 뒤라, 보상 삭제가
     * 없으면 아무도 가리키지 않는 파일이 디스크에 남는다. 그 상태는 응답에도 DB 에도 흔적이 부재해
     * <b>디스크를 직접 보는 것 말고는 관측할 수단이 없다.</b>
     */
    @Test
    void 학생_행이_롤백되면_저장했던_사진_파일이_지워진다() throws Exception {
        mockMvc.perform(multipart(BASE)
                        .file(데이터_파트(등록_본문(NAME_PREFIX + "롤백", "\"gender\":\"자몽\"")))
                        .file(사진_파트(png()))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isUnprocessableEntity());

        assertThat(photoStorage.stored)
                .as("보상 삭제를 검사하려면 저장이 실제로 일어난 뒤여야 한다")
                .hasSize(1);
        assertThat(파일(photoStorage.stored.getFirst()))
                .as("롤백된 요청이 만든 파일은 남으면 안 된다")
                .doesNotExist();
    }

    /**
     * 사진을 바꾸면 옛 파일이 지워진다 — 커밋 뒤에.
     *
     * <p>새 파일이 남아 있는 것을 함께 본다. 옛 파일 삭제만 보면 <b>둘 다 지우는</b> 구현이 통과하고,
     * 그때 학생 상세는 사진 없는 주소를 가리킨다.
     */
    @Test
    void 수정으로_사진을_바꾸면_옛_파일이_지워진다() throws Exception {
        long studentId = 등록한다(NAME_PREFIX + "교체");
        String 옛_파일 = photoStorage.stored.getFirst();

        mockMvc.perform(multipart(HttpMethod.PATCH, BASE + "/" + studentId)
                        .file(데이터_파트("{}"))
                        .file(사진_파트(jpeg()))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk());

        assertThat(photoStorage.stored).hasSize(2);
        assertThat(파일(옛_파일))
                .as("교체된 옛 사진은 지워져야 한다 — 안 지우면 바꿀 때마다 디스크에 쌓인다")
                .doesNotExist();
        assertThat(파일(photoStorage.stored.getLast()))
                .as("새 사진은 남아 있어야 한다")
                .exists();
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private long 등록한다(String name) throws Exception {
        MvcResult result = mockMvc.perform(multipart(BASE).file(데이터_파트(등록_본문(name, null)))
                        .file(사진_파트(png()))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.student_id"));
    }

    private String 등록_본문(String name, String extra) {
        return "{\"name\":\"%s\",\"can_go_alone\":false%s}"
                .formatted(name, extra == null ? "" : "," + extra);
    }

    private MockMultipartFile 데이터_파트(String json) {
        return new MockMultipartFile("data", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile 사진_파트(byte[] content) {
        return new MockMultipartFile("photo", "face.bin", "image/png", content);
    }

    private Integer 학생_수(String name) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM student WHERE name = ?", Integer.class, name);
    }

    private Path 파일(String photoUrl) {
        return Path.of(photoRoot, photoUrl.substring(photoUrl.lastIndexOf('/') + 1));
    }

    private String 관계자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }

    private static byte[] png() {
        byte[] bytes = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, bytes, 0, 8);
        return bytes;
    }

    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        System.arraycopy(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 0, bytes, 0, 3);
        return bytes;
    }
}
