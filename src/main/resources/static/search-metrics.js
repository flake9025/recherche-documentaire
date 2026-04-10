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
    const systemCpu = formatPercent(metrics.systemCpuUsagePct);
    const processCpu = formatPercent(metrics.processCpuUsagePct);
    const heap = formatMemory(metrics.heapUsedMb, metrics.heapMaxMb);
    const systemMemory = formatMemory(metrics.systemMemoryUsedMb, metrics.systemMemoryTotalMb);

    container.innerHTML = `
        <span class="runtime-pill">Temps: ${responseTime}</span>
        <span class="runtime-pill">Recherche: ${escapeHtml(metrics.searchEngine || 'n/a')}</span>
        <span class="runtime-pill">Index: ${escapeHtml(metrics.indexEngine || 'n/a')}</span>
        <span class="runtime-pill">Store: ${escapeHtml(metrics.embeddingsStore || 'n/a')}</span>
        ${rebuildTime ? `<span class="runtime-pill runtime-pill-warn">Rebuild: ${rebuildTime}</span>` : ''}
        <span class="runtime-pill">CPU syst.: ${systemCpu}</span>
        <span class="runtime-pill">CPU proc.: ${processCpu}</span>
        <span class="runtime-pill">Heap: ${heap}</span>
        <span class="runtime-pill">RAM: ${systemMemory}</span>
    `;
}

function formatMetricValue(value, unit) {
    if (value === undefined || value === null || value < 0) {
        return 'n/a';
    }
    return `${value} ${unit}`;
}

function formatPercent(value) {
    if (value === undefined || value === null || value < 0) {
        return 'n/a';
    }
    return `${Number(value).toFixed(2)} %`;
}

function formatMemory(used, total) {
    if (used === undefined || used === null || used < 0) {
        return 'n/a';
    }
    if (total === undefined || total === null || total < 0) {
        return `${used} MB`;
    }
    return `${used} / ${total} MB`;
}
