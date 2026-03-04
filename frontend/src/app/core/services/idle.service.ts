import {
  DestroyRef,
  inject,
  Injectable,
  NgZone,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { fromEvent, merge, throttleTime } from 'rxjs';

const IDLE_WARNING_MS = 25 * 60 * 1000; // 25 minutes
const IDLE_EXPIRY_MS = 30 * 60 * 1000; // 30 minutes
const CHECK_INTERVAL_MS = 30 * 1000; // check every 30 seconds

export type IdleState = 'active' | 'warning' | 'expired';

@Injectable({ providedIn: 'root' })
export class IdleService {
  private readonly ngZone = inject(NgZone);
  private readonly destroyRef = inject(DestroyRef);

  private lastActivity = Date.now();
  private checkTimer: ReturnType<typeof setInterval> | null = null;

  readonly state = signal<IdleState>('active');

  start(): void {
    this.stop(); // Clean up any previous timers
    this.lastActivity = Date.now();
    this.state.set('active');
    this.listenForActivity();
    this.startChecking();
  }

  stop(): void {
    if (this.checkTimer) {
      clearInterval(this.checkTimer);
      this.checkTimer = null;
    }
    this.state.set('active');
  }

  resetActivity(): void {
    this.lastActivity = Date.now();
    if (this.state() === 'warning') {
      this.state.set('active');
    }
  }

  private listenForActivity(): void {
    this.ngZone.runOutsideAngular(() => {
      merge(
        fromEvent(document, 'mousemove'),
        fromEvent(document, 'keydown'),
        fromEvent(document, 'scroll'),
        fromEvent(document, 'click'),
        fromEvent(document, 'touchstart')
      )
        .pipe(throttleTime(5000), takeUntilDestroyed(this.destroyRef))
        .subscribe(() => {
          this.lastActivity = Date.now();
          if (this.state() !== 'active') {
            this.ngZone.run(() => this.state.set('active'));
          }
        });
    });
  }

  private startChecking(): void {
    this.ngZone.runOutsideAngular(() => {
      this.checkTimer = setInterval(() => {
        const elapsed = Date.now() - this.lastActivity;

        if (elapsed >= IDLE_EXPIRY_MS) {
          this.ngZone.run(() => this.state.set('expired'));
        } else if (elapsed >= IDLE_WARNING_MS) {
          this.ngZone.run(() => {
            if (this.state() !== 'warning') {
              this.state.set('warning');
            }
          });
        }
      }, CHECK_INTERVAL_MS);
    });

    this.destroyRef.onDestroy(() => this.stop());
  }
}
