#!/usr/bin/env bash
# Builds SNTelegram without touching the network.
#
# The normal build is Gradle against the real paper-api. This script exists for the case where
# Maven Central or repo.papermc.io is unreachable - which, for a Russian-hosted box, is a Tuesday.
# It compiles against the tiny compile-only stubs in ./bukkit-stubs, which are NOT included in the
# resulting jar: the server supplies the real classes.
#
# The stubs are kept honest by tools/offline/api-surface.txt and by the CI job that compiles the
# same sources against the real paper-api and diffs the emitted method descriptors. A stub whose
# signature drifts compiles perfectly and then throws NoSuchMethodError on a live server, so that
# comparison is the whole reason this build is trustworthy.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Everything below runs from the project root and uses relative paths on purpose. javac is a
# native Windows binary under Git Bash and cannot resolve the MSYS-style /d/GitHub/... lines that
# `find` would otherwise write into the @argfile - the shell translates command-line arguments for
# it, but never the contents of a file. Relative paths are understood identically on both
# platforms, so the self-test actually runs on the machine the plugin is developed on.
cd "$ROOT"
OUT="build/offline"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/stubs" "$OUT/jar"

echo "==> stubs"
find tools/offline/bukkit-stubs -name '*.java' > "$OUT/stub-sources.txt"
javac -nowarn -encoding UTF-8 --release 17 -d "$OUT/stubs" "@$OUT/stub-sources.txt"

echo "==> sources"
find src/main/java -name '*.java' > "$OUT/sources.txt"
javac -Xlint:all -Werror -encoding UTF-8 --release 17 -cp "$OUT/stubs" -d "$OUT/classes" "@$OUT/sources.txt"

echo "==> resources"
cp -r src/main/resources/. "$OUT/classes/"
# Gradle expands ${version} in plugin.yml via processResources; do the same here so the offline
# jar is not subtly different from the released one.
VERSION="$(grep -oP 'VERSION = "\K[^"]+' src/main/java/network/somikyy/sntelegram/core/Build.java)"
sed -i "s/\${version}/$VERSION/g" "$OUT/classes/plugin.yml"
echo "    version $VERSION"

echo "==> jar"
cat > "$OUT/manifest.txt" <<EOF
Implementation-Title: SNTelegram
Implementation-Version: $VERSION
Implementation-Vendor: Somikyy Network
EOF
jar --create \
    --file "$OUT/jar/SNTelegram-$VERSION.jar" \
    --manifest "$OUT/manifest.txt" \
    -C "$OUT/classes" .

# Stable name for scripts that do not want to know the version number.
cp "$OUT/jar/SNTelegram-$VERSION.jar" "$OUT/jar/SNTelegram-offline.jar"

# The stubs must never reach the jar: shipping org/bukkit/Bukkit.class would shadow the server's
# own class on some loaders and break in ways nobody would connect back to this plugin.
if unzip -l "$OUT/jar/SNTelegram-$VERSION.jar" | grep -qE ' (org/bukkit|net/kyori|io/papermc|com/destroystokyo)/'; then
    echo "FATAL: заглушки попали в jar" >&2
    exit 1
fi

echo "OK: $ROOT/$OUT/jar/SNTelegram-$VERSION.jar"
