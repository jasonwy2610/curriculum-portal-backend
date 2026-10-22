package vn.edu.huflit.portal.service.impl;

import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.service.GradeConversionService;

@Service
public class GradeConversionServiceImpl implements GradeConversionService {

    @Override
    public GradeResult convert(double score10) {
        if (score10 < 0 || score10 > 10) {
            throw new IllegalArgumentException("score10 phải nằm trong khoảng 0 đến 10");
        }

        if (score10 >= 9.0) {
            return new GradeResult("A+", 4.0, "PASSED");
        }
        if (score10 >= 8.5) {
            return new GradeResult("A", 4.0, "PASSED");
        }
        if (score10 >= 8.0) {
            return new GradeResult("B+", 3.5, "PASSED");
        }
        if (score10 >= 7.0) {
            return new GradeResult("B", 3.0, "PASSED");
        }
        if (score10 >= 6.0) {
            return new GradeResult("C+", 2.5, "PASSED");
        }
        if (score10 >= 5.5) {
            return new GradeResult("C", 2.0, "PASSED");
        }
        if (score10 >= 5.0) {
            return new GradeResult("D+", 1.5, "PASSED");
        }
        if (score10 >= 4.0) {
            return new GradeResult("D", 1.0, "PASSED");
        }

        return new GradeResult("F", 0.0, "FAILED");
    }
}
