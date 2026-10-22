package vn.edu.huflit.portal.curriculum.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCurriculumCourseRequest {
    @Min(value = 1, message = "Học kỳ phải lớn hơn hoặc bằng 1")
    private Integer semesterNumber;

    @Pattern(regexp = "(?i)^(MANDATORY|ELECTIVE)$", message = "Loại học phần phải là MANDATORY hoặc ELECTIVE")
    private String type;

    private String knowledgeGroupCode;

    private List<String> specializationCodes;

    private List<String> prerequisiteCourseCodes;
}
