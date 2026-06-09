package com.datagenerator.web.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 认证关闭时，登录/登出表单仍指向 /api/v1/auth/*，此处提供重定向以避免 404。
 */
@Controller
@ConditionalOnProperty(prefix = "data-generator.auth", name = "enabled", havingValue = "false")
public class AuthDisabledController {

    @PostMapping("/api/v1/auth/login")
    public String login() {
        return "redirect:/";
    }

    @PostMapping("/api/v1/auth/logout")
    public String logout() {
        return "redirect:/";
    }
}
