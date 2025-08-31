// EasyCTF Documentation JavaScript

// Language Management
let currentLang = localStorage.getItem('ctf-lang') || 'ja';

// Translations
const translations = {
    ja: {
        'nav.home': 'ホーム',
        'nav.gameplay': 'ゲームプレイ',
        'nav.commands': 'コマンド',
        'nav.shop': 'ショップ',
        'nav.match': 'マッチ',
        'nav.admin': '管理者ガイド',
        'nav.faq': 'FAQ',
        'sidebar.menu': 'メニュー',
        'search.placeholder': '検索...',
        'copy': 'コピー',
        'copied': 'コピーしました！'
    },
    en: {
        'nav.home': 'Home',
        'nav.gameplay': 'Gameplay',
        'nav.commands': 'Commands',
        'nav.shop': 'Shop',
        'nav.match': 'Match',
        'nav.admin': 'Admin Guide',
        'nav.faq': 'FAQ',
        'sidebar.menu': 'Menu',
        'search.placeholder': 'Search...',
        'copy': 'Copy',
        'copied': 'Copied!'
    }
};

// Initialize
document.addEventListener('DOMContentLoaded', function() {
    initializeSidebar();
    initializeLanguage();
    initializeSearch();
    initializeCopyButtons();
    initializeTooltips();
    initializeScrollSpy();
    initializeTheme();
});

// Sidebar Toggle
function initializeSidebar() {
    const sidebar = document.querySelector('.sidebar');
    const sidebarToggle = document.querySelector('.sidebar-toggle');
    const mainContent = document.querySelector('.main-content');
    
    if (sidebarToggle) {
        sidebarToggle.addEventListener('click', function() {
            sidebar.classList.toggle('active');
            mainContent.classList.toggle('full-width');
            
            // Save state
            const isActive = sidebar.classList.contains('active');
            localStorage.setItem('sidebar-state', isActive ? 'active' : 'collapsed');
        });
        
        // Restore state
        const savedState = localStorage.getItem('sidebar-state');
        if (savedState === 'active' && window.innerWidth <= 1024) {
            sidebar.classList.add('active');
        }
    }
    
    // Submenu handling
    const menuItems = document.querySelectorAll('.has-submenu > a');
    menuItems.forEach(item => {
        item.addEventListener('click', function(e) {
            e.preventDefault();
            const submenu = this.nextElementSibling;
            if (submenu) {
                submenu.classList.toggle('active');
                this.classList.toggle('expanded');
            }
        });
    });
    
    // Active link highlighting
    const currentPath = window.location.pathname.split('/').pop() || 'index.html';
    const links = document.querySelectorAll('.sidebar nav a');
    links.forEach(link => {
        if (link.getAttribute('href') === currentPath) {
            link.classList.add('active');
            // Expand parent submenu if exists
            const parentSubmenu = link.closest('.submenu');
            if (parentSubmenu) {
                parentSubmenu.classList.add('active');
                parentSubmenu.previousElementSibling.classList.add('expanded');
            }
        }
    });
}

// Language Switching
function initializeLanguage() {
    const langButtons = document.querySelectorAll('.language-switch button');
    
    // Set active language button
    langButtons.forEach(btn => {
        if (btn.dataset.lang === currentLang) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
        
        btn.addEventListener('click', function(e) {
            e.preventDefault();
            currentLang = this.dataset.lang;
            localStorage.setItem('ctf-lang', currentLang);
            
            // Update active button
            langButtons.forEach(b => b.classList.remove('active'));
            this.classList.add('active');
            
            // Update language display
            updateLanguage();
        });
    });
    
    // Initial language update
    updateLanguage();
}

function updateLanguage() {
    console.log('Updating language to:', currentLang);
    
    // Update content sections with data-lang attribute
    const langElements = document.querySelectorAll('div[data-lang]');
    
    langElements.forEach(el => {
        if (el.dataset.lang === currentLang) {
            el.style.display = '';  // Use empty string to restore default display
        } else {
            el.style.display = 'none';
        }
    });
    
    // Update navigation and UI text with data-i18n attribute
    const i18nElements = document.querySelectorAll('[data-i18n]');
    console.log('Found', i18nElements.length, 'elements with data-i18n');
    
    i18nElements.forEach(el => {
        const key = el.dataset.i18n;
        // Since our translations object uses flat keys like 'nav.home', we directly access them
        const translation = translations[currentLang][key];
        
        console.log('Translating', key, ':', translation);
        
        if (translation) {
            el.textContent = translation;
        }
    });
}

// Search Functionality
function initializeSearch() {
    const searchInput = document.querySelector('.search-input');
    if (!searchInput) return;
    
    searchInput.addEventListener('input', function(e) {
        const searchTerm = e.target.value.toLowerCase();
        const sections = document.querySelectorAll('.content-section');
        
        sections.forEach(section => {
            const text = section.textContent.toLowerCase();
            if (text.includes(searchTerm)) {
                section.style.display = '';
                // Highlight search term
                if (searchTerm) {
                    highlightText(section, searchTerm);
                } else {
                    removeHighlight(section);
                }
            } else {
                section.style.display = 'none';
            }
        });
        
        // Show no results message
        const visibleSections = document.querySelectorAll('.content-section:not([style*="display: none"])');
        const noResults = document.querySelector('.no-results');
        if (visibleSections.length === 0 && searchTerm) {
            if (!noResults) {
                const msg = document.createElement('div');
                msg.className = 'alert alert-info no-results';
                msg.textContent = currentLang === 'ja' ? '検索結果が見つかりませんでした。' : 'No results found.';
                document.querySelector('.main-content').appendChild(msg);
            }
        } else if (noResults) {
            noResults.remove();
        }
    });
}

