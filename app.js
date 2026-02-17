document.addEventListener('DOMContentLoaded', () => {
  const copiesValue = document.getElementById('copiesValue');
  const copiesMinus = document.getElementById('copiesMinus');
  const copiesPlus = document.getElementById('copiesPlus');
  const printBtn = document.getElementById('printBtn');
  const layoutSelect = document.getElementById('layoutSelect');

  let copies = 1;
  const MIN_COPIES = 1;
  const MAX_COPIES = 99;

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
    alert(`Printing ${copies} copy(ies) with ${layout} label(s) per sheet.`);
  });

  // Initialize
  updateCopiesUI();
});
