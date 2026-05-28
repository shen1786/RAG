package com.example.demo.Config;

import lombok.Getter;

import java.util.List;

@Getter
public final class AuthSeedProperties {

    private AuthSeedProperties() {
    }

    public static final List<String> USER_PERMISSIONS = List.of(
            "auth:password:change",
            "ai:chat",
            "ai:session:create",
            "ai:session:list",
            "ai:session:delete",
            "ai:session:history",
            "ai:session:extract-profile",
            "ai:multi-turn:chat",
            "document:list",
            "document:status",
            "document:delete",
            "document:delete-status",
            "upload:check",
            "upload:file",
            "upload:batch",
            "upload:chunk:check",
            "upload:chunk:file",
            "upload:chunk:merge"
    );

    public static final List<String> ADMIN_EXTRA_PERMISSIONS = List.of(
            "user:manage",
            "user:password:reset"
    );
}
