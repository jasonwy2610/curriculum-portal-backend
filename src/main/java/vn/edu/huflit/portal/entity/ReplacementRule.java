package vn.edu.huflit.portal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "replacementRules")
public class ReplacementRule {
    @Id
    private ObjectId id;

    private String ruleType; // "1-1" | "1-N" | "N-1"
    private List<String> oldCourseCodes;
    private List<String> newCourseCodes;
    private ObjectId majorId;
    private List<String> applicableCohorts;
    private Date effectiveDate;
    private String reason;
    private String note;
    private String status; // "ACTIVE" | "INACTIVE"
    private ObjectId createdBy;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}