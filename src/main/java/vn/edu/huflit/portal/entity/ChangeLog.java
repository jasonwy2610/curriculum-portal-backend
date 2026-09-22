package vn.edu.huflit.portal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "changeLogs")
public class ChangeLog {
    @Id
    private ObjectId id;

    private String entityType; // "Curriculum" | "Course" | "ReplacementRule"
    private ObjectId entityId; // Polymorphic reference
    private String action;     // "CREATE" | "UPDATE" | "CLONE"
    private List<FieldChangeItem> changes;
    private ObjectId changedBy;

    @CreatedDate
    private Date createdAt;

    @Data
    public static class FieldChangeItem {
        private String field;
        private Object oldValue;
        private Object newValue;
    }
}