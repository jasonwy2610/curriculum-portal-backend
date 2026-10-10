package vn.edu.huflit.portal.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.edu.huflit.portal.entity.Major;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MajorResponse {
    private String id;
    private String code;
    private String name;
    private String description;
    private String status;
    private Date createdAt;
    private Date updatedAt;

    public static MajorResponse fromEntity(Major major) {
        if (major == null) {
            return null;
        }
        return MajorResponse.builder()
                .id(major.getId() != null ? major.getId().toHexString() : null)
                .code(major.getCode())
                .name(major.getName())
                .description(major.getDescription())
                .status(major.getStatus())
                .createdAt(major.getCreatedAt())
                .updatedAt(major.getUpdatedAt())
                .build();
    }
}
