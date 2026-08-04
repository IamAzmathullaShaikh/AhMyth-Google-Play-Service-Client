#!/usr/bin/env bash
# Restart the mock C2 server (detached), keeping a FIFO writer alive so orders
# can be piped in later via:  echo 'apps' > /tmp/c2in
set -u
FIFO=/tmp/c2in
PORT=${1:-42474}
DIR="$(cd "$(dirname "$0")" && pwd)"

pkill -f "[m]ock_c2_test_client" 2>/dev/null || true
sleep 0.3
rm -f "$FIFO"
mkfifo "$FIFO"
setsid bash -c "exec 9<>$FIFO; sleep 7200" >/dev/null 2>&1 &
setsid python3 "$DIR/mock_c2_test_client.py" --port "$PORT" < "$FIFO" > /tmp/mock_c2.log 2>&1 &
sleep 1.2
echo "server pid: $(pgrep -f '[m]ock_c2_test_client' | head -1)"
cat /tmp/mock_c2.log
