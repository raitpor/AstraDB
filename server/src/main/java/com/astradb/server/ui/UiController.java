package com.astradb.server.ui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 管理页面路由（Thymeleaf 视图；页面数据经原生 JS 调 REST API 获取）。
 */
@Controller
public class UiController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/table")
    public String table(@RequestParam("name") String name, Model model) {
        model.addAttribute("tableName", name);
        return "table";
    }
}
