function humanizeError(raw, status) {
  const message = String(raw || '');
  const duplicate = message.match(/duplicate key error collection:\s*[^.]+\.curriculums.*?cohort:\s*["']?([^"',}\s]+)["']?/i)
    || message.match(/E11000 duplicate key error.*?cohort:\s*["']?([^"',}\s]+)["']?/i);
  if (duplicate) return {
    title: 'Không thể tạo chương trình đào tạo',
    message: `Khóa ${duplicate[1]} đã có chương trình đào tạo thuộc ngành này. Vui lòng kiểm tra CTĐT hiện có hoặc chỉnh sửa chương trình đã tạo.`
  };
  if (/E11000|duplicate key/i.test(message)) return {
    title: 'Dữ liệu đã tồn tại',
    message: 'Thông tin bạn nhập bị trùng với dữ liệu đã có trong hệ thống. Vui lòng kiểm tra mã hoặc tên trước khi lưu.'
  };
  if (status === 401 || /unauthorized|jwt|token expired/i.test(message)) return {
    title: 'Phiên đăng nhập không hợp lệ',
    message: 'Phiên làm việc đã hết hạn hoặc thông tin đăng nhập không chính xác. Vui lòng đăng nhập lại.'
  };
  if (status === 403 || /access denied|forbidden/i.test(message)) return {
    title: 'Bạn không có quyền thực hiện thao tác này',
    message: 'Vui lòng liên hệ giáo vụ hoặc đăng nhập bằng tài khoản có quyền phù hợp.'
  };
  if (status === 404) return { title: 'Không tìm thấy dữ liệu', message: 'Dữ liệu có thể đã bị xóa hoặc không còn khả dụng. Vui lòng tải lại trang.' };
  if (status === 400 || /validation|invalid|must not be blank/i.test(message)) return {
    title: 'Thông tin chưa hợp lệ',
    message: message && !/exception|error|mongo|org\.|java\./i.test(message) ? message : 'Vui lòng kiểm tra lại các trường thông tin bắt buộc và định dạng dữ liệu.'
  };
  if (status >= 500 || /exception|mongo|org\.|java\.|write operation error/i.test(message)) return {
    title: 'Hệ thống chưa thể xử lý yêu cầu',
    message: 'Đã xảy ra lỗi khi xử lý dữ liệu. Vui lòng thử lại sau; nếu lỗi tiếp diễn, hãy liên hệ quản trị viên.'
  };
  return { title: 'Thao tác chưa thành công', message: message && !/exception|error|mongo|org\.|java\./i.test(message) ? message : 'Vui lòng thử lại. Nếu lỗi tiếp diễn, hãy liên hệ quản trị viên.' };
}
export async function requestJson(url, {token, method = 'GET', body} = {}) {
  const init = {
    method,
    headers: {'Content-Type': 'application/json', ...(token ? {Authorization: `Bearer ${token}`} : {})},
    ...(body !== undefined ? {body: JSON.stringify(body)} : {})
  };
  let response;
  try {
    response = await fetch(url, init);
  } catch {
    const error = new Error('Vui lòng kiểm tra kết nối mạng hoặc thử lại sau.');
    error.title = 'Không thể kết nối máy chủ';
    throw error;
  }
  const data = await response.json().catch(() => null);
  if (!response.ok || data?.success === false) {
    const friendly = humanizeError(data?.message || data?.error, response.status);
    const error = new Error(friendly.message);
    error.title = friendly.title;
    throw error;
  }
  if (data === null && response.status !== 204) {
    const error = new Error('Máy chủ trả về phản hồi không đúng định dạng.');
    error.title = 'Không thể đọc phản hồi';
    throw error;
  }
  return data?.data !== undefined ? data.data : data;
}
