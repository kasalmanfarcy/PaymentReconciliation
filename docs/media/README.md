# Demo recording (M11.12)

**Status:** prepared, not recorded. Recording needs a person at a terminal; the README shows a text
excerpt of a real `scripts/demo.sh` run until the GIF exists.

## Record it (about 10 minutes)

```bash
brew install asciinema agg                      # recorder and GIF renderer, both local tools
docker compose --profile full up -d --build     # start the stack first, so the recording has no build
# wait until `curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/imports` prints 401
# resize the terminal to about 100 x 30 first; -i caps idle pauses at 1.5 s
asciinema rec -i 1.5 -c './scripts/demo.sh --no-start' docs/media/demo.cast
agg --font-size 18 --speed 1.5 docs/media/demo.cast docs/media/demo.gif
```

`-i` (idle time limit) cuts the waits for the worker; `demo.sh` pauses one second before each step so a
viewer can read it (`DEMO_FAST=1` turns that off). Run the demo once before recording if you want
the second-run behaviour (re-uploads answered `409` and reused) instead of the first.

Keep `demo.gif` under ~5 MB (check with `ls -lh`); if it's larger, raise `--speed` or record with
fewer rows. The three moments worth seeing are the run summary, the work queue and the stale-ETag
`409`.

## Put it on the README

Replace the text excerpt under the status note with:

```markdown
![ReconFlow demo: upload, reconcile, resolve with If-Match, a stale ETag rejected](docs/media/demo.gif)
```

Commit `demo.gif` (not `demo.cast`, unless you want the replayable source too) and tick M11.12 in
TASKS.md.
