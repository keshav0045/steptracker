export class WorkoutTimer {
  constructor() { this.startedAt = 0; this.elapsedBeforeStart = 0; this.running = false; }
  start() {
    if (this.running) return;
    this.startedAt = performance.now();
    this.running = true;
  }
  pause() {
    if (!this.running) return;
    this.elapsedBeforeStart += performance.now() - this.startedAt;
    this.running = false;
  }
  reset() { this.startedAt = 0; this.elapsedBeforeStart = 0; this.running = false; }
  elapsedMs() { return this.elapsedBeforeStart + (this.running ? performance.now() - this.startedAt : 0); }
  elapsedSeconds() { return Math.floor(this.elapsedMs() / 1000); }
  format() {
    const total = this.elapsedSeconds();
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    return [hours, minutes, seconds].map(n => String(n).padStart(2, '0')).join(':');
  }
}
