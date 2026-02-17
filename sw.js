var CACHE_NAME = 'iprint-v7';
var ASSETS = [
  '/Print-app/',
  '/Print-app/index.html',
  '/Print-app/styles.css',
  '/Print-app/app.js',
  '/Print-app/icon-192.png',
  '/Print-app/icon-512.png'
];

// Install – cache app shell
self.addEventListener('install', function(e) {
  e.waitUntil(
    caches.open(CACHE_NAME).then(function(cache) { return cache.addAll(ASSETS); })
  );
  self.skipWaiting();
});

// Activate – delete ALL old caches
self.addEventListener('activate', function(e) {
  e.waitUntil(
    caches.keys().then(function(keys) {
      return Promise.all(
        keys.filter(function(k) { return k !== CACHE_NAME; })
            .map(function(k) { return caches.delete(k); })
      );
    })
  );
  self.clients.claim();
});

// Fetch – network first, fall back to cache
// This ensures deploys are picked up immediately
self.addEventListener('fetch', function(e) {
  e.respondWith(
    fetch(e.request)
      .then(function(response) {
        // Update cache with fresh copy
        var clone = response.clone();
        caches.open(CACHE_NAME).then(function(cache) {
          cache.put(e.request, clone);
        });
        return response;
      })
      .catch(function() {
        return caches.match(e.request);
      })
  );
});
