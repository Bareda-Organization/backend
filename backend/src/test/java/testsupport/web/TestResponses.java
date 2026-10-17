package testsupport.web;

import java.nio.charset.StandardCharsets;

import org.springframework.test.web.servlet.MvcResult;

/** MockMvc 응답을 읽는 공용 도우미 — 시험 클래스마다 복사하던 본문 읽기의 단일 정의. */
public final class TestResponses {

    private TestResponses() {
    }

    /** 응답 본문을 UTF-8 문자열로 읽는다. */
    public static String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
