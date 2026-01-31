#!/bin/bash
# Start multiple mock servers for testing

echo "🚀 Starting mock servers..."

# Check if port 9090 is already in use
if lsof -Pi :9090 -sTCP:LISTEN -t >/dev/null 2>&1 ; then
    echo "⚠️  Port 9090 is already in use"
    PID_9090=$(lsof -Pi :9090 -sTCP:LISTEN -t)
    echo "   Process PID: $PID_9090"
else
    # Start server 1 on port 9090
    python3 mock-server.py 9090 server1 &
    SERVER1_PID=$!
    echo "✅ Server 1 started on port 9090 (PID: $SERVER1_PID)"
fi

# Wait a bit
sleep 1

# Check if port 9091 is already in use
if lsof -Pi :9091 -sTCP:LISTEN -t >/dev/null 2>&1 ; then
    echo "⚠️  Port 9091 is already in use"
    PID_9091=$(lsof -Pi :9091 -sTCP:LISTEN -t)
    echo "   Process PID: $PID_9091"
else
    # Start server 2 on port 9091
    python3 mock-server.py 9091 server2 &
    SERVER2_PID=$!
    echo "✅ Server 2 started on port 9091 (PID: $SERVER2_PID)"
fi

echo ""
echo "📊 Current mock servers:"
lsof -Pi :9090,:9091 -sTCP:LISTEN | grep -v COMMAND
echo ""
echo "To stop all servers: kill \$(lsof -t -i:9090,9091)"
echo "Or press Ctrl+C"

# Wait for both servers (if started)
wait
