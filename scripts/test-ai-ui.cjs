const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const staticDirectory = path.join(root, 'recherche-documentaire-webapp-demo', 'src', 'main', 'resources', 'static');
const source = fs.readFileSync(path.join(staticDirectory, 'script.js'), 'utf8');

function fixture(models) {
    const elements = new Map();
    function element() {
        return {
            value: '', textContent: '', checked: false, disabled: true, hidden: false,
            children: [], options: [], listeners: {}, style: {}, dataset: {},
            add(option) {
                this.options.push(option);
                if (!this.value) this.value = option.value;
            },
            addEventListener(name, listener) { this.listeners[name] = listener; },
            replaceChildren() { this.children = []; },
            append(...children) { this.children.push(...children); }
        };
    }
    const document = {
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, element());
            return elements.get(id);
        },
        createElement() { return element(); }
    };
    const context = vm.createContext({
        window: { location: { origin: 'http://127.0.0.1' }, addEventListener() {} },
        document,
        Option: function(text, value) { this.text = text; this.value = value; },
        fetch: async () => ({ ok: true, json: async () => models })
    });
    vm.runInContext(source, context);
    return { context, document };
}

test('the checkbox does not falsely promise local inference', () => {
    const html = fs.readFileSync(path.join(staticDirectory, 'index.html'), 'utf8');
    assert.match(html, /id="summarizeResults" disabled> Synthetiser les resultats avec une IA<\/label>/);
    assert.doesNotMatch(html, /resultats avec une IA locale/);
});

test('the refreshed pages preserve authentication and document control contracts', () => {
    const html = fs.readFileSync(path.join(staticDirectory, 'index.html'), 'utf8');
    for (const id of [
        'currentUser', 'logoutBtn', 'filterCategory', 'filterAuthor', 'filterDateFrom', 'filterDateTo', 'filterSort',
        'searchQuery', 'searchBtn', 'clearSearchBtn', 'searchFilters', 'activeFilterCount',
        'summarizeResults', 'aiModel', 'aiStatus', 'aiSummary', 'aiOptions', 'aiOptionState',
        'searchResultsHeader', 'resultsCount', 'resultsRuntime', 'searchResultContent', 'searchLoading', 'searchError',
        'indexFile', 'fileDrop', 'fileName', 'documentTitle', 'documentAuthor', 'documentDate', 'indexType',
        'indexOcrType', 'indexBtn', 'resetIndexBtn', 'indexLoading', 'indexError', 'indexSuccess', 'indexResultContent',
        'rebuildDocumentsBtn', 'rebuildAuthorsBtn', 'maintenanceLoading', 'maintenanceError', 'maintenanceSuccess',
        'maintenanceResultContent', 'refreshStatsBtn', 'statsLoading', 'statsError', 'statsCards'
    ]) {
        assert.match(html, new RegExp(`id="${id}"`), `Missing control: ${id}`);
    }
    assert.match(html, /href="\/admin\.html" data-admin-only hidden/);
    const login = fs.readFileSync(path.join(staticDirectory, 'login.html'), 'utf8');
    assert.match(login, /id="loginForm"/);
    assert.match(login, /name="username" autocomplete="username"/);
    assert.match(login, /type="password" name="password"\s+autocomplete="current-password"/);
    assert.match(login, /id="loginError" role="alert"/);
    for (const page of [html, login]) {
        assert.match(page, /src="\/logo\.svg"/);
        assert.match(page, /src="auth\.js"/);
    }
    assert.ok(fs.existsSync(path.join(staticDirectory, 'logo.svg')));
});

