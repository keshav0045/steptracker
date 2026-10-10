export class GpsTracker {
  constructor({ onPosition, onError }) {
    this.onPosition = onPosition;
    this.onError = onError;
    this.watchId = null;
  }
  start() {
    if (!('geolocation' in navigator)) {
      this.onError({ code: 0, message: 'This browser does not provide GPS location.' });
      return false;
    }
    if (this.watchId !== null) return true;
    try {
      this.watchId = navigator.geolocation.watchPosition(
        position => this.onPosition({
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy: position.coords.accuracy,
          timestamp: position.timestamp
        }),
        error => this.onError(error),
        { enableHighAccuracy: true, maximumAge: 0, timeout: 20000 }
      );
      return true;
    } catch (error) {
      this.onError({ code: 0, message: error.message || 'Unable to start GPS tracking.' });
      return false;
    }
  }
  stop() {
    if (this.watchId !== null && 'geolocation' in navigator) navigator.geolocation.clearWatch(this.watchId);
    this.watchId = null;
  }
}
