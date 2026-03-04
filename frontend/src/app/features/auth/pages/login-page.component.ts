import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthStore } from '../store/auth.store';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <mat-card class="auth-card">
      <mat-card-header>
        <mat-card-title>Sign in to TaskForge</mat-card-title>
      </mat-card-header>

      <mat-card-content>
        @if (sessionMessage()) {
          <div class="info-banner" role="status">
            <mat-icon>info_outline</mat-icon>
            <span>{{ sessionMessage() }}</span>
          </div>
        }
        @if (store.error()) {
          <div class="error-banner" role="alert">
            <mat-icon>error_outline</mat-icon>
            <span>{{ store.error() }}</span>
          </div>
        }

        <form [formGroup]="form" (ngSubmit)="onSubmit()">
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Email</mat-label>
            <input
              matInput
              formControlName="email"
              type="email"
              autocomplete="email"
              aria-label="Email address"
            />
            @if (form.controls.email.hasError('required') && form.controls.email.touched) {
              <mat-error>Email is required</mat-error>
            } @else if (form.controls.email.hasError('email')) {
              <mat-error>Enter a valid email address</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Password</mat-label>
            <input
              matInput
              formControlName="password"
              [type]="hidePassword() ? 'password' : 'text'"
              autocomplete="current-password"
              aria-label="Password"
            />
            <button
              mat-icon-button
              matSuffix
              type="button"
              (click)="hidePassword.set(!hidePassword())"
              [attr.aria-label]="hidePassword() ? 'Show password' : 'Hide password'"
            >
              <mat-icon>{{ hidePassword() ? 'visibility_off' : 'visibility' }}</mat-icon>
            </button>
            @if (form.controls.password.hasError('required') && form.controls.password.touched) {
              <mat-error>Password is required</mat-error>
            }
          </mat-form-field>

          <button
            mat-flat-button
            color="primary"
            type="submit"
            class="full-width submit-btn"
            [disabled]="store.loading()"
            aria-label="Sign in"
          >
            @if (store.loading()) {
              <mat-spinner diameter="20" />
            } @else {
              Sign in
            }
          </button>
        </form>
      </mat-card-content>

      <mat-card-actions align="end">
        <a mat-button routerLink="/register" aria-label="Create a new account">
          Create Account
        </a>
      </mat-card-actions>
    </mat-card>
  `,
  styles: `
    :host {
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100%;
      padding: 24px;
    }
    .auth-card {
      width: 100%;
      max-width: 420px;
    }
    mat-card-header {
      justify-content: center;
      margin-bottom: 16px;
    }
    .full-width {
      width: 100%;
    }
    .submit-btn {
      margin-top: 8px;
      height: 48px;
    }
    .error-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      margin-bottom: 16px;
      border-radius: 4px;
      background-color: #fdecea;
      color: #611a15;
    }
    :host-context(body.dark-theme) .error-banner {
      background-color: #3d1c1c;
      color: #f5c6cb;
    }
    .info-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      margin-bottom: 16px;
      border-radius: 4px;
      background-color: #e3f2fd;
      color: #0d47a1;
    }
    :host-context(body.dark-theme) .info-banner {
      background-color: #1a237e;
      color: #bbdefb;
    }
    mat-card-actions {
      padding: 8px 0;
    }
  `,
})
export class LoginPageComponent implements OnInit {
  protected readonly store = inject(AuthStore);
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);

  protected readonly hidePassword = signal(true);
  protected readonly sessionMessage = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  private static readonly KNOWN_MESSAGES: Record<string, string> = {
    'session-expired': 'Your session has expired due to inactivity.',
    'logged-out': 'You have been logged out.',
  };

  ngOnInit(): void {
    this.store.clearError();
    const messageKey = this.route.snapshot.queryParamMap.get('message');
    if (messageKey && LoginPageComponent.KNOWN_MESSAGES[messageKey]) {
      this.sessionMessage.set(LoginPageComponent.KNOWN_MESSAGES[messageKey]);
    }
  }

  onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.store.login(this.form.getRawValue());
  }
}
