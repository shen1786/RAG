function renderKnowledgePage(container) {
    container.innerHTML = `
    <div class="page-container">
      <h1 class="page-title">知识库管理</h1>
      <p class="page-subtitle">管理基于 RAG 上传的文档与文件。所有的内容会被向量化并供智能回答使用。</p>
      
      <div class="card">
        <div class="knowledge-toolbar">
          <input type="text" id="searchInput" placeholder="搜索文档名..." class="search-input" onkeyup="handleSearch(event)" />
          <select id="typeFilter" class="filter-select" onchange="loadDocuments()">
            <option value="">所有类型</option>
            <option value="TEXT">文本</option>
            <option value="IMAGE">图片</option>
            <option value="VIDEO">视频</option>
          </select>
          <button class="btn" onclick="loadDocuments()">
             <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><path d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
             检索
          </button>
        </div>
        
        <div class="table-container">
          <table class="data-table">
            <thead>
              <tr>
                <th>文件名</th>
                <th>类型</th>
                <th>大小</th>
                <th>上传时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody id="docTableBody">
              <!-- JS rendered rows -->
            </tbody>
          </table>
        </div>
        
        <div class="pagination" id="pagination"></div>
      </div>
    </div>
  `;

    const style = document.createElement('style');
    style.id = 'knowledge-styles';
    style.textContent = `
    .knowledge-toolbar { display: flex; gap: 16px; margin-bottom: 24px; }
    .search-input { flex: 1; padding: 10px 16px; background-color: var(--bg-dark); border: 1px solid var(--border-color); color: var(--text-main); border-radius: 8px; }
    .filter-select { padding: 10px 16px; background-color: var(--bg-dark); border: 1px solid var(--border-color); color: var(--text-main); border-radius: 8px; }
    
    .table-container { overflow-x: auto; border: 1px solid var(--border-color); border-radius: 8px; }
    .data-table { width: 100%; border-collapse: collapse; text-align: left; }
    .data-table th { background-color: rgba(0,0,0,0.2); padding: 16px; font-weight: 600; color: var(--text-muted); border-bottom: 1px solid var(--border-color); }
    .data-table td { padding: 16px; border-bottom: 1px solid var(--border-color); transition: background 0.2s; }
    .data-table tbody tr:hover td { background-color: var(--bg-card-hover); }
    .data-table tbody tr:last-child td { border-bottom: none; }
    
    .doc-name { display: flex; align-items: center; gap: 12px; }
    .doc-icon { width: 32px; height: 32px; border-radius: 6px; background-color: rgba(34, 197, 94, 0.1); color: var(--primary-color); display: flex; align-items: center; justify-content: center; }
    
    .btn-danger { color: #ef4444; background: rgba(239, 68, 68, 0.1); padding: 6px 12px; border-radius: 6px; transition: all 0.2s; border: none; cursor: pointer; }
    .btn-danger:hover { background: #ef4444; color: white; }
    
    .pagination { display: flex; justify-content: flex-end; gap: 8px; margin-top: 24px; }
    .page-btn { padding: 6px 12px; background: var(--bg-dark); border: 1px solid var(--border-color); color: var(--text-muted); border-radius: 6px; cursor: pointer; }
    .page-btn:hover:not(:disabled) { border-color: var(--primary-color); color: var(--text-main); }
    .page-btn.active { background: var(--primary-color); color: white; border-color: var(--primary-color); }
    .page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
  `;
    if (!document.getElementById('knowledge-styles')) document.head.appendChild(style);

    // Initial load
    loadDocuments(1);
}

let currentPage = 1;

function handleSearch(e) {
    if (e.key === 'Enter') {
        loadDocuments(1);
    }
}

async function loadDocuments(page = 1) {
    currentPage = page;
    const keyword = document.getElementById('searchInput').value;
    const type = document.getElementById('typeFilter').value;

    const tbody = document.getElementById('docTableBody');
    tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding: 40px;">加载中...</td></tr>`;

    try {
        const query = new URLSearchParams({
            page: page,
            pageSize: 10,
            userId: getUserId()
        });
        if (keyword) query.append('keyword', keyword);
        if (type) query.append('sourceType', type);

        const result = await fetchApi(`/api/documents?${query.toString()}`);
        listDocumentsInTable({
            items: result.records || [],
            total: result.total || 0,
            page: result.page || page,
            totalPages: result.totalPages || 0
        });
    } catch (error) {
        tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding: 40px; color: #ef4444;">加载失败: ${error.message}</td></tr>`;
    }
}

function listDocumentsInTable(result) {
    const tbody = document.getElementById('docTableBody');
    tbody.innerHTML = '';

    if (!result.items || result.items.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding: 40px; color: var(--text-muted);">暂无文档记录</td></tr>`;
        document.getElementById('pagination').innerHTML = '';
        return;
    }

    result.items.forEach(doc => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
      <td>
        <div class="doc-name">
          <div class="doc-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </div>
          <span>${doc.filename}</span>
        </div>
      </td>
      <td>${doc.sourceType || 'UNKNOWN'}</td>
      <td>${formatBytes(doc.fileSize)}</td>
      <td>${formatDate(doc.createdAt)}</td>
      <td>
        <button class="btn-danger" onclick="deleteDocument('${doc.fileHash}')">删除</button>
      </td>
    `;
        tbody.appendChild(tr);
    });

    renderPagination(result);
}

function renderPagination(result) {
    const container = document.getElementById('pagination');
    let html = `<button class="page-btn" ${result.page <= 1 ? 'disabled' : ''} onclick="loadDocuments(${result.page - 1})">上一页</button>`;

    for (let i = 1; i <= result.totalPages; i++) {
        html += `<button class="page-btn ${result.page === i ? 'active' : ''}" onclick="loadDocuments(${i})">${i}</button>`;
    }

    html += `<button class="page-btn" ${result.page >= result.totalPages ? 'disabled' : ''} onclick="loadDocuments(${result.page + 1})">下一页</button>`;
    container.innerHTML = html;
}

async function deleteDocument(hash) {
    if (!confirm('确定要删除该文档及其全部关联知识及向量索引吗？此操作无法撤销。')) return;

    try {
        const res = await fetchApi(`/api/documents/${hash}?userId=${encodeURIComponent(getUserId())}`, {
            method: 'DELETE'
        });
        showToast('删除任务已提交: ' + res.taskId);
        loadDocuments(currentPage);
    } catch (error) {
        //
    }
}
