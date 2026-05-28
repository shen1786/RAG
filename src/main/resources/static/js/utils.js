// Toast Notification System
function showToast(message, type = 'success') {
    const container = document.getElementById('toastContainer');
    if (!container) return;
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    toast.innerHTML = `
    <span>${message}</span>
    <button class="toast-close" onclick="this.parentElement.remove()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
        <path d="M18 6L6 18M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </button>
  `;

    container.appendChild(toast);

    setTimeout(() => {
        if (toast.parentElement) {
            toast.style.opacity = '0';
            toast.style.transform = 'translateY(100%)';
            setTimeout(() => toast.remove(), 300);
        }
    }, 3000);
}

const STATIC_TOKEN_KEY = 'static_demo_token';
const STATIC_USER_KEY = 'static_demo_user';

function getStaticCredentials() {
    const userIdInput = document.getElementById('globalUserId');
    const username = userIdInput ? userIdInput.value.trim() : 'user_001';
    return {
        username: username || 'user_001',
        password: 'password123',
        email: (username || 'user_001') + '@example.com'
    };
}

function getStoredStaticUser() {
    try {
        return JSON.parse(localStorage.getItem(STATIC_USER_KEY) || 'null');
    } catch (error) {
        return null;
    }
}

function getAuthToken() {
    return localStorage.getItem(STATIC_TOKEN_KEY) || '';
}

function getUserId() {
    const userIdInput = document.getElementById('globalUserId');
    return userIdInput ? userIdInput.value.trim() : 'user_001';
}

function clearStaticAuth() {
    localStorage.removeItem(STATIC_TOKEN_KEY);
    localStorage.removeItem(STATIC_USER_KEY);
}

async function rawJsonFetch(url, options = {}) {
    const response = await fetch(url, options);
    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.code !== 200) {
        throw new Error(result.message || `请求失败 (${response.status})`);
    }
    return result;
}

async function loginWithStaticCredentials(credentials) {
    const loginResult = await rawJsonFetch('/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            username: credentials.username,
            password: credentials.password
        })
    });
    const token = loginResult.data.tokenValue || loginResult.data.token;
    localStorage.setItem(STATIC_TOKEN_KEY, token);
    localStorage.setItem('token', token);
    localStorage.setItem(STATIC_USER_KEY, JSON.stringify({
        userId: loginResult.data.userId || credentials.username,
        username: credentials.username,
        email: credentials.email
    }));
    return loginResult.data;
}

async function ensureLoginForStaticPage(forceRefresh = false) {
    const credentials = getStaticCredentials();
    const storedUser = getStoredStaticUser();
    
    if (!forceRefresh && getAuthToken() && storedUser && storedUser.username === credentials.username) {
        return storedUser;
    }

    if (forceRefresh) {
        clearStaticAuth();
    }

    try {
        return await loginWithStaticCredentials(credentials);
    } catch (loginError) {
        try {
            // If login fails (username not exist), attempt automatic registration
            await rawJsonFetch('/auth/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    username: credentials.username,
                    password: credentials.password,
                    roleCode: 'user'
                })
            });
            return await loginWithStaticCredentials(credentials);
        } catch (registerError) {
            clearStaticAuth();
            console.error('Auto auth failed', registerError);
            showToast('老页面自动登录失败: ' + (registerError.message || '网络或鉴权错误'), 'error');
            throw loginError;
        }
    }
}

document.addEventListener('DOMContentLoaded', () => {
    ensureLoginForStaticPage().catch(() => {});
    const input = document.getElementById('globalUserId');
    if (input) {
        input.addEventListener('change', () => {
            ensureLoginForStaticPage(true).catch(() => {});
        });
    }
});

// Simple fetch wrapper to handle JSON responses and errors
async function fetchApi(url, options = {}, retry = true) {
    try {
        if (!url.startsWith('/auth/')) {
            await ensureLoginForStaticPage();
        }
        const reqOptions = {
            ...options,
            headers: {
                'Content-Type': 'application/json',
                'satoken': getAuthToken(),
                ...(options.headers || {})
            }
        };

        // If FormData, remove Content-Type to let browser set boundary
        if (options.body instanceof FormData) {
            delete reqOptions.headers['Content-Type'];
        }

        const response = await fetch(url, reqOptions);

        if (response.status === 401 && retry && !url.startsWith('/auth/')) {
            clearStaticAuth();
            await ensureLoginForStaticPage(true);
            return fetchApi(url, options, false);
        }

        // For streams or non-json
        if (options.isStream || response.headers.get('content-type')?.includes('text/plain')) {
            return response;
        }

        const result = await response.json();
        if (result.code !== 200) {
            throw new Error(result.message || 'API request failed');
        }
        return result.data;
    } catch (error) {
        showToast(error.message, 'error');
        throw error;
    }
}

// File Hash Generator
async function generateFileHash(file) {
    if (file.size < 100 * 1024 * 1024) { // < 100MB
        const buffer = await file.arrayBuffer();
        const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
    } else {
        // Fast metadata hashing for large files to avoid browser freeze and produce standard 64-char hex
        const metadata = `${file.name}:${file.size}:${file.lastModified}`;
        const encoder = new TextEncoder();
        const data = encoder.encode(metadata);
        const hashBuffer = await crypto.subtle.digest('SHA-256', data);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
    }
}

// Format bytes
function formatBytes(bytes, decimals = 2) {
    if (!+bytes) return '0 Bytes';
    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizes[i]}`;
}

// Format Date
function formatDate(timestamp) {
    if (!timestamp) return '-';
    const date = typeof timestamp === 'string'
        ? new Date(timestamp.replace(' ', 'T'))
        : new Date(timestamp);
    if (Number.isNaN(date.getTime())) {
        return String(timestamp);
    }
    return date.toLocaleString('zh-CN', { hour12: false });
}
