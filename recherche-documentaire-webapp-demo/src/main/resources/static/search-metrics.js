function displaySearchResults(data, resultContent, resultsCount, resultsHeader) {
    const resultsRuntime = document.getElementById('resultsRuntime');
    renderSearchMetrics(data ? data.metrics : null, resultsRuntime);

    if (!data || data.nbResults === 0) {
        resultContent.innerHTML = '<div class="no-results">Aucun resultat trouve</div>';
        resultsCount.textContent = '0 resultat';
        resultsHeader.style.display = 'flex';
        return;
    }

    resultsCount.textContent = `${data.nbResults} resultat${data.nbResults > 1 ? 's' : ''} trouve${data.nbResults > 1 ? 's' : ''}`;
    resultsHeader.style.display = 'flex';

    let html = '';
    data.fragments.forEach(fragment => {
        html += createDocumentCard(fragment);
    });

    resultContent.innerHTML = html;
}

function renderSearchMetrics(metrics, container) {
    if (!container) {
        return;
    }
    if (!metrics) {
        container.innerHTML = '';
        return;
    }

    const responseTime = formatMetricValue(metrics.responseTimeMs, 'ms');
    const rebuildTime = metrics.rebuildTimeMs > 0 ? formatMetricValue(metrics.rebuildTimeMs, 'ms') : null;
    const embeddingTime = metrics.embeddingTimeMs > 0 ? formatMetricValue(metrics.embeddingTimeMs, 'ms') : null;
    const containerRam = formatContainerMemory(metrics.containerMemoryUsedMb, metrics.containerMemoryLimitMb);

    container.innerHTML = `
        <span class="runtime-pill">Temps: ${responseTime}</span>
        ${embeddingTime ? `<span class="runtime-pill">Encoding BERT: ${embeddingTime}</span>` : ''}
        <span class="runtime-pill">Recherche: ${escapeHtml(metrics.searchEngine || 'n/a')}</span>
        <span class="runtime-pill">Index: ${escapeHtml(metrics.indexEngine || 'n/a')}</span>
        <span class="runtime-pill">Store: ${escapeHtml(metrics.embeddingsStore || 'n/a')}</span>
        ${rebuildTime ? `<span class="runtime-pill runtime-pill-warn">Rebuild: ${rebuildTime}</span>` : ''}
        ${containerRam ? `<span class="runtime-pill">RAM conteneur: ${containerRam}</span>` : ''}
    `;
}

function formatMetricValue(value, unit) {
    if (value === undefined || value === null || value < 0) {
        return 'n/a';
    }
    return `${value} ${unit}`;
}

function formatContainerMemory(used, limit) {
    if (used === undefined || used === null || used < 0) {
        return null; // non disponible (hors conteneur)
    }
    if (limit === undefined || limit === null || limit < 0) {
        return `${used} MB`;
    }
    return `${used} / ${limit} MB`;
}
