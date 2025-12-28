// Economy Helper - Shared Components

// URL 경로에서 현재 언어 및 깊이 감지
const CURRENT_LANG = window.location.pathname.includes('/kr/') ? 'kr' : 'us';
const IS_RESULTS_PAGE = window.location.pathname.includes('/results/');
const ROOT_PATH = IS_RESULTS_PAGE ? '../../' : '../';
const LANG_PATH = ROOT_PATH + CURRENT_LANG + '/';

// 다국어 설정
const I18N = {
    kr: {
        nav: {
            dictionary: '금융사전',
            personality: '성향 테스트'
        },
        langOptions: [
            { value: 'kr', label: '한국어', selected: true },
            { value: 'us', label: 'English', selected: false }
        ]
    },
    us: {
        nav: {
            dictionary: 'Dictionary',
            personality: 'Personality Test'
        },
        langOptions: [
            { value: 'us', label: 'English', selected: true },
            { value: 'kr', label: '한국어', selected: false }
        ]
    }
};

// 네비게이션 아이템
const NAV_ITEMS = [
    { id: 'dictionary', href: 'index.html' },
    { id: 'personality', href: 'personality-test.html' }
];

// Google AdSense 설정
const ADSENSE_CONFIG = {
    clientId: 'ca-pub-1927828313220344',
    adsbygoogleScript: 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js',
    ampAutoAdsScript: 'https://cdn.ampproject.org/v0/amp-auto-ads-0.1.js'
};

// 언어 변경 시 localStorage에 저장
function switchLanguage(targetLang, targetUrl) {
    localStorage.setItem('preferredLanguage', targetLang);
    window.location.href = targetUrl;
}

// 언어 변경 핸들러
function handleLanguageChange(lang, activePageId) {
    const langPath = ROOT_PATH + lang + '/';
    const targetUrl = activePageId === 'personality'
        ? langPath + 'personality-test.html'
        : langPath + 'index.html';
    switchLanguage(lang, targetUrl);
}

// AdSense 스크립트 삽입
function injectAdSenseScripts() {
    if (document.querySelector('script[src*="pagead2.googlesyndication.com"]')) return;

    const adsbyGoogleScript = document.createElement('script');
    adsbyGoogleScript.async = true;
    adsbyGoogleScript.src = ADSENSE_CONFIG.adsbygoogleScript + '?client=' + ADSENSE_CONFIG.clientId;
    adsbyGoogleScript.crossOrigin = 'anonymous';
    document.head.appendChild(adsbyGoogleScript);

    const ampAutoAdsScript = document.createElement('script');
    ampAutoAdsScript.async = true;
    ampAutoAdsScript.setAttribute('custom-element', 'amp-auto-ads');
    ampAutoAdsScript.src = ADSENSE_CONFIG.ampAutoAdsScript;
    document.head.appendChild(ampAutoAdsScript);
}

// AMP Auto Ads 삽입
function injectAmpAutoAds() {
    if (document.querySelector('amp-auto-ads')) return;

    const ampAutoAds = document.createElement('amp-auto-ads');
    ampAutoAds.setAttribute('type', 'adsense');
    ampAutoAds.setAttribute('data-ad-client', ADSENSE_CONFIG.clientId);

    const header = document.getElementById('header');
    if (header && header.nextSibling) {
        header.parentNode.insertBefore(ampAutoAds, header.nextSibling);
    } else {
        document.body.insertBefore(ampAutoAds, document.body.firstChild);
    }
}

