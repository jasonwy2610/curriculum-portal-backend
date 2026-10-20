package vn.edu.huflit.portal.curriculum.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.edu.huflit.portal.curriculum.model.Curriculum;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumResponse {
    private String id;
    private String curriculumCode;
    private String curriculumName;
    private String majorId;
    private String cohort;
    private Integer appliedYear;
    private Integer totalCredits;
    private String description;
    private String status;
    private Integer version;
    private String clonedFromCurriculumId;
    private String specializationSelectionTiming;
    private Integer specializationSelectionSemester;

    @Builder.Default
    private List<SpecializationDto> specializations = new ArrayList<>();

    @Builder.Default
    private List<KnowledgeGroupDto> knowledgeGroups = new ArrayList<>();

    @Builder.Default
    private List<SemesterDto> semesters = new ArrayList<>();

    private String createdBy;
    private Date createdAt;
    private Date updatedAt;

    public static CurriculumResponse fromEntity(Curriculum entity) {
        if (entity == null) {
            return null;
        }

        List<SpecializationDto> specs = entity.getSpecializations() == null ? new ArrayList<>() :
                entity.getSpecializations().stream()
                        .map(s -> SpecializationDto.builder()
                                .code(s.getCode())
                                .name(s.getName())
                                .build())
                        .collect(Collectors.toList());

        List<KnowledgeGroupDto> groups = entity.getKnowledgeGroups() == null ? new ArrayList<>() :
                entity.getKnowledgeGroups().stream()
                        .map(g -> KnowledgeGroupDto.builder()
                                .groupCode(g.getGroupCode())
                                .groupName(g.getGroupName())
                                .order(g.getOrder())
                                .build())
                        .collect(Collectors.toList());

        List<SemesterDto> semList = entity.getSemesters() == null ? new ArrayList<>() :
                entity.getSemesters().stream()
                        .map(sem -> SemesterDto.builder()
                                .semesterNumber(sem.getSemesterNumber())
                                .courses(sem.getCourses() == null ? new ArrayList<>() :
                                        sem.getCourses().stream()
                                                .map(c -> CurriculumCourseDto.builder()
                                                        .courseCode(c.getCourseCode())
                                                        .courseName(c.getCourseName())
                                                        .credits(c.getCredits())
                                                        .type(c.getType())
                                                        .knowledgeGroupCode(c.getKnowledgeGroupCode())
                                                        .specializationCodes(c.getSpecializationCodes() == null ? new ArrayList<>() : new ArrayList<>(c.getSpecializationCodes()))
                                                        .prerequisiteCourseCodes(c.getPrerequisiteCourseCodes() == null ? new ArrayList<>() : new ArrayList<>(c.getPrerequisiteCourseCodes()))
                                                        .build())
                                                .collect(Collectors.toList()))
                                .build())
                        .collect(Collectors.toList());

        return CurriculumResponse.builder()
                .id(entity.getId() != null ? entity.getId().toHexString() : null)
                .curriculumCode(entity.getCurriculumCode())
                .curriculumName(entity.getCurriculumName())
                .majorId(entity.getMajorId() != null ? entity.getMajorId().toHexString() : null)
                .cohort(entity.getCohort())
                .appliedYear(entity.getAppliedYear())
                .totalCredits(entity.getTotalCredits())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .version(entity.getVersion())
                .clonedFromCurriculumId(entity.getClonedFromCurriculumId() != null ? entity.getClonedFromCurriculumId().toHexString() : null)
                .specializationSelectionTiming(entity.getSpecializationSelectionTiming())
                .specializationSelectionSemester(entity.getSpecializationSelectionSemester())
                .specializations(specs)
                .knowledgeGroups(groups)
                .semesters(semList)
                .createdBy(entity.getCreatedBy() != null ? entity.getCreatedBy().toHexString() : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
