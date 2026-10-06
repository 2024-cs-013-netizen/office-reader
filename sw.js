self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

// Helper: Save the incoming file directly into IndexedDB
function saveSharedFile(file) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('ReaderDB', 1);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains('shared')) {
        db.createObjectStore('shared');
      }
    };
    req.onsuccess = (e) => {
      const db = e.target.result;
      const tx = db.transaction('shared', 'readwrite');
      tx.objectStore('shared').put(file, 'incoming_file');
      tx.oncomplete = () => {
        db.close();
        resolve();
      };
      tx.onerror = () => reject(tx.error);
    };
    req.onerror = () => reject(req.error);
  });
}

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Catch the file incoming from WhatsApp
  if (url.pathname === '/share-target' && event.request.method === 'POST') {
    event.respondWith((async () => {
      try {
        const formData = await event.request.formData();
        
        // Grab the file from the form data
        let sharedFile = null;
        for (const [key, value] of formData.entries()) {
          if (value && typeof value === 'object' && value.name) {
            sharedFile = value;
            break;
          }
        }

        if (sharedFile) {
          await saveSharedFile(sharedFile);
        }

        // Must use an absolute URL to avoid the blank screen crash
        const targetUrl = new URL('/?shared=1', self.location.origin).href;
        return Response.redirect(targetUrl, 303);
      } catch (err) {
        const errorUrl = new URL('/?error=' + encodeURIComponent(err.message), self.location.origin).href;
        return Response.redirect(errorUrl, 303);
      }
    })());
  }
});
