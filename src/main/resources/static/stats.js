const STATS_ENDPOINT = '/api/stats';

window.addEventListener('load', function() {
    const refreshButton = document.getElementById('refreshStatsBtn');
    if (refreshButton) {
        refreshButton.addEventListener('click', loadStats);
    }

    document.querySelectorAll('.tab-button[data-tab="stats"]').forEach(button => {
        button.addEventListener('click', () => {
            loadStats();
        });
    });
});

async function loadStats() {
    const loading = document.getElementById('statsLoading');
    const error = document.getElementById('statsError');
    const cards = document.getElementById('statsCards');

    if (!loading || !error || !cards) {
        return;
    }

    hideElement(error);
    showElement(loading);

    try {
        const response = await fetch(`${window.location.origin}${STATS_ENDPOINT}`);
        if (!response.ok) {
            throw new Error(`Erreur HTTP ${response.status}`);
        }

        const stats = await response.json();
        cards.innerHTML = renderStatsCards(stats);
    } catch (err) {
        showError(error, `Erreur : ${err.message}`);
    } finally {
        hideElement(loading);
    }
}

function renderStatsCards(stats) {
    const items = [
        ['Profil actif', stats.activeProfile],
        ['Moteur index', stats.indexEngine],
        ['Moteur recherche', stats.searchEngine],
        ['Store embeddings', stats.embeddingsStore],
        ['Modèle BERT', stats.embeddingsModelId],
        ['Modèle BERT chargé', stats.embeddingsModelLoaded ? '✅ Oui' : '⏳ Non (premier appel)'],
        ['Documents en base', formatInteger(stats.databaseDocumentCount)],
        ['Documents OCR en attente', formatInteger(stats.pendingOcrCount)],
        ['Documents dans index memoire', formatInteger(stats.inMemoryIndexDocumentCount)],
        ['Embeddings en memoire', formatInteger(stats.inMemoryEmbeddingsCount)],
        ['Taille index memoire', stats.inMemoryIndexSizeHuman],
        ['Fichiers stockes', formatInteger(stats.storageFileCount)],
        ['Espace stockage', stats.storageSizeHuman]
    ];

    return items.map(([label, value]) => `
        <article class="stats-card">
            <span class="stats-label">${escapeHtml(String(label))}</span>
            <strong class="stats-value">${escapeHtml(value == null ? 'n/a' : String(value))}</strong>
        </article>
    `).join('');
}

function formatInteger(value) {
    if (value === undefined || value === null) {
        return 'n/a';
    }
    return new Intl.NumberFormat('fr-FR').format(value);
}
