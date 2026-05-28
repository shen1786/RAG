package com.example.demo.model.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {
    private String userId;
    private String username;
    private String email;
    private String status;
    private List<String> roles;
    private List<String> permissions;
}
