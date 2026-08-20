package com.astradb.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全契约测试：鉴权（未认证 401/认证 200/错误密码 401/表单登录/health 放行/静态放行）。
 */
@SpringBootTest(properties = {"astradb.data-dir=target/sec-contract-data",
        "astradb.security.enabled=true", "astradb.security.username=admin", "astradb.security.password=test123"})
@AutoConfigureMockMvc
class SecurityContractTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/listTables").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/listTables").contentType("application/json").content("{}")
                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                .encodeToString("admin:test123".getBytes())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/listTables").contentType("application/json").content("{}")
                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                .encodeToString("admin:wrong".getBytes())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void formLoginAndPublicEndpoints() throws Exception {
        // health 放行
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        // 静态资源放行
        mvc.perform(get("/js/app.js")).andExpect(status().isOk());
        mvc.perform(get("/css/style.css")).andExpect(status().isOk());
        // 未认证访问页面 → 401（无会话）；表单登录后可访问
        mvc.perform(get("/")).andExpect(status().isUnauthorized());
        // 生产配置禁用 CSRF（无状态 Basic 认证）：登录页不应渲染 _csrf 字段，POST /login 直接可用
        String loginPage = mvc.perform(get("/login")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(loginPage.contains("_csrf"),
                "CSRF 已禁用，登录页不应渲染 _csrf 字段");
        mvc.perform(post("/login").param("username", "admin").param("password", "test123"))
                .andExpect(status().is3xxRedirection());
    }
}
