export class TrackerMap {
  constructor(element) {
    // Start with a neutral world view; only real device coordinates create a location marker.
    this.map = L.map(element, { zoomControl: false, preferCanvas: true }).fitWorld();
    this.tiles = {};
    this.addTileLayer('dark');
    L.control.zoom({ position: 'topright' }).addTo(this.map);
    L.control.scale({ imperial: false, position: 'bottomright' }).addTo(this.map);
    this.route = L.polyline([], { color: '#4b91ff', weight: 5, opacity: .95, lineCap: 'round', lineJoin: 'round' }).addTo(this.map);
    this.positionMarker = null;
    this.autoCenter = true;
    this.routeVisible = true;
  }
  addTileLayer(style) {
    if (!this.tiles.osm) {
      this.tiles.osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>'
      });
    }
    if (!this.map.hasLayer(this.tiles.osm)) this.tiles.osm.addTo(this.map);
    this.map.getContainer().classList.toggle('dark-map-tiles', style === 'dark');
  }
  setStyle(style) { this.addTileLayer(style); }
  setAutoCenter(enabled) { this.autoCenter = enabled; }
  setRouteVisible(enabled) { this.routeVisible = enabled; this.route.setStyle({ opacity: enabled ? .95 : 0 }); }
  updatePosition(position, routePoints) {
    const point = [position.latitude, position.longitude];
    if (!this.positionMarker) {
      this.positionMarker = L.marker(point, {
        icon: L.divIcon({ className: '', html: '<div class="location-pin"></div>', iconSize: [22, 22], iconAnchor: [11, 11] }),
        title: 'Your current location'
      }).addTo(this.map);
      this.map.setView(point, 17, { animate: true });
    } else {
      this.positionMarker.setLatLng(point);
      if (this.autoCenter) this.map.panTo(point, { animate: true, duration: .35 });
    }
    this.route.setLatLngs(routePoints.map(item => [item.latitude, item.longitude]));
  }
  restoreRoute(routePoints) {
    this.route.setLatLngs(routePoints.map(item => [item.latitude, item.longitude]));
    if (routePoints.length) {
      const last = routePoints.at(-1);
      this.positionMarker = L.marker([last.latitude, last.longitude], {
        icon: L.divIcon({ className: '', html: '<div class="location-pin"></div>', iconSize: [22, 22], iconAnchor: [11, 11] }),
        title: 'Last workout location'
      }).addTo(this.map);
      this.map.fitBounds(this.route.getBounds().pad(.18), { maxZoom: 16 });
    }
  }
  recenter() {
    if (this.positionMarker) this.map.setView(this.positionMarker.getLatLng(), Math.max(this.map.getZoom(), 16), { animate: true });
  }
  clear() {
    this.route.setLatLngs([]);
    if (this.positionMarker) this.map.removeLayer(this.positionMarker);
    this.positionMarker = null;
  }
  invalidateSize() { setTimeout(() => this.map.invalidateSize(), 150); }
}
