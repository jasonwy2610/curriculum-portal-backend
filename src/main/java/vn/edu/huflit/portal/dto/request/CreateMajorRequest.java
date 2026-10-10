package vn.edu.huflit.portal.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateMajorRequest {

    @NotBlank(message = "Mã ngành không được để trống")
    private String code;

    @NotBlank(message = "Tên ngành không được để trống")
    private String name;

    private String description;

    private String status; // Mặc định là "ACTIVE" nếu không truyền
}
