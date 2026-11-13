package vn.edu.huflit.portal.curriculum.service;

import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/** Immutable recognition record. Never update an existing document when a rule changes. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "recognitionEvidence")
@CompoundIndex(name = "student_curriculum_rule_unique", def = "{'studentId':1,'curriculumId':1,'ruleId':1}", unique = true)
public class RecognitionEvidence {
    @Id private ObjectId id;
    private ObjectId studentId;
    private ObjectId curriculumId;
    private ObjectId ruleId;
    private ObjectId ruleFamilyId;
    private Integer versionNumber;
    private List<String> originalCourseCodes;
    private List<String> replacementCourseCodes;
    private Map<String,String> attemptIds;
    private Double grade4;
    private Date recognizedAt;
}
