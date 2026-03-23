const serviceMap = {
    "Gravitational.html": 1,
    "Base64.html": 2,
    "CompDecomp.html": 3,
    "CSVStats.html": 4,
    "Image-ASCII.html": 5,
};

const currentPage = window.location.pathname.split("/").pop();
const serviceNum = serviceMap[currentPage] ?? -1;
const GATEWAY = "";

window.onload = function() {
    console.log("establish connection to server");
    establishConnection();
    initDragAndDrop();

    if (serviceNum === 5) {
        document.getElementById("Upload_File").setAttribute("accept", "image/png, image/jpeg");
    }

    document.getElementById("uploadForm").addEventListener("submit", function(e) {
        e.preventDefault();
        uploadFile();
    });

    document.getElementById("Upload_File").addEventListener('change', function() {
        const label = document.querySelector('label[for="Upload_File"]');
        if (this.files[0]) updateLabelText(label, this.files[0].name);

        if (this.files[0]) {
            const file = this.files[0];
            if (serviceNum === 5 && !["image/png", "image/jpeg"].includes(file.type)) {
                alert("Please upload a PNG or JPG file");
                this.value = "";
                updateLabelText(label, null);
                return;
            }
            updateLabelText(label, file.name);
            showPreview(file);
        }
    });
}

function establishConnection() {
    fetch(GATEWAY + '/ping')
        .then(response => response.json())
        .then(data => { console.log("Connection successful:", data); })
        .catch(error => { console.error("Connection error:", error); });
}

function initDragAndDrop() {
    const label = document.querySelector('label[for="Upload_File"]');
    if (!label) return;

    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(evt => {
        label.addEventListener(evt, e => { e.preventDefault(); e.stopPropagation(); });
    });
    ['dragenter', 'dragover'].forEach(evt => {
        label.addEventListener(evt, () => label.classList.add('drag-over'));
    });
    ['dragleave', 'drop'].forEach(evt => {
        label.addEventListener(evt, () => label.classList.remove('drag-over'));
    });

    label.addEventListener('drop', function(e) {
        const file = e.dataTransfer.files[0];
        if (!file) return;

        const dt = new DataTransfer();
        dt.items.add(file);
        document.getElementById("Upload_File").files = dt.files;

        updateLabelText(label, file.name);
        showPreview(file);
    });
}

function updateLabelText(label, filename) {
    label.innerHTML = filename
        ? `<b>${filename}</b>`
        : 'Click or Drag <br> to Upload File';
}

function uploadFile() {
    if (serviceNum === -1) {
        alert("no service selected");
        return;
    }

    const fileInput = document.getElementById("Upload_File");
    const file = fileInput.files[0];
    if (!file) {
        alert("Please select a file");
        return;
    }

    if(serviceNum == 4 && !["text/csv"].includes(file.type)) {
        alert("Please uplaod a csv file");
        return;
    }

    if (serviceNum === 5 && !["image/png", "image/jpeg"].includes(file.type)) {
        alert("Please upload a PNG or JPG file");
        return;
    }

    const reader = new FileReader();
    reader.onload = function(event) {
        const base64Data = event.target.result.split(',')[1];
        let data;
        if (serviceNum === 2) {
            const op = document.querySelector('input[name="b64op"]:checked')?.value || 'encode';
            data = { service: 2, operation: op, filename: file.name, data: base64Data };
        } else {
            data = { service: serviceNum, filename: file.name, base64: base64Data };
        }
        sendToServer(data);
    };
    reader.readAsDataURL(file);
}

function setStatus(text) {
    const el = document.getElementById('status');
    if (el) el.textContent = text;
}

function setResult(html) {
    const el = document.getElementById('result');
    if (el) el.innerHTML = html;
}

function sendToServer(data) {

    setStatus('Uploading…');
    setResult('');
    
    fetch(GATEWAY + "/api/service", {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    })
    .then(response => {
        if (!response.ok) throw new Error(`Server error (${response.status})`);
        return response.json();
    })
    .then(responseData => {
        console.log('Server response:', responseData);
        handleResponse(responseData, data.filename);
    })
    .catch(error => {
        console.error('Error sending file:', error);
        if (error instanceof TypeError) {
            setStatus('Network error: could not reach the server. Make sure the server is running.');
        } else {
            setStatus('Error: ' + error.message);
        }
    });
}

function handleResponse(responseData, originalFilename) {
    if (responseData.status !== 'ok') {
        setStatus('Error: ' + (responseData.message || 'Unknown error from server.'));
        return;
    }

    let outName, mimeType, statusMsg;

    if (serviceNum === 2) {
        const op = document.querySelector('input[name="b64op"]:checked')?.value || 'encode';
        if (op === 'decode') {
            outName   = originalFilename.replace(/\.b64$/i, '') || originalFilename + '.decoded';
            mimeType  = 'application/octet-stream';
            statusMsg = 'Done! Decoded file ready.';
        } else {
            outName   = originalFilename + '.b64';
            mimeType  = 'text/plain';
            statusMsg = 'Done! Encoded file ready.';
        }
    } else if (serviceNum === 4) {
        outName    = responseData.filename || originalFilename.replace(/\.csv$/i, '_stats.csv');
        mimeType   = 'text/csv';
        statusMsg  = 'Done! Stats file ready.';
    } else if (serviceNum === 5) {
        outName    = responseData.filename || originalFilename.replace(/\.(png|jpe?g)$/i, '_ascii.txt');
        mimeType   = 'text/plain';
        statusMsg  = 'Done! ASCII file ready.';
    } else {
        return;
    }

    // For Base64 decode, the result is base64-encoded original bytes — decode back to binary.
    const blobData = (serviceNum === 2 && document.querySelector('input[name="b64op"]:checked')?.value === 'decode')
        ? base64ToUint8Array(responseData.result)
        : (serviceNum === 4)
            ? base64ToUint8Array(responseData.result)
            : responseData.result;
    const blob = new Blob([blobData], { type: mimeType });
    const url  = URL.createObjectURL(blob);

    const a = document.createElement('a');
    a.href = url; a.download = outName; a.click();

    setStatus(statusMsg);
    setResult(
        `<a href="${url}" download="${outName}" ` +
        `style="color:#9966cc;font-family:'Courier New',monospace;font-size:13px;">` +
        `Download ${outName}</a>`
    );
}

function base64ToUint8Array(b64) {
    const binary = atob(b64);
    const bytes  = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
}
    
function showPreview(file) {
    const preview = document.getElementById("Image_Preview");
    if (!preview) return;
    const reader = new FileReader();
    reader.onload = e => {
        preview.src = e.target.result;
        preview.style.display = "block";
    };
    reader.readAsDataURL(file);
}