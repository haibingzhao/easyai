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
  /**
   * Sign in, optionally acting under a group the user belongs to. The backend validates membership
   * and mints the token pair with that group's shared-asset bucket in its claims; an omitted (or
   * unknown/unauthorized) groupId degrades to a personal, group-less session.
   */
  async login(username: string, password: string, groupId?: string): Promise<AuthResponse> {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ username, password, groupId }),
    });
    if (!response.ok) {
      const msg = await response.text().catch(() => 'Login failed');
      throw new Error(msg || 'Login failed');
    }
    return response.json();
  }

  /**
   * Switch the active group and re-mint tokens without re-entering credentials. Reads the same
   * httpOnly refresh cookie as refresh; a group the caller is not a member of is refused (403) and
   * leaves the current session untouched. A null groupId leaves the group context (personal session).
   */
  async switchGroup(groupId?: string | null): Promise<AuthResponse> {
    const response = await fetch('/api/auth/switch-group', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ groupId: groupId ?? null }),
    });
    if (!response.ok) {
      const msg = await response.text().catch(() => 'Failed to switch group');
      throw new Error(msg || 'Failed to switch group');
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
