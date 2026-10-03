// =============================================================================
// SCRIPT SEED MẪU - BASELINE CHÍNH THỨC v4.2 (MONGODB ATLAS)
// =============================================================================

print("--> [1/2] Đang thiết lập Indexes theo chuẩn Baseline v4.2...");

// 1. users
db.users.createIndex({ "email": 1 }, { unique: true });
db.users.createIndex(
    { "studentCode": 1 },
    { unique: true, partialFilterExpression: { "studentCode": { $exists: true, $type: "string" } } }
);
db.users.createIndex({ "majorId": 1 });
db.users.createIndex({ "curriculumId": 1 });

// 2. majors
db.majors.createIndex({ "code": 1 }, { unique: true });

// 3. courses
db.courses.createIndex({ "courseCode": 1 }, { unique: true });
db.courses.createIndex({ "courseName": "text" });

// 4. curriculums (Index chuẩn v4.2)
db.curriculums.createIndex({ "curriculumCode": 1 }, { unique: true });
db.curriculums.createIndex({ "majorId": 1 });
db.curriculums.createIndex({ "cohort": 1 });
db.curriculums.createIndex({ "majorId": 1, "cohort": 1, "version": 1 });

// 5. replacementRules (Index chuẩn v4.2)
db.replacementRules.createIndex({ "oldCourseCodes": 1 });
db.replacementRules.createIndex({ "newCourseCodes": 1 });
db.replacementRules.createIndex({ "applicableCohorts": 1 });
db.replacementRules.createIndex({ "majorId": 1, "applicableCohorts": 1 });

// 6. academicRecords (Index chuẩn v4.2)
db.academicRecords.createIndex({ "studentId": 1, "courseCode": 1, "curriculumId": 1 }, { unique: true });
db.academicRecords.createIndex({ "studentId": 1 });
db.academicRecords.createIndex({ "curriculumId": 1 });

// 7. notifications (Index chuẩn v4.2 có replacementRuleId)
db.notifications.createIndex({ "recipientIds": 1 });
db.notifications.createIndex({ "curriculumId": 1 });
db.notifications.createIndex({ "createdAt": -1 });
db.notifications.createIndex({ "replacementRuleId": 1 });

// 8. changeLogs
db.changeLogs.createIndex({ "entityType": 1, "entityId": 1, "createdAt": -1 });

print("--> [2/2] Đang nạp dữ liệu Seed mẫu ban đầu...");

const defaultPasswordHash = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi"; // Mật khẩu: 123456

// 1. majors: Ngành Công nghệ Thông tin
const majorId = new ObjectId("66e01a01f1a2b3c4d5e6f902");
db.majors.updateOne(
    { _id: majorId },
    {
        $set: {
            code: "7480201",
            name: "Công nghệ Thông tin",
            description: "Chương trình đào tạo trình độ Đại học 3.5 năm",
            status: "ACTIVE",
            createdAt: new Date(),
            updatedAt: new Date()
        }
    },
    { upsert: true }
);

// 2. users: Admin Giáo Vụ
const adminId = new ObjectId("66e01a01f1a2b3c4d5e6f900");
db.users.updateOne(
    { email: "admin@huflit.edu.vn" },
    {
        $set: {
            _id: adminId,
            passwordHash: defaultPasswordHash,
            fullName: "Quản Trị Viên Giáo Vụ",
            role: "ADMIN",
            status: "ACTIVE",
            studentCode: null,
            majorId: null,
            cohort: null,
            curriculumId: null,
            chosenSpecializationCode: null,
            createdAt: new Date(),
            updatedAt: new Date()
        }
    },
    { upsert: true }
);

// 3. courses: Danh mục môn học nền tảng
db.courses.updateOne(
    { courseCode: "1250052" },
    {
        $set: {
            courseName: "Nhập môn công nghệ thông tin",
            credits: 2,
            theoryHours: 30,
            practiceHours: 0,
            description: "Nhập môn tổng quan ngành CNTT",
            department: "Khoa CNTT",
            status: "ACTIVE",
            createdAt: new Date(),
            updatedAt: new Date()
        }
    },
    { upsert: true }
);

