package vn.edu.huflit.portal.service;

import vn.edu.huflit.portal.dto.request.LoginRequest;
import vn.edu.huflit.portal.dto.response.LoginResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request);
}