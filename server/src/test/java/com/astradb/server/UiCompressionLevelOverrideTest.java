package com.astradb.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 压缩等级默认值随 server 配置覆盖（ASTRA_DB_COMPRESSION_LEVEL）同步到页面。
 */
@SpringBootTest(properties = {
        "astradb.data-dir=target/ui-override-data",
        "astradb.security.enabled=false",
        "astradb.compression-level=10"})
@AutoConfigureMockMvc
class UiCompressionLevelOverrideTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void indexRendersOverriddenCompressionLevel() throws Exception {
        // 配置覆盖为 10 → 建表表单压缩等级默认值 = 10（不再硬编码 3）
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"tlevel\"")))
                .andExpect(content().string(containsString("value=\"10\"")));
    }
}
