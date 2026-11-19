import {test,expect} from '@playwright/test';
const fixtures=JSON.parse(process.env.PORTAL_E2E_FIXTURES);
const fixture=(info,shape='workflow')=>fixtures[info.project.name][shape];
async function login(page,f,role='admin'){
  await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByPlaceholder('mssv@huflit.edu.vn').fill(f[role+'Email']);
  await page.getByPlaceholder('Nhập mật khẩu').fill(f.password);
  await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();
  await expect(page.locator('.sidebar')).toBeVisible();
  await expect(page.locator('.loading-line')).toHaveCount(0);
}
async function nav(page,name){await page.locator('.sidebar').getByRole('button',{name,exact:true}).click();}
async function api(page,path,method='GET',body){
  const response=await page.evaluate(async({path,method,body,base})=>{
    const token=JSON.parse(localStorage.getItem('huflit-session')).token;
    const r=await fetch(base+path,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
    const json=await r.json();return {status:r.status,data:json.data};
  },{path,method,body,base:process.env.VITE_API_BASE_URL});
  return response;
}
async function enterGrade(page,f,code,grade,semester='1',date='2024-06-01T07:00:00'){
  await nav(page,'Kết quả học tập');
  await page.getByRole('button',{name:'Quản lý lần học',exact:true}).click();
  await page.getByRole('button',{name:/Nhập.*điểm|Thêm.*lần học|Ghi nhận/}).click();
  const modal=page.locator('form.modal');
  await modal.getByLabel('Tìm sinh viên',{exact:true}).fill(f.studentCode);
  await modal.getByRole('combobox',{name:'Sinh viên',exact:true}).selectOption(f.studentId);
  await modal.getByRole('combobox',{name:'Học kỳ trong CTĐT',exact:true}).selectOption(semester);
  await modal.getByRole('combobox',{name:'Năm học thực tế',exact:true}).selectOption('2024');
  await modal.getByLabel('Ngày giờ hoàn thành thực tế (giờ Việt Nam)',{exact:true}).fill(date);
  await modal.getByRole('combobox',{name:'Học phần',exact:true}).selectOption(f.prefix+code);
  await modal.getByLabel('Điểm hệ 10 (0–10)',{exact:true}).fill(String(grade));
  const response=page.waitForResponse(r=>r.url().endsWith('/academic-records/attempts')&&r.request().method()==='POST');
  await modal.getByRole('button',{name:'Lưu dữ liệu',exact:true}).click();
  const saved=await response;
  expect(saved.status()).toBe(200);
  expect(saved.request().postDataJSON().attempt.completedAt).toBe(new Date(date+'+07:00').toISOString());
  await expect(modal).toHaveCount(0);
  await expect(page.getByText('Đã lưu kết quả học tập',{exact:true}).last()).toBeVisible();
}
test.beforeEach(async({page})=>{
  // Keep acceptance deterministic/offline for third-party fonts; application APIs remain real.
  await page.route('https://fonts.googleapis.com/**',route=>route.fulfill({contentType:'text/css',body:''}));
  const errors=[];page.__expectedHttp=new Set();page.on('pageerror',()=>errors.push('uncaught browser exception'));
  page.on('console',m=>{if(m.type()==='error'&&!m.text().includes('Failed to load resource'))
    errors.push(m.text().replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g,'[redacted token]').slice(0,250));});
  page.on('requestfailed',r=>{if(!r.failure()?.errorText.includes('ERR_ABORTED'))errors.push('failed request '+new URL(r.url()).pathname);});
  page.on('response',r=>{
    const path=new URL(r.url()).pathname;
    if(r.status()>=400 && !page.__expectedHttp.has(path+':'+r.status()) && !(r.status()===403&&path==='/api/v1/admin/students') && !(r.status()===400&&path==='/api/majors'))
      errors.push(`HTTP ${r.status()} ${path}`);
  });
  page.__errors=errors;
  page.on('dialog',dialog=>dialog.accept());
});
test.afterEach(async({page},info)=>{
  await page.screenshot({path:info.outputPath('final.png'),fullPage:true,timeout:10000});
  expect(page.__errors).toEqual([]);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'page must fit the viewport').toBe(true);
});

