package src.backend.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.student.geocoding.impl.StubGeocodingClient;

/**
 * 학원 등록·주소 수정이 주소를 좌표로 옮겨 저장하는지(BR-202 · {@code Ruling 374}, API_SPEC §6.2·§6.3).
 *
 * <p>좌표를 채우는 운영 경로가 없으면 콘솔로 만든 학원의 회차 확정이 전부
 * {@code ACADEMY_COORDINATES_MISSING} 으로 실패한다. 지오코딩은 결정론적 스텁이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAcademyCoordinatesTest {

    /** 번지가 붙어 스텁이 좌표로 옮길 수 있는 주소. */
    private static final String GEOCODABLE = "테헤란로 152";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Test
    void 주소를_넣어_등록하면_좌표가_함께_저장된다() throws Exception {
        long id = 등록한다("BR202좌표학원", GEOCODABLE, 201);

        assertThat(학원(id).hasCoordinates()).as("등록 때 주소가 좌표로 옮겨져야 회차 확정이 가능하다").isTrue();
    }

    @Test
    void 좌표로_옮길_수_없는_주소는_422_이고_학원이_저장되지_않는다() throws Exception {
        long before = academyRepository.count();

        mockMvc.perform(post("/api/v1/admin/academies").header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문("BR202실패학원", "번지없는주소")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_FAILED"));

        assertThat(academyRepository.count()).as("검증 실패는 저장 보류").isEqualTo(before);
    }

    @Test
    void 주소를_바꾸면_좌표가_새_주소로_다시_구해진다() throws Exception {
        long id = 등록한다("BR202수정학원", GEOCODABLE, 201);
        var before = 학원(id).getLat();

        mockMvc.perform(patch("/api/v1/admin/academies/" + id).header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"테헤란로 190\"}"))
                .andExpect(status().isOk());

        assertThat(학원(id).getLat()).as("번지가 다르면 스텁 위도가 다르다").isNotEqualByComparingTo(before);
    }

    @Test
    void 바뀐_주소를_좌표로_옮길_수_없으면_422_이고_옛_주소와_좌표가_남는다() throws Exception {
        long id = 등록한다("BR202보류학원", GEOCODABLE, 201);

        mockMvc.perform(patch("/api/v1/admin/academies/" + id).header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"번지없는주소\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_FAILED"));

        Academy kept = 학원(id);
        assertThat(kept.getAddress()).isEqualTo(GEOCODABLE);
        assertThat(kept.hasCoordinates()).isTrue();
    }

    /** {@code Ruling 450} — 주소 없는 학원은 첫 운행 날 회차 확정이 전부 실패하므로 등록 때 막는다. */
    @Test
    void 주소_없이_등록하면_422_이고_학원이_저장되지_않는다() throws Exception {
        long before = academyRepository.count();

        mockMvc.perform(post("/api/v1/admin/academies").header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BR450무주소학원\",\"region\":\"서울\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(academyRepository.count()).isEqualTo(before);
    }

    @Test
    void 공백만_있는_주소로_등록하면_422_이다() throws Exception {
        long before = academyRepository.count();

        mockMvc.perform(post("/api/v1/admin/academies").header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문("BR450공백등록학원", "   ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(academyRepository.count()).isEqualTo(before);
    }

    @Test
    void 지오코딩_공급자_장애는_503_이다() throws Exception {
        mockMvc.perform(post("/api/v1/admin/academies").header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문("BR202장애학원", StubGeocodingClient.UNAVAILABLE_MARKER + "로 1")))
                .andExpect(status().isServiceUnavailable());
    }

    /** {@code Ruling 450} — 주소는 필수다. 비우는 수정을 통과시키면 그 학원의 회차 확정이 전부 막힌다. */
    @Test
    void 주소를_비우는_수정은_422_이고_옛_주소와_좌표가_남는다() throws Exception {
        long id = 등록한다("BR202비움학원", GEOCODABLE, 201);

        mockMvc.perform(patch("/api/v1/admin/academies/" + id).header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        Academy kept = 학원(id);
        assertThat(kept.getAddress()).isEqualTo(GEOCODABLE);
        assertThat(kept.hasCoordinates()).as("거부된 수정이 좌표를 지우면 안 된다").isTrue();
    }

    @Test
    void 공백만_있는_주소로_수정해도_422_이다() throws Exception {
        long id = 등록한다("BR450공백학원", GEOCODABLE, 201);

        mockMvc.perform(patch("/api/v1/admin/academies/" + id).header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(학원(id).getAddress()).isEqualTo(GEOCODABLE);
    }

    @Test
    void 주소를_보내지_않거나_null_이면_기존_주소와_좌표를_그대로_둔다() throws Exception {
        long id = 등록한다("BR450유지학원", GEOCODABLE, 201);

        for (String body : new String[] {"{\"contact\":\"02-111-1111\"}", "{\"address\":null,\"contact\":\"02-222-2222\"}"}) {
            mockMvc.perform(patch("/api/v1/admin/academies/" + id).header("Authorization", 관리자())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            Academy kept = 학원(id);
            assertThat(kept.getAddress()).as(body).isEqualTo(GEOCODABLE);
            assertThat(kept.hasCoordinates()).as(body).isTrue();
        }
    }

    /** 관계자 웹은 이름만 고쳐도 폼 전체(주소 포함)를 보낸다 — 같은 주소를 다시 보낸 수정이 좌표를 지우면 안 된다. */
    @Test
    void 같은_주소를_다시_보낸_수정은_좌표를_그대로_둔다() throws Exception {
        long id = 등록한다("BR450같은주소학원", GEOCODABLE, 201);
        var before = 학원(id).getLat();

        mockMvc.perform(patch("/api/v1/admin/academies/" + id).header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BR450이름만바꿈\",\"address\":\"" + GEOCODABLE + "\"}"))
                .andExpect(status().isOk());

        Academy kept = 학원(id);
        assertThat(kept.hasCoordinates()).as("같은 주소를 다시 보냈다고 좌표를 지우면 확정이 막힌다").isTrue();
        assertThat(kept.getLat()).isEqualByComparingTo(before);
    }

    @Test
    void 주소를_보내지_않은_수정은_좌표를_건드리지_않는다() throws Exception {
        long id = 등록한다("BR202이름만학원", GEOCODABLE, 201);

        mockMvc.perform(patch("/api/v1/admin/academies/" + id).header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contact\":\"02-000-0000\"}"))
                .andExpect(status().isOk());

        assertThat(학원(id).hasCoordinates()).isTrue();
    }

    private Academy 학원(long id) {
        return academyRepository.findById(id).orElseThrow();
    }

    private long 등록한다(String name, String address, int expectedStatus) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/academies").header("Authorization", 관리자())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(name, address)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(JsonPath.read(body, "$.data.academy_id"));
    }

    private String 본문(String name, String address) {
        return "{\"name\":\"" + name + "\",\"region\":\"서울\",\"address\":\"" + address + "\"}";
    }

    private String 관리자() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }
}
