# ring-hot-reload

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

;; Create the hot reload middleware
(let [{:keys [handler start! stop!]} (hot/wrap-hot-reload my-handler)]
  ;; `handler` is your new Ring handler — use it with your server adapter
  ;; Start watching for file changes
  (def watcher-handle (start!))

  ;; ... later, to stop:
  ;; (stop! watcher-handle)
  )
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

## Return Value

`wrap-hot-reload` returns a map:

| Key | Description |
|---|---|
| `:handler` | The Ring handler to pass to your server adapter |
| `:watcher` | The `Watcher` instance (for advanced use) |
| `:start!` | `(fn [])` — starts watching; returns a handle |
| `:stop!` | `(fn [handle])` — stops watching, cleans up resources |
| `:notify!` | `(fn [])` — manually trigger a reload |

### Lifecycle Management

The caller is responsible for starting and stopping the watcher. A typical
pattern with a Ring adapter:

```clojure
(require '[ring.adapter.jetty :as jetty]
         '[ring.hot-reload.core :as hot])

(defonce server (atom nil))
(defonce watcher-handle (atom nil))

(defn start! []
  (let [{:keys [handler start! stop!]} (hot/wrap-hot-reload #'my-handler)]
    (reset! server (jetty/run-jetty handler {:port 3000 :join? false}))
    (reset! watcher-handle (start!))))

(defn stop! []
  (when-let [h @watcher-handle]
    ;; stop! from the wrap-hot-reload return map
    ;; you'll need to capture it; see note below
    )
  (when-let [s @server]
    (.stop s)))
```

> **Tip:** Capture the `stop!` function from the return map alongside the watcher
> handle so you can call it during shutdown.

## nREPL Integration

The optional nREPL middleware triggers a hot reload whenever an `eval` operation
completes in your REPL. This means evaluating a form in your editor refreshes
the browser — even without saving the file.

The middleware must be configured at nREPL startup (it cannot be added dynamically).
There are several ways to set it up:

### Via .nrepl.edn

```clojure
{:middleware [ring.hot-reload.nrepl/wrap-hot-reload-nrepl]}
```

### Via deps.edn alias

```clojure
{:aliases
 {:dev {:extra-deps {nrepl/nrepl {:mvn/version "1.3.0"}}
        :main-opts ["-m" "nrepl.cmdline"
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

## Known Limitations

- **macOS file watch:** Beholder uses [directory-watcher](https://github.com/gmethvin/directory-watcher)
  which provides native macOS FSEvents support via JNA, so file change detection should
  be near-instant. The watcher is behind a `Watcher` protocol so the implementation can
  be swapped if needed.
- **String response bodies only:** Script injection only works when the Ring response
  body is a string. If your handler returns an InputStream or File body, the script
  won't be injected.

## License

Copyright © 2025 Brett Adams

Distributed under the MIT License. See [LICENSE](LICENSE) for details.
