package vn.edu.huflit.portal.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCourseRequest {

    @NotBlank(message = "Mã học phần không được để trống")
    private String courseCode;

    @NotBlank(message = "Tên học phần không được để trống")
    private String courseName;

    @NotNull(message = "Số tín chỉ không được để trống")
    @Min(value = 1, message = "Số tín chỉ phải lớn hơn hoặc bằng 1")
    private Integer credits;

    @NotNull(message = "Số giờ lý thuyết không được để trống")
    @Min(value = 0, message = "Số giờ lý thuyết phải lớn hơn hoặc bằng 0")
    private Integer theoryHours;

    @NotNull(message = "Số giờ thực hành không được để trống")
    @Min(value = 0, message = "Số giờ thực hành phải lớn hơn hoặc bằng 0")
    private Integer practiceHours;

    private String description;

    private String department;

    private String status; // Mặc định "ACTIVE" nếu không truyền
}
