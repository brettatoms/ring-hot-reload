# ring-hot-reload

[![Clojars Project](https://img.shields.io/clojars/v/com.github.brettatoms/ring-hot-reload.svg)](https://clojars.org/com.github.brettatoms/ring-hot-reload)

A Ring middleware that provides hot reload for server-rendered Clojure applications.
When server-side code changes (file save or REPL eval), the browser automatically
re-fetches the page and morphs the DOM in place using [idiomorph](https://github.com/bigskysoftware/idiomorph),
preserving scroll position, focus, and form state.

## Features

- **DOM morphing** — No full page reload. The DOM is diffed and patched in place.
- **Error overlay** — Server errors are displayed in a dismissable overlay with the
  full error response rendered in an iframe. Automatically dismissed on next successful reload.
- **Framework-friendly** — Only injects into full HTML pages. Partial responses
  (htmx fragments, datastar SSE) are left untouched.
- **nREPL integration** — Optional nREPL middleware triggers reload on eval, so
  evaluating code in your editor refreshes the browser without saving the file.
- **No framework dependencies** — Works with any Ring 1.12+ application.

## Installation

Add to your `deps.edn`:

```clojure
{:deps {com.github.brettatoms/ring-hot-reload {:mvn/version "RELEASE"}}}
```

> **Note:** This library requires Ring 1.12+ for WebSocket support (`ring.websocket`).

## Quick Start

```clojure
(require '[ring.hot-reload.core :as hot])

(defn my-handler [request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "<html><body><h1>Hello</h1></body></html>"})

;; 1. Create a reloader
(def hr (hot/hot-reloader {:watch-paths ["src"]}))

;; 2. Wrap your handler with the middleware
(def app (hot/wrap-hot-reload my-handler hr))

;; 3. Start watching for file changes
(def watcher-handle (hot/start! hr))

;; ... later, to stop:
;; (hot/stop! hr watcher-handle)
```

The middleware:
1. Intercepts requests to `/__hot-reload` and handles WebSocket upgrades
2. Injects a client script into full HTML page responses
3. Watches `src/` for file changes and notifies connected browsers

## Configuration

`wrap-hot-reload` accepts an options map:

| Option | Default | Description |
|---|---|---|
| `:watch-paths` | `["src"]` | Directories to watch for file changes |
| `:watch-extensions` | `#{".clj" ".cljc" ".edn" ".html" ".css"}` | File extensions that trigger reload |
| `:uri-prefix` | `"/__hot-reload"` | Path for the WebSocket endpoint |
| `:inject?` | `(constantly true)` | Predicate `(fn [request response])` controlling script injection |
| `:debounce-ms` | `100` | Debounce window in milliseconds |

### Watch paths

By default only `"src"` is watched. If your templates or other files live
elsewhere, add those directories. The handler must read files on each request
(not cached at startup) for changes to be reflected:

```clojure
;; Watch both source code and template files
(hot/wrap-hot-reload handler {:watch-paths ["src" "resources/templates"]})

;; Handler reads the template on each request — changes are picked up
(defn my-handler [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (io/resource "templates/page.html"))})
```

### Watch extensions

Override which file types trigger a reload:

```clojure
(hot/wrap-hot-reload handler {:watch-extensions #{".clj" ".cljc" ".mustache"}})
```

### Selective injection

Skip script injection for specific routes (e.g. API endpoints that happen
to return HTML):

```clojure
(hot/wrap-hot-reload handler
  {:inject? (fn [request _response]
              (not (str/starts-with? (:uri request) "/api/")))})
```

## API

### `hot-reloader`

Creates a reloader — a map of composable pieces. Does not start watching.

```clojure
(def hr (hot/hot-reloader opts))
```

The reloader map contains:

| Key | Description |
|---|---|
| `:ws-handler` | Ring handler for the WebSocket endpoint |
| `:injection-middleware` | Ring middleware `(fn [handler] -> handler)` that injects the client script |
| `:script` | JavaScript string containing the client code |
| `:uri-prefix` | The WebSocket endpoint path |

### `wrap-hot-reload`

Standard Ring middleware. Takes a handler and a reloader, returns a handler.

```clojure
(def app (hot/wrap-hot-reload my-handler hr))
```

### `start!` / `stop!`

Start and stop the file watcher.

```clojure
(def handle (hot/start! hr))
;; ... later:
(hot/stop! hr handle)
```

### Lifecycle Management

The caller is responsible for starting and stopping the watcher. A typical
pattern with a Ring adapter:

```clojure
(require '[ring.adapter.jetty :as jetty]
         '[ring.hot-reload.core :as hot])

(defonce server (atom nil))
(defonce hr (hot/hot-reloader {:watch-paths ["src"]}))
(defonce watcher-handle (atom nil))

(defn start! []
  (let [app (hot/wrap-hot-reload #'my-handler hr)]
    (reset! server (jetty/run-jetty app {:port 3000 :join? false}))
    (reset! watcher-handle (hot/start! hr))))

(defn stop! []
  (when-let [h @watcher-handle]
    (hot/stop! hr h))
  (when-let [s @server]
    (.stop s)))
```

## nREPL Integration

The optional nREPL middleware triggers a hot reload whenever an `eval` operation
completes in your REPL. This means evaluating a form in your editor refreshes
the browser — even without saving the file.

The middleware must be configured at nREPL startup (it cannot be added dynamically).
There are several ways to set it up:

### Via .nrepl.edn

```clojure
{:middleware ^:concat [ring.hot-reload.nrepl/wrap-hot-reload-nrepl]}
```

### Via deps.edn alias

```clojure
{:aliases {:dev {:extra-deps {nrepl/nrepl {:mvn/version "1.3.0"}}
                 :main-opts ["-m" "nrepl.cmdline"
                             ;; WARNING: This will replace any existing middleware
                             "--middleware" "[ring.hot-reload.nrepl/wrap-hot-reload-nrepl]"]}}}
```

### Via CIDER (Emacs)

Add to your `.dir-locals.el` (appends to the existing CIDER middleware list):

```elisp
((clojure-mode
  (eval . (progn
            (make-local-variable 'cider-jack-in-nrepl-middlewares)
            (add-to-list 'cider-jack-in-nrepl-middlewares "ring.hot-reload.nrepl/wrap-hot-reload-nrepl")))))
```

Or add it globally to your Emacs config:

```elisp
(add-to-list 'cider-jack-in-nrepl-middlewares "ring.hot-reload.nrepl/wrap-hot-reload-nrepl")
```

> **Note:** Setting `cider-jack-in-nrepl-middlewares` directly in `.dir-locals.el`
> replaces the default CIDER middleware. Use the `eval`/`add-to-list` form above
> to append instead.

The nREPL middleware communicates with the Ring middleware through a global
notification mechanism. No additional configuration is needed — it works
automatically when both middleware are loaded in the same JVM.

## How It Works

### Clojure source changes

For Clojure source changes, the typical workflow is REPL-driven:

1. Edit code in your editor
2. Eval the changed form (e.g. `C-c C-c` in CIDER)
3. Save the file — the watcher detects the save and notifies the browser
4. The browser re-fetches the page and morphs the DOM

Saving the file triggers the browser re-fetch, but the code must be evaluated
in the REPL first for changes to take effect. The file watcher doesn't
re-evaluate Clojure files — it only signals the browser.

### Template / static file changes

For HTML templates, CSS, and other files read from disk at request time
(e.g. via `slurp`), saving the file is sufficient — no REPL eval needed:

```
Save file → watcher detects change → debounce (100ms) → notify browser
  → browser fetches page → server reads updated file → idiomorph morphs DOM
```

### What the client does

1. Connects to the WebSocket and listens for reload signals
2. On reload signal, fetches the current page via `fetch()`
3. If the response is OK, morphs the DOM using idiomorph (preserving focus, scroll, form state)
4. If the response is an error, displays a dismissable overlay with the error rendered in an iframe
5. On next successful reload, the error overlay is automatically dismissed
6. Auto-reconnects on disconnect with exponential backoff

## Composable Usage (Reitit, etc.)

For applications using a router, you can use the reloader's pieces directly
instead of `wrap-hot-reload`:

```clojure
(require '[ring.hot-reload.core :as hot]
         '[reitit.ring :as ring])

(def hr (hot/hot-reloader {:watch-paths ["src" "resources/templates"]
                            :uri-prefix "/__hot-reload"}))

(def app
  (ring/ring-handler
   (ring/router
    [[(:uri-prefix hr) {:get (:ws-handler hr)
                        :no-doc true}]
     ["" {:middleware [(:injection-middleware hr)]}
      ["/" {:get home-handler}]
      ["/about" {:get about-handler}]]])))

(def watcher-handle (hot/start! hr))
;; ... (hot/stop! hr watcher-handle) to shut down
```

This approach avoids wrapping the entire handler, so the WebSocket endpoint
participates in your router's middleware stack naturally.

## Vite Integration

ring-hot-reload works alongside a Vite dev server. Use `wrap-hot-reload` for
Clojure/template changes (full page morph), and Vite's `@vite/client` for
CSS/JS HMR (instant, partial updates). The two mechanisms are independent —
no Vite plugin required.

For automatic Vite integration (dev server lifecycle, asset URL resolution,
`@vite/client` injection), see [zodiac-assets](https://github.com/brettatoms/zodiac-assets).

## Server Adapter Compatibility

This library uses Ring's standard `ring.websocket` protocol (introduced in Ring 1.12)
for the WebSocket endpoint. Your server adapter must support this protocol.

**Compatible adapters:**
- `ring/ring-jetty-adapter` 1.12+
- `info.sunng/ring-jetty9-adapter` 0.35+
- `http-kit` 2.8+

The WebSocket endpoint uses a dedicated path (`/__hot-reload` by default) and does
not conflict with other WebSocket endpoints in your application. If your app has its
own WebSocket routes, they work independently — Ring's WebSocket model is per-request,
so each path can return its own `::ws/listener` response.

## Events

The client script dispatches two events on `document` around each DOM morph:

### `hot-reload:before-morph`

Dispatched before the DOM is morphed. The event is **cancelable** — if a
listener calls `preventDefault()`, ring-hot-reload skips its built-in
Idiomorph call, allowing the app to handle the morph itself.

| `event.detail` | Description |
|---|---|
| `html` | The fetched HTML string (after DOCTYPE removal and stylesheet preservation) |

```javascript
document.addEventListener('hot-reload:before-morph', (e) => {
  // e.detail.html contains the new HTML
  myCustomMorph(document.body, e.detail.html);
  e.preventDefault(); // skip the default Idiomorph morph
});
```

### `hot-reload:after-morph`

Dispatched after the morph completes (whether handled by Idiomorph or a
custom handler).

```javascript
document.addEventListener('hot-reload:after-morph', () => {
  // Re-initialize your framework here
});
```

### Alpine.js Integration

Alpine.js attaches reactive state to DOM elements. The default Idiomorph
morph is Alpine-unaware — it patches the DOM but leaves Alpine's internal
state stale, causing reactive effects (e.g. `x-show`, `x-bind`) to break.

The recommended fix is to use Alpine's own [morph plugin](https://alpinejs.dev/plugins/morph)
via the `before-morph` event. Alpine.morph understands Alpine's reactive
system and preserves state across DOM updates:

```javascript
import morph from '@alpinejs/morph';
Alpine.plugin(morph);

document.addEventListener('hot-reload:before-morph', (e) => {
  const doc = new DOMParser().parseFromString(e.detail.html, 'text/html');
  Alpine.morph(document.body, doc.body);
  e.preventDefault();
});
```

If you don't need to preserve Alpine state, you can use the simpler
`after-morph` approach to destroy and re-initialize Alpine after each morph:

```javascript
document.addEventListener('hot-reload:after-morph', () => {
  Alpine.destroyTree(document.body);
  Alpine.initTree(document.body);
});
```

## Known Limitations

- **macOS file watch:** Beholder uses [directory-watcher](https://github.com/gmethvin/directory-watcher)
  which provides native macOS FSEvents support via JNA, so file change detection should
  be near-instant. The watcher is behind a `Watcher` protocol so the implementation can
  be swapped if needed.
- **String response bodies only:** Script injection only works when the Ring response
  body is a string. If your handler returns an InputStream or File body, the script
  won't be injected.

## License

Copyright © 2026 Brett Adams

Distributed under the MIT License. See [LICENSE](LICENSE) for details.
