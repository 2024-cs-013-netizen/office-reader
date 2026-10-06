const CACHE_NAME = 'reader-cache-v1';

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Intercept the file coming from the Android Share Menu
  if (url.pathname === '/share-target' && event.request.method === 'POST') {
    event.respondWith((async () => {
      const formData = await event.request.formData();
      const file = formData.get('shared_file');

      // Save the shared file temporarily in the browser Cache
      const cache = await caches.open('shared-files');
      await cache.put('incoming-file', new Response(file));

      // Redirect the user to the reader screen
      return Response.redirect('/?shared=true', 303);
    })());
  }
});
