package vn.edu.huflit.portal.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.config.JwtTokenProvider;
import vn.edu.huflit.portal.dto.request.LoginRequest;
import vn.edu.huflit.portal.dto.response.LoginResponse;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.repository.UserRepository;
import vn.edu.huflit.portal.service.AuthService;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Email hoặc mật khẩu không chính xác"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Email hoặc mật khẩu không chính xác");
        }

        if ("INACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new RuntimeException("Tài khoản đã bị vô hiệu hóa");
        }

        String token = tokenProvider.generateToken(user.getEmail(), user.getRole(), user.getId().toHexString());

        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId().toHexString())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .studentCode(user.getStudentCode())
                .curriculumId(user.getCurriculumId() != null ? user.getCurriculumId().toHexString() : null)
                .build();
    }
}