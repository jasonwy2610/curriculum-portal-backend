package vn.edu.huflit.portal.curriculum.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "curriculums")
public class Curriculum {

    @Id
    private ObjectId id;

    // Compare-and-set generation, including legacy documents where this field is absent.
    private Long revision;

    @Indexed(unique = true)
    private String curriculumCode;

    private String curriculumName;
    private ObjectId majorId;
    private String cohort;
    private Integer appliedYear;
    private Integer totalCredits;
    // Chi tieu theo CTDT, khong phai tong danh sach hoc phan tu chon.
    private Integer mandatoryCredits;
    private Integer electiveCreditsRequired;
    // Cấu trúc thống nhất; giữ các trường root cũ để tương thích bản ghi trước đây.
    private CreditRequirements creditRequirements;
    // Authoritative per-specialization requirements; legacy root totals remain for compatibility.
    @Builder.Default private java.util.Map<String, TrackCreditPlan> creditPlansBySpecialization = new java.util.LinkedHashMap<>();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TrackCreditPlan {
        private Integer requiredTotal;
        private Integer mandatoryCredits;
        private Integer electiveCredits;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreditRequirements {
        private Integer requiredTotal;
        private Integer mandatoryCredits;
        private Integer electiveCreditsRequired;
        private Integer freeElectiveCredits;
        private Integer specializationElectiveCredits;
        private Integer graduationCredits;
    }

    public Integer resolvedTotalCredits() {
        return creditRequirements != null && creditRequirements.getRequiredTotal() != null
                ? creditRequirements.getRequiredTotal() : totalCredits;
    }

    public Integer resolvedMandatoryCredits() {
        if (creditRequirements != null && creditRequirements.getMandatoryCredits() != null)
            return creditRequirements.getMandatoryCredits();
        if (mandatoryCredits != null) return mandatoryCredits;
        if (creditRequirements != null && creditRequirements.getRequiredTotal() != null
                && creditRequirements.getFreeElectiveCredits() != null
                && creditRequirements.getSpecializationElectiveCredits() != null) {
            int value = creditRequirements.getRequiredTotal() - creditRequirements.getFreeElectiveCredits()
                    - creditRequirements.getSpecializationElectiveCredits();
            return value >= 0 ? value : null;
        }
        return null;
    }

    public Integer resolvedElectiveCreditsRequired() {
        Integer total = resolvedTotalCredits(), mandatory = resolvedMandatoryCredits();
        return total == null || mandatory == null ? null : total - mandatory;
    }

    public void applyCreditPlan(int total, int mandatory) {
        this.totalCredits = total;
        this.mandatoryCredits = mandatory;
        this.electiveCreditsRequired = total - mandatory;
        if (this.creditRequirements == null) this.creditRequirements = new CreditRequirements();
        this.creditRequirements.setRequiredTotal(total);
        this.creditRequirements.setMandatoryCredits(mandatory);
        this.creditRequirements.setElectiveCreditsRequired(total - mandatory);
        // Không tự thay đổi chỉ tiêu phân nhóm đã khai báo.
    }
    private String description;

    // DRAFT | ACTIVE | ARCHIVED | INACTIVE
    private String status;

    private Integer version;

    @Field("clonedFrom")
    private ObjectId clonedFromCurriculumId;

    @Field("specializationTrack")
    private String specializationSelectionTiming; // EARLY | LATER

    @Field("specializationDecisionSemester")
    private Integer specializationSelectionSemester;

    @Builder.Default
    private List<SpecializationItem> specializations = new ArrayList<>();

    @Builder.Default
    private List<KnowledgeGroupItem> knowledgeGroups = new ArrayList<>();

    @Builder.Default
    private List<SemesterItem> semesters = new ArrayList<>();

    // P0: Nhom yeu cau tin chi. CTDT cu chua migrate co danh sach rong.
    @Builder.Default
    private List<RequirementGroupItem> requirementGroups = new ArrayList<>();

    // Each tree references the shared course catalogue by courseCode.
    @Builder.Default
    private java.util.Map<String, SpecializationTree> specializationTrees = new java.util.LinkedHashMap<>();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SpecializationTree {
        @Builder.Default private List<SemesterItem> semesters = new ArrayList<>();
        @Builder.Default private List<RequirementGroupItem> requirementGroups = new ArrayList<>();
    }

    @Data @Builder(toBuilder = true) @NoArgsConstructor @AllArgsConstructor
    public static class RequirementGroupItem {
        private String groupCode;
        private String groupName;
        // MANDATORY_COMMON | MANDATORY_SPECIALIZATION | ELECTIVE_SPECIALIZATION
        // ELECTIVE_FREE | GRADUATION | CONDITIONAL
        private String kind;
        @Builder.Default private List<String> specializationCodes = new ArrayList<>();
        private Integer requiredCredits;
        @Builder.Default private List<String> eligibleCourseCodes = new ArrayList<>();
        @Builder.Default private List<CompletionOptionItem> completionOptions = new ArrayList<>();
        private Boolean countsTowardGraduation;
        private Boolean countsTowardGpa;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompletionOptionItem {
        private String optionCode;
        @Builder.Default private List<String> courseCodes = new ArrayList<>();
    }

    private ObjectId createdBy;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpecializationItem {
        private String code;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgeGroupItem {
        private String groupCode;
        private String groupName;
        private Integer order;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SemesterItem {
        private Integer semesterNumber;

        @Builder.Default
        private List<CurriculumCourseItem> courses = new ArrayList<>();
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurriculumCourseItem {
        private String courseCode;
        private String courseName;
        private Integer credits;
        private String type; // MANDATORY | ELECTIVE
        private String knowledgeGroupCode;
        private String requirementGroupCode;
        private Boolean countsTowardGraduation;
        private Boolean countsTowardGpa;
        private String completionType;

        @Builder.Default
        private List<String> specializationCodes = new ArrayList<>();

        @Builder.Default
        private List<String> prerequisiteCourseCodes = new ArrayList<>();
    }
}
