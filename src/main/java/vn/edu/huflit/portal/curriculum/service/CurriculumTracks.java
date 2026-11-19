package vn.edu.huflit.portal.curriculum.service;

import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.entity.User;
import java.util.*;

/** Resolves a single student's curriculum without mutating the stored version. */
public final class CurriculumTracks {
    private CurriculumTracks() {}

    public static void validateStudentScope(Curriculum curriculum, User student) {
        if (student == null || student.getMajorId() == null || student.getCohort() == null
            || student.getCohort().isBlank() || !Objects.equals(curriculum.getMajorId(), student.getMajorId())
            || !Objects.equals(curriculum.getCohort(), student.getCohort()))
            throw new IllegalArgumentException("CTĐT không thuộc ngành và khóa tuyển sinh của sinh viên");
    }

    /** The assigned version, identity and tree are resolved together for every student operation. */
    public static Curriculum forStudent(Curriculum curriculum, User student) {
        if (student == null || student.getCurriculumId() == null)
            throw new IllegalArgumentException("Sinh viên chưa được gán CTĐT");
        if (!student.getCurriculumId().equals(curriculum.getId()))
            throw new IllegalArgumentException("CTĐT không khớp phiên bản đã gán cố định cho sinh viên");
        validateStudentScope(curriculum, student);
        if ("DRAFT".equalsIgnoreCase(curriculum.getStatus()))
            throw new IllegalArgumentException("CTĐT được gán chưa được công bố");
        return select(curriculum, student.getChosenSpecializationCode());
    }

    public static Curriculum select(Curriculum curriculum, String code) {
        boolean hasTracks = !safe(curriculum.getSpecializations()).isEmpty();
        if (hasTracks && safe(curriculum.getSpecializations()).stream()
            .noneMatch(item -> item != null && Objects.equals(item.getCode(), code))) {
            throw new IllegalArgumentException("Chuyên ngành cố định không thuộc CTĐT đã gán");
        }
        Curriculum.SpecializationTree tree = code == null ? null : trees(curriculum).get(code);
        boolean explicit = !trees(curriculum).isEmpty();
        if (explicit && tree == null)
            throw new IllegalStateException("CTĐT thiếu cây chuyên ngành: " + code);
        List<Curriculum.SemesterItem> semesters = explicit ? safe(tree.getSemesters()) : safe(curriculum.getSemesters());
        List<Curriculum.RequirementGroupItem> groups = explicit ? safe(tree.getRequirementGroups()) : safe(curriculum.getRequirementGroups());
        // Keep legacy scope fields readable while using the same projection everywhere.
        return curriculum.toBuilder()
            .semesters(semesters.stream().filter(Objects::nonNull).map(semester ->
                Curriculum.SemesterItem.builder().semesterNumber(semester.getSemesterNumber())
                    .courses(safe(semester.getCourses()).stream().filter(Objects::nonNull)
                        .filter(course -> applies(course.getSpecializationCodes(), code)).toList()).build()).toList())
            .requirementGroups(groups.stream().filter(Objects::nonNull)
                .filter(group -> applies(group.getSpecializationCodes(), code)).toList())
            .specializationTrees(new LinkedHashMap<>()).build();
    }

    public static Map<String, Curriculum.SpecializationTree> trees(Curriculum curriculum) {
        return curriculum.getSpecializationTrees() == null ? Map.of() : curriculum.getSpecializationTrees();
    }

    public static boolean applies(List<String> codes, String track) {
        return codes == null || codes.isEmpty() || codes.contains(track);
    }

    public static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}
