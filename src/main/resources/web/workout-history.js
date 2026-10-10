const STORAGE_KEY = 'stepwise.workouts.v1';
let accountId = 'anonymous';

export const WorkoutHistory = {
  setAccount(id) { accountId = String(id); },
  storageKey() { return `${STORAGE_KEY}.${accountId}`; },
  all() {
    try {
      const items = JSON.parse(localStorage.getItem(this.storageKey()) || '[]');
      return Array.isArray(items) ? items : [];
    } catch { return []; }
  },
  save(workout) {
    const items = [workout, ...this.all()].slice(0, 100);
    localStorage.setItem(this.storageKey(), JSON.stringify(items));
    return items;
  },
  clear() { localStorage.removeItem(this.storageKey()); }
};
