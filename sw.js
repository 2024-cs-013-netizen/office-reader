self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

// Helper: Save raw file bytes into IndexedDB
function saveSharedData(data) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('ReaderDB', 2);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains('shared')) {
        db.createObjectStore('shared');
      }
    };
    req.onsuccess = (e) => {
      const db = e.target.result;
      const tx = db.transaction('shared', 'readwrite');
      tx.objectStore('shared').put(data, 'incoming_file');
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

  if (url.pathname === '/share-target' && event.request.method === 'POST') {
    event.respondWith((async () => {
      try {
        const formData = await event.request.formData();
        let targetFile = null;
        let debugLog = [];

        // Check every piece of data Android sent over
        for (const [key, value] of formData.entries()) {
          const isFileLike = value && (value instanceof Blob || (typeof value === 'object' && 'size' in value));
          debugLog.push(`${key} (${isFileLike ? value.size + ' bytes' : value})`);
          
          if (!targetFile && isFileLike && value.size > 0) {
            targetFile = value;
          }
        }

        // Fallback: check the direct field name
        if (!targetFile) {
          const direct = formData.get('shared_file');
          if (direct && (direct instanceof Blob || typeof direct === 'object')) {
            targetFile = direct;
          }
        }

        if (targetFile && targetFile.size > 0) {
          // Read into an ArrayBuffer so no data stream gets cut off
          const buffer = await targetFile.arrayBuffer();
          const safeName = (targetFile.name && targetFile.name.trim() !== '') 
            ? targetFile.name 
            : 'document.docx';

          await saveSharedData({
            name: safeName,
            type: targetFile.type || '',
            buffer: buffer
          });

          const targetUrl = new URL('/?shared=1', self.location.origin).href;
          return Response.redirect(targetUrl, 303);
        } else {
          // If Android dropped the payload, redirect with debug details
          const debugInfo = encodeURIComponent(debugLog.join(' | ') || 'empty_payload');
          const targetUrl = new URL(`/?shared=0&debug=${debugInfo}`, self.location.origin).href;
          return Response.redirect(targetUrl, 303);
        }
      } catch (err) {
        const errorUrl = new URL('/?error=' + encodeURIComponent(err.message), self.location.origin).href;
        return Response.redirect(errorUrl, 303);
      }
    })());
  }
});
