package dev.bytebank.common.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(post("/test")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("texto"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("TIPO_CONTENIDO_NO_SOPORTADO"));
    }

    @Test
    void notAcceptableReturns406WithJsonApiError() throws Exception {
        mockMvc.perform(get("/test")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("FORMATO_NO_ACEPTADO"));
    }

    @RestController
    static class TestController {

        @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        Payload echo(@RequestBody Payload payload) {
            return payload;
        }

        @GetMapping(value = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
        Payload get() {
            return new Payload("ok");
        }
    }

    record Payload(String value) {
    }
}
