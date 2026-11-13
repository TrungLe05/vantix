export type UserRole = "BUYER" | "ADMIN" | "STAFF";
export type UserStatus = "PENDING_VERIFICATION" | "ACTIVE" | "LOCKED";
export type OauthProvider = "NONE" | "GOOGLE";

export interface UserResponse {
  id: string;
  email: string;
  fullName: string;
  role: UserRole;
  status: UserStatus;
  oauthProvider: OauthProvider;
  mustChangePassword: boolean;
}

export type User = UserResponse;

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface LoginResponse {
  auth: AuthResponse;
  user: UserResponse;
  isNewUser: boolean;
}
