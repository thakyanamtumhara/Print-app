// ── Register Service Worker ──
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

document.addEventListener('DOMContentLoaded', () => {
  // ── DOM Elements ──
  const copiesValue = document.getElementById('copiesValue');
  const copiesMinus = document.getElementById('copiesMinus');
  const copiesPlus = document.getElementById('copiesPlus');
  const printBtn = document.getElementById('printBtn');
  const layoutSelect = document.getElementById('layoutSelect');
  const previewArea = document.getElementById('previewArea');
  const previewWrapper = document.getElementById('previewWrapper');
  const labelContainer = document.getElementById('labelContainer');
  const editBtn = document.getElementById('editBtn');
  const editBanner = document.getElementById('editBanner');
  const undoBtn = document.getElementById('undoBtn');
  const doneEditBtn = document.getElementById('doneEditBtn');
  const whiteoutCanvas = document.getElementById('whiteoutCanvas');
  const zoomInBtn = document.getElementById('zoomIn');
  const zoomOutBtn = document.getElementById('zoomOut');
  const zoomLevelDisplay = document.getElementById('zoomLevel');
  const printArea = document.getElementById('printArea');

  let copies = 1;
  const MIN_COPIES = 1;
  const MAX_COPIES = 99;
  let loadedFileName = null;

  // ── Copies ──
  function updateCopiesUI() {
    copiesValue.textContent = copies;
    copiesMinus.disabled = copies <= MIN_COPIES;
    copiesPlus.disabled = copies >= MAX_COPIES;
  }

  copiesMinus.addEventListener('click', () => {
    if (copies > MIN_COPIES) { copies--; updateCopiesUI(); }
  });

  copiesPlus.addEventListener('click', () => {
    if (copies < MAX_COPIES) { copies++; updateCopiesUI(); }
  });

  // ══════════════════════════════════════════
  // ── ZOOM ──
  // ══════════════════════════════════════════
  let zoom = 1;
  const ZOOM_MIN = 0.5;
  const ZOOM_MAX = 3;
  const ZOOM_STEP = 0.25;

  function updateZoom() {
    previewWrapper.style.transform = 'scale(' + zoom + ')';
    // Add margin so scrollable area matches zoomed size
    var extraW = previewWrapper.scrollWidth * (zoom - 1);
    var extraH = previewWrapper.scrollHeight * (zoom - 1);
    previewWrapper.style.marginRight = extraW + 'px';
    previewWrapper.style.marginBottom = extraH + 'px';
    zoomLevelDisplay.textContent = Math.round(zoom * 100) + '%';
  }

  zoomInBtn.addEventListener('click', () => {
    zoom = Math.min(ZOOM_MAX, +(zoom + ZOOM_STEP).toFixed(2));
    updateZoom();
  });

  zoomOutBtn.addEventListener('click', () => {
    zoom = Math.max(ZOOM_MIN, +(zoom - ZOOM_STEP).toFixed(2));
    updateZoom();
  });

  // ══════════════════════════════════════════
  // ── WHITE-OUT (ERASER) TOOL ──
  // Canvas overlay — draw white strokes to cover text
  // ══════════════════════════════════════════
  let editMode = false;
  let strokes = []; // Array of stroke arrays for undo
  let currentStroke = null;
  const BRUSH_RADIUS = 14;
  const ctx = whiteoutCanvas.getContext('2d');

  function resizeCanvas() {
    var w = labelContainer.offsetWidth;
    var h = labelContainer.offsetHeight;
    if (w === 0 || h === 0) return;
    whiteoutCanvas.width = w;
    whiteoutCanvas.height = h;
    redrawStrokes();
  }

  function redrawStrokes() {
    ctx.clearRect(0, 0, whiteoutCanvas.width, whiteoutCanvas.height);
    for (var s = 0; s < strokes.length; s++) {
      drawStroke(strokes[s]);
    }
  }

  function drawStroke(points) {
    if (points.length === 0) return;
    ctx.fillStyle = '#FFFFFF';
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = BRUSH_RADIUS * 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    // Draw circles at each point + lines between them
    ctx.beginPath();
    ctx.arc(points[0].x, points[0].y, BRUSH_RADIUS, 0, Math.PI * 2);
    ctx.fill();

    if (points.length > 1) {
      ctx.beginPath();
      ctx.moveTo(points[0].x, points[0].y);
      for (var i = 1; i < points.length; i++) {
        ctx.lineTo(points[i].x, points[i].y);
      }
      ctx.stroke();
      // End cap
      ctx.beginPath();
      ctx.arc(points[points.length - 1].x, points[points.length - 1].y, BRUSH_RADIUS, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  function getCanvasPos(e) {
    var rect = whiteoutCanvas.getBoundingClientRect();
    var clientX = e.touches ? e.touches[0].clientX : e.clientX;
    var clientY = e.touches ? e.touches[0].clientY : e.clientY;
    return {
      x: ((clientX - rect.left) / rect.width) * whiteoutCanvas.width,
      y: ((clientY - rect.top) / rect.height) * whiteoutCanvas.height
    };
  }

  function onDrawStart(e) {
    if (!editMode) return;
    e.preventDefault();
    var pos = getCanvasPos(e);
    currentStroke = [pos];
    drawStroke(currentStroke);
  }

  function onDrawMove(e) {
    if (!editMode || !currentStroke) return;
    e.preventDefault();
    var pos = getCanvasPos(e);
    currentStroke.push(pos);
    // Draw incremental line segment
    var prev = currentStroke[currentStroke.length - 2];
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = BRUSH_RADIUS * 2;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(prev.x, prev.y);
    ctx.lineTo(pos.x, pos.y);
    ctx.stroke();
    ctx.fillStyle = '#FFFFFF';
    ctx.beginPath();
    ctx.arc(pos.x, pos.y, BRUSH_RADIUS, 0, Math.PI * 2);
    ctx.fill();
  }

  function onDrawEnd() {
    if (currentStroke && currentStroke.length > 0) {
      strokes.push(currentStroke);
    }
    currentStroke = null;
  }

  // Touch events
  whiteoutCanvas.addEventListener('touchstart', onDrawStart, { passive: false });
  whiteoutCanvas.addEventListener('touchmove', onDrawMove, { passive: false });
  whiteoutCanvas.addEventListener('touchend', onDrawEnd);
  whiteoutCanvas.addEventListener('touchcancel', onDrawEnd);
  // Mouse events (for desktop/browser testing)
  whiteoutCanvas.addEventListener('mousedown', onDrawStart);
  whiteoutCanvas.addEventListener('mousemove', onDrawMove);
  whiteoutCanvas.addEventListener('mouseup', onDrawEnd);
  whiteoutCanvas.addEventListener('mouseleave', onDrawEnd);

  // Edit mode toggle
  function setEditMode(on) {
    editMode = on;
    if (on) {
      previewArea.classList.add('edit-mode');
      editBanner.classList.add('active');
      editBtn.classList.add('edit-active');
    } else {
      previewArea.classList.remove('edit-mode');
      editBanner.classList.remove('active');
      editBtn.classList.remove('edit-active');
    }
  }

  editBtn.addEventListener('click', () => setEditMode(!editMode));
  doneEditBtn.addEventListener('click', () => setEditMode(false));

  undoBtn.addEventListener('click', () => {
    if (strokes.length > 0) {
      strokes.pop();
      redrawStrokes();
    }
  });

  // Resize canvas when label size changes
  var resizeObserver = new ResizeObserver(() => resizeCanvas());
  resizeObserver.observe(labelContainer);

  // ══════════════════════════════════════════
  // ── PRINT: build tiled A4 layout ──
  // ══════════════════════════════════════════
  function buildPrintArea() {
    var layout = parseInt(layoutSelect.value, 10);
    var contentHtml = labelContainer.innerHTML;
    // Capture whiteout as image
    var canvasDataUrl = whiteoutCanvas.toDataURL('image/png');
    var hasWhiteout = strokes.length > 0;

    printArea.innerHTML = '';
    printArea.className = 'layout-' + layout;

    for (var i = 0; i < layout; i++) {
      var tile = document.createElement('div');
      tile.className = 'print-tile';

      var inner = '<div class="print-tile-content"><div class="label-container">' +
        contentHtml + '</div></div>';

      if (hasWhiteout) {
        inner += '<img class="print-tile-overlay" src="' + canvasDataUrl + '">';
      }

      tile.innerHTML = inner;
      printArea.appendChild(tile);
    }
  }

  // ── Print button ──
  printBtn.addEventListener('click', () => {
    // Exit edit mode if active
    if (editMode) setEditMode(false);

    // Build the tiled print area
    buildPrintArea();

    if (window.AndroidBridge && window.AndroidBridge.isAndroid()) {
      // Android app → native print dialog (A4, monochrome, auto-selects Brother)
      window.AndroidBridge.print(copies);
    } else {
      // Browser → browser print dialog
      window.print();
    }
  });

  // ══════════════════════════════════════════
  // ── FILE HANDLING ──
  // ══════════════════════════════════════════
  function clearWhiteout() {
    strokes = [];
    currentStroke = null;
    if (ctx) ctx.clearRect(0, 0, whiteoutCanvas.width, whiteoutCanvas.height);
  }

  function displayFile(file) {
    loadedFileName = file.name;
    clearWhiteout();

    if (file.type.startsWith('image/')) {
      var url = URL.createObjectURL(file);
      labelContainer.innerHTML =
        '<div style="padding:12px;text-align:center;">' +
        '<img src="' + url + '" alt="' + file.name + '"' +
        ' style="max-width:100%;max-height:70vh;border-radius:4px;object-fit:contain;">' +
        '</div>';
    } else if (file.type === 'application/pdf') {
      var url2 = URL.createObjectURL(file);
      labelContainer.innerHTML =
        '<div style="padding:0;display:flex;justify-content:center;">' +
        '<embed src="' + url2 + '" type="application/pdf"' +
        ' style="width:100%;height:60vh;border:none;border-radius:4px;">' +
        '</div>';
    } else {
      labelContainer.innerHTML =
        '<div style="padding:32px 16px;text-align:center;color:#555;">' +
        '<svg width="48" height="48" viewBox="0 0 24 24" fill="none" style="margin-bottom:12px">' +
        '<path d="M14 2H6C4.9 2 4 2.9 4 4V20C4 21.1 4.9 22 6 22H18C19.1 22 20 21.1 20 20V8L14 2Z"' +
        ' stroke="#007aff" stroke-width="1.5" fill="none"/>' +
        '<path d="M14 2V8H20" stroke="#007aff" stroke-width="1.5"/>' +
        '</svg>' +
        '<div style="font-size:15px;font-weight:600;">' + file.name + '</div>' +
        '<div style="font-size:13px;margin-top:4px;color:#888;">' + (file.size / 1024).toFixed(1) + ' KB</div>' +
        '</div>';
    }
  }

  function displayFileFromBase64(name, mimeType, base64Data) {
    loadedFileName = name;
    clearWhiteout();
    var dataUrl = 'data:' + mimeType + ';base64,' + base64Data;

    if (mimeType.startsWith('image/')) {
      labelContainer.innerHTML =
        '<div style="padding:12px;text-align:center;">' +
        '<img src="' + dataUrl + '" alt="' + name + '"' +
        ' style="max-width:100%;max-height:70vh;border-radius:4px;object-fit:contain;">' +
        '</div>';
    } else if (mimeType === 'application/pdf') {
      labelContainer.innerHTML =
        '<div style="padding:0;display:flex;justify-content:center;">' +
        '<embed src="' + dataUrl + '" type="application/pdf"' +
        ' style="width:100%;height:60vh;border:none;border-radius:4px;">' +
        '</div>';
    } else {
      labelContainer.innerHTML =
        '<div style="padding:32px 16px;text-align:center;color:#555;">' +
        '<div style="font-size:15px;font-weight:600;">' + name + '</div>' +
        '</div>';
    }
  }

  // Expose to Android native bridge
  window.handleNativeFile = displayFileFromBase64;

  // Check if Android already sent a file before JS loaded
  if (window._pendingFile) {
    displayFileFromBase64(window._pendingFile.name, window._pendingFile.type, window._pendingFile.data);
    window._pendingFile = null;
  }

  // File Handling API (desktop Chrome)
  if ('launchQueue' in window) {
    window.launchQueue.setConsumer(async (launchParams) => {
      if (launchParams.files && launchParams.files.length > 0) {
        var handle = launchParams.files[0];
        var file = await handle.getFile();
        displayFile(file);
      }
    });
  }

  // Initialize
  updateCopiesUI();
  updateZoom();
});
