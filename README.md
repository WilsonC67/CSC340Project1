# QU Micro-services Cluster — CSC340 Project 1
**Team Name:** Aunt Man

**Team Members:** Aurelien Buisine, Reed Scampoli, Wilson Chen, Jacob Batson

---

## Overview

A distributed microservices cluster where a central server routes client task requests to a dynamic pool of worker Service Nodes (SNs). The browser frontend communicates via HTTP to an HTTP Gateway, which bridges to the internal custom TCP/UDP protocol.

```
Browser ──HTTP──▶ HTTPGateway ──TCP──▶ DoormanListener ──TCP──▶ Service Node
                                              ▲
                                Service Nodes (UDP heartbeats)
```

### Services

| # | Service | Node Class |
|---|---------|-----------|
| 1 | N-Body Gravitational Stepper | `Services.NBody.NBodyNode` |
| 2 | Base64 Encode / Decode | `Services.Base64.Base64Node` |
| 3 | Compression / Decompression | `Services.Compression.CompressionNode` |
| 4 | CSV Stats | `Services.CSVStats.CSVStatsNode` |
| 5 | Image to ASCII | `Services.ImageToAscii.ImageToAsciiNode` |

### Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 5050 | HTTP | HTTPGateway — browser-facing |
| 5101 | TCP | DoormanListener — client connections |
| 5102 | TCP | Service Node listener (all SNs, each on own host) |
| 6001 | UDP | HeartbeatMonitor — receives SN heartbeats |

---

## Running Locally

### Prerequisites
- Java 17+
- All commands run from the project root directory

### Step 1 — Compile

```bash
mkdir -p out

# Core server
javac -d out Source/*.java

# Service nodes
javac -cp out -d out Services/Base64/*.java
javac -cp out -d out Services/NBody/*.java
javac -cp out -d out Services/Compression/*.java
javac -cp out -d out Services/ImageToAscii/*.java
javac -cp out -d out Services/CSVStats/*.java
```

### Step 2 — Start the Server (Terminal 1)

```bash
java -cp out -Dfrontend.dir=Frontend Source.Server
```

The frontend is now accessible at **http://localhost:5050**

### Step 3 — Start Service Nodes (one terminal each)

On AWS each node runs on its own host and all use the default port 5102.
When running locally, pass a unique `-Dservice.port` to each so they don't conflict:

```bash
# Terminal 2
java -cp out Services.NBody.NBodyNode

# Terminal 3
java -cp out -Dservice.port=5103 Services.Base64.Base64Node

# Terminal 4
java -cp out -Dservice.port=5104 Services.Compression.CompressionNode

# Terminal 5
java -cp out -Dservice.port=5105 Services.CSVStats.CSVStatsNode

# Terminal 6
java -cp out -Dservice.port=5106 Services.ImageToAscii.ImageToAsciiNode
```

Each node sends a UDP heartbeat to the server every 5 seconds. Once registered, it appears as **Online** on the home page within 10 seconds.

> **Note:** If running nodes on a different machine than the server, pass the server IP:
> ```bash
> java -cp out -Dserver.host=<SERVER_IP> Services.Base64.Base64Node
> ```

### Step 4 — Use the Frontend

Open **http://localhost:5050** in a browser. Service cards show **Online** (green) or **Offline** (red) based on live heartbeat status, updated every 10 seconds.

> **Note:** All frontend API calls use relative URLs (`/api/service`, etc.), so the same build works both locally and on AWS without any changes.

---

## Protocol

### Client → Server (TCP :5101)

1. Client connects to DoormanListener
2. Server immediately sends the available service list:
   ```
   AVAILABLE_SERVICES:BASE64_ENCODE_DECODE,CSV_STATS,...\n
   ```
3. Client sends JSON payload and signals EOF (closes write end):
   ```json
   { "service": 4, "filename": "data.csv", "base64": "<base64-encoded file>" }
   ```
   For Base64 Encode/Decode (service 2), the payload uses `data` and `operation` instead:
   ```json
   { "service": 2, "operation": "encode", "filename": "file.txt", "data": "<base64-encoded file>" }
   ```
4. Server routes to the appropriate live SN, forwards payload
5. SN processes and responds — server streams result back to client
6. Connection closes

> A client may also connect, read the service list, and close without sending a payload (status-only query — used by the home page).

### Service Node → Server (UDP :6001)

Each SN sends a heartbeat packet every 5 seconds:
```
<nodeId>,<tcpPort>,<serviceName>
```
Example: `2,5102,BASE64_ENCODE_DECODE`

The server marks a node **dead** if no heartbeat is received for **60 seconds**. Dead nodes that resume heartbeats are automatically re-added as available (recovery).

### Server → Service Node (TCP :5102)

The Pipe thread connects to the SN, forwards the full JSON payload, signals EOF, then reads all response bytes and streams them back to the original client.

---

## Service Reference

### Service 1 — N-Body Gravitational Stepper

Simulates gravitational interaction between N bodies over a given number of time steps using Euler integration.

**Node:** `NBodyNode` (Pattern A) — `NODE_ID = 1`

**Request payload:**

```json
{
  "service": 1,
  "dt":      0.01,
  "steps":   100,
  "bodies": [
    { "mass": 1e30, "x": 0, "y": 0, "z": 0, "vx": 0, "vy": 0, "vz": 0 },
    { "mass": 5e24, "x": 1.5e11, "y": 0, "z": 0, "vx": 0, "vy": 29783, "vz": 0 }
  ]
}
```

