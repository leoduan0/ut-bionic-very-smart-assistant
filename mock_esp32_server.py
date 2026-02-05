#!/usr/bin/env python3
"""
Mock ESP32 Server for Testing Android App
Run this on your computer to simulate ESP32 responses
"""

import socket
import json
import threading
from datetime import datetime

HOST = '0.0.0.0'  # Listen on all network interfaces
PORT = 4211

def log(message):
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] {message}")

def handle_client(client_socket, address):
    log(f"New connection from {address}")
    
    try:
        # Receive data
        data = client_socket.recv(1024).decode('utf-8').strip()
        log(f"Received: {data}")
        
        # Try to parse as JSON
        try:
            command = json.loads(data)
            cmd = command.get('cmd')
            target = command.get('target')
            duration = command.get('duration', 5000)
            
            log(f"Command: {cmd}, Target: {target}, Duration: {duration}ms")
            
            # Simulate button press
            if cmd == "PUSH_BUTTON":
                log(f"Simulating {target} door button press for {duration}ms...")
                
                # Send success response
                response = {
                    "success": True,
                    "message": f"{target.capitalize()} door opened successfully",
                    "target": target
                }
                
                response_json = json.dumps(response) + "\n"
                client_socket.send(response_json.encode('utf-8'))
                log(f"Sent response: {response}")
            else:
                # Unknown command
                response = {
                    "success": False,
                    "message": "Unknown command",
                    "error": f"Command '{cmd}' not recognized"
                }
                response_json = json.dumps(response) + "\n"
                client_socket.send(response_json.encode('utf-8'))
                log(f"Sent error response: {response}")
                
        except json.JSONDecodeError:
            # Handle legacy commands or heartbeat
            if data.startswith("ARE_YOU_ALIVE"):
                log("Heartbeat received - responding with ACK")
                client_socket.send(b"ACK\n")
            elif data.startswith("COMMAND:"):
                log("Legacy command format detected")
                response = {"success": True, "message": "OK"}
                client_socket.send(json.dumps(response).encode('utf-8'))
            else:
                log(f"Invalid JSON: {data}")
                
    except Exception as e:
        log(f"Error handling client: {e}")
    finally:
        client_socket.close()
        log(f"Connection closed for {address}")

def main():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind((HOST, PORT))
    server_socket.listen(5)
    
    # Get local IP
    hostname = socket.gethostname()
    local_ip = socket.gethostbyname(hostname)
    
    print("=" * 60)
    print("Mock ESP32 Server Started")
    print("=" * 60)
    log(f"Listening on {HOST}:{PORT}")
    log(f"Your computer's IP: {local_ip}")
    print("\nIn your Android app, set the Controller Address to:")
    print(f"  For Android Emulator: 10.0.2.2")
    print(f"  For Real Phone:       {local_ip}")
    print("\nWaiting for connections...\n")
    
    try:
        while True:
            client_socket, address = server_socket.accept()
            # Handle each client in a separate thread
            client_thread = threading.Thread(target=handle_client, args=(client_socket, address))
            client_thread.start()
    except KeyboardInterrupt:
        log("\nShutting down server...")
    finally:
        server_socket.close()
        log("Server stopped")

if __name__ == "__main__":
    main()
