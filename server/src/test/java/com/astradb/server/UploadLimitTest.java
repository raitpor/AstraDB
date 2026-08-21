package com.astradb.server;

import com.astradb.server.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SS-9：上传超限（MaxUploadSizeExceededException）→ 413 PAYLOAD_TOO_LARGE 结构化错误体
 * （而非落入全局 500 兜底）。MockMvc 的 multipart 请求不经过容器级大小限制，
 * 故直接验证 ApiExceptionHandler 的映射逻辑。
 */
class UploadLimitTest {

    @Test
    void oversizedUploadMappedTo413() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/importSnapshot");
        ResponseEntity<ApiExceptionHandler.ApiError> resp =
                handler.uploadTooLarge(new MaxUploadSizeExceededException(1024L * 1024), req);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode());
        assertEquals("PAYLOAD_TOO_LARGE", resp.getBody().code());
    }
}
