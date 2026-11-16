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
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "replacementRules")
public class ReplacementRule {
    @Id
    @JsonSerialize(using = ToStringSerializer.class)
    private ObjectId id;

    // A family identifies all versions of one logical replacement rule.
    @JsonSerialize(using = ToStringSerializer.class)
    private ObjectId ruleFamilyId;
    @JsonSerialize(using = ToStringSerializer.class)
    private ObjectId previousVersionId;
    private Integer versionNumber;
    private Date publishedAt;
    private Date supersededAt; // metadata only; published rule content is immutable
    private Date endsAt; // exclusive end for independent retirement
    private Date retirementRecordedAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private ObjectId retiredBy;
    private String retirementReason;
    private String lifecycleStatus; // DRAFT | PUBLISHED (legacy ACTIVE treated as published)

    private String ruleType; // "1-1" | "1-N" | "N-1"
    private List<String> oldCourseCodes;
    private List<String> newCourseCodes;
    @JsonSerialize(using = ToStringSerializer.class)
    private ObjectId majorId;
    private List<String> applicableCohorts;
    private Date effectiveDate;
    private String reason;
    private String note;
    private String status; // ACTIVE for published rules with a timeline, INACTIVE for drafts
    @JsonSerialize(using = ToStringSerializer.class)
    private ObjectId createdBy;

    @CreatedDate
    private Date createdAt;

    @LastModifiedDate
    private Date updatedAt;
}
