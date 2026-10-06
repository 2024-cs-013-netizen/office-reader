self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

function saveSharedData(data) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('ReaderDB', 3);
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
        let entriesSeen = [];

        for (const [key, val] of formData.entries()) {
          const isFile = val && (val instanceof Blob || typeof val === 'object');
          entriesSeen.push(`${key}: ${isFile ? val.size + 'B' : val}`);

          if (!targetFile && isFile && val.size > 0) {
            targetFile = val;
          }
        }

        if (targetFile) {
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
          const debugText = encodeURIComponent(entriesSeen.join(' ; ') || 'empty_form');
          const targetUrl = new URL(`/?shared=0&debug=${debugText}`, self.location.origin).href;
          return Response.redirect(targetUrl, 303);
        }
      } catch (err) {
        const errorUrl = new URL('/?error=' + encodeURIComponent(err.message), self.location.origin).href;
        return Response.redirect(errorUrl, 303);
      }
    })());
  }
});
