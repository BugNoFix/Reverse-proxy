#!/usr/bin/env python3
"""
Simple mock HTTP server for testing the reverse proxy
Usage: python3 mock-server.py <port> <server_name>
Example: python3 mock-server.py 9090 server1
"""

import sys
import json
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime

class MockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/health':
            # Health check endpoint
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            response = {'status': 'healthy', 'server': server_name}
            self.wfile.write(json.dumps(response).encode())
        else:
            # Regular endpoint
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            response = {
                'message': f'Hello from {server_name}',
                'path': self.path,
                'method': 'GET',
                'timestamp': datetime.now().isoformat(),
                'headers': dict(self.headers)
            }
            self.wfile.write(json.dumps(response, indent=2).encode())
        self.log_message(f"[{server_name}] {self.command} {self.path}")

    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length).decode('utf-8') if content_length > 0 else ''
        
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        
        response = {
            'message': f'POST received by {server_name}',
            'path': self.path,
            'method': 'POST',
            'timestamp': datetime.now().isoformat(),
            'body': body,
            'headers': dict(self.headers)
        }
        self.wfile.write(json.dumps(response, indent=2).encode())
        self.log_message(f"[{server_name}] {self.command} {self.path}")

    def do_PUT(self):
        self.do_POST()

    def do_DELETE(self):
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        response = {
            'message': f'DELETE received by {server_name}',
            'path': self.path,
            'timestamp': datetime.now().isoformat()
        }
        self.wfile.write(json.dumps(response, indent=2).encode())
        self.log_message(f"[{server_name}] {self.command} {self.path}")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: python3 mock-server.py <port> <server_name>")
        print("Example: python3 mock-server.py 9090 server1")
        sys.exit(1)
    
    port = int(sys.argv[1])
    server_name = sys.argv[2]
    
    server = HTTPServer(('0.0.0.0', port), MockHandler)
    print(f"🚀 Mock server '{server_name}' listening on port {port}")
    print(f"   Health check: http://localhost:{port}/health")
    print(f"   Press Ctrl+C to stop")
    
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print(f"\n👋 Stopping {server_name}")
        server.shutdown()
