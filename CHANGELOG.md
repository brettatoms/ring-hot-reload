# Change Log

* 0.2.9 -- 2026-04-01
  - Add `hot-reload:before-morph` event (cancelable) dispatched before DOM morphing
  - Add `hot-reload:after-morph` event dispatched after DOM morphing
  - Apps can use `before-morph` to replace Idiomorph with a custom morph (e.g. Alpine.morph)

* 0.2.6 -- 2026-03-23
  - Refactor API: introduce `hot-reloader` fn returning composable pieces
  - Add `start!`/`stop!` fns for watcher lifecycle
  - Make `wrap-hot-reload` true Ring middleware
  - Remove `create-hot-reload` (replaced by `hot-reloader`)

* 0.1.5 -- 2026-03-21
  - Preserve stylesheet hrefs during morph to prevent Vite HMR conflicts

* 0.1.3 -- 2026-03-21
  - Promote nREPL dependency from :dev alias

* 0.1.2 -- 2026-03-21
  - Initial release
