#!/usr/bin/env bash
# Plays a scenario in the dev client, headless, and records what happens.
#
#   tools/critic/run_critic.sh <scenario.json> [run-name]
#
# Output (build/critic/<run-name>/): playback.log, shots/NN_name.png, audio.wav (the real audio mix,
# recorded by OpenAL Soft's wave-file backend), latest.log (the client log), gradle.log, scenario.json.
# See tools/critic/README.md.
#
# Environment (all optional):
#   CRITIC_HARD_TIMEOUT_MIN  minutes after which the whole run is killed            (default 20)
#   CRITIC_GRADLE_ARGS       extra arguments for gradlew, e.g. "--offline"
#   CRITIC_NO_AUDIO=1        do not record audio (the game then uses no sound device)
set -uo pipefail

if [[ $# -lt 1 || $# -gt 2 || "$1" == "-h" || "$1" == "--help" ]]; then
	sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
	exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCENARIO="$(realpath "$1")"
NAME="${2:-$(basename "$SCENARIO" .json)}"
OUT="$ROOT/build/critic/$NAME"
RUN_DIR="$ROOT/run"
HARD_TIMEOUT_MIN="${CRITIC_HARD_TIMEOUT_MIN:-20}"
OPTIONS_TEMPLATE="$ROOT/tools/critic/options.txt"

if [[ ! -f "$SCENARIO" ]]; then
	echo "run_critic: no such scenario: $SCENARIO" >&2
	exit 2
fi
for tool in xvfb-run python3 ffprobe; do
	command -v "$tool" >/dev/null || echo "run_critic: warning: '$tool' not found on PATH" >&2
done

# --- settings the script needs from the scenario (also checks that it is JSON) ---
SETTINGS="$(python3 -I - "$SCENARIO" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as f:
        data = json.load(f)
except Exception as e:
    print("ERROR %s" % e)
    sys.exit(0)
s = data.get("settings", {}) if isinstance(data, dict) else {}
steps = data.get("steps", []) if isinstance(data, dict) else data
w, h = (s.get("window") or "1280x720").split("x")
print("OK %s %s %d %s %s" % (s.get("lang", "en_us"), s.get("gui_scale", 3), len(steps), w, h))
PY
)"
if [[ "$SETTINGS" != OK* ]]; then
	echo "run_critic: the scenario is not valid JSON: ${SETTINGS#ERROR }" >&2
	exit 2
fi
read -r _ GAME_LANG GUI_SCALE STEP_COUNT WIN_W WIN_H <<<"$SETTINGS"

# --- output directory and OpenAL configuration ---
rm -rf "$OUT"
mkdir -p "$OUT/shots"
cp "$SCENARIO" "$OUT/scenario.json"

if [[ -z "${CRITIC_NO_AUDIO:-}" ]]; then
	cat >"$OUT/alsoft.conf" <<EOF
[general]
drivers = wave
channels = stereo
sample-type = int16
frequency = 44100

[wave]
file = $OUT/audio.wav
EOF
	export ALSOFT_CONF="$OUT/alsoft.conf"
	export ALSOFT_DRIVERS=wave
fi

# --- options.txt: back up, install, and restore on exit however the script ends ---
mkdir -p "$RUN_DIR"
OPTIONS="$RUN_DIR/options.txt"
BACKUP="$RUN_DIR/options.txt.critic-backup"
HAD_OPTIONS=0
if [[ -f "$OPTIONS" ]]; then
	HAD_OPTIONS=1
	cp -p "$OPTIONS" "$BACKUP"
fi
restore_options() {
	if [[ "$HAD_OPTIONS" == 1 ]]; then
		mv -f "$BACKUP" "$OPTIONS"
	else
		rm -f "$OPTIONS"
	fi
}
GAME_PID=""
WATCHDOG_PID=""
cleanup() {
	[[ -n "$WATCHDOG_PID" ]] && kill "$WATCHDOG_PID" 2>/dev/null
	[[ -n "$GAME_PID" ]] && kill -TERM -- "-$GAME_PID" 2>/dev/null
	restore_options
}
trap cleanup EXIT
trap 'echo "run_critic: interrupted" >&2; exit 130' INT TERM

sed -e "s/@LANG@/$GAME_LANG/" -e "s/@GUI_SCALE@/$GUI_SCALE/" \
	"$OPTIONS_TEMPLATE" >"$OPTIONS"
cp "$OPTIONS" "$OUT/options.txt"

# The previous run's log would otherwise be mistaken for this run's if the game never starts.
rm -f "$RUN_DIR/logs/latest.log"

# --- run the game ---
echo "run_critic: $NAME: $STEP_COUNT steps, lang $GAME_LANG, window ${WIN_W}x${WIN_H}, output in $OUT"
START="$(date +%s)"
cd "$ROOT" || exit 1
export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
# setsid gives the run its own process group, so a timeout can kill Xvfb, Gradle and the game together.
# shellcheck disable=SC2086
setsid xvfb-run -a -s "-screen 0 ${WIN_W}x${WIN_H}x24" ./gradlew runClient --no-daemon ${CRITIC_GRADLE_ARGS:-} \
	"-Pcritic=$SCENARIO" "-PcriticOut=$OUT" "-PcriticWidth=$WIN_W" "-PcriticHeight=$WIN_H" >"$OUT/gradle.log" 2>&1 &
GAME_PID=$!
(
	# Run the sleeps as background jobs so that killing this subshell kills them too; an orphaned
	# sleep would keep the script's stdout pipe open for the rest of its time.
	sleep $((HARD_TIMEOUT_MIN * 60)) &
	SLEEP_PID=$!
	trap 'kill "$SLEEP_PID" 2>/dev/null; exit 0' TERM
	wait "$SLEEP_PID"
	echo "run_critic: hard timeout after $HARD_TIMEOUT_MIN min; killing the run" >&2
	kill -TERM -- "-$GAME_PID" 2>/dev/null
	sleep 20
	kill -KILL -- "-$GAME_PID" 2>/dev/null
) &
WATCHDOG_PID=$!
wait "$GAME_PID"
STATUS=$?
kill "$WATCHDOG_PID" 2>/dev/null
wait "$WATCHDOG_PID" 2>/dev/null
WATCHDOG_PID=""
GAME_PID=""
ELAPSED=$(($(date +%s) - START))

# --- collect ---
[[ -f "$RUN_DIR/logs/latest.log" ]] && cp "$RUN_DIR/logs/latest.log" "$OUT/latest.log"
restore_options
HAD_OPTIONS=0
trap - EXIT

# --- summary ---
SHOTS="$(find "$OUT/shots" -name '*.png' 2>/dev/null | wc -l)"
LOG_LINES=0
WARNINGS=0
COMPLETED="no"
if [[ -f "$OUT/playback.log" ]]; then
	LOG_LINES="$(wc -l <"$OUT/playback.log")"
	WARNINGS="$(grep -c '"type":"warn"' "$OUT/playback.log")"
	grep -q '"type":"end"' "$OUT/playback.log" && COMPLETED="yes"
fi
AUDIO="none"
if [[ -s "$OUT/audio.wav" ]]; then
	DURATION="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$OUT/audio.wav" 2>/dev/null || true)"
	AUDIO="$(du -h "$OUT/audio.wav" | cut -f1), ${DURATION:-unreadable} s"
fi
echo "---- run_critic summary: $NAME ----"
echo "scenario completed : $COMPLETED   (exit status $STATUS, $ELAPSED s)"
echo "screenshots        : $SHOTS   ($OUT/shots)"
echo "audio.wav          : $AUDIO"
echo "playback.log       : $LOG_LINES lines, $WARNINGS warnings   ($OUT/playback.log)"
echo "client log         : $OUT/latest.log   (gradle: $OUT/gradle.log)"
if [[ "$COMPLETED" != yes ]]; then
	[[ -f "$OUT/playback.log" ]] && grep '"type":"fatal"' "$OUT/playback.log" | head -3 >&2
	echo "run_critic: the scenario did not finish; see the end of $OUT/gradle.log and $OUT/latest.log" >&2
	exit 1
fi
exit 0
