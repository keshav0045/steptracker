import { displayToKm, formatDistance, haversineKm, kmToDisplay, unitLabel } from './distance.js';
import { GpsTracker } from './gps-tracker.js';
import { TrackerMap } from './tracker-map.js';
import { WorkoutHistory } from './workout-history.js';
import { WorkoutTimer } from './workout-timer.js';

const $ = selector => document.querySelector(selector);
const API_BASE_KEY = 'stepwise.api-base.v1';
let authMode = 'signin';
const isAndroidApp = location.hostname === 'appassets.androidplatform.net';
let apiBase = isAndroidApp ? localStorage.getItem(API_BASE_KEY) || '' : location.origin;
if (isAndroidApp && apiBase) {
  try {
    const savedApi = new URL(apiBase);
    if (savedApi.protocol !== 'https:' || savedApi.username || savedApi.password || !['', '/'].includes(savedApi.pathname) || savedApi.search || savedApi.hash) apiBase = '';
    else apiBase = savedApi.origin;
  } catch { apiBase = ''; }
}
const state = {
  targetKm: 10, activity: 'walking', unit: 'km', mapStyle: 'dark', autoCenter: true,
  routeVisible: true, distanceKm: 0, points: [], lastAccepted: null, accuracy: null,
  mode: 'idle', reachedTarget: false, pendingWorkout: null, toastTimeout: null, account: null
};
const timer = new WorkoutTimer();
const timerDisplay = $('#timer-readout');
const gpsStatus = $('#gps-status');
const statusIndicator = $('#status-indicator');
const accuracyLabel = $('#accuracy-label');
let mapView;
let gps;
let ticker;

try {
  if (!window.L) throw new Error('Map library did not load. Check your internet connection and reload this page.');
  mapView = new TrackerMap($('#map'));
} catch (error) {
  gpsStatus.textContent = error.message;
  statusIndicator.classList.add('error');
  $('#start-button').disabled = true;
}

gps = new GpsTracker({ onPosition: handlePosition, onError: handleGpsError });

function showToast(message) {
  const toast = $('#toast');
  toast.textContent = message;
  toast.classList.add('visible');
  clearTimeout(state.toastTimeout);
  state.toastTimeout = setTimeout(() => toast.classList.remove('visible'), 3200);
}

async function apiRequest(path, options = {}) {
  if (!apiBase) throw new Error('Connect this app to your secure Stepwise API server first.');
  if (isAndroidApp && new URL(apiBase).protocol !== 'https:') throw new Error('Android account access requires an HTTPS API server.');
  const response = await fetch(`${apiBase}${path}`, {
    ...options,
    credentials: 'include',
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) }
  });
  let payload = {};
  try { payload = await response.json(); } catch { /* handled as a generic response below */ }
  if (!response.ok) throw new Error(payload.error?.message || `Request failed (${response.status}).`);
  return payload;
}

function showAuth(mode = 'signin', error = '') {
  authMode = mode;
  $('#tracker-app').hidden = true;
  $('#auth-screen').hidden = false;
  const creating = mode === 'signup';
  $('#auth-title').textContent = creating ? 'Create your account' : 'Welcome back';
  $('#auth-description').textContent = creating ? 'Your workouts and progress are kept with your account.' : 'Sign in to continue to Stepwise.';
  $('#auth-submit').textContent = creating ? 'Create account' : 'Sign in';
  $('#auth-switch-text').textContent = creating ? 'Already have an account?' : 'New to Stepwise?';
  $('#auth-switch-button').textContent = creating ? 'Sign in' : 'Create account';
  $('#auth-password').autocomplete = creating ? 'new-password' : 'current-password';
  $('#auth-password').minLength = creating ? 12 : 1;
  $('#confirm-password-group').hidden = !creating;
  $('#auth-confirm-password').required = creating;
  $('#auth-error').textContent = error;
  $('#api-setup').hidden = !isAndroidApp;
  $('#auth-submit').disabled = isAndroidApp && !apiBase;
}

