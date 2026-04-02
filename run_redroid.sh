#!/usr/bin/env bash
set -euo pipefail

IMAGE="redroid/redroid:11.0.0-latest"
NAME="redroid"

echo "[1/4] Docker 拉取镜像: ${IMAGE}"
docker pull "${IMAGE}"

echo "[2/4] 清理旧容器(如有): ${NAME}"
docker rm -f "${NAME}" >/dev/null 2>&1 || true

echo "[3/4] 启动 Redroid 容器(暴露 5555)"
# 注意：Redroid 通常需要宿主机具备 binder/binderfs 支持，并允许 privileged 容器。
docker run -d \
  --name "${NAME}" \
  --privileged \
  -p 5555:5555 \
  "${IMAGE}"

echo "[4/4] adb connect 连接到容器"
adb connect 127.0.0.1:5555
adb devices

