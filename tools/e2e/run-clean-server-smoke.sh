#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 5 ]]; then
  echo "usage: $0 <fabric|forge|neoforge> <server-dir> <port> <artifact> <minecraft-version>" >&2
  exit 2
fi

loader="$1"
server_dir="$2"
port="$3"
artifact="$4"
minecraft_version="$5"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
e2e_dir="$repo_root/tools/e2e"

mkdir -p "$server_dir/mods"

case "$loader" in
  fabric)
    curl --fail --silent --show-error --location \
      "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar" \
      --output "$server_dir/fabric-installer.jar"
    (
      cd "$server_dir"
      java -jar fabric-installer.jar server \
        -mcversion "$minecraft_version" -loader 0.19.5 -downloadMinecraft
    )
    curl --fail --silent --show-error --location \
      "https://maven.fabricmc.net/net/fabricmc/fabric-api/0.116.17+1.21.1/fabric-api-0.116.17+1.21.1.jar" \
      --output "$server_dir/mods/fabric-api.jar"
    launch=(java -jar fabric-server-launch.jar nogui)
    ;;
  forge)
    curl --fail --silent --show-error --location \
      "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.23/forge-1.20.1-47.4.23-installer.jar" \
      --output "$server_dir/forge-installer.jar"
    (
      cd "$server_dir"
      java -jar forge-installer.jar --installServer
    )
    launch=(bash run.sh nogui)
    ;;
  neoforge)
    curl --fail --silent --show-error --location \
      "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.201/neoforge-21.1.201-installer.jar" \
      --output "$server_dir/neoforge-installer.jar"
    (
      cd "$server_dir"
      java -jar neoforge-installer.jar --installServer
    )
    launch=(bash run.sh nogui)
    ;;
  *)
    echo "unsupported loader: $loader" >&2
    exit 2
    ;;
esac

cp "$artifact" "$server_dir/mods/"
printf 'eula=true\n' > "$server_dir/eula.txt"
cat > "$server_dir/server.properties" <<EOF
online-mode=false
white-list=true
enforce-secure-profile=false
server-port=$port
EOF

server_log="$server_dir/server.log"
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill -- "-$server_pid" 2>/dev/null || kill "$server_pid" 2>/dev/null || true
    for _ in {1..15}; do
      kill -0 "$server_pid" 2>/dev/null || return 0
      sleep 1
    done
    kill -KILL -- "-$server_pid" 2>/dev/null || kill -KILL "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

(
  cd "$server_dir"
  exec setsid "${launch[@]}"
) > "$server_log" 2>&1 &
server_pid=$!

for _ in {1..90}; do
  if grep -qE 'Done \([0-9.]+s\)!' "$server_log"; then
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    cat "$server_log"
    exit 1
  fi
  sleep 2
done

if ! grep -qE 'Done \([0-9.]+s\)!' "$server_log"; then
  cat "$server_log"
  exit 1
fi

MC_HOST=127.0.0.1 \
MC_PORT="$port" \
MC_VERSION="$minecraft_version" \
MC_USERNAME="E2E_${loader}" \
MC_EXPECTED_STATUS=rejected \
npm --prefix "$e2e_dir" run smoke

echo "clean ${loader} ${minecraft_version} server smoke passed"
