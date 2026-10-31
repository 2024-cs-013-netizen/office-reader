// =========================================================
// INLINE DOCUMENT EDITOR ENGINE (editor.js)
// =========================================================

const EditorEngine = (function () {
  let currentFile = null;
  let currentExt = '';
  let isEditing = false;

  let btnEdit = null;
  let btnSave = null;
  let viewer = null;

  // Formats that can be safely edited and saved back without corruption
  const SUPPORTED_FORMATS = [
    'txt', 'csv', 'tsv', 'md', 'markdown',
    'cpp', 'c', 'h', 'py', 'java', 'html',
    'css', 'js', 'json', 'xml', 'log'
  ];

  function init(editBtnId, saveBtnId, viewerId) {
    btnEdit = document.getElementById(editBtnId);
    btnSave = document.getElementById(saveBtnId);
    viewer = document.getElementById(viewerId);

    if (btnEdit && btnSave) {
      btnEdit.addEventListener('click', toggleEditMode);
      btnSave.addEventListener('click', saveFile);
    }
  }

  // Called whenever a new file is rendered
  function checkSupport(file, ext) {
    currentFile = file;
    currentExt = (ext || '').toLowerCase();
    isEditing = false;

    if (!btnEdit || !btnSave) return;

    btnSave.style.display = 'none';

    if (SUPPORTED_FORMATS.includes(currentExt)) {
      btnEdit.style.display = 'inline-flex';
      btnEdit.textContent = '✏️ Edit';
    } else {
      // PDF, DOCX, and PPTX remain read-only
      btnEdit.style.display = 'none';
    }
  }

  function toggleEditMode() {
    isEditing = !isEditing;

    if (isEditing) {
      btnEdit.textContent = '❌ Cancel';
      btnSave.style.display = 'inline-flex';
      enableEditable(true);
    } else {
      btnEdit.textContent = '✏️ Edit';
      btnSave.style.display = 'none';
      enableEditable(false);
    }
  }

  function enableEditable(enable) {
    if (!viewer) return;

    if (currentExt === 'csv' || currentExt === 'tsv') {
      const cells = viewer.querySelectorAll('th, td');
      cells.forEach((cell) => {
        cell.contentEditable = enable ? 'true' : 'false';
        cell.style.outline = enable ? '1px dashed #4f46e5' : 'none';
      });

      if (enable && cells.length > 0) {
        cells[0].focus();
      }
    } else {
      const codeBlock = viewer.querySelector('.code-body');
      const docPaper = viewer.querySelector('.doc-paper');
      const target = codeBlock || docPaper;

      if (target) {
        target.contentEditable = enable ? 'true' : 'false';
        target.style.outline = enable ? '1px solid #4f46e5' : 'none';
        if (enable) target.focus();
      }
    }
  }

  async function saveFile() {
    if (!currentFile) return;

    let updatedText = '';

    // Reconstruct CSV/TSV table rows into clean text
    if (currentExt === 'csv' || currentExt === 'tsv') {
      const rows = viewer.querySelectorAll('tr');
      const delimiter = currentExt === 'tsv' ? '\t' : ',';
      const lines = [];

      rows.forEach((row) => {
        const cells = Array.from(row.querySelectorAll('th, td')).map((cell) => {
          let text = cell.textContent.trim();
          // Escape quotes and wrap commas in quotes
          if (text.includes(delimiter) || text.includes('"') || text.includes('\n')) {
            text = `"${text.replace(/"/g, '""')}"`;
          }
          return text;
        });
        lines.push(cells.join(delimiter));
      });

      updatedText = lines.join('\n');
    } else {
      const codeBlock = viewer.querySelector('.code-body');
      const docPaper = viewer.querySelector('.doc-paper');
      updatedText = codeBlock ? codeBlock.textContent : (docPaper ? docPaper.innerText : '');
    }

    // Turn off edit mode visuals
    enableEditable(false);
    btnEdit.textContent = '✏️ Edit';
    btnSave.style.display = 'none';
    isEditing = false;

    // PATH 1: Android Native Overwrite (Direct in-place save)
    if (window.AndroidStorage && typeof window.AndroidStorage.overwriteOriginalFile === 'function') {
      try {
        const base64Data = btoa(unescape(encodeURIComponent(updatedText)));
        window.AndroidStorage.overwriteOriginalFile(base64Data);
        return;
      } catch (err) {
        console.error('Android bridge write failed, falling back to download', err);
      }
    }

    // PATH 2: Desktop / Mobile Browser Fallback
    const blob = new Blob([updatedText], { type: currentFile.type || 'text/plain' });
    const downloadLink = document.createElement('a');
    downloadLink.href = URL.createObjectURL(blob);
    downloadLink.download = currentFile.name;
    downloadLink.click();
    URL.revokeObjectURL(downloadLink.href);
  }

  return {
    init,
    checkSupport
  };
})();
