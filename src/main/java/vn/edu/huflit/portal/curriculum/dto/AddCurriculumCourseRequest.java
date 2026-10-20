package vn.edu.huflit.portal.curriculum.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddCurriculumCourseRequest {
    @NotBlank(message = "Mã học phần không được để trống")
    private String courseCode;

    private String courseName; // Tùy chọn (tự động đồng bộ từ Course nếu không gửi)
    private Integer credits;    // Tùy chọn (tự động đồng bộ từ Course nếu không gửi)

    @NotNull(message = "Học kỳ không được để trống")
    @Min(value = 1, message = "Học kỳ phải lớn hơn hoặc bằng 1")
    private Integer semesterNumber;

    @NotBlank(message = "Loại học phần không được để trống")
    @Pattern(regexp = "(?i)^(MANDATORY|ELECTIVE)$", message = "Loại học phần phải là MANDATORY hoặc ELECTIVE")
    private String type; // "MANDATORY" | "ELECTIVE"

    private String knowledgeGroupCode;

    @Builder.Default
    private List<String> specializationCodes = new ArrayList<>();

    @Builder.Default
    private List<String> prerequisiteCourseCodes = new ArrayList<>();
}
