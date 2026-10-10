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
public class UpdateMajorRequest {

    @NotBlank(message = "Tên ngành không được để trống")
    private String name;

    private String description;

    private String status; // "ACTIVE" | "INACTIVE"
}
