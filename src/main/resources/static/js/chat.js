let currentSessionId = null;
let currentTurnCount = 0;

function renderChatPage(container) {
  container.innerHTML = `
    <div class="chat-layout">
      <!-- Session Sidebar -->
      <div class="session-sidebar">
        <div class="session-header">
          <h3>会话历史</h3>
          <button class="btn btn-sm" onclick="createNewSession()">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
            新对话
          </button>
        </div>
        <div class="session-list" id="sessionList">
          <!-- Session items loaded here -->
        </div>
      </div>
      
      <!-- Main Chat Area -->
      <div class="chat-main">
        <div class="chat-messages" id="chatMessages">
          <div class="welcome-msg">
            <h2>欢迎使用 RAG 智能问答系统</h2>
            <p>请在下方输入框开始提问，系统将结合您的知识库和长期记忆进行回复。</p>
          </div>
        </div>
        
        <div class="chat-input-area">
          <textarea id="chatInput" placeholder="请输入您的问题 (Shift + Enter 换行, Enter 发送)..." rows="1"></textarea>
          <button class="btn send-btn" id="sendBtn" onclick="sendMessage()">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>
      </div>
    </div>
  `;

  // Add styles
  const style = document.createElement('style');
  style.id = 'chat-styles';
  style.textContent = `
    .chat-layout { display: flex; height: 100vh; background-color: var(--bg-dark); }
    .session-sidebar { width: 280px; border-right: 1px solid var(--border-color); display: flex; flex-direction: column; background-color: var(--bg-card); }
    .session-header { padding: 16px; border-bottom: 1px solid var(--border-color); display: flex; justify-content: space-between; align-items: center; }
    .session-header h3 { font-size: 16px; font-weight: 600; }
    .btn-sm { padding: 6px 12px; font-size: 14px; }
    .session-list { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
    
    .session-item { padding: 12px; border-radius: 8px; cursor: pointer; display: flex; justify-content: space-between; align-items: center; transition: background 0.2s; border: 1px solid transparent; }
    .session-item:hover { background-color: var(--bg-card-hover); }
    .session-item.active { background-color: rgba(34, 197, 94, 0.1); border-color: rgba(34, 197, 94, 0.3); }
    .session-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; }
    .delete-session { color: var(--text-muted); opacity: 0; transition: opacity 0.2s; }
    .session-item:hover .delete-session { opacity: 1; }
    .delete-session:hover { color: #ef4444; }
    
    .chat-main { flex: 1; display: flex; flex-direction: column; position: relative; }
    .chat-messages { flex: 1; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 24px; }
    .welcome-msg { text-align: center; color: var(--text-muted); margin: auto; padding: 40px; }
    .welcome-msg h2 { color: var(--text-main); margin-bottom: 12px; }
    
    .message { display: flex; gap: 16px; max-width: 80%; }
    .message.user { align-self: flex-end; flex-direction: row-reverse; }
    .message.ai { align-self: flex-start; }
    
    .msg-avatar { width: 36px; height: 36px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
    .user .msg-avatar { background-color: var(--primary-color); }
    .ai .msg-avatar { background-color: #3b82f6; }
    
    .msg-content { padding: 12px 16px; border-radius: 12px; line-height: 1.6; font-size: 15px; }
    .user .msg-content { background-color: var(--primary-color); color: #fff; border-bottom-right-radius: 4px; }
    .ai .msg-content { background-color: var(--bg-card); border: 1px solid var(--border-color); border-bottom-left-radius: 4px; }
    
    .chat-input-area { padding: 20px; border-top: 1px solid var(--border-color); background-color: var(--bg-dark); display: flex; gap: 12px; align-items: flex-end; }
    #chatInput { flex: 1; background-color: var(--bg-card); border: 1px solid var(--border-color); border-radius: 12px; padding: 14px 16px; color: var(--text-main); font-family: inherit; font-size: 15px; resize: none; max-height: 200px; outline: none; transition: border-color 0.2s; }
    #chatInput:focus { border-color: var(--primary-color); }
    .send-btn { border-radius: 50%; width: 48px; height: 48px; padding: 0; justify-content: center; flex-shrink: 0; }
    
    .meta-info { font-size: 12px; color: var(--text-muted); margin-top: 8px; display: flex; gap: 12px; }
    .badge { padding: 2px 6px; border-radius: 4px; background: rgba(59, 130, 246, 0.2); color: #60a5fa; }
  `;
  if (!document.getElementById('chat-styles')) document.head.appendChild(style);

  // Auto resize textarea
  const chatInput = document.getElementById('chatInput');
  chatInput.addEventListener('input', function () {
    this.style.height = 'auto';
    this.style.height = (this.scrollHeight) + 'px';
  });

  chatInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });

  loadSessions();
}

