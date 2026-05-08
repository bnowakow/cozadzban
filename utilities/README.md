# Utilities

## Facebook feed auto-scroll

Paste `facebook-feed-autoscroll.js` into the browser developer console while Facebook is open. It scrolls the feed for 2 minutes to load more posts.

To extend the 2 minute period, edit this line in the script:

```javascript
const durationMs = 2 * 60 * 1000;
```

For example, use `5 * 60 * 1000` for 5 minutes.

To stop it early, run:

```javascript
clearInterval(window.facebookFeedAutoScrollTimer);
window.facebookFeedAutoScrollTimer = null;
```
