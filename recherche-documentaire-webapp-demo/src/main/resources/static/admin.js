const userForm = document.getElementById('userForm');
const adminError = document.getElementById('adminError');

async function adminRequest(url, options) {
    const response = await fetch(url, options);
    if (!response.ok) {
        const body = await response.json().catch(() => ({ message: `Erreur HTTP ${response.status}` }));
        throw new Error(body.message || `Erreur HTTP ${response.status}`);
    }
    return response.status === 204 ? null : response.json();
}

async function loadUsers() {
    const users = await adminRequest('/api/admin/users');
    const table = document.getElementById('usersTable');
    table.replaceChildren();
    const managers = userForm.elements.managerId;
    const selection = managers.value;
    managers.replaceChildren(new Option('Selectionner un responsable', ''));
    users.filter(user => user.role === 'MANAGER' && user.enabled).forEach(user => {
        managers.add(new Option(`${user.displayName} (${user.username})`, user.id));
    });
    managers.value = selection;
    users.forEach(user => {
        const row = document.createElement('tr');
        [user.id, user.username, user.displayName, user.role,
            users.find(item => item.id === user.managerId)?.username || '-', user.enabled ? 'Oui' : 'Non'].forEach(value => {
            const cell = document.createElement('td');
            cell.textContent = value;
            row.append(cell);
        });
        const actions = document.createElement('td');
        const edit = document.createElement('button');
        edit.textContent = 'Modifier';
        edit.addEventListener('click', () => {
            userForm.elements.id.value = user.id;
            userForm.elements.username.value = user.username;
            userForm.elements.displayName.value = user.displayName;
            userForm.elements.password.value = '';
            userForm.elements.role.value = user.role;
            userForm.elements.managerId.value = user.managerId || '';
            userForm.elements.enabled.checked = user.enabled;
            document.getElementById('userFormTitle').textContent = 'Modifier un utilisateur';
            updateManagerField();
        });
        const remove = document.createElement('button');
        remove.textContent = 'Supprimer';
        remove.addEventListener('click', async () => {
            if (!confirm(`Supprimer le compte ${user.username} ?`)) return;
            try {
                await adminRequest(`/api/admin/users/${user.id}`, { method: 'DELETE' });
                await loadUsers();
                adminError.textContent = '';
            } catch (error) {
                adminError.textContent = error.message;
            }
        });
        actions.append(edit, remove);
        row.append(actions);
        table.append(row);
    });
    updateManagerField();
}

function updateManagerField() {
    const isUser = userForm.elements.role.value === 'USER';
    const managers = userForm.elements.managerId;
    const id = userForm.elements.id.value;
    for (const option of managers.options) {
        option.disabled = Boolean(id && option.value === id);
    }
    managers.disabled = !isUser;
    managers.required = isUser;
    if (!isUser || (id && managers.value === id)) managers.value = '';
}

userForm.elements.role.addEventListener('change', updateManagerField);
document.getElementById('resetUser').addEventListener('click', () => {
    userForm.reset();
    userForm.elements.id.value = '';
    document.getElementById('userFormTitle').textContent = 'Ajouter un utilisateur';
    updateManagerField();
});
userForm.addEventListener('submit', async event => {
    event.preventDefault();
    const fields = userForm.elements;
    const id = fields.id.value;
    const payload = {
        username: fields.username.value,
        displayName: fields.displayName.value,
        password: fields.password.value,
        role: fields.role.value,
        managerId: fields.managerId.value ? Number(fields.managerId.value) : null,
        enabled: fields.enabled.checked
    };
    try {
        await adminRequest(`/api/admin/users${id ? '/' + id : ''}`, {
            method: id ? 'PUT' : 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        userForm.reset();
        fields.id.value = '';
        document.getElementById('userFormTitle').textContent = 'Ajouter un utilisateur';
        updateManagerField();
        await loadUsers();
        adminError.textContent = '';
    } catch (error) {
        adminError.textContent = error.message;
    }
});

initializeSession().then(loadUsers).catch(error => { adminError.textContent = error.message; });