test('Bedrock shows the upstream model and warns about cloud transmission', async () => {
    const { context, document } = fixture([
        { id: 'bedrock', model: 'bedrock', displayName: 'openai.gpt-5.4', provider: 'LITELLM', hosting: 'CLOUD' }
    ]);
    await context.initializeAi();
    assert.equal(document.getElementById('aiModel').options[0].text, 'openai.gpt-5.4 (cloud, LITELLM)');
    assert.match(document.getElementById('aiStatus').textContent, /Modele cloud.*hors du serveur/);
    assert.match(document.getElementById('aiStatus').textContent, /integral pour les petits documents/);
    assert.equal(document.getElementById('summarizeResults').checked, false);
    assert.equal(document.getElementById('summarizeResults').disabled, false);
    assert.equal(document.getElementById('aiOptionState').textContent, 'Optionnelle');
});

test('collapsed AI options keep the cloud destination explicit after opt-in', async () => {
    const { context, document } = fixture([
        { id: 'cloud', model: 'bedrock', provider: 'LITELLM', hosting: 'CLOUD' }
    ]);
    await context.initializeAi();
    const checkbox = document.getElementById('summarizeResults');
    checkbox.checked = true;
    checkbox.listeners.change();
    assert.equal(document.getElementById('aiOptionState').textContent, 'Activee - cloud');
    assert.equal(document.getElementById('aiOptionState').dataset.active, 'true');
});

test('collapsed filters count the exact normalized criteria sent by search', () => {
    const { context, document } = fixture([]);
    document.getElementById('filterCategory').value = 'Tout';
    document.getElementById('filterSort').value = 'Plus récents';
    context.updateSearchFilterSummary();
    assert.equal(document.getElementById('activeFilterCount').textContent, 'Aucun filtre');
    document.getElementById('filterCategory').value = 'Rapport';
    document.getElementById('filterAuthor').value = '  Compte demo  ';
    document.getElementById('filterDateFrom').value = '2030-01-01';
    context.updateSearchFilterSummary();
    assert.equal(document.getElementById('activeFilterCount').textContent, '3 filtres');
    assert.equal(document.getElementById('activeFilterCount').dataset.active, 'true');
    assert.deepEqual(JSON.parse(JSON.stringify(context.readSearchFilters())), {
        filters: { category: 'RAPPORT', author: 'Compte demo', dateFrom: '2030-01-01', dateTo: null, sort: 'DESC' },
        activeCount: 3
    });
});

test('changing from local to cloud renews opt-in', async () => {
    const { context, document } = fixture([
        { id: 'local', model: 'mistral', provider: 'LITELLM', hosting: 'LOCAL' },
        { id: 'cloud', model: 'bedrock', provider: 'LITELLM', hosting: 'CLOUD' }
    ]);
    await context.initializeAi();
    assert.match(document.getElementById('aiStatus').textContent, /local\/on-premise/);
    const checkbox = document.getElementById('summarizeResults');
    checkbox.checked = true;
    const select = document.getElementById('aiModel');
    select.value = 'cloud';
    select.listeners.change();
    assert.equal(checkbox.checked, false);
    assert.match(document.getElementById('aiStatus').textContent, /Modele cloud/);
});

test('unknown hosting is explicit instead of being labelled local', async () => {
    const { context, document } = fixture([{ id: 'custom', model: 'custom', provider: 'OPENAI' }]);
    await context.initializeAi();
    assert.match(document.getElementById('aiModel').options[0].text, /destination non precisee/);
    assert.match(document.getElementById('aiStatus').textContent, /Destination non precisee/);
});

test('partial context is disclosed next to the corresponding citation', () => {
    const { context, document } = fixture([]);
    context.displaySummary({ summary: {
        model: 'model', text: 'Resume [1] [2]',
        sources: [
            { number: 1, documentId: '10', title: 'Source complete', partial: false },
            { number: 2, documentId: '20', title: 'Source partielle', partial: true }
        ]
    } });
    const children = document.getElementById('aiSummary').children;
    assert.equal(children[2].textContent, '[1] Source complete');
    assert.equal(children[4].textContent, '[2] Source partielle (contexte abrege)');
    assert.equal(children[4].href, '/api/documents/20/file');
    assert.equal(children[4].rel, 'noopener');
});
