package vn.edu.huflit.portal.entity;

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
    private String status; // "ACTIVE" | "ARCHIVED"
    private Integer version;
    private ObjectId clonedFrom; // Phục vụ F07 (Clone CTĐT)

    private String specializationTrack; // "FROM_START" | "LATER"
    private Integer specializationDecisionSemester;

    private List<SpecializationItem> specializations;
    private List<KnowledgeGroupItem> knowledgeGroups;
    private List<SemesterItem> semesters;

    private ObjectId createdBy;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;

    @Data
    public static class SpecializationItem {
        private String code;
        private String name;
    }

    @Data
    public static class KnowledgeGroupItem {
        private String groupCode;
        private String groupName;
        private Integer order;
    }

    @Data
    public static class SemesterItem {
        private Integer semesterNumber;
        private List<CurriculumCourseItem> courses;
    }

    @Data
    public static class CurriculumCourseItem {
        private String courseCode;
        private String type; // "MANDATORY" | "ELECTIVE"
        private String knowledgeGroupCode;
        private List<String> specializationCodes; // null hoặc rỗng là môn chung
        private List<String> prerequisiteCourseCodes; // Môn tiên quyết
    }
}