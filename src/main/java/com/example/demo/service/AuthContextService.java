package com.example.demo.service;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Service;

@Service
public class AuthContextService {

    public String getCurrentUserId() {
        return StpUtil.getLoginIdAsString();
    }

    public String resolveUserId(String requestedUserId) {
        String currentUserId = getCurrentUserId();
        if (requestedUserId == null || requestedUserId.isBlank()) {
            return currentUserId;
        }
        if (!currentUserId.equals(requestedUserId)) {
            throw new IllegalArgumentException("请求中的用户标识与当前登录用户不一致");
        }
        return currentUserId;
    }
}
