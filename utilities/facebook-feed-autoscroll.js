(() => {
  const durationMs = 8 * 60 * 1000;
  const stepPx = 900;
  const intervalMs = 1200;
  const start = Date.now();

  if (window.facebookFeedAutoScrollTimer) {
    clearInterval(window.facebookFeedAutoScrollTimer);
  }

  window.facebookFeedAutoScrollTimer = setInterval(() => {
    const elapsed = Date.now() - start;

    if (elapsed >= durationMs) {
      clearInterval(window.facebookFeedAutoScrollTimer);
      window.facebookFeedAutoScrollTimer = null;
      console.log("Done scrolling Facebook feed.");
      return;
    }

    window.scrollBy({
      top: stepPx + Math.floor(Math.random() * 400),
      left: 0,
      behavior: "smooth"
    });

    console.log(`Scrolled for ${Math.round(elapsed / 1000)}s...`);
  }, intervalMs);

  console.log("Started auto-scrolling. Stop early with:");
  console.log("clearInterval(window.facebookFeedAutoScrollTimer); window.facebookFeedAutoScrollTimer = null;");
})();
