package com.astradb.server.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 管理页面路由（Thymeleaf 视图；页面数据经原生 JS 调 REST API 获取）。
 */
@Controller
public class UiController {

    /** 建表表单默认 zstd 压缩等级，与 server 配置（astradb.compression-level）同步。 */
    @Value("${astradb.compression-level:3}")
    private int defaultCompressionLevel;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("defaultCompressionLevel", defaultCompressionLevel);
        return "index";
    }

    @GetMapping("/table")
    public String table(@RequestParam("name") String name, Model model) {
        model.addAttribute("tableName", name);
        return "table";
    }
}
