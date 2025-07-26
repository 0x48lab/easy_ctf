// ナビゲーションのトグル
document.addEventListener('DOMContentLoaded', function() {
    const navToggle = document.querySelector('.nav-toggle');
    const navMenu = document.querySelector('.nav-menu');
    
    if (navToggle) {
        navToggle.addEventListener('click', function() {
            navMenu.classList.toggle('active');
        });
    }
    
    // ページ外クリックでメニューを閉じる
    document.addEventListener('click', function(e) {
        if (!e.target.closest('.navbar')) {
            navMenu.classList.remove('active');
        }
    });
});

// スムーススクロール
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            const offset = 80; // ナビゲーションの高さ分
            const targetPosition = target.offsetTop - offset;
            window.scrollTo({
                top: targetPosition,
                behavior: 'smooth'
            });
        }
    });
});

// コマンドコピー機能
function setupCopyButtons() {
    const copyButtons = document.querySelectorAll('.copy-btn');
    
    copyButtons.forEach(button => {
        button.addEventListener('click', function() {
            const commandBlock = this.closest('.command-block');
            const command = commandBlock.querySelector('code').textContent;
            
            navigator.clipboard.writeText(command).then(() => {
                const originalText = this.textContent;
                this.textContent = 'コピーしました！';
                this.style.backgroundColor = '#27ae60';
                
                setTimeout(() => {
                    this.textContent = originalText;
                    this.style.backgroundColor = '';
                }, 2000);
            }).catch(err => {
                console.error('コピーに失敗しました:', err);
            });
        });
    });
}

// ページ読み込み時のアニメーション
function setupScrollAnimations() {
    const observerOptions = {
        threshold: 0.1,
        rootMargin: '0px 0px -50px 0px'
    };
    
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('fade-in');
                observer.unobserve(entry.target);
            }
        });
    }, observerOptions);
    
    // アニメーション対象の要素を監視
    const animateElements = document.querySelectorAll('.feature-card, .step, .phase, .tip-card');
    animateElements.forEach(el => {
        observer.observe(el);
    });
}

// アコーディオン機能（FAQ用）
function setupAccordions() {
    const accordionHeaders = document.querySelectorAll('.accordion-header');
    
    accordionHeaders.forEach(header => {
        header.addEventListener('click', function() {
            const accordionItem = this.parentElement;
            const accordionContent = accordionItem.querySelector('.accordion-content');
            const isOpen = accordionItem.classList.contains('active');
            
            // 他のアコーディオンを閉じる
            document.querySelectorAll('.accordion-item').forEach(item => {
                item.classList.remove('active');
                item.querySelector('.accordion-content').style.maxHeight = null;
            });
            
            // クリックされたアコーディオンをトグル
            if (!isOpen) {
                accordionItem.classList.add('active');
                accordionContent.style.maxHeight = accordionContent.scrollHeight + 'px';
            }
        });
    });
}

// テーブルのソート機能
function setupTableSort() {
    const sortableTables = document.querySelectorAll('.sortable');
    
    sortableTables.forEach(table => {
        const headers = table.querySelectorAll('th');
        
        headers.forEach((header, index) => {
            header.style.cursor = 'pointer';
            header.addEventListener('click', function() {
                sortTable(table, index);
            });
        });
    });
}

function sortTable(table, columnIndex) {
    const tbody = table.querySelector('tbody');
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const isNumeric = rows.every(row => {
        const cell = row.cells[columnIndex];
        return !isNaN(parseFloat(cell.textContent));
    });
    
    rows.sort((a, b) => {
        const aValue = a.cells[columnIndex].textContent;
        const bValue = b.cells[columnIndex].textContent;
        
        if (isNumeric) {
            return parseFloat(aValue) - parseFloat(bValue);
        } else {
            return aValue.localeCompare(bValue);
        }
    });
    
    // 現在の順序を反転
    const currentOrder = table.dataset.sortOrder === 'asc' ? 'desc' : 'asc';
    table.dataset.sortOrder = currentOrder;
    
    if (currentOrder === 'desc') {
        rows.reverse();
    }
    
    // テーブルに行を再追加
    rows.forEach(row => tbody.appendChild(row));
}

