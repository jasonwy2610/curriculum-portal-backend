package vn.edu.huflit.portal.service;

import vn.edu.huflit.portal.dto.request.CreateMajorRequest;
import vn.edu.huflit.portal.dto.request.UpdateMajorRequest;
import vn.edu.huflit.portal.dto.response.MajorResponse;

import java.util.List;

public interface MajorService {

    List<MajorResponse> getAllMajors(String status, String keyword);

    MajorResponse getMajorById(String id);

    MajorResponse getMajorByCode(String code);

    MajorResponse createMajor(CreateMajorRequest request);

    MajorResponse updateMajor(String id, UpdateMajorRequest request);

    MajorResponse deleteMajor(String id);
}
