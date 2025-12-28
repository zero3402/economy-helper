// Economy Helper - 공용 컴포넌트 (한국어 버전)

// 언어 설정
const LANG_CONFIG = {
    current: 'ko',
    switchLabel: 'English'
};

// Google AdSense 설정
const ADSENSE_CONFIG = {
    clientId: 'ca-pub-1927828313220344',
    adsbygoogleScript: 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js',
    ampAutoAdsScript: 'https://cdn.ampproject.org/v0/amp-auto-ads-0.1.js'
};

function injectAdSenseScripts() {
    if (document.querySelector('script[src*="pagead2.googlesyndication.com"]')) return;
    var adsbyGoogleScript = document.createElement('script');
    adsbyGoogleScript.async = true;
    adsbyGoogleScript.src = ADSENSE_CONFIG.adsbygoogleScript + '?client=' + ADSENSE_CONFIG.clientId;
    adsbyGoogleScript.crossOrigin = 'anonymous';
    document.head.appendChild(adsbyGoogleScript);
    var ampAutoAdsScript = document.createElement('script');
    ampAutoAdsScript.async = true;
    ampAutoAdsScript.setAttribute('custom-element', 'amp-auto-ads');
    ampAutoAdsScript.src = ADSENSE_CONFIG.ampAutoAdsScript;
    document.head.appendChild(ampAutoAdsScript);
}

function injectAmpAutoAds() {
    if (document.querySelector('amp-auto-ads')) return;
    var ampAutoAds = document.createElement('amp-auto-ads');
    ampAutoAds.setAttribute('type', 'adsense');
    ampAutoAds.setAttribute('data-ad-client', ADSENSE_CONFIG.clientId);
    var header = document.getElementById('header');
    if (header && header.nextSibling) {
        header.parentNode.insertBefore(ampAutoAds, header.nextSibling);
    } else {
        document.body.insertBefore(ampAutoAds, document.body.firstChild);
    }
}

function injectCommonStyles(basePath) {
    basePath = basePath || '';
    var stylesheets = [
        { href: 'https://cdn.jsdelivr.net/npm/daisyui@4.4.19/dist/full.min.css', rel: 'stylesheet' },
        { href: 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap', rel: 'stylesheet' },
        { href: basePath + 'styles.css', rel: 'stylesheet' }
    ];
    stylesheets.forEach(function(item) {
        if (!document.querySelector('link[href="' + item.href + '"]')) {
            var link = document.createElement('link');
            link.rel = item.rel;
            link.href = item.href;
            link.type = 'text/css';
            document.head.appendChild(link);
        }
    });
    if (!document.querySelector('script[src*="tailwindcss.com"]')) {
        var tailwindScript = document.createElement('script');
        tailwindScript.src = 'https://cdn.tailwindcss.com';
        document.head.appendChild(tailwindScript);
    }
}

var NAV_ITEMS = [
    { id: 'dictionary', label: '금융사전', href: 'index.html' },
    { id: 'personality', label: '성격테스트', href: 'personality-test.html' }
];

function renderHeader(activePageId, basePath) {
    basePath = basePath || '';
    var header = document.getElementById('header');
    if (!header) return;

    var imgBasePath = '../';
    var langSwitchUrl = '../us/index.html';
    if (activePageId === 'personality') langSwitchUrl = '../us/personality-test.html';

    var navItemsDesktop = NAV_ITEMS.map(function(item) {
        var isActive = item.id === activePageId;
        var activeClass = isActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50';
        return '<li><a href="' + basePath + item.href + '" class="font-medium ' + activeClass + ' rounded-lg">' + item.label + '</a></li>';
    }).join('');

    var navItemsMobile = NAV_ITEMS.map(function(item) {
        var isActive = item.id === activePageId;
        var activeClass = isActive ? 'text-blue-600' : 'text-slate-600';
        return '<li><a href="' + basePath + item.href + '" class="font-medium ' + activeClass + '">' + item.label + '</a></li>';
    }).join('');

    header.innerHTML = '<div class="container mx-auto px-4"><div class="navbar min-h-16 p-0"><div class="navbar-start"><a href="' + basePath + 'index.html" class="flex items-center gap-3 text-xl font-bold text-slate-800 hover:text-blue-600 transition-colors"><img src="' + imgBasePath + 'images/logo.png" alt="Economy Helper" class="h-8 w-8 object-contain scale-150"><span class="hidden sm:inline">Economy Helper</span></a></div><div class="navbar-end"><ul class="menu menu-horizontal px-1 hidden md:flex gap-1">' + navItemsDesktop + '<li><a href="' + langSwitchUrl + '" class="font-medium text-slate-600 hover:text-blue-600 hover:bg-blue-50 rounded-lg">' + LANG_CONFIG.switchLabel + '</a></li></ul><div class="dropdown dropdown-end md:hidden"><label tabindex="0" class="btn btn-ghost btn-circle"><svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" /></svg></label><ul tabindex="0" class="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">' + navItemsMobile + '<li><a href="' + langSwitchUrl + '" class="font-medium text-slate-600">' + LANG_CONFIG.switchLabel + '</a></li></ul></div></div></div></div>';
}

function renderFooter() {
    var footer = document.getElementById('footer');
    if (!footer) return;
    footer.innerHTML = '<div class="container mx-auto px-4 py-6"><p class="text-center text-slate-500 text-sm">&copy; ' + new Date().getFullYear() + ' Economy Helper. All rights reserved.</p></div>';
}

function initComponents(activePageId, options) {
    options = options || {};
    var basePath = options.basePath || '';
    injectCommonStyles(basePath);
    injectAdSenseScripts();
    injectAmpAutoAds();
    renderHeader(activePageId, basePath);
    renderFooter();
}

function initAds(basePath) {
    basePath = basePath || '';
    injectCommonStyles(basePath);
    injectAdSenseScripts();
    injectAmpAutoAds();
}
