import { computed, effect, inject } from '@angular/core';
import { Router } from '@angular/router';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, pipe, switchMap, tap } from 'rxjs';
import { AuthApiService } from '../services/auth-api.service';
import {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  UserResponse,
} from '@shared/models/auth.model';
import { IdleService, IdleState } from '@core/services/idle.service';

const ACCESS_TOKEN_KEY = 'taskforge-access-token';
const REFRESH_TOKEN_KEY = 'taskforge-refresh-token';
const USER_KEY = 'taskforge-user';

interface AuthState {
  user: UserResponse | null;
  accessToken: string | null;
  refreshToken: string | null;
  loading: boolean;
  error: string | null;
}

const initialState: AuthState = {
  user: null,
  accessToken: null,
  refreshToken: null,
  loading: false,
  error: null,
};

function storeTokens(response: AuthResponse): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, response.accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, response.refreshToken);
  localStorage.setItem(USER_KEY, JSON.stringify(response.user));
}

function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export const AuthStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed((store) => ({
    isAuthenticated: computed(
      () => store.user() !== null && store.accessToken() !== null
    ),
    userDisplayName: computed(
      () => store.user()?.fullName ?? store.user()?.email ?? ''
    ),
  })),
  withMethods(
    (
      store,
      authApi = inject(AuthApiService),
      router = inject(Router),
      idleService = inject(IdleService)
    ) => ({
      initializeFromStorage(): void {
        const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
        const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
        const userJson = localStorage.getItem(USER_KEY);

        if (accessToken && refreshToken && userJson) {
          try {
            const user = JSON.parse(userJson) as UserResponse;
            patchState(store, { user, accessToken, refreshToken });
            idleService.start();
          } catch {
            clearTokens();
          }
        }
      },

      register: rxMethod<RegisterRequest>(
        pipe(
          tap(() => patchState(store, { loading: true, error: null })),
          switchMap((request) =>
            authApi.register(request).pipe(
              tap((response) => {
                storeTokens(response);
                patchState(store, {
                  user: response.user,
                  accessToken: response.accessToken,
                  refreshToken: response.refreshToken,
                  loading: false,
                });
                idleService.start();
                router.navigate(['/dashboard'], {
                  queryParams: { registered: 'true' },
                });
              }),
              catchError((err) => {
                const message =
                  err.error?.detail ??
                  err.error?.title ??
                  'Registration failed. Please try again.';
                patchState(store, { loading: false, error: message });
                return EMPTY;
              })
            )
          )
        )
      ),

      login: rxMethod<LoginRequest>(
        pipe(
          tap(() => patchState(store, { loading: true, error: null })),
          switchMap((request) =>
            authApi.login(request).pipe(
              tap((response) => {
                storeTokens(response);
                patchState(store, {
                  user: response.user,
                  accessToken: response.accessToken,
                  refreshToken: response.refreshToken,
                  loading: false,
                });
                idleService.start();
                const returnUrl =
                  localStorage.getItem('taskforge-return-url') ?? '/dashboard';
                localStorage.removeItem('taskforge-return-url');
                router.navigate([returnUrl]);
              }),
              catchError((err) => {
                const message =
                  err.error?.detail ??
                  err.error?.title ??
                  'Invalid email or password';
                patchState(store, { loading: false, error: message });
                return EMPTY;
              })
            )
          )
        )
      ),

      logout(message?: string): void {
        const accessToken = store.accessToken();
        if (accessToken) {
          authApi.logout({ accessToken }).subscribe({
            error: () => {
              // Best-effort — ignore errors
            },
          });
        }
        idleService.stop();
        clearTokens();
        patchState(store, {
          user: null,
          accessToken: null,
          refreshToken: null,
          error: null,
        });
        if (message) {
          router.navigate(['/login'], { queryParams: { message } });
        } else {
          router.navigate(['/login']);
        }
      },

      refreshToken(): void {
        const refreshTokenValue = store.refreshToken();
        if (!refreshTokenValue) {
          clearTokens();
          patchState(store, initialState);
          router.navigate(['/login']);
          return;
        }

        authApi.refresh({ refreshToken: refreshTokenValue }).subscribe({
          next: (response) => {
            storeTokens(response);
            patchState(store, {
              user: response.user,
              accessToken: response.accessToken,
              refreshToken: response.refreshToken,
            });
          },
          error: () => {
            idleService.stop();
            clearTokens();
            patchState(store, initialState);
            router.navigate(['/login']);
          },
        });
      },

      updateTokens(response: AuthResponse): void {
        patchState(store, {
          user: response.user,
          accessToken: response.accessToken,
          refreshToken: response.refreshToken,
        });
      },

      clearError(): void {
        patchState(store, { error: null });
      },
    })
  ),
  withHooks({
    onInit(store) {
      const idleService = inject(IdleService);

      effect(() => {
        const state: IdleState = idleService.state();
        if (state === 'expired' && store.isAuthenticated()) {
          store.logout('session-expired');
        }
      });
    },
  })
);
