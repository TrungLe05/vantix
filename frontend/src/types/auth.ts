export interface User {
  id: string;
  email: string;
  fullName: string;
  role: string;
  status: string;
  oauthProvider: string;
  mustChangePassword: boolean;
}
