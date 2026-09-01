package com.example.opsagent.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 将 Vue History 路由转发到前端入口页面，由客户端路由完成页面渲染。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Controller
public class FrontendPageController {

    @GetMapping({
        "/",
        "/login",
        "/register",
        "/dashboard",
        "/tickets",
        "/tickets/{id}",
        "/notifications",
        "/admin"
    })
    public String index() {
        return "forward:/index.html";
    }
}
