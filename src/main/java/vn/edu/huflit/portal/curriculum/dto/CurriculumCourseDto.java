package vn.edu.huflit.portal.curriculum.dto;

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
public class CurriculumCourseDto {
    private String courseCode;
    private String courseName;
    private Integer credits;
    private String type; // "MANDATORY" | "ELECTIVE"
    private String knowledgeGroupCode;

    @Builder.Default
    private List<String> specializationCodes = new ArrayList<>();

    @Builder.Default
    private List<String> prerequisiteCourseCodes = new ArrayList<>();
}
