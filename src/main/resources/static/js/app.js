function navigateTo(pageId) {
    // Update nav state
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    document.getElementById(`nav-${pageId}`)?.classList.add('active');

    // Close sidebar on mobile after click
    const sidebar = document.getElementById('sidebar');
    if (window.innerWidth <= 768) {
        sidebar.classList.remove('show');
    }

    // Load page content
    const mainContent = document.getElementById('mainContent');
    mainContent.innerHTML = ''; // Clear current

    switch (pageId) {
        case 'chat':
            renderChatPage(mainContent);
            break;
        case 'knowledge':
            renderKnowledgePage(mainContent);
            break;
        case 'upload':
            renderUploadPage(mainContent);
            break;
        default:
            renderChatPage(mainContent);
    }
}

function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('show');
}

// Initial load
document.addEventListener('DOMContentLoaded', async () => {
    try {
        await ensureLoginForStaticPage();
    } catch (error) {
        console.warn('静态页自动登录尚未完成:', error.message);
    }
    // default page
    const hash = window.location.hash.replace('#', '') || 'chat';
    navigateTo(hash);

    // Hash change listening
    window.addEventListener('hashchange', () => {
        const newHash = window.location.hash.replace('#', '') || 'chat';
        navigateTo(newHash);
    });
});
