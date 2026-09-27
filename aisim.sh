#!/usr/bin/env bash
# aisim.sh: headless AI-vs-AI harness for Tribal Trouble. Manual: docs/aisim.md
# Everything except `build` runs plain java from the latest build snapshot, never from Gradle's output folders,
# so you can keep editing and building while batches run.
set -euo pipefail
cd "$(dirname "$0")"

find_jdk() {
    local dir
    for dir in "${AISIM_JDK:-}" "${JAVA_HOME:-}" /c/Program\ Files/Java/jdk-26* /usr/lib/jvm/*26* \
            /Library/Java/JavaVirtualMachines/*26*/Contents/Home; do
        if [ -n "$dir" ] && [ -x "$dir/bin/java" ] && "$dir/bin/java" -version 2>&1 | grep -q 'version "26'; then
            echo "$dir"
            return 0
        fi
    done
    echo "aisim: no JDK 26 found; set AISIM_JDK to a JDK 26 folder" >&2
    return 1
}

# Sets snap to the latest build snapshot's id, the first field of aisim/snap/latest; exits 2 without one.
read_snapshot() {
    if [ ! -f aisim/snap/latest ]; then
        echo "aisim: no snapshot yet: run ./aisim.sh build" >&2
        exit 2
    fi
    read -r snap _ < aisim/snap/latest
}

# Help must work on a fresh clone, before the first build and without a JDK: say what to run first.
case "${1:-help}" in
    help | -h | --help)
        if [ ! -f aisim/snap/latest ]; then
            echo "aisim: no build yet. Run ./aisim.sh build first. The commands: docs/aisim.md#commands"
            exit 0
        fi
        set -- help # Aisim knows only "help"; it would reject -h and --help with exit 2
        ;;
esac

JDK=$(find_jdk)
# the parent JVM; workers get the same options from Batch.workerCommand (keep in sync)
OPTS=(-ea --enable-native-access=ALL-UNNAMED -Xmx768m -XX:+UseSerialGC -Djava.awt.headless=true)
[ "$(uname)" = Darwin ] && OPTS+=(-XstartOnFirstThread) # GLFW must own the first thread on macOS

case "${1:-help}" in
    build)
        # :tt:aisimClasspath writes live.args (the fresh build's class path); Aisim snapshot runs from it and copies
        # the build's classes into aisim/snap/
        JAVA_HOME="$JDK" ./gradlew -q :tt:aisimClasspath
        exec "$JDK/bin/java" "${OPTS[@]}" @tt/build/aisim/live.args com.oddlabs.tt.aisim.Aisim snapshot
        ;;
    gui)
        # The game itself, with SPEC in single-player "Hard" slots (PlayTest).
        shift
        # --stale-ok is for guicheck, not for the game: com.oddlabs.tt.Main rejects flags it does not know
        stale_ok=
        if [ "${1:-}" = --stale-ok ]; then
            stale_ok=--stale-ok
            shift
        fi
        if [ $# -eq 0 ]; then
            echo "aisim: usage: ./aisim.sh gui [--stale-ok] SPEC [game args]" >&2
            exit 2
        fi
        spec=$1
        shift
        read_snapshot # once: aisim.snap below always names the class path the game runs on
        cp_args="aisim/snap/$snap/cp.args"
        # Aisim guicheck (internal) checks SPEC and the build's freshness. ${stale_ok:+...} adds no argument when
        # stale_ok is empty (an empty array would do, but "${a[@]}" of one fails under set -u in macOS's bash 3.2).
        "$JDK/bin/java" "${OPTS[@]}" "@$cp_args" com.oddlabs.tt.aisim.Aisim guicheck ${stale_ok:+"$stale_ok"} "$spec" \
            || exit 2
        # the game's options, as tt/build.gradle.kts gives `gradlew :tt:run` (keep in sync)
        GUI_OPTS=(-ea -esa --enable-native-access=ALL-UNNAMED -Dcom.oddlabs.tt.developer=true
            -Djdk.crypto.KeyAgreement.legacyKDF=true -Xms80m -Xmx512m)
        [ "$(uname)" = Darwin ] && GUI_OPTS+=(-XstartOnFirstThread)
        # the game finds its resources relative to tt/, as under gradlew :tt:run
        cd tt
        exec "$JDK/bin/java" "${GUI_OPTS[@]}" "-Dcom.oddlabs.tt.hard_ai=$spec" "-Daisim.snap=$snap" \
            "-Dorg.lwjgl.system.SharedLibraryExtractPath=../aisim/natives/gui" "@../$cp_args" com.oddlabs.tt.Main "$@"
        ;;
    lab)
        # Your own tool: java runs a .java source file straight from source (with the other .java files of its folder)
        # on the last build's class path. No -Xmx, so JDK_JAVA_OPTIONS can set the heap (docs/aisim.md#your-own-tools).
        shift
        if [ $# -eq 0 ] || [ "${1%.java}" = "$1" ]; then
            echo "aisim: usage: ./aisim.sh lab FILE.java [args]" >&2
            exit 2
        fi
        read_snapshot
        exec "$JDK/bin/java" -ea -Djava.awt.headless=true "@aisim/snap/$snap/cp.args" "$@"
        ;;
    *)
        read_snapshot
        exec "$JDK/bin/java" "${OPTS[@]}" "@aisim/snap/$snap/cp.args" com.oddlabs.tt.aisim.Aisim "$@"
        ;;
esac
