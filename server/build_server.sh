#!/usr/bin/env bash
# 服务端编译入口：一条命令编译，检查依赖，输出可执行文件位置。
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v g++ >/dev/null 2>&1; then
  echo "ERROR: g++ not found in PATH" >&2
  exit 1
fi

echo "Building forest_racer_server with g++ ($(g++ --version | head -1))"
make clean >/dev/null
make all

echo "OK: $(pwd)/forest_racer_server"
echo "Run: ./forest_racer_server --port 7777"
