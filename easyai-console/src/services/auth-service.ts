export interface UserProfile {
  id: string;
  username: string;
  displayName: string;
  avatar: string;
  email: string | null;
}

export interface AuthResponse {
  accessToken: string;
  user: UserProfile;
}

export class AuthService {
  async login(username: string, password: string): Promise<AuthResponse> {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ username, password }),
    });
    if (!response.ok) {
      const msg = await response.text().catch(() => 'Login failed');
      throw new Error(msg || 'Login failed');
    }
    return response.json();
  }

  async register(username: string, password: string, email: string, displayName?: string): Promise<AuthResponse> {
    const response = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ username, password, email, displayName }),
    });
    if (!response.ok) {
      const msg = await response.text().catch(() => 'Registration failed');
      throw new Error(msg || 'Registration failed');
    }
    return response.json();
  }

  async logout(): Promise<void> {
    await fetch('/api/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
    });
  }
}

export const authService = new AuthService();
