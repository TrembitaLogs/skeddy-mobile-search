#!/usr/bin/env bash
# Connect to remote Android device via SSH tunnel + reverse port for local server.
# Usage: ./scripts/connect-device.sh

set -euo pipefail

REMOTE_USER="victorpasilis"
REMOTE_HOST="100.116.238.108"
ADB_PORT=5037
SERVER_PORT=8000

echo "=== Skeddy Remote Device Connection ==="

# 1. Kill existing tunnels and ADB server
echo "[1/4] Closing existing tunnels..."
pkill -f "ssh.*${REMOTE_HOST}" 2>/dev/null || true
adb kill-server 2>/dev/null || true
sleep 1

# 2. Start SSH tunnel: ADB forward + server reverse
echo "[2/4] Starting SSH tunnel (ADB: -L ${ADB_PORT}, Server: -R ${SERVER_PORT})..."
ssh -L ${ADB_PORT}:localhost:${ADB_PORT} \
    -R ${SERVER_PORT}:localhost:${SERVER_PORT} \
    ${REMOTE_USER}@${REMOTE_HOST} \
    -N -f

sleep 2

# 3. Verify device connection
echo "[3/4] Checking device..."
DEVICES=$(adb devices -l 2>&1)
echo "${DEVICES}"

if ! echo "${DEVICES}" | grep -q "device "; then
    echo "ERROR: No device found. Check USB connection on remote machine."
    exit 1
fi

# 4. Reverse port forwarding to device
echo "[4/4] Setting up adb reverse (device localhost:${SERVER_PORT} -> local server)..."
adb reverse tcp:${SERVER_PORT} tcp:${SERVER_PORT}

echo ""
echo "=== Connected ==="
echo "  Device:  $(adb devices -l | grep 'device ' | head -1)"
echo "  Server:  Android localhost:${SERVER_PORT} -> local machine localhost:${SERVER_PORT}"
echo ""
echo "Run local server on port ${SERVER_PORT}, app connects to http://localhost:${SERVER_PORT}/api/v1/"
