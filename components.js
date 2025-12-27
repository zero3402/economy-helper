// Economy Helper - Shared Components

// Google AdSense Configuration
const ADSENSE_CONFIG = {
    clientId: 'ca-pub-1927828313220344',
    adsbygoogleScript: 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js',
    ampAutoAdsScript: 'https://cdn.ampproject.org/v0/amp-auto-ads-0.1.js'
};

// Inject Google AdSense Scripts into head
function injectAdSenseScripts() {
    // Check if scripts already exist
    if (document.querySelector('script[src*="pagead2.googlesyndication.com"]')) {
        return;
    }

    // Create and inject adsbygoogle script
    const adsbyGoogleScript = document.createElement('script');
    adsbyGoogleScript.async = true;
    adsbyGoogleScript.src = `${ADSENSE_CONFIG.adsbygoogleScript}?client=${ADSENSE_CONFIG.clientId}`;
    adsbyGoogleScript.crossOrigin = 'anonymous';
    document.head.appendChild(adsbyGoogleScript);

    // Create and inject amp-auto-ads script
    const ampAutoAdsScript = document.createElement('script');
    ampAutoAdsScript.async = true;
    ampAutoAdsScript.setAttribute('custom-element', 'amp-auto-ads');
    ampAutoAdsScript.src = ADSENSE_CONFIG.ampAutoAdsScript;
    document.head.appendChild(ampAutoAdsScript);
}

// Inject amp-auto-ads tag into body (after header)
function injectAmpAutoAds() {
    // Check if amp-auto-ads already exists
    if (document.querySelector('amp-auto-ads')) {
        return;
    }

    const ampAutoAds = document.createElement('amp-auto-ads');
    ampAutoAds.setAttribute('type', 'adsense');
    ampAutoAds.setAttribute('data-ad-client', ADSENSE_CONFIG.clientId);

    // Insert after header or at the beginning of body
    const header = document.getElementById('header');
    if (header && header.nextSibling) {
        header.parentNode.insertBefore(ampAutoAds, header.nextSibling);
    } else {
        document.body.insertBefore(ampAutoAds, document.body.firstChild);
    }
}

// Inject common stylesheets
function injectCommonStyles(basePath = '') {
    const stylesheets = [
        { href: 'https://cdn.jsdelivr.net/npm/daisyui@4.4.19/dist/full.min.css', rel: 'stylesheet' },
        { href: 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap', rel: 'stylesheet' },
        { href: `${basePath}styles.css`, rel: 'stylesheet' }
    ];

    const preconnects = [
        { href: 'https://fonts.googleapis.com', rel: 'preconnect' },
        { href: 'https://fonts.gstatic.com', rel: 'preconnect', crossorigin: true }
    ];

    // Add preconnects
    preconnects.forEach(item => {
        if (!document.querySelector(`link[href="${item.href}"][rel="preconnect"]`)) {
            const link = document.createElement('link');
            link.rel = item.rel;
            link.href = item.href;
            if (item.crossorigin) link.crossOrigin = '';
            document.head.appendChild(link);
        }
    });

    // Add stylesheets
    stylesheets.forEach(item => {
        if (!document.querySelector(`link[href="${item.href}"]`)) {
            const link = document.createElement('link');
            link.rel = item.rel;
            link.href = item.href;
            link.type = 'text/css';
            document.head.appendChild(link);
        }
    });

    // Add Tailwind CSS script if not exists
    if (!document.querySelector('script[src*="tailwindcss.com"]')) {
        const tailwindScript = document.createElement('script');
        tailwindScript.src = 'https://cdn.tailwindcss.com';
        document.head.appendChild(tailwindScript);
    }
}

const NAV_ITEMS = [
    { id: 'dictionary', label: 'Dictionary', href: 'index.html' },
    { id: 'personality', label: 'Personality Test', href: 'personality-test.html' },
    { id: 'salary', label: 'Salary Calculator', href: 'salary-calculator.html' }
];

// Render Header Component
function renderHeader(activePageId, basePath = '') {
    const header = document.getElementById('header');
    if (!header) return;

    const navItemsDesktop = NAV_ITEMS.map(item => {
        const isActive = item.id === activePageId;
        const activeClass = isActive
            ? 'text-blue-600 bg-blue-50'
            : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50';
        return `<li><a href="${basePath}${item.href}" class="font-medium ${activeClass} rounded-lg">${item.label}</a></li>`;
    }).join('');

    const navItemsMobile = NAV_ITEMS.map(item => {
        const isActive = item.id === activePageId;
        const activeClass = isActive ? 'text-blue-600' : 'text-slate-600';
        return `<li><a href="${basePath}${item.href}" class="font-medium ${activeClass}">${item.label}</a></li>`;
    }).join('');

    header.innerHTML = `
        <div class="container mx-auto px-4">
            <div class="navbar min-h-16 p-0">
                <div class="navbar-start">
                    <a href="${basePath}index.html" class="flex items-center gap-3 text-xl font-bold text-slate-800 hover:text-blue-600 transition-colors">
                        <img src="${basePath}images/logo.png" alt="Economy Helper" class="h-8 w-8 object-contain scale-150">
                        <span class="hidden sm:inline">Economy Helper</span>
                    </a>
                </div>
                <div class="navbar-end">
                    <!-- Desktop Menu -->
                    <ul class="menu menu-horizontal px-1 hidden md:flex gap-1">
                        ${navItemsDesktop}
                    </ul>
                    <!-- Mobile Menu -->
                    <div class="dropdown dropdown-end md:hidden">
                        <label tabindex="0" class="btn btn-ghost btn-circle">
                            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
                            </svg>
                        </label>
                        <ul tabindex="0" class="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">
                            ${navItemsMobile}
                        </ul>
                    </div>
                </div>
            </div>
        </div>
    `;
}

// Render Footer Component
function renderFooter() {
    const footer = document.getElementById('footer');
    if (!footer) return;

    footer.innerHTML = `
        <div class="container mx-auto px-4 py-6">
            <p class="text-center text-slate-500 text-sm">
                &copy; ${new Date().getFullYear()} Economy Helper. All rights reserved.
            </p>
        </div>
    `;
}

// Initialize Components
function initComponents(activePageId, options = {}) {
    const basePath = options.basePath || '';

    // Inject common styles
    injectCommonStyles(basePath);

    // Inject AdSense scripts
    injectAdSenseScripts();

    // Inject amp-auto-ads tag
    injectAmpAutoAds();

    // Render header and footer
    renderHeader(activePageId, basePath);
    renderFooter();
}

// Initialize only ads (for pages that don't use header/footer)
function initAds(basePath = '') {
    injectCommonStyles(basePath);
    injectAdSenseScripts();
    injectAmpAutoAds();
}