function highlightText(element, searchTerm) {
    const walker = document.createTreeWalker(
        element,
        NodeFilter.SHOW_TEXT,
        null,
        false
    );
    
    const textNodes = [];
    let node;
    while (node = walker.nextNode()) {
        textNodes.push(node);
    }
    
    textNodes.forEach(textNode => {
        const text = textNode.textContent;
        const regex = new RegExp(`(${searchTerm})`, 'gi');
        if (regex.test(text)) {
            const span = document.createElement('span');
            span.innerHTML = text.replace(regex, '<mark>$1</mark>');
            textNode.parentNode.replaceChild(span, textNode);
        }
    });
}

function removeHighlight(element) {
    const marks = element.querySelectorAll('mark');
    marks.forEach(mark => {
        const parent = mark.parentNode;
        parent.replaceChild(document.createTextNode(mark.textContent), mark);
        parent.normalize();
    });
}

// Copy to Clipboard
function initializeCopyButtons() {
    const commandBoxes = document.querySelectorAll('.command-box');
    
    commandBoxes.forEach(box => {
        const button = document.createElement('button');
        button.className = 'copy-btn';
        button.textContent = 'Copy';
        button.addEventListener('click', function() {
            const text = box.textContent.replace('$ ', '').replace('Copy', '').trim();
            copyToClipboard(text);
            
            // Show feedback
            button.textContent = 'Copied!';
            button.style.background = '#27ae60';
            setTimeout(() => {
                button.textContent = 'Copy';
                button.style.background = '';
            }, 2000);
        });
        box.appendChild(button);
    });
}

function copyToClipboard(text) {
    if (navigator.clipboard) {
        navigator.clipboard.writeText(text);
    } else {
        // Fallback for older browsers
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
    }
}

// Tooltips
function initializeTooltips() {
    const tooltips = document.querySelectorAll('[data-tooltip]');
    
    tooltips.forEach(element => {
        const tooltipText = element.dataset.tooltip;
        const tooltip = document.createElement('span');
        tooltip.className = 'tooltiptext';
        tooltip.textContent = tooltipText;
        element.classList.add('tooltip');
        element.appendChild(tooltip);
    });
}

// Scroll Spy
function initializeScrollSpy() {
    const sections = document.querySelectorAll('section[id]');
    const navLinks = document.querySelectorAll('.sidebar nav a[href^="#"]');
    
    if (sections.length === 0 || navLinks.length === 0) return;
    
    window.addEventListener('scroll', () => {
        let current = '';
        
        sections.forEach(section => {
            const sectionTop = section.offsetTop;
            const sectionHeight = section.clientHeight;
            if (window.pageYOffset >= sectionTop - 200) {
                current = section.getAttribute('id');
            }
        });
        
        navLinks.forEach(link => {
            link.classList.remove('active');
            if (link.getAttribute('href') === `#${current}`) {
                link.classList.add('active');
            }
        });
    });
}

// Theme Toggle (Light/Dark)
function initializeTheme() {
    const savedTheme = localStorage.getItem('ctf-theme') || 'light';
    document.body.dataset.theme = savedTheme;
    
    // Create theme toggle button if not exists
    if (!document.querySelector('.theme-toggle')) {
        const themeToggle = document.createElement('button');
        themeToggle.className = 'theme-toggle';
        themeToggle.innerHTML = savedTheme === 'light' ? '🌙' : '☀️';
        themeToggle.addEventListener('click', function() {
            const currentTheme = document.body.dataset.theme;
            const newTheme = currentTheme === 'light' ? 'dark' : 'light';
            document.body.dataset.theme = newTheme;
            localStorage.setItem('ctf-theme', newTheme);
            this.innerHTML = newTheme === 'light' ? '🌙' : '☀️';
        });
        document.querySelector('.header').appendChild(themeToggle);
    }
}

// Smooth Scrolling
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function(e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            target.scrollIntoView({
                behavior: 'smooth',
                block: 'start'
            });
        }
    });
});

// Tab Functionality
function initializeTabs() {
    const tabContainers = document.querySelectorAll('.tab-container');
    
    tabContainers.forEach(container => {
        const tabs = container.querySelectorAll('.tab');
        const contents = container.querySelectorAll('.tab-content');
        
        tabs.forEach((tab, index) => {
            tab.addEventListener('click', () => {
                // Remove active class from all tabs and contents
                tabs.forEach(t => t.classList.remove('active'));
                contents.forEach(c => c.classList.remove('active'));
                
                // Add active class to clicked tab and corresponding content
                tab.classList.add('active');
                if (contents[index]) {
                    contents[index].classList.add('active');
                }
            });
        });
    });
}

// Export functions for use in HTML
window.ctfDocs = {
    switchLanguage: (lang) => {
        currentLang = lang;
        localStorage.setItem('ctf-lang', lang);
        updateLanguage();
    },
    toggleSidebar: () => {
        document.querySelector('.sidebar').classList.toggle('active');
    },
    copyCommand: (text) => {
        copyToClipboard(text);
    }
};