// 공통 스타일 주입
function injectCommonStyles() {
    const stylesheets = [
        { href: 'https://cdn.jsdelivr.net/npm/daisyui@4.4.19/dist/full.min.css' },
        { href: 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap' },
        { href: ROOT_PATH + 'styles.css' }
    ];

    stylesheets.forEach(item => {
        if (!document.querySelector('link[href="' + item.href + '"]')) {
            const link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = item.href;
            link.type = 'text/css';
            document.head.appendChild(link);
        }
    });

    if (!document.querySelector('script[src*="tailwindcss.com"]')) {
        const tailwindScript = document.createElement('script');
        tailwindScript.src = 'https://cdn.tailwindcss.com';
        document.head.appendChild(tailwindScript);
    }
}

// 헤더 렌더링
function renderHeader(activePageId) {
    const header = document.getElementById('header');
    if (!header) return;

    const lang = I18N[CURRENT_LANG];

    // 네비게이션 아이템 생성
    const navItemsDesktop = NAV_ITEMS.map(item => {
        const isActive = item.id === activePageId;
        const activeClass = isActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50';
        const label = lang.nav[item.id];
        return '<li><a href="' + LANG_PATH + item.href + '" class="font-medium ' + activeClass + ' rounded-lg">' + label + '</a></li>';
    }).join('');

    const navItemsMobile = NAV_ITEMS.map(item => {
        const isActive = item.id === activePageId;
        const activeClass = isActive ? 'text-blue-600' : 'text-slate-600';
        const label = lang.nav[item.id];
        return '<li><a href="' + LANG_PATH + item.href + '" class="font-medium ' + activeClass + '">' + label + '</a></li>';
    }).join('');

    header.innerHTML = '<div class="container mx-auto px-4"><div class="navbar min-h-16 p-0"><div class="navbar-start"><a href="' + LANG_PATH + 'index.html" class="flex items-center gap-3 text-xl font-bold text-slate-800 hover:text-blue-600 transition-colors"><img src="' + ROOT_PATH + 'images/logo.png" alt="Economy Helper" class="h-8 w-8 object-contain scale-150"><span class="hidden sm:inline">Economy Helper</span></a></div><div class="navbar-end"><ul class="menu menu-horizontal px-1 hidden md:flex gap-1 items-center">' + navItemsDesktop + '</ul><div class="dropdown dropdown-end md:hidden"><label tabindex="0" class="btn btn-ghost btn-circle"><svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" /></svg></label><ul tabindex="0" class="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">' + navItemsMobile + '</ul></div></div></div></div>';
}

// 푸터 렌더링
function renderFooter() {
    const footer = document.getElementById('footer');
    if (!footer) return;
    footer.innerHTML = '<div class="container mx-auto px-4 py-6"><p class="text-center text-slate-500 text-sm">&copy; ' + new Date().getFullYear() + ' Economy Helper. All rights reserved.</p></div>';
}

// 컴포넌트 초기화
function initComponents(activePageId) {
    injectCommonStyles();
    injectAdSenseScripts();
    injectAmpAutoAds();
    renderHeader(activePageId);
    renderFooter();
}

// === 결과 페이지 공유 기능 ===

// 공유 텍스트 생성 (각 페이지에서 getPersonalityName, getPersonalitySlogan 함수 정의 필요)
function getShareText() {
    const name = typeof getPersonalityName === 'function' ? getPersonalityName() : '';
    const slogan = typeof getPersonalitySlogan === 'function' ? getPersonalitySlogan() : '';
    const prefix = CURRENT_LANG === 'kr' ? '금융 성향 테스트 결과, 나는' : 'My Financial Personality Test Result:';
    return `${prefix} "${name}"! ${slogan}`;
}

// 공유 모달 열기
function shareResults() {
    const shareText = getShareText();
    if (navigator.share) {
        navigator.share({
            title: typeof getPersonalityName === 'function' ? getPersonalityName() : '',
            text: shareText,
            url: window.location.href
        });
    } else {
        const copyText = document.getElementById('copy-link-text');
        if (copyText) copyText.textContent = CURRENT_LANG === 'kr' ? '링크 복사' : 'Copy Link';
        const modal = document.getElementById('share-modal');
        if (modal) modal.showModal();
    }
}

// X(트위터) 공유
function shareToTwitter() {
    const shareText = getShareText();
    const twitterUrl = `https://twitter.com/intent/tweet?text=${encodeURIComponent(shareText)}&url=${encodeURIComponent(window.location.href)}`;
    window.open(twitterUrl, '_blank', 'width=550,height=420');
}

// 페이스북 공유
function shareToFacebook() {
    const facebookUrl = `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(window.location.href)}`;
    window.open(facebookUrl, '_blank', 'width=550,height=420');
}

// 링크 복사
function copyShareLink() {
    const shareText = getShareText();
    const fullText = `${shareText}\n\n${window.location.href}`;
    const copyTextEl = document.getElementById('copy-link-text');
    const copiedText = CURRENT_LANG === 'kr' ? '복사됨!' : 'Copied!';
    const defaultText = CURRENT_LANG === 'kr' ? '링크 복사' : 'Copy Link';

    navigator.clipboard.writeText(fullText).then(() => {
        if (copyTextEl) {
            copyTextEl.textContent = copiedText;
            setTimeout(() => { copyTextEl.textContent = defaultText; }, 2000);
        }
    });
}
