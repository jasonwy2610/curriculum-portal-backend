export const learningLabel=course=>({PASSED:'Đã đạt',FAILED:'Không đạt',NOT_STUDIED:'Chưa có kết quả',UNVERIFIED:'Cần xác minh'})[course.learningStatus]
  ??(course.passed===true?'Đã đạt':'Chưa có kết quả xác minh');
export function recognitionLabel(rule){
  return ({HISTORICAL_RECOGNIZED:'Đã công nhận · bằng chứng lịch sử',RECOGNIZED:'Đã công nhận',ELIGIBLE:'Đủ điều kiện xét công nhận',NOT_RECOGNIZED:'Chưa được công nhận'})[rule.recognitionState]
    ??(rule.recognized===true?'Đã công nhận':'Chưa được công nhận');
}
export function replacementShape(rule){
  const old=rule.oldCourseCodes?.length??0,news=rule.newCourseCodes?.length??0;
  return old===1&&news===1?'1–1':old===1&&news>1?'1–N':old>1&&news===1?'N–1':'Cần xác minh';
}
