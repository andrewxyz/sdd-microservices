// Thin typed helpers, one per operation this app calls (contracts/README.md). Every request and
// response type is derived from schema.ts, generated from the backend's own OpenAPI contracts
// (constitution XIII). Nothing here does more than shape the call — behaviour lives in the pages
// and hooks that use these.
import { request } from "./client";
import type { auth, certificate, course, courseContent, enrollment, payment, progress } from "./schema";

type AuthSchemas = auth.components["schemas"];
type CourseSchemas = course.components["schemas"];
type CourseContentSchemas = courseContent.components["schemas"];
type PaymentSchemas = payment.components["schemas"];
type EnrollmentSchemas = enrollment.components["schemas"];
type ProgressSchemas = progress.components["schemas"];
type CertificateSchemas = certificate.components["schemas"];

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

// --- Account (specs/004) --------------------------------------------------------------------------

export function listPayments(): Promise<Page<PaymentView>> {
  return request("/v1/payments");
}
