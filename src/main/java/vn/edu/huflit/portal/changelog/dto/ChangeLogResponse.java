package vn.edu.huflit.portal.changelog.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.edu.huflit.portal.changelog.model.ChangeLog;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeLogResponse {
    private String id;
    private String entityType;
    private String entityId;
    private String action;
    private List<FieldChangeDto> changes;
    private String changedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date createdAt;

    public static ChangeLogResponse fromEntity(ChangeLog log) {
        if (log == null) {
            return null;
        }
        return ChangeLogResponse.builder()
                .id(log.getId() != null ? log.getId().toHexString() : null)
                .entityType(log.getEntityType())
                .entityId(log.getEntityId() != null ? log.getEntityId().toHexString() : null)
                .action(log.getAction())
                .changedBy(log.getChangedBy() != null ? log.getChangedBy().toHexString() : null)
                .changes(log.getChanges() != null ? log.getChanges().stream()
                        .map(c -> FieldChangeDto.builder()
                                .field(c.getField())
                                .oldValue(c.getOldValue())
                                .newValue(c.getNewValue())
                                .build())
                        .collect(Collectors.toList()) : new ArrayList<>())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