function setSignedIn(user) {
  state.account = user;
  WorkoutHistory.setAccount(user.id);
  $('#account-email').textContent = user.email;
  $('#account-email').title = user.email;
  $('#auth-screen').hidden = true;
  $('#tracker-app').hidden = false;
}

async function initializeAuth() {
  if (isAndroidApp) $('#api-server-url').value = apiBase;
  showAuth('signin');
  if (!apiBase) {
    $('#auth-error').textContent = 'Enter your HTTPS API server above, then connect.';
    return;
  }
  try {
    const user = await apiRequest('/api/auth/me');
    setSignedIn(user);
  } catch (error) {
    if (/sign in to continue/i.test(error.message)) showAuth('signin');
    else showAuth('signin', `Could not reach the account service: ${error.message}`);
  }
}

function openDialog(id) {
  const dialog = document.getElementById(id);
  if (dialog && !dialog.open) dialog.showModal();
}

function closeDialog(id) {
  const dialog = document.getElementById(id);
  if (dialog?.open) dialog.close();
}

function showGpsStatus(message, kind = 'ready') {
  gpsStatus.textContent = message;
  statusIndicator.classList.toggle('active', kind === 'active');
  statusIndicator.classList.toggle('error', kind === 'error');
}

function setTarget(targetKm) {
  state.targetKm = Math.max(0.5, Math.min(200, targetKm));
  const targetNowReached = state.distanceKm >= state.targetKm && state.distanceKm > 0;
  if (targetNowReached && !state.reachedTarget && (state.mode === 'tracking' || state.mode === 'paused')) {
    showToast('Target distance reached. Great work!');
  }
  state.reachedTarget = targetNowReached;
  updateTargetUI();
  updateProgressUI();
}

function updateTargetUI() {
  const displayValue = kmToDisplay(state.targetKm, state.unit);
  $('#target-value').textContent = displayValue.toFixed(1);
  $('#target-unit').textContent = unitLabel(state.unit);
  const preset = [1, 2, 5, 10, 15].find(value => Math.abs(displayValue - value) < 0.06);
  $('#target-preset').value = preset ? String(preset) : 'custom';
  const unit = unitLabel(state.unit);
  [...$('#target-preset').options].forEach(option => {
    if (option.value !== 'custom') option.textContent = `${option.value} ${unit}`;
  });
  $('.input-label .small-unit').textContent = unit;
  document.querySelectorAll('.small-unit').forEach(element => { element.textContent = unit.toUpperCase(); });
  updateProgressUI();
}

function updateProgressUI() {
  const progress = Math.min(100, state.distanceKm / state.targetKm * 100);
  const remaining = Math.max(0, state.targetKm - state.distanceKm);
  $('#distance-readout').textContent = formatDistance(state.distanceKm, state.unit, 2);
  $('#distance-unit').textContent = unitLabel(state.unit);
  $('#remaining-label').textContent = `${formatDistance(remaining, state.unit)} ${unitLabel(state.unit)} left`;
  $('#progress-label').textContent = `${Math.floor(progress)}% complete`;
  $('#progress-fill').style.width = `${progress}%`;
  $('#pace-readout').textContent = state.distanceKm > 0
    ? (timer.elapsedMs() / 60_000 / kmToDisplay(state.distanceKm, state.unit)).toFixed(1)
    : '—';
  $('#completion-banner')?.classList.toggle('visible', state.reachedTarget);
}

function resetWorkout() {
  gps.stop();
  clearInterval(ticker);
  ticker = null;
  timer.reset();
  state.mode = 'idle';
  state.distanceKm = 0;
  state.points = [];
  state.lastAccepted = null;
  state.accuracy = null;
  state.reachedTarget = false;
  state.pendingWorkout = null;
  mapView?.clear();
  timerDisplay.textContent = '00:00:00';
  accuracyLabel.textContent = 'Waiting for location permission';
  showGpsStatus('Ready when you are');
  updateStartButton();
  updateProgressUI();
}

