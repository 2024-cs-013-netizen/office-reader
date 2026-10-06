self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

function saveSharedData(data) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('ReaderDB', 4);
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

        // Check if WhatsApp sent the name in the text field
        const textParam = formData.get('text') || '';
        const titleParam = formData.get('title') || '';

        for (const [key, val] of formData.entries()) {
          const isFile = val && (val instanceof Blob || (typeof val === 'object' && 'size' in val));
          entriesSeen.push(`${key}: ${isFile ? val.size + 'B' : val}`);

          if (!targetFile && isFile && val.size > 0) {
            targetFile = val;
          }
        }

        if (targetFile) {
          const buffer = await targetFile.arrayBuffer();
          
          // Use the real name WhatsApp passed in the text field if the file name is blank
          let finalName = 'document.docx';
          if (targetFile.name && targetFile.name.trim() !== '') {
            finalName = targetFile.name;
          } else if (textParam && textParam.includes('.')) {
            finalName = textParam.trim();
          }

          await saveSharedData({
            name: finalName,
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