test('admin tree publication, fixed assignment, real grade form, electives, correction and student progress',async({page},info)=>{
  const f=fixture(info); await login(page,f);
  await nav(page,'Ngành đào tạo');await expect(page.getByText('Integration major',{exact:true}).first()).toBeVisible();
  await nav(page,'Quản lý CTĐT');
  await page.getByRole('combobox',{name:'CTĐT và phiên bản',exact:true}).selectOption(f.programId);
  await page.getByRole('combobox',{name:'Chuyên ngành',exact:true}).selectOption('BB');
  await expect(page.getByRole('combobox',{name:'Chuyên ngành',exact:true})).toHaveValue('BB');
  await page.getByRole('combobox',{name:'Chuyên ngành',exact:true}).selectOption('AA');
  await page.getByRole('button',{name:'Kiểm tra toàn bộ cây',exact:true}).click();
  await expect(page.getByText('Tất cả cây hợp lệ.',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Công bố toàn bộ CTĐT',exact:true}).click();
  await expect(page.getByText('Phiên bản đã khóa. Tạo bản sao để thay đổi.',{exact:true})).toBeVisible();
  await nav(page,'Gán CTĐT sinh viên');
  await page.getByLabel('Tìm sinh viên để gán CTĐT').fill(f.studentCode);
  await page.getByRole('button',{name:'Tìm sinh viên',exact:true}).click();
  await page.getByRole('combobox',{name:'Sinh viên',exact:true}).selectOption(f.studentId);
  await page.getByRole('combobox',{name:'CTĐT đã công bố',exact:true}).selectOption(f.programId);
  await page.getByRole('combobox',{name:'Chuyên ngành cố định',exact:true}).selectOption('AA');
  await page.getByRole('button',{name:'Gán cố định',exact:true}).click();
  await expect(page.getByText('Sinh viên đã được gán phiên bản và chuyên ngành cố định.',{exact:true})).toBeVisible();
  for(const [code,grade,semester,day] of [['C',7,'1',1],['S',8,'1',1],['E4',3,'2',1],['E3',7,'2',2],['E2',8,'2',3],['E4',10,'2',4]])
    await enterGrade(page,f,code,grade,semester,`2024-06-0${day}T07:00:00`);
  await page.getByRole('button',{name:'Sửa điểm có lý do',exact:true}).click();
  await page.getByPlaceholder('Tìm mã số / tên / email').fill(f.studentCode);
  await page.getByRole('button',{name:'Tìm sinh viên',exact:true}).click();
  await page.getByRole('combobox',{name:'Chọn sinh viên',exact:true}).selectOption(f.studentId);
  await page.getByRole('button',{name:'Xem kết quả học tập',exact:true}).click();
  await page.getByRole('combobox',{name:'Học phần',exact:true}).selectOption({label:f.prefix+'C (1 lần học)'});
  await page.getByRole('combobox',{name:'Lần học',exact:true}).selectOption({index:1});
  await page.getByLabel('Điểm mới',{exact:true}).fill('8');
  await page.getByLabel('Lý do chỉnh sửa',{exact:true}).fill('Local E2E verified correction');
  await page.getByRole('button',{name:'Lưu sửa điểm',exact:true}).click();
  await expect(page.getByText('Đã sửa điểm và lưu lịch sử',{exact:true})).toBeVisible();
  await nav(page,'Quản lý CTĐT');
  const selectedProgram=page.getByRole('combobox',{name:'CTĐT và phiên bản',exact:true});
  await selectedProgram.selectOption(f.programId); // Re-selecting the retained version must not erase its tree.
  await expect(page.getByText('Phiên bản đã khóa. Tạo bản sao để thay đổi.',{exact:true})).toBeVisible();
  await page.getByLabel('Mã CTĐT cho phiên bản mới',{exact:true}).fill(f.prefix+'V2');
  await page.getByRole('button',{name:'Tạo phiên bản nháp mới',exact:true}).click();
  await expect(page.getByText('Lưu bản nháp trước khi kiểm tra và công bố.',{exact:true})).toBeVisible();
  await nav(page,'Đăng xuất');await login(page,f,'student');await nav(page,'CTĐT của tôi');
  await expect(page.getByText('Không thể tải CTĐT')).toHaveCount(0);
  const result=await api(page,'/api/v1/student/curriculum/evaluation-preview');
  expect(result.status).toBe(200);expect(result.data.curriculumId).toBe(f.programId);
  expect(result.data.recognizedCredits).toBe(11);expect(result.data.gpaCredits).toBe(15);
  expect(result.data.groupProgress.find(g=>g.groupCode==='FREE').recognizedCourseCodes).toEqual([f.prefix+'E3',f.prefix+'E2']);
  expect(result.data.cumulativeGpa4).toBe(3.53);
  await expect(page.locator('.sidebar').getByRole('button',{name:'Quản lý CTĐT',exact:true})).toHaveCount(0);
  const denied=await api(page,'/api/v1/admin/students');expect(denied.status).toBe(403);
  await nav(page,'Kết quả học tập');
  await expect(page.getByRole('heading',{name:'Bảng điểm của bạn',exact:true})).toBeVisible();
  const transcript=await api(page,'/api/v1/student/academic-records/transcript');
  expect(transcript.status).toBe(200);expect(transcript.data.summary.academicEarnedCredits).toBe(15);
  expect(transcript.data.summary.graduationRecognizedCredits).toBe(11);expect(transcript.data.summary.cumulativeGpa4).toBe(3.53);
  await page.getByRole('combobox',{name:'Học kỳ',exact:true}).selectOption('2024-1');
  await expect(page.getByText('2,79',{exact:true}).first()).toBeVisible();
  await page.getByRole('button',{name:'Thu gọn tất cả',exact:true}).click();
  await expect(page.locator('.sr-term-heading').first()).toHaveAttribute('aria-expanded','false');
  await page.getByRole('button',{name:'Mở tất cả',exact:true}).click();
  await expect(page.locator('.sr-term-heading').first()).toHaveAttribute('aria-expanded','true');
  await page.evaluate(()=>window.scrollTo(0,0));
  await page.screenshot({path:info.outputPath('transcript.png'),fullPage:true});
  await page.getByPlaceholder('Mã hoặc tên học phần').fill('Integration E4');
  if(info.project.name==='desktop')await expect(page.getByRole('row').filter({hasText:f.prefix+'E4'})).toHaveCount(2);
  else await expect(page.locator('.sr-mobile-cards:visible article')).toHaveCount(2);
  await page.getByRole('combobox',{name:'Chế độ xem',exact:true}).selectOption('highest');
  await page.getByRole('button',{name:'Lịch sử '+f.prefix+'E4',exact:true}).click();
  await expect(page.getByRole('dialog').getByRole('listitem')).toHaveCount(2);
  await expect(page.getByRole('dialog').getByText('Không đạt',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Đóng lịch sử',exact:true}).click();
  await page.getByPlaceholder('Mã hoặc tên học phần').fill('NO_MATCH');
  await expect(page.getByText('Không có kết quả phù hợp',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Xóa bộ lọc',exact:true}).click();
});
for(const shape of ['1-1','1-N','N-1'])test(`replacement ${shape} through grade form and visible recognition`,async({page},info)=>{
  const f=fixture(info,shape);await login(page,f);
  await enterGrade(page,f,'NEW',7);
  if(shape==='1-N')await enterGrade(page,f,'ALT',8);
  await nav(page,'Đăng xuất');await login(page,f,'student');await nav(page,'CTĐT của tôi');
  const result=await api(page,'/api/v1/student/curriculum/evaluation-preview');
  expect(result.status).toBe(200);expect(result.data.recognizedCredits).toBe(shape==='N-1'?6:3);
  expect(result.data.recognizedReplacementRuleIds).toHaveLength(1);
  await nav(page,'Môn học thay thế');await expect(page.getByText(shape,{exact:true}).first()).toBeVisible();
  if(shape==='1-1'){
    await nav(page,'Đăng xuất');await login(page,f);
    const retired=await api(page,'/api/v1/admin/replacement-rules/'+result.data.recognizedReplacementRuleIds[0]+'/retire','POST',{reason:'Local browser historical verification'});
    expect(retired.status).toBe(200);
    await nav(page,'Đăng xuất');await login(page,f,'student');await nav(page,'CTĐT của tôi');
    await page.locator('.sc-semester-trigger').first().click();
    await page.getByTitle('Xem thông tin thay thế').first().click();
    await expect(page.getByRole('dialog').getByRole('heading',{name:'Bằng chứng công nhận đã lưu',exact:true})).toBeVisible();
    const history=await api(page,'/api/v1/student/curriculum/evaluation-preview');
    expect(history.data.recognizedCredits).toBe(3);expect(history.data.historicalRecognitionEvidence).toHaveLength(1);
  }
});

test('loading, empty directory and business API error remain understandable',async({page},info)=>{
  const f=fixture(info);await login(page,f);
  await nav(page,'Gán CTĐT sinh viên');
  await page.getByLabel('Tìm sinh viên để gán CTĐT').fill('NO_SUCH_LOCAL_STUDENT');
  await page.getByRole('button',{name:'Tìm sinh viên',exact:true}).click();
  await expect(page.getByText('Không tìm thấy sinh viên phù hợp. Hãy đổi từ khóa hoặc bộ lọc.',{exact:true})).toBeVisible();
  await page.route('**/api/majors?*',async route=>{await new Promise(r=>setTimeout(r,1500));await route.fulfill({status:400,contentType:'application/json',body:JSON.stringify({success:false,message:'Dữ liệu kiểm thử chưa hợp lệ'})});});
  await nav(page,'Ngành đào tạo');await expect(page.getByText(/Đang tải/).first()).toBeVisible();
  await expect(page.getByText('Dữ liệu kiểm thử chưa hợp lệ').first()).toBeVisible();
  await expect(page.getByText('Không thể kết nối máy chủ',{exact:true})).toHaveCount(0);
});

test('transcript loading, empty, HTTP failure and genuinely missing metadata are scoped',async({page},info)=>{
  const f=fixture(info);await login(page,f,'student');let phase='empty';
  const path='/api/v1/student/academic-records/transcript';
  page.__expectedHttp.add(path+':503');
  await page.route('**'+path,async route=>{
    await new Promise(r=>setTimeout(r,700));
    if(phase==='empty')return route.fulfill({contentType:'application/json',body:JSON.stringify({success:true,data:{summary:{academicEarnedCredits:0},attempts:[],semesters:[],issues:[]}})});
    if(phase==='error')return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({success:false,message:'java.lang.InternalError: test'})});
    if(phase==='invalid')return route.fulfill({contentType:'application/json',body:JSON.stringify({success:true,data:{}})});
    const response=await route.fetch();const json=await response.json();const value=json.data;const code=value.attempts[0].courseCode;
    value.attempts=value.attempts.map(r=>r.courseCode===code?{...r,courseName:null,credits:null}:r);
    value.summary.cumulativeGpa4=null;value.summary.academicEarnedCredits=null;
    value.semesters=value.semesters.map(s=>value.attempts.some(r=>r.courseCode===code&&r.semesterCode===s.semesterCode)?{...s,gpa4:null,attemptedCredits:null,earnedCredits:null}:s);
    value.issues=[{courseCode:code,semesterCode:null,field:'credits',message:'Thiếu số tín chỉ hợp lệ trong danh mục.'}];
    return route.fulfill({response,json});
  });
  await nav(page,'Kết quả học tập');await expect(page.getByText('Đang tải bảng điểm và GPA chính thức...',{exact:true})).toBeVisible();
  await expect(page.getByText('Chưa có kết quả học tập',{exact:true})).toBeVisible();
  phase='error';await page.getByRole('button',{name:'Tải lại bảng điểm',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Chưa thể tải kết quả học tập',exact:true})).toBeVisible();
  await expect(page.getByText('java.lang.InternalError: test',{exact:true})).toHaveCount(0);
  phase='invalid';await page.getByRole('button',{name:'Thử lại',exact:true}).click();
  await expect(page.getByText('Dữ liệu bảng điểm chưa đầy đủ. Vui lòng thử lại hoặc liên hệ giáo vụ.',{exact:true})).toBeVisible();
  phase='missing';await page.getByRole('button',{name:'Thử lại',exact:true}).click();
  await page.locator('.sr-issues summary').click();
  await expect(page.locator('.sr-issues li').filter({hasText:'Thiếu số tín chỉ hợp lệ trong danh mục.'})).toHaveCount(1);
  await expect(page.locator('.sr-overview article').first().locator('strong')).toHaveText('—');
  await expect(page.locator('pre')).toHaveCount(0);
});
