// Thin typed helpers, one per operation this app calls (contracts/README.md). Every request and
// response type is derived from schema.ts, generated from the backend's own OpenAPI contracts
// (constitution XIII). Nothing here does more than shape the call — behaviour lives in the pages
// and hooks that use these.
import { request } from "./client";
import type {
  analytics,
  auth,
  certificate,
  course,
  courseContent,
  enrollment,
  payment,
  progress,
  review,
  reviewSummaries,
} from "./schema";

type AuthSchemas = auth.components["schemas"];
type CourseSchemas = course.components["schemas"];
type CourseContentSchemas = courseContent.components["schemas"];
type PaymentSchemas = payment.components["schemas"];
type EnrollmentSchemas = enrollment.components["schemas"];
type ProgressSchemas = progress.components["schemas"];
type CertificateSchemas = certificate.components["schemas"];
type ReviewSchemas = review.components["schemas"];
type ReviewSummariesSchemas = reviewSummaries.components["schemas"];
type AnalyticsSchemas = analytics.components["schemas"];

export type TokenPair = AuthSchemas["TokenPair"];
export type MeView = { id: string; email: string; role: "STUDENT" | "INSTRUCTOR" | "ADMINISTRATOR"; createdAt: string };
export type RegisterResponse = { id: string; email: string; role: "STUDENT"; createdAt: string };

export type CoursePublicView = CourseSchemas["CoursePublicView"];
export type Category = CourseSchemas["Category"];
export type Page<T> = { items: T[]; page: number; size: number; totalItems: number };

export type CourseContentView = CourseContentSchemas["CourseContentView"];

export type PaymentView = PaymentSchemas["PaymentView"];

export type EnrollmentView = EnrollmentSchemas["EnrollmentView"];

export type ProgressView = ProgressSchemas["ProgressView"];

export type CertificateView = CertificateSchemas["CertificateView"];
export type VerificationView = CertificateSchemas["VerificationView"];

export type ReviewView = ReviewSchemas["ReviewView"];
export type ReviewWriteRequest = ReviewSchemas["ReviewWriteRequest"];
export type CourseRatingSummary = ReviewSchemas["CourseRatingSummary"];
export type CourseRatingSummaries = ReviewSummariesSchemas["CourseRatingSummaries"];

/** The batch summaries endpoint refuses more than this many ids (callers chunk). */
export const MAX_SUMMARY_IDS = 50;

export type MyCourse = CourseSchemas["CourseFullView"];

export type InstructorSummary = AnalyticsSchemas["Summary"];
export type InstructorCourseList = AnalyticsSchemas["CourseList"];
export type InstructorCourseDetail = AnalyticsSchemas["CourseDetail"];
export type InstructorTotals = AnalyticsSchemas["Totals"];

export type CourseListQuery = {
  q?: string;
  category?: string;
  level?: "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
  free?: boolean;
  sort?: "newest" | "title" | "price_asc" | "price_desc";
  page?: number;
  size?: number;
};

function toQueryString(query: Record<string, string | number | boolean | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") {
      params.set(key, String(value));
    }
  }
  const qs = params.toString();
  return qs ? `?${qs}` : "";
}

// --- Session (specs/002-user-auth) --------------------------------------------------------------

export function register(email: string, password: string): Promise<RegisterResponse> {
  return request("/v1/auth/register", { method: "POST", body: { email, password } });
}

export function login(email: string, password: string): Promise<TokenPair> {
  return request("/v1/auth/login", { method: "POST", body: { email, password } });
}

export function refresh(refreshToken: string): Promise<TokenPair> {
  return request("/v1/auth/refresh", { method: "POST", body: { refreshToken } });
}

export function logout(refreshToken: string): Promise<void> {
  return request("/v1/auth/logout", { method: "POST", body: { refreshToken } });
}

export function me(): Promise<MeView> {
  return request("/v1/auth/me");
}

// --- Catalog (specs/003-course-catalog) ---------------------------------------------------------

export function listCourses(query: CourseListQuery): Promise<Page<CoursePublicView>> {
  return request(`/v1/courses${toQueryString(query)}`);
}

