import React from 'react';
const date = value => value ? new Date(value).toLocaleString('vi-VN', {timeZone: 'Asia/Ho_Chi_Minh'}) : 'Chưa có dữ liệu lịch sử';

export default function ReplacementEvidenceDetails({rules = []}) {
  if (!rules.length) return <p>Chưa có thông tin thay thế cho học phần này.</p>;
  return <div className="replacement-evidence">{rules.map((rule, index) => <article key={rule.ruleId ?? index}>
    <h4>{rule.historicalEvidence ? 'Bằng chứng công nhận đã lưu' : 'Thông tin thay thế học phần'}</h4>
    <p><b>Môn được thay:</b> {(rule.oldCourseCodes ?? []).join(', ') || 'Chưa có thông tin'}</p>
    <p><b>Môn nguồn:</b></p><ul>{(rule.newCourses ?? []).map(course => <li key={course.courseCode}>
      {course.courseName || course.courseCode} · {course.courseCode} · {course.credits ?? '—'} tín chỉ
      {course.passed ? ' · Đã đạt' : rule.recognized ? ' · Đã được dùng trong bằng chứng công nhận' : ' · Chưa đạt'}
    </li>)}</ul>
    <p><b>Trạng thái:</b> {rule.recognized ? 'Đã công nhận theo bằng chứng hợp lệ' : rule.automaticRecognitionSupported
      ? 'Đang áp dụng; hệ thống xét theo lần học và ngày hoàn thành thực tế' : 'Chưa đủ điều kiện công nhận mới'}</p>
    {rule.effectiveDate && <p>Ngày bắt đầu: {date(rule.effectiveDate)}</p>}
    {rule.recognizedAt && <p>Ngày ghi nhận bằng chứng: {date(rule.recognizedAt)}</p>}
    {rule.versionNumber != null && <p>Phiên bản quy tắc: {rule.versionNumber}</p>}
    {rule.grade4 != null && <p>Điểm quy đổi đã lưu: {rule.grade4}</p>}
    {rule.reason && <p>Lý do: {rule.reason}</p>}{rule.note && <p>{rule.note}</p>}
    {rule.recognized && <p>Tín chỉ công nhận theo môn gốc; không cộng trùng môn nguồn.</p>}
  </article>)}</div>;
}
