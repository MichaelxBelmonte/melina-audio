(() => {
  const header = document.querySelector("[data-header]");
  const year = document.querySelector("[data-year]");
  const canvas = document.querySelector("[data-signal]");
  const toggle = document.querySelector("[data-signal-toggle]");
  const monitorLabel = document.querySelector("[data-monitor-label]");
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  if (year) year.textContent = String(new Date().getFullYear());

  const updateHeader = () => header?.classList.toggle("is-scrolled", window.scrollY > 18);
  updateHeader();
  window.addEventListener("scroll", updateHeader, { passive: true });

  const revealItems = document.querySelectorAll(".reveal");
  if (reducedMotion.matches || !("IntersectionObserver" in window)) {
    revealItems.forEach((item) => item.classList.add("is-visible"));
  } else {
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        });
      },
      { threshold: 0.12 },
    );
    revealItems.forEach((item) => observer.observe(item));
  }

  if (!canvas) return;
  const context = canvas.getContext("2d");
  if (!context) return;

  let focused = true;
  let animationFrame = 0;
  let time = 0;

  const resize = () => {
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    const rect = canvas.getBoundingClientRect();
    canvas.width = Math.max(1, Math.round(rect.width * ratio));
    canvas.height = Math.max(1, Math.round(rect.height * ratio));
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
  };

  const noise = (x, seed) =>
    Math.sin(x * 0.071 + seed) * 0.42 +
    Math.sin(x * 0.137 - seed * 1.7) * 0.26 +
    Math.sin(x * 0.023 + seed * 0.4) * 0.32;

  const draw = () => {
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    const center = height / 2;
    context.clearRect(0, 0, width, height);

    const gradients = context.createLinearGradient(0, 0, width, 0);
    gradients.addColorStop(0, "rgba(200, 255, 66, 0.2)");
    gradients.addColorStop(0.18, "rgba(200, 255, 66, 0.95)");
    gradients.addColorStop(0.82, "rgba(200, 255, 66, 0.95)");
    gradients.addColorStop(1, "rgba(200, 255, 66, 0.2)");

    context.beginPath();
    for (let x = 0; x <= width; x += 2) {
      const envelope =
        0.18 +
        Math.pow(Math.sin((x / width) * Math.PI * 3.2 + time * 0.7), 8) * 0.72;
      const rawNoise = noise(x, time) * 0.54 + Math.sin(x * 0.46 + time * 2.4) * 0.18;
      const voice = Math.sin(x * 0.105 + time * 1.8) * envelope;
      const amplitude = focused
        ? voice * height * 0.29 + rawNoise * height * 0.035
        : (voice * 0.55 + rawNoise * 0.72) * height * 0.26;
      const y = center + amplitude;
      if (x === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    }
    context.strokeStyle = gradients;
    context.lineWidth = focused ? 2 : 1.25;
    context.stroke();

    context.beginPath();
    for (let x = 0; x <= width; x += 3) {
      const y = center + noise(x, time + 7) * height * (focused ? 0.018 : 0.08);
      if (x === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    }
    context.strokeStyle = focused ? "rgba(238, 242, 235, 0.22)" : "rgba(238, 242, 235, 0.38)";
    context.lineWidth = 1;
    context.stroke();

    if (!reducedMotion.matches) {
      time += 0.035;
      animationFrame = window.requestAnimationFrame(draw);
    }
  };

  toggle?.addEventListener("click", () => {
    focused = !focused;
    toggle.setAttribute("aria-pressed", String(focused));
    if (monitorLabel) {
      monitorLabel.textContent = focused ? "FOCUSED" : "RAW SIGNAL";
      monitorLabel.style.color = focused ? "var(--signal)" : "var(--paper)";
    }
    if (reducedMotion.matches) draw();
  });

  const handleMotionChange = () => {
    window.cancelAnimationFrame(animationFrame);
    draw();
  };
  reducedMotion.addEventListener?.("change", handleMotionChange);
  window.addEventListener("resize", () => {
    resize();
    if (reducedMotion.matches) draw();
  });

  resize();
  draw();
})();
