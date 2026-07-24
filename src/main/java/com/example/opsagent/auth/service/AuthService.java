package com.example.opsagent.auth.service;

import com.example.opsagent.auth.dto.LoginRequest;
import com.example.opsagent.auth.dto.LoginResponse;
import com.example.opsagent.auth.vo.AuthUserVO;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    AuthUserVO me(String authorization);

    void logout(String authorization);

    void register(LoginRequest request);
}
