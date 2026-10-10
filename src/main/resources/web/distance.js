const EARTH_RADIUS_METERS = 6_371_000;

export function haversineKm(a, b) {
  const radians = value => value * Math.PI / 180;
  const dLat = radians(b.latitude - a.latitude);
  const dLon = radians(b.longitude - a.longitude);
  const lat1 = radians(a.latitude);
  const lat2 = radians(b.latitude);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, h))) / 1000;
}

export const kmToDisplay = (km, unit) => unit === 'mi' ? km * 0.621371 : km;
export const displayToKm = (distance, unit) => unit === 'mi' ? distance / 0.621371 : distance;
export const unitLabel = unit => unit === 'mi' ? 'mi' : 'km';
export const formatDistance = (km, unit, digits = 2) => kmToDisplay(km, unit).toFixed(digits);
