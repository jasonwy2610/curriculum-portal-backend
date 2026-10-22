package vn.edu.huflit.portal.curriculum.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigureSpecializationsRequest {
    private String specializationSelectionTiming; // "EARLY" | "LATER"
    private Integer specializationSelectionSemester;

    @Builder.Default
    private List<SpecializationDto> specializations = new ArrayList<>();
}
