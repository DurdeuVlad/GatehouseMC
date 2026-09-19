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
fabric_loader_version="${FABRIC_LOADER_VERSION:-0.19.5}"
fabric_api_version="${FABRIC_API_VERSION:-0.116.17+1.21.1}"
fabric_api_jar="${FABRIC_API_JAR:-}"
rcon_port="${E2E_RCON_PORT:-}"
rcon_password="${E2E_RCON_PASSWORD:-}"
driver="${E2E_DRIVER:-smoke}"

[[ -f "$artifact" ]] || { echo "Artifact does not exist: $artifact" >&2; exit 1; }
if [[ "$loader" == "fabric" && "$minecraft_version" != "1.21.1" \
  && -z "${FABRIC_API_VERSION:-}" && -z "$fabric_api_jar" ]]; then
  echo "Set FABRIC_API_VERSION or FABRIC_API_JAR for Fabric $minecraft_version" >&2
  exit 2
fi

mkdir -p "$server_dir/mods"

case "$loader" in
  fabric)
    curl --fail --silent --show-error --location \
      "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar" \
      --output "$server_dir/fabric-installer.jar"
    (
      cd "$server_dir"
      java -jar fabric-installer.jar server \
        -mcversion "$minecraft_version" -loader "$fabric_loader_version" -downloadMinecraft
    )
    if [[ -n "$fabric_api_jar" ]]; then
      [[ -f "$fabric_api_jar" ]] || { echo "Fabric API override does not exist: $fabric_api_jar" >&2; exit 1; }
      cp "$fabric_api_jar" "$server_dir/mods/fabric-api.jar"
    else
      curl --fail --silent --show-error --location \
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$fabric_api_version/fabric-api-$fabric_api_version.jar" \
        --output "$server_dir/mods/fabric-api.jar"
    fi
    launch=(java -jar fabric-server-launch.jar nogui)
    if command -v cmd.exe >/dev/null 2>&1; then
      restart_command_json='["java","-jar","fabric-server-launch.jar","nogui"]'
    else
      restart_command_json='["java","-jar","fabric-server-launch.jar","nogui"]'
    fi
    ;;
  forge)
    curl --fail --silent --show-error --location \
      "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.23/forge-1.20.1-47.4.23-installer.jar" \
      --output "$server_dir/forge-installer.jar"
    (
      cd "$server_dir"
      java -jar forge-installer.jar --installServer
    )
    if command -v cmd.exe >/dev/null 2>&1 && [[ -f "$server_dir/run.bat" ]]; then
      launch=(cmd.exe //c java @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1-47.4.23/win_args.txt nogui)
      restart_command_json='["java","@user_jvm_args.txt","@libraries/net/minecraftforge/forge/1.20.1-47.4.23/win_args.txt","nogui"]'
    else
      launch=(bash run.sh nogui)
      restart_command_json='["bash","run.sh","nogui"]'
    fi
    ;;
  neoforge)
    curl --fail --silent --show-error --location \
      "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.201/neoforge-21.1.201-installer.jar" \
      --output "$server_dir/neoforge-installer.jar"
    (
      cd "$server_dir"
      java -jar neoforge-installer.jar --installServer
    )
    if command -v cmd.exe >/dev/null 2>&1 && [[ -f "$server_dir/run.bat" ]]; then
      launch=(cmd.exe //c java @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.201/win_args.txt nogui)
      restart_command_json='["java","@user_jvm_args.txt","@libraries/net/neoforged/neoforge/21.1.201/win_args.txt","nogui"]'
    else
      launch=(bash run.sh nogui)
      restart_command_json='["bash","run.sh","nogui"]'
    fi
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
if [[ -n "$rcon_port" || -n "$rcon_password" ]]; then
  [[ -n "$rcon_port" && -n "$rcon_password" ]] || {
    echo "E2E_RCON_PORT and E2E_RCON_PASSWORD must be provided together" >&2
    exit 2
  }
  cat >> "$server_dir/server.properties" <<EOF
enable-rcon=true
rcon.port=$rcon_port
rcon.password=$rcon_password
EOF
fi

server_log="$server_dir/server.log"
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    if command -v taskkill.exe >/dev/null 2>&1; then
      taskkill.exe /PID "$server_pid" /T /F >/dev/null 2>&1 || true
    else
      kill -- "-$server_pid" 2>/dev/null || kill "$server_pid" 2>/dev/null || true
    fi
    for _ in {1..15}; do
      kill -0 "$server_pid" 2>/dev/null || return 0
      sleep 1
      done
    if command -v taskkill.exe >/dev/null 2>&1; then
      taskkill.exe /PID "$server_pid" /T /F >/dev/null 2>&1 || true
    else
      kill -KILL -- "-$server_pid" 2>/dev/null || kill -KILL "$server_pid" 2>/dev/null || true
    fi
  fi
}
trap cleanup EXIT

if command -v setsid >/dev/null 2>&1; then
  (
    cd "$server_dir"
    exec setsid "${launch[@]}"
  ) > "$server_log" 2>&1 &
else
  (
    cd "$server_dir"
    exec "${launch[@]}"
  ) > "$server_log" 2>&1 &
fi
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

if [[ "$driver" == "smoke" ]]; then
  MC_HOST=127.0.0.1 \
  MC_PORT="$port" \
  MC_VERSION="$minecraft_version" \
  MC_USERNAME="E2E_${loader}" \
  MC_EXPECTED_STATUS=rejected \
  npm --prefix "$e2e_dir" run smoke
else
  MC_HOST=127.0.0.1 \
  MC_PORT="$port" \
  MC_VERSION="$minecraft_version" \
  MC_USERNAME="E2E_Bob" \
  RCON_PORT="$rcon_port" \
  RCON_PASSWORD="$rcon_password" \
  M9_SERVER_DIR="$server_dir" \
  M9_SERVER_COMMAND_JSON="$restart_command_json" \
  M9_LOADER="$loader" \
  M9_MINECRAFT_VERSION="$minecraft_version" \
  npm --prefix "$e2e_dir" run "$driver"
fi

echo "clean ${loader} ${minecraft_version} server smoke passed"
