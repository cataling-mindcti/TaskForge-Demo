import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthStore } from '../store/auth.store';

function passwordStrengthValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value as string;
  if (!value) return null;

  const errors: ValidationErrors = {};
  if (value.length < 8) errors['minlength'] = true;
  if (!/[A-Z]/.test(value)) errors['uppercase'] = true;
  if (!/[a-z]/.test(value)) errors['lowercase'] = true;
  if (!/\d/.test(value)) errors['digit'] = true;

  return Object.keys(errors).length ? errors : null;
}

function passwordMatchValidator(group: AbstractControl): ValidationErrors | null {
  const password = group.get('password')?.value as string;
  const confirmCtrl = group.get('confirmPassword');
  const confirm = confirmCtrl?.value as string;
  if (!password || !confirm) return null;
  if (password !== confirm) {
    confirmCtrl?.setErrors({ ...confirmCtrl.errors, passwordMismatch: true });
    return { passwordMismatch: true };
  }
  // Clear only the mismatch error, preserve other errors
  if (confirmCtrl?.hasError('passwordMismatch')) {
    const { passwordMismatch, ...rest } = confirmCtrl.errors!;
    confirmCtrl.setErrors(Object.keys(rest).length ? rest : null);
  }
  return null;
}

@Component({
  selector: 'app-register-page',
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
        <mat-card-title>Create your account</mat-card-title>
      </mat-card-header>

      <mat-card-content>
        @if (store.error()) {
          <div class="error-banner" role="alert">
            <mat-icon>error_outline</mat-icon>
            <span>{{ store.error() }}</span>
          </div>
        }

        <form [formGroup]="form" (ngSubmit)="onSubmit()">
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Full Name</mat-label>
            <input
              matInput
              formControlName="fullName"
              type="text"
              autocomplete="name"
              maxlength="100"
              aria-label="Full name (optional)"
            />
            @if (form.controls.fullName.hasError('maxlength')) {
              <mat-error>Name must be 100 characters or less</mat-error>
            }
            <mat-hint>Optional</mat-hint>
          </mat-form-field>

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
              autocomplete="new-password"
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
          </mat-form-field>

          @if (form.controls.password.value && form.controls.password.touched) {
            <div class="password-requirements" aria-label="Password requirements">
              <p class="requirement" [class.met]="!form.controls.password.hasError('minlength') && form.controls.password.value.length >= 8">
                <mat-icon>{{ form.controls.password.value.length >= 8 ? 'check_circle' : 'cancel' }}</mat-icon>
                At least 8 characters
              </p>
              <p class="requirement" [class.met]="!form.controls.password.hasError('uppercase')">
                <mat-icon>{{ !form.controls.password.hasError('uppercase') ? 'check_circle' : 'cancel' }}</mat-icon>
                One uppercase letter
              </p>
              <p class="requirement" [class.met]="!form.controls.password.hasError('lowercase')">
                <mat-icon>{{ !form.controls.password.hasError('lowercase') ? 'check_circle' : 'cancel' }}</mat-icon>
                One lowercase letter
              </p>
              <p class="requirement" [class.met]="!form.controls.password.hasError('digit')">
                <mat-icon>{{ !form.controls.password.hasError('digit') ? 'check_circle' : 'cancel' }}</mat-icon>
                One digit
              </p>
            </div>
          }

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Confirm Password</mat-label>
            <input
              matInput
              formControlName="confirmPassword"
              [type]="hidePassword() ? 'password' : 'text'"
              autocomplete="new-password"
              aria-label="Confirm password"
            />
            @if (form.hasError('passwordMismatch') && form.controls.confirmPassword.touched) {
              <mat-error>Passwords do not match</mat-error>
            }
          </mat-form-field>

          <button
            mat-flat-button
            color="primary"
            type="submit"
            class="full-width submit-btn"
            [disabled]="store.loading()"
            aria-label="Create account"
          >
            @if (store.loading()) {
              <mat-spinner diameter="20" />
            } @else {
              Create Account
            }
          </button>
        </form>
      </mat-card-content>

      <mat-card-actions align="end">
        <a mat-button routerLink="/login" aria-label="Go to sign in page">
          Already have an account? Sign in
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
    .password-requirements {
      margin: -8px 0 16px;
      padding: 0 4px;
    }
    .requirement {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 4px 0;
      font-size: 0.8rem;
      color: #d32f2f;
    }
    .requirement.met {
      color: #388e3c;
    }
    :host-context(body.dark-theme) .requirement {
      color: #ef9a9a;
    }
    :host-context(body.dark-theme) .requirement.met {
      color: #81c784;
    }
    .requirement mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
    }
    mat-card-actions {
      padding: 8px 0;
    }
  `,
})
export class RegisterPageComponent implements OnInit {
  protected readonly store = inject(AuthStore);
  private readonly fb = inject(FormBuilder);

  protected readonly hidePassword = signal(true);

  protected readonly form = this.fb.nonNullable.group(
    {
      fullName: ['', [Validators.maxLength(100)]],
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, passwordStrengthValidator]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: passwordMatchValidator }
  );

  ngOnInit(): void {
    this.store.clearError();
  }

  onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { fullName, email, password, confirmPassword } = this.form.getRawValue();
    this.store.register({
      email,
      password,
      confirmPassword,
      ...(fullName ? { fullName } : {}),
    });
  }
}
