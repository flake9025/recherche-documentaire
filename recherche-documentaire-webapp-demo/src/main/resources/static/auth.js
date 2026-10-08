const rawFetch = window.fetch.bind(window);
let csrfToken;

async function refreshCsrf() {
    const response = await rawFetch('/api/auth/csrf', { credentials: 'same-origin' });
    if (!response.ok) throw new Error('Impossible de preparer la session');
    csrfToken = await response.json();
    return csrfToken;
}

window.fetch = async function(input, options = {}) {
    const url = new URL(typeof input === 'string' ? input : input.url, window.location.origin);
    const method = (options.method || 'GET').toUpperCase();
    if (url.origin === window.location.origin && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        const token = csrfToken || await refreshCsrf();
        const headers = new Headers(options.headers);
        headers.set(token.headerName, token.token);
        options = { ...options, headers, credentials: 'same-origin' };
    }
    const response = await rawFetch(input, options);
    if (response.status === 401 && window.location.pathname !== '/login.html') {
        window.location.assign('/login.html');
    }
    return response;
};

async function initializeSession() {
    const response = await fetch('/api/auth/me');
    if (!response.ok) throw new Error('Session indisponible');
    const user = await response.json();
    const label = document.getElementById('currentUser');
    if (label) label.textContent = `${user.displayName} (${user.role})`;
    document.querySelectorAll('[data-admin-only], [data-tab="maintenance"], [data-tab="stats"]').forEach(element => {
        element.hidden = user.role !== 'ADMIN';
    });
    const logout = document.getElementById('logoutBtn');
    if (logout) {
        logout.addEventListener('click', async () => {
            try {
                const result = await fetch('/api/auth/logout', { method: 'POST' });
                if (!result.ok) throw new Error('Deconnexion impossible');
                window.location.assign('/login.html');
            } catch (error) {
                alert(error.message);
            }
        });
    }
    return user;
}