async function loadSessions() {
  try {
    const list = document.getElementById('sessionList');
    if (!list) return;

    const token = getUserId();
    const result = await fetchApi('/ai/session/list', {
      method: 'POST',
      body: JSON.stringify({ userId: token })
    });

    list.innerHTML = '';
    if (result && result.sessions && result.sessions.length > 0) {
      result.sessions.forEach(sid => {
        const item = document.createElement('div');
        item.className = `session-item ${sid === currentSessionId ? 'active' : ''}`;
        item.onclick = () => selectSession(sid);
        item.innerHTML = `
          <div class="session-title">会话: ${sid.substring(0, 8)}...</div>
          <button class="delete-session btn-sm rounded" onclick="deleteSession(event, '${sid}')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
          </button>
        `;
        list.appendChild(item);
      });
      if (!currentSessionId) selectSession(result.sessions[0]);
    } else {
      list.innerHTML = `<div style="text-align:center;color:var(--text-muted);padding:20px;">暂无会话</div>`;
    }
  } catch (error) {
    console.error('Failed to load sessions', error);
  }
}

async function createNewSession() {
  try {
    const result = await fetchApi('/ai/session/create', {
      method: 'POST',
      body: JSON.stringify({ userId: getUserId() })
    });
    currentSessionId = result.sessionId;
    currentTurnCount = 0;
    document.getElementById('chatMessages').innerHTML = '';
    loadSessions();
    showToast('新会话创建成功');
  } catch (error) { }
}

async function deleteSession(e, sid) {
  e.stopPropagation();
  try {
    await fetchApi('/ai/session/delete', {
      method: 'POST',
      body: JSON.stringify({ sessionId: sid, userId: getUserId() })
    });
    showToast('会话已删除，系统正提炼长期记忆...');
    if (currentSessionId === sid) {
      currentSessionId = null;
      document.getElementById('chatMessages').innerHTML = '';
    }
    loadSessions();
  } catch (error) { }
}

// Extract user profile for the given session by calling the new backend endpoint
function triggerProfileExtraction(sid) {
  if (!sid) return;
  const url = '/ai/session/extract-profile';
  const data = JSON.stringify({ sessionId: sid, userId: getUserId() });

  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'satoken': getAuthToken()
    },
    body: data,
    keepalive: true
  }).catch(() => { });
}

// Add page unload event listener
window.addEventListener('beforeunload', () => {
  if (currentSessionId && currentTurnCount > 0) {
    triggerProfileExtraction(currentSessionId);
  }
});

function selectSession(sid) {
  // Extract profile for the old session if we actively chatted
  if (currentSessionId && currentSessionId !== sid && currentTurnCount > 0) {
    triggerProfileExtraction(currentSessionId);
  }

  // 切换会话
  currentSessionId = sid;
  document.querySelectorAll('.session-item').forEach(el => el.classList.remove('active'));
  loadSessions();

  // 异步加载此会话的历史消息
  loadHistory(sid);
}

// 获取并恢复历史会话内容
async function loadHistory(sid) {
  const container = document.getElementById('chatMessages');
  container.innerHTML = '<div style="text-align:center; padding: 40px; color: var(--text-muted);">加载历史中...</div>';

  try {
    const history = await fetchApi('/ai/session/history?sessionId=' + sid, { method: 'GET' });

    container.innerHTML = '';
    currentTurnCount = 0;

    if (history && history.length > 0) {
      history.forEach(msg => {
        addMessageToUI(msg.role, msg.content);
        if (msg.role === 'ai') currentTurnCount++;
      });
    } else {
      // 没有任何历史则显示欢迎页面
      container.innerHTML = `
        <div class="welcome-msg">
          <h2>欢迎使用 RAG 智能问答系统</h2>
          <p>请在下方输入框开始提问，系统将结合您的知识库和长期记忆进行回复。</p>
        </div>
      `;
    }
  } catch (e) {
    container.innerHTML = '<div style="text-align:center; padding: 40px; color: #ef4444;">加载历史失败</div>';
  }
}

