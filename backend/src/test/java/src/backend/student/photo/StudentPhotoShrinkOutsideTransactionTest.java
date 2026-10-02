package src.backend.student.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.student.photo.spec.StudentPhoto;

/**
 * 사진을 줄이는 순간 이 스레드에 트랜잭션·연결이 없다(BR-322) — 12MP 사진 한 장의 디코딩·축소·재인코딩이 수백 ms 라 학생 저장
 * 트랜잭션 안에 두면 그 시간만큼 DB 연결을 쥔다. 등록·수정 두 경로를 {@link PhotoResizer#shrink} 감시로 확인한다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 시험이 트랜잭션을 열면 서비스가 합류해 "밖" 이 존재할 수 없다. 만든 행은
 * {@link #뒷정리한다()} 가 직접 지운다. {@code MockMvc} 는 호출한 스레드에서 도므로 정적 메서드 감시가 요청 처리에 닿는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudentPhotoShrinkOutsideTransactionTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    private static final String BASE = "/api/v1/staff/students";

    private static final String NAME_PREFIX = "BR322";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Value("${app.photo.local.root}")
    private String photoRoot;

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM student WHERE name LIKE ?", NAME_PREFIX + "%");
    }

    @Test
    void 등록의_사진_줄이기는_트랜잭션_밖에서_돈다() throws Exception {
        List<TxObservation> observed = new ArrayList<>();

        MvcResult result;
        try (MockedStatic<PhotoResizer> resizer = observing(observed)) {
            result = mockMvc.perform(multipart(BASE).file(데이터_파트("{\"name\":\"" + NAME_PREFIX + "등록\",\"can_go_alone\":false}"))
                            .file(사진_파트(png(1600, 1200)))
                            .header("Authorization", 관계자_토큰()))
                    .andExpect(status().isCreated())
                    .andReturn();
        }

        assertOutside(observed);
        assertThat(저장된_사진의_긴_변(result)).as("저장소에는 줄어든 사진이 넘어간다").isEqualTo(512);
    }

    @Test
    void 수정의_사진_줄이기는_트랜잭션_밖에서_돈다() throws Exception {
        long studentId = 사진_없이_등록한다(NAME_PREFIX + "수정");
        List<TxObservation> observed = new ArrayList<>();

        MvcResult result;
        try (MockedStatic<PhotoResizer> resizer = observing(observed)) {
            result = mockMvc.perform(multipart(HttpMethod.PATCH, BASE + "/" + studentId).file(데이터_파트("{}"))
                            .file(사진_파트(png(1600, 1200)))
                            .header("Authorization", 관계자_토큰()))
                    .andExpect(status().isOk())
                    .andReturn();
        }

        assertOutside(observed);
        assertThat(저장된_사진의_긴_변(result)).as("저장소에는 줄어든 사진이 넘어간다").isEqualTo(512);
    }

    private void assertOutside(List<TxObservation> observed) {
        assertThat(observed).as("줄이기가 실제로 불려야 한다").hasSize(1);
        assertThat(observed).as("줄이기 시점에 트랜잭션·연결·EntityManager 가 없어야 한다")
                .allSatisfy(observation -> assertThat(observation.holdsNothing()).isTrue());
    }

    /** 실제 줄이기를 그대로 돌리되 호출 시점의 트랜잭션 자원을 적는다. */
    private static MockedStatic<PhotoResizer> observing(List<TxObservation> observed) {
        MockedStatic<PhotoResizer> resizer = Mockito.mockStatic(PhotoResizer.class, Mockito.CALLS_REAL_METHODS);
        resizer.when(() -> PhotoResizer.shrink(any(StudentPhoto.class))).thenAnswer(invocation -> {
            observed.add(TxObservation.now());
            return invocation.callRealMethod();
        });
        return resizer;
    }

    private long 사진_없이_등록한다(String name) throws Exception {
        MvcResult result = mockMvc.perform(multipart(BASE).file(데이터_파트("{\"name\":\"" + name + "\",\"can_go_alone\":false}"))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(JsonPath.read(응답(result), "$.data.student_id"));
    }

    private int 저장된_사진의_긴_변(MvcResult result) throws Exception {
        String photoUrl = JsonPath.read(응답(result), "$.data.photo_url");
        byte[] stored = Files.readAllBytes(Path.of(photoRoot, photoUrl.substring(photoUrl.lastIndexOf('/') + 1)));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(stored));
        return Math.max(image.getWidth(), image.getHeight());
    }

    private static String 응답(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MockMultipartFile 데이터_파트(String json) {
        return new MockMultipartFile("data", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile 사진_파트(byte[] content) {
        return new MockMultipartFile("photo", "face.png", "image/png", content);
    }

    private String 관계자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** 호출 시점에 이 스레드에 묶인 트랜잭션 자원 — {@code resources} 가 비어야 연결·EntityManager 가 없다. */
    private record TxObservation(boolean transactionActive, Map<Object, Object> resources) {

        static TxObservation now() {
            return new TxObservation(TransactionSynchronizationManager.isActualTransactionActive(),
                    Map.copyOf(TransactionSynchronizationManager.getResourceMap()));
        }

        boolean holdsNothing() {
            return !transactionActive && resources.isEmpty();
        }
    }
}
