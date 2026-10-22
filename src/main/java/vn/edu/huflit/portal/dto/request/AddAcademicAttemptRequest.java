package vn.edu.huflit.portal.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddAcademicAttemptRequest {

    @NotBlank(message = "semesterCode không được để trống")
    private String semesterCode;

    @NotNull(message = "score10 không được để trống")
    @DecimalMin(value = "0.0", message = "score10 phải >= 0")
    @DecimalMax(value = "10.0", message = "score10 phải <= 10")
    private Double score10;

    private Integer attemptNumber;
}