function updateStartButton() {
  const button = $('#start-button');
  button.classList.remove('is-active', 'is-paused');
  if (state.mode === 'tracking') {
    button.innerHTML = '<span class="start-glyph">Ⅱ</span><span>Pause</span>';
    button.classList.add('is-active');
  } else if (state.mode === 'paused') {
    button.innerHTML = '<span class="start-glyph">▶</span><span>Resume</span>';
    button.classList.add('is-paused');
  } else {
    button.innerHTML = '<span class="start-glyph">▶</span><span>Start</span>';
  }
  $('#options-button').classList.toggle('stop-ready', state.mode !== 'idle');
  $('#stop-workout-button').classList.toggle('visible', state.mode === 'tracking' || state.mode === 'paused');
}

function startWorkout() {
  if (!mapView) return;
  state.distanceKm = 0;
  state.points = [];
  state.lastAccepted = null;
  state.accuracy = null;
  state.reachedTarget = false;
  mapView.clear();
  timer.reset();
  timer.start();
  state.mode = 'tracking';
  const started = gps.start();
  if (!started) {
    timer.reset();
    state.mode = 'idle';
    updateStartButton();
    return;
  }
  showGpsStatus('Requesting location…', 'active');
  accuracyLabel.textContent = 'Waiting for GPS fix';
  updateStartButton();
  updateProgressUI();
  ticker = setInterval(() => {
    timerDisplay.textContent = timer.format();
    updateProgressUI();
  }, 1000);
}

function resumeWorkout() {
  timer.start();
  state.mode = 'tracking';
  gps.start();
  showGpsStatus('Tracking your route', 'active');
  updateStartButton();
  ticker = setInterval(() => {
    timerDisplay.textContent = timer.format();
    updateProgressUI();
  }, 1000);
}

function pauseWorkout() {
  gps.stop();
  timer.pause();
  clearInterval(ticker);
  ticker = null;
  state.mode = 'paused';
  timerDisplay.textContent = timer.format();
  showGpsStatus('Paused');
  updateStartButton();
  updateProgressUI();
}

function handlePosition(position) {
  if (state.mode !== 'tracking') return;
  state.accuracy = position.accuracy;
  if (position.accuracy > 60) {
    accuracyLabel.textContent = `GPS accuracy ±${Math.round(position.accuracy)} m · waiting for a clearer fix`;
    showGpsStatus('Weak GPS signal', 'active');
    return;
  }
  const previous = state.lastAccepted;
  if (previous) {
    const deltaKm = haversineKm(previous, position);
    const elapsedSeconds = Math.max(1, (position.timestamp - previous.timestamp) / 1000);
    // Discard implausible GPS jumps while retaining a sensible previous fix.
    if (deltaKm > Math.max(0.12, elapsedSeconds * 0.012)) {
      accuracyLabel.textContent = `GPS accuracy ±${Math.round(position.accuracy)} m · filtering a location jump`;
      return;
    }
    if (deltaKm >= 0.0005) state.distanceKm += deltaKm;
  }
  state.lastAccepted = position;
  state.points.push({ latitude: position.latitude, longitude: position.longitude, timestamp: position.timestamp });
  mapView?.updatePosition(position, state.points);
  accuracyLabel.textContent = `GPS accuracy ±${Math.round(position.accuracy)} m`;
  showGpsStatus('Tracking your route', 'active');
  updateProgressUI();
  if (!state.reachedTarget && state.distanceKm >= state.targetKm) {
    state.reachedTarget = true;
    updateProgressUI();
    showToast('Target distance reached. Great work!');
  }
}

