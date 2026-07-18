package src.backend.global.response;

/**
 * 모든 REST 응답을 감싸는 공통 포맷.
 * 성공/실패 여부를 일관되게 내려 프론트가 한 가지 형태로 파싱하도록 한다.
 */
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
