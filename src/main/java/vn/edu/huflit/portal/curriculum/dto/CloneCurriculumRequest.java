package vn.edu.huflit.portal.curriculum.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloneCurriculumRequest {
    private String newCurriculumCode;
    private String curriculumCode;

    private String newCurriculumName;
    private String curriculumName;

    private String newCohort;
    private String cohort;

    private Integer newAppliedYear;
    private Integer appliedYear;

    private String description;
    private String status; // "DRAFT" | "ACTIVE" | "ARCHIVED"

    public String getTargetCurriculumCode() {
        if (newCurriculumCode != null && !newCurriculumCode.trim().isEmpty()) {
            return newCurriculumCode.trim();
        }
        return (curriculumCode != null && !curriculumCode.trim().isEmpty()) ? curriculumCode.trim() : null;
    }

    public String getTargetCurriculumName() {
        if (newCurriculumName != null && !newCurriculumName.trim().isEmpty()) {
            return newCurriculumName.trim();
        }
        return (curriculumName != null && !curriculumName.trim().isEmpty()) ? curriculumName.trim() : null;
    }

    public String getTargetCohort() {
        if (newCohort != null && !newCohort.trim().isEmpty()) {
            return newCohort.trim();
        }
        return (cohort != null && !cohort.trim().isEmpty()) ? cohort.trim() : null;
    }

    public Integer getTargetAppliedYear() {
        if (newAppliedYear != null) {
            return newAppliedYear;
        }
        return appliedYear;
    }
}