function handleGpsError(error) {
  const permissionDenied = error.code === 1;
  const messages = {
    1: 'Location permission was denied. Allow location access in your browser settings.',
    2: 'Your location is unavailable. Check that device location services are enabled.',
    3: 'GPS is taking too long. Move to an open area and try again.'
  };
  const message = messages[error.code] || error.message || 'Unable to read your location.';
  showGpsStatus(message, permissionDenied || error.code === 2 ? 'error' : 'ready');
  accuracyLabel.textContent = 'Location access is needed for GPS tracking';
  showToast(message);
  if (permissionDenied && state.mode === 'tracking' && state.points.length === 0) {
    gps.stop();
    timer.reset();
    clearInterval(ticker);
    ticker = null;
    state.mode = 'idle';
    timerDisplay.textContent = '00:00:00';
    updateStartButton();
  }
}

function stopWorkout(showSummary = true) {
  if (state.mode === 'idle') return;
  gps.stop();
  timer.pause();
  clearInterval(ticker);
  ticker = null;
  state.mode = 'summary';
  state.pendingWorkout = {
    id: crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    date: new Date().toISOString(),
    activity: state.activity,
    targetKm: state.targetKm,
    distanceKm: state.distanceKm,
    durationSeconds: timer.elapsedSeconds(),
    route: [...state.points],
    completed: state.distanceKm >= state.targetKm
  };
  showGpsStatus('Workout stopped');
  fillSummary(state.pendingWorkout);
  closeDialog('options-dialog');
  updateStartButton();
  if (showSummary) openDialog('summary-dialog');
}

function fillSummary(workout) {
  const pace = workout.distanceKm > 0 ? workout.durationSeconds / 60 / kmToDisplay(workout.distanceKm, state.unit) : null;
  $('#summary-title').textContent = activityName(workout.activity);
  $('#summary-distance').textContent = `${formatDistance(workout.distanceKm, state.unit)} ${unitLabel(state.unit)}`;
  $('#summary-duration').textContent = formatDuration(workout.durationSeconds);
  $('#summary-pace').textContent = pace === null ? '—' : `${pace.toFixed(1)} min/${unitLabel(state.unit)}`;
  $('#summary-completion').textContent = `${Math.min(100, Math.floor(workout.distanceKm / workout.targetKm * 100))}%`;
  $('#summary-route-note').textContent = workout.route.length > 1
    ? `GPS route captured with ${workout.route.length} location points.`
    : 'A route could not be recorded without a GPS fix.';
}

function formatDuration(seconds) {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remainder = seconds % 60;
  return [hours, minutes, remainder].map(value => String(value).padStart(2, '0')).join(':');
}

function activityName(activity) {
  return ({ walking: 'Outdoor Walking', running: 'Running', cycling: 'Cycling' })[activity] || activity;
}

function renderHistory() {
  const items = WorkoutHistory.all();
  const list = $('#history-list');
  list.replaceChildren();
  if (!items.length) {
    const empty = document.createElement('div');
    empty.className = 'empty-history';
    empty.textContent = 'Your completed workouts will appear here.';
    list.append(empty);
    return;
  }
  for (const item of items) {
    const card = document.createElement('article');
    card.className = 'history-item';
    const header = document.createElement('div');
    header.className = 'history-item-head';
    const title = document.createElement('strong');
    title.textContent = activityName(item.activity);
    const date = document.createElement('time');
    const parsedDate = new Date(item.date);
    date.dateTime = item.date;
    date.textContent = Number.isNaN(parsedDate.valueOf()) ? 'Unknown date' : parsedDate.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
    header.append(title, date);
    const metrics = document.createElement('div');
    metrics.className = 'history-metrics';
    const distance = `${formatDistance(item.distanceKm, state.unit)} ${unitLabel(state.unit)}`;
    const pace = item.distanceKm > 0 ? `${(item.durationSeconds / 60 / kmToDisplay(item.distanceKm, state.unit)).toFixed(1)} min/${unitLabel(state.unit)}` : '— pace';
    [distance, formatDuration(item.durationSeconds), pace, item.completed ? 'Target complete' : 'Target not reached'].forEach(value => {
      const span = document.createElement('span'); span.textContent = value; metrics.append(span);
    });
    card.append(header, metrics);
    list.append(card);
  }
}