// 検索機能
function setupSearch() {
    const searchInput = document.getElementById('search-input');
    const searchResults = document.getElementById('search-results');
    
    if (!searchInput || !searchResults) return;
    
    searchInput.addEventListener('input', function() {
        const query = this.value.toLowerCase();
        
        if (query.length < 2) {
            searchResults.innerHTML = '';
            return;
        }
        
        // 検索処理（実際の実装では、ページ内容から検索）
        const results = searchContent(query);
        displaySearchResults(results);
    });
}

function searchContent(query) {
    // この関数は実際のコンテンツ検索ロジックを実装
    // ここでは仮の実装
    const allContent = document.querySelectorAll('h1, h2, h3, p, li');
    const results = [];
    
    allContent.forEach(element => {
        if (element.textContent.toLowerCase().includes(query)) {
            results.push({
                title: element.tagName === 'P' || element.tagName === 'LI' 
                    ? element.textContent.substring(0, 50) + '...' 
                    : element.textContent,
                element: element
            });
        }
    });
    
    return results.slice(0, 10); // 最大10件
}

function displaySearchResults(results) {
    const searchResults = document.getElementById('search-results');
    
    if (results.length === 0) {
        searchResults.innerHTML = '<p>検索結果が見つかりませんでした。</p>';
        return;
    }
    
    const html = results.map(result => `
        <div class="search-result-item" data-element-id="${result.element.id || ''}">
            <h4>${result.title}</h4>
        </div>
    `).join('');
    
    searchResults.innerHTML = html;
    
    // 結果クリックでスクロール
    searchResults.querySelectorAll('.search-result-item').forEach((item, index) => {
        item.addEventListener('click', () => {
            results[index].element.scrollIntoView({ behavior: 'smooth', block: 'center' });
            results[index].element.style.backgroundColor = '#ffeb3b';
            setTimeout(() => {
                results[index].element.style.backgroundColor = '';
            }, 2000);
        });
    });
}

// ダークモード切り替え
function setupDarkMode() {
    const darkModeToggle = document.getElementById('dark-mode-toggle');
    const body = document.body;
    
    // 保存された設定を読み込み
    const isDarkMode = localStorage.getItem('darkMode') === 'true';
    if (isDarkMode) {
        body.classList.add('dark-mode');
    }
    
    if (darkModeToggle) {
        darkModeToggle.addEventListener('click', () => {
            body.classList.toggle('dark-mode');
            const isDark = body.classList.contains('dark-mode');
            localStorage.setItem('darkMode', isDark);
            
            // アイコンを変更
            const icon = darkModeToggle.querySelector('i');
            icon.className = isDark ? 'fas fa-sun' : 'fas fa-moon';
        });
    }
}

// 初期化
document.addEventListener('DOMContentLoaded', function() {
    setupCopyButtons();
    setupScrollAnimations();
    setupAccordions();
    setupTableSort();
    setupSearch();
    setupDarkMode();
});

// ページトップへ戻るボタン
const backToTopButton = document.createElement('button');
backToTopButton.innerHTML = '<i class="fas fa-arrow-up"></i>';
backToTopButton.className = 'back-to-top';
backToTopButton.style.cssText = `
    position: fixed;
    bottom: 20px;
    right: 20px;
    background-color: var(--primary-color);
    color: white;
    border: none;
    border-radius: 50%;
    width: 50px;
    height: 50px;
    font-size: 20px;
    cursor: pointer;
    display: none;
    z-index: 999;
    transition: all 0.3s ease;
`;

document.body.appendChild(backToTopButton);

window.addEventListener('scroll', () => {
    if (window.pageYOffset > 300) {
        backToTopButton.style.display = 'block';
    } else {
        backToTopButton.style.display = 'none';
    }
});

backToTopButton.addEventListener('click', () => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
});