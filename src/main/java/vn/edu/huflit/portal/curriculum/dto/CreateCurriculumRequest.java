package vn.edu.huflit.portal.curriculum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class CreateCurriculumRequest {
    @NotBlank(message = "Mã chương trình đào tạo không được để trống")
    private String curriculumCode;

    @NotBlank(message = "Tên chương trình đào tạo không được để trống")
    private String curriculumName;

    @NotBlank(message = "Mã ngành (majorId) không được để trống")
    private String majorId;

    @NotBlank(message = "Khóa tuyển sinh (cohort) không được để trống")
    private String cohort;

    @NotNull(message = "Năm áp dụng không được để trống")
    private Integer appliedYear;

    private Integer totalCredits;
    private String description;
    private String status; // "DRAFT" | "ACTIVE" | "ARCHIVED"

    private String specializationSelectionTiming; // "EARLY" | "LATER"
    private Integer specializationSelectionSemester;

    @Builder.Default
    private List<SpecializationDto> specializations = new ArrayList<>();

    @Builder.Default
    private List<KnowledgeGroupDto> knowledgeGroups = new ArrayList<>();

    @Builder.Default
    private List<SemesterDto> semesters = new ArrayList<>();
}
