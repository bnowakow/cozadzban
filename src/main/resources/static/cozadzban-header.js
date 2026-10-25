(function () {
  if (window.cozadzbanHeaderInstalled) return;
  window.cozadzbanHeaderInstalled = true;

  function closeMenu() {
    document.querySelectorAll('.czj-site-menu[open]').forEach(function (menu) {
      menu.removeAttribute('open');
    });
  }

  document.addEventListener('click', function (event) {
    if (!(event.target instanceof Element)) return;
    if (!event.target.closest('.czj-site-menu')) closeMenu();
    var action = event.target.closest('.czj-site-header [data-action]');
    if (!action || action.disabled) return;
    var name = action.getAttribute('data-action');
    if (name === 'theme') {
      var dark = document.documentElement.getAttribute('theme') === 'dark';
      document.documentElement.removeAttribute('theme');
      document.body.removeAttribute('theme');
      if (!dark) {
        document.documentElement.setAttribute('theme', 'dark');
        document.body.setAttribute('theme', 'dark');
      }
      try { localStorage.setItem('cozadzban-theme', dark ? 'light' : 'dark'); } catch (ignored) {}
      closeMenu();
      return;
    }
    var paths = {
      'import-apify': '/admin/facebook-import/run/apify',
      'import-selenium': '/admin/facebook-import/run/selenium',
      'stop-import': '/admin/facebook-import/terminate'
    };
    if (!paths[name]) return;
    action.disabled = true;
    var csrf = document.querySelector('meta[name="_csrf"]');
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
    if (!csrf || !csrfHeader) {
      action.disabled = false;
      alert('Please reload the page and try again.');
      return;
    }
    var headers = {};
    headers[csrfHeader.content] = csrf.content;
    fetch(paths[name], { method: 'POST', credentials: 'same-origin', headers: headers }).then(function (response) {
      if (!response.ok) throw new Error('Facebook import request failed (' + response.status + ')');
      location.reload();
    }).catch(function (error) {
      action.disabled = false;
      alert(error.message);
    });
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') closeMenu();
  });

  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form.matches('.czj-site-logout')) return;
    event.preventDefault();
    var body = new FormData(form);
    var csrf = document.querySelector('meta[name="_csrf"]');
    var csrfParameter = document.querySelector('meta[name="_csrf_parameter"]');
    if (csrf && csrfParameter && !body.has(csrfParameter.content)) {
      body.set(csrfParameter.content, csrf.content);
    }
    fetch(form.action, { method: 'POST', credentials: 'same-origin', body: body }).then(function (response) {
      if (!response.ok) throw new Error('Logout failed (' + response.status + ')');
      location.href = '/';
    }).catch(function () { location.reload(); });
  });
})();
