// ── Register Service Worker ──
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

document.addEventListener('DOMContentLoaded', () => {
  var copiesValue = document.getElementById('copiesValue');
  var copiesMinus = document.getElementById('copiesMinus');
  var copiesPlus = document.getElementById('copiesPlus');
  var printBtn = document.getElementById('printBtn');
  var layoutSelect = document.getElementById('layoutSelect');
  var labelContainer = document.getElementById('labelContainer');
  var whiteoutCanvas = document.getElementById('whiteoutCanvas');
  var undoBtn = document.getElementById('undoBtn');
  var printArea = document.getElementById('printArea');
  var printerDot = document.getElementById('printerDot');
  var printerText = document.getElementById('printerText');

  var copies = 1;

  // ── Printer status: green blink = connected, red blink = disconnected ──
  function updatePrinterStatus(connected) {
    if (connected) {
      printerDot.classList.add('connected');
      printerText.textContent = 'Brother HL-B2080DW';
    } else {
      printerDot.classList.remove('connected');
      printerText.textContent = 'Printer not found';
    }
  }

  function checkPrinter() {
    if (window.AndroidBridge && typeof window.AndroidBridge.isPrinterConnected === 'function') {
      // Android app can actually detect the printer on the network
      updatePrinterStatus(window.AndroidBridge.isPrinterConnected());
    } else {
      // Web browser cannot detect a printer — default to disconnected (red)
      updatePrinterStatus(false);
    }
  }

  // Android bridge can call this to push status updates
  window.updatePrinterConnected = function(connected) {
    updatePrinterStatus(connected);
  };

  // Poll every 5s (only useful when Android bridge is present)
  setInterval(checkPrinter, 5000);
  checkPrinter();

  // ── Copies ──
  function updateCopiesUI() {
    copiesValue.textContent = copies;
    copiesMinus.disabled = copies <= 1;
    copiesPlus.disabled = copies >= 99;
  }

  copiesMinus.addEventListener('click', function() {
    if (copies > 1) { copies--; updateCopiesUI(); }
  });
  copiesPlus.addEventListener('click', function() {
    if (copies < 99) { copies++; updateCopiesUI(); }
  });

  // ══════════════════════════════════════════
  // ── WHITE-OUT CANVAS (always active) ──
  // One finger = draw white. Two fingers = browser pinch zoom.
  // Canvas has touch-action: pinch-zoom so browser handles pinch natively.
  // ══════════════════════════════════════════
  var strokes = [];
  var currentStroke = null;
  var BRUSH = 14;
  var ctx = whiteoutCanvas.getContext('2d');

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
    for (var i = 0; i < strokes.length; i++) {
      paintStroke(strokes[i]);
    }
  }

  function paintStroke(pts) {
    if (!pts.length) return;
    ctx.fillStyle = '#FFFFFF';
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = BRUSH * 2;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.arc(pts[0].x, pts[0].y, BRUSH, 0, Math.PI * 2);
    ctx.fill();
    if (pts.length > 1) {
      ctx.beginPath();
      ctx.moveTo(pts[0].x, pts[0].y);
      for (var i = 1; i < pts.length; i++) {
        ctx.lineTo(pts[i].x, pts[i].y);
      }
      ctx.stroke();
    }
  }

  function pos(e) {
    var r = whiteoutCanvas.getBoundingClientRect();
    var cx = e.touches ? e.touches[0].clientX : e.clientX;
    var cy = e.touches ? e.touches[0].clientY : e.clientY;
    return {
      x: ((cx - r.left) / r.width) * whiteoutCanvas.width,
      y: ((cy - r.top) / r.height) * whiteoutCanvas.height
    };
  }

  function drawStart(e) {
    // If 2+ fingers, let browser handle pinch zoom
    if (e.touches && e.touches.length > 1) { currentStroke = null; return; }
    e.preventDefault();
    var p = pos(e);
    currentStroke = [p];
    ctx.fillStyle = '#FFFFFF';
    ctx.beginPath();
    ctx.arc(p.x, p.y, BRUSH, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawMove(e) {
    // Cancel draw if second finger added (pinch starting)
    if (e.touches && e.touches.length > 1) { currentStroke = null; return; }
    if (!currentStroke) return;
    e.preventDefault();
    var p = pos(e);
    currentStroke.push(p);
    var prev = currentStroke[currentStroke.length - 2];
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = BRUSH * 2;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(prev.x, prev.y);
    ctx.lineTo(p.x, p.y);
    ctx.stroke();
  }

  function drawEnd() {
    if (currentStroke && currentStroke.length > 0) {
      strokes.push(currentStroke);
      updateUndoBtn();
    }
    currentStroke = null;
  }

  // Touch
  whiteoutCanvas.addEventListener('touchstart', drawStart, { passive: false });
  whiteoutCanvas.addEventListener('touchmove', drawMove, { passive: false });
  whiteoutCanvas.addEventListener('touchend', drawEnd);
  whiteoutCanvas.addEventListener('touchcancel', drawEnd);
  // Mouse (desktop)
  whiteoutCanvas.addEventListener('mousedown', drawStart);
  whiteoutCanvas.addEventListener('mousemove', drawMove);
  whiteoutCanvas.addEventListener('mouseup', drawEnd);
  whiteoutCanvas.addEventListener('mouseleave', drawEnd);

  // Floating undo button
  function updateUndoBtn() {
    undoBtn.style.display = strokes.length > 0 ? 'flex' : 'none';
  }

  undoBtn.addEventListener('click', function() {
    strokes.pop();
    redrawStrokes();
    updateUndoBtn();
  });

  // Auto-resize canvas
  new ResizeObserver(function() { resizeCanvas(); }).observe(labelContainer);

  // ══════════════════════════════════════════
  // ── PRINT ──
  // ══════════════════════════════════════════
  function buildPrintArea() {
    var layout = parseInt(layoutSelect.value, 10);
    var html = labelContainer.innerHTML;
    var img = whiteoutCanvas.toDataURL('image/png');
    var hasMarks = strokes.length > 0;

    printArea.innerHTML = '';
    printArea.className = 'layout-' + layout;

    for (var i = 0; i < layout; i++) {
      var tile = document.createElement('div');
      tile.className = 'print-tile';
      var inner = '<div class="print-tile-content"><div class="label-container">' + html + '</div></div>';
      if (hasMarks) {
        inner += '<img class="print-tile-overlay" src="' + img + '">';
      }
      tile.innerHTML = inner;
      printArea.appendChild(tile);
    }
  }

  printBtn.addEventListener('click', function() {
    buildPrintArea();
    if (window.AndroidBridge && window.AndroidBridge.isAndroid()) {
      window.AndroidBridge.print(copies);
    } else {
      window.print();
    }
  });

  // ══════════════════════════════════════════
  // ── FILE HANDLING ──
  // ══════════════════════════════════════════
  function clearWhiteout() {
    strokes = [];
    currentStroke = null;
    ctx.clearRect(0, 0, whiteoutCanvas.width, whiteoutCanvas.height);
    updateUndoBtn();
  }

  function displayFile(file) {
    clearWhiteout();
    if (file.type.startsWith('image/')) {
      var url = URL.createObjectURL(file);
      labelContainer.innerHTML =
        '<div style="padding:12px;text-align:center">' +
        '<img src="' + url + '" style="max-width:100%;max-height:70vh;border-radius:4px;object-fit:contain">' +
        '</div>';
    } else if (file.type === 'application/pdf') {
      var url2 = URL.createObjectURL(file);
      labelContainer.innerHTML =
        '<div style="padding:0;display:flex;justify-content:center">' +
        '<embed src="' + url2 + '" type="application/pdf" style="width:100%;height:60vh;border:none;border-radius:4px">' +
        '</div>';
    } else {
      labelContainer.innerHTML =
        '<div style="padding:32px 16px;text-align:center;color:#555">' +
        '<div style="font-size:15px;font-weight:600">' + file.name + '</div>' +
        '<div style="font-size:13px;margin-top:4px;color:#888">' + (file.size / 1024).toFixed(1) + ' KB</div>' +
        '</div>';
    }
  }

  function displayFileFromBase64(name, mimeType, base64Data) {
    clearWhiteout();
    var dataUrl = 'data:' + mimeType + ';base64,' + base64Data;
    if (mimeType.startsWith('image/')) {
      labelContainer.innerHTML =
        '<div style="padding:12px;text-align:center">' +
        '<img src="' + dataUrl + '" style="max-width:100%;max-height:70vh;border-radius:4px;object-fit:contain">' +
        '</div>';
    } else if (mimeType === 'application/pdf') {
      labelContainer.innerHTML =
        '<div style="padding:0;display:flex;justify-content:center">' +
        '<embed src="' + dataUrl + '" type="application/pdf" style="width:100%;height:60vh;border:none;border-radius:4px">' +
        '</div>';
    } else {
      labelContainer.innerHTML =
        '<div style="padding:32px 16px;text-align:center;color:#555">' +
        '<div style="font-size:15px;font-weight:600">' + name + '</div>' +
        '</div>';
    }
  }

  window.handleNativeFile = displayFileFromBase64;

  if (window._pendingFile) {
    displayFileFromBase64(window._pendingFile.name, window._pendingFile.type, window._pendingFile.data);
    window._pendingFile = null;
  }

  if ('launchQueue' in window) {
    window.launchQueue.setConsumer(async function(launchParams) {
      if (launchParams.files && launchParams.files.length > 0) {
        var handle = launchParams.files[0];
        var file = await handle.getFile();
        displayFile(file);
      }
    });
  }

  updateCopiesUI();
});
