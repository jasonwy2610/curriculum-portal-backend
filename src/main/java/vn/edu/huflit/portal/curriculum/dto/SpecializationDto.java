package vn.edu.huflit.portal.curriculum.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecializationDto {
    @NotBlank(message = "Mã chuyên ngành không được để trống")
    private String code;

    @NotBlank(message = "Tên chuyên ngành không được để trống")
    private String name;
}
