const CACHE_NAME = 'reader-core-v4';
const CORE_ASSETS = [
  '/',
  '/index.html',
  '/manifest.json',
  '/icon.svg'
];

const CDN_ASSETS = [
  'https://unpkg.com/jszip/dist/jszip.min.js',
  'https://unpkg.com/docx-preview/dist/docx-preview.min.js',
  'https://cdn.sheetjs.com/xlsx-latest/package/dist/xlsx.full.min.js'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(async (cache) => {
      await cache.addAll(CORE_ASSETS);
      for (const url of CDN_ASSETS) {
        try {
          const res = await fetch(url, { mode: 'cors', credentials: 'omit' });
          if (res.ok) await cache.put(url, res);
        } catch (e) {
          console.warn('CDN offline pre-cache skipped for:', url);
        }
      }
    })
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) return caches.delete(key);
        })
      )
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  if (req.method !== 'GET') return;

  // Network-First for HTML navigation so classmates get updates immediately
  if (req.mode === 'navigate' || url.pathname === '/' || url.pathname.endsWith('.html')) {
    event.respondWith(
      fetch(req)
        .then((networkRes) => {
          if (networkRes && networkRes.ok) {
            const copy = networkRes.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
          }
          return networkRes;
        })
        .catch(() => caches.match(req).then((cached) => cached || caches.match('/index.html') || caches.match('/')))
    );
    return;
  }

  // Cache-First with safe offline fallback for scripts and icons
  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req)
        .then((networkRes) => {
          if (networkRes && networkRes.ok) {
            const copy = networkRes.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
          }
          return networkRes;
        })
        .catch(() => new Response('', { status: 408, statusText: 'Offline' }));
    })
  );
});
