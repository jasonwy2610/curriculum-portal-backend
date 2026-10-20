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
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "curriculums")
public class Curriculum {
    @Id
    private ObjectId id;

    @Indexed(unique = true)
    private String curriculumCode;

    private String curriculumName;
    private ObjectId majorId;
    private String cohort;
    private Integer appliedYear;
    private Integer totalCredits;
    private String description;
    private String status; // "DRAFT" | "ACTIVE" | "ARCHIVED" | "INACTIVE"
    private Integer version;

    @Field("clonedFrom")
    private ObjectId clonedFromCurriculumId; // Phục vụ F07 (Clone CTĐT)

    @Field("specializationTrack")
    private String specializationSelectionTiming; // "EARLY" | "LATER"

    @Field("specializationDecisionSemester")
    private Integer specializationSelectionSemester;

    @Builder.Default
    private List<SpecializationItem> specializations = new ArrayList<>();

    @Builder.Default
    private List<KnowledgeGroupItem> knowledgeGroups = new ArrayList<>();

    @Builder.Default
    private List<SemesterItem> semesters = new ArrayList<>();

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
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurriculumCourseItem {
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
}