db.courses.updateOne(
    { courseCode: "1250064" },
    {
        $set: {
            courseName: "Nhập môn lập trình",
            credits: 4,
            theoryHours: 45,
            practiceHours: 30,
            description: "Cơ sở lập trình C/C++",
            department: "Khoa CNTT",
            status: "ACTIVE",
            createdAt: new Date(),
            updatedAt: new Date()
        }
    },
    { upsert: true }
);

db.courses.updateOne(
    { courseCode: "1221014" },
    {
        $set: {
            courseName: "Cấu trúc dữ liệu và giải thuật",
            credits: 4,
            theoryHours: 45,
            practiceHours: 30,
            description: "Giải thuật và cấu trúc dữ liệu nâng cao",
            department: "Khoa CNTT",
            status: "ACTIVE",
            createdAt: new Date(),
            updatedAt: new Date()
        }
    },
    { upsert: true }
);

// 4. curriculums: CTĐT Khóa K2025
const curriculumId = new ObjectId("66e01a01f1a2b3c4d5e6f903");
db.curriculums.updateOne(
    { _id: curriculumId },
    {
        $set: {
            curriculumCode: "CTDT-CNTT-K2025",
            curriculumName: "Chương trình đào tạo Công nghệ Thông tin - Khóa 2025",
            majorId: majorId,
            cohort: "K2025",
            appliedYear: 2025,
            totalCredits: 135,
            status: "ACTIVE",
            version: 1,
            clonedFrom: null,
            specializationTrack: "LATER",
            specializationDecisionSemester: 3,
            specializations: [
                { code: "CNPM", name: "Công nghệ Phần mềm" },
                { code: "ANM", name: "An ninh mạng" }
            ],
            knowledgeGroups: [
                { groupCode: "DC", groupName: "Kiến thức đại cương", order: 1 },
                { groupCode: "CS", groupName: "Kiến thức cơ sở ngành", order: 2 },
                { groupCode: "CN", groupName: "Kiến thức chuyên ngành", order: 3 }
            ],
            semesters: [
                {
                    semesterNumber: 1,
                    courses: [
                        {
                            courseCode: "1250052",
                            type: "MANDATORY",
                            knowledgeGroupCode: "CS",
                            specializationCodes: [],
                            prerequisiteCourseCodes: []
                        },
                        {
                            courseCode: "1250064",
                            type: "MANDATORY",
                            knowledgeGroupCode: "CS",
                            specializationCodes: [],
                            prerequisiteCourseCodes: []
                        }
                    ]
                },
                {
                    semesterNumber: 3,
                    courses: [
                        {
                            courseCode: "1221014",
                            type: "MANDATORY",
                            knowledgeGroupCode: "CN",
                            specializationCodes: ["CNPM", "ANM"],
                            prerequisiteCourseCodes: ["1250064"]
                        }
                    ]
                }
            ],
            createdBy: adminId,
            createdAt: new Date(),
            updatedAt: new Date()
        }
    },
    { upsert: true }
);

// 5. users: Sinh viên mẫu K2025
db.users.updateOne(
    { email: "25001234@huflit.edu.vn" },
    {
        $set: {
            _id: new ObjectId("66e01a01f1a2b3c4d5e6f901"),
            passwordHash: defaultPasswordHash,
            fullName: "Trần Anh Tuấn",
            role: "STUDENT",
            status: "ACTIVE",
            studentCode: "25001234",
            majorId: majorId,
            cohort: "K2025",
            curriculumId: curriculumId,
            chosenSpecializationCode: null,
            createdAt: new Date(),
            updatedAt: new Date()
        }
    },
    { upsert: true }
);

print("==========================================================");
print("--> DONE: Đã cập nhật thành công Baseline v4.2 lên Atlas!");
print("==========================================================");