function addMessageToUI(role, content, meta, msgId) {
  const container = document.getElementById('chatMessages');
  const msgDiv = document.createElement('div');
  msgDiv.className = 'message ' + role;
  if (msgId) {
    msgDiv.id = msgId;
    msgDiv.setAttribute('data-full-text', content || '');
  }

  const avatar = role === 'user' ? 'U' : 'AI';

  let metaHtml = '';
  if (meta && meta.hitKnowledge) {
    metaHtml = '<div class="meta-info"><span class="badge">命中知识库</span><span>引用文献: ' + (meta.referenceCount || 0) + '</span></div>';
  }

  const displayContent = content ? content.replace(/\n/g, '<br/>') : '';

  msgDiv.innerHTML = '<div class="msg-avatar">' + avatar + '</div>' +
    '<div style="display:flex; flex-direction:column; width:100%;">' +
    '<div class="msg-content">' + displayContent + '</div>' +
    metaHtml +
    '</div>';
  container.appendChild(msgDiv);

  const welcome = container.querySelector('.welcome-msg');
  if (welcome) welcome.remove();

  container.scrollTop = container.scrollHeight;
  return msgDiv;
}

// Handler for piecemeal stream updates
function updateStreamMessage(msgId, chunkText) {
  const msgDiv = document.getElementById(msgId);
  if (!msgDiv) return;

  // Accumulate the literal text chunks
  let currentText = msgDiv.getAttribute('data-full-text') || '';
  currentText += chunkText;
  msgDiv.setAttribute('data-full-text', currentText);

  // Render the text with line breaks
  const contentDiv = msgDiv.querySelector('.msg-content');
  if (contentDiv) {
    contentDiv.innerHTML = currentText.replace(/\n/g, '<br/>');
  }

  // Keep scroll pinned to bottom while streaming
  const container = document.getElementById('chatMessages');
  if (container) {
    container.scrollTop = container.scrollHeight;
  }
}

async function sendMessage() {
  const input = document.getElementById('chatInput');
  const text = input.value.trim();
  if (!text) return;

  if (!currentSessionId) {
    await createNewSession();
  }

  addMessageToUI('user', text);
  input.value = '';
  input.style.height = 'auto';

  const btn = document.getElementById('sendBtn');
  btn.disabled = true;
  btn.innerHTML = '...';

  // Create a placeholder bubble for AI streaming reply
  const streamMsgId = 'ai-msg-' + Date.now();
  addMessageToUI('ai', '', null, streamMsgId);

  try {
    // Fetch SSE stream from backend
    const response = await fetch('/ai/multi-turn/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'satoken': getAuthToken()
      },
      body: JSON.stringify({
        userId: getUserId(),
        sessionId: currentSessionId,
        message: text,
        turnCount: currentTurnCount
      })
    });

    if (!response.ok) {
      throw new Error('请求失败 (Status: ' + response.status + ')');
    }

    // Read SSE stream — format: "data:token_text\n\n"
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let done = false;
    let buffer = '';

    while (!done) {
      const result = await reader.read();
      done = result.done;

      if (result.value) {
        buffer += decoder.decode(result.value, { stream: true }).replace(/\r\n/g, '\n').replace(/\r/g, '\n');

        var parts = buffer.split('\n\n');
        buffer = parts.pop() || '';

        for (var i = 0; i < parts.length; i++) {
          handleSseEvent(parts[i], streamMsgId);
        }
      }
    }

    if (buffer) {
      handleSseEvent(buffer, streamMsgId);
    }

    currentTurnCount++;

  } catch (err) {
    updateStreamMessage(streamMsgId, '\n\n🚫 ' + err.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  }
}

function handleSseEvent(rawEvent, streamMsgId) {
  if (!rawEvent) return;

  var eventType = 'message';
  var dataLines = [];
  var lines = rawEvent.split('\n');

  for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    if (!line) continue;
    if (line.startsWith('event:')) {
      eventType = line.substring(6).trim();
      continue;
    }
    if (line.startsWith('data:')) {
      var dataValue = line.substring(5);
      if (dataValue.startsWith(' ')) {
        dataValue = dataValue.substring(1);
      }
      dataLines.push(dataValue);
    }
  }

  if (eventType === 'message') {
    updateStreamMessage(streamMsgId, dataLines.join('\n'));
  }
}
