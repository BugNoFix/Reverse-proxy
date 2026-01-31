#!/bin/bash
# Stop all mock servers

echo "🛑 Stopping mock servers..."

# Find and kill processes on port 9090
if lsof -Pi :9090 -sTCP:LISTEN -t >/dev/null 2>&1 ; then
    PID_9090=$(lsof -Pi :9090 -sTCP:LISTEN -t)
    kill $PID_9090
    echo "✅ Stopped server on port 9090 (PID: $PID_9090)"
else
    echo "ℹ️  No server running on port 9090"
fi

# Find and kill processes on port 9091
if lsof -Pi :9091 -sTCP:LISTEN -t >/dev/null 2>&1 ; then
    PID_9091=$(lsof -Pi :9091 -sTCP:LISTEN -t)
    kill $PID_9091
    echo "✅ Stopped server on port 9091 (PID: $PID_9091)"
else
    echo "ℹ️  No server running on port 9091"
fi

echo ""
echo "✅ All mock servers stopped!"