export function getCourse(courseId: string): Promise<CoursePublicView> {
  return request(`/v1/courses/${courseId}`);
}

export function listCategories(): Promise<Category[]> {
  return request("/v1/categories");
}

// --- Enrolling (specs/004-checkout-enrollment) --------------------------------------------------

export function checkout(input: {
  courseId: string;
  idempotencyKey: string;
  paymentToken?: string;
}): Promise<PaymentView> {
  return request("/v1/checkouts", { method: "POST", body: input });
}

export function getEnrollmentProgress(courseId: string): Promise<ProgressView> {
  return request(`/v1/enrollments/${courseId}/progress`);
}

export function listEnrollments(): Promise<Page<EnrollmentView>> {
  return request("/v1/enrollments");
}

// --- Learning (specs/004, specs/005) ------------------------------------------------------------

export function getCourseContent(courseId: string): Promise<CourseContentView> {
  return request(`/v1/courses/${courseId}/content`);
}

export function markLessonComplete(courseId: string, lessonId: string): Promise<ProgressView> {
  return request(`/v1/enrollments/${courseId}/lessons/${lessonId}/complete`, { method: "PUT" });
}

export function unmarkLessonComplete(courseId: string, lessonId: string): Promise<ProgressView> {
  return request(`/v1/enrollments/${courseId}/lessons/${lessonId}/complete`, { method: "DELETE" });
}

// --- Certificates (specs/005) --------------------------------------------------------------------

export function listMyCertificates(): Promise<Page<CertificateView>> {
  return request("/v1/certificates/mine");
}

export function verifyCertificate(code: string): Promise<VerificationView> {
  return request(`/v1/certificates/verify/${encodeURIComponent(code)}`);
}

// --- Reviews (specs/007, specs/008) -------------------------------------------------------------

export function listReviews(courseId: string, page: number): Promise<Page<ReviewView>> {
  return request(`/v1/reviews/courses/${courseId}?page=${page}`);
}

export function getReviewSummary(courseId: string): Promise<CourseRatingSummary> {
  return request(`/v1/reviews/courses/${courseId}/summary`);
}

export function getReviewSummaries(courseIds: string[]): Promise<CourseRatingSummaries> {
  if (courseIds.length > MAX_SUMMARY_IDS) {
    throw new Error(`At most ${MAX_SUMMARY_IDS} course ids per request.`);
  }
  return request(`/v1/reviews/summaries?courseIds=${courseIds.join(",")}`);
}

export function createReview(courseId: string, body: ReviewWriteRequest): Promise<ReviewView> {
  return request(`/v1/reviews/courses/${courseId}`, { method: "POST", body });
}

export function updateReview(reviewId: string, body: ReviewWriteRequest): Promise<ReviewView> {
  return request(`/v1/reviews/${reviewId}`, { method: "PUT", body });
}

export function deleteReview(reviewId: string): Promise<void> {
  return request(`/v1/reviews/${reviewId}`, { method: "DELETE" });
}

// --- Account (specs/004)--------------------------------------------------------------------------

export function listPayments(): Promise<Page<PaymentView>> {
  return request("/v1/payments");
}

// --- Instructor dashboard (specs/009-instructor-dashboard) ---------------------------------------

/** The caller's own courses in every status (drafts and archived included). */
export function listMyCourses(): Promise<MyCourse[]> {
  return request("/v1/my/courses");
}

export function getInstructorSummary(): Promise<InstructorSummary> {
  return request("/v1/instructor/stats/summary");
}

/** Courses with activity: the caller's own, or every course for an administrator (paged). */
export function listInstructorCourses(page = 0, size = 200): Promise<InstructorCourseList> {
  return request(`/v1/instructor/stats/courses?page=${page}&size=${size}`);
}

export function getInstructorCourse(courseId: string, days: 7 | 30 | 90): Promise<InstructorCourseDetail> {
  return request(`/v1/instructor/stats/courses/${encodeURIComponent(courseId)}?days=${days}`);
}
