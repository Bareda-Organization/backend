package src.backend.student.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.11 {@code POST · PATCH /staff/students} 의 사진 업로드 — 목표 12 ①~④(Ruling 159 · 160).
 *
 * <p>이 클래스가 고정하는 것은 <b>요청 형식과 검증 경계</b> 넷이다 — 멀티파트로 올린 사진이
 * {@code photo_url} 로 되돌아오는가 · 사진 없이도 등록되는가 · 이미지 3종 밖이 4xx 인가 ·
 * 5MB 초과가 4xx 인가.
 *
 * <p>응답의 {@code photo_url} 만 보면 <b>요청값을 되돌려주는 구현</b>과 구별되지 않는다. 그래서
 * 저장 디렉터리를 직접 뒤져 파일이 실재하는지 함께 본다 — {@code PhotoStorage.store} 를 no-op 으로
 * 바꾸면 이 단언이 문다.
 *
 * <p>요청 필드는 {@code photo}(파일), 응답·컬럼은 {@code photo_url}(주소)이다(Ruling 160) — 두 값이
 * 같은 것이 아니라서 이름을 가른다. 이름이 같으면 클라이언트가 외부 URL 을 그대로 보내는 경로가 생겨
 * 저장 포트를 우회한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentPhotoUploadTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    private static final String BASE = "/api/v1/staff/students";

    /** 사양이 정한 상한(§1.1) — 경계 <b>바로 위</b>를 두드려야 상한이 다른 값으로 바뀐 것을 잡는다. */
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 로컬 디스크 구현이 파일을 두는 곳 — 저장이 실제로 일어났는지 대조할 유일한 수단이다. */
    @Value("${app.photo.local.root}")
    private String photoRoot;

    @Test
    void 사진과_함께_등록하면_photo_url_이_응답에_실린다() throws Exception {
        MvcResult result = mockMvc.perform(multipart(BASE)
                        .file(데이터_파트("""
                                {"name":"P5T6사진등록","can_go_alone":false}"""))
                        .file(new MockMultipartFile("photo", "face.png", "image/png", png()))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.photo_url").isNotEmpty())
                .andReturn();

        String photoUrl = JsonPath.read(본문(result), "$.data.photo_url");
        assertThat(저장된_파일(photoUrl))
                .as("응답이 가리키는 주소에 실제 파일이 있어야 한다 — 없으면 주소만 만든 구현이다")
                .exists();
    }

    /**
     * 관계자 상세 응답에는 {@code photo_url} 이 <b>있다</b>(§1.12) — 학부모·학생 앱의 부재와 짝이다.
     *
     * <p>등록 응답이 아니라 <b>다시 조회</b>해서 본다. 등록 응답만 보면 컬럼에 저장하지 않고 응답에만
     * 실은 구현이 통과한다.
     */
    @Test
    void 관계자_상세_응답에는_photo_url_이_있다() throws Exception {
        long studentId = 사진과_함께_등록한다("P5T6상세사진", png());

        mockMvc.perform(get(BASE + "/" + studentId).header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photo_url").isNotEmpty());
    }

    /** 사진은 선택 필드다(§5.11 {@code ○}) — 파일 파트를 통째로 빼도 등록이 성립해야 한다. */
    @Test
    void 사진_없이도_등록된다() throws Exception {
        mockMvc.perform(multipart(BASE)
                        .file(데이터_파트("""
                                {"name":"P5T6사진없음","can_go_alone":false}"""))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.photo_url").value((Object) null));
    }

    /**
     * 이미지 3종 밖은 거부한다(§1.1).
     *
     * <p>파일명과 {@code Content-Type} 을 <b>이미지로 위장</b>시킨다 — 클라이언트가 스스로 신고한 값을
     * 믿는 구현이면 이 요청이 통과하고, 그때 서버에는 임의의 바이트열이 이미지로 저장된다.
     */
    @Test
    void jpeg_png_webp_밖의_형식은_거부된다() throws Exception {
        mockMvc.perform(multipart(BASE)
                        .file(데이터_파트("""
                                {"name":"P5T6형식위반","can_go_alone":false}"""))
                        .file(new MockMultipartFile("photo", "face.png", "image/png",
                                "%PDF-1.7 not an image".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 3종은 전부 받는다 — 하나만 보면 나머지 둘을 막는 구현이 통과한다. */
    @Test
    void jpeg_png_webp_는_모두_받는다() throws Exception {
        assertThat(사진과_함께_등록한다("P5T6제이펙", jpeg())).isPositive();
        assertThat(사진과_함께_등록한다("P5T6피엔지", png())).isPositive();
        assertThat(사진과_함께_등록한다("P5T6웹피", webp())).isPositive();
    }

    /**
     * 상한 5MB 초과는 거부한다(§1.1).
     *
     * <p>바로 아래(상한 그대로)가 통과하는 것까지 함께 본다 — 초과만 보면 <b>모든 사진을 거부하는
     * 구현</b>도 통과한다. 경계 양쪽이 필요하다.
     */
    @Test
    void 파일이_5MB_를_넘으면_거부된다() throws Exception {
        mockMvc.perform(multipart(BASE)
                        .file(데이터_파트("""
                                {"name":"P5T6용량초과","can_go_alone":false}"""))
                        .file(new MockMultipartFile("photo", "big.png", "image/png", png(MAX_BYTES + 1)))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(multipart(BASE)
                        .file(데이터_파트("""
                                {"name":"P5T6경계허용","can_go_alone":false}"""))
                        .file(new MockMultipartFile("photo", "edge.png", "image/png", png(MAX_BYTES)))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isCreated());
    }

    /** 수정으로도 사진이 붙는다(STU-03) — 등록만 열려 있으면 사진을 나중에 넣을 수단이 부재하다. */
    @Test
    void 수정으로_사진을_올리면_photo_url_이_바뀐다() throws Exception {
        long studentId = 사진과_함께_등록한다("P5T6수정사진", png());
        String before = 상세의_사진_주소(studentId);

        mockMvc.perform(multipart(HttpMethod.PATCH, BASE + "/" + studentId)
                        .file(데이터_파트("{}"))
                        .file(new MockMultipartFile("photo", "new.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk());

        assertThat(상세의_사진_주소(studentId))
                .as("새 파일이 저장되면 주소도 바뀐다 — 같으면 옛 사진이 그대로 남은 것이다")
                .isNotEqualTo(before);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 관계자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }

    /** JSON 파트 — 사양이 "JSON 파트 + 파일 파트" 로 정한 앞쪽이다(§1.1). */
    private MockMultipartFile 데이터_파트(String json) {
        return new MockMultipartFile("data", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private long 사진과_함께_등록한다(String name, byte[] photo) throws Exception {
        MvcResult result = mockMvc.perform(multipart(BASE)
                        .file(데이터_파트("{\"name\":\"%s\",\"can_go_alone\":false}".formatted(name)))
                        .file(new MockMultipartFile("photo", "face.bin", "image/png", photo))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(JsonPath.read(본문(result), "$.data.student_id"));
    }

    private String 상세의_사진_주소(long studentId) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "/" + studentId)
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(본문(result), "$.data.photo_url");
    }

    private Path 저장된_파일(String photoUrl) {
        return Path.of(photoRoot, photoUrl.substring(photoUrl.lastIndexOf('/') + 1));
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** PNG 서명 8바이트 — 실제 디코딩은 하지 않으므로 머리만 맞으면 된다. */
    private static byte[] png() {
        return png(64);
    }

    private static byte[] png(int size) {
        byte[] bytes = new byte[size];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, bytes, 0, 8);
        return bytes;
    }

    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        System.arraycopy(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 0, bytes, 0, 3);
        return bytes;
    }

    /** WEBP 는 {@code RIFF} 뒤 4바이트 크기를 건너뛰고 {@code WEBP} 가 오는 형태다. */
    private static byte[] webp() {
        byte[] bytes = new byte[64];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }
}
