const CACHE_NAME = 'reader-live-v3.0';

// Install immediately without waiting for user to close the app
self.addEventListener('install', (event) => {
  self.skipWaiting();
});

// Delete all old stuck caches from previous versions immediately
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            return caches.delete(key);
          }
        })
      )
    ).then(() => self.clients.claim())
  );
});

// NETWORK-FIRST STRATEGY: Always fetch the newest update from Vercel when online.
// Only fall back to offline cache if the student has no internet.
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;

  event.respondWith(
    fetch(event.request)
      .then((networkResponse) => {
        const clone = networkResponse.clone();
        caches.open(CACHE_NAME).then((cache) => {
          cache.put(event.request, clone);
        });
        return networkResponse;
      })
      .catch(() => caches.match(event.request))
  );
});
