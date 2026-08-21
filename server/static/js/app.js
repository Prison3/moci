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
})();
