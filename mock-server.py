#!/usr/bin/env python3
"""
Simple mock HTTP server for testing the reverse proxy with HTTP caching support
Usage: python3 mock-server.py <port> <server_name>
Example: python3 mock-server.py 9090 server1
"""

import sys
import json
import hashlib
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime, timezone
from email.utils import formatdate, parsedate_to_datetime

# In-memory "database" with version tracking
data_store = {
    '/api/users': {'data': [{'id': 1, 'name': 'Marco'}, {'id': 2, 'name': 'Maria'}], 'version': 1},
    '/api/products': {'data': [{'id': 1, 'product': 'Laptop'}, {'id': 2, 'product': 'Phone'}], 'version': 1}
}

class MockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/health':
            # Health check endpoint (no caching)
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.send_header('Cache-Control', 'no-cache')
            self.end_headers()
            response = {'status': 'healthy', 'server': server_name}
            self.wfile.write(json.dumps(response).encode())
        
        elif self.path.startswith('/api/'):
            # API endpoints with caching support
            self.handle_cached_endpoint()
        
        else:
            # Regular endpoint with default caching
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.send_header('Cache-Control', 'max-age=60')  # Cache for 60 seconds
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

    def handle_cached_endpoint(self):
        """Handle API endpoints with ETag and Last-Modified support"""
        # Get data from store
        if self.path in data_store:
            data = data_store[self.path]['data']
            version = data_store[self.path]['version']
        else:
            data = {'message': f'Data from {server_name}', 'path': self.path}
            version = 1
        
        # Generate ETag (hash of data + version)
        content = json.dumps(data, sort_keys=True)
        etag = f'"{hashlib.md5((content + str(version)).encode()).hexdigest()}"'
        
        # Last-Modified (fixed for testing)
        last_modified = formatdate(timeval=None, localtime=False, usegmt=True)
        
        # Check If-None-Match (ETag validation)
        if_none_match = self.headers.get('If-None-Match')
        if if_none_match == etag:
            # ETag matches - send 304
            self.send_response(304)
            self.send_header('ETag', etag)
            self.send_header('Cache-Control', 'max-age=120, must-revalidate')
            self.send_header('Last-Modified', last_modified)
            self.end_headers()
            self.log_message(f"[{server_name}] 304 Not Modified (ETag match)")
            return
        
        # Check If-Modified-Since (Last-Modified validation)
        if_modified_since = self.headers.get('If-Modified-Since')
        if if_modified_since:
            try:
                # For simplicity, always return 304 if header is present and ETag didn't match
                # (in real scenario, would compare timestamps)
                pass
            except:
                pass
        
        # Send full response with caching headers
        response_body = json.dumps({
            'server': server_name,
            'data': data,
            'timestamp': datetime.now().isoformat()
        }, indent=2).encode()
        
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.send_header('Content-Length', str(len(response_body)))
        self.send_header('ETag', etag)
        self.send_header('Last-Modified', last_modified)
        self.send_header('Cache-Control', 'max-age=30, must-revalidate')  # Cache 30s, then revalidate
        self.send_header('Vary', 'Accept-Encoding')
        self.end_headers()
        self.wfile.write(response_body)

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
