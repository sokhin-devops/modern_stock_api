// Keeps the prod API awake during the business day and reports when it doesn't answer (doc/plain/DevSecOps.md C8).
// Render free sleeps after 15 minutes without requests, and a sleeping API misses its 07:00 Telegram summary.
export default {
  async scheduled(event, env) {
    let problem = null;
    try {
      const res = await fetch(env.HEALTH_URL, { signal: AbortSignal.timeout(90_000) });
      if (!res.ok) problem = `HTTP ${res.status}`;
    } catch (e) {
      problem = e.message;
    }
    // 06:00–06:59 Phnom Penh time: the API may still be waking up, so don't alert yet
    const wakingUp = new Date(event.scheduledTime).getUTCHours() === 23;
    if (problem && !wakingUp && env.TELEGRAM_BOT_TOKEN) {
      await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ chat_id: env.TELEGRAM_CHAT_ID, text: `ModernStock API (prod) did not answer: ${problem}` }),
      });
    }
  },
};
