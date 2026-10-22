package vn.edu.huflit.portal.curriculum.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCurriculumRequest {
    @NotBlank(message = "Tên chương trình đào tạo không được để trống")
    private String curriculumName;

    private String majorId;
    private String cohort;
    private Integer appliedYear;
    private Integer totalCredits;
    private String description;
    private String status;

    private String specializationSelectionTiming;
    private Integer specializationSelectionSemester;

    private List<SpecializationDto> specializations;
    private List<KnowledgeGroupDto> knowledgeGroups;
    private List<SemesterDto> semesters;
}