function beginFromButton() {
  if (state.mode === 'idle' || state.mode === 'summary') startWorkout();
  else if (state.mode === 'tracking') pauseWorkout();
  else if (state.mode === 'paused') resumeWorkout();
}

$('#target-minus').addEventListener('click', () => setTarget(state.targetKm - displayToKm(0.5, state.unit)));
$('#target-plus').addEventListener('click', () => setTarget(state.targetKm + displayToKm(0.5, state.unit)));
$('#target-preset').addEventListener('change', event => {
  if (event.target.value === 'custom') {
    $('#custom-target-input').value = kmToDisplay(state.targetKm, state.unit).toFixed(1);
    openDialog('target-dialog');
  } else setTarget(displayToKm(Number(event.target.value), state.unit));
});
$('#custom-target-button').addEventListener('click', () => {
  $('#custom-target-input').value = kmToDisplay(state.targetKm, state.unit).toFixed(1);
  openDialog('target-dialog');
});
$('#save-target-button').addEventListener('click', () => {
  const value = Number($('#custom-target-input').value);
  if (!Number.isFinite(value) || value < 0.5 || value > 200) {
    showToast(`Choose a target between 0.5 and 200 ${unitLabel(state.unit)}.`);
    return;
  }
  setTarget(displayToKm(value, state.unit));
  closeDialog('target-dialog');
});
$('#activity-select').addEventListener('change', event => { state.activity = event.target.value; });
$('#start-button').addEventListener('click', beginFromButton);
$('#auth-switch-button').addEventListener('click', () => {
  authMode = authMode === 'signin' ? 'signup' : 'signin';
  showAuth(authMode);
});
$('#auth-form').addEventListener('submit', async event => {
  event.preventDefault();
  const email = $('#auth-email').value.trim();
  const password = $('#auth-password').value;
  const confirmation = $('#auth-confirm-password').value;
  if (!$('#auth-email').checkValidity()) { showAuth(authMode, 'Enter a valid email address.'); return; }
  if (authMode === 'signup' && password.length < 12) { showAuth(authMode, 'Use a password with at least 12 characters.'); return; }
  if (authMode === 'signup' && password !== confirmation) { showAuth(authMode, 'The passwords do not match.'); return; }
  $('#auth-submit').disabled = true;
  $('#auth-error').textContent = '';
  try {
    const user = await apiRequest(`/api/auth/${authMode}`, { method: 'POST', body: JSON.stringify({ email, password }) });
    $('#auth-password').value = '';
    $('#auth-confirm-password').value = '';
    setSignedIn(user);
  } catch (error) {
    $('#auth-error').textContent = error.message;
  } finally {
    $('#auth-submit').disabled = isAndroidApp && !apiBase;
  }
});
$('#connect-api-button').addEventListener('click', async () => {
  const button = $('#connect-api-button');
  try {
    const url = new URL($('#api-server-url').value.trim());
    if (url.protocol !== 'https:' || url.username || url.password || !['', '/'].includes(url.pathname) || url.search || url.hash) {
      throw new Error('Use the HTTPS origin only, such as https://tracker.example.com.');
    }
    button.disabled = true;
    apiBase = url.origin;
    await apiRequest('/health');
    localStorage.setItem(API_BASE_KEY, apiBase);
    $('#auth-submit').disabled = false;
    $('#auth-error').textContent = 'API connected. Sign in or create your account.';
  } catch (error) {
    apiBase = '';
    $('#auth-submit').disabled = true;
    $('#auth-error').textContent = error.message;
  } finally { button.disabled = false; }
});
async function signOut(saveWorkout) {
  $('#signout-button').disabled = true;
  try {
    if (saveWorkout && (state.mode === 'tracking' || state.mode === 'paused')) stopWorkout(false);
    if (state.pendingWorkout && saveWorkout) WorkoutHistory.save(state.pendingWorkout);
    if (!saveWorkout && (state.mode !== 'idle' || state.pendingWorkout)) resetWorkout();
    if (saveWorkout && state.pendingWorkout) resetWorkout();
    closeDialog('summary-dialog');
    closeDialog('signout-dialog');
    await apiRequest('/api/auth/logout', { method: 'POST', body: '{}' });
    state.account = null;
    showAuth('signin', 'You have signed out.');
  } catch (error) {
    showToast(`Could not sign out: ${error.message}`);
  }
  finally { $('#signout-button').disabled = false; }
}
$('#signout-button').addEventListener('click', () => {
  if (state.mode !== 'idle' || state.pendingWorkout) openDialog('signout-dialog');
  else signOut(false);
});
$('#signout-save-workout').addEventListener('click', () => signOut(true));
$('#signout-discard-workout').addEventListener('click', () => signOut(false));
$('#signout-cancel').addEventListener('click', () => closeDialog('signout-dialog'));
$('#signout-dialog').addEventListener('close', () => {
  if (state.pendingWorkout && !$('#summary-dialog').open) openDialog('summary-dialog');
});
$('#summary-signout').addEventListener('click', () => {
  closeDialog('summary-dialog');
  openDialog('signout-dialog');
});
$('#settings-button').addEventListener('click', () => openDialog('settings-dialog'));
$('#history-button').addEventListener('click', () => { renderHistory(); openDialog('history-dialog'); });
$('#options-button').addEventListener('click', () => openDialog('options-dialog'));
$('#unit-setting').addEventListener('change', event => {
  state.unit = event.target.value;
  updateTargetUI(); updateProgressUI();
  if (state.pendingWorkout) fillSummary(state.pendingWorkout);
  if ($('#history-dialog').open) renderHistory();
});
$('#map-style-setting').addEventListener('change', event => { state.mapStyle = event.target.value; mapView?.setStyle(state.mapStyle); });
$('#autocenter-setting').addEventListener('change', event => { state.autoCenter = event.target.checked; mapView?.setAutoCenter(state.autoCenter); });
$('#route-visible-setting').addEventListener('change', event => { state.routeVisible = event.target.checked; mapView?.setRouteVisible(state.routeVisible); });
$('#recenter-button').addEventListener('click', () => { mapView?.recenter(); closeDialog('options-dialog'); });
$('#clear-history-button').addEventListener('click', () => {
  if (!confirm('Clear all saved workout history from this browser?')) return;
  try { WorkoutHistory.clear(); renderHistory(); showToast('Workout history cleared.'); }
  catch { showToast('The browser could not clear local workout history.'); }
});
$('#stop-workout-button').addEventListener('click', stopWorkout);
$('#save-workout').addEventListener('click', () => {
  if (!state.pendingWorkout) return;
  try {
    WorkoutHistory.save(state.pendingWorkout);
    const saved = state.pendingWorkout;
    closeDialog('summary-dialog');
    state.pendingWorkout = null;
    state.mode = 'idle';
    updateStartButton();
    showToast(saved.completed ? 'Workout saved — target completed!' : 'Workout saved to this device.');
  } catch { showToast('Could not save workout. Check this browser’s local storage settings.'); }
});
$('#discard-workout').addEventListener('click', () => { closeDialog('summary-dialog'); resetWorkout(); });
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => closeDialog(button.dataset.close)));
document.querySelectorAll('dialog').forEach(dialog => dialog.addEventListener('click', event => {
  if (event.target === dialog) dialog.close();
}));
$('#summary-dialog').addEventListener('cancel', event => event.preventDefault());

setTarget(state.targetKm);
$('#unit-setting').value = state.unit;
$('#map-style-setting').value = state.mapStyle;
$('#activity-select').value = state.activity;
if (mapView) requestAnimationFrame(() => mapView.invalidateSize());
initializeAuth();
