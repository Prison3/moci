(function () {
  const toasts = document.querySelectorAll(".toast");
  toasts.forEach((el) => {
    setTimeout(() => {
      el.style.opacity = "0";
      el.style.transition = "opacity .4s";
      setTimeout(() => el.remove(), 400);
    }, 3200);
  });

  const wrap = document.querySelector(".review-wrap");
  if (!wrap) return;

  const cards = Array.from(document.querySelectorAll(".flashcard"));
  const rateBar = document.getElementById("rate-bar");
  const spellPanel = document.getElementById("spell-panel");
  const spellInput = document.getElementById("spell-input");
  const spellError = document.getElementById("spell-error");
  const spellCancel = document.getElementById("spell-cancel");
  const donePanel = document.getElementById("done-panel");
  const deck = document.getElementById("deck");
  const doneCount = document.getElementById("done-count");
  const bar = document.getElementById("progress-bar");
  const csrf = wrap.getAttribute("data-csrf") || "";
  const total = window.REVIEW_TOTAL || cards.length;
  let index = 0;
  let finished = 0;

  function current() {
    return cards[index];
  }

  function hideSpell() {
    const card = current();
    if (card) card.classList.remove("is-spelling");
    if (spellPanel) spellPanel.hidden = true;
    if (spellError) spellError.hidden = true;
    if (spellInput) {
      spellInput.value = "";
      spellInput.classList.remove("is-wrong");
    }
  }

  function showSpell() {
    const card = current();
    if (!card || !spellPanel || !spellInput) return;
    card.classList.add("is-spelling");
    if (rateBar) rateBar.hidden = true;
    spellPanel.hidden = false;
    if (spellError) spellError.hidden = true;
    spellInput.classList.remove("is-wrong");
    spellInput.value = "";
    spellInput.focus();
  }

  function show(i) {
    cards.forEach((c) => c.classList.remove("is-current", "is-open", "is-spelling"));
    hideSpell();
    if (!cards[i]) {
      if (deck) deck.hidden = true;
      if (rateBar) rateBar.hidden = true;
      if (spellPanel) spellPanel.hidden = true;
      if (donePanel) donePanel.hidden = false;
      const label = document.querySelector(".progress-label");
      if (label) label.hidden = true;
      return;
    }
    cards[i].classList.add("is-current");
    if (rateBar) rateBar.hidden = true;
  }

  function updateProgress() {
    if (doneCount) doneCount.textContent = String(finished);
    if (bar && total) bar.style.width = `${Math.round((finished / total) * 100)}%`;
  }

  async function submitReview(rating, spelling) {
    const card = current();
    if (!card) return false;
    const id = card.getAttribute("data-id");
    const body = { rating };
    if (spelling !== undefined) body.spelling = spelling;
    const res = await fetch(`/api/review/${id}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-Token": csrf,
      },
      body: JSON.stringify(body),
    });
    if (res.status === 400) {
      const data = await res.json().catch(() => ({}));
      if (data.error === "spelling") return "spelling";
    }
    if (!res.ok) throw new Error("review failed");
    finished += 1;
    updateProgress();
    index += 1;
    show(index);
    return "ok";
  }

  cards.forEach((card) => {
    const face = card.querySelector(".card-face");
    if (!face) return;
    face.addEventListener("click", () => {
      if (!card.classList.contains("is-current")) return;
      if (card.classList.contains("is-spelling")) return;
      card.classList.add("is-open");
      if (rateBar) rateBar.hidden = false;
    });
  });

  if (rateBar) {
    rateBar.addEventListener("click", async (event) => {
      const btn = event.target.closest("[data-rating]");
      if (!btn) return;
      const rating = btn.getAttribute("data-rating");
      if (rating === "easy") {
        showSpell();
        return;
      }
      btn.disabled = true;
      try {
        await submitReview(rating);
      } catch (_err) {
        alert("提交失败，请检查网络后重试。");
      } finally {
        btn.disabled = false;
      }
    });
  }

  if (spellPanel) {
    spellPanel.addEventListener("submit", async (event) => {
      event.preventDefault();
      const spelling = (spellInput && spellInput.value) || "";
      const submitBtn = spellPanel.querySelector('button[type="submit"]');
      if (submitBtn) submitBtn.disabled = true;
      try {
        const result = await submitReview("easy", spelling);
        if (result === "spelling") {
          if (spellError) spellError.hidden = false;
          if (spellInput) {
            spellInput.classList.add("is-wrong");
            spellInput.focus();
            spellInput.select();
          }
        }
      } catch (_err) {
        alert("提交失败，请检查网络后重试。");
      } finally {
        if (submitBtn) submitBtn.disabled = false;
      }
    });
  }

  if (spellCancel) {
    spellCancel.addEventListener("click", () => {
      hideSpell();
      const card = current();
      if (card && card.classList.contains("is-open") && rateBar) {
        rateBar.hidden = false;
      }
    });
  }

  updateProgress();
})();
