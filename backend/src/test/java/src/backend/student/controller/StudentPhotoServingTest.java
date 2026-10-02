package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.photo.spec.StudentPhoto;

/**
 * 학생 사진 서빙 {@code GET /files/photos/{fileName}}(BR-214 · {@code Ruling 377}, API_SPEC §5.11.1).
 *
 * <p>사진은 L3 라 무인증 정적 경로로 열지 않는다 — 권한({@code STUDENT_READ_PHOTO}) + 요청자 학원 = 학생 학원이
 * 함께 성립할 때만 내려간다. 타 학원·퇴원·없는 파일은 존재를 드러내지 않고 한 가지 404 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentPhotoServingTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PhotoStorage photoStorage;

    private Long studentId;

    private String fileName;

    @BeforeEach
    void A학원_학생에_사진을_붙인다() {
        studentId = jdbcTemplate.queryForObject(
                "SELECT id FROM student WHERE academy_id = ? AND deleted_at IS NULL ORDER BY id LIMIT 1",
                Long.class, ACADEMY_A);
        String url = photoStorage.store(StudentPhoto.of(PNG));
        fileName = url.substring(url.lastIndexOf('/') + 1);
        jdbcTemplate.update("UPDATE student SET photo_url = ? WHERE id = ?", url, studentId);
    }

    @Test
    void 저장소가_만드는_주소는_api_접두사를_포함한다() {
        String url = photoStorage.store(StudentPhoto.of(PNG));

        assertThat(url).as("photo_url 은 앱이 그대로 요청할 수 있는 경로여야 한다")
                .startsWith("/api/v1/files/photos/");
    }

    @Test
    void 같은_학원_관계자는_사진을_받고_캐시는_private_이다() throws Exception {
        byte[] body = mockMvc.perform(get("/api/v1/files/photos/" + fileName)
                        .header("Authorization", 관계자(ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(PNG);
    }

    /**
     * R46-KFIXBE K-3 — 하루 캐시 + 파일명 ETag. 같은 사진을 화면마다·재방문마다 다시 받지 않는다(학생 목록 한 화면이 행마다 1장 — 60명이면 약
     * 228MB 전송). {@code If-None-Match} 가 일치하면 본문 없이 {@code 304}, 다른 파일의 ETag 면 {@code 200} 이다.
     */
    @Test
    void 사진은_하루_캐시와_파일명_ETag_를_싣고_일치하면_304_이다() throws Exception {
        var first = mockMvc.perform(get("/api/v1/files/photos/" + fileName).header("Authorization", 관계자(ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("private"),
                        org.hamcrest.Matchers.containsString("max-age=86400"))))
                .andReturn().getResponse();
        String eTag = first.getHeader("ETag");
        assertThat(eTag).as("파일명이 UUID 라 내용이 바뀌지 않는다 — 파일명이 ETag").isEqualTo("\"" + fileName + "\"");

        byte[] notModified = mockMvc.perform(get("/api/v1/files/photos/" + fileName)
                        .header("Authorization", 관계자(ACADEMY_A)).header("If-None-Match", eTag))
                .andExpect(status().isNotModified())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(notModified).as("304 는 본문이 없다").isEmpty();

        mockMvc.perform(get("/api/v1/files/photos/" + fileName)
                        .header("Authorization", 관계자(ACADEMY_A)).header("If-None-Match", "\"다른파일.png\""))
                .andExpect(status().isOk());
    }

    /** 304 가 접근 권한을 우회하지 않는다 — 남의 학원 관계자가 올바른 ETag 를 들고 와도 404 다(존재를 드러내지 않는다). */
    @Test
    void ETag_가_일치해도_다른_학원_관계자는_304_가_아니라_404_이다() throws Exception {
        mockMvc.perform(get("/api/v1/files/photos/" + fileName).header("Authorization", 관계자(ACADEMY_B))
                        .header("If-None-Match", "\"" + fileName + "\""))
                .andExpect(status().isNotFound());
    }

    @Test
    void 다른_학원_관계자는_404_STUDENT_NOT_FOUND_이다() throws Exception {
        mockMvc.perform(get("/api/v1/files/photos/" + fileName).header("Authorization", 관계자(ACADEMY_B)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    @Test
    void 퇴원한_학생의_사진은_404_STUDENT_NOT_FOUND_이다() throws Exception {
        jdbcTemplate.update("UPDATE student SET deleted_at = now() WHERE id = ?", studentId);

        mockMvc.perform(get("/api/v1/files/photos/" + fileName).header("Authorization", 관계자(ACADEMY_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    @Test
    void 어떤_학생에도_붙지_않은_파일명은_404_이다() throws Exception {
        mockMvc.perform(get("/api/v1/files/photos/00000000-0000-0000-0000-000000000000.png")
                        .header("Authorization", 관계자(ACADEMY_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    @Test
    void 점점을_품은_파일명은_저장_디렉터리_밖을_읽지_못하고_404_이다() throws Exception {
        mockMvc.perform(get("/api/v1/files/photos/..secret.png").header("Authorization", 관계자(ACADEMY_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    /** Ruling 786 — 메인 관리자는 학원 무관이다(O-06 관제 명단이 학생 사진 주소를 싣는다). */
    @Test
    void 메인_관리자는_어느_학원_학생의_사진이든_받는다() throws Exception {
        byte[] body = mockMvc.perform(get("/api/v1/files/photos/" + fileName).header("Authorization", 메인_관리자()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(PNG);
    }

    /** 학원 무관이 퇴원·부재까지 열어 주는 것은 아니다 — 존재 비노출 404 는 메인 관리자에게도 같다. */
    @Test
    void 메인_관리자도_퇴원한_학생의_사진은_404_STUDENT_NOT_FOUND_이다() throws Exception {
        jdbcTemplate.update("UPDATE student SET deleted_at = now() WHERE id = ?", studentId);

        mockMvc.perform(get("/api/v1/files/photos/" + fileName).header("Authorization", 메인_관리자()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    @Test
    void 메인_관리자도_어떤_학생에도_붙지_않은_파일명은_404_이다() throws Exception {
        mockMvc.perform(get("/api/v1/files/photos/00000000-0000-0000-0000-000000000000.png")
                        .header("Authorization", 메인_관리자()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    @Test
    void 사진_읽기_권한이_없는_학부모는_403_이다() throws Exception {
        String parent = "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/files/photos/" + fileName).header("Authorization", parent))
                .andExpect(status().isForbidden());
    }

    @Test
    void 토큰이_없으면_401_이다() throws Exception {
        mockMvc.perform(get("/api/v1/files/photos/" + fileName)).andExpect(status().isUnauthorized());
    }

    private String 메인_관리자() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private String 관계자(Long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(1L, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }
}
