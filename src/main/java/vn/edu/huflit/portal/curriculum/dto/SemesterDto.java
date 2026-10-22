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
public class SemesterDto {
    private Integer semesterNumber;

    @Builder.Default
    private List<CurriculumCourseDto> courses = new ArrayList<>();
}
