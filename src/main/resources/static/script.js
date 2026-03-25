// ==================== CONFIGURATION ====================
const API_BASE = window.location.origin;

const ENDPOINTS = {
    search: '/api/search/',
    index: '/api/index/addFromOCR',
    autocompleteAuthors: '/api/autocomplete/authors'
};

// ==================== INITIALISATION ====================
window.addEventListener('load', function() {
    initEventListeners();
});

function initEventListeners() {
    document.getElementById('searchBtn').addEventListener('click', performSearch);
    document.getElementById('indexBtn').addEventListener('click', performIndex);
    document.getElementById('resetIndexBtn').addEventListener('click', resetIndexForm);
    document.getElementById('clearSearchBtn').addEventListener('click', clearSearch);

    document.getElementById('indexFile').addEventListener('change', handleFileChange);

    document.getElementById('searchQuery').addEventListener('keypress', function(e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            performSearch();
        }
    });

    document.querySelectorAll('.chip').forEach(chip => {
        chip.addEventListener('click', () => {
            const query = chip.getAttribute('data-query') || '';
            const searchQuery = document.getElementById('searchQuery');
            searchQuery.value = query;
            searchQuery.focus();
        });
    });

    setupFileDrop();
    setupTabs();
    setDefaultDepositDate();

    // Initialiser l'autocomplétion sur le filtre auteur
    setupAuthorAutocomplete();
}

// ==================== GESTION FICHIER ====================
function handleFileChange() {
    try {
        const fileInput = document.getElementById('indexFile');
        const fileName = document.getElementById('fileName');

        if (!fileName) return;

        if (fileInput.files && fileInput.files.length > 0) {
            fileName.textContent = '✓ ' + fileInput.files[0].name;
        } else {
            fileName.textContent = '';
        }
    } catch (err) {
        console.error('Erreur changement fichier :', err);
    }
}

function setupFileDrop() {
    const dropArea = document.getElementById('fileDrop');
    const fileInput = document.getElementById('indexFile');

    if (!dropArea || !fileInput) return;

    ['dragenter', 'dragover'].forEach(eventName => {
        dropArea.addEventListener(eventName, (e) => {
            e.preventDefault();
            dropArea.classList.add('dragover');
        });
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropArea.addEventListener(eventName, (e) => {
            e.preventDefault();
            dropArea.classList.remove('dragover');
        });
    });

    dropArea.addEventListener('drop', (e) => {
        if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
            fileInput.files = e.dataTransfer.files;
            handleFileChange();
        }
    });
}

function setupTabs() {
    const tabButtons = document.querySelectorAll('.tab-button');
    const tabPanels = document.querySelectorAll('.tab-panel');

    if (tabButtons.length === 0 || tabPanels.length === 0) return;

    tabButtons.forEach(button => {
        button.addEventListener('click', () => {
            const target = button.getAttribute('data-tab');
            if (!target) return;

            tabButtons.forEach(btn => btn.classList.remove('is-active'));
            tabPanels.forEach(panel => panel.classList.remove('is-active'));

            tabButtons.forEach(btn => {
                if (btn.getAttribute('data-tab') === target) {
                    btn.classList.add('is-active');
                }
            });

            const panel = document.querySelector(`.tab-panel[data-tab-panel="${target}"]`);
            if (panel) {
                panel.classList.add('is-active');
            }
        });
    });
}

