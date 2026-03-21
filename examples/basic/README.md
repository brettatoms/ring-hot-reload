# ring-hot-reload example

A todo app using htmx for interactivity, with ring-hot-reload for
full-page hot reload on file changes.

## Running

```bash
cd examples/basic
clojure -M -m example
```

Then open http://localhost:3000.

## Try it out

1. Add, toggle, and delete todos — htmx handles these as partial updates
2. Edit `resources/templates/page.html` and save — the page hot reloads immediately
   (templates are read from disk on each request)
3. Edit `src/example.clj` and eval the changed form in your REPL — the page
   hot reloads immediately (no save needed, via the nREPL middleware in `.nrepl.edn`)
4. Type in the add field, then save a file — input state is preserved
