#!/usr/bin/env sh
# Starts the Estúdio (tools/audio/studio.py), creating the audio venv on the first run.
# Needs Python 3.10+ and ffmpeg on PATH.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg is not on PATH. Install it first (e.g. 'sudo apt install ffmpeg' or 'brew install ffmpeg')." >&2
  exit 1
fi
if [ ! -x "$here/.venv/bin/python" ]; then
  echo "First run: creating tools/audio/.venv and installing the pipeline (a few minutes)..."
  python3 -m venv "$here/.venv"
  "$here/.venv/bin/python" -m pip install -q -r "$here/requirements.txt"
fi
exec "$here/.venv/bin/python" -I "$here/studio.py" "$@"
