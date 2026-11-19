import React from 'react';
import AcademicIdentity from './AcademicIdentity.jsx';
import {electiveProgress} from './curriculum-ui.js';

export function StudentCurriculumState({loading = false, unassigned = false}) {
  if (loading) return <section className="panel" role="status">Đang tải CTĐT và kết quả công nhận của bạn...</section>;
  return <section className="panel huf-friendly-empty" role="status">
    <h2>{unassigned ? 'Bạn chưa được gán CTĐT' : 'Chương trình đào tạo của bạn chưa sẵn sàng'}</h2>
    <p>Giáo vụ cần gán CTĐT đúng ngành, khóa tuyển sinh và chuyên ngành cố định cho tài khoản của bạn.</p>
    <p>Bạn không cần tự tạo CTĐT hoặc đăng ký chuyên ngành. Liên hệ phòng đào tạo để được kiểm tra.</p>
  </section>;
}

export default function StudentCurriculumSummary({data, majors}) {
  const elective = electiveProgress(data);
  return <section className="panel student-tree-summary">
    <AcademicIdentity curriculum={data} majors={majors}/>
    {elective ? <><h3>Nhóm tự chọn chung</h3><div className="tree-metrics">
      <span>Tín chỉ tự chọn đã đạt <b>{elective.passed ?? 'Chưa có dữ liệu'}</b></span>
      <span>Tín chỉ được công nhận <b>{elective.recognized} / {elective.required}</b></span>
      <span>Tín chỉ còn thiếu <b>{elective.missing}</b></span>
    </div><p>{elective.complete ? 'Đã hoàn thành nhóm. Không bắt buộc hoàn thành thêm các lựa chọn còn lại.'
      : 'Nhóm đang thiếu yêu cầu. Có thể đạt các môn khác trong cùng nhóm để bù môn không đạt.'}</p>
      <p className="muted">Các môn học dư và lần học không đạt vẫn được giữ trong bảng điểm. GPA hiển thị theo kết quả tính chính thức của hệ thống.</p></>
      : <p>Chưa có tiến độ nhóm tự chọn từ hệ thống.</p>}
  </section>;
}
