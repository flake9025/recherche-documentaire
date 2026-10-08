document.getElementById('loginForm').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button');
    const error = document.getElementById('loginError');
    error.textContent = '';
    button.disabled = true;
    try {
        await refreshCsrf();
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams(new FormData(form))
        });
        if (!response.ok) throw new Error('Connexion refusee. Verifier les identifiants.');
        window.location.assign('/');
    } catch (exception) {
        error.textContent = exception.message;
    } finally {
        button.disabled = false;
    }
});
