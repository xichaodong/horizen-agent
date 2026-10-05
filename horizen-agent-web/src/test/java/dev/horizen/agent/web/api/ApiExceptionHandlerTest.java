package dev.horizen.agent.web.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

class ApiExceptionHandlerTest {
    @Test
    void disconnectedAsyncResponseDoesNotWriteAnErrorBody() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new FailureController())
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        mvc.perform(get("/disconnected")).andExpect(content().string(""));
        mvc.perform(get("/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("请求处理出现异常，当前结果尚未确认。"));
    }

    @RestController
    static class FailureController {
        @GetMapping("/disconnected")
        String disconnected() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("disconnected client");
        }

        @GetMapping("/failure")
        String failure() throws IOException {
            throw new IOException("unrelated server failure");
        }
    }
}
