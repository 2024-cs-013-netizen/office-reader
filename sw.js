const CACHE_NAME = 'reader-live-v3.1';

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

// Network-first strategy with cache fallbacks
// Excludes APK files and version checks from clogging Cache Storage
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;

  const url = new URL(event.request.url);

  // Never store heavy APK binary or live update checks in Cache Storage
  if (url.pathname.endsWith('.apk') || url.pathname.endsWith('version.json')) {
    return;
  }

  event.respondWith(
    fetch(event.request)
      .then((networkResponse) => {
        if (networkResponse && networkResponse.status === 200) {
          const clone = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, clone);
          });
        }
        return networkResponse;
      })
      .catch(() => caches.match(event.request))
  );
});
