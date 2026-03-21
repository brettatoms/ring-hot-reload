var overlay = null;
var overlayTemplate = '{{overlay-template}}';

function bustStylesheetCache() {
  var timestamp = Date.now();
  document.querySelectorAll('link[rel="stylesheet"]').forEach(function(link) {
    var href = link.getAttribute('href');
    if (href) {
      var base = href.replace(/[?&]_hot=\d+/, '');
      var sep = base.indexOf('?') === -1 ? '?' : '&';
      link.setAttribute('href', base + sep + '_hot=' + timestamp);
    }
  });
}

function reload() {
  console.log('[ring-hot-reload] reloading...');
  fetch(window.location.href, {headers: {'Accept': 'text/html'}})
    .then(function(r) {
      if (!r.ok) {
        return r.text().then(function(html) {
          showErrorOverlay(html, r.status);
        });
      }
      return r.text().then(function(html) {
        if (html) {
          console.log('[ring-hot-reload] morphing DOM, html length:', html.length);
          dismissOverlay();
          var cleaned = html.replace(/<!DOCTYPE[^>]*>/i, '').trim();
          Idiomorph.morph(document.documentElement, cleaned, {
            head: {style: 'merge'},
            morphStyle: 'outerHTML'
          });
          if ('{{bust-css-cache}}' === 'true') bustStylesheetCache();
        }
      });
    })
    .catch(function(err) {
      console.error('[ring-hot-reload] fetch failed:', err);
    });
}

function showErrorOverlay(html, status) {
  dismissOverlay();
  var rendered = overlayTemplate
    .replace('{{status}}', status)
    .replace('{{srcdoc}}', html.replace(/&/g, '&amp;').replace(/"/g, '&quot;'));
  document.body.insertAdjacentHTML('beforeend', rendered);
  overlay = document.getElementById('__hot-reload-overlay');
  overlay.addEventListener('click', function(e) {
    if (e.target === overlay) dismissOverlay();
  });
}

function dismissOverlay() {
  if (overlay) {
    overlay.remove();
    overlay = null;
  }
}
