// ── Register Service Worker ──
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

document.addEventListener('DOMContentLoaded', () => {
  const copiesValue = document.getElementById('copiesValue');
  const copiesMinus = document.getElementById('copiesMinus');
  const copiesPlus = document.getElementById('copiesPlus');
  const printBtn = document.getElementById('printBtn');
  const layoutSelect = document.getElementById('layoutSelect');
  const previewArea = document.querySelector('.preview-area');
  const labelContainer = document.querySelector('.label-container');

  let copies = 1;
  const MIN_COPIES = 1;
  const MAX_COPIES = 99;
  let loadedFileName = null;

  function updateCopiesUI() {
    copiesValue.textContent = copies;
    copiesMinus.disabled = copies <= MIN_COPIES;
    copiesPlus.disabled = copies >= MAX_COPIES;
  }

  copiesMinus.addEventListener('click', () => {
    if (copies > MIN_COPIES) {
      copies--;
      updateCopiesUI();
    }
  });

  copiesPlus.addEventListener('click', () => {
    if (copies < MAX_COPIES) {
      copies++;
      updateCopiesUI();
    }
  });

  printBtn.addEventListener('click', () => {
    const layout = layoutSelect.value;
    const fileInfo = loadedFileName ? ` — File: ${loadedFileName}` : '';
    alert(`Printing ${copies} copy(ies) with ${layout} label(s) per sheet.${fileInfo}`);
  });

  // ── Handle files opened via "Open with" / File Handling API ──
  function displayFile(file) {
    loadedFileName = file.name;
    const headerTitle = document.querySelector('.header-title');
    headerTitle.textContent = file.name;

    if (file.type.startsWith('image/')) {
      // Show image labels directly in the preview
      const url = URL.createObjectURL(file);
      labelContainer.innerHTML = `
        <div style="padding:12px;text-align:center;">
          <img src="${url}" alt="${file.name}"
               style="max-width:100%;max-height:50vh;border-radius:4px;object-fit:contain;">
        </div>`;
    } else if (file.type === 'application/pdf') {
      // Show PDF in an embed
      const url = URL.createObjectURL(file);
      labelContainer.innerHTML = `
        <div style="padding:0;display:flex;justify-content:center;">
          <embed src="${url}" type="application/pdf"
                 style="width:100%;height:55vh;border:none;border-radius:4px;">
        </div>`;
    } else {
      // Generic file – show name
      labelContainer.innerHTML = `
        <div style="padding:32px 16px;text-align:center;color:#555;">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" style="margin-bottom:12px">
            <path d="M14 2H6C4.9 2 4 2.9 4 4V20C4 21.1 4.9 22 6 22H18C19.1 22 20 21.1 20 20V8L14 2Z"
                  stroke="#007aff" stroke-width="1.5" fill="none"/>
            <path d="M14 2V8H20" stroke="#007aff" stroke-width="1.5"/>
          </svg>
          <div style="font-size:15px;font-weight:600;">${file.name}</div>
          <div style="font-size:13px;margin-top:4px;color:#888;">${(file.size / 1024).toFixed(1)} KB</div>
        </div>`;
    }
  }

  // File Handling API (Chrome 102+): triggered when app is opened via "Open with"
  if ('launchQueue' in window) {
    window.launchQueue.setConsumer(async (launchParams) => {
      if (launchParams.files && launchParams.files.length > 0) {
        const handle = launchParams.files[0];
        const file = await handle.getFile();
        displayFile(file);
      }
    });
  }

  // Initialize
  updateCopiesUI();
});
