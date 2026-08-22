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
 * 管理页面路由契约（MockMvc）：压缩等级默认值随 server 配置（astradb.compression-level）注入建表表单。
 */
@SpringBootTest(properties = {"astradb.data-dir=target/ui-contract-data", "astradb.security.enabled=false"})
@AutoConfigureMockMvc
class UiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void indexRendersDefaultCompressionLevel() throws Exception {
        // 默认配置 astradb.compression-level=3 → 建表表单压缩等级默认值 = 3
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"tlevel\"")))
                .andExpect(content().string(containsString("value=\"3\"")));
    }

    @Test
    void tablePageRendersTableName() throws Exception {
        mockMvc.perform(get("/table").param("name", "demo"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("const tableName = 'demo';")));
    }
}
