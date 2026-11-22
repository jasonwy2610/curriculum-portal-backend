package vn.edu.huflit.portal.academic.service;

import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.entity.User;
import java.io.IOException;
import java.util.*;

/** Read-only presentation schedules. Never infers a calendar from cohort or attempt dates. */
final class TranscriptCalendar {
    private static final Properties CONFIRMED = load();

    private static Properties load() {
        Properties values = new Properties();
        try (var input = TranscriptCalendar.class.getResourceAsStream("/transcript-calendars.properties")) {
            if (input != null) values.load(input);
            return values;
        } catch (IOException failure) {
            throw new IllegalStateException("Không đọc được lịch học đã xác nhận", failure);
        }
    }

    static Map<Integer,String> resolve(Curriculum tree, User student) {
        return resolve(CONFIRMED, tree, student);
    }

    static Map<Integer,String> resolve(Properties schedules, Curriculum tree, User student) {
        if (tree.getId() == null || !Objects.equals(tree.getId(), student.getCurriculumId())
                || tree.getVersion() == null || tree.getCohort() == null
                || !Objects.equals(tree.getCohort(), student.getCohort())
                || student.getChosenSpecializationCode() == null) return Map.of();
        String key = tree.getId().toHexString() + "." + tree.getVersion() + "."
                + student.getChosenSpecializationCode() + "." + student.getCohort();
        String configured = schedules.getProperty(key);
        if (configured == null) return Map.of();
        String[] codes = configured.split(",", -1);
        Set<Integer> ordinals = new HashSet<>();
        for (var semester : tree.getSemesters()) ordinals.add(semester.getSemesterNumber());
        if (codes.length != tree.getSemesters().size() || ordinals.size() != codes.length)
            throw new IllegalStateException("Lịch học đã xác nhận không khớp số kỳ của cây CTĐT");
        Map<Integer,String> result = new LinkedHashMap<>();
        String previous = "";
        for (int i = 0; i < codes.length; i++) {
            String code = codes[i].trim();
            if (!ordinals.contains(i + 1) || !code.matches("20\\d{2}-[123]") || code.compareTo(previous) <= 0)
                throw new IllegalStateException("Lịch học đã xác nhận có kỳ thiếu, trùng hoặc không hợp lệ");
            result.put(i + 1, code);previous = code;
        }
        return Collections.unmodifiableMap(result);
    }
}
