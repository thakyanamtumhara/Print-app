const CACHE_NAME = 'iprint-v1';
const ASSETS = [
  '/Print-app/',
  '/Print-app/index.html',
  '/Print-app/styles.css',
  '/Print-app/app.js',
  '/Print-app/icon-192.png',
  '/Print-app/icon-512.png'
];

// Install – cache app shell
self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS))
  );
  self.skipWaiting();
});

// Activate – clean old caches
self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

// Fetch – serve from cache, fallback to network
self.addEventListener('fetch', (e) => {
  e.respondWith(
    caches.match(e.request).then((cached) => cached || fetch(e.request))
  );
});
