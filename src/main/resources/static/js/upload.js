function renderUploadPage(container) {
    container.innerHTML = `
    <div class="page-container">
      <h1 class="page-title">上传中心</h1>
      <p class="page-subtitle">将文档或文件长传至知识库解析。大文件将自动使用分片上传策略，以支持断点和秒传。</p>
      
      <div class="card upload-area" id="uploadArea">
        <div class="upload-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <polyline points="17 8 12 3 7 8" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <line x1="12" y1="3" x2="12" y2="15" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <h3>点击或拖拽文件到这里</h3>
        <p>支持将文件自动切分上传 (推荐最大 2GB)。可多选批量上传。</p>
        <input type="file" id="fileInput" multiple style="display: none;" onchange="handleFileSelect(event)" />
        <button class="btn" style="margin-top: 24px;" onclick="document.getElementById('fileInput').click()">选择文件</button>
      </div>

      <div class="upload-queue" id="uploadQueue">
        <h3 style="margin-bottom: 16px; font-weight: 600;">上传队列</h3>
        <!-- Upload items here -->
      </div>
    </div>
  `;

    const style = document.createElement('style');
    style.id = 'upload-styles';
    style.textContent = `
    .upload-area { border: 2px dashed var(--border-color); display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 60px 20px; text-align: center; transition: all 0.2s; background: rgba(30, 41, 59, 0.5); }
    .upload-area.drag-over { border-color: var(--primary-color); background: rgba(34, 197, 94, 0.05); }
    .upload-icon { color: var(--primary-color); margin-bottom: 16px; }
    .upload-area h3 { margin-bottom: 8px; font-weight: 600; }
    .upload-area p { color: var(--text-muted); font-size: 14px; }
    
    .upload-queue { margin-top: 32px; display: flex; flex-direction: column; gap: 12px; }
    .queue-item { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: 8px; padding: 16px; }
    .queue-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
    .queue-file-name { font-weight: 500; font-size: 14px; }
    .queue-status { font-size: 12px; color: var(--text-muted); }
    
    .progress-bar-bg { width: 100%; height: 6px; background: var(--bg-dark); border-radius: 3px; overflow: hidden; }
    .progress-bar-fill { height: 100%; background: var(--primary-color); width: 0%; transition: width 0.3s; }
  `;
    if (!document.getElementById('upload-styles')) document.head.appendChild(style);

    // Setup drag drop
    const dropZone = document.getElementById('uploadArea');
    dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('drag-over'); });
    dropZone.addEventListener('dragleave', () => { dropZone.classList.remove('drag-over'); });
    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('drag-over');
        if (e.dataTransfer.files.length) {
            processFiles(e.dataTransfer.files);
        }
    });
}

function handleFileSelect(e) {
    if (e.target.files.length) {
        processFiles(e.target.files);
        e.target.value = ''; // Reset
    }
}

// -----------------------------------------------------
// Upload Logic (Chunk & Standard mixed logic handler)
// -----------------------------------------------------

const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB per chunk

async function processFiles(fileList) {
    for (let i = 0; i < fileList.length; i++) {
        const file = fileList[i];
        await startUploadProcess(file);
    }
}

async function startUploadProcess(file) {
    const fileId = 'file_' + Date.now() + '_' + Math.floor(Math.random() * 1000);
    createQueueItem(fileId, file.name);

    try {
        updateQueueStatus(fileId, '计算哈希值中...', 0);
        const hash = await generateFileHash(file);

        if (file.size > CHUNK_SIZE * 2) {
            // Large file: Use chunk upload
            await handleChunkUpload(file, hash, fileId);
        } else {
            // Small file: Fast direct upload
            await handleDirectUpload(file, hash, fileId);
        }
    } catch (error) {
        updateQueueStatus(fileId, '上传失败: ' + error.message, 0);
        document.getElementById(fileId).style.borderColor = '#ef4444';
    }
}

async function handleDirectUpload(file, hash, fileId) {
    // Check first
    updateQueueStatus(fileId, '检查秒传...', 10);
    const checkReqBody = new URLSearchParams({ fileHash: hash, userId: getUserId() });

    try {
        const checkRes = await fetchApi(`/api/upload/check?${checkReqBody.toString()}`);
        if (checkRes?.exists) {
            updateQueueStatus(fileId, '秒传成功', 100);
            return;
        }
    } catch (e) { /* ignore and proceed */ }

    updateQueueStatus(fileId, '正在直传中...', 30);

    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileHash', hash);
    formData.append('userId', getUserId());

    await fetchApi('/api/upload', {
        method: 'POST',
        body: formData
    });

    updateQueueStatus(fileId, '上传完成', 100);
}

async function handleChunkUpload(file, hash, fileId) {
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

    updateQueueStatus(fileId, '校验断点及秒传...', 5);

    const query = new URLSearchParams({
        fileHash: hash,
        userId: getUserId(),
        filename: file.name,
        fileSize: file.size,
        totalChunks: totalChunks
    });

    let uploadedChunks = [];
    try {
        const checkRes = await fetchApi(`/api/upload/chunk/check?${query.toString()}`);
        if (checkRes.progress === 100 || checkRes.sourceId) {
            updateQueueStatus(fileId, '秒传成功', 100);
            return;
        }
        uploadedChunks = checkRes.uploadedChunks || [];
    } catch (e) { /* Proceed with fresh chunk if check fails initially */ }

    for (let i = 0; i < totalChunks; i++) {
        if (uploadedChunks.includes(i)) continue; // skip uploaded

        const start = i * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, file.size);
        const chunkBlob = file.slice(start, end);

        updateQueueStatus(fileId, `正在上传分片 ${i + 1}/${totalChunks}...`, 10 + Math.floor((i / totalChunks) * 80));

        const formData = new FormData();
        formData.append('fileHash', hash);
        formData.append('userId', getUserId());
        formData.append('chunkNumber', i);
        formData.append('chunk', chunkBlob, 'chunk');

        await fetchApi('/api/upload/chunk', {
            method: 'POST',
            body: formData
        });
    }

    updateQueueStatus(fileId, '合并分片中...', 95);

    const mergeFormData = new URLSearchParams({
        fileHash: hash,
        userId: getUserId(),
        filename: file.name
    });

    const mergeRes = await fetchApi('/api/upload/chunk/merge', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: mergeFormData.toString()
    });

    updateQueueStatus(fileId, '上传并解析排队中完成', 100);
}

// GUI helpers
function createQueueItem(fileId, filename) {
    const queue = document.getElementById('uploadQueue');
    const div = document.createElement('div');
    div.className = 'queue-item';
    div.id = fileId;
    div.innerHTML = `
    <div class="queue-header">
      <div class="queue-file-name">${filename}</div>
      <div class="queue-status" id="status_text_${fileId}">排队中...</div>
    </div>
    <div class="progress-bar-bg">
      <div class="progress-bar-fill" id="status_bar_${fileId}"></div>
    </div>
  `;
    queue.appendChild(div);
}

function updateQueueStatus(fileId, text, percent) {
    const stateText = document.getElementById(`status_text_${fileId}`);
    const stateBar = document.getElementById(`status_bar_${fileId}`);
    if (stateText) stateText.innerText = text;
    if (stateBar) stateBar.style.width = percent + '%';
    if (percent >= 100 && stateBar) {
        stateBar.style.backgroundColor = '#22c55e'; // success color
    }
}
