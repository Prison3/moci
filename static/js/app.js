(function () {
  const toasts = document.querySelectorAll(".toast");
  toasts.forEach((el) => {
    setTimeout(() => {
      el.style.opacity = "0";
      el.style.transition = "opacity .4s";
      setTimeout(() => el.remove(), 400);
    }, 3200);
  });

  function pickEnglishVoice() {
    if (!window.speechSynthesis) return null;
    const voices = speechSynthesis.getVoices();
    return (
      voices.find((v) => /^en-US/i.test(v.lang)) ||
      voices.find((v) => /^en/i.test(v.lang)) ||
      null
    );
  }

  let speakToken = 0;

  function clearSpeaking() {
    document.querySelectorAll(".speak-btn.is-speaking").forEach((el) => {
      el.classList.remove("is-speaking");
    });
  }

  function speakEnglish(text, btn) {
    const value = (text || "").trim();
    if (!value || !window.speechSynthesis) return;
    speechSynthesis.cancel();
    clearSpeaking();
    const token = ++speakToken;
    const utter = new SpeechSynthesisUtterance(value);
    utter.lang = "en-US";
    utter.rate = 0.9;
    const voice = pickEnglishVoice();
    if (voice) utter.voice = voice;
    if (btn) {
      btn.classList.add("is-speaking");
      const stop = () => {
        if (token === speakToken) btn.classList.remove("is-speaking");
      };
      utter.addEventListener("end", stop);
      utter.addEventListener("error", stop);
    }
    speechSynthesis.speak(utter);
  }

  if (window.speechSynthesis) {
    speechSynthesis.getVoices();
    speechSynthesis.addEventListener("voiceschanged", () => {
      pickEnglishVoice();
    });
  }

  document.addEventListener("click", (event) => {
    const btn = event.target.closest("[data-speak]");
    if (!btn) return;
    event.preventDefault();
    event.stopPropagation();
    speakEnglish(btn.getAttribute("data-speak") || "", btn);
  });

  const cal = document.querySelector(".month-cal");
  if (cal) {
    const detail = document.getElementById("cal-detail");
    const logs = document.getElementById("cal-logs");
    const cache = new Map();

    function escapeHtml(value) {
      return String(value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
    }

    function statusText(status) {
      if (status === "learning") return "现为复习";
      if (status === "mastered") return "现为掌握";
      return "现为新词";
    }

    function statusClass(status) {
      if (status === "learning") return "badge-learning";
      if (status === "mastered") return "badge-mastered";
      return "badge-new";
    }

    function speakBtn(text, label) {
      const safe = escapeHtml(text || "");
      return `<button type="button" class="speak-btn speak-inline" data-speak="${safe}" aria-label="${escapeHtml(label)}">
        <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path class="speak-body" d="M3 10v4h3.2L11 18.5V5.5L6.2 10H3Z"/>
          <path class="speak-wave speak-wave-1" d="M14 9.15a3.15 3.15 0 0 1 0 5.7"/>
          <path class="speak-wave speak-wave-2" d="M16.55 7.15a5.5 5.5 0 0 1 0 9.7"/>
        </svg>
      </button>`;
    }

    function extraLine(label, text) {
      if (!text) return "";
      return `<p class="extra${label === "例句" ? " example" : ""}"><span class="extra-label">${label}</span> ${escapeHtml(text)} ${speakBtn(text, "朗读" + label)}</p>`;
    }

    function renderWords(words) {
      if (!logs) return;
      if (!words || !words.length) {
        logs.innerHTML = '<p class="muted cal-empty">这一天还没有学习记录。</p>';
        return;
      }
      const items = words
        .map((row) => {
          const kindClass = row.kind === "review" ? "badge-learning" : "badge-new";
          const kindText = row.kind === "review" ? "复习" : "学新词";
          const ratingClass = row.rating === "easy" ? "badge-mastered" : "badge-new";
          const ratingText = row.rating === "easy" ? "学会" : "不认识";
          return `<li class="log-row cal-word">
            <div class="cal-word-head">
              <div>
                <strong class="term">${escapeHtml(row.term)}</strong>
                ${speakBtn(row.term, "朗读单词")}
                <p>${escapeHtml(row.meaning || "")}</p>
              </div>
              <div class="log-tags">
                <span class="badge ${kindClass}">${kindText}</span>
                <span class="badge ${ratingClass}">${ratingText}</span>
                <span class="badge ${statusClass(row.status)}">${statusText(row.status)}</span>
              </div>
            </div>
            ${extraLine("短语", row.phrase)}
            ${extraLine("例句", row.example)}
          </li>`;
        })
        .join("");
      logs.innerHTML = `<ul class="word-list cal-logs">${items}</ul>`;
    }

    function calLabel(cell) {
      const day = cell.getAttribute("data-day");
      const newN = cell.getAttribute("data-new");
      const reviewN = cell.getAttribute("data-review");
      const newQ = cell.getAttribute("data-new-q");
      const reviewQ = cell.getAttribute("data-review-q");
      const prefix = cell.getAttribute("data-today") === "1" ? "今天" : `${day}日`;
      let state = "还没学";
      if (cell.getAttribute("data-future") === "1") state = "还没到";
      else if (cell.getAttribute("data-complete") === "1") state = "已完成";
      else if (cell.getAttribute("data-studied") === "1") state = "进行中";
      return `${prefix}：新词 ${newN} / ${newQ} · 复习 ${reviewN} / ${reviewQ} · ${state}`;
    }

    async function loadDay(cell) {
      const date = cell.getAttribute("data-date");
      if (!date) return;
      if (cache.has(date)) {
        renderWords(cache.get(date));
        return;
      }
      if (logs) logs.innerHTML = '<p class="muted cal-empty">加载中…</p>';
      try {
        const res = await fetch(`/api/study-day?date=${encodeURIComponent(date)}`);
        if (!res.ok) throw new Error("load failed");
        const data = await res.json();
        const words = data.words || [];
        cache.set(date, words);
        renderWords(words);
      } catch (_err) {
        if (logs) logs.innerHTML = '<p class="muted cal-empty">加载失败，请稍后重试。</p>';
      }
    }

    cal.addEventListener("click", (event) => {
      const cell = event.target.closest(".cal-cell[data-date]");
      if (!cell || cell.disabled || cell.classList.contains("is-future")) return;
      cal.querySelectorAll(".cal-cell.is-selected").forEach((el) => {
        el.classList.remove("is-selected");
        el.setAttribute("aria-pressed", "false");
      });
      cell.classList.add("is-selected");
      cell.setAttribute("aria-pressed", "true");
      if (detail) detail.textContent = calLabel(cell);
      loadDay(cell);
    });
  }

  const wrap = document.querySelector(".review-wrap");
  if (!wrap) return;

  const cards = Array.from(document.querySelectorAll(".flashcard"));
  const rateBar = document.getElementById("rate-bar");
  const checkPanel = document.getElementById("check-panel");
  const readStep = document.getElementById("read-step");
  const spellStep = document.getElementById("spell-step");
  const micBtn = document.getElementById("mic-btn");
  const readHint = document.getElementById("read-hint");
  const readError = document.getElementById("read-error");
  const spellInput = document.getElementById("spell-input");
  const spellError = document.getElementById("spell-error");
  const checkCancel = document.getElementById("check-cancel");
  const donePanel = document.getElementById("done-panel");
  const deck = document.getElementById("deck");
  const doneCount = document.getElementById("done-count");
  const bar = document.getElementById("progress-bar");
  const csrf = wrap.getAttribute("data-csrf") || "";
  const needSpeak = wrap.getAttribute("data-know-speak") === "1";
  const needSpell = wrap.getAttribute("data-know-spell") === "1";
  const total = window.REVIEW_TOTAL || cards.length;
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  let index = 0;
  let finished = 0;
  let spokenText = "";
  let recognition = null;

  function current() {
    return cards[index];
  }

  function normalizeSpoken(text) {
    return (text || "")
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, " ")
      .replace(/\s+/g, " ")
      .trim();
  }

  function spokenMatches(spoken, term) {
    const want = normalizeSpoken(term);
    const said = normalizeSpoken(spoken);
    if (!want || !said) return false;
    if (said === want) return true;
    const saidTokens = said.split(" ").filter(Boolean);
    const wantTokens = want.split(" ").filter(Boolean);
    for (let i = 0; i <= saidTokens.length - wantTokens.length; i += 1) {
      if (wantTokens.every((tok, j) => saidTokens[i + j] === tok)) return true;
    }
    return false;
  }

  function stopListening() {
    if (recognition) {
      try {
        recognition.onresult = null;
        recognition.onerror = null;
        recognition.onend = null;
        recognition.stop();
      } catch (_err) {
        /* ignore */
      }
      recognition = null;
    }
    if (micBtn) {
      micBtn.classList.remove("is-listening");
      micBtn.disabled = false;
      micBtn.textContent = "开始朗读";
    }
  }

  function hideCheck() {
    stopListening();
    spokenText = "";
    const card = current();
    if (card) card.classList.remove("is-checking", "is-spelling");
    if (checkPanel) checkPanel.hidden = true;
    if (readStep) readStep.hidden = false;
    if (spellStep) spellStep.hidden = true;
    if (readError) readError.hidden = true;
    if (spellError) spellError.hidden = true;
    if (spellInput) {
      spellInput.value = "";
      spellInput.classList.remove("is-wrong");
    }
    if (readHint) {
      readHint.hidden = false;
      readHint.textContent = "点击后对着麦克风读英文。";
    }
  }

  function showRead() {
    const card = current();
    if (!card || !checkPanel) return;
    hideCheck();
    card.classList.add("is-checking");
    if (rateBar) rateBar.hidden = true;
    checkPanel.hidden = false;
    if (window.speechSynthesis) speechSynthesis.cancel();
    if (!SpeechRecognition) {
      if (readError) {
        readError.hidden = false;
        readError.textContent = "当前浏览器不支持语音识别，请使用 Chrome 或 Safari。";
      }
      if (micBtn) micBtn.disabled = true;
    }
  }

  function showSpell() {
    const card = current();
    if (!card) return;
    stopListening();
    card.classList.add("is-checking", "is-spelling");
    if (rateBar) rateBar.hidden = true;
    if (checkPanel) checkPanel.hidden = false;
    if (readStep) readStep.hidden = true;
    if (spellStep) spellStep.hidden = false;
    const spellLabel = document.getElementById("spell-label");
    if (spellLabel) {
      spellLabel.textContent = needSpeak ? "朗读正确，请默写完整单词" : "请默写完整单词";
    }
    if (spellError) spellError.hidden = true;
    if (spellInput) {
      spellInput.classList.remove("is-wrong");
      spellInput.value = "";
      spellInput.focus();
    }
  }

  function beginKnowCheck() {
    if (!needSpeak && !needSpell) {
      const btn = rateBar && rateBar.querySelector('[data-rating="easy"]');
      if (btn) btn.disabled = true;
      submitReview("easy")
        .catch(() => alert("提交失败，请检查网络后重试。"))
        .finally(() => {
          if (btn) btn.disabled = false;
        });
      return;
    }
    if (needSpeak) {
      showRead();
      return;
    }
    showSpell();
  }

  function transcriptFromEvent(event) {
    const parts = [];
    for (let i = 0; i < event.results.length; i += 1) {
      const result = event.results[i];
      for (let j = 0; j < result.length; j += 1) {
        parts.push(result[j].transcript || "");
      }
    }
    return parts;
  }

  function startListening() {
    const card = current();
    const term = card ? card.getAttribute("data-term") || "" : "";
    if (!card || !term || !SpeechRecognition) return;
    stopListening();
    if (window.speechSynthesis) speechSynthesis.cancel();
    if (readError) readError.hidden = true;
    recognition = new SpeechRecognition();
    recognition.lang = "en-US";
    recognition.interimResults = false;
    recognition.maxAlternatives = 5;
    recognition.continuous = false;
    if (micBtn) {
      micBtn.classList.add("is-listening");
      micBtn.textContent = "正在听…";
    }
    recognition.onresult = (event) => {
      const transcripts = transcriptFromEvent(event);
      const hit = transcripts.find((text) => spokenMatches(text, term));
      if (hit) {
        spokenText = hit;
        if (needSpell) {
          showSpell();
        } else {
          submitReview("easy", undefined, spokenText).catch(() => {
            alert("提交失败，请检查网络后重试。");
          });
        }
        return;
      }
      if (readError) {
        readError.hidden = false;
        readError.textContent = "没听清或读得不对，请再试一次。";
      }
    };
    recognition.onerror = () => {
      if (readError) {
        readError.hidden = false;
        readError.textContent = "没听清或读得不对，请再试一次。";
      }
    };
    recognition.onend = () => {
      if (micBtn) {
        micBtn.classList.remove("is-listening");
        micBtn.textContent = spokenText ? "朗读正确" : "开始朗读";
        micBtn.disabled = false;
      }
      recognition = null;
    };
    try {
      recognition.start();
    } catch (_err) {
      if (readError) {
        readError.hidden = false;
        readError.textContent = "无法开始录音，请检查麦克风权限。";
      }
      stopListening();
    }
  }

  function show(i) {
    cards.forEach((c) => c.classList.remove("is-current", "is-checking", "is-spelling"));
    hideCheck();
    if (!cards[i]) {
      if (deck) deck.hidden = true;
      if (rateBar) rateBar.hidden = true;
      if (checkPanel) checkPanel.hidden = true;
      if (donePanel) donePanel.hidden = false;
      const label = document.querySelector(".progress-label");
      if (label) label.hidden = true;
      return;
    }
    cards[i].classList.add("is-current");
    if (rateBar) rateBar.hidden = false;
  }

  function updateProgress() {
    if (doneCount) doneCount.textContent = String(finished);
    if (bar && total) bar.style.width = `${Math.round((finished / total) * 100)}%`;
  }

  async function submitReview(rating, spelling, spoken) {
    const card = current();
    if (!card) return false;
    const id = card.getAttribute("data-id");
    const body = { rating };
    if (spelling !== undefined) body.spelling = spelling;
    if (spoken !== undefined) body.spoken = spoken;
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
      if (data.error === "spoken") return "spoken";
    }
    if (!res.ok) throw new Error("review failed");
    finished += 1;
    updateProgress();
    index += 1;
    show(index);
    return "ok";
  }

  if (rateBar) {
    rateBar.addEventListener("click", async (event) => {
      const btn = event.target.closest("[data-rating]");
      if (!btn) return;
      const rating = btn.getAttribute("data-rating");
      if (rating === "easy") {
        beginKnowCheck();
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

  if (micBtn) {
    micBtn.addEventListener("click", () => {
      if (micBtn.classList.contains("is-listening")) {
        stopListening();
        return;
      }
      startListening();
    });
  }

  if (checkPanel) {
    checkPanel.addEventListener("submit", async (event) => {
      event.preventDefault();
      const spelling = (spellInput && spellInput.value) || "";
      const submitBtn = checkPanel.querySelector('button[type="submit"]');
      if (submitBtn) submitBtn.disabled = true;
      try {
        if (needSpeak && !spokenText) {
          if (readError) {
            readError.hidden = false;
            readError.textContent = "请先正确朗读这个单词。";
          }
          if (readStep) readStep.hidden = false;
          if (spellStep) spellStep.hidden = true;
          const card = current();
          if (card) card.classList.remove("is-spelling");
          return;
        }
        const result = await submitReview(
          "easy",
          needSpell ? spelling : undefined,
          needSpeak ? spokenText : undefined
        );
        if (result === "spelling") {
          if (spellError) spellError.hidden = false;
          if (spellInput) {
            spellInput.classList.add("is-wrong");
            spellInput.focus();
            spellInput.select();
          }
        } else if (result === "spoken") {
          spokenText = "";
          if (readStep) readStep.hidden = false;
          if (spellStep) spellStep.hidden = true;
          const card = current();
          if (card) card.classList.remove("is-spelling");
          if (readError) {
            readError.hidden = false;
            readError.textContent = "请先正确朗读这个单词。";
          }
        }
      } catch (_err) {
        alert("提交失败，请检查网络后重试。");
      } finally {
        if (submitBtn) submitBtn.disabled = false;
      }
    });
  }

  if (checkCancel) {
    checkCancel.addEventListener("click", () => {
      hideCheck();
      const card = current();
      if (card && rateBar) rateBar.hidden = false;
    });
  }

  updateProgress();
})();
