package vn.edu.huflit.portal.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String tokenType; // "Bearer"
    private String userId;
    private String email;
    private String fullName;
    private String role; // "ADMIN" | "STUDENT"
    private String studentCode;
    private String curriculumId;
}