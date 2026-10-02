package src.backend.student.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpMethod;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/** 학생 등록·수정의 {@code note}({@code text} 컬럼)는 이웃 DTO 와 같은 200자 상한이다(BR-257) — 넘으면 422. */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.transaction.annotation.Transactional
class StudentNoteLengthTest {

    private static final String NOTE_201 = "가".repeat(201);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void 등록_note_201자는_422_이다() throws Exception {
        perform(HttpMethod.POST, "/api/v1/staff/students",
                "{\"name\":\"이름\",\"can_go_alone\":false,\"note\":\"" + NOTE_201 + "\"}");
    }

    @Test
    void 수정_note_201자는_422_이다() throws Exception {
        perform(HttpMethod.PATCH, "/api/v1/staff/students/999999", "{\"note\":\"" + NOTE_201 + "\"}");
    }

    private void perform(HttpMethod method, String path, String data) throws Exception {
        mockMvc.perform(multipart(method, path)
                        .file(new MockMultipartFile("data", "", "application/json", data.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer "
                                + tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}