**Response:**

```json
{ "status": "ok", "steps_completed": 100, "dt": 0.01, "bodies": [ … ] }
```

---

### Service 2 — Base64 Encode / Decode

Encodes any file to a Base64 text file (`.b64`) or decodes a `.b64` file back to its original form.

**Node:** `Base64Node` (Pattern A) — `NODE_ID = 2`

**Request payload:**

```json
{ "service": 2, "operation": "encode", "filename": "photo.png", "data": "<base64-encoded file>" }
{ "service": 2, "operation": "decode", "filename": "photo.png.b64", "data": "<base64-encoded file>" }
```

**Response:**

```json
{ "status": "ok", "result": "<base64 string>" }
```
> The `data` field is always base64 (the browser's `readAsDataURL` encoding). For decode, the node unwraps two layers: the browser wrapper and the `.b64` file content.

---

### Service 3 — Compression / Decompression

Compresses any file using Java's Deflate algorithm (ZIP format), or decompresses a previously compressed file.

**Node:** `CompressionNode` (Pattern A) — `NODE_ID = 3`

> **File size limit:** Maximum 30 MB. Larger files will be rejected by the frontend before upload.

**Request payload:**

```json
{ "service": 3, "operation": "compress",   "filename": "notes.txt",     "data": "<base64-encoded file>" }
{ "service": 3, "operation": "decompress", "filename": "notes.txt.zip", "data": "<base64-encoded file>" }
```

**Response:**

```json
{ "status": "ok", "result": "<base64-encoded output bytes>", "filename": "notes.txt.zip" }
```

> Result is base64-encoded binary — the frontend must decode it with `base64ToUint8Array` before creating a Blob.

---

### Service 4 — CSV Stats

Computes per-column descriptive statistics (mean, median, mode, standard deviation, min, max) for all numeric columns in a CSV file.

**Node:** `CSVStatsNode` (Pattern A) — `NODE_ID = 4`

**Request payload:**

```json
{ "service": 4, "filename": "data.csv", "base64": "<base64-encoded CSV file>" }
```

**Response:**

```json
{ "status": "ok", "result": "<CSV text of statistics>", "filename": "CSVStats.csv" }
```

---

### Service 5 — Image to ASCII

Converts a PNG or JPEG image to an ASCII art text file by grayscaling, resizing, and mapping pixel brightness to ASCII characters.

**Node:** `ImageToAsciiNode` (Pattern A) — `NODE_ID = 5`

**Request payload:**

```json
{ "service": 5, "filename": "photo.png", "base64": "<base64-encoded image>" }
```

**Response:**

```json
{ "status": "ok", "result": "<ASCII art text>", "filename": "ascii.txt" }
```

---

## Architecture

### Server Components

| Class | Role |
|-------|------|
| `Server` | Main entry point — starts all server threads |
| `DoormanListener` | Accepts TCP client connections on :5101, spawns a `Pipe` per client |
| `Pipe` | Client thread — sends service list, routes request to SN, returns result |
| `HeartbeatMonitor` | UDP listener on :6001 — receives heartbeats, sweeps dead nodes every 10s |
| `NodeRegistry` | Thread-safe map of all known nodes and their alive status |
| `HTTPGateway` | HTTP server on :5050 — serves frontend files and bridges HTTP to TCP |

### Service Node Base (`Node.java`)

All SNs extend `Node`, which provides:
- **HeartbeatPulse** — daemon thread sending UDP heartbeats to the server
- **ServiceListener** — TCP server on :5102 accepting connections from `Pipe`
- **Worker pool** — handles multiple concurrent requests per node

Subclasses implement one of two patterns:
- **Pattern A** — override `handleRequest(byte[] payload)` — pure Java, in-process
- **Pattern B** — override `getExecutorCommand()` — spawns a subprocess (Python, C, etc.)

---

## AWS Deployment

The live server is at **http://54.225.145.40:5050**

Each component runs as a systemd service on its own EC2 instance.

### Build & Deploy

A build script on the server watches for changes on `master`, compiles all sources into a JAR, distributes it to every node via `scp`, and restarts all systemd services:

```bash
# Compile all sources into /home/ec2-user/Artifact/
javac -d /home/ec2-user/Artifact Source/*.java
javac -d /home/ec2-user/Artifact Services/NBody/*.java
javac -d /home/ec2-user/Artifact Services/Base64/*.java
javac -d /home/ec2-user/Artifact Services/Compression/*.java
javac -d /home/ec2-user/Artifact Services/CSVStats/*.java
javac -d /home/ec2-user/Artifact Services/ImageToAscii/*.java

# Package into a single JAR
jar cvf MicroService.jar \
    Source/*.class \
    Services/NBody/*.class \
    Services/Base64/*.class \
    Services/Compression/*.class \
    Services/CSVStats/*.class \
    Services/ImageToAscii/*.class

# Distribute to all nodes and restart
scp MicroService.jar ec2-user@<node-ip>:/home/ec2-user
ssh ec2-user@<node-ip> "sudo systemctl restart microservice"
```

### Runtime flags

```bash
# Server
java -cp /home/ec2-user/MicroService.jar -Dfrontend.dir=/home/ec2-user/Frontend Source.Server

# Service node pointing to server's private IP
java -cp /home/ec2-user/MicroService.jar -Dserver.host=10.0.x.x Services.Base64.Base64Node
```