// ==================== RECHERCHE ====================
async function performSearch() {
    const query = document.getElementById('searchQuery').value;
    const categoryFilter = document.getElementById('filterCategory').value;
    const authorFilter = document.getElementById('filterAuthor').value;
    const dateFrom = document.getElementById('filterDateFrom').value;
    const dateTo = document.getElementById('filterDateTo').value;
    const sort = document.getElementById('filterSort').value;

    const loading = document.getElementById('searchLoading');
    const error = document.getElementById('searchError');
    const result = document.getElementById('searchResult');
    const resultContent = document.getElementById('searchResultContent');
    const resultsHeader = document.getElementById('searchResultsHeader');
    const resultsCount = document.getElementById('resultsCount');

    const hasQuery = query.trim().length > 0;
    const hasCategory = categoryFilter !== 'Tout';
    const hasAuthor = authorFilter.trim().length > 0;
    const hasDateFrom = dateFrom.trim().length > 0;
    const hasDateTo = dateTo.trim().length > 0;

    if (!hasQuery && !hasCategory && !hasAuthor && !hasDateFrom && !hasDateTo) {
        showError(error, 'Veuillez entrer une requête ou un filtre');
        return;
    }

    hideElement(error);
    hideElement(result);
    resultsHeader.style.display = 'none';
    showElement(loading);
    setButtonsDisabled(true);

    try {
        const payload = {
            query: hasQuery ? query.trim() : null,
            category: categoryFilter !== 'Tout' ? categoryFilter.toUpperCase() : null,
            author: hasAuthor ? authorFilter.trim() : null,
            dateFrom: hasDateFrom ? dateFrom.trim() : null,
            dateTo: hasDateTo ? dateTo.trim() : null,
            sort: sort === 'Plus récents' ? 'DESC' : 'ASC'
        };

        const response = await fetch(`${API_BASE}${ENDPOINTS.search}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw new Error('Erreur HTTP ' + response.status);
        }

        const data = await response.json();
        displaySearchResults(data, resultContent, resultsCount, resultsHeader);
        showElement(result);
    } catch (err) {
        showError(error, 'Erreur : ' + err.message);
    } finally {
        hideElement(loading);
        setButtonsDisabled(false);
    }
}


function displaySearchResults(data, resultContent, resultsCount, resultsHeader) {
    if (data.nbResults === 0) {
        resultContent.innerHTML = '<div class="no-results">Aucun résultat trouvé</div>';
        return;
    }

    resultsCount.textContent = `${data.nbResults} résultat${data.nbResults > 1 ? 's' : ''} trouvé${data.nbResults > 1 ? 's' : ''}`;
    resultsHeader.style.display = 'flex';

    let html = '';
    data.fragments.forEach(fragment => {
        html += createDocumentCard(fragment);
    });

    resultContent.innerHTML = html;
}

function createDocumentCard(fragment) {
    const shortId = escapeHtml(fragment.id.substring(0, 8));
    const name = escapeHtml(fragment.name || 'Sans titre');
    const author = escapeHtml(fragment.author || 'Auteur inconnu');
    const category = escapeHtml(fragment.category || 'Non classé');
    const dateDepot = fragment.date;
    const filename = escapeHtml(fragment.filename || '');
    const snippet = fragment.fragment || '';
    const score = fragment.score !== undefined ? fragment.score.toFixed(2) : 'N/A';

    // Si ton backend renvoie un URL complet, on l'utilise directement
    const fileUrl = fragment.fileUrl ? escapeHtml(fragment.fileUrl) : null;

    const categoryLabel = mapCategoryLabel(category);

    let actionsHtml = '';
    if (fileUrl) {
        actionsHtml = `
            <div class="document-actions">
                <a href="${fileUrl}" class="btn-link" target="_blank" rel="noopener">
                    Voir le PDF
                </a>
                <a href="${fileUrl}" class="btn-link" download="${filename || name + '.pdf'}">
                    Télécharger
                </a>
            </div>
        `;
    }

    return `
        <div class="document-item">
            <div class="document-header">
                <div>
                    <div class="document-name">${name}</div>
                    <div class="document-meta">
                        <span class="document-meta-item">ID: <span class="mono">${shortId}</span></span>
                        <span class="document-meta-item">Score: <span class="score">${score}</span></span>
                        <span class="document-meta-item">Auteur: ${author}</span>
                        <span class="document-meta-item">Date: ${dateDepot}</span>
                        ${filename ? `<span class="document-meta-item">Fichier: ${filename}</span>` : ''}
                    </div>
                </div>
                <span class="document-category-tag">${categoryLabel}</span>
            </div>
            <div class="document-fragment">${snippet}</div>
            ${actionsHtml}
        </div>
    `;
}


// Optionnel: mapper les codes techniques vers des labels lisibles
function mapCategoryLabel(category) {
    switch (category) {
        case 'FACTURE': return 'Facture';
        case 'RAPPORT': return 'Rapport';
        case 'CONTRAT': return 'Contrat';
        default: return category;
    }
}


// ==================== INDEXATION ====================
async function performIndex() {
    const type = document.getElementById('indexType').value;
    const ocrType = document.getElementById('indexOcrType').value;
    const title = document.getElementById('documentTitle').value;
    const author = document.getElementById('documentAuthor').value;
    const documentDateInput = document.getElementById('documentDate');
    let dateDepot = documentDateInput ? documentDateInput.value : '';
    const fileInput = document.getElementById('indexFile');
    const loading = document.getElementById('indexLoading');
    const error = document.getElementById('indexError');
    const success = document.getElementById('indexSuccess');
    const result = document.getElementById('indexResult');
    const resultContent = document.getElementById('indexResultContent');

    if (!type) {
        showError(error, 'Veuillez sélectionner un type de document');
        return;
    }

    if (!title.trim()) {
        showError(error, 'Veuillez saisir un titre de document');
        return;
    }

    if (!author.trim()) {
        showError(error, 'Veuillez saisir un auteur de dépôt');
        return;
    }

    if (!fileInput.files || fileInput.files.length === 0) {
        showError(error, 'Veuillez sélectionner un fichier');
        return;
    }

    hideElement(error);
    hideElement(success);
    hideElement(result);
    showElement(loading);
    setButtonsDisabled(true);

    try {
        const formData = new FormData();
        formData.append('file', fileInput.files[0]);
        formData.append('titre', title.trim());
        formData.append('auteur', author.trim());
        formData.append('categorie', type);
        formData.append('ocrType', ocrType);
        if (!dateDepot.trim() && documentDateInput) {
            setDefaultDepositDate();
            dateDepot = documentDateInput.value;
        }
        if (dateDepot.trim()) {
            formData.append('dateDepot', dateDepot.trim());
        }

        const response = await fetch(API_BASE + ENDPOINTS.index, {
            method: 'POST',
            body: formData
        });

        if (!response.ok) {
            throw new Error('Erreur HTTP : ' + response.status);
        }

        const data = await response.text();

        success.textContent = '✓ Document indexé avec succès !';
        showElement(success);

        resultContent.textContent = data;
        showElement(result);

        resetIndexForm();
    } catch (err) {
        showError(error, 'Erreur : ' + err.message);
    } finally {
        hideElement(loading);
        setButtonsDisabled(false);
    }
}

function resetIndexForm() {
    const fileInput = document.getElementById('indexFile');
    if (fileInput) {
        fileInput.value = '';
    }

    const fileName = document.getElementById('fileName');
    if (fileName) {
        fileName.textContent = '';
    }

    document.getElementById('indexType').value = 'FACTURE';
    document.getElementById('indexOcrType').value = 'pdfbox';
    document.getElementById('documentTitle').value = '';
    document.getElementById('documentAuthor').value = '';
    setDefaultDepositDate();
}

function setDefaultDepositDate() {
    const documentDate = document.getElementById('documentDate');
    if (documentDate) {
        documentDate.value = new Date().toISOString().split('T')[0];
    }
}

function clearSearch() {
    document.getElementById('searchQuery').value = '';
    document.getElementById('searchQuery').focus();
    hideElement(document.getElementById('searchResult'));
    hideElement(document.getElementById('searchError'));
}

function setButtonsDisabled(isDisabled) {
    document.getElementById('searchBtn').disabled = isDisabled;
    document.getElementById('indexBtn').disabled = isDisabled;
}

// ==================== AUTOCOMPLÉTION ====================
let autocompleteTimeout = null;

function setupAuthorAutocomplete() {
    const searchInput = document.getElementById('filterAuthor');
    if (!searchInput) return;

    // Créer le conteneur de suggestions
    const autocompleteContainer = document.createElement('div');
    autocompleteContainer.id = 'authorAutocomplete';
    autocompleteContainer.className = 'autocomplete-container';

    // Insérer après le champ de recherche
    const fieldDiv = searchInput.closest('.autocomplete-control') || searchInput.parentElement;
    if (fieldDiv) {
        fieldDiv.appendChild(autocompleteContainer);
        // S'assurer que le parent a position relative
        fieldDiv.style.position = 'relative';
    }

    // Écouter les saisies
    searchInput.addEventListener('input', function() {
        const query = this.value.trim();

        // Annuler la requête précédente
        if (autocompleteTimeout) {
            clearTimeout(autocompleteTimeout);
        }

        // Masquer si moins de 2 caractères
        if (query.length < 2) {
            hideAutocomplete();
            return;
        }

        // Attendre 300ms avant de faire la requête (debounce)
        autocompleteTimeout = setTimeout(() => {
            fetchAuthorSuggestions(query);
        }, 300);
    });

    // Fermer l'autocomplétion si on clique ailleurs
    document.addEventListener('click', function(e) {
        if (!searchInput.contains(e.target) && !autocompleteContainer.contains(e.target)) {
            hideAutocomplete();
        }
    });

    // Navigation au clavier
    searchInput.addEventListener('keydown', function(e) {
        const items = autocompleteContainer.querySelectorAll('.autocomplete-item');
        const activeItem = autocompleteContainer.querySelector('.autocomplete-item.active');

        if (items.length === 0) return;

        if (e.key === 'ArrowDown') {
            e.preventDefault();
            if (!activeItem) {
                items[0]?.classList.add('active');
            } else {
                const next = activeItem.nextElementSibling;
                activeItem.classList.remove('active');
                if (next) {
                    next.classList.add('active');
                } else {
                    items[0]?.classList.add('active');
                }
            }
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            if (!activeItem) {
                items[items.length - 1]?.classList.add('active');
            } else {
                const prev = activeItem.previousElementSibling;
                activeItem.classList.remove('active');
                if (prev) {
                    prev.classList.add('active');
                } else {
                    items[items.length - 1]?.classList.add('active');
                }
            }
        } else if (e.key === 'Enter' && activeItem) {
            e.preventDefault();
            activeItem.click();
        } else if (e.key === 'Escape') {
            hideAutocomplete();
        }
    });
}

async function fetchAuthorSuggestions(query) {
    const autocompleteContainer = document.getElementById('authorAutocomplete');

    try {
        const response = await fetch(
            `${API_BASE}${ENDPOINTS.autocompleteAuthors}?query=${encodeURIComponent(query)}&limit=8`
        );

        if (!response.ok) {
            console.error('Erreur HTTP autocomplete:', response.status);
            hideAutocomplete();
            return;
        }

        const suggestions = await response.json();
        console.log('Suggestions reçues:', suggestions);
        displayAuthorSuggestions(suggestions);
    } catch (err) {
        console.error('Erreur autocomplétion:', err);
        hideAutocomplete();
    }
}

function displayAuthorSuggestions(suggestions) {
    const autocompleteContainer = document.getElementById('authorAutocomplete');

    if (!suggestions || suggestions.length === 0) {
        hideAutocomplete();
        return;
    }

    let html = '';
    suggestions.forEach(suggestion => {
        // Utiliser le highlight si disponible, sinon l'auteur brut
        const displayText = suggestion.highlight || escapeHtml(suggestion.author);
        const weight = suggestion.weight || 0;

        html += `
            <div class="autocomplete-item" data-author="${escapeHtml(suggestion.author)}">
                <div class="autocomplete-text">${displayText}</div>
                <div class="autocomplete-count">${weight} doc${weight > 1 ? 's' : ''}</div>
            </div>
        `;
    });

    autocompleteContainer.innerHTML = html;
    autocompleteContainer.classList.add('show');

    // Ajouter les événements de clic
    autocompleteContainer.querySelectorAll('.autocomplete-item').forEach(item => {
        item.addEventListener('click', function() {
            const author = this.getAttribute('data-author');
            document.getElementById('filterAuthor').value = author;
            hideAutocomplete();
            // Focus sur le bouton de recherche pour faciliter la soumission
            document.getElementById('searchBtn').focus();
        });

        // Ajouter/enlever la classe active au survol
        item.addEventListener('mouseenter', function() {
            autocompleteContainer.querySelectorAll('.autocomplete-item').forEach(i => {
                i.classList.remove('active');
            });
            this.classList.add('active');
        });
    });
}

function hideAutocomplete() {
    const autocompleteContainer = document.getElementById('authorAutocomplete');
    if (autocompleteContainer) {
        autocompleteContainer.classList.remove('show');
        autocompleteContainer.innerHTML = '';
    }
}

// ==================== UTILITAIRES ====================
function showElement(element) {
    element.classList.add('show');
}

function hideElement(element) {
    element.classList.remove('show');
}

function showError(errorElement, message) {
    errorElement.textContent = message;
    showElement(errorElement);
}

function escapeHtml(text) {
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text.replace(/[&<>"']/g, m => map[m]);
